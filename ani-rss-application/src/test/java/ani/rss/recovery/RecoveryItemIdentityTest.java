package ani.rss.recovery;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryItemIdentityTest {
    private final Ani ani = new Ani().setTitle("Example").setSeason(1);

    @Test
    void ignoresHashSizeExtensionCodecBitrateAndPublicationTime() {
        Item first = item("first",
                "[Group] Example - 01 [WEB-DL][1080P][A1B2C3D4][1.2 GiB][x265][5000kbps] 2026-08-08 23:49:23.mkv",
                "old-template S01E01.mkv");
        Item second = item("second",
                "[Group] Example - 01 [WEB-DL][1080P][FFFFFFFF][900 MiB][H.264][8000kbps] 2026-08-09 01:02:03.mp4",
                "new-template S01E01.mp4");

        RecoveryItemIdentity.Value firstIdentity = RecoveryItemIdentity.from(ani, first);
        RecoveryItemIdentity.Value secondIdentity = RecoveryItemIdentity.from(ani, second);

        assertTrue(firstIdentity.sameRelease(secondIdentity));
        assertFalse(firstIdentity.sameOutput(secondIdentity));
    }

    @Test
    void keepsMeaningfulReleaseVariantsDistinct() {
        RecoveryItemIdentity.Value video = RecoveryItemIdentity.from(ani,
                item("video", "[Group] Example - 01 [VideoVer][1080P]", "Example S01E01"));
        RecoveryItemIdentity.Value live = RecoveryItemIdentity.from(ani,
                item("live", "[Group] Example - 01 [LiveVer][1080P]", "Example S01E01"));
        RecoveryItemIdentity.Value higherResolution = RecoveryItemIdentity.from(ani,
                item("4k", "[Group] Example - 01 [VideoVer][2160P]", "Example S01E01"));
        RecoveryItemIdentity.Value otherGroup = RecoveryItemIdentity.from(ani,
                item("other", "[Other] Example - 01 [VideoVer][1080P]", "Example S01E01")
                        .setSubgroup("Other"));

        assertFalse(video.sameRelease(live));
        assertFalse(video.sameRelease(higherResolution));
        assertFalse(video.sameRelease(otherGroup));
    }

    @Test
    void comparesFinalOutputNamesSeparatelyFromReleaseIdentity() {
        RecoveryItemIdentity.Value first = RecoveryItemIdentity.from(ani,
                item("first", "[Group] Example - 01 [VideoVer][1080P]", "Example S01E01.mkv"));
        RecoveryItemIdentity.Value second = RecoveryItemIdentity.from(ani,
                item("second", "[Group] Example - 01 [LiveVer][1080P]", "Example S01E01.mp4"));

        assertFalse(first.sameRelease(second));
        assertTrue(first.sameOutput(second));
    }

    @Test
    void refusesToMergeItemsWithoutAnyNamingEvidence() {
        Item first = new Item().setInfoHash("first").setEpisode(1.0).setSubgroup("Group");
        Item second = new Item().setInfoHash("second").setEpisode(1.0).setSubgroup("Group");

        assertFalse(RecoveryItemIdentity.from(ani, first)
                .sameRelease(RecoveryItemIdentity.from(ani, second)));
    }

    private static Item item(String hash, String title, String output) {
        return new Item()
                .setInfoHash(hash)
                .setEpisode(1.0)
                .setSubgroup("Group")
                .setTitle(title)
                .setReName(output);
    }
}
