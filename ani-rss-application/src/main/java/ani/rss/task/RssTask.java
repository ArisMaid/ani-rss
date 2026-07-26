package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RSS
 */
@Slf4j
@Component
public class RssTask implements BaseTask {
    public static final List<Ani> ANI_LIST = AniUtil.ANI_LIST;
    public static final AtomicBoolean DOWNLOAD_LOCK = new AtomicBoolean(false);

    public static void syncDownload() {
        syncDownload(ANI_LIST);
    }

    public static void syncDownload(List<Ani> aniList) {
        acquireDownloadLock();
        syncDownloadWithLock(aniList);
    }

    public static void submitDownload(List<Ani> aniList) {
        acquireDownloadLock();
        ThreadUtil.execute(() -> syncDownloadWithLock(aniList));
    }

    static void acquireDownloadLock() {
        if (!DOWNLOAD_LOCK.compareAndSet(false, true)) {
            throw new IllegalStateException("存在未完成任务，请等待...");
        }
    }

    static void releaseDownloadLock() {
        DOWNLOAD_LOCK.set(false);
    }

    private static void syncDownloadWithLock(List<Ani> aniList) {
        try {
            download(aniList);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        } finally {
            releaseDownloadLock();
        }
    }

    public static void download(List<Ani> aniList) {
        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
        if (!TorrentUtil.login()) {
            return;
        }
        for (Ani ani : aniList) {
            if (!TaskService.LOOP.get()) {
                // 停止循环
                return;
            }

            if (!ANI_LIST.contains(ani)) {
                // 订阅可能已经被删除
                continue;
            }

            String title = ani.getTitle();
            Boolean enable = ani.getEnable();
            if (!enable) {
                log.debug("{} 未启用", title);
                continue;
            }

            try {
                downloadService.downloadAni(ani);
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.error("{} {}", title, message);
                log.error(message, e);
            }
            // 避免短时间频繁请求导致流控
            ThreadUtil.sleep(500);
        }
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
            syncDownload(ANI_LIST);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }
        ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
    }
}
