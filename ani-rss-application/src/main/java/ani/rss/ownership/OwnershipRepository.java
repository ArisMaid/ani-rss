package ani.rss.ownership;

import ani.rss.persistence.DatabaseManager;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class OwnershipRepository {
    public DownloadOwnership createPending(DownloadOwnership ownership) {
        return DatabaseManager.transaction(connection -> {
            try (PreparedStatement existing = connection.prepareStatement("""
                    SELECT * FROM download_ownership
                    WHERE downloader_type = ? AND info_hash = ?
                    """)) {
                existing.setString(1, ownership.downloaderType());
                existing.setString(2, normalizeHash(ownership.infoHash()));
                try (ResultSet resultSet = existing.executeQuery()) {
                    if (resultSet.next()) {
                        DownloadOwnership current = mapOwnership(resultSet);
                        if (!current.subscriptionId().equals(ownership.subscriptionId())) {
                            throw new IllegalStateException("下载任务已归属于其他订阅");
                        }
                        return current;
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO download_ownership(
                        ownership_id, downloader_type, remote_task_id, info_hash,
                        subscription_id, season, episode, save_root, state, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                bindOwnership(statement, ownership);
                statement.executeUpdate();
            }
            return ownership;
        });
    }

    public Optional<DownloadOwnership> find(String ownershipId) {
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM download_ownership WHERE ownership_id = ?")) {
                statement.setString(1, ownershipId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapOwnership(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public Optional<DownloadOwnership> findForTask(String downloaderType, String remoteTaskId, String infoHash) {
        return DatabaseManager.withConnection(connection -> {
            String sql = """
                    SELECT * FROM download_ownership
                    WHERE downloader_type = ?
                      AND ((? IS NOT NULL AND remote_task_id = ?)
                        OR (? IS NOT NULL AND info_hash = ?))
                    ORDER BY CASE state WHEN 'ACTIVE' THEN 0 WHEN 'LEGACY_ADOPTED' THEN 1 ELSE 2 END
                    LIMIT 1
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, downloaderType);
                statement.setString(2, blankToNull(remoteTaskId));
                statement.setString(3, blankToNull(remoteTaskId));
                statement.setString(4, blankToNull(infoHash));
                statement.setString(5, normalizeHash(infoHash));
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapOwnership(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public List<DownloadOwnership> listBySubscription(String subscriptionId) {
        return list("SELECT * FROM download_ownership WHERE subscription_id = ? ORDER BY created_at", subscriptionId);
    }

    public List<DownloadOwnership> listAll() {
        return list("SELECT * FROM download_ownership ORDER BY created_at", null);
    }

    public void activate(String ownershipId, String remoteTaskId) {
        updateStateAndRemoteId(ownershipId, OwnershipState.ACTIVE, remoteTaskId);
    }

    public void markFailed(String ownershipId) {
        markFailed(ownershipId, null);
    }

    public void markFailed(String ownershipId, String remoteTaskId) {
        updateStateAndRemoteId(ownershipId, OwnershipState.FAILED, remoteTaskId);
    }

    public void updateState(String ownershipId, OwnershipState state) {
        updateStateAndRemoteId(ownershipId, state, null);
    }

    public void updateSaveRoot(String ownershipId, String saveRoot) {
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE download_ownership SET save_root = ?, updated_at = ? WHERE ownership_id = ?
                    """)) {
                statement.setString(1, saveRoot);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, ownershipId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    public void updateSaveRoots(Map<String, String> roots) {
        DatabaseManager.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE download_ownership SET save_root = ?, updated_at = ? WHERE ownership_id = ?
                    """)) {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, String> entry : roots.entrySet()) {
                    statement.setString(1, entry.getValue());
                    statement.setLong(2, now);
                    statement.setString(3, entry.getKey());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    public void replaceFiles(String ownershipId, List<OwnedFile> files) {
        DatabaseManager.transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM owned_files WHERE ownership_id = ?")) {
                delete.setString(1, ownershipId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO owned_files(ownership_id, relative_path, kind, size) VALUES (?, ?, ?, ?)
                    """)) {
                for (OwnedFile file : files) {
                    insert.setString(1, ownershipId);
                    insert.setString(2, file.relativePath());
                    insert.setString(3, file.kind());
                    if (file.size() == null) {
                        insert.setNull(4, java.sql.Types.BIGINT);
                    } else {
                        insert.setLong(4, file.size());
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    public List<OwnedFile> listFiles(String ownershipId) {
        return DatabaseManager.withConnection(connection -> {
            List<OwnedFile> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ownership_id, relative_path, kind, size FROM owned_files WHERE ownership_id = ?")) {
                statement.setString(1, ownershipId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        long size = resultSet.getLong("size");
                        result.add(new OwnedFile(
                                resultSet.getString("ownership_id"),
                                resultSet.getString("relative_path"),
                                resultSet.getString("kind"),
                                resultSet.wasNull() ? null : size
                        ));
                    }
                }
            }
            return result;
        });
    }

    public void addQuarantineEntries(List<QuarantineEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        DatabaseManager.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO quarantine_entries(
                        entry_id, operation_id, ownership_id, original_path,
                        quarantine_path, purge_after, state, created_at, previous_state
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (QuarantineEntry entry : entries) {
                    statement.setString(1, entry.entryId());
                    statement.setString(2, entry.operationId());
                    statement.setString(3, entry.ownershipId());
                    statement.setString(4, entry.originalPath());
                    statement.setString(5, entry.quarantinePath());
                    statement.setLong(6, entry.purgeAfter());
                    statement.setString(7, entry.state());
                    statement.setLong(8, entry.createdAt());
                    statement.setString(9, entry.previousOwnershipState());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE download_ownership SET state = 'QUARANTINED', updated_at = ? WHERE ownership_id = ?
                    """)) {
                long now = System.currentTimeMillis();
                for (String ownershipId : entries.stream()
                        .map(QuarantineEntry::ownershipId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))) {
                    statement.setLong(1, now);
                    statement.setString(2, ownershipId);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    public void markQuarantineOperationRestored(String operationId) {
        DatabaseManager.transaction(connection -> {
            Map<String, String> previousStates = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT ownership_id, previous_state
                    FROM quarantine_entries WHERE operation_id = ?
                    """)) {
                statement.setString(1, operationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        previousStates.put(resultSet.getString("ownership_id"),
                                resultSet.getString("previous_state"));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE quarantine_entries SET state = 'RESTORED'
                    WHERE operation_id = ? AND state = 'QUARANTINED'
                    """)) {
                statement.setString(1, operationId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE download_ownership SET state = ?, updated_at = ? WHERE ownership_id = ?
                    """)) {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, String> entry : previousStates.entrySet()) {
                    String previous = entry.getValue();
                    OwnershipState state = "LEGACY_ADOPTED".equals(previous)
                            ? OwnershipState.LEGACY_ADOPTED : OwnershipState.ACTIVE;
                    statement.setString(1, state.name());
                    statement.setLong(2, now);
                    statement.setString(3, entry.getKey());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    public List<QuarantineEntry> listQuarantine(String operationId, Long purgeBefore) {
        return DatabaseManager.withConnection(connection -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM quarantine_entries WHERE 1=1");
            if (operationId != null) {
                sql.append(" AND operation_id = ?");
            }
            if (purgeBefore != null) {
                sql.append(" AND purge_after <= ? AND state = 'QUARANTINED'");
            }
            sql.append(" ORDER BY created_at");
            List<QuarantineEntry> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                if (operationId != null) {
                    statement.setString(index++, operationId);
                }
                if (purgeBefore != null) {
                    statement.setLong(index, purgeBefore);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(mapQuarantine(resultSet));
                    }
                }
            }
            return result;
        });
    }

    public void updateQuarantineState(String entryId, String state) {
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE quarantine_entries SET state = ? WHERE entry_id = ?")) {
                statement.setString(1, state);
                statement.setString(2, entryId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private void updateStateAndRemoteId(String ownershipId, OwnershipState state, String remoteTaskId) {
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE download_ownership
                    SET state = ?, remote_task_id = COALESCE(?, remote_task_id), updated_at = ?
                    WHERE ownership_id = ?
                    """)) {
                statement.setString(1, state.name());
                statement.setString(2, blankToNull(remoteTaskId));
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, ownershipId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private List<DownloadOwnership> list(String sql, String parameter) {
        return DatabaseManager.withConnection(connection -> {
            List<DownloadOwnership> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (parameter != null) {
                    statement.setString(1, parameter);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(mapOwnership(resultSet));
                    }
                }
            }
            return result;
        });
    }

    private static void bindOwnership(PreparedStatement statement, DownloadOwnership ownership) throws SQLException {
        statement.setString(1, ownership.ownershipId());
        statement.setString(2, ownership.downloaderType());
        statement.setString(3, blankToNull(ownership.remoteTaskId()));
        statement.setString(4, normalizeHash(ownership.infoHash()));
        statement.setString(5, ownership.subscriptionId());
        if (ownership.season() == null) {
            statement.setNull(6, java.sql.Types.INTEGER);
        } else {
            statement.setInt(6, ownership.season());
        }
        statement.setString(7, ownership.episode());
        statement.setString(8, ownership.saveRoot());
        statement.setString(9, ownership.state().name());
        statement.setLong(10, ownership.createdAt());
        statement.setLong(11, ownership.updatedAt());
    }

    private static DownloadOwnership mapOwnership(ResultSet resultSet) throws SQLException {
        int season = resultSet.getInt("season");
        boolean seasonWasNull = resultSet.wasNull();
        return new DownloadOwnership(
                resultSet.getString("ownership_id"),
                resultSet.getString("downloader_type"),
                resultSet.getString("remote_task_id"),
                resultSet.getString("info_hash"),
                resultSet.getString("subscription_id"),
                seasonWasNull ? null : season,
                resultSet.getString("episode"),
                resultSet.getString("save_root"),
                OwnershipState.valueOf(resultSet.getString("state")),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at")
        );
    }

    private static QuarantineEntry mapQuarantine(ResultSet resultSet) throws SQLException {
        return new QuarantineEntry(
                resultSet.getString("entry_id"),
                resultSet.getString("operation_id"),
                resultSet.getString("ownership_id"),
                resultSet.getString("original_path"),
                resultSet.getString("quarantine_path"),
                resultSet.getLong("purge_after"),
                resultSet.getString("state"),
                resultSet.getLong("created_at"),
                resultSet.getString("previous_state")
        );
    }

    private static String normalizeHash(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
