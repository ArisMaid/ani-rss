package ani.rss.controller;

import ani.rss.commons.CacheUtils;
import ani.rss.entity.Login;
import ani.rss.entity.web.Result;
import ani.rss.auth.AuthService;
import ani.rss.util.other.AuthUtil;
import cn.hutool.core.lang.Assert;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class LoginController extends BaseController {

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<AuthService.LoginResult> login(
            @RequestBody Login myLogin,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthUtil.limitLoginAttempts(false);

        String myUsername = myLogin.getUsername();
        Assert.notBlank(myUsername, "用户名不能为空");
        Assert.notBlank(myLogin.getPassword(), "密码不能为空");
        try {
            AuthService.LoginResult result = AuthService.login(
                    myUsername, myLogin.getPassword(), request, response);
            clearLimitLoginAttempts();
            log.info("登录成功");
            return Result.success(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            AuthUtil.limitLoginAttempts(true);
            log.warn("登录失败");
            return Result.error("用户名或密码错误");
        }
    }

    /**
     * 清除限制尝试次数
     */
    private void clearLimitLoginAttempts() {
        String ip = AuthUtil.getIp();
        String key = "LimitLoginAttempts#" + ip;
        if (CacheUtils.containsKey(key)) {
            CacheUtils.remove(key);
        }
    }
}
