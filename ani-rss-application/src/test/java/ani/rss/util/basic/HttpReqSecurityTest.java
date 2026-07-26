package ani.rss.util.basic;

import ani.rss.entity.Config;
import cn.hutool.http.HttpRequest;
import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class HttpReqSecurityTest {
    @Test
    void proxyCredentialsDoNotReplaceJvmAuthenticator() {
        Authenticator previous = Authenticator.getDefault();
        Authenticator marker = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return null;
            }
        };
        try {
            Authenticator.setDefault(marker);
            Config config = new Config()
                    .setProxy(true)
                    .setProxyHost("127.0.0.1")
                    .setProxyPort(8080)
                    .setProxyUsername("proxy-user")
                    .setProxyPassword("proxy-password")
                    .setProxyList("example.com");
            HttpRequest request = HttpRequestPlus.get("https://example.com/private?token=secret");

            HttpReq.setProxy(request, config);

            assertSame(marker, Authenticator.getDefault());
        } finally {
            Authenticator.setDefault(previous);
        }
    }

    @Test
    void sanitizesUserInfoQueryAndFragment() {
        assertEquals("https://example.com:8443/private/path",
                HttpReq.sanitizeUrl("https://user:secret@example.com:8443/private/path?token=value#fragment"));
    }
}
