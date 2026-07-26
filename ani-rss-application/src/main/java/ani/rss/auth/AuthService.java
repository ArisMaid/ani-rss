package ani.rss.auth;

import ani.rss.commons.AtomicFileWriter;
import ani.rss.commons.GsonStatic;
import ani.rss.auth.fun.IpWhitelist;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.util.other.AuthUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.crypto.SecureUtil;
import com.google.gson.JsonObject;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Browser authentication boundary with default credentials and server sessions. */
@Service
public class AuthService {
    public static final String SESSION_COOKIE = "ANI_SESSION";
    public static final String CSRF_COOKIE = "ANI_CSRF";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String STATE_FILE = "auth-state.v2.json";
    private static final String LEGACY_SETUP_CODE_FILE = "initial-setup-code.txt";
    public static final String DEFAULT_USERNAME = "Aris";
    private static final String DEFAULT_PASSWORD_HASH = "$argon2id$v=19$m=16384,t=2,p=1$jRb7PAbkuB/Y/KrATXIRdg$r8N7hVPgPfm9e1duDT6bmA9eB8qtFYXL5rBab0msJI4";
    private static final long LEGACY_MIGRATION_TTL = Duration.ofDays(30).toMillis();
    private static final long OAUTH_STATE_TTL = Duration.ofMinutes(10).toMillis();
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final PasswordEncoder PASSWORDS = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, OAuthState> OAUTH_STATES = new ConcurrentHashMap<>();
    private static final Object STATE_LOCK = new Object();
    private static AuthState state;
    private static Path loadedStatePath;
    private static boolean preserveSessionsOnNextStateLoad;
    private static boolean rewriteStateOnNextInitialize;

    public static void initialize() {
        synchronized (STATE_LOCK) {
            Path path = statePath();
            if (state == null || !path.equals(loadedStatePath)) {
                state = readState(path);
                if (!preserveSessionsOnNextStateLoad) {
                    SESSIONS.clear();
                }
                preserveSessionsOnNextStateLoad = false;
            }
            boolean stateChanged = rewriteStateOnNextInitialize;
            rewriteStateOnNextInitialize = false;
            Config config = ConfigUtil.snapshot();
            Login login = config.getLogin();
            long now = System.currentTimeMillis();
            if (isUnconfiguredLogin(login)) {
                Config candidate = ConfigUtil.copy(config);
                candidate.setLogin(new Login()
                        .setUsername(DEFAULT_USERNAME)
                        .setPassword(DEFAULT_PASSWORD_HASH));
                ConfigUtil.sync(candidate);
                stateChanged |= clearLegacyMigrationState();
            } else {
                if (isLegacyHash(login.getPassword())) {
                    if (state.legacyMigrationUntil == 0 && isBlank(state.legacyTokenHash)) {
                        state.legacyMigrationUntil = now + LEGACY_MIGRATION_TTL;
                        state.legacyTokenHash = legacyTokenFingerprint();
                        stateChanged = true;
                    } else if (state.legacyMigrationUntil > now && isBlank(state.legacyTokenHash)) {
                        state.legacyTokenHash = legacyTokenFingerprint();
                        stateChanged = true;
                    }
                } else if (state.legacyMigrationUntil <= now || isBlank(state.legacyTokenHash)) {
                    stateChanged |= clearLegacyMigrationState();
                }
            }
            if (stateChanged) {
                writeState(path, state);
            }
            removeLegacySetupCode();
            loadedStatePath = path;
        }
    }

    public static void reload() {
        reload(false);
    }

    public static void reload(boolean preserveSessions) {
        synchronized (STATE_LOCK) {
            state = null;
            loadedStatePath = null;
            preserveSessionsOnNextStateLoad = preserveSessions;
            rewriteStateOnNextInitialize = false;
            if (!preserveSessions) {
                SESSIONS.clear();
            }
        }
        initialize();
    }

    public static void invalidateSessions() {
        SESSIONS.clear();
        OAUTH_STATES.clear();
    }

    public static void invalidateLegacyMigration() {
        initialize();
        synchronized (STATE_LOCK) {
            if (clearLegacyMigrationState()) {
                writeState(statePath(), state);
            }
        }
    }

    public static String credentialFingerprint() {
        Login login = ConfigUtil.snapshot().getLogin();
        String username = login == null || login.getUsername() == null ? "" : login.getUsername();
        String password = login == null || login.getPassword() == null ? "" : login.getPassword();
        return SecureUtil.sha256(username + "\u0000" + password);
    }

    public static LoginResult login(String username, String password,
                                    HttpServletRequest request, HttpServletResponse response) {
        initialize();
        if (isBlank(username) || isBlank(password)) {
            throw new IllegalArgumentException("username and password are required");
        }
        Config config = ConfigUtil.snapshot();
        Login stored = config.getLogin();
        if (stored == null || isBlank(stored.getUsername()) || isBlank(stored.getPassword())) {
            throw new IllegalStateException("login configuration is incomplete");
        }
        if (!MessageDigest.isEqual(username.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                stored.getUsername().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new AuthenticationFailureException("invalid username or password");
        }

        boolean valid = false;
        boolean migrate = false;
        String encoded = stored.getPassword();
        if (encoded.startsWith("$argon2")) {
            valid = PASSWORDS.matches(password, encoded);
        } else if (isLegacyHash(encoded)) {
            valid = MessageDigest.isEqual(SecureUtil.md5(password).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    encoded.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            migrate = valid;
        }
        if (!valid) {
            throw new AuthenticationFailureException("invalid username or password");
        }
        if (migrate) {
            Config candidate = ConfigUtil.snapshot();
            candidate.getLogin().setPassword(PASSWORDS.encode(password));
            ConfigUtil.sync(candidate);
            SESSIONS.clear();
        }
        return issueSession(username, request, response);
    }

    public static boolean validateRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        initialize();
        String token = sessionToken(request);
        if (isBlank(token)) {
            return false;
        }
        Session session = SESSIONS.get(token);
        if (session == null || session.expiresAt < System.currentTimeMillis()) {
            SESSIONS.remove(token);
            return false;
        }
        Config config = ConfigUtil.snapshot();
        if (Boolean.TRUE.equals(config.getVerifyLoginIp()) &&
                !constantEquals(session.ip, AuthUtil.getIp(request))) {
            SESSIONS.remove(token);
            return false;
        }
        if (isUnsafe(request.getMethod()) && !isAuthEndpoint(request.getRequestURI())) {
            String csrf = request.getHeader(CSRF_HEADER);
            if (isBlank(csrf) || !constantEquals(csrf, session.csrf)) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasSessionCredential(HttpServletRequest request) {
        return !isBlank(sessionToken(request));
    }

    public static boolean validateLegacyRequest(HttpServletRequest request) {
        initialize();
        String expectedFingerprint;
        synchronized (STATE_LOCK) {
            if (state.legacyMigrationUntil <= System.currentTimeMillis() ||
                    isBlank(state.legacyTokenHash)) {
                return false;
            }
            expectedFingerprint = state.legacyTokenHash;
        }
        if (request == null) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        if (isBlank(authorization) || authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        return constantEquals(expectedFingerprint, SecureUtil.sha256(authorization));
    }

    public static boolean legacyTokensEnabled() {
        initialize();
        synchronized (STATE_LOCK) {
            return state.legacyMigrationUntil > System.currentTimeMillis() &&
                    !isBlank(state.legacyTokenHash);
        }
    }

    public static LoginResult migrateLegacy(HttpServletRequest request, HttpServletResponse response) {
        if (!validateLegacyRequest(request)) {
            throw new AuthenticationFailureException("legacy token migration is not available");
        }
        return issueSession(ConfigUtil.snapshot().getLogin().getUsername(), request, response);
    }

    public static LoginResult loginFromIpWhitelist(HttpServletRequest request, HttpServletResponse response) {
        Config config = ConfigUtil.snapshot();
        Login login = config.getLogin();
        if (login == null || isBlank(login.getUsername()) || isBlank(login.getPassword()) ||
                !new IpWhitelist().apply(request)) {
            throw new AuthenticationFailureException("IP whitelist login is not available");
        }
        return issueSession(login.getUsername(), request, response);
    }

    public static void logout(HttpServletRequest request, HttpServletResponse response) {
        String token = sessionToken(request);
        if (!isBlank(token)) {
            SESSIONS.remove(token);
        }
        if (response != null) {
            addCookie(response, SESSION_COOKIE, "", request, 0, true);
            addCookie(response, CSRF_COOKIE, "", request, 0, false);
        }
    }

    public static String csrf(HttpServletRequest request) {
        String token = sessionToken(request);
        Session session = SESSIONS.get(token);
        if (session == null || session.expiresAt < System.currentTimeMillis()) {
            throw new IllegalStateException("session expired");
        }
        return session.csrf;
    }

    public static String sessionBinding(HttpServletRequest request) {
        String token = sessionToken(request);
        Session session = SESSIONS.get(token);
        if (session == null || session.expiresAt < System.currentTimeMillis()) {
            throw new IllegalStateException("session expired");
        }
        return SecureUtil.sha256(token);
    }

    public static OAuthStateResult issueOAuthState(String provider, HttpServletRequest request) {
        if (!"bgm".equals(provider)) {
            throw new IllegalArgumentException("unsupported OAuth provider");
        }
        if (!validateRequest(request)) {
            throw new AuthenticationFailureException("session required");
        }
        String value = randomToken(24);
        long expiresAt = System.currentTimeMillis() + OAUTH_STATE_TTL;
        OAUTH_STATES.put(value, new OAuthState(provider, sessionBinding(request), expiresAt));
        trimOAuthStates();
        return new OAuthStateResult(value, expiresAt);
    }

    public static void consumeOAuthState(String provider, String value, HttpServletRequest request) {
        if (!validateRequest(request)) {
            throw new AuthenticationFailureException("session required");
        }
        if (isBlank(value)) {
            throw new AuthenticationFailureException("OAuth state is missing");
        }
        OAuthState state = OAUTH_STATES.get(value);
        if (state == null || state.expiresAt < System.currentTimeMillis() ||
                !constantEquals(provider, state.provider) ||
                !constantEquals(sessionBinding(request), state.binding) ||
                !OAUTH_STATES.remove(value, state)) {
            throw new AuthenticationFailureException("OAuth state is invalid or expired");
        }
    }

    private static LoginResult issueSession(String username, HttpServletRequest request,
                                            HttpServletResponse response) {
        initialize();
        Config config = ConfigUtil.snapshot();
        long ttl = sessionTtl(config);
        String token = randomToken(32);
        String csrf = randomToken(24);
        long expiresAt = System.currentTimeMillis() + ttl;
        String ip = Boolean.TRUE.equals(config.getVerifyLoginIp()) ? AuthUtil.getIp(request) : "";
        if (Boolean.TRUE.equals(config.getMultiLoginForbidden())) {
            SESSIONS.clear();
        }
        SESSIONS.put(token, new Session(username, csrf, ip, expiresAt));
        trimSessions();
        if (response != null) {
            addCookie(response, SESSION_COOKIE, token, request, ttl / 1000, true);
            addCookie(response, CSRF_COOKIE, csrf, request, ttl / 1000, false);
        }
        return new LoginResult(username, csrf, expiresAt);
    }

    private static void trimSessions() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        if (SESSIONS.size() > 10_000) {
            SESSIONS.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.expiresAt, b.expiresAt)))
                    .limit(SESSIONS.size() - 10_000)
                    .map(Map.Entry::getKey)
                    .forEach(SESSIONS::remove);
        }
    }

    private static void trimOAuthStates() {
        long now = System.currentTimeMillis();
        OAUTH_STATES.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        if (OAUTH_STATES.size() > 10_000) {
            OAUTH_STATES.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.expiresAt, b.expiresAt)))
                    .limit(OAUTH_STATES.size() - 10_000)
                    .map(Map.Entry::getKey)
                    .forEach(OAUTH_STATES::remove);
        }
    }

    private static long sessionTtl(Config config) {
        Integer configured = config == null ? null : config.getLoginEffectiveHours();
        int hours = configured == null ? 24 : Math.max(1, Math.min(configured, 8760));
        return Duration.ofHours(hours).toMillis();
    }

    private static void addCookie(HttpServletResponse response, String name, String value,
                                  HttpServletRequest request, long maxAge, boolean httpOnly) {
        boolean secure = isSecureRequest(request);
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private static boolean isSecureRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        Config config = ConfigUtil.snapshot();
        if (!Boolean.TRUE.equals(config.getReverseProxyTrustIpListEnabled()) ||
                config.getReverseProxyTrustIpList() == null ||
                !config.getReverseProxyTrustIpList().contains(request.getRemoteAddr())) {
            return false;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null &&
                "https".equalsIgnoreCase(forwardedProto.split(",", 2)[0].trim());
    }

    private static String sessionToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (SESSION_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private static boolean isUnsafe(String method) {
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method) &&
                !"OPTIONS".equalsIgnoreCase(method);
    }

    private static boolean isAuthEndpoint(String uri) {
        return "/api/v2/auth/login".equals(uri);
    }

    private static void validateCredentials(String username, String password) {
        validateUsername(username);
        validatePassword(password);
    }

    public static void validateUsername(String username) {
        if (isBlank(username) || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username format is invalid");
        }
    }

    public static String encodePassword(String password) {
        validatePassword(password);
        return PASSWORDS.encode(password);
    }

    private static void validatePassword(String password) {
        if (isBlank(password) || password.length() < 8 || password.length() > 256) {
            throw new IllegalArgumentException("password must be between 8 and 256 characters");
        }
    }

    private static boolean isLegacyHash(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{32}");
    }

    private static String legacyTokenFingerprint() {
        return SecureUtil.sha256(AuthUtil.getAuth(AuthUtil.getLogin()));
    }

    private static boolean clearLegacyMigrationState() {
        if (state.legacyMigrationUntil == 0 && isBlank(state.legacyTokenHash)) {
            return false;
        }
        state.legacyMigrationUntil = 0;
        state.legacyTokenHash = null;
        return true;
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isUnconfiguredLogin(Login login) {
        return login == null || (isBlank(login.getUsername()) && isBlank(login.getPassword()));
    }

    private static Path statePath() {
        return ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize().resolve(STATE_FILE);
    }

    public static void validateStateFile(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("auth state is not a regular file");
            }
            AuthState candidate = parseState(path);
            if (candidate.legacyMigrationUntil < 0 ||
                    (!isBlank(candidate.legacyTokenHash) &&
                            !candidate.legacyTokenHash.matches("(?i)[0-9a-f]{64}"))) {
                throw new IllegalStateException("auth state contains invalid values");
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("validate auth state failed", e);
        }
    }

    private static AuthState readState(Path path) {
        try {
            rewriteStateOnNextInitialize = false;
            if (!Files.exists(path)) {
                return new AuthState();
            }
            String content = Files.readString(path);
            AuthState loaded = parseState(content);
            rewriteStateOnNextInitialize = containsLegacySetupState(content);
            return loaded;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("load auth state failed", e);
        }
    }

    private static AuthState parseState(Path path) throws IOException {
        return parseState(Files.readString(path));
    }

    private static AuthState parseState(String content) {
        AuthState loaded = GsonStatic.GSON.fromJson(content, AuthState.class);
        if (loaded == null) {
            throw new IllegalStateException("auth state is empty");
        }
        return loaded;
    }

    private static boolean containsLegacySetupState(String content) {
        JsonObject json = GsonStatic.GSON.fromJson(content, JsonObject.class);
        return json != null && (json.has("setupCodeHash") || json.has("setupExpiresAt") || json.has("setupUsed"));
    }

    private static void writeState(Path path, AuthState value) {
        try {
            AtomicFileWriter.writeUtf8(path, GsonStatic.toJson(value));
        } catch (IOException e) {
            throw new IllegalStateException("save auth state failed", e);
        }
    }

    private static void removeLegacySetupCode() {
        Path path = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize()
                .resolve(LEGACY_SETUP_CODE_FILE);
        try {
            if (!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The obsolete setup-code file is never read; a failed cleanup must not disable login.
        }
    }

    public record LoginResult(String username, String csrfToken, long expiresAt) {
    }

    public record OAuthStateResult(String state, long expiresAt) {
    }

    private static final class Session {
        private final String username;
        private final String csrf;
        private final String ip;
        private volatile long expiresAt;

        private Session(String username, String csrf, String ip, long expiresAt) {
            this.username = Objects.requireNonNull(username);
            this.csrf = Objects.requireNonNull(csrf);
            this.ip = Objects.requireNonNull(ip);
            this.expiresAt = expiresAt;
        }
    }

    private record OAuthState(String provider, String binding, long expiresAt) {
    }

    private static final class AuthState {
        private long legacyMigrationUntil;
        private String legacyTokenHash;
    }
}
