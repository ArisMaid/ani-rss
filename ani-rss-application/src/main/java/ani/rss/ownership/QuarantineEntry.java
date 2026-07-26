package ani.rss.ownership;

public record QuarantineEntry(
        String entryId,
        String operationId,
        String ownershipId,
        String originalPath,
        String quarantinePath,
        long purgeAfter,
        String state,
        long createdAt
) {
}
