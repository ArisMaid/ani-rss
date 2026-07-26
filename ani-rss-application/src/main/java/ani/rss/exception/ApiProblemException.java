package ani.rss.exception;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A typed v2 API failure with stable machine-readable metadata. */
public final class ApiProblemException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String operationId;
    private final Map<String, Object> properties;

    public ApiProblemException(HttpStatus status, String code, String message,
                               String operationId, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.operationId = operationId == null || operationId.isBlank()
                ? UUID.randomUUID().toString() : operationId;
        this.properties = properties == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(properties));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String operationId() {
        return operationId;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
