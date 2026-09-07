package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.service.ImageCacheService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;

@RestController
@RequestMapping("/v2/images")
public class ImageController {
    private final ImageCacheService cache;

    public ImageController(ImageCacheService cache) {
        this.cache = cache;
    }

    @Auth
    @PostMapping
    public ImageCacheService.ImageRef create(@RequestBody ImageRequest request,
                                             HttpServletRequest servletRequest) {
        if (request == null) {
            throw new IllegalArgumentException("image request is required");
        }
        return cache.cache(request.url(), servletRequest);
    }

    @Auth
    @GetMapping(params = "url")
    public void publicImage(@RequestParam("url") String url,
                            HttpServletRequest request,
                            HttpServletResponse response) {
        try (ImageCacheService.PublicImageHandle handle = cache.openPublicImage(url, request)) {
            ImageCacheService.PublicImage image = handle.image();
            String ifNoneMatch = request.getHeader("If-None-Match");
            response.setHeader("ETag", image.etag());
            response.setHeader("Cache-Control", "private, max-age=3600");
            response.setHeader("Vary", "Cookie");
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (ifNoneMatch != null && matchesIfNoneMatch(ifNoneMatch, image.etag())) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }
            response.setContentType(image.contentType());
            response.setContentLengthLong(image.length());
            try (OutputStream output = response.getOutputStream()) {
                handle.inputStream().transferTo(output);
            }
        } catch (RuntimeException e) {
            // Preserve authentication and upstream status mapping. Only the
            // checked stream failure is converted to the controller's generic
            // streaming error.
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("stream public image failed", e);
        }
    }

    @Auth
    @GetMapping("/{id}")
    public void image(@PathVariable String id, HttpServletRequest request,
                      HttpServletResponse response) {
        ImageCacheService.CachedImage image = cache.resolve(id, request);
        try {
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentType(image.contentType());
            response.setContentLengthLong(Files.size(image.path()));
            try (InputStream input = Files.newInputStream(
                    image.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 OutputStream output = response.getOutputStream()) {
                input.transferTo(output);
            }
        } catch (Exception e) {
            throw new IllegalStateException("stream image failed", e);
        }
    }

    public record ImageRequest(String url) {
    }

    private static boolean matchesIfNoneMatch(String header, String etag) {
        String normalized = etag.startsWith("W/") ? etag.substring(2) : etag;
        for (String candidate : header.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value)) return true;
            if (value.startsWith("W/")) value = value.substring(2);
            if (normalized.equals(value)) return true;
        }
        return false;
    }
}
