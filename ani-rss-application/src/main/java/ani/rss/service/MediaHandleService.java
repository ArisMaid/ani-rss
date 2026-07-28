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
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Service
public final class MediaHandleService {
    private static final long DEFAULT_TTL = Duration.ofHours(2).toMillis();
    private static final int DEFAULT_MAX_HANDLES = 10_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final PriorityQueue<QueuedHandle> expiryQueue = new PriorityQueue<>(
            Comparator.comparingLong((QueuedHandle queued) -> queued.value().expiresAt())
                    .thenComparingLong(QueuedHandle::sequence));
    private final long ttl;
    private final int maxHandles;
    private final LongSupplier clock;
    private long nextSequence;

    public MediaHandleService() {
        this(DEFAULT_TTL, DEFAULT_MAX_HANDLES, System::currentTimeMillis);
    }

    MediaHandleService(long ttl, int maxHandles, LongSupplier clock) {
        if (ttl <= 0 || maxHandles <= 0) {
            throw new IllegalArgumentException("media handle limits must be positive");
        }
        this.ttl = ttl;
        this.maxHandles = maxHandles;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

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
            return register(new Handle(realFile, AuthService.sessionBinding(request),
                    "", true, clock.getAsLong() + ttl));
        } catch (IOException e) {
            throw new IllegalStateException("media path is unavailable", e);
        }
    }

    public MediaResource resolve(String handle, HttpServletRequest request) {
        Handle value = handles.get(handle);
        if (value == null) {
            throw new IllegalArgumentException("media handle expired");
        }
        if (value.expiresAt < clock.getAsLong()) {
            handles.remove(handle, value);
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
        return register(new Handle(resource.path(), "", AuthUtil.getIp(request), false,
                clock.getAsLong() + ttl));
    }

    private String register(Handle value) {
        String handle;
        do {
            handle = randomHandle();
        } while (handles.putIfAbsent(handle, value) != null);

        synchronized (expiryQueue) {
            expiryQueue.add(new QueuedHandle(handle, value, nextSequence++));
            trimExpired(clock.getAsLong());
        }
        return handle;
    }

    /**
     * Removes each queued handle at most once. This keeps issuance O(log H)
     * instead of scanning and sorting the entire handle map for every file.
     */
    private void trimExpired(long now) {
        while (!expiryQueue.isEmpty() && expiryQueue.peek().value().expiresAt() < now) {
            QueuedHandle expired = expiryQueue.poll();
            handles.remove(expired.key(), expired.value());
        }
        while (handles.size() > maxHandles && !expiryQueue.isEmpty()) {
            QueuedHandle oldest = expiryQueue.poll();
            handles.remove(oldest.key(), oldest.value());
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

    private record QueuedHandle(String key, Handle value, long sequence) {
    }
}
