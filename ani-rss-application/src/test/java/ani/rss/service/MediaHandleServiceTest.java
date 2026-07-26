package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.controller.v2.MediaController;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.util.other.ConfigUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.RandomAccessFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaHandleServiceTest {
    @TempDir
    Path tempDir;
    private Config original;

    @BeforeEach
    void setUp() throws Exception {
        original = ConfigUtil.snapshot();
        System.setProperty("CONFIG", tempDir.toString());
        Files.deleteIfExists(tempDir.resolve("auth-state.v2.json"));
        Config config = ConfigUtil.copy(original).setLogin(new Login()
                .setUsername("media-user")
                .setPassword(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("media-pass")));
        ConfigUtil.sync(config);
        AuthService.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        ConfigUtil.sync(original);
        Files.deleteIfExists(tempDir.resolve("auth-state.v2.json"));
        System.clearProperty("CONFIG");
    }

    @Test
    void rangeAndHeadResponsesUseTheIssuedHandle() throws Exception {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AuthService.LoginResult login = AuthService.login("media-user", "media-pass", loginRequest, loginResponse);
        MockHttpServletRequest request = authenticatedRequest(loginResponse, login.csrfToken(), "GET");
        Path file = tempDir.resolve("video.mp4");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);

        MediaHandleService handles = new MediaHandleService();
        String handle = handles.issue(file, tempDir, request);
        MediaController controller = new MediaController(handles);
        request.addHeader("Range", "bytes=2-4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.media(handle, request, response);

        assertEquals(206, response.getStatus());
        assertEquals("bytes 2-4/10", response.getHeader("Content-Range"));
        assertEquals("234", response.getContentAsString());

        MockHttpServletRequest full = authenticatedRequest(loginResponse, login.csrfToken(), "GET");
        MockHttpServletResponse fullResponse = new MockHttpServletResponse();
        controller.media(handle, full, fullResponse);
        assertEquals(200, fullResponse.getStatus());
        assertEquals("0123456789", fullResponse.getContentAsString());
        assertEquals("bytes", fullResponse.getHeader("Accept-Ranges"));

        MockHttpServletRequest head = authenticatedRequest(loginResponse, login.csrfToken(), "HEAD");
        MockHttpServletResponse headResponse = new MockHttpServletResponse();
        controller.media(handle, head, headResponse);
        assertEquals(200, headResponse.getStatus());
        assertEquals("10", headResponse.getHeader("Content-Length"));
        assertEquals("", headResponse.getContentAsString());
    }

    @Test
    void multipleRangesAndOtherSessionsAreRejected() throws Exception {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AuthService.LoginResult login = AuthService.login("media-user", "media-pass", loginRequest, loginResponse);
        MockHttpServletRequest request = authenticatedRequest(loginResponse, login.csrfToken(), "GET");
        Path file = tempDir.resolve("video.mp4");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        MediaHandleService handles = new MediaHandleService();
        String handle = handles.issue(file, tempDir, request);

        request.addHeader("Range", "bytes=0-1,3-4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MediaController(handles).media(handle, request, response);
        assertEquals(416, response.getStatus());

        MockHttpServletRequest otherLoginRequest = new MockHttpServletRequest();
        MockHttpServletResponse otherLoginResponse = new MockHttpServletResponse();
        AuthService.LoginResult otherLogin = AuthService.login(
                "media-user", "media-pass", otherLoginRequest, otherLoginResponse);
        MockHttpServletRequest other = authenticatedRequest(otherLoginResponse, otherLogin.csrfToken(), "GET");
        assertThrows(IllegalArgumentException.class, () -> handles.resolve(handle, other));
    }

    @Test
    void supportsClosedOpenAndSuffixRangesAndRejectsUnsatisfiedRange() throws Exception {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AuthService.LoginResult login = AuthService.login("media-user", "media-pass", loginRequest, loginResponse);
        Path file = tempDir.resolve("video.mp4");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        MediaHandleService handles = new MediaHandleService();
        MockHttpServletRequest issueRequest = authenticatedRequest(loginResponse, login.csrfToken(), "GET");
        String handle = handles.issue(file, tempDir, issueRequest);
        MediaController controller = new MediaController(handles);

        assertRange(controller, handle, loginResponse, login.csrfToken(), "bytes=0-0", 206, "0");
        assertRange(controller, handle, loginResponse, login.csrfToken(), "bytes=5-", 206, "56789");
        assertRange(controller, handle, loginResponse, login.csrfToken(), "bytes=-3", 206, "789");
        assertRange(controller, handle, loginResponse, login.csrfToken(), "bytes=10-", 416, "");
        assertRange(controller, handle, loginResponse, login.csrfToken(), "invalid", 416, "");
    }

    @Test
    void streamsRangesBeyondTheTwoGigabyteBoundary() throws Exception {
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AuthService.LoginResult login = AuthService.login(
                "media-user", "media-pass", new MockHttpServletRequest(), loginResponse);
        Path file = tempDir.resolve("large-video.mkv");
        long offset = (long) Integer.MAX_VALUE + 1;
        try (RandomAccessFile random = new RandomAccessFile(file.toFile(), "rw")) {
            random.setLength(offset + 16);
            random.seek(offset);
            random.write("XYZ".getBytes(StandardCharsets.US_ASCII));
        }
        MediaHandleService handles = new MediaHandleService();
        String handle = handles.issue(file, tempDir,
                authenticatedRequest(loginResponse, login.csrfToken(), "GET"));
        MockHttpServletRequest request = authenticatedRequest(loginResponse, login.csrfToken(), "GET");
        request.addHeader("Range", "bytes=" + offset + "-" + (offset + 2));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new MediaController(handles).media(handle, request, response);

        assertEquals(206, response.getStatus());
        assertEquals("XYZ", response.getContentAsString());
        assertEquals("3", response.getHeader("Content-Length"));
    }

    @Test
    void externalPlayerHandleNeedsNoCookieButRemainsClientAddressBound() throws Exception {
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("192.0.2.40");
        AuthService.LoginResult login = AuthService.login(
                "media-user", "media-pass", loginRequest, loginResponse);
        Path file = tempDir.resolve("video.mp4");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        MediaHandleService handles = new MediaHandleService();
        MockHttpServletRequest sessionRequest = authenticatedRequest(
                loginResponse, login.csrfToken(), "POST");
        sessionRequest.setRemoteAddr("192.0.2.40");
        String sessionHandle = handles.issue(file, tempDir, sessionRequest);
        String external = handles.issueExternal(sessionHandle, sessionRequest);

        MockHttpServletRequest player = new MockHttpServletRequest();
        player.setMethod("GET");
        player.setRemoteAddr("192.0.2.40");
        assertEquals(file.toRealPath(), handles.resolve(external, player).path());

        MockHttpServletRequest otherClient = new MockHttpServletRequest();
        otherClient.setMethod("GET");
        otherClient.setRemoteAddr("192.0.2.41");
        assertThrows(ani.rss.auth.AuthenticationFailureException.class,
                () -> handles.resolve(external, otherClient));
    }

    private static void assertRange(MediaController controller, String handle,
                                    MockHttpServletResponse loginResponse, String csrf,
                                    String range, int status, String body) throws Exception {
        MockHttpServletRequest request = authenticatedRequest(loginResponse, csrf, "GET");
        request.addHeader("Range", range);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.media(handle, request, response);
        assertEquals(status, response.getStatus());
        assertEquals(body, response.getContentAsString());
    }

    private static MockHttpServletRequest authenticatedRequest(
            MockHttpServletResponse loginResponse, String csrf, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI("/api/v2/media/test");
        request.setCookies(cookie(loginResponse, AuthService.SESSION_COOKIE),
                cookie(loginResponse, AuthService.CSRF_COOKIE));
        request.addHeader(AuthService.CSRF_HEADER, csrf);
        return request;
    }

    private static Cookie cookie(MockHttpServletResponse response, String name) {
        String header = response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }
}
