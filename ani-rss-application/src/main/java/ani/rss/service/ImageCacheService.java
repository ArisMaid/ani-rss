package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.auth.AuthenticationFailureException;
import ani.rss.commons.PathPolicy;
import ani.rss.exception.UpstreamServiceException;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.crypto.SecureUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImageCacheService {
    private static final long TTL = Duration.ofHours(2).toMillis();
    private static final int MAX_URL_LENGTH = 8192;
    private static final int LOCK_STRIPES = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> sourceIndex = new ConcurrentHashMap<>();
    private final Object[] sourceLocks = createLocks();

    public ImageRef cache(String url, HttpServletRequest request) {
        if (!AuthService.validateRequest(request)) {
            throw new AuthenticationFailureException("session required");
        }
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("image URL is invalid or too long");
        }
        String binding = AuthService.sessionBinding(request);
        String sourceKey = binding + ":" + SecureUtil.sha256(url);
        ImageRef existing = existing(sourceKey);
        if (existing != null) {
            return existing;
        }
        Object lock = sourceLocks[Math.floorMod(sourceKey.hashCode(), sourceLocks.length)];
        try {
            synchronized (lock) {
                existing = existing(sourceKey);
                if (existing != null) {
                    return existing;
                }
                SafeImageFetcher.FetchedImage fetched;
                try {
                    fetched = SafeImageFetcher.fetch(url, ConfigUtil.snapshot());
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (IllegalStateException e) {
                    throw new UpstreamServiceException("image fetch failed", e);
                }
                String id = randomId();
                String extension = extension(fetched.contentType());
                Path root = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize().resolve("image-cache");
                Path target = root.resolve(id + extension).normalize();
                Files.createDirectories(root);
                if (Files.isSymbolicLink(root)) {
                    throw new IOException("image cache directory is a symbolic link");
                }
                Path rootParent = root.getParent();
                if (rootParent == null) {
                    throw new IOException("image cache directory must have a parent");
                }
                PathPolicy.requireNoSymbolicLinks(rootParent, root);
                PathPolicy.realPathWithin(rootParent, root);
                Path temporary = Files.createTempFile(root, ".image-", ".part");
                try {
                    Files.write(temporary, fetched.bytes());
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                long expiresAt = System.currentTimeMillis() + TTL;
                entries.put(id, new Entry(target, fetched.contentType(), binding, sourceKey, expiresAt));
                sourceIndex.put(sourceKey, id);
                trimExpired();
                return new ImageRef(id, expiresAt);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cache image failed", e);
        }
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    public CachedImage resolve(String id, HttpServletRequest request) {
        if (!AuthService.validateRequest(request)) {
            throw new AuthenticationFailureException("session required");
        }
        Entry entry = entries.get(id);
        if (entry == null || entry.expiresAt < System.currentTimeMillis()) {
            removeEntry(id);
            throw new IllegalArgumentException("image cache id expired");
        }
        if (!entry.binding.equals(AuthService.sessionBinding(request))) {
            throw new IllegalArgumentException("image cache id belongs to another session");
        }
        if (!Files.isRegularFile(entry.path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry.path)) {
            throw new IllegalArgumentException("cached image is unavailable");
        }
        return new CachedImage(entry.path, entry.contentType);
    }

    private void trimExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().stream()
                .filter(entry -> entry.getValue().expiresAt < now)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::removeEntry);
        if (entries.size() > 10_000) {
            entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.expiresAt, b.expiresAt)))
                    .limit(entries.size() - 10_000)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(this::removeEntry);
        }
    }

    private ImageRef existing(String sourceKey) {
        String id = sourceIndex.get(sourceKey);
        Entry entry = id == null ? null : entries.get(id);
        if (entry == null || entry.expiresAt < System.currentTimeMillis() ||
                !Files.isRegularFile(entry.path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry.path)) {
            if (id != null) {
                removeEntry(id);
            }
            return null;
        }
        return new ImageRef(id, entry.expiresAt);
    }

    private void removeEntry(String id) {
        Entry removed = entries.remove(id);
        if (removed == null) {
            return;
        }
        sourceIndex.remove(removed.sourceKey, id);
        try {
            Files.deleteIfExists(removed.path);
        } catch (IOException ignored) {
            // The exact cache file will be retried by a later maintenance pass.
        }
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            default -> ".img";
        };
    }

    private static String randomId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record ImageRef(String id, long expiresAt) {
    }

    public record CachedImage(Path path, String contentType) {
    }

    private record Entry(Path path, String contentType, String binding, String sourceKey, long expiresAt) {
    }
}
