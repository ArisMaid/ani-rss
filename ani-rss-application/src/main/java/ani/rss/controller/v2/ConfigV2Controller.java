package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.entity.Config;
import ani.rss.entity.ProxyTest;
import ani.rss.exception.ApiProblemException;
import ani.rss.service.ConfigService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v2/config")
public class ConfigV2Controller {
    private final ConfigService configService;

    public ConfigV2Controller(ConfigService configService) {
        this.configService = configService;
    }

    @Auth
    @GetMapping
    public Config config() {
        return configService.config();
    }

    @Auth
    @PutMapping
    public Config update(@RequestBody Config candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("configuration is required");
        }
        configService.setConfig(candidate);
        return configService.config();
    }

    @Auth
    @PostMapping("/proxy-test")
    public ProxyTest proxyTest(@RequestBody ProxyTestRequest request) {
        if (request == null || request.config() == null) {
            throw new IllegalArgumentException("proxy test request is required");
        }
        ProxyTest result = configService.testProxyUrl(request.url(), request.config());
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            Set<String> invalid = Set.of("INVALID_URL", "INVALID_PROXY_CONFIG", "TARGET_NOT_PROXIED");
            boolean invalidRequest = invalid.contains(result.getFailureType());
            throw new ApiProblemException(
                    invalidRequest ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                    invalidRequest ? "PROXY_TEST_INVALID" : "PROXY_TEST_FAILED",
                    "proxy test failed",
                    result.getOperationId(),
                    Map.of(
                            "failureType", result.getFailureType() == null ? "UNKNOWN" : result.getFailureType(),
                            "upstreamStatus", result.getStatus() == null ? 0 : result.getStatus(),
                            "elapsedMillis", result.getTime() == null ? 0 : result.getTime()));
        }
        return result;
    }

    @Auth
    @PostMapping("/downloader-test")
    public DownloaderTestResult downloaderTest(@RequestBody Config candidate) {
        if (!configService.downloadLoginTest(candidate)) {
            throw new ApiProblemException(HttpStatus.BAD_GATEWAY, "DOWNLOADER_TEST_FAILED",
                    "downloader connection test failed", null, Map.of());
        }
        return new DownloaderTestResult(true);
    }

    @Auth
    @PostMapping("/api-key/reveal")
    public ApiKeyView revealApiKey(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new ApiKeyView(configService.apiKey());
    }

    @Auth
    @PostMapping("/api-key/rotate")
    public ApiKeyView rotateApiKey(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new ApiKeyView(configService.rotateApiKey());
    }

    public record ProxyTestRequest(String url, Config config) {
    }

    public record DownloaderTestResult(boolean success) {
    }

    public record ApiKeyView(String apiKey) {
    }
}
