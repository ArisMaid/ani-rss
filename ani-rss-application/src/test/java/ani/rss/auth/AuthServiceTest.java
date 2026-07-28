package ani.rss.auth;

import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.service.ConfigService;
import ani.rss.controller.v2.ConfigV2Controller;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.AuthUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.crypto.SecureUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    @TempDir
    Path tempDir;
    private Config original;

    @BeforeEach
    void setUp() throws Exception {
        original = ConfigUtil.snapshot();
        System.setProperty("CONFIG", tempDir.toString());
        Files.deleteIfExists(tempDir.resolve("auth-state.v2.json"));
        Files.deleteIfExists(tempDir.resolve("initial-setup-code.txt"));
        Config fresh = ConfigUtil.copy(original)
                .setLogin(new Login().setUsername("").setPassword(""))
                .setApiKey("");
        ConfigUtil.sync(fresh);
    }

    @AfterEach
    void tearDown() throws Exception {
        ConfigUtil.sync(original);
        Files.deleteIfExists(tempDir.resolve("auth-state.v2.json"));
        Files.deleteIfExists(tempDir.resolve("initial-setup-code.txt"));
        System.clearProperty("CONFIG");
        LogUtil.loadLogback();
    }

    @Test
    void blankLoginUsesSanitizedAdminCredentialsAndStoresPrivateVerifier() throws Exception {
        Files.writeString(tempDir.resolve("initial-setup-code.txt"), "obsolete");
        Files.writeString(tempDir.resolve("auth-state.v2.json"), """
                {"setupCodeHash":"obsolete","setupExpiresAt":1,"setupUsed":false}
                """);
        AuthService.reload();

        Login stored = ConfigUtil.snapshot().getLogin();
        assertEquals("admin", stored.getUsername());
        assertTrue(stored.getPassword().matches("(?i)[0-9a-f]{32}"));
        assertFalse(Files.exists(tempDir.resolve("initial-setup-code.txt")));
        assertFalse(Files.readString(tempDir.resolve("auth-state.v2.json")).contains("setupCodeHash"));
        assertTrue(Files.readString(tempDir.resolve("auth-state.v2.json")).contains("argon2PasswordHash"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthService.LoginResult result = AuthService.login(
                "admin", "admin", null, response);

        assertEquals("admin", result.username());
        assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(v -> v.startsWith("ANI_SESSION=")));
        String sessionHeader = cookieHeader(response, AuthService.SESSION_COOKIE);
        String csrfHeader = cookieHeader(response, AuthService.CSRF_COOKIE);
        assertTrue(sessionHeader.contains("HttpOnly"));
        assertTrue(sessionHeader.contains("SameSite=Strict"));
        assertFalse(csrfHeader.contains("HttpOnly"));
        assertTrue(csrfHeader.contains("SameSite=Strict"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v2/config");
        request.setCookies(sessionCookie(response), csrfCookie(response));
        assertFalse(AuthService.validateRequest(request));
        request.addHeader(AuthService.CSRF_HEADER, result.csrfToken());
        assertTrue(AuthService.validateRequest(request));
    }

    @Test
    void existingNonEmptyLoginIsNotOverwrittenByDefaultCredentials() {
        String existingHash = AuthService.encodePassword("existing-password");
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator").setPassword(existingHash));
        ConfigUtil.sync(configured);

        AuthService.reload();

        Login stored = ConfigUtil.snapshot().getLogin();
        assertEquals("operator", stored.getUsername());
        assertEquals(existingHash, stored.getPassword());
        AuthService.login("operator", "existing-password", null, null);
    }

    @Test
    void upstreamMd5PasswordRemainsCompatibleAndGainsPrivateVerifierOnLogin() throws Exception {
        Config legacy = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("legacy").setPassword(SecureUtil.md5("secret-pass")));
        ConfigUtil.sync(legacy);
        Files.deleteIfExists(tempDir.resolve("auth-state.v2.json"));
        AuthService.initialize();

        assertThrows(IllegalArgumentException.class,
                () -> AuthService.login("legacy", SecureUtil.md5("secret-pass"), null, null));
        AuthService.login("legacy", "secret-pass", null, null);

        assertEquals(SecureUtil.md5("secret-pass"), ConfigUtil.snapshot().getLogin().getPassword());
        assertTrue(Files.readString(tempDir.resolve("auth-state.v2.json")).contains("argon2PasswordHash"));
    }

    @Test
    void legacyTokenCanOnlyBeExchangedDuringMigrationWindow() throws Exception {
        Config legacy = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("legacy").setPassword(SecureUtil.md5("secret-pass")));
        ConfigUtil.sync(legacy);
        AuthService.reload();
        String legacyToken = AuthUtil.getAuth(AuthUtil.getLogin());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("Authorization", legacyToken);

        assertFalse(AuthUtil.test(request,
                ConfigV2Controller.class.getMethod("config").getAnnotation(ani.rss.annotation.Auth.class)));

        MockHttpServletRequest migration = new MockHttpServletRequest();
        migration.setRemoteAddr("198.51.100.20");
        migration.addHeader("Authorization", legacyToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthService.migrateLegacy(migration, response);
        assertTrue(response.getHeaders("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith(AuthService.SESSION_COOKIE + "=")));

        MockHttpServletRequest queryOnly = new MockHttpServletRequest();
        queryOnly.setParameter("s", legacyToken);
        assertThrows(AuthenticationFailureException.class,
                () -> AuthService.migrateLegacy(queryOnly, new MockHttpServletResponse()));

        Object state = ReflectionTestUtils.getField(AuthService.class, "state");
        ReflectionTestUtils.setField(state, "legacyMigrationUntil", 1L);
        assertThrows(AuthenticationFailureException.class,
                () -> AuthService.migrateLegacy(migration, new MockHttpServletResponse()));
    }

    @Test
    void legacyTokenSurvivesCompatibleMd5LoginAndAuthStateReload() throws Exception {
        Config legacy = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("legacy").setPassword(SecureUtil.md5("secret-pass")))
                .setMultiLoginForbidden(false);
        ConfigUtil.sync(legacy);
        AuthService.reload();
        String legacyToken = AuthUtil.getAuth(AuthUtil.getLogin());

        AuthService.login("legacy", "secret-pass", null, new MockHttpServletResponse());
        assertTrue(ConfigUtil.snapshot().getLogin().getPassword().matches("(?i)[0-9a-f]{32}"));
        AuthService.reload();

        MockHttpServletRequest migration = new MockHttpServletRequest();
        migration.addHeader("Authorization", legacyToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthService.migrateLegacy(migration, response);

        assertTrue(response.getHeaders("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith(AuthService.SESSION_COOKIE + "=")));
    }

    @Test
    void configUpdateKeepsAnUpstreamCompatibleDigestAndPrivateVerifier() throws Exception {
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator")
                        .setPassword(AuthService.encodePassword("old-password")));
        ConfigUtil.sync(configured);
        MockHttpServletResponse oldLogin = new MockHttpServletResponse();
        AuthService.login("operator", "old-password", new MockHttpServletRequest(), oldLogin);
        Config candidate = ConfigUtil.snapshot();
        candidate.getLogin().setPassword("replacement-password");

        new ConfigService().setConfig(candidate);

        String stored = ConfigUtil.snapshot().getLogin().getPassword();
        assertNotEquals("replacement-password", stored);
        assertTrue(stored.matches("(?i)[0-9a-f]{32}"));
        assertTrue(Files.readString(tempDir.resolve("auth-state.v2.json")).contains("argon2PasswordHash"));
        MockHttpServletRequest oldSession = new MockHttpServletRequest();
        oldSession.setMethod("GET");
        oldSession.setCookies(sessionCookie(oldLogin));
        assertFalse(AuthService.validateRequest(oldSession));
        AuthService.login("operator", "replacement-password", null, null);
    }

    @Test
    void oldLocalArgonConfigMigratesToAnUpstreamCompatibleDigestAfterLogin() throws Exception {
        String oldLocalHash = AuthService.encodePassword("old-local-password");
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator").setPassword(oldLocalHash));
        ConfigUtil.sync(configured);
        Files.deleteIfExists(tempDir.resolve("auth-state.v2.json"));
        AuthService.reload();

        AuthService.login("operator", "old-local-password", null, null);

        assertEquals(SecureUtil.md5("old-local-password"), ConfigUtil.snapshot().getLogin().getPassword());
        assertTrue(Files.readString(tempDir.resolve("auth-state.v2.json")).contains("argon2PasswordHash"));
    }

    @Test
    void configUpdatePreservesMd5SubmittedByAnUpstreamCompatibleClient() throws Exception {
        String oldHash = SecureUtil.md5("old-compatible-password");
        String replacementHash = SecureUtil.md5("replacement-compatible-password");
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator").setPassword(oldHash));
        ConfigUtil.sync(configured);
        AuthService.reload();
        AuthService.login("operator", "old-compatible-password", null, null);

        Config candidate = ConfigUtil.snapshot();
        candidate.getLogin().setPassword(replacementHash);
        new ConfigService().setConfig(candidate);

        assertEquals(replacementHash, ConfigUtil.snapshot().getLogin().getPassword());
        String state = Files.readString(tempDir.resolve("auth-state.v2.json"));
        assertFalse(state.contains("argon2PasswordHash"));
    }

    @Test
    void sessionHonorsIpBindingAndMultiLoginPolicy() {
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator")
                        .setPassword(AuthService.encodePassword("session-password")))
                .setVerifyLoginIp(true)
                .setMultiLoginForbidden(false);
        ConfigUtil.sync(configured);

        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AuthService.login("operator", "session-password", loginRequest, loginResponse);

        MockHttpServletRequest sameIp = new MockHttpServletRequest();
        sameIp.setMethod("GET");
        sameIp.setRemoteAddr("192.0.2.10");
        sameIp.setCookies(sessionCookie(loginResponse));
        assertTrue(AuthService.validateRequest(sameIp));

        MockHttpServletRequest otherIp = new MockHttpServletRequest();
        otherIp.setMethod("GET");
        otherIp.setRemoteAddr("192.0.2.11");
        otherIp.setCookies(sessionCookie(loginResponse));
        assertFalse(AuthService.validateRequest(otherIp));

        configured.setVerifyLoginIp(false).setMultiLoginForbidden(true);
        ConfigUtil.sync(configured);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        AuthService.login("operator", "session-password", new MockHttpServletRequest(), firstResponse);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        AuthService.login("operator", "session-password", new MockHttpServletRequest(), secondResponse);

        MockHttpServletRequest firstSession = new MockHttpServletRequest();
        firstSession.setMethod("GET");
        firstSession.setCookies(sessionCookie(firstResponse));
        assertFalse(AuthService.validateRequest(firstSession));
        MockHttpServletRequest secondSession = new MockHttpServletRequest();
        secondSession.setMethod("GET");
        secondSession.setCookies(sessionCookie(secondResponse));
        assertTrue(AuthService.validateRequest(secondSession));
    }

    @Test
    void oauthStateIsSessionBoundSingleUseAndCannotBeInvalidatedByAnotherSession() {
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator")
                        .setPassword(AuthService.encodePassword("session-password")))
                .setMultiLoginForbidden(false)
                .setVerifyLoginIp(false);
        ConfigUtil.sync(configured);
        AuthService.reload();

        MockHttpServletResponse firstLogin = new MockHttpServletResponse();
        AuthService.login("operator", "session-password", new MockHttpServletRequest(), firstLogin);
        MockHttpServletRequest first = oauthRequest(firstLogin);
        AuthService.OAuthStateResult issued = AuthService.issueOAuthState("bgm", first);

        MockHttpServletResponse secondLogin = new MockHttpServletResponse();
        AuthService.login("operator", "session-password", new MockHttpServletRequest(), secondLogin);
        MockHttpServletRequest second = oauthRequest(secondLogin);
        assertThrows(AuthenticationFailureException.class,
                () -> AuthService.consumeOAuthState("bgm", issued.state(), second));

        AuthService.consumeOAuthState("bgm", issued.state(), first);
        assertThrows(AuthenticationFailureException.class,
                () -> AuthService.consumeOAuthState("bgm", issued.state(), first));
    }

    @Test
    void expiredServerSessionIsRejected() {
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator")
                        .setPassword(AuthService.encodePassword("session-password")));
        ConfigUtil.sync(configured);
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AuthService.login("operator", "session-password", new MockHttpServletRequest(), loginResponse);
        Cookie cookie = sessionCookie(loginResponse);

        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(AuthService.class, "SESSIONS");
        Object session = sessions.get(cookie.getValue());
        ReflectionTestUtils.setField(session, "expiresAt", 0L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setCookies(cookie);
        assertFalse(AuthService.validateRequest(request));
    }

    @Test
    void authReloadPreservesSessionsOnlyWhenExplicitlyRequested() {
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator")
                        .setPassword(AuthService.encodePassword("session-password")));
        ConfigUtil.sync(configured);
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AuthService.login("operator", "session-password", new MockHttpServletRequest(), loginResponse);
        Cookie cookie = sessionCookie(loginResponse);

        AuthService.reload(true);
        MockHttpServletRequest preserved = new MockHttpServletRequest();
        preserved.setMethod("GET");
        preserved.setCookies(cookie);
        assertTrue(AuthService.validateRequest(preserved));

        AuthService.reload(false);
        MockHttpServletRequest invalidated = new MockHttpServletRequest();
        invalidated.setMethod("GET");
        invalidated.setCookies(cookie);
        assertFalse(AuthService.validateRequest(invalidated));
    }

    @Test
    void ipWhitelistIssuesARealCsrfProtectedSessionInsteadOfBypassingAuth() {
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator")
                        .setPassword(AuthService.encodePassword("session-password")))
                .setIpWhitelist(true)
                .setIpWhitelistStr("192.0.2.25");
        ConfigUtil.sync(configured);
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("192.0.2.25");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();

        AuthService.LoginResult result = AuthService.loginFromIpWhitelist(loginRequest, loginResponse);

        MockHttpServletRequest stateChanging = new MockHttpServletRequest();
        stateChanging.setMethod("POST");
        stateChanging.setRemoteAddr("192.0.2.25");
        stateChanging.setRequestURI("/api/v2/config/proxy-test");
        stateChanging.setCookies(sessionCookie(loginResponse), csrfCookie(loginResponse));
        assertFalse(AuthService.validateRequest(stateChanging));
        stateChanging.addHeader(AuthService.CSRF_HEADER, result.csrfToken());
        assertTrue(AuthService.validateRequest(stateChanging));

        MockHttpServletRequest outsider = new MockHttpServletRequest();
        outsider.setRemoteAddr("192.0.2.26");
        assertThrows(AuthenticationFailureException.class,
                () -> AuthService.loginFromIpWhitelist(outsider, new MockHttpServletResponse()));
    }

    @Test
    void csrfExemptionMatchesOnlyTheExactLoginEndpoint() {
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator")
                        .setPassword(AuthService.encodePassword("session-password")));
        ConfigUtil.sync(configured);
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AuthService.login("operator", "session-password", new MockHttpServletRequest(), loginResponse);

        MockHttpServletRequest misleadingLogin = new MockHttpServletRequest();
        misleadingLogin.setMethod("POST");
        misleadingLogin.setRequestURI("/api/v2/ownership/login");
        misleadingLogin.setCookies(sessionCookie(loginResponse));
        assertFalse(AuthService.validateRequest(misleadingLogin));

        MockHttpServletRequest removedSetupEndpoint = new MockHttpServletRequest();
        removedSetupEndpoint.setMethod("POST");
        removedSetupEndpoint.setRequestURI("/api/v2/auth/setup");
        removedSetupEndpoint.setCookies(sessionCookie(loginResponse));
        assertFalse(AuthService.validateRequest(removedSetupEndpoint));
    }

    @Test
    void trustedHttpsReverseProxyProducesSecureCookies() {
        Config configured = ConfigUtil.copy(ConfigUtil.snapshot())
                .setLogin(new Login().setUsername("operator")
                        .setPassword(AuthService.encodePassword("session-password")))
                .setReverseProxyTrustIpListEnabled(true)
                .setReverseProxyTrustIpList(List.of("192.0.2.5"));
        ConfigUtil.sync(configured);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.5");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthService.login("operator", "session-password", request, response);

        assertTrue(response.getHeaders("Set-Cookie").stream()
                .allMatch(value -> value.contains("Secure")));
    }

    private static Cookie sessionCookie(MockHttpServletResponse response) {
        return cookie(response, AuthService.SESSION_COOKIE);
    }

    private static MockHttpServletRequest oauthRequest(MockHttpServletResponse response) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/bgm/oauth/callback");
        Cookie csrf = csrfCookie(response);
        request.setCookies(sessionCookie(response), csrf);
        request.addHeader(AuthService.CSRF_HEADER, csrf.getValue());
        return request;
    }

    private static Cookie csrfCookie(MockHttpServletResponse response) {
        return cookie(response, AuthService.CSRF_COOKIE);
    }

    private static Cookie cookie(MockHttpServletResponse response, String name) {
        String header = cookieHeader(response, name);
        String value = header.substring(name.length() + 1, header.indexOf(';'));
        return new Cookie(name, value);
    }

    private static String cookieHeader(MockHttpServletResponse response, String name) {
        return response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
    }
}
