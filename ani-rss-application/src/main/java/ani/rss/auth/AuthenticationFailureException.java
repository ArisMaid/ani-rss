package ani.rss.auth;

/** A credential or one-time-code failure that must be reported as HTTP 401. */
public class AuthenticationFailureException extends IllegalArgumentException {
    public AuthenticationFailureException(String message) {
        super(message);
    }
}
