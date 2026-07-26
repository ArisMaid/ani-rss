package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.util.basic.HttpReq;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CacheService {
    private static final int COVER_REQUEST_TIMEOUT_MILLIS = 5_000;

    public JsonObject getBgmCover() {
        JsonObject jsonObject = new JsonObject();
        try {
            jsonObject = HttpReq.get("https://cache.wushuo.top/bgm/cover")
                    .timeout(COVER_REQUEST_TIMEOUT_MILLIS)
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        return GsonStatic.fromJson(res.body(), JsonObject.class);
                    });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return jsonObject;
    }
}
