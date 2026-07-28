package ani.rss.ownership;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Shared, non-recursive filesystem primitives for ownership transactions. */
final class FileTransactionSupport {
    private FileTransactionSupport() {
    }

    static Path requireParent(Path path, String message) {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalStateException(message);
        }
        return parent;
    }

    static void pruneEmptyParents(Path current, Path stopAt, boolean deleteStopAt) throws IOException {
        while (current != null && current.startsWith(stopAt) &&
                (deleteStopAt || !current.equals(stopAt))) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                if (stream.iterator().hasNext()) {
                    return;
                }
            }
            Files.delete(current);
            if (current.equals(stopAt)) {
                return;
            }
            current = current.getParent();
        }
    }

    static IllegalStateException rollbackMoves(
            List<Move> moved,
            String collisionMessage,
            String failureMessage,
            String parentMessage) {
        return rollbackMoves(moved, collisionMessage, failureMessage, parentMessage, ignored -> { });
    }

    static IllegalStateException rollbackMoves(
            List<Move> moved,
            String collisionMessage,
            String failureMessage,
            String parentMessage,
            AfterMove afterMove) {
        IllegalStateException failure = null;
        for (int i = moved.size() - 1; i >= 0; i--) {
            Move file = moved.get(i);
            try {
                if (!Files.exists(file.target(), LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.exists(file.source(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(collisionMessage);
                }
                Files.createDirectories(requireParent(file.source(), parentMessage));
                Files.move(file.target(), file.source(), StandardCopyOption.ATOMIC_MOVE);
                afterMove.accept(file);
            } catch (Exception rollbackError) {
                if (failure == null) {
                    failure = new IllegalStateException(failureMessage);
                }
                failure.addSuppressed(rollbackError);
            }
        }
        return failure;
    }

    record Move(Path source, Path target) {
    }

    @FunctionalInterface
    interface AfterMove {
        void accept(Move move) throws Exception;
    }
}
