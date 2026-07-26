package ani.rss.persistence;

import ani.rss.util.other.ConfigUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private static final Object LOCK = new Object();
    private static volatile Connection connection;
    private static volatile Path openedPath;

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
        } catch (ClassNotFoundException | SQLException e) {
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
                Path parent = normalized.getParent();
                if (parent == null) {
                    throw new IllegalArgumentException("database snapshot target must have a parent directory");
                }
                Files.createDirectories(parent);
                try (PreparedStatement statement = connection().prepareStatement("VACUUM INTO ?")) {
                    statement.setString(1, normalized.toString());
                    statement.executeUpdate();
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
        synchronized (LOCK) {
            try {
            Path expectedPath = path();
            if (connection != null && !connection.isClosed() && expectedPath.equals(openedPath)) {
                return connection;
            }
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            Path parent = expectedPath.getParent();
            if (parent == null) {
                throw new IllegalStateException("database path must have a parent directory");
            }
            Files.createDirectories(parent);
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
        if (version < 2) {
            boolean autoCommit = current.getAutoCommit();
            current.setAutoCommit(false);
            try (Statement statement = current.createStatement()) {
                statement.executeUpdate("ALTER TABLE quarantine_entries ADD COLUMN previous_state TEXT");
                statement.executeUpdate("INSERT INTO schema_migrations(version, applied_at) VALUES (2, "
                        + System.currentTimeMillis() + ")");
                current.commit();
            } catch (SQLException e) {
                current.rollback();
                throw e;
            } finally {
                current.setAutoCommit(autoCommit);
            }
        }
        if (version < 3) {
            boolean autoCommit = current.getAutoCommit();
            current.setAutoCommit(false);
            try (Statement statement = current.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS missing_episode_recovery (
                            recovery_id TEXT PRIMARY KEY,
                            subscription_id TEXT NOT NULL,
                            info_hash TEXT NOT NULL,
                            season INTEGER,
                            episode TEXT,
                            item_json TEXT NOT NULL,
                            state TEXT NOT NULL,
                            attempts INTEGER NOT NULL,
                            next_attempt_at INTEGER NOT NULL,
                            last_error_code TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            UNIQUE(subscription_id, info_hash)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_missing_episode_recovery_due
                        ON missing_episode_recovery(subscription_id, state, next_attempt_at)
                        """);
                statement.executeUpdate("INSERT INTO schema_migrations(version, applied_at) VALUES (3, "
                        + System.currentTimeMillis() + ")");
                current.commit();
            } catch (SQLException e) {
                current.rollback();
                throw e;
            } finally {
                current.setAutoCommit(autoCommit);
            }
        }
        if (version < 4) {
            boolean autoCommit = current.getAutoCommit();
            current.setAutoCommit(false);
            try (Statement statement = current.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS completed_migration_finalization (
                            subscription_id TEXT PRIMARY KEY,
                            subscription_fingerprint TEXT NOT NULL,
                            target_root TEXT NOT NULL,
                            state TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("INSERT INTO schema_migrations(version, applied_at) VALUES (4, "
                        + System.currentTimeMillis() + ")");
                current.commit();
            } catch (SQLException e) {
                current.rollback();
                throw e;
            } finally {
                current.setAutoCommit(autoCommit);
            }
        }
        if (version < 5) {
            boolean autoCommit = current.getAutoCommit();
            current.setAutoCommit(false);
            try (Statement statement = current.createStatement()) {
                if (!hasColumn(current, "missing_episode_recovery", "source_hash")) {
                    statement.executeUpdate("ALTER TABLE missing_episode_recovery ADD COLUMN source_hash TEXT");
                }
                // Existing records predate canonical torrent hash resolution.
                // Their prior key is also the only safe cache-input identifier.
                statement.executeUpdate("UPDATE missing_episode_recovery SET source_hash = info_hash "
                        + "WHERE source_hash IS NULL OR TRIM(source_hash) = ''");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_missing_episode_recovery_source "
                        + "ON missing_episode_recovery(subscription_id, source_hash)");
                statement.executeUpdate("INSERT INTO schema_migrations(version, applied_at) VALUES (5, "
                        + System.currentTimeMillis() + ")");
                current.commit();
            } catch (SQLException e) {
                current.rollback();
                throw e;
            } finally {
                current.setAutoCommit(autoCommit);
            }
        }
        if (version < 6) {
            boolean autoCommit = current.getAutoCommit();
            current.setAutoCommit(false);
            try (Statement statement = current.createStatement()) {
                // A deleted ownership can be reused without weakening the
                // single live downloader/hash identity constraint.
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ownership_reassignment_history (
                            history_id TEXT PRIMARY KEY,
                            ownership_id TEXT NOT NULL,
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
                            reassigned_at INTEGER NOT NULL,
                            replacement_subscription_id TEXT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ownership_reassignment_file_history (
                            history_id TEXT NOT NULL,
                            relative_path TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            size INTEGER,
                            PRIMARY KEY (history_id, relative_path),
                            FOREIGN KEY (history_id) REFERENCES ownership_reassignment_history(history_id)
                                ON DELETE CASCADE
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ownership_reassignment_history_ownership "
                        + "ON ownership_reassignment_history(ownership_id, reassigned_at)");
                statement.executeUpdate("INSERT INTO schema_migrations(version, applied_at) VALUES (6, "
                        + System.currentTimeMillis() + ")");
                current.commit();
            } catch (SQLException e) {
                current.rollback();
                throw e;
            } finally {
                current.setAutoCommit(autoCommit);
            }
        }
        if (version < 7) {
            boolean autoCommit = current.getAutoCommit();
            current.setAutoCommit(false);
            try (Statement statement = current.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS mikan_bgm_cache (
                            mikan_id TEXT PRIMARY KEY,
                            bgm_id TEXT NOT NULL,
                            expires_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS public_bgm_score_cache (
                            bgm_id TEXT PRIMARY KEY,
                            score REAL NOT NULL,
                            expires_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mikan_bgm_cache_expiry "
                        + "ON mikan_bgm_cache(expires_at)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_public_bgm_score_cache_expiry "
                        + "ON public_bgm_score_cache(expires_at)");
                statement.executeUpdate("INSERT INTO schema_migrations(version, applied_at) VALUES (7, "
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

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T value) throws SQLException;
    }
}
