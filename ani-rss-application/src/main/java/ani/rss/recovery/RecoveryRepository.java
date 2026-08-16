package ani.rss.recovery;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.persistence.DatabaseManager;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Database boundary for retryable, expected RSS downloads. */
@Repository
public class RecoveryRepository {
    public RecoveryRecord observe(Ani ani, Item item) {
        return observeWithStatus(ani, item).record();
    }

    public Observation observeWithStatus(Ani ani, Item item) {
        if (ani == null || StrUtil.isBlank(ani.getId()) || item == null || StrUtil.isBlank(item.getInfoHash())) {
            throw new IllegalArgumentException("recovery item requires subscription id and info-hash");
        }
        long now = System.currentTimeMillis();
        String sourceHash = normalizeHash(item.getInfoHash());
        String itemJson = GsonStatic.toJson(item);
        return DatabaseManager.transaction(connection -> {
            Optional<RecoveryRecord> existing = findBySourceHash(connection, ani.getId(), sourceHash);
            if (existing.isPresent()) {
                RecoveryRecord current = existing.get();
                // Explicit local removal remains explicit even while the RSS
                // item stays visible. New RSS hashes still create new records.
                if (current.state() == RecoveryState.CANCELLED) {
                    return new Observation(current, false);
                }
                RecoveryState state = current.state();
                long nextAttemptAt = current.nextAttemptAt();
                String observedEpisode = episode(item);
                if (Objects.equals(current.season(), ani.getSeason()) &&
                        Objects.equals(current.episode(), observedEpisode) &&
                        Objects.equals(current.itemJson(), itemJson)) {
                    return new Observation(current, false);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE missing_episode_recovery
                        SET season = ?, episode = ?, item_json = ?, state = ?, next_attempt_at = ?, updated_at = ?
                        WHERE recovery_id = ?
                        """)) {
                    statement.setObject(1, ani.getSeason());
                    statement.setString(2, observedEpisode);
                    statement.setString(3, itemJson);
                    statement.setString(4, state.name());
                    statement.setLong(5, nextAttemptAt);
                    statement.setLong(6, now);
                    statement.setString(7, current.recoveryId());
                    statement.executeUpdate();
                }
                return new Observation(new RecoveryRecord(
                        current.recoveryId(), current.subscriptionId(), current.sourceHash(),
                        current.infoHash(),
                        ani.getSeason(), observedEpisode, itemJson, state, current.attempts(), nextAttemptAt,
                        current.lastErrorCode(), current.createdAt(), now), false);
            }
            RecoveryRecord created = new RecoveryRecord(
                    UUID.randomUUID().toString(), ani.getId(), sourceHash, sourceHash,
                    ani.getSeason(), episode(item), itemJson,
                    RecoveryState.PENDING, 0, now, null, now, now);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO missing_episode_recovery(
                        recovery_id, subscription_id, source_hash, info_hash, season, episode, item_json, state,
                        attempts, next_attempt_at, last_error_code, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                bind(statement, created);
                statement.executeUpdate();
            }
            return new Observation(created, true);
        });
    }

    public List<RecoveryRecord> listRecoverable(String subscriptionId) {
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM missing_episode_recovery
                    WHERE subscription_id = ? AND state NOT IN ('CANCELLED', 'SUPERSEDED')
                    ORDER BY created_at
                    """)) {
                statement.setString(1, subscriptionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<RecoveryRecord> records = new ArrayList<>();
                    while (resultSet.next()) {
                        records.add(map(resultSet));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    public List<RecoveryRecord> listBySubscription(String subscriptionId) {
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM missing_episode_recovery
                    WHERE subscription_id = ?
                    ORDER BY created_at
                    """)) {
                statement.setString(1, subscriptionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<RecoveryRecord> records = new ArrayList<>();
                    while (resultSet.next()) {
                        records.add(map(resultSet));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    public List<RecoveryRecord> listForReconciliation(String subscriptionId, long satisfiedAuditBefore) {
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM (
                        SELECT * FROM missing_episode_recovery
                        WHERE subscription_id = ?
                          AND state IN ('PENDING', 'DEFERRED', 'SUBMITTED', 'RETRY_WAIT')
                        UNION ALL
                        SELECT * FROM missing_episode_recovery
                        WHERE subscription_id = ?
                          AND state = 'SATISFIED'
                          AND updated_at <= ?
                    )
                    ORDER BY created_at
                    """)) {
                statement.setString(1, subscriptionId);
                statement.setString(2, subscriptionId);
                statement.setLong(3, satisfiedAuditBefore);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<RecoveryRecord> records = new ArrayList<>();
                    while (resultSet.next()) {
                        records.add(map(resultSet));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    public boolean hasOutstanding(String subscriptionId) {
        if (StrUtil.isBlank(subscriptionId)) {
            return false;
        }
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM missing_episode_recovery
                    WHERE subscription_id = ?
                      AND state IN ('PENDING', 'DEFERRED', 'SUBMITTED', 'RETRY_WAIT')
                    LIMIT 1
                    """)) {
                statement.setString(1, subscriptionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        });
    }

    public Optional<RecoveryRecord> find(String subscriptionId, String infoHash) {
        return DatabaseManager.withConnection(connection ->
                findByReference(connection, subscriptionId, normalizeHash(infoHash)));
    }

    /**
     * Associates an RSS cache key with the canonical BitTorrent info-hash.
     * The source hash remains stable so retries keep using the exact cached
     * torrent or magnet input that ANI-RSS originally accepted.
     */
    public Optional<RecoveryRecord> promoteCanonicalHash(
            String subscriptionId, String sourceHash, String canonicalHash) {
        if (StrUtil.isBlank(subscriptionId) || StrUtil.isBlank(sourceHash) || StrUtil.isBlank(canonicalHash)) {
            return Optional.empty();
        }
        String source = normalizeHash(sourceHash);
        String canonical = normalizeHash(canonicalHash);
        return DatabaseManager.transaction(connection -> {
            Optional<RecoveryRecord> currentOpt = findBySourceHash(connection, subscriptionId, source);
            if (currentOpt.isEmpty()) {
                return Optional.empty();
            }
            RecoveryRecord current = currentOpt.get();
            if (canonical.equals(current.infoHash())) {
                return Optional.of(current);
            }

            Optional<RecoveryRecord> collision = findByInfoHash(connection, subscriptionId, canonical);
            if (collision.isPresent() && !collision.get().recoveryId().equals(current.recoveryId())) {
                // A canonical hash already belongs to another accepted RSS
                // item. Never overwrite either record's cache association.
                return Optional.empty();
            }

            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE missing_episode_recovery SET info_hash = ?, updated_at = ? WHERE recovery_id = ?
                    """)) {
                statement.setString(1, canonical);
                statement.setLong(2, now);
                statement.setString(3, current.recoveryId());
                statement.executeUpdate();
            }
            return Optional.of(new RecoveryRecord(
                    current.recoveryId(), current.subscriptionId(), current.sourceHash(), canonical,
                    current.season(), current.episode(), current.itemJson(), current.state(), current.attempts(),
                    current.nextAttemptAt(), current.lastErrorCode(), current.createdAt(), now));
        });
    }

    public boolean markSubmitted(String subscriptionId, String infoHash, long nextObservationAt) {
        return update(subscriptionId, infoHash, RecoveryState.SUBMITTED, null, nextObservationAt, false);
    }

    public boolean defer(String subscriptionId, String infoHash, long nextAttemptAt) {
        return update(subscriptionId, infoHash, RecoveryState.DEFERRED, null, nextAttemptAt, false);
    }

    public boolean markSatisfied(String subscriptionId, String infoHash) {
        return update(subscriptionId, infoHash, RecoveryState.SATISFIED, null, Long.MAX_VALUE, false);
    }

    public boolean scheduleRetry(String subscriptionId, String infoHash, String errorCode, long nextAttemptAt) {
        return update(subscriptionId, infoHash, RecoveryState.RETRY_WAIT, errorCode, nextAttemptAt, true);
    }

    public boolean armMissing(String subscriptionId, String infoHash) {
        return update(subscriptionId, infoHash, RecoveryState.PENDING, null, System.currentTimeMillis(), false);
    }

    public boolean reactivate(String subscriptionId, String infoHash) {
        return update(subscriptionId, infoHash, RecoveryState.PENDING, null, System.currentTimeMillis(), false);
    }

    public boolean markSuperseded(String subscriptionId, String infoHash, String reason) {
        return update(subscriptionId, infoHash, RecoveryState.SUPERSEDED,
                StrUtil.blankToDefault(reason, "RECOVERY_SUPERSEDED"), Long.MAX_VALUE, false);
    }

    public void touchSatisfiedAudit(String recoveryId) {
        if (StrUtil.isBlank(recoveryId)) {
            return;
        }
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE missing_episode_recovery SET updated_at = ?
                    WHERE recovery_id = ? AND state = 'SATISFIED'
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, recoveryId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    public void cancelSubscription(String subscriptionId) {
        if (StrUtil.isBlank(subscriptionId)) {
            return;
        }
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE missing_episode_recovery
                    SET state = 'CANCELLED', updated_at = ?
                    WHERE subscription_id = ? AND state <> 'CANCELLED'
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, subscriptionId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    public void cancel(String subscriptionId, String infoHash) {
        if (StrUtil.isBlank(subscriptionId) || StrUtil.isBlank(infoHash)) {
            return;
        }
        DatabaseManager.withConnection(connection -> {
            Optional<RecoveryRecord> record = findByReference(connection, subscriptionId, normalizeHash(infoHash));
            if (record.isEmpty()) {
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE missing_episode_recovery
                    SET state = 'CANCELLED', updated_at = ?
                    WHERE recovery_id = ?
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, record.get().recoveryId());
                statement.executeUpdate();
                return null;
            }
        });
    }

    private boolean update(
            String subscriptionId, String infoHash, RecoveryState state, String errorCode,
            long nextAttemptAt, boolean incrementAttempts) {
        return DatabaseManager.withConnection(connection -> {
            Optional<RecoveryRecord> record = findByReference(connection, subscriptionId, normalizeHash(infoHash));
            if (record.isEmpty()) {
                return false;
            }
            RecoveryRecord current = record.get();
            if (!incrementAttempts && current.state() == state &&
                    Objects.equals(current.lastErrorCode(), errorCode) &&
                    current.nextAttemptAt() == nextAttemptAt) {
                return true;
            }
            String sql = incrementAttempts ? """
                    UPDATE missing_episode_recovery
                    SET state = ?, last_error_code = ?, attempts = attempts + 1,
                        next_attempt_at = ?, updated_at = ?
                    WHERE recovery_id = ?
                    """ : """
                    UPDATE missing_episode_recovery
                    SET state = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ?
                    WHERE recovery_id = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, state.name());
                statement.setString(2, errorCode);
                statement.setLong(3, nextAttemptAt);
                statement.setLong(4, System.currentTimeMillis());
                statement.setString(5, current.recoveryId());
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static Optional<RecoveryRecord> findByReference(
            java.sql.Connection connection, String subscriptionId, String reference) throws SQLException {
        Optional<RecoveryRecord> source = findBySourceHash(connection, subscriptionId, reference);
        return source.isPresent() ? source : findByInfoHash(connection, subscriptionId, reference);
    }

    private static Optional<RecoveryRecord> findBySourceHash(
            java.sql.Connection connection, String subscriptionId, String sourceHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM missing_episode_recovery WHERE subscription_id = ? AND source_hash = ?
                """)) {
            statement.setString(1, subscriptionId);
            statement.setString(2, sourceHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private static Optional<RecoveryRecord> findByInfoHash(
            java.sql.Connection connection, String subscriptionId, String infoHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM missing_episode_recovery WHERE subscription_id = ? AND info_hash = ?
                """)) {
            statement.setString(1, subscriptionId);
            statement.setString(2, infoHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private static void bind(PreparedStatement statement, RecoveryRecord record) throws SQLException {
        statement.setString(1, record.recoveryId());
        statement.setString(2, record.subscriptionId());
        statement.setString(3, record.sourceHash());
        statement.setString(4, record.infoHash());
        statement.setObject(5, record.season());
        statement.setString(6, record.episode());
        statement.setString(7, record.itemJson());
        statement.setString(8, record.state().name());
        statement.setInt(9, record.attempts());
        statement.setLong(10, record.nextAttemptAt());
        statement.setString(11, record.lastErrorCode());
        statement.setLong(12, record.createdAt());
        statement.setLong(13, record.updatedAt());
    }

    private static RecoveryRecord map(ResultSet resultSet) throws SQLException {
        Object season = resultSet.getObject("season");
        return new RecoveryRecord(
                resultSet.getString("recovery_id"),
                resultSet.getString("subscription_id"),
                StrUtil.blankToDefault(resultSet.getString("source_hash"), resultSet.getString("info_hash")),
                resultSet.getString("info_hash"),
                season instanceof Number number ? number.intValue() : null,
                resultSet.getString("episode"),
                resultSet.getString("item_json"),
                RecoveryState.valueOf(resultSet.getString("state")),
                resultSet.getInt("attempts"),
                resultSet.getLong("next_attempt_at"),
                resultSet.getString("last_error_code"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    private static String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String episode(Item item) {
        return item.getEpisode() == null ? null : item.getEpisode().toString();
    }

    public record Observation(RecoveryRecord record, boolean created) {
    }
}
