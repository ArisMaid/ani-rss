package ani.rss.controller;

import ani.rss.annotation.Auth;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProxyImageController extends BaseController {
    @Auth
    @Operation(summary = "旧图片代理接口（已停用）")
    @GetMapping("/proxyImage")
    public void proxyImage(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_GONE);
    }
}
