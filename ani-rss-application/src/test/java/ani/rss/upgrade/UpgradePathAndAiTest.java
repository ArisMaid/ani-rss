package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.Release;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradePathAndAiTest {
    @TempDir Path temporary;
    @Test void mapsLongestSegmentPrefixAndForeignCaseRules() {
        var paths=new LibraryPathMappingService();
        var maps=List.of(new UpgradeSettings.PathMapping("/media",temporary.toString()),new UpgradeSettings.PathMapping("/media/anime",temporary.resolve("anime").toString()));
        assertEquals(temporary.resolve("anime/a.mkv"),paths.map("/media/anime/a.mkv",maps));
        assertThrows(IllegalArgumentException.class,()->paths.map("/media-other/a.mkv",maps));
        assertThrows(IllegalArgumentException.class,()->paths.map("/Media/a.mkv",maps));
        assertThrows(IllegalArgumentException.class,()->paths.map("/media/../a.mkv",maps));
        assertEquals(temporary.resolve("a.mkv"),paths.map("c:\\MEDIA\\a.mkv",List.of(new UpgradeSettings.PathMapping("C:\\Media",temporary.toString()))));
        assertEquals(temporary.resolve("a.mkv"),paths.map("\\\\NAS\\Media\\a.mkv",List.of(new UpgradeSettings.PathMapping("//nas/media",temporary.toString()))));
    }
    @Test void forbidsRootDeletionAndNonRegularFile() throws Exception {
        var paths=new LibraryPathMappingService();
        assertThrows(java.io.IOException.class,()->paths.requireSafe(temporary,temporary,true));
        Path folder=Files.createDirectory(temporary.resolve("folder")); assertThrows(java.io.IOException.class,()->paths.requireSafe(folder,temporary,true));
        Path file=Files.writeString(temporary.resolve("one.mkv"),"fixture"); assertEquals(file,paths.requireSafe(file,temporary,true));
    }
    @Test void rejectsInventedEvidenceIdsAndAudioSubtitleInference() {
        Release r=new ReleaseAnalysisService().parse("[A] Example - 01 [1080p][CHS][日语音轨]","100","fixture"); r.setId("r1");
        var output=JsonParser.parseString("""
            {"items":[{"id":"invented","fields":[{"field":"resolution","value":2160,"quote":"1080p"}]},
            {"id":"r1","fields":[{"field":"resolution","value":2160,"quote":"2160p"},
            {"field":"languages","value":["JPN"],"quote":"日语音轨"},
            {"field":"subjectId","value":"999","quote":"Example"}]}]}
            """).getAsJsonObject();
        DeepSeekAnalysisClient.applyValidated(List.of(r),output);
        assertEquals(1080,r.getResolution()); assertEquals("100",r.getSubjectId()); assertEquals(Set.of("CHS"),r.getLanguages());
    }
    @Test void oneInvalidAiItemDoesNotDiscardIndependentSupportedFields() {
        Release r=new Release(); r.setId("r1"); r.setTitle("[A] Example [1080p][简日繁]");
        var output=JsonParser.parseString("""
            {"items":[{"id":"r1","fields":[{"field":"resolution","value":1080,"quote":"1080p"},
            {"field":"groups","value":["invented"],"quote":"[A]"},
            {"field":"languages","value":["CHS","CHT","JPN"],"quote":"简日繁"}]}]}
            """).getAsJsonObject();
        DeepSeekAnalysisClient.applyValidated(List.of(r),output);
        assertEquals(1080,r.getResolution()); assertTrue(r.getGroups().isEmpty()); assertEquals(Set.of("CHS","CHT","JPN"),r.getLanguages());
    }
    @Test void actualExternalTextOverridesAContradictoryFilenameAndPreservesObservedEmbeddedChinese() throws Exception {
        Path video=temporary.resolve("episode.mkv"),subtitle=Files.writeString(temporary.resolve("episode.chs.srt"),"これは日本語の字幕です。新しい物語が始まります。");
        Release declared=new ReleaseAnalysisService().parse("Example - 01 [1080p][CHS][外挂]","100","fixture");
        new MediaVerificationService().supplementSubtitles(declared,video,List.of(subtitle)); assertEquals(Set.of("JPN"),declared.getLanguages());
        var embedded=new ani.rss.upgrade.UpgradeModels.Evidence("EMBEDDED_SUBTITLE_TRACK","languages","CHS"); embedded.setVerificationLevel("OBSERVED"); declared.getEvidence().add(embedded);
        new MediaVerificationService().supplementSubtitles(declared,video,List.of(subtitle)); assertEquals(Set.of("CHS","JPN"),declared.getLanguages());
    }
    @Test void missingDeclaredExternalSubtitleCannotSatisfyChineseButUnreadableEvidenceDoesNotDenyHardSubtitles() throws Exception {
        Release declared=new ReleaseAnalysisService().parse("Example - 01 [1080p][CHS][外挂]","100","fixture");
        new MediaVerificationService().supplementSubtitles(declared,temporary.resolve("episode.mkv"),List.of()); assertTrue(declared.getLanguages().isEmpty());
        declared.setSubtitlePackaging("HARD"); declared.getLanguages().add("CHS");
        new MediaVerificationService().supplementSubtitles(declared,temporary.resolve("episode.mkv"),List.of()); assertEquals(Set.of("CHS"),declared.getLanguages());
    }
    @Test void utf16SubtitleTextCanCorrectAFalseJapaneseFilenameDeclaration() throws Exception {
        Path subtitle=Files.write(temporary.resolve("episode.jpn.srt"),"这是我们说过的话，书和车都已经进来了。".getBytes(java.nio.charset.StandardCharsets.UTF_16));
        Release declared=new ReleaseAnalysisService().parse("Example - 01 [1080p][JPN][外挂]","100","fixture");
        new MediaVerificationService().supplementSubtitles(declared,temporary.resolve("episode.mkv"),List.of(subtitle)); assertEquals(Set.of("CHS"),declared.getLanguages());
    }
}
