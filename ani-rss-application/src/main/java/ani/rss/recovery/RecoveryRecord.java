package ani.rss.recovery;

/** Persisted input needed to safely retry one RSS item. */
public record RecoveryRecord(
        String recoveryId,
        String subscriptionId,
        String sourceHash,
        String infoHash,
        Integer season,
        String episode,
        String itemJson,
        RecoveryState state,
        int attempts,
        long nextAttemptAt,
        String lastErrorCode,
        long createdAt,
        long updatedAt) {
}
