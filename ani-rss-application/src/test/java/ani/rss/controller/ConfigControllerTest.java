package ani.rss.controller;

import ani.rss.service.RestoreService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigControllerTest {
    @Test
    void legacyImportEndpointOnlyStagesAndCannotConfirmRestore() throws Exception {
        RestoreService restoreService = mock(RestoreService.class);
        RestoreService.RestoreOperationView staged = new RestoreService.RestoreOperationView(
                "operation-1",
                RestoreService.RestoreStatus.VALIDATED,
                false,
                "3.1.75",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1L,
                2L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "backup.zip", "application/zip", new byte[]{1, 2, 3});
        when(restoreService.stage(any(), anyLong())).thenReturn(staged);

        ConfigController controller = new ConfigController();
        ReflectionTestUtils.setField(controller, "restoreService", restoreService);

        assertEquals(staged, controller.importConfig(file).getData());
        verify(restoreService).stage(any(), anyLong());
        verify(restoreService, never()).confirm(any());
    }
}
