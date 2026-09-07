package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.AnimeGarden;
import ani.rss.entity.web.Result;
import ani.rss.service.AnimeGardenService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
public class AnimeGardenController {
    private static final long MAX_ENRICHMENT_BODY_BYTES = 16 * 1024;

    @Resource
    private AnimeGardenService animeGardenService;

    @Auth
    @Operation(summary = "AnimeGarden 番剧列表")
    @PostMapping("/animeGardenList")
    public Result<List<AnimeGarden.Week>> animeGardenList(HttpServletRequest request) {
        String bgmUrl = request.getParameter("bgmUrl");
        return Result.success(animeGardenService.list(bgmUrl));
    }

    @Auth
    @Operation(summary = "AnimeGarden 番剧字幕组列表")
    @PostMapping("/animeGardenGroup")
    public Result<List<AnimeGarden.Group>> animeGardenGroup(@RequestParam("bgmId") String bgmId) {
        return Result.success(animeGardenService.group(bgmId));
    }

    @Auth
    @Operation(summary = "补充 AnimeGarden 封面和公开评分")
    @PostMapping("/animeGardenEnrichment")
    public Result<AnimeGarden.EnrichmentResponse> animeGardenEnrichment(
            @RequestBody List<String> subjectIds,
            HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_ENRICHMENT_BODY_BYTES) {
            throw new IllegalArgumentException("AnimeGarden subject 请求体过大");
        }
        if (subjectIds == null || subjectIds.size() > 48) {
            throw new IllegalArgumentException("一次最多补载 48 个 AnimeGarden subject");
        }
        return Result.success(animeGardenService.enrich(subjectIds));
    }
}
