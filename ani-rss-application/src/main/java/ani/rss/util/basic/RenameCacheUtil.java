package ani.rss.util.basic;


import ani.rss.persistence.DatabaseManager;
import lombok.extern.slf4j.Slf4j;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 重命名缓存
 */
@Slf4j
public class RenameCacheUtil {
    private static final String TABLE_NAME = "RENAME_CACHES";

    public static synchronized void put(String key, String object) {
        log.debug("put => key: {}, object: {}", key, object);
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + TABLE_NAME + "(K, V) VALUES (?, ?) " +
                            "ON CONFLICT(K) DO UPDATE SET V=excluded.V")) {
                statement.setString(1, key);
                statement.setString(2, object);
                statement.executeUpdate();
                return null;
            }
        });
    }

    public static synchronized String get(String key) {
        log.debug("get => key: {}", key);
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT V FROM " + TABLE_NAME + " WHERE K = ?")) {
                statement.setString(1, key);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getString(1) : null;
                }
            }
        });
    }

    public static synchronized void remove(String key) {
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + TABLE_NAME + " WHERE K = ?")) {
                statement.setString(1, key);
                if (statement.executeUpdate() > 0) {
                    log.debug("remove => key: {}", key);
                }
                return null;
            }
        });
    }
}
