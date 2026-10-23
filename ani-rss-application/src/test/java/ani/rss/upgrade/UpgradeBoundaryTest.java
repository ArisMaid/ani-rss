package ani.rss.upgrade;

import ani.rss.controller.v2.UpgradeController;
import ani.rss.upgrade.UpgradeModels.Release;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradeBoundaryTest {
    @Test void serializedSettingsKeepMappingsAndBudgets() throws Exception {
        var settings=new UpgradeSettings(); settings.setLibraryIds(List.of("library")); settings.setEmbyMappings(List.of(new UpgradeSettings.PathMapping("/remote","/local")));
        var bytes=new ByteArrayOutputStream(); try(var out=new ObjectOutputStream(bytes)) {out.writeObject(settings);}
        try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var restored=(UpgradeSettings)in.readObject(); assertEquals(settings,restored);
        }
    }
    @Test void previewAndVerificationSnapshotsDoNotLeakMutableEvidence() {
        var release=new Release(); release.setTitle("Original"); release.getGroups().add("Group");
        var preview=new UpgradeController.Preview(release,null);
        var verified=new MediaVerificationService.Verified(release,1,2,"key","hash");
        release.getGroups().clear(); preview.candidate().getGroups().clear(); verified.attributes().getGroups().clear();
        assertEquals(Set.of("Group"),preview.candidate().getGroups()); assertEquals(Set.of("Group"),verified.attributes().getGroups());
        assertNull(preview.current());
    }
    @Test void listResponsesAndBindingsAreImmutableSnapshots() {
        var input=new ArrayList<>(List.of("one")); var page=new UpgradeController.Page<>(input,0,50,false); var binding=new MikanQuarterCatalog.Binding("1",input);
        input.clear(); assertEquals(List.of("one"),page.items()); assertEquals(List.of("one"),binding.feeds());
        assertThrows(UnsupportedOperationException.class,()->page.items().clear());
    }
    @Test void rootsAndParentlessPathsAreRejectedBeforeFilesystemWork() {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath().getRoot();
        assertThrows(IllegalArgumentException.class,()->UpgradePaths.fileName(root));
        assertThrows(IllegalArgumentException.class,()->UpgradePaths.parent(Path.of("filename.mkv")));
    }
}
