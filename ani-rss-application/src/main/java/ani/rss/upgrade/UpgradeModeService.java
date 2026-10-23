package ani.rss.upgrade;

import ani.rss.entity.Config;
import ani.rss.exception.ApiProblemException;
import ani.rss.util.other.ConfigUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class UpgradeModeService {
    public static void normalize(Config config) {
        if (config.getUpgradeMode() == null) {
            config.setUpgradeMode(Boolean.TRUE.equals(config.getDelete())
                    && Boolean.TRUE.equals(config.getStandbyRss()) && !Boolean.TRUE.equals(config.getCoexist())
                    ? UpgradeMode.LEGACY : UpgradeMode.OFF);
        }
        if (config.getUpgradeModeRevision() == null) config.setUpgradeModeRevision(0L);
        if (config.getUpgrade() == null) config.setUpgrade(new UpgradeSettings());
        UpgradeSettings s = config.getUpgrade();
        if (s.getDownloadConcurrency() < 1 || s.getDownloadConcurrency() > 20
                || s.getAiConcurrency() < 1 || s.getAiConcurrency() > 8
                || s.getAiDailyRequests() < 0 || s.getAiDailyTokens() < 0 || s.getReserveBytes() < 0
                || s.getNoProgressHours() < 1 || s.getDownloadTimeoutHours() < s.getNoProgressHours())
            throw new IllegalArgumentException("自动洗版配额或超时设置无效");
        java.time.ZoneId.of(s.getTimezone());
    }

    public ModeView mode() {
        Config c = ConfigUtil.snapshot();
        normalize(c);
        return new ModeView(c.getUpgradeMode(), c.getUpgradeModeRevision());
    }

    public ModeView switchMode(UpgradeMode mode, long expectedRevision) {
        if (mode == null) throw new IllegalArgumentException("请选择洗版模式");
        ConfigUtil.switchUpgradeMode(mode, expectedRevision);
        return mode();
    }

    public static void conflict(long revision) {
        throw new ApiProblemException(HttpStatus.CONFLICT, "UPGRADE_CONFIG_CONFLICT",
                "设置已被其他页面修改，请刷新后重试", null, Map.of("revision", revision));
    }

    public static boolean legacyAllowed() {
        Config c = ConfigUtil.CONFIG;
        if (c.getUpgradeMode() == null) return Boolean.TRUE.equals(c.getDelete())
                && Boolean.TRUE.equals(c.getStandbyRss()) && !Boolean.TRUE.equals(c.getCoexist());
        return c.getUpgradeMode() == UpgradeMode.LEGACY;
    }

    public record ModeView(UpgradeMode mode, long revision) {}
}
