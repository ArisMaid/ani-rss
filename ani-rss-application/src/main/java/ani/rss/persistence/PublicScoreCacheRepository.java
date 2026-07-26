package ani.rss.persistence;

import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    /** SQLite supports 999 bind variables by default; leave room for the expiry value. */
    private static final int MAX_IDS_PER_BATCH = 500;

    public Optional<MikanMapping> findMikanMapping(String mikanId, long now) {
        if (!isNumericId(mikanId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(findMikanMappings(List.of(mikanId), now).get(mikanId));
    }

    public Optional<BgmScore> findBgmScore(String bgmId, long now) {
        if (!isNumericId(bgmId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(findBgmScores(List.of(bgmId), now).get(bgmId));
    }

    /**
     * Reads all still-valid mappings in one SQLite operation per bounded ID
     * chunk.  This avoids serial connection-lock acquisition for every card
     * when a Mikan season is restored from the durable score cache.
     */
    public Map<String, MikanMapping> findMikanMappings(Collection<String> mikanIds, long now) {
        LinkedHashSet<String> ids = normalizedIds(mikanIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return DatabaseManager.withConnection(connection -> {
            Map<String, MikanMapping> result = new LinkedHashMap<>();
            for (List<String> batch : batches(ids)) {
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                String sql = "SELECT mikan_id, bgm_id, expires_at FROM mikan_bgm_cache "
                        + "WHERE expires_at > ? AND mikan_id IN (" + placeholders + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, now);
                    bindIds(statement, batch, 2);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            result.put(resultSet.getString("mikan_id"), new MikanMapping(
                                    resultSet.getString("bgm_id"), resultSet.getLong("expires_at")));
                        }
                    }
                }
            }
            return Map.copyOf(result);
        });
    }

    /** See {@link #findMikanMappings(Collection, long)}. */
    public Map<String, BgmScore> findBgmScores(Collection<String> bgmIds, long now) {
        LinkedHashSet<String> ids = normalizedIds(bgmIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return DatabaseManager.withConnection(connection -> {
            Map<String, BgmScore> result = new LinkedHashMap<>();
            for (List<String> batch : batches(ids)) {
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                String sql = "SELECT bgm_id, score, expires_at FROM public_bgm_score_cache "
                        + "WHERE expires_at > ? AND bgm_id IN (" + placeholders + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, now);
                    bindIds(statement, batch, 2);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            result.put(resultSet.getString("bgm_id"), new BgmScore(
                                    resultSet.getDouble("score"), resultSet.getLong("expires_at")));
                        }
                    }
                }
            }
            return Map.copyOf(result);
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

    private static LinkedHashSet<String> normalizedIds(Collection<String> values) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (values == null) {
            return ids;
        }
        for (String value : values) {
            if (isNumericId(value)) {
                ids.add(value);
            }
        }
        return ids;
    }

    private static List<List<String>> batches(LinkedHashSet<String> ids) {
        List<String> values = new ArrayList<>(ids);
        List<List<String>> batches = new ArrayList<>();
        for (int index = 0; index < values.size(); index += MAX_IDS_PER_BATCH) {
            batches.add(values.subList(index, Math.min(index + MAX_IDS_PER_BATCH, values.size())));
        }
        return batches;
    }

    private static void bindIds(PreparedStatement statement, List<String> ids, int startIndex)
            throws SQLException {
        for (int index = 0; index < ids.size(); index++) {
            statement.setString(startIndex + index, ids.get(index));
        }
    }

    public record MikanMapping(String bgmId, long expiresAt) {
    }

    public record BgmScore(double score, long expiresAt) {
    }
}
