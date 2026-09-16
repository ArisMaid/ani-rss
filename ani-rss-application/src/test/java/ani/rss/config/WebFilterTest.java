package ani.rss.config;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            LogUtil.loadLogback();
        }
    }

    @Test
    void privateNetworkModeRejectsPublicIndexWithShortNoStoreHtml() throws Exception {
        Config original = ConfigUtil.snapshot();
        String originalConfigPath = System.getProperty("CONFIG");
        System.setProperty("CONFIG", tempDir.toString());
        ConfigUtil.sync(ConfigUtil.copy(original).setInnerIP(true));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/");
            request.setRemoteAddr("8.8.8.8");
            MockHttpServletResponse response = new MockHttpServletResponse();

            new WebFilter().doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertTrue(response.getContentType().startsWith("text/html"));
            assertEquals("禁止公网访问", response.getContentAsString());
        } finally {
            ConfigUtil.sync(original);
            restoreConfigPath(originalConfigPath);
            LogUtil.loadLogback();
        }
    }

    @Test
    void privateNetworkModeKeepsPrivateHashedAssetsLongCacheable() throws Exception {
        Config original = ConfigUtil.snapshot();
        String originalConfigPath = System.getProperty("CONFIG");
        System.setProperty("CONFIG", tempDir.toString());
        ConfigUtil.sync(ConfigUtil.copy(original).setInnerIP(true));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/assets/main-Abc123.js");
            request.setRemoteAddr("192.168.1.20");
            MockHttpServletResponse response = new MockHttpServletResponse();

            new WebFilter().doFilter(request, response, new MockFilterChain());

            assertEquals("private, max-age=31536000, immutable", response.getHeader("Cache-Control"));
        } finally {
            ConfigUtil.sync(original);
            restoreConfigPath(originalConfigPath);
            LogUtil.loadLogback();
        }
    }

    @Test
    void privateNetworkModeKeepsLegacyApiJsonContract() throws Exception {
        Config original = ConfigUtil.snapshot();
        String originalConfigPath = System.getProperty("CONFIG");
        System.setProperty("CONFIG", tempDir.toString());
        ConfigUtil.sync(ConfigUtil.copy(original).setInnerIP(true));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/api/config");
            request.setRemoteAddr("8.8.8.8");
            MockHttpServletResponse response = new MockHttpServletResponse();

            new WebFilter().doFilter(request, response, new MockFilterChain());

            assertEquals(403, response.getStatus());
            assertTrue(response.getContentType().startsWith("application/json"));
            JsonObject body = GsonStatic.fromJson(response.getContentAsString(), JsonObject.class);
            assertEquals("仅允许内网访问", body.get("message").getAsString());
        } finally {
            ConfigUtil.sync(original);
            restoreConfigPath(originalConfigPath);
            LogUtil.loadLogback();
        }
    }

    private static void restoreConfigPath(String originalConfigPath) {
        if (originalConfigPath == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", originalConfigPath);
        }
    }
}
