package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.download.DownloaderResult;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.entity.web.Result;
import ani.rss.util.other.TorrentUtil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TorrentsInfosController extends BaseController {

    @Auth
    @Operation(summary = "下载列表")
    @PostMapping("/torrentsInfos")
    public Result<List<TorrentsInfo>> torrentsInfos() {
        DownloaderResult<List<TorrentsInfo>> result = TorrentUtil.getTorrentsInfosResult();
        if (!result.isSuccess()) {
            return Result.error("下载器任务列表不可用 code:{}", result.errorCode());
        }
        return Result.success(result.value() == null ? List.of() : result.value());
    }

}
