package ani.rss.config;

import ani.rss.commons.GsonStatic;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebFilterTest {
    @Test
    void privateNetworkModeRejectsPublicV2RequestsWithProblemDetails() throws Exception {
        Boolean original = ConfigUtil.CONFIG.getInnerIP();
        ConfigUtil.CONFIG.setInnerIP(true);
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
            ConfigUtil.CONFIG.setInnerIP(original);
        }
    }
}
