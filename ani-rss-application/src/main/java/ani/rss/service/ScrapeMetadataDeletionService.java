package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.PathPolicy;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnedFile;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deletes only scraper sidecars whose subscription directory can be proved. */
final class ScrapeMetadataDeletionService {
    private static final Set<String> FIXED_METADATA = Set.of(
            "tvshow.nfo", "season.nfo", "bangumi.ini");

    private final OwnershipService ownershipService;

    ScrapeMetadataDeletionService(OwnershipService ownershipService) {
        this.ownershipService = ownershipService;
    }

    OwnershipService.FileDeletionOutcome deleteBestEffort(
            Collection<DownloadOwnership> releasedOwnerships,
            Map<String, Path> cleanupBoundaries) {
        if (releasedOwnerships == null || releasedOwnerships.isEmpty()) {
            return new OwnershipService.FileDeletionOutcome(0, 0);
        }

        Set<String> releasedIds = releasedOwnerships.stream()
                .filter(java.util.Objects::nonNull)
                .map(DownloadOwnership::ownershipId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Path> protectedRoots = protectedRoots(releasedIds);
        Map<Path, Set<String>> mediaNamesByDirectory = new LinkedHashMap<>();
        Set<Path> directories = new LinkedHashSet<>();

        for (DownloadOwnership ownership : releasedOwnerships) {
            if (ownership == null || (ownership.state() != OwnershipState.ACTIVE &&
                    ownership.state() != OwnershipState.LEGACY_ADOPTED)) {
                continue;
            }
            try {
                Path root = safeRoot(ownership.saveRoot());
                List<OwnedFile> manifest = ownershipService.listFiles(ownership.ownershipId());
                boolean hasValidManifestEntry = false;
                for (OwnedFile file : manifest) {
                    try {
                        Path ownedPath = PathPolicy.resolveWithin(root, file.relativePath());
                        Path parent = ownedPath.getParent();
                        Path fileName = ownedPath.getFileName();
                        if (parent == null || fileName == null) {
                            continue;
                        }
                        hasValidManifestEntry = true;
                        directories.add(parent);
                        mediaNamesByDirectory.computeIfAbsent(parent, ignored -> new HashSet<>())
                                .add(baseName(fileName.toString()));
                    } catch (RuntimeException ignored) {
                        // One malformed manifest entry must not expand the cleanup scope.
                    }
                }
                if (hasValidManifestEntry) {
                    addCleanupDirectories(directories, root,
                            cleanupBoundaries == null ? null : cleanupBoundaries.get(ownership.ownershipId()));
                }
            } catch (RuntimeException ignored) {
                // An invalid root or manifest cannot authorize metadata deletion.
            }
        }

        int deleted = 0;
        int skipped = 0;
        for (Path directory : directories) {
            if (intersectsProtectedRoot(directory, protectedRoots)) {
                continue;
            }
            Set<String> mediaNames = mediaNamesByDirectory.getOrDefault(directory, Set.of());
            try {
                requireSafeDirectory(directory);
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                    for (Path entry : entries) {
                        Path fileName = entry.getFileName();
                        if (fileName == null || !isGeneratedMetadata(fileName.toString(), mediaNames)) {
                            continue;
                        }
                        if (Files.isSymbolicLink(entry) ||
                                !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                            skipped++;
                            continue;
                        }
                        try {
                            PathPolicy.requireNoSymbolicLinks(directory, entry);
                            Files.delete(entry);
                            deleted++;
                        } catch (IOException | RuntimeException ignored) {
                            skipped++;
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Missing, shared, or unverifiable directories are retained.
            }
        }
        return new OwnershipService.FileDeletionOutcome(deleted, skipped);
    }

    private List<Path> protectedRoots(Set<String> releasedIds) {
        Set<Path> roots = new LinkedHashSet<>();
        for (DownloadOwnership ownership : ownershipService.listAll()) {
            if (ownership == null || releasedIds.contains(ownership.ownershipId()) ||
                    ownership.state() == OwnershipState.DELETED) {
                continue;
            }
            try {
                roots.add(safeRoot(ownership.saveRoot()));
            } catch (RuntimeException ignored) {
                try {
                    roots.add(Path.of(ownership.saveRoot()).toAbsolutePath().normalize());
                } catch (RuntimeException ignoredAgain) {
                    // No usable path means this record cannot expand deletion scope.
                }
            }
        }
        return List.copyOf(roots);
    }

    private static void addCleanupDirectories(Set<Path> directories, Path root, Path boundaryValue) {
        directories.add(root);
        if (boundaryValue == null) {
            return;
        }
        Path boundary = boundaryValue.toAbsolutePath().normalize();
        if (PathPolicy.isFileSystemRoot(boundary) || root.equals(boundary) || !root.startsWith(boundary)) {
            return;
        }
        PathPolicy.requireNoSymbolicLinks(boundary.getRoot(), root);
        for (Path current = root; current != null && !current.equals(boundary); current = current.getParent()) {
            directories.add(current);
        }
    }

    private static Path safeRoot(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ownership root is required");
        }
        Path root = Path.of(value).toAbsolutePath().normalize();
        if (PathPolicy.isFileSystemRoot(root) || root.getRoot() == null) {
            throw new IllegalArgumentException("ownership root is unsafe");
        }
        PathPolicy.requireNoSymbolicLinks(root.getRoot(), root);
        return root;
    }

    private static void requireSafeDirectory(Path directory) {
        if (directory == null || PathPolicy.isFileSystemRoot(directory) ||
                Files.isSymbolicLink(directory) ||
                !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || directory.getRoot() == null) {
            throw new IllegalArgumentException("metadata directory is unsafe");
        }
        PathPolicy.requireNoSymbolicLinks(directory.getRoot(), directory);
    }

    private static boolean intersectsProtectedRoot(Path directory, Collection<Path> protectedRoots) {
        for (Path protectedRoot : protectedRoots) {
            if (directory.equals(protectedRoot) || directory.startsWith(protectedRoot) ||
                    protectedRoot.startsWith(directory)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGeneratedMetadata(String fileName, Set<String> mediaNames) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (FIXED_METADATA.contains(normalized)) {
            return true;
        }

        String extension = extension(normalized);
        if (!FileUtils.isImageFormat(normalized)) {
            return extension.equals("nfo") && mediaNames.contains(baseName(normalized));
        }

        String stem = baseName(normalized);
        if (stem.equals("poster") || stem.equals("fanart") || stem.equals("clearlogo") ||
                stem.matches("fanart[1-4]") || stem.matches("season\\d{2,}-poster")) {
            return true;
        }
        if (!stem.endsWith("-thumb")) {
            return false;
        }
        return mediaNames.contains(stem.substring(0, stem.length() - "-thumb".length()));
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String value = dot > 0 ? fileName.substring(0, dot) : fileName;
        return value.toLowerCase(Locale.ROOT);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot + 1 < fileName.length() ? fileName.substring(dot + 1) : "";
    }
}
