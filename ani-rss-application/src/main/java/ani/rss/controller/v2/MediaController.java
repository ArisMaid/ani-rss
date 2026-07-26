package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.service.MediaHandleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

@RestController
@RequestMapping("/v2/media")
public class MediaController {
    private final MediaHandleService handles;

    public MediaController(MediaHandleService handles) {
        this.handles = handles;
    }

    @RequestMapping(value = "/{handle}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void media(@PathVariable String handle,
                      HttpServletRequest request,
                      HttpServletResponse response) {
        MediaHandleService.MediaResource resource = handles.resolve(handle, request);
        String etag = "\"" + resource.length() + "-" + resource.lastModified() + "\"";
        response.setHeader(HttpHeaders.ETAG, etag);
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, resource.lastModified());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (etag.equals(request.getHeader(HttpHeaders.IF_NONE_MATCH))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        String contentType = MediaTypeFactory.getMediaType(resource.path().getFileName().toString())
                .map(MediaType::toString)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentType(contentType);
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        long start = 0;
        long end = resource.length() - 1;
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            List<HttpRange> ranges;
            try {
                ranges = HttpRange.parseRanges(rangeHeader);
                if (ranges.size() != 1 || resource.length() == 0) {
                    throw new IllegalArgumentException("only one range is supported");
                }
                HttpRange range = ranges.get(0);
                start = range.getRangeStart(resource.length());
                end = range.getRangeEnd(resource.length());
                if (start < 0 || end < start || start >= resource.length()) {
                    throw new IllegalArgumentException("range is unsatisfied");
                }
                end = Math.min(end, resource.length() - 1);
            } catch (RuntimeException e) {
                response.resetBuffer();
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + resource.length());
                response.setContentLengthLong(0);
                return;
            }
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + end + "/" + resource.length());
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        long length = resource.length() == 0 ? 0 : end - start + 1;
        response.setContentLengthLong(length);
        if ("HEAD".equalsIgnoreCase(request.getMethod()) || length == 0) {
            return;
        }
        try (InputStream input = Files.newInputStream(
                resource.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = response.getOutputStream()) {
            input.skipNBytes(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (Exception e) {
            throw new IllegalStateException("stream media failed", e);
        }
    }

    @Auth
    @PostMapping("/{handle}/external")
    public ExternalMediaHandle external(@PathVariable String handle, HttpServletRequest request) {
        return new ExternalMediaHandle(handles.issueExternal(handle, request));
    }

    public record ExternalMediaHandle(String handle) {
    }
}
