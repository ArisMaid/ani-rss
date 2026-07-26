package ani.rss.entity;

import ani.rss.commons.GsonStatic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AniDownloadPathCompatibilityTest {
    @Test
    void readsLegacyDownloadPathAndWritesCanonicalTemplateName() {
        Ani ani = GsonStatic.fromJson(
                "{\"downloadPath\":\"/legacy/${title}\"}", Ani.class);

        assertEquals("/legacy/${title}", ani.getCustomDownloadPathTemplate());
        assertEquals("/legacy/${title}", ani.getDownloadPath());

        String json = GsonStatic.toJson(ani);
        assertTrue(json.contains("\"customDownloadPathTemplate\""));
        assertFalse(json.contains("\"downloadPath\""));
    }
}
