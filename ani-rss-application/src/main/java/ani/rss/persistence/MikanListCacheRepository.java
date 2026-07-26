package ani.rss.persistence;

import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Durable, short-lived snapshots for the public Mikan seasonal schedule.
 * Search results are deliberately not stored here: they are user-driven and
 * already have a much shorter in-memory cache.
 */
@Repository
public class MikanListCacheRepository {
    private static final Pattern CACHE_KEY = Pattern.compile("mikan:list:[a-f0-9]{64}");
    private static final int MAX_SNAPSHOT_BYTES = 512 * 1024;

    public Optional<Snapshot> findValid(String cacheKey, long now) {
        if (!validKey(cacheKey)) {
            return Optional.empty();
        }
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT snapshot_json, expires_at
                    FROM mikan_list_cache
                    WHERE cache_key = ? AND expires_at > ?
                    """)) {
                statement.setString(1, cacheKey);
                statement.setLong(2, now);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    String snapshot = resultSet.getString("snapshot_json");
                    if (!validSnapshot(snapshot)) {
                        return Optional.empty();
                    }
                    return Optional.of(new Snapshot(snapshot, resultSet.getLong("expires_at")));
                }
            }
        });
    }

    public void save(String cacheKey, String snapshotJson, long expiresAt) {
        if (!validKey(cacheKey) || !validSnapshot(snapshotJson) || expiresAt <= System.currentTimeMillis()) {
            return;
        }
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mikan_list_cache(cache_key, snapshot_json, expires_at, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(cache_key) DO UPDATE SET
                        snapshot_json = excluded.snapshot_json,
                        expires_at = excluded.expires_at,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, cacheKey);
                statement.setString(2, snapshotJson);
                statement.setLong(3, expiresAt);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            }
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "DELETE FROM mikan_list_cache WHERE expires_at <= ?")) {
                cleanup.setLong(1, System.currentTimeMillis());
                cleanup.executeUpdate();
            }
            return null;
        });
    }

    private static boolean validKey(String value) {
        return value != null && CACHE_KEY.matcher(value).matches();
    }

    private static boolean validSnapshot(String value) {
        return value != null && !value.isBlank() &&
                value.getBytes(StandardCharsets.UTF_8).length <= MAX_SNAPSHOT_BYTES;
    }

    public record Snapshot(String snapshotJson, long expiresAt) {
    }
}
