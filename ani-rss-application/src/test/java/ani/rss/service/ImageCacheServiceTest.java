package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.auth.AuthenticationFailureException;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.util.other.ConfigUtil;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageCacheServiceTest {
    @TempDir
    Path tempDir;
    private Config original;

    @BeforeEach
    void setUp() {
        original = ConfigUtil.snapshot();
        System.setProperty("CONFIG", tempDir.toString());
        Config config = ConfigUtil.copy(original)
                .setLogin(new Login().setUsername("image-user")
                        .setPassword(AuthService.encodePassword("image-password")))
                .setImagePrivateAllowlist("127.0.0.1")
                .setMultiLoginForbidden(false);
        ConfigUtil.sync(config);
        AuthService.reload();
    }

    @AfterEach
    void tearDown() {
        AuthService.invalidateSessions();
        ConfigUtil.sync(original);
        System.clearProperty("CONFIG");
    }

    @Test
    void deduplicatesWithinSessionAndBindsIdsToThatSession() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            exchange.getResponseBody().write(image);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            MockHttpServletResponse firstLogin = login();
            MockHttpServletRequest first = authenticated(firstLogin, "POST");
            ImageCacheService service = new ImageCacheService();

            ImageCacheService.ImageRef firstRef = service.cache(url, first);
            ImageCacheService.ImageRef duplicate = service.cache(url, first);
            assertEquals(firstRef.id(), duplicate.id());
            assertEquals(1, requests.get());

            MockHttpServletResponse secondLogin = login();
            MockHttpServletRequest second = authenticated(secondLogin, "POST");
            ImageCacheService.ImageRef secondRef = service.cache(url, second);
            assertNotEquals(firstRef.id(), secondRef.id());
            assertEquals(2, requests.get());
            assertThrows(IllegalArgumentException.class,
                    () -> service.resolve(firstRef.id(), authenticated(secondLogin, "GET")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingSessionAsAuthenticationFailure() {
        ImageCacheService service = new ImageCacheService();
        assertThrows(AuthenticationFailureException.class,
                () -> service.cache("https://example.com/image.png", new MockHttpServletRequest()));
    }

    private static MockHttpServletResponse login() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthService.login("image-user", "image-password", new MockHttpServletRequest(), response);
        return response;
    }

    private static MockHttpServletRequest authenticated(MockHttpServletResponse login, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI("/api/v2/images");
        Cookie session = cookie(login, AuthService.SESSION_COOKIE);
        Cookie csrf = cookie(login, AuthService.CSRF_COOKIE);
        request.setCookies(session, csrf);
        request.addHeader(AuthService.CSRF_HEADER, csrf.getValue());
        return request;
    }

    private static Cookie cookie(MockHttpServletResponse response, String name) {
        String header = response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }
}
