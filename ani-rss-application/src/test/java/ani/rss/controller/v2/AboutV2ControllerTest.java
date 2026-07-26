package ani.rss.controller.v2;

import ani.rss.entity.About;
import ani.rss.service.UpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AboutV2ControllerTest {
    @Test
    void acceptedUpdateReturnsTypedState() {
        UpdateService service = mock(UpdateService.class);
        About about = new About().setLatest("3.1.76").setUpdate(true);
        when(service.about()).thenReturn(about);
        AboutV2Controller controller = new AboutV2Controller(service);

        var response = controller.update();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("3.1.76", response.getBody().version());
        assertEquals("STARTED", response.getBody().status());
        verify(service).update(about);
    }

    @Test
    void updateFailureIsNotReportedAsSuccess() {
        UpdateService service = mock(UpdateService.class);
        About about = new About().setLatest("3.1.76").setUpdate(true);
        when(service.about()).thenReturn(about);
        doThrow(new IllegalStateException("download failed")).when(service).update(about);

        assertThrows(IllegalStateException.class, () -> new AboutV2Controller(service).update());
    }
}
