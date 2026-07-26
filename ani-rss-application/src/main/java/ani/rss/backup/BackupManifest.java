package ani.rss.backup;

import java.util.List;

public record BackupManifest(
        int formatVersion,
        String applicationVersion,
        long createdAt,
        List<Entry> files) {
    public static final int CURRENT_FORMAT = 1;

    public BackupManifest {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public record Entry(String path, long size, String sha256) {
    }
}
