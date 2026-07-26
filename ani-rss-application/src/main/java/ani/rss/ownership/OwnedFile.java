package ani.rss.ownership;

public record OwnedFile(String ownershipId, String relativePath, String kind, Long size) {
}
