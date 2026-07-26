package ani.rss.config;

import ani.rss.auth.AuthenticationFailureException;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.exception.ResultException;
import ani.rss.exception.ApiProblemException;
import ani.rss.exception.UpstreamServiceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class CustomExceptionHandler {

    @ExceptionHandler(ApiProblemException.class)
    public Object apiProblem(ApiProblemException e, HttpServletRequest request) {
        if (!isV2(request)) {
            return Result.error(e.getMessage());
        }
        ProblemDetail detail = problem(e.status(), e.code(), e.getMessage(), e.operationId());
        e.properties().forEach(detail::setProperty);
        return detail;
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public Object upstreamFailure(UpstreamServiceException e, HttpServletRequest request) {
        if (isV2(request)) {
            return problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_FAILURE", e.getMessage());
        }
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(AuthenticationFailureException.class)
    public Object authentication(AuthenticationFailureException e, HttpServletRequest request) {
        if (isV2(request)) {
            return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", e.getMessage());
        }
        return Result.error(e.getMessage());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public Object malformedRequest(Exception e, HttpServletRequest request) {
        if (isV2(request)) {
            return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request syntax is invalid");
        }
        return Result.error("请求格式错误");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Object unsupportedMediaType(HttpServletRequest request) {
        if (isV2(request)) {
            return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "request media type is not supported");
        }
        return Result.error("不支持的请求类型");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object uploadTooLarge(HttpServletRequest request) {
        if (isV2(request)) {
            return problem(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "uploaded file is too large");
        }
        return Result.error("上传文件过大");
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class
    })
    public Object exception(Exception e, HttpServletRequest request) {
        if (isV2(request)) {
            HttpStatus status = e instanceof IllegalArgumentException
                    ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
            return problem(status,
                    e instanceof IllegalArgumentException ? "INVALID_REQUEST" : "STATE_CONFLICT",
                    e.getMessage());
        }
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(ResultException.class)
    public Object resultException(ResultException e, HttpServletRequest request) {
        if (isV2(request)) {
            int code = e.getResult().getCode();
            HttpStatus status = code == ResultCode.HTTP_FORBIDDEN
                    ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
            return problem(status, status == HttpStatus.UNAUTHORIZED ? "AUTH_REQUIRED" : "REQUEST_REJECTED",
                    e.getResult().getMessage());
        }
        return e.getResult();
    }

    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class
    })
    public Object notFoundException(HttpServletRequest request) {
        if (isV2(request)) {
            return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "resource not found");
        }
        return new Result<>(404, "404 Not Found !");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Object methodNotAllowed(HttpServletRequest request) {
        if (isV2(request)) {
            return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "method not allowed");
        }
        return new Result<>(HttpStatus.METHOD_NOT_ALLOWED.value(), "405 Method Not Allowed !");
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e, HttpServletRequest request) {
        String operationId = java.util.UUID.randomUUID().toString();
        log.error("request failed operationId:{} type:{}", operationId, e.getClass().getSimpleName());
        if (isV2(request)) {
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "request failed", operationId);
        }
        return Result.error("请求失败，operationId: " + operationId);
    }

    private static boolean isV2(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        return "/api/v2".equals(uri) || uri.startsWith("/api/v2/");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        return problem(status, code, detail, java.util.UUID.randomUUID().toString());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail, String operationId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                detail == null || detail.isBlank() ? status.getReasonPhrase() : detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("operationId", operationId);
        return problem;
    }

}
