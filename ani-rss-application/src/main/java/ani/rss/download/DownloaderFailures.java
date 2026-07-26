package ani.rss.download;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Converts implementation exceptions to stable, non-sensitive operation results. */
public final class DownloaderFailures {
    private DownloaderFailures() {
    }

    public static <T> DownloaderResult<T> result(Throwable throwable) {
        return result(throwable, null);
    }

    public static <T> DownloaderResult<T> result(Throwable throwable, String remoteTaskId) {
        DownloaderOperationException operation = find(throwable, DownloaderOperationException.class);
        if (operation != null) {
            return new DownloaderResult<>(operation.status(), operation.errorCode(),
                    operation.retryable(), remoteTaskId, null);
        }
        if (find(throwable, SocketTimeoutException.class) != null) {
            return new DownloaderResult<>(DownloaderStatus.FAILED, "DOWNLOADER_TIMEOUT",
                    true, remoteTaskId, null);
        }
        if (find(throwable, ConnectException.class) != null ||
                find(throwable, NoRouteToHostException.class) != null ||
                find(throwable, UnknownHostException.class) != null ||
                find(throwable, SocketException.class) != null) {
            return new DownloaderResult<>(DownloaderStatus.FAILED, "DOWNLOADER_CONNECTION_FAILED",
                    true, remoteTaskId, null);
        }
        if (find(throwable, IOException.class) != null) {
            return new DownloaderResult<>(DownloaderStatus.FAILED, "DOWNLOADER_IO_FAILURE",
                    false, remoteTaskId, null);
        }
        if (find(throwable, IllegalArgumentException.class) != null) {
            return new DownloaderResult<>(DownloaderStatus.REJECTED, "DOWNLOADER_INVALID_REQUEST",
                    false, remoteTaskId, null);
        }
        return new DownloaderResult<>(DownloaderStatus.FAILED, "DOWNLOADER_INTERNAL_FAILURE",
                false, remoteTaskId, null);
    }

    public static boolean isRetryable(Throwable throwable) {
        return result(throwable).retryable();
    }

    private static <T extends Throwable> T find(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
