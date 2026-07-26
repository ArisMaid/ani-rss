package ani.rss.controller.v2;

import ani.rss.entity.Config;
import ani.rss.entity.ProxyTest;
import ani.rss.exception.ApiProblemException;
import ani.rss.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigV2ControllerTest {
    @Test
    void proxyFailureUsesProblemStatusAndPreservesOperationId() {
        ConfigService service = mock(ConfigService.class);
        Config candidate = new Config();
        when(service.testProxyUrl("http://127.0.0.1:1", candidate)).thenReturn(new ProxyTest()
                .setSuccess(false)
                .setFailureType("IORuntimeException")
                .setOperationId("proxy-operation")
                .setStatus(0)
                .setTime(12L));

        ApiProblemException failure = assertThrows(ApiProblemException.class,
                () -> new ConfigV2Controller(service).proxyTest(
                        new ConfigV2Controller.ProxyTestRequest("http://127.0.0.1:1", candidate)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.status());
        assertEquals("PROXY_TEST_FAILED", failure.code());
        assertEquals("proxy-operation", failure.operationId());
        assertEquals("IORuntimeException", failure.properties().get("failureType"));
    }

    @Test
    void downloaderRejectionIsNotReturnedAsHttpSuccess() {
        ConfigService service = mock(ConfigService.class);
        Config candidate = new Config();
        when(service.downloadLoginTest(candidate)).thenReturn(false);

        ApiProblemException failure = assertThrows(ApiProblemException.class,
                () -> new ConfigV2Controller(service).downloaderTest(candidate));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.status());
        assertEquals("DOWNLOADER_TEST_FAILED", failure.code());
    }
}
