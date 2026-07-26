package ani.rss.controller.v2;

import ani.rss.service.SubscriptionDeletionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionControllerTest {
    @Test
    void deleteForwardsExplicitFalseForLocalFiles() {
        SubscriptionDeletionService service = mock(SubscriptionDeletionService.class);
        SubscriptionDeletionService.DeletionResult result =
                new SubscriptionDeletionService.DeletionResult(1, 1, 0, 0);
        when(service.delete(List.of("subscription"), false)).thenReturn(result);

        SubscriptionDeletionService.DeletionResult actual = new SubscriptionController(service)
                .delete(new SubscriptionController.DeletionRequest(List.of("subscription"), false));

        assertEquals(result, actual);
        verify(service).delete(List.of("subscription"), false);
    }

    @Test
    void deleteDefaultsToLocalFileDeletionForOlderClients() {
        SubscriptionDeletionService service = mock(SubscriptionDeletionService.class);
        SubscriptionDeletionService.DeletionResult result =
                new SubscriptionDeletionService.DeletionResult(1, 1, 1, 0);
        when(service.delete(List.of("subscription"), true)).thenReturn(result);

        SubscriptionDeletionService.DeletionResult actual = new SubscriptionController(service)
                .delete(new SubscriptionController.DeletionRequest(List.of("subscription"), null));

        assertEquals(result, actual);
        verify(service).delete(List.of("subscription"), true);
    }
}
