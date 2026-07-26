package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.MavenUtils;
import ani.rss.download.DownloaderClientFactory;
import ani.rss.download.DownloaderClient;
import ani.rss.entity.Config;
import ani.rss.entity.GitInfo;
import ani.rss.entity.Login;
import ani.rss.entity.NotificationConfig;
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
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class ConfigService {
    private static final SecureRandom RANDOM = new SecureRandom();

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
        Config config = ConfigUtil.snapshot();
        config.getLogin().setPassword("");
        clearSecrets(config);
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
        Config config = ConfigUtil.snapshot();
        Config previousConfig = ConfigUtil.copy(config);
        Config candidate = ConfigUtil.copy(config);
        Login login = previousConfig.getLogin();
        String username = login.getUsername();
        String password = login.getPassword();
        Integer renameSleepSeconds = config.getRenameSleepSeconds();
        Integer sleep = config.getRssSleepMinutes();
        Boolean autoStart = config.getAutoStart();

        newConfig.setExpirationTime(null)
                .setOutTradeNo(null)
                .setTryOut(null)
                .setVersion(null)
                .setGitInfo(null)
                .setVerifyExpirationTime(null);

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true);

        BeanUtil.copyProperties(newConfig, candidate, copyOptions);
        candidate.setVersion(null)
                .setGitInfo(null)
                .setVerifyExpirationTime(null);
        preserveSecrets(previousConfig, candidate);

        String loginPassword = candidate.getLogin().getPassword();
        String rawPassword = null;
        if (StrUtil.isBlank(loginPassword)) {
            candidate.getLogin().setPassword(password);
        } else if (!Objects.equals(loginPassword, password)) {
            if (AuthService.isCompatibleMd5Password(loginPassword)) {
                candidate.getLogin().setPassword(loginPassword.toLowerCase(java.util.Locale.ROOT));
            } else {
                AuthService.validatePassword(loginPassword);
                rawPassword = loginPassword;
                candidate.getLogin().setPassword(SecureUtil.md5(loginPassword));
            }
        }
        String loginUsername = candidate.getLogin().getUsername();
        if (StrUtil.isBlank(loginUsername)) {
            candidate.getLogin().setUsername(username);
        } else if (!Objects.equals(loginUsername, username)) {
            AuthService.validateUsername(loginUsername);
        }
        boolean credentialsChanged = !Objects.equals(username, candidate.getLogin().getUsername()) ||
                !Objects.equals(password, candidate.getLogin().getPassword());

        Boolean proxy = candidate.getProxy();
        if (proxy) {
            String proxyHost = candidate.getProxyHost();
            Integer proxyPort = candidate.getProxyPort();
            if (StrUtil.isBlank(proxyHost) || Objects.isNull(proxyPort)) {
                throw new IllegalArgumentException("代理参数不完整");
            }
        }

        ConfigUtil.sync(candidate);
        Config committed = ConfigUtil.snapshot();
        Integer newRenameSleepSeconds = committed.getRenameSleepSeconds();
        Integer newSleep = committed.getRssSleepMinutes();
        Boolean newAutoStart = committed.getAutoStart();
        boolean taskSettingsChanged = !Objects.equals(newSleep, sleep) ||
                !Objects.equals(newRenameSleepSeconds, renameSleepSeconds);
        boolean downloaderChanged = !Objects.equals(
                GsonStatic.toJson(previousConfig), GsonStatic.toJson(committed));
        boolean autoStartChanged = !Objects.equals(newAutoStart, autoStart) && BaseStart.isSupported();
        try {
            if (credentialsChanged) {
                AuthService.invalidateSessions();
                if (rawPassword != null) {
                    AuthService.recordPasswordVerifier(rawPassword);
                } else {
                    AuthService.clearPasswordVerifier();
                }
                AuthService.invalidateLegacyMigration();
            }
            LogUtil.loadLogback();
            if (downloaderChanged) {
                TorrentUtil.loadDownloadTool();
            }
            // 时间间隔发生改变，重启任务
            if (taskSettingsChanged) {
                taskService.restart();
            }
            // 开机自启发生改变
            if (autoStartChanged) {
                BaseStart instance = BaseStart.getInstance();
                instance.sync();
            }
        } catch (Exception failure) {
            try {
                ConfigUtil.sync(previousConfig);
                LogUtil.loadLogback();
                if (downloaderChanged) {
                    TorrentUtil.loadDownloadTool();
                }
                if (taskSettingsChanged) {
                    taskService.restart();
                }
                if (autoStartChanged) {
                    BaseStart.getInstance().sync();
                }
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                throw new IllegalStateException("configuration rollback failed", failure);
            }
            throw failure;
        }
    }

    public String clearCache() {
        long size = clearService.clearCover() + clearService.clearPreviewImages();
        return FileUtils.formatSize(size, true);
    }

    public ProxyTest testProxy(String url, Config config) {
        try {
            return testProxyUrl(Base64.decodeStr(url), config);
        } catch (RuntimeException e) {
            return new ProxyTest()
                    .setOperationId(UUID.randomUUID().toString())
                    .setStatus(0)
                    .setTime(0L)
                    .setSuccess(false)
                    .setFailureType("INVALID_ENCODING");
        }
    }

    public ProxyTest testProxyUrl(String url, Config config) {
        config = hydrateTestSecrets(config);
        String operationId = UUID.randomUUID().toString();
        log.info("代理测试 operationId:{} origin:{}", operationId, HttpReq.sanitizeOrigin(url));
        ProxyTest proxyTest = new ProxyTest().setOperationId(operationId).setStatus(0);
        long start = LocalDateTimeUtil.toEpochMilli(LocalDateTimeUtil.now());
        try {
            if (StrUtil.isBlank(url)) {
                throw new IllegalArgumentException("invalid proxy-test URL");
            }
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ||
                    StrUtil.isBlank(uri.getHost()) || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("invalid proxy-test URL");
            }
            if (Boolean.TRUE.equals(config.getProxy())) {
                if (StrUtil.isBlank(config.getProxyHost()) || config.getProxyPort() == null ||
                        config.getProxyPort() < 1 || config.getProxyPort() > 65535) {
                    proxyTest.setSuccess(false).setFailureType("INVALID_PROXY_CONFIG");
                    return finishProxyTest(proxyTest, start);
                }
                if (!HttpReq.isProxy(uri.toString(), config)) {
                    proxyTest.setSuccess(false).setFailureType("TARGET_NOT_PROXIED");
                    return finishProxyTest(proxyTest, start);
                }
            }
            HttpRequest httpRequest = HttpReq.get(uri.toString(), config);
            try (var response = httpRequest.execute()) {
            int status = response.getStatus();
            proxyTest.setStatus(status).setSuccess(status >= 200 && status < 300);
            if (!proxyTest.getSuccess()) {
                proxyTest.setFailureType("HTTP_" + status);
            }
            }
        } catch (IllegalArgumentException e) {
            proxyTest.setSuccess(false).setFailureType("INVALID_URL");
        } catch (Exception e) {
            proxyTest.setSuccess(false).setFailureType(e.getClass().getSimpleName());
        }

        return finishProxyTest(proxyTest, start);
    }

    private static ProxyTest finishProxyTest(ProxyTest result, long start) {
        long end = LocalDateTimeUtil.toEpochMilli(LocalDateTimeUtil.now());
        return result.setTime(end - start);
    }

    public Boolean downloadLoginTest(Config config) {
        config = hydrateTestSecrets(config);
        ConfigUtil.format(config);
        DownloaderClient client = DownloaderClientFactory.createTestClient(config);
        return client.connect(true).isSuccess();
    }

    public NotificationConfig notificationForOperation(NotificationConfig candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("notification configuration is required");
        }
        NotificationConfig result = GsonStatic.fromJson(GsonStatic.toJson(candidate), NotificationConfig.class);
        Config active = ConfigUtil.snapshot();
        if (active.getNotificationConfigList() == null) {
            return result;
        }
        findMatchingNotification(active.getNotificationConfigList(), result)
                .ifPresent(previous -> preserveNotificationSecrets(previous, result));
        return result;
    }

    public String apiKey() {
        return StrUtil.blankToDefault(ConfigUtil.snapshot().getApiKey(), "");
    }

    public String rotateApiKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String apiKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Config candidate = ConfigUtil.snapshot().setApiKey(apiKey);
        setConfig(candidate);
        return apiKey;
    }

    private static Config hydrateTestSecrets(Config candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("test configuration is required");
        }
        Config result = ConfigUtil.copy(candidate);
        Config active = ConfigUtil.snapshot();
        if (sameDownloader(result, active) && StrUtil.isBlank(result.getDownloadToolPassword())) {
            result.setDownloadToolPassword(active.getDownloadToolPassword());
        }
        if (sameProxy(result, active) && StrUtil.isBlank(result.getProxyPassword())) {
            result.setProxyPassword(active.getProxyPassword());
        }
        return result;
    }

    private static boolean sameDownloader(Config left, Config right) {
        return Objects.equals(left.getDownloadToolType(), right.getDownloadToolType()) &&
                Objects.equals(left.getDownloadToolHost(), right.getDownloadToolHost()) &&
                Objects.equals(left.getDownloadToolUsername(), right.getDownloadToolUsername());
    }

    private static boolean sameProxy(Config left, Config right) {
        return Objects.equals(left.getProxyHost(), right.getProxyHost()) &&
                Objects.equals(left.getProxyPort(), right.getProxyPort()) &&
                Objects.equals(left.getProxyUsername(), right.getProxyUsername());
    }

    private static void clearSecrets(Config config) {
        config.setTmdbApiKey("")
                .setDownloadToolPassword("")
                .setProxyPassword("")
                .setBgmToken("")
                .setBgmAppSecret("")
                .setBgmRefreshToken("")
                .setApiKey("")
                .setGithubToken("");
        if (config.getNotificationConfigList() != null) {
            for (NotificationConfig notification : config.getNotificationConfigList()) {
                notification.setMailPassword("")
                        .setServerChanSendKey("")
                        .setServerChan3ApiUrl("")
                        .setTelegramBotToken("")
                        .setWebHookUrl("")
                        .setWebHookHeader("")
                        .setWebHookBody("")
                        .setEmbyApiKey("")
                        .setOpenListUploadApiKey("")
                        .setBarkDeviceKeys(List.of());
            }
        }
    }

    private static void preserveSecrets(Config previous, Config candidate) {
        if (StrUtil.isBlank(candidate.getTmdbApiKey())) candidate.setTmdbApiKey(previous.getTmdbApiKey());
        if (StrUtil.isBlank(candidate.getDownloadToolPassword())) candidate.setDownloadToolPassword(previous.getDownloadToolPassword());
        if (StrUtil.isBlank(candidate.getProxyPassword())) candidate.setProxyPassword(previous.getProxyPassword());
        if (StrUtil.isBlank(candidate.getBgmToken())) candidate.setBgmToken(previous.getBgmToken());
        if (StrUtil.isBlank(candidate.getBgmAppSecret())) candidate.setBgmAppSecret(previous.getBgmAppSecret());
        if (StrUtil.isBlank(candidate.getBgmRefreshToken())) candidate.setBgmRefreshToken(previous.getBgmRefreshToken());
        if (StrUtil.isBlank(candidate.getApiKey())) candidate.setApiKey(previous.getApiKey());
        if (StrUtil.isBlank(candidate.getGithubToken())) candidate.setGithubToken(previous.getGithubToken());

        List<NotificationConfig> oldNotifications = previous.getNotificationConfigList();
        List<NotificationConfig> newNotifications = candidate.getNotificationConfigList();
        if (oldNotifications == null || newNotifications == null) {
            return;
        }
        List<NotificationConfig> unmatched = new ArrayList<>(oldNotifications);
        for (NotificationConfig newValue : newNotifications) {
            if (newValue == null) {
                continue;
            }
            findMatchingNotification(unmatched, newValue).ifPresent(oldValue -> {
                preserveNotificationSecrets(oldValue, newValue);
                unmatched.remove(oldValue);
            });
        }
    }

    /**
     * The upstream document has no notification identifier. Match only when
     * the public identity is unique, so masked secrets are never copied to an
     * ambiguous new notification.
     */
    private static java.util.Optional<NotificationConfig> findMatchingNotification(
            List<NotificationConfig> candidates, NotificationConfig target) {
        List<NotificationConfig> matches = candidates.stream()
                .filter(Objects::nonNull)
                .filter(value -> sameNotificationIdentity(value, target))
                .limit(2)
                .toList();
        return matches.size() == 1 ? java.util.Optional.of(matches.get(0)) : java.util.Optional.empty();
    }

    private static boolean sameNotificationIdentity(NotificationConfig left, NotificationConfig right) {
        return Objects.equals(left.getNotificationType(), right.getNotificationType()) &&
                Objects.equals(left.getComment(), right.getComment()) &&
                Objects.equals(left.getSort(), right.getSort());
    }

    private static void preserveNotificationSecrets(NotificationConfig previous, NotificationConfig candidate) {
        if (StrUtil.isBlank(candidate.getMailPassword())) candidate.setMailPassword(previous.getMailPassword());
        if (StrUtil.isBlank(candidate.getServerChanSendKey())) candidate.setServerChanSendKey(previous.getServerChanSendKey());
        if (StrUtil.isBlank(candidate.getServerChan3ApiUrl())) candidate.setServerChan3ApiUrl(previous.getServerChan3ApiUrl());
        if (StrUtil.isBlank(candidate.getTelegramBotToken())) candidate.setTelegramBotToken(previous.getTelegramBotToken());
        if (StrUtil.isBlank(candidate.getWebHookUrl())) candidate.setWebHookUrl(previous.getWebHookUrl());
        if (StrUtil.isBlank(candidate.getWebHookHeader())) candidate.setWebHookHeader(previous.getWebHookHeader());
        if (StrUtil.isBlank(candidate.getWebHookBody())) candidate.setWebHookBody(previous.getWebHookBody());
        if (StrUtil.isBlank(candidate.getEmbyApiKey())) candidate.setEmbyApiKey(previous.getEmbyApiKey());
        if (StrUtil.isBlank(candidate.getOpenListUploadApiKey())) candidate.setOpenListUploadApiKey(previous.getOpenListUploadApiKey());
        if ((candidate.getBarkDeviceKeys() == null || candidate.getBarkDeviceKeys().isEmpty()) &&
                previous.getBarkDeviceKeys() != null) {
            candidate.setBarkDeviceKeys(List.copyOf(previous.getBarkDeviceKeys()));
        }
    }


}
