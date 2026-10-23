package ani.rss.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpaceReservationServiceTest {
    @TempDir Path temporary;

    private FileStore volume(long usable) throws IOException {
        FileStore store=mock(FileStore.class); when(store.name()).thenReturn("fixture-volume");
        when(store.type()).thenReturn("fixture-fs"); when(store.getUsableSpace()).thenReturn(usable); return store;
    }
    @Test void reservationsShareCapacityAcrossDirectoriesAndReleaseRestoresCapacity() throws Exception {
        Path first=Files.createDirectory(temporary.resolve("first")),second=Files.createDirectory(temporary.resolve("second"));
        FileStore volume=volume(1000); SpaceReservationService service=new SpaceReservationService();
        try(var filesystem=mockStatic(Files.class,CALLS_REAL_METHODS)) {
            filesystem.when(()->Files.getFileStore(first)).thenReturn(volume); filesystem.when(()->Files.getFileStore(second)).thenReturn(volume);
            service.reserve("first",first,600,100);
            assertEquals("INSUFFICIENT_DISK_SPACE",assertThrows(IOException.class,()->service.reserve("second",second,400,100)).getMessage());
            service.reserve("second",second,300,100);
            assertThrows(IOException.class,()->service.reserve("first",first,700,100));
            assertThrows(IOException.class,()->service.reserve("second",second,400,100));
            service.release("first"); service.reserve("second",second,900,100);
            assertThrows(IOException.class,()->service.reserve("third",first,1,100));
        }
    }
    @Test void sharedTransferRetainsTheLargerReservationWithoutDoubleCounting() throws Exception {
        FileStore volume=volume(1000); SpaceReservationService service=new SpaceReservationService();
        try(var filesystem=mockStatic(Files.class,CALLS_REAL_METHODS)) {
            filesystem.when(()->Files.getFileStore(temporary)).thenReturn(volume);
            service.reserve("owner",temporary,600,100); service.reserve("waiter",temporary,200,100);
            service.transfer("owner","waiter");
            assertThrows(IOException.class,()->service.reserve("other",temporary,400,100)); service.reserve("other",temporary,300,100);
            service.release("waiter"); service.reserve("last",temporary,600,100);
            assertThrows(IOException.class,()->service.reserve("extra",temporary,1,100));
        }
    }
    @Test void fallingFreeSpaceAndLargeMarginCannotOverflowIntoAvailableCapacity() throws Exception {
        FileStore volume=volume(1000); SpaceReservationService service=new SpaceReservationService();
        try(var filesystem=mockStatic(Files.class,CALLS_REAL_METHODS)) {
            filesystem.when(()->Files.getFileStore(temporary)).thenReturn(volume);
            service.reserve("running",temporary,900,0); when(volume.getUsableSpace()).thenReturn(10L);
            assertThrows(IOException.class,()->service.reserve("new",temporary,0,Long.MAX_VALUE));
            assertThrows(IOException.class,()->service.reserve("new",temporary,0,0));
            service.release("running"); service.reserve("new",temporary,0,10);
        }
    }
}
