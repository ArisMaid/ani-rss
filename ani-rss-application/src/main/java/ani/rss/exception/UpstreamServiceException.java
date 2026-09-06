package ani.rss.exception;

/** A bounded outbound operation failed without exposing its URL or response body. */
public class UpstreamServiceException extends IllegalStateException {
    private final long retryAfterSeconds;

    public UpstreamServiceException(String message, Throwable cause) {
        this(message, cause, 0);
    }

    public UpstreamServiceException(String message, Throwable cause, long retryAfterSeconds) {
        super(message, cause);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
