package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.auth.AuthenticationFailureException;
import ani.rss.commons.PathPolicy;
import ani.rss.util.other.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MediaHandleService {
    private static final long TTL = Duration.ofHours(2).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();

    public String issue(Path file, Path allowedRoot, HttpServletRequest request) {
        if (!AuthService.validateRequest(request)) {
            throw new AuthenticationFailureException("session required");
        }
        try {
            Path originalRoot = allowedRoot.toAbsolutePath().normalize();
            Path originalFile = file.toAbsolutePath().normalize();
            PathPolicy.requireNoSymbolicLinks(originalRoot, originalFile);
            Path root = originalRoot.toRealPath();
            Path realFile = originalFile.toRealPath();
            PathPolicy.requireWithin(root, realFile);
            if (!Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(realFile)) {
                throw new IllegalArgumentException("media file is not a regular file");
            }
            String handle = randomHandle();
            handles.put(handle, new Handle(realFile, AuthService.sessionBinding(request),
                    "", true, System.currentTimeMillis() + TTL));
            trimExpired();
            return handle;
        } catch (IOException e) {
            throw new IllegalStateException("media path is unavailable", e);
        }
    }

    public MediaResource resolve(String handle, HttpServletRequest request) {
        Handle value = handles.get(handle);
        if (value == null || value.expiresAt < System.currentTimeMillis()) {
            handles.remove(handle);
            throw new IllegalArgumentException("media handle expired");
        }
        if (value.requiresSession) {
            if (!AuthService.validateRequest(request)) {
                throw new AuthenticationFailureException("session required");
            }
            if (!value.binding.equals(AuthService.sessionBinding(request))) {
                throw new AuthenticationFailureException("media handle belongs to another session");
            }
        } else if (!value.clientIp.equals(AuthUtil.getIp(request))) {
            throw new AuthenticationFailureException("external media handle belongs to another client");
        }
        try {
            if (!Files.isRegularFile(value.path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(value.path)) {
                throw new IllegalArgumentException("media file is unavailable");
            }
            return new MediaResource(value.path, Files.size(value.path),
                    Files.getLastModifiedTime(value.path, LinkOption.NOFOLLOW_LINKS).toMillis());
        } catch (IOException e) {
            throw new IllegalStateException("read media metadata failed", e);
        }
    }

    public String issueExternal(String handle, HttpServletRequest request) {
        MediaResource resource = resolve(handle, request);
        String external = randomHandle();
        handles.put(external, new Handle(resource.path(), "", AuthUtil.getIp(request), false,
                System.currentTimeMillis() + TTL));
        trimExpired();
        return external;
    }

    private void trimExpired() {
        long now = System.currentTimeMillis();
        handles.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        if (handles.size() > 10_000) {
            handles.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.expiresAt, b.expiresAt)))
                    .limit(handles.size() - 10_000)
                    .map(Map.Entry::getKey)
                    .forEach(handles::remove);
        }
    }

    private static String randomHandle() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record MediaResource(Path path, long length, long lastModified) {
    }

    private record Handle(Path path, String binding, String clientIp,
                          boolean requiresSession, long expiresAt) {
    }
}
