package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.Release;
import java.util.List;
import com.google.gson.JsonObject;

public interface AiAnalysisClient {
    JsonObject models();
    JsonObject test();
    void analyze(List<Release> releases);
    default java.util.Optional<String> disambiguate(UpgradeModels.Target target,List<JsonObject> candidates) { return java.util.Optional.empty(); }
    default List<String> aliases(UpgradeModels.Target target) { return List.of(); }
    default java.util.Optional<String> chapterMapping(UpgradeModels.Target target,String kind,JsonObject episode) { return java.util.Optional.empty(); }
}
