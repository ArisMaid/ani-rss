package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.MavenUtils;
import ani.rss.download.DownloaderClientFactory;
import ani.rss.download.DownloaderClient;
import ani.rss.entity.Config;
import ani.rss.entity.GitInfo;
import ani.rss.entity.Login;
import ani.rss.entity.ProxyTest;
import ani.rss.start.BaseStart;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class ConfigService {

    @Resource
    private AfdianService afdianService;

    @Resource
    private ClearService clearService;

    @Resource
    private TaskService taskService;

    @Resource
    private GitProperties gitProperties;

    public Config config() {
        String version = MavenUtils.getVersion();
        Config config = ObjectUtil.clone(ConfigUtil.CONFIG);
        config.getLogin().setPassword("");
        config.setVersion(version)
                .setGitInfo(getGitInfo())
                .setVerifyExpirationTime(afdianService.verifyExpirationTime());
        return config;
    }

    public GitInfo getGitInfo() {
        return new GitInfo()
                .setBranch(gitProperties.getBranch())
                .setShortCommitId(gitProperties.getShortCommitId())
                .setCommitId(gitProperties.getCommitId());
    }

    public void setConfig(Config newConfig) {
        Config config = ConfigUtil.CONFIG;
        Config previousConfig = ConfigUtil.copy(config);
        Config candidate = ConfigUtil.copy(config);
        Login login = previousConfig.getLogin();
        String username = login.getUsername();
        String password = login.getPassword();
        Integer renameSleepSeconds = config.getRenameSleepSeconds();
        Integer sleep = config.getRssSleepMinutes();
        String download = config.getDownloadToolType();
        Boolean autoStart = config.getAutoStart();

        newConfig.setExpirationTime(null)
                .setOutTradeNo(null)
                .setTryOut(null);

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true);

        BeanUtil.copyProperties(newConfig, candidate, copyOptions);

        String loginPassword = candidate.getLogin().getPassword();
        // 密码未发生修改
        if (StrUtil.isBlank(loginPassword)) {
            candidate.getLogin().setPassword(password);
        }
        String loginUsername = candidate.getLogin().getUsername();
        if (StrUtil.isBlank(loginUsername)) {
            candidate.getLogin().setUsername(username);
        }

        Boolean proxy = candidate.getProxy();
        if (proxy) {
            String proxyHost = candidate.getProxyHost();
            Integer proxyPort = candidate.getProxyPort();
            if (StrUtil.isBlank(proxyHost) || Objects.isNull(proxyPort)) {
                throw new IllegalArgumentException("代理参数不完整");
            }
        }

        ConfigUtil.sync(candidate);
        BeanUtil.copyProperties(candidate, config);
        LogUtil.loadLogback();
        Integer newRenameSleepSeconds = candidate.getRenameSleepSeconds();
        Integer newSleep = candidate.getRssSleepMinutes();
        Boolean newAutoStart = candidate.getAutoStart();

        try {
            // 时间间隔发生改变，重启任务
            if (!Objects.equals(newSleep, sleep) ||
                    !Objects.equals(newRenameSleepSeconds, renameSleepSeconds)) {
                taskService.restart();
            }
            // 下载工具发生改变
            if (!download.equals(candidate.getDownloadToolType())) {
                TorrentUtil.loadDownloadTool();
            }
            // 开机自启发生改变
            if (!newAutoStart.equals(autoStart) && BaseStart.isSupported()) {
                BaseStart instance = BaseStart.getInstance();
                instance.sync();
            }
        } catch (Exception e) {
            BeanUtil.copyProperties(previousConfig, config);
            ConfigUtil.sync(previousConfig);
            LogUtil.loadLogback();
            throw e;
        }
    }

    public String clearCache() {
        File configDir = ConfigUtil.getConfigDir();
        String configDirStr = FileUtils.getAbsolutePath(configDir);

        Long size = clearService.clearCover();

        // 清理 mikan 预览封面
        FileUtil.del(configDirStr + "/img");

        return FileUtils.formatSize(size, true);
    }

    public ProxyTest testProxy(String url, Config config) {
        url = Base64.decodeStr(url);
        String operationId = UUID.randomUUID().toString();

        log.info("代理测试 operationId:{} url:{}", operationId, HttpReq.sanitizeUrl(url));

        HttpRequest httpRequest = HttpReq.get(url, config);

        ProxyTest proxyTest = new ProxyTest().setOperationId(operationId);

        long start = LocalDateTimeUtil.toEpochMilli(LocalDateTimeUtil.now());
        try (var response = httpRequest.execute()) {
            int status = response.getStatus();
            proxyTest.setStatus(status).setSuccess(status >= 200 && status < 300);
            if (!proxyTest.getSuccess()) {
                proxyTest.setFailureType("HTTP_" + status);
            }
        } catch (Exception e) {
            proxyTest.setSuccess(false).setFailureType(e.getClass().getSimpleName());
        }

        long end = LocalDateTimeUtil.toEpochMilli(LocalDateTimeUtil.now());
        proxyTest.setTime(end - start);
        return proxyTest;
    }

    public Boolean downloadLoginTest(Config config) {
        ConfigUtil.format(config);
        DownloaderClient client = DownloaderClientFactory.createTestClient(config);
        return client.connect(true).isSuccess();
    }


}
