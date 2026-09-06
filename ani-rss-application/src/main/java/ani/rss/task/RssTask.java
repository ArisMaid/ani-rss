package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.ListAni;
import ani.rss.service.DownloadService;
import ani.rss.service.TaskService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RSS
 */
@Slf4j
@Component
public class RssTask implements BaseTask {
    private static final SubscriptionDownloadQueue DOWNLOAD_QUEUE = new SubscriptionDownloadQueue();

    public static void syncDownload() {
        syncDownload(AniUtil.snapshot());
    }

    public static void syncDownload(List<Ani> aniList) {
        enqueue(aniList, Runnable::run);
    }

    public static void submitDownload(List<Ani> aniList) {
        enqueue(aniList, command -> ThreadUtil.execute(command));
    }

    public static ListAni.Refresh refreshStatus() {
        SubscriptionDownloadQueue.Status status = DOWNLOAD_QUEUE.snapshot();
        return new ListAni.Refresh(status.running(), status.failedCount(), status.finishedAt());
    }

    private static void enqueue(List<Ani> aniList, Executor executor) {
        List<String> subscriptionIds = aniList == null ? List.of() : aniList.stream()
                .filter(Objects::nonNull)
                .map(Ani::getId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        DOWNLOAD_QUEUE.submit(subscriptionIds, executor, RssTask::downloadByIds);
    }

    private static int downloadByIds(List<String> subscriptionIds) {
        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
        int failedCount = 0;
        try {
            if (!TorrentUtil.login()) {
                log.error("downloader login failed");
                return subscriptionIds.size();
            }
        } catch (Exception e) {
            log.error("downloader login failed type:{}", e.getClass().getSimpleName());
            return subscriptionIds.size();
        }
        try (TorrentUtil.SnapshotCycle ignored = TorrentUtil.openSnapshotCycle()) {
            for (int index = 0; index < subscriptionIds.size(); index++) {
                if (!TaskService.LOOP.get()) {
                    // 停止循环时，未处理的订阅必须显示为异常结束，不能让刷新页误报成功。
                    failedCount += subscriptionIds.size() - index;
                    break;
                }

                String subscriptionId = subscriptionIds.get(index);

                Ani ani = AniUtil.findRuntimeById(subscriptionId).orElse(null);
                if (ani == null) {
                    // 订阅可能已经被删除
                    continue;
                }

                String title = ani.getTitle();
                Boolean enable = ani.getEnable();
                if (!Boolean.TRUE.equals(enable)) {
                    log.debug("{} 未启用", title);
                    continue;
                }

                try {
                    if (!downloadService.downloadAni(ani)) {
                        failedCount++;
                        log.warn("{} 本轮未完成：下载器任务快照不可用或订阅已失效", title);
                    }
                } catch (Exception e) {
                    failedCount++;
                    String message = ExceptionUtils.getMessage(e);
                    log.error("{} {}", title, message);
                    log.error(message, e);
                }
                // 避免短时间频繁请求导致流控
                ThreadUtil.sleep(500);
            }
        }
        return failedCount;
    }

    @Override
    public void accept(AtomicBoolean loop) {
        Config config = ConfigUtil.CONFIG;
        Integer sleep = config.getRssSleepMinutes();

        if (!config.getRss()) {
            log.debug("rss未启用");
            ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
            return;
        }

        try {
            syncDownload(AniUtil.snapshot());
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }
        ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
    }
}
