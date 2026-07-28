package ani.rss.config;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebFilterTest {
    @TempDir
    Path tempDir;

    @Test
    void privateNetworkModeRejectsPublicV2RequestsWithProblemDetails() throws Exception {
        Config original = ConfigUtil.snapshot();
        String originalConfigPath = System.getProperty("CONFIG");
        System.setProperty("CONFIG", tempDir.toString());
        ConfigUtil.sync(ConfigUtil.copy(original).setInnerIP(true));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/api/v2/config");
            request.setRemoteAddr("8.8.8.8");
            MockHttpServletResponse response = new MockHttpServletResponse();

            new WebFilter().doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            JsonObject problem = GsonStatic.fromJson(response.getContentAsString(), JsonObject.class);
            assertEquals("PRIVATE_NETWORK_REQUIRED", problem.get("code").getAsString());
            assertEquals(403, problem.get("status").getAsInt());
        } finally {
            ConfigUtil.sync(original);
            if (originalConfigPath == null) {
                System.clearProperty("CONFIG");
            } else {
                System.setProperty("CONFIG", originalConfigPath);
            }
        }
    }
}
