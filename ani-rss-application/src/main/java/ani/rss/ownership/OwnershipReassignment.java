package ani.rss.ownership;

/** Immutable evidence retained when a deleted ownership is reused. */
public record OwnershipReassignment(
        String historyId,
        String ownershipId,
        String downloaderType,
        String remoteTaskId,
        String infoHash,
        String previousSubscriptionId,
        String previousSaveRoot,
        String replacementSubscriptionId,
        long reassignedAt
) {
}
