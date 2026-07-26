package ani.rss.download;

/** Stable downloader failure metadata that is safe to expose outside an adapter. */
public final class DownloaderOperationException extends RuntimeException {
    private final DownloaderStatus status;
    private final String errorCode;
    private final boolean retryable;

    private DownloaderOperationException(
            DownloaderStatus status, String errorCode, boolean retryable, Throwable cause) {
        super(errorCode, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public static DownloaderOperationException rejected(String errorCode) {
        return new DownloaderOperationException(DownloaderStatus.REJECTED, errorCode, false, null);
    }

    public static DownloaderOperationException failed(String errorCode, boolean retryable) {
        return new DownloaderOperationException(DownloaderStatus.FAILED, errorCode, retryable, null);
    }

    public static DownloaderOperationException failed(
            String errorCode, boolean retryable, Throwable cause) {
        return new DownloaderOperationException(DownloaderStatus.FAILED, errorCode, retryable, cause);
    }

    public static DownloaderOperationException http(String adapter, int statusCode) {
        String prefix = adapter == null || adapter.isBlank()
                ? "DOWNLOADER"
                : adapter.toUpperCase(java.util.Locale.ROOT);
        if (statusCode == 401 || statusCode == 403) {
            return rejected(prefix + "_AUTHENTICATION_FAILED");
        }
        if (statusCode >= 500 && statusCode <= 599) {
            return failed(prefix + "_HTTP_" + statusCode, true);
        }
        return rejected(prefix + "_HTTP_" + statusCode);
    }

    public DownloaderStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
