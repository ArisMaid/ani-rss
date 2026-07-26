package ani.rss.config;

import ani.rss.auth.ClientAddressPolicy;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Global;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.util.other.AuthUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ani.rss.controller.BaseController.setCacheControl;

@Component
public class WebFilter implements Filter {
    /**
     * 指定缓存的文件
     */
    private static final List<String> CACHE_EXT = List.of("css", "js", "jpg", "png", "svg", "ico");

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String uri = request.getRequestURI();
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (uri.startsWith("/api/v2/auth") || uri.startsWith("/api/v2/config") ||
                uri.equals("/api/login") || uri.equals("/api/config") || uri.equals("/api/setConfig")) {
            response.setHeader("Cache-Control", "no-store");
        }

        Config config = ConfigUtil.CONFIG;
        if (uri.startsWith("/api") && Boolean.TRUE.equals(config.getInnerIP()) &&
                !ClientAddressPolicy.isPrivate(AuthUtil.getIp(request))) {
            writePrivateNetworkRequired(request, response);
            return;
        }

        // 非 api
        if (!uri.startsWith("/api")) {
            String extName = FileUtil.extName(uri);

            if (StrUtil.isBlank(extName) && !uri.endsWith("/")) {
                String htmlPath = uri + ".html";
                request.getRequestDispatcher(htmlPath).forward(request, response);
                return;
            }

            if (StrUtil.isNotBlank(extName) && CACHE_EXT.contains(extName)) {
                setCacheControl(response, 86400);
            } else {
                setCacheControl(response, 0);
            }
        }

        Global.REQUEST.set(request);
        Global.RESPONSE.set(response);
        try {
            cors(response);
            filterChain.doFilter(req, res);
        } finally {
            Global.REQUEST.remove();
            Global.RESPONSE.remove();
        }
    }

    private void cors(HttpServletResponse response) {
        Config config = ConfigUtil.CONFIG;
        Boolean allowCors = config.getAllowCors();
        if (!allowCors) {
            return;
        }

        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "*");
        response.addHeader("Access-Control-Allow-Headers", "*");
        response.addHeader("Access-Control-Max-Age", "0");
    }

    private static void writePrivateNetworkRequired(HttpServletRequest request,
                                                    HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        Object body;
        if (isV2(request.getRequestURI())) {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("type", "about:blank");
            problem.put("title", "Forbidden");
            problem.put("status", HttpServletResponse.SC_FORBIDDEN);
            problem.put("detail", "private network access is required");
            problem.put("code", "PRIVATE_NETWORK_REQUIRED");
            problem.put("operationId", UUID.randomUUID().toString());
            body = problem;
            response.setContentType("application/problem+json");
        } else {
            body = new Result<Void>()
                    .setCode(ResultCode.HTTP_FORBIDDEN)
                    .setMessage("仅允许内网访问");
            response.setContentType("application/json");
        }
        byte[] bytes = GsonStatic.toJson(body).getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private static boolean isV2(String uri) {
        return "/api/v2".equals(uri) || uri != null && uri.startsWith("/api/v2/");
    }
}
