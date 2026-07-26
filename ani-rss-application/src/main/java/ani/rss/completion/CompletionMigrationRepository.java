package ani.rss.completion;

import ani.rss.persistence.DatabaseManager;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class CompletionMigrationRepository {
    public CompletionMigrationRecord prepare(String subscriptionId, String fingerprint, String targetRoot) {
        long now = System.currentTimeMillis();
        return DatabaseManager.transaction(connection -> {
            Optional<CompletionMigrationRecord> existing = find(connection, subscriptionId);
            if (existing.isPresent()) {
                CompletionMigrationRecord value = existing.get();
                if (!value.subscriptionFingerprint().equals(fingerprint) || !value.targetRoot().equals(targetRoot)) {
                    update(connection, subscriptionId, fingerprint, targetRoot, CompletionMigrationState.CONFLICT, now);
                    return new CompletionMigrationRecord(subscriptionId, fingerprint, targetRoot,
                            CompletionMigrationState.CONFLICT, value.createdAt(), now);
                }
                if (value.state() == CompletionMigrationState.FAILED) {
                    update(connection, subscriptionId, fingerprint, targetRoot, CompletionMigrationState.PREPARED, now);
                    return new CompletionMigrationRecord(subscriptionId, fingerprint, targetRoot,
                            CompletionMigrationState.PREPARED, value.createdAt(), now);
                }
                return value;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO completed_migration_finalization(
                        subscription_id, subscription_fingerprint, target_root, state, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, subscriptionId);
                statement.setString(2, fingerprint);
                statement.setString(3, targetRoot);
                statement.setString(4, CompletionMigrationState.PREPARED.name());
                statement.setLong(5, now);
                statement.setLong(6, now);
                statement.executeUpdate();
            }
            return new CompletionMigrationRecord(subscriptionId, fingerprint, targetRoot,
                    CompletionMigrationState.PREPARED, now, now);
        });
    }

    public void setState(String subscriptionId, CompletionMigrationState state) {
        DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE completed_migration_finalization SET state = ?, updated_at = ? WHERE subscription_id = ?
                    """)) {
                statement.setString(1, state.name());
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, subscriptionId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    public List<CompletionMigrationRecord> listPendingFinalization() {
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM completed_migration_finalization
                    WHERE state IN ('PREPARED', 'MOVED') ORDER BY created_at
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                List<CompletionMigrationRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(map(resultSet));
                }
                return List.copyOf(result);
            }
        });
    }

    private static Optional<CompletionMigrationRecord> find(java.sql.Connection connection, String subscriptionId)
            throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM completed_migration_finalization WHERE subscription_id = ?
                """)) {
            statement.setString(1, subscriptionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private static void update(
            java.sql.Connection connection, String subscriptionId, String fingerprint, String targetRoot,
            CompletionMigrationState state, long now) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE completed_migration_finalization
                SET subscription_fingerprint = ?, target_root = ?, state = ?, updated_at = ?
                WHERE subscription_id = ?
                """)) {
            statement.setString(1, fingerprint);
            statement.setString(2, targetRoot);
            statement.setString(3, state.name());
            statement.setLong(4, now);
            statement.setString(5, subscriptionId);
            statement.executeUpdate();
        }
    }

    private static CompletionMigrationRecord map(ResultSet resultSet) throws java.sql.SQLException {
        return new CompletionMigrationRecord(
                resultSet.getString("subscription_id"),
                resultSet.getString("subscription_fingerprint"),
                resultSet.getString("target_root"),
                CompletionMigrationState.valueOf(resultSet.getString("state")),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }
}
