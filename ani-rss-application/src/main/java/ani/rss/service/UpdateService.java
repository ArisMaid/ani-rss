package ani.rss.service;

import ani.rss.commons.*;
import ani.rss.entity.About;
import ani.rss.entity.Config;
import ani.rss.entity.Github;
import ani.rss.exception.UpstreamServiceException;
import ani.rss.update.BaseUpdate;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.comparator.VersionComparator;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class UpdateService {
    private static final Pattern RELEASE_VERSION =
            Pattern.compile("^[Vv]?(\\d+\\.\\d+\\.\\d+)(?:[-+].*)?$");
    private static final Pattern LOCAL_FORK_VERSION =
            Pattern.compile("^[Vv]?(\\d+\\.\\d+\\.\\d+)\\.\\d+(?:[-+].*)?$");

    static boolean isLocalForkVersion(String value) {
        return value != null && LOCAL_FORK_VERSION.matcher(value.trim()).matches();
    }

    static String upstreamBaseVersion(String value) {
        if (value == null) {
            return "";
        }
        Matcher localMatcher = LOCAL_FORK_VERSION.matcher(value.trim());
        if (localMatcher.matches()) {
            return localMatcher.group(1);
        }
        Matcher matcher = RELEASE_VERSION.matcher(value.trim());
        return matcher.matches() ? matcher.group(1) : value.trim();
    }

    static boolean isUpstreamUpdateAvailable(String latest, String current) {
        String latestBase = upstreamBaseVersion(latest);
        String currentBase = upstreamBaseVersion(current);
        if (!RELEASE_VERSION.matcher(latest == null ? "" : latest.trim()).matches() ||
                !RELEASE_VERSION.matcher(currentBase).matches()) {
            return false;
        }
        return VersionComparator.INSTANCE.compare(latestBase, currentBase) > 0;
    }

    static boolean isAutomaticUpdateAllowed(String latest, String current) {
        if (isLocalForkVersion(current)) {
            return false;
        }
        String latestSeries = ReUtil.get("^[Vv]?(\\d+\\.\\d+)", latest, 1);
        String currentSeries = ReUtil.get("^[Vv]?(\\d+\\.\\d+)", current, 1);
        return StrUtil.isNotBlank(latestSeries) && latestSeries.equals(currentSeries);
    }

    /**
     * 关于
     *
     * @return 关于信息
     */
    public synchronized About about() {
        Config config = ConfigUtil.CONFIG;
        String key = "github#releases-latest";

        About cacheAbout = CacheUtils.get(key);

        if (Objects.nonNull(cacheAbout)) {
            return cacheAbout;
        }

        String version = MavenUtils.getVersion();

        About about = new About()
                .setVersion(version)
                .setUpdate(false)
                .setAutoUpdate(false)
                .setLatest("")
                .setMarkdownBody("");
        try {
            HttpRequest request = HttpReq.get(ProjectMetadata.LATEST_RELEASE_API)
                    .timeout(3000);

            String githubToken = config.getGithubToken();
            if (StrUtil.isNotBlank(githubToken)) {
                request.header(Header.AUTHORIZATION, "Bearer " + githubToken);
            }

            request.then(response -> {
                int status = response.getStatus();
                if (status == 404) {
                    throw new IllegalStateException("release metadata was not found");
                }
                HttpReq.assertStatus(response);

                Github.Release release = GsonStatic.fromJson(response.body(), Github.Release.class);

                String message = release.getMessage();
                if (StrUtil.isNotBlank(message)) {
                    throw new IllegalStateException("release API rejected the request");
                }

                String latest = release.getTagName().replaceFirst("(?i)^v", "");

                /*
                禁止非跨小版本的更新
                取前两位版本号判断是允许自动更新
                */
                String reg = "^[Vv]?(\\d+\\.\\d+)";
                boolean autoUpdate = ReUtil.get(reg, latest, 1)
                        .equals(ReUtil.get(reg, version, 1));

                about
                        .setDate(release.getPublishedAt())
                        .setAutoUpdate(autoUpdate && isAutomaticUpdateAllowed(latest, version))
                        .setUpdate(isUpstreamUpdateAvailable(latest, version))
                        .setLatest(latest)
                        .setMarkdownBody(release.getBody());

                MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();

                String filename = currentFile.isJar() ? "ani-rss.jar" : "ani-rss.exe";

                List<Github.Assets> assets = release.getAssets();
                for (Github.Assets asset : assets) {
                    String name = asset.getName();
                    if (!filename.equals(name)) {
                        continue;
                    }

                    Long size = asset.getSize();
                    String formatSize = FileUtils.formatSize(size, true);

                    String sha256 = asset.getDigest()
                            .replace("sha256:", "");

                    about.setDownloadUrl(asset.getBrowserDownloadUrl())
                            .setSha256(sha256)
                            .setSize(size)
                            .setFormatSize(formatSize);
                }
            });
        } catch (Exception e) {
            // Settings must stay usable when GitHub or the configured proxy is offline.
            // An explicit update still fails because this fallback has update=false.
            log.warn("检测更新失败，使用本地版本信息 type:{}", e.getClass().getSimpleName());
        }
        // 缓存一分钟
        CacheUtils.put(key, about, 1000 * 60);
        return about;
    }

    /**
     * 更新程序
     *
     * @param about 关于信息
     */
    public synchronized void update(About about) {
        Boolean update = about.getUpdate();
        if (!Boolean.TRUE.equals(update)) {
            throw new IllegalStateException("no update is available");
        }
        if (isLocalForkVersion(MavenUtils.getVersion())) {
            throw new IllegalStateException("local fork build requires manual upstream synchronization");
        }
        if (StrUtil.isBlank(about.getDownloadUrl()) || StrUtil.isBlank(about.getSha256()) ||
                about.getSize() == null || about.getSize() <= 0) {
            throw new IllegalStateException("update metadata is incomplete");
        }

        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();

        Assert.isTrue(currentFile.isFile(), "不支持更新");

        BaseUpdate baseUpdate = BaseUpdate.getInstance();

        File updateFile;
        try {
            updateFile = baseUpdate.downloadUpdateFile(about);
        } catch (RuntimeException e) {
            throw new UpstreamServiceException("update download failed", e);
        }

        ThreadUtil.execute(() -> {
            try {
                baseUpdate.update(updateFile);
            } catch (Exception e) {
                log.error("更新应用失败 type:{}", e.getClass().getSimpleName());
            }
        });
    }
}
