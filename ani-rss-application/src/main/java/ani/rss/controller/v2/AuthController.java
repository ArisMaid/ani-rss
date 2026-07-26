package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.auth.AuthService;
import ani.rss.util.other.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

@RestController
@RequestMapping("/v2/auth")
public class AuthController {
    @PostMapping("/login")
    public AuthService.LoginResult login(@RequestBody Credentials credentials,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials are required");
        }
        return guarded(() -> AuthService.login(
                credentials.username(), credentials.password(), request, response));
    }

    @PostMapping("/logout")
    @Auth
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        AuthService.logout(request, response);
    }

    @Auth
    @GetMapping("/csrf")
    public CsrfResponse csrf(HttpServletRequest request) {
        return new CsrfResponse(AuthService.csrf(request));
    }

    @PostMapping("/migrate")
    public AuthService.LoginResult migrate(HttpServletRequest request, HttpServletResponse response) {
        return guarded(() -> AuthService.migrateLegacy(request, response));
    }

    @PostMapping("/ip-login")
    public AuthService.LoginResult ipLogin(HttpServletRequest request, HttpServletResponse response) {
        return AuthService.loginFromIpWhitelist(request, response);
    }

    @Auth
    @PostMapping("/oauth-state/{provider}")
    public AuthService.OAuthStateResult oauthState(@PathVariable String provider,
                                                   HttpServletRequest request) {
        return AuthService.issueOAuthState(provider, request);
    }

    private static <T> T guarded(Supplier<T> action) {
        AuthUtil.limitLoginAttempts(false);
        try {
            T result = action.get();
            AuthUtil.clearLoginAttempts();
            return result;
        } catch (RuntimeException e) {
            AuthUtil.limitLoginAttempts(true);
            throw e;
        }
    }

    public record Credentials(String username, String password) {
    }

    public record CsrfResponse(String csrfToken) {
    }
}
