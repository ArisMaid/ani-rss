package ani.rss.config;

import ani.rss.auth.AuthenticationFailureException;
import ani.rss.exception.UpstreamServiceException;
import ani.rss.exception.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CustomExceptionHandlerTest {
    @Test
    void v2ErrorsUseProblemDetailWithStableCodeAndOperationId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v2/config");
        Object result = new CustomExceptionHandler().exception(
                new IllegalArgumentException("bad candidate"), request);

        ProblemDetail problem = (ProblemDetail) result;
        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.getStatus());
        assertEquals("INVALID_REQUEST", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("operationId"));
    }

    @Test
    void v2MethodErrorsUse405ProblemDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v2/config");

        ProblemDetail problem = (ProblemDetail) new CustomExceptionHandler().methodNotAllowed(request);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), problem.getStatus());
        assertEquals("METHOD_NOT_ALLOWED", problem.getProperties().get("code"));
    }

    @Test
    void v2CredentialFailuresUse401ProblemDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v2/auth/login");

        ProblemDetail problem = (ProblemDetail) new CustomExceptionHandler().authentication(
                new AuthenticationFailureException("invalid username or password"), request);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), problem.getStatus());
        assertEquals("AUTHENTICATION_FAILED", problem.getProperties().get("code"));
    }

    @Test
    void v2UpstreamFailuresUse502WithoutResponseDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v2/images");

        ProblemDetail problem = (ProblemDetail) new CustomExceptionHandler().upstreamFailure(
                new UpstreamServiceException("image fetch failed", new IOException("secret body")), request);

        assertEquals(HttpStatus.BAD_GATEWAY.value(), problem.getStatus());
        assertEquals("UPSTREAM_FAILURE", problem.getProperties().get("code"));
        assertEquals("image fetch failed", problem.getDetail());
    }

    @Test
    void typedApiProblemsKeepStableMetadata() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v2/config/proxy-test");
        ApiProblemException failure = new ApiProblemException(
                HttpStatus.BAD_GATEWAY, "PROXY_TEST_FAILED", "proxy test failed",
                "operation-1", Map.of("failureType", "TIMEOUT"));

        ProblemDetail problem = (ProblemDetail) new CustomExceptionHandler().apiProblem(failure, request);

        assertEquals(HttpStatus.BAD_GATEWAY.value(), problem.getStatus());
        assertEquals("PROXY_TEST_FAILED", problem.getProperties().get("code"));
        assertEquals("operation-1", problem.getProperties().get("operationId"));
        assertEquals("TIMEOUT", problem.getProperties().get("failureType"));
    }
}
