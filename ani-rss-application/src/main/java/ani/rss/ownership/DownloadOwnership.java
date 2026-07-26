package ani.rss.ownership;

public record DownloadOwnership(
        String ownershipId,
        String downloaderType,
        String remoteTaskId,
        String infoHash,
        String subscriptionId,
        Integer season,
        String episode,
        String saveRoot,
        OwnershipState state,
        long createdAt,
        long updatedAt
) {
}
