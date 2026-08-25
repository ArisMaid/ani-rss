package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Github;
import ani.rss.entity.UpdateInfo;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.comparator.VersionComparator;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class GithubService {
    public Optional<Github.Release> getLatest(String owner, String repo) {
        Config config = ConfigUtil.snapshot();
        HttpRequest request = HttpReq.get(StrUtil.format(
                "https://api.github.com/repos/{}/{}/releases/latest", owner, repo), config)
                .timeout(3000);
        if (StrUtil.isNotBlank(config.getGithubToken())) {
            request.header(Header.AUTHORIZATION, "Bearer " + config.getGithubToken());
        }
        return request.thenFunction(response -> {
            if (response.getStatus() == 404) {
                return Optional.empty();
            }
            HttpReq.assertStatus(response);
            return Optional.ofNullable(GsonStatic.fromJson(response.body(), Github.Release.class));
        });
    }

    public UpdateInfo getUpdateInfo(String owner, String repo, String filename, String currentVersion) {
        UpdateInfo result = new UpdateInfo()
                .setUpdate(false)
                .setAutoUpdate(false)
                .setLatest("")
                .setDownloadUrl("")
                .setSha256("")
                .setMarkdownBody("")
                .setSize(0L)
                .setFormatSize("0 B");

        Optional<Github.Release> release = getLatest(owner, repo);
        if (release.isEmpty() || StrUtil.isBlank(release.get().getTagName()) ||
                StrUtil.isNotBlank(release.get().getMessage())) {
            return result;
        }

        Github.Release value = release.get();
        String latest = value.getTagName().replaceFirst("(?i)^v", "");
        String latestSeries = ReUtil.get("^(\\d+\\.\\d+)", latest, 1);
        String currentSeries = ReUtil.get("^(\\d+\\.\\d+)", currentVersion, 1);
        boolean autoUpdate = StrUtil.isNotBlank(latestSeries) && latestSeries.equals(currentSeries);
        boolean update = VersionComparator.INSTANCE.compare(latest, currentVersion) > 0;

        result.setLatest(latest)
                .setUpdate(update)
                .setAutoUpdate(autoUpdate)
                .setDate(value.getPublishedAt())
                .setMarkdownBody(StrUtil.blankToDefault(value.getBody(), ""));

        List<Github.Assets> assets = value.getAssets() == null ? Collections.emptyList() : value.getAssets();
        for (Github.Assets asset : assets) {
            if (!filename.equals(asset.getName()) || asset.getSize() == null ||
                    StrUtil.isBlank(asset.getBrowserDownloadUrl()) || StrUtil.isBlank(asset.getDigest())) {
                continue;
            }
            String digest = asset.getDigest().replaceFirst("(?i)^sha256:", "");
            if (StrUtil.isBlank(digest)) {
                continue;
            }
            result.setDownloadUrl(asset.getBrowserDownloadUrl())
                    .setSha256(digest)
                    .setSize(asset.getSize())
                    .setFormatSize(FileUtils.formatSize(asset.getSize(), true));
            break;
        }
        if (StrUtil.isBlank(result.getDownloadUrl())) {
            result.setUpdate(false);
        }
        return result;
    }
}
