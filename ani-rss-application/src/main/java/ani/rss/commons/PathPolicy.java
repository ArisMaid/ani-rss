package ani.rss.commons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central path normalization and containment checks for file operations.
 */
public final class PathPolicy {
    private PathPolicy() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        String trimmed = value.trim().replace('\\', '/');
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        Path normalized = Paths.get(trimmed).normalize();
        String result = normalized.toString().replace('\\', '/');

        // Path#toString preserves Unix and UNC roots but Windows drive roots
        // can be represented without the trailing separator by some providers.
        if (isWindowsDriveRoot(trimmed) && !result.endsWith("/")) {
            return result + "/";
        }
        if ("/".equals(trimmed)) {
            return "/";
        }
        return result;
    }

    public static Path requireWithin(Path root, Path candidate) {
        Path normalizedRoot = absolute(root);
        Path normalizedCandidate = absolute(candidate);
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("path escapes allowed root");
        }
        return normalizedCandidate;
    }

    public static Path requireSafeDeletionTarget(Path root, Path candidate) {
        Path normalizedRoot = absolute(root);
        Path normalizedCandidate = requireWithin(normalizedRoot, candidate);
        if (normalizedCandidate.equals(normalizedRoot) || isFileSystemRoot(normalizedCandidate)) {
            throw new IllegalArgumentException("refusing to operate on a root directory");
        }
        return normalizedCandidate;
    }

    public static Path resolveWithin(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relative path must not be blank");
        }
        Path relative = Paths.get(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("absolute paths are not allowed");
        }
        return requireWithin(root, absolute(root).resolve(relative).normalize());
    }

    public static Path realPathWithin(Path root, Path candidate) throws IOException {
        Path realRoot = root.toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot)) {
            throw new IllegalArgumentException("resolved path escapes allowed root");
        }
        return realCandidate;
    }

    /** Rejects a symbolic link at the allowed root or any component below it. */
    public static Path requireNoSymbolicLinks(Path root, Path candidate) {
        Path normalizedRoot = absolute(root);
        Path normalizedCandidate = requireWithin(normalizedRoot, candidate);
        Path current = normalizedRoot;
        if (Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("symbolic links are not allowed");
        }
        for (Path part : normalizedRoot.relativize(normalizedCandidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("symbolic links are not allowed");
            }
        }
        return normalizedCandidate;
    }

    public static boolean isFileSystemRoot(Path path) {
        Path absolute = absolute(path);
        return absolute.getParent() == null;
    }

    private static Path absolute(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        return path.toAbsolutePath().normalize();
    }

    private static boolean isWindowsDriveRoot(String path) {
        return path.matches("(?i)^[a-z]:/?$");
    }
}
