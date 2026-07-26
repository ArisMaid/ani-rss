package ani.rss.ownership;

public record OwnershipCandidate(
        String downloaderType,
        String remoteTaskId,
        String infoHash,
        String taskName,
        String savePath,
        String subscriptionId,
        String subscriptionTitle,
        boolean autoAdoptable,
        String reason
) {
}
