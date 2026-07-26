package ani.rss.completion;

public record CompletionMigrationRecord(
        String subscriptionId,
        String subscriptionFingerprint,
        String targetRoot,
        CompletionMigrationState state,
        long createdAt,
        long updatedAt) {
}
