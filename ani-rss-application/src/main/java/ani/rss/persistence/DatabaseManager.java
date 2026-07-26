package ani.rss.persistence;

import ani.rss.util.other.ConfigUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private static final Object LOCK = new Object();
    private static Connection connection;
    private static Path openedPath;

    private DatabaseManager() {
    }

    public static <T> T withConnection(SqlFunction<Connection, T> operation) {
        synchronized (LOCK) {
            try {
                return operation.apply(connection());
            } catch (SQLException e) {
                throw new IllegalStateException("数据库操作失败", e);
            }
        }
    }

    public static <T> T transaction(SqlFunction<Connection, T> operation) {
        synchronized (LOCK) {
            Connection current = connection();
            boolean previousAutoCommit;
            try {
                previousAutoCommit = current.getAutoCommit();
                current.setAutoCommit(false);
                T result = operation.apply(current);
                current.commit();
                current.setAutoCommit(previousAutoCommit);
                return result;
            } catch (Exception e) {
                try {
                    current.rollback();
                    current.setAutoCommit(true);
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("数据库事务失败", e);
            }
        }
    }

    public static void close() {
        synchronized (LOCK) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    throw new IllegalStateException("关闭数据库失败", e);
                } finally {
                    connection = null;
                    openedPath = null;
                }
            }
        }
    }

    public static void reopen() {
        synchronized (LOCK) {
            close();
            connection();
        }
    }

    public static boolean integrityCheck() {
        return withConnection(current -> {
            try (Statement statement = current.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
                return resultSet.next() && "ok".equalsIgnoreCase(resultSet.getString(1));
            }
        });
    }

    public static boolean integrityCheck(Path databasePath) {
        Path normalized = databasePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            return false;
        }
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection checkConnection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
                 Statement statement = checkConnection.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
                return resultSet.next() && "ok".equalsIgnoreCase(resultSet.getString(1));
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Creates a consistent online snapshot without closing the active connection. */
    public static void backupTo(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        synchronized (LOCK) {
            if (Files.exists(normalized)) {
                throw new IllegalArgumentException("database snapshot target already exists");
            }
            try {
                Files.createDirectories(normalized.getParent());
                String escaped = normalized.toString().replace("'", "''");
                try (Statement statement = connection().createStatement()) {
                    statement.executeUpdate("VACUUM INTO '" + escaped + "'");
                }
                if (!integrityCheck(normalized)) {
                    throw new IllegalStateException("database snapshot integrity check failed");
                }
            } catch (Exception e) {
                throw new IllegalStateException("create database snapshot failed", e);
            }
        }
    }

    public static Path path() {
        return ConfigUtil.getConfigDir().toPath()
                .toAbsolutePath()
                .normalize()
                .resolve("database.db");
    }

    private static Connection connection() {
        try {
            Path expectedPath = path();
            if (connection != null && !connection.isClosed() && expectedPath.equals(openedPath)) {
                return connection;
            }
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            Files.createDirectories(expectedPath.getParent());
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + expectedPath);
            openedPath = expectedPath;
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            migrate(connection);
            return connection;
        } catch (Exception e) {
            connection = null;
            openedPath = null;
            throw new IllegalStateException("打开数据库失败", e);
        }
    }

    private static void migrate(Connection current) throws SQLException {
        try (Statement statement = current.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    )
                    """);
        }
        int version = 0;
        try (Statement statement = current.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            if (resultSet.next()) {
                version = resultSet.getInt(1);
            }
        }
        if (version < 1) {
            boolean autoCommit = current.getAutoCommit();
            current.setAutoCommit(false);
            try (Statement statement = current.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS RENAME_CACHES (K TEXT PRIMARY KEY, V TEXT NOT NULL)");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS download_ownership (
                            ownership_id TEXT PRIMARY KEY,
                            downloader_type TEXT NOT NULL,
                            remote_task_id TEXT,
                            info_hash TEXT NOT NULL,
                            subscription_id TEXT NOT NULL,
                            season INTEGER,
                            episode TEXT,
                            save_root TEXT NOT NULL,
                            state TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            UNIQUE(downloader_type, info_hash)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS owned_files (
                            ownership_id TEXT NOT NULL,
                            relative_path TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            size INTEGER,
                            PRIMARY KEY (ownership_id, relative_path),
                            FOREIGN KEY (ownership_id) REFERENCES download_ownership(ownership_id) ON DELETE CASCADE
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS quarantine_entries (
                            entry_id TEXT PRIMARY KEY,
                            operation_id TEXT NOT NULL,
                            ownership_id TEXT NOT NULL,
                            original_path TEXT NOT NULL,
                            quarantine_path TEXT NOT NULL,
                            purge_after INTEGER NOT NULL,
                            state TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            FOREIGN KEY (ownership_id) REFERENCES download_ownership(ownership_id) ON DELETE RESTRICT
                        )
                        """);
                statement.executeUpdate("INSERT INTO schema_migrations(version, applied_at) VALUES (1, "
                        + System.currentTimeMillis() + ")");
                current.commit();
            } catch (SQLException e) {
                current.rollback();
                throw e;
            } finally {
                current.setAutoCommit(autoCommit);
            }
        }
    }

    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T value) throws SQLException;
    }
}
