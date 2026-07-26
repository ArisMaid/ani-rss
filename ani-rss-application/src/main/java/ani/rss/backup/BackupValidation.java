package ani.rss.backup;

import java.util.List;
import java.util.Set;

public record BackupValidation(
        boolean valid,
        boolean legacy,
        String applicationVersion,
        List<BackupManifest.Entry> files,
        Set<String> topLevelNames,
        List<String> warnings) {
    public BackupValidation {
        files = files == null ? List.of() : List.copyOf(files);
        topLevelNames = topLevelNames == null ? Set.of() : Set.copyOf(topLevelNames);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
