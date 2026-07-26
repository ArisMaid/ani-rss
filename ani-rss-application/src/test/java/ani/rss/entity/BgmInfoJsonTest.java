package ani.rss.entity;

import ani.rss.commons.GsonStatic;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BgmInfoJsonTest {
    @Test
    void infoboxRemainsPartOfTheJsonContract() {
        String json = "{\"id\":\"42\",\"infobox\":[{\"key\":\"放送星期\",\"value\":\"星期一\"}]}";

        BgmInfo info = GsonStatic.fromJson(json, BgmInfo.class);

        assertEquals("放送星期", info.getInfobox().get(0).get("key").getAsString());
        JsonObject serialized = JsonParser.parseString(GsonStatic.toJson(info)).getAsJsonObject();
        assertTrue(serialized.has("infobox"));
        assertEquals("星期一", serialized.getAsJsonArray("infobox")
                .get(0).getAsJsonObject().get("value").getAsString());
    }
}
