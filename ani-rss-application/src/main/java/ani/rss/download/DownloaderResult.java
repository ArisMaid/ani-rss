package ani.rss.download;

public record DownloaderResult<T>(
        DownloaderStatus status,
        String errorCode,
        boolean retryable,
        String remoteTaskId,
        T value
) {
    public static <T> DownloaderResult<T> success(T value) {
        return new DownloaderResult<>(DownloaderStatus.SUCCESS, null, false, null, value);
    }

    public static <T> DownloaderResult<T> success(T value, String remoteTaskId) {
        return new DownloaderResult<>(DownloaderStatus.SUCCESS, null, false, remoteTaskId, value);
    }

    public static <T> DownloaderResult<T> rejected(String errorCode) {
        return new DownloaderResult<>(DownloaderStatus.REJECTED, errorCode, false, null, null);
    }

    public static <T> DownloaderResult<T> failed(String errorCode, boolean retryable) {
        return new DownloaderResult<>(DownloaderStatus.FAILED, errorCode, retryable, null, null);
    }

    public boolean isSuccess() {
        return status == DownloaderStatus.SUCCESS;
    }
}
