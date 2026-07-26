package ani.rss.persistence;

import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Small durable cache for public Mikan-to-Bangumi mappings and Bangumi scores.
 * It deliberately stores only public numeric identifiers and expiry metadata;
 * source URLs, browser state, and account-specific data never enter this table.
 */
@Repository
public class PublicScoreCacheRepository {
    private static final Pattern NUMERIC_ID = Pattern.compile("\\d+");

    public Optional<MikanMapping> findMikanMapping(String mikanId, long now) {
        if (!isNumericId(mikanId)) {
            return Optional.empty();
        }
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT bgm_id, expires_at FROM mikan_bgm_cache
                    WHERE mikan_id = ? AND expires_at > ?
                    """)) {
                statement.setString(1, mikanId);
                statement.setLong(2, now);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(new MikanMapping(
                                    resultSet.getString("bgm_id"), resultSet.getLong("expires_at")))
                            : Optional.empty();
                }
            }
        });
    }

    public Optional<BgmScore> findBgmScore(String bgmId, long now) {
        if (!isNumericId(bgmId)) {
            return Optional.empty();
        }
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT score, expires_at FROM public_bgm_score_cache
                    WHERE bgm_id = ? AND expires_at > ?
                    """)) {
                statement.setString(1, bgmId);
                statement.setLong(2, now);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(new BgmScore(
                                    resultSet.getDouble("score"), resultSet.getLong("expires_at")))
                            : Optional.empty();
                }
            }
        });
    }

    public void saveMikanMapping(String mikanId, String bgmId, long expiresAt) {
        if (!isNumericId(mikanId) || expiresAt <= System.currentTimeMillis()) {
            return;
        }
        String normalizedBgmId = isNumericId(bgmId) ? bgmId : "";
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mikan_bgm_cache(mikan_id, bgm_id, expires_at, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(mikan_id) DO UPDATE SET
                        bgm_id = excluded.bgm_id,
                        expires_at = excluded.expires_at,
                        updated_at = excluded.updated_at
                    """)) {
                long now = System.currentTimeMillis();
                statement.setString(1, mikanId);
                statement.setString(2, normalizedBgmId);
                statement.setLong(3, expiresAt);
                statement.setLong(4, now);
                statement.executeUpdate();
                return null;
            }
        });
    }

    public void saveBgmScore(String bgmId, double score, long expiresAt) {
        if (!isNumericId(bgmId) || !Double.isFinite(score) || expiresAt <= System.currentTimeMillis()) {
            return;
        }
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO public_bgm_score_cache(bgm_id, score, expires_at, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(bgm_id) DO UPDATE SET
                        score = excluded.score,
                        expires_at = excluded.expires_at,
                        updated_at = excluded.updated_at
                    """)) {
                long now = System.currentTimeMillis();
                statement.setString(1, bgmId);
                statement.setDouble(2, score);
                statement.setLong(3, expiresAt);
                statement.setLong(4, now);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private static boolean isNumericId(String value) {
        return value != null && NUMERIC_ID.matcher(value).matches();
    }

    public record MikanMapping(String bgmId, long expiresAt) {
    }

    public record BgmScore(double score, long expiresAt) {
    }
}
