package ani.rss.exception;

/** A bounded outbound operation failed without exposing its URL or response body. */
public class UpstreamServiceException extends IllegalStateException {
    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
