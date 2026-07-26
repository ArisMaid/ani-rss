package ani.rss.config;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.MavenUtils;
import ani.rss.auth.AuthService;
import ani.rss.service.BackupService;
import ani.rss.service.TaskService;
import ani.rss.completion.CompletionMigrationService;
import ani.rss.ownership.OwnershipMigrationService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.net.NetUtil;
import cn.hutool.core.util.RuntimeUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

@Slf4j
@Component
@ConditionalOnProperty(name = "ani-rss.startup.enabled", matchIfMissing = true)
public class Runner implements ApplicationRunner {

    @Resource
    private BackupService backupService;

    @Resource
    private TaskService taskService;

    @Resource
    private OwnershipMigrationService ownershipMigrationService;

    @Resource
    private CompletionMigrationService completionMigrationService;

    @Value("${server.port}")
    private String port;

    @Override
    public void run(@NonNull ApplicationArguments args) {
        try {
            ConfigUtil.load();
            AuthService.initialize();
            backupService.backup();

            AniUtil.load();
            try {
                ownershipMigrationService.adoptStrictCandidates();
            } catch (Exception e) {
                log.warn("旧下载任务归属扫描失败: {}", ExceptionUtils.getMessage(e));
            }
            completionMigrationService.reconcilePendingFinalizations();
            taskService.start();
            String version = MavenUtils.getVersion();
            log.info("version {}", version);


            for (String ip : NetUtil.localIpv4s()) {
                InetSocketAddress inetSocketAddress = new InetSocketAddress(ip, Integer.parseInt(port));
                if (NetUtil.isOpen(inetSocketAddress, 100)) {
                    log.info("http://{}:{}", ip, port);
                }
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            System.exit(1);
        }
        RuntimeUtil.addShutdownHook(() -> log.info("程序退出..."));
    }
}
