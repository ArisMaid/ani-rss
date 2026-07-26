package ani.rss.service;

import ani.rss.entity.Config;
import ani.rss.entity.ProxyTest;
import cn.hutool.core.codec.Base64;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyTestServiceTest {
    private HttpServer server;
    private volatile int status;

    @BeforeEach
    void startServer() throws IOException {
        status = 204;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void reportsHttpSuccessAndFailureHonestly() {
        ConfigService service = new ConfigService();
        Config config = new Config().setProxy(false);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/probe?secret=value";

        ProxyTest success = service.testProxy(Base64.encode(url), config);
        assertTrue(success.getSuccess());
        assertNotNull(success.getOperationId());

        status = 503;
        ProxyTest failure = service.testProxy(Base64.encode(url), config);
        assertFalse(failure.getSuccess());
        assertTrue("HTTP_503".equals(failure.getFailureType()));
    }

    @Test
    void unreachableAddressIsFailure() {
        ConfigService service = new ConfigService();
        Config config = new Config().setProxy(false);

        ProxyTest result = service.testProxy(Base64.encode("http://127.0.0.1:1/unreachable"), config);

        assertFalse(result.getSuccess());
        assertNotNull(result.getFailureType());
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
