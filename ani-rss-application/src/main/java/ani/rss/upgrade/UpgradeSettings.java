package ani.rss.upgrade;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class UpgradeSettings {
    private String defaultPolicyId;
    private boolean notifications;
    private String embyUrl = "";
    private String embyApiKey = "";
    private boolean embyKeyConfigured;
    private boolean clearEmbyKey;
    private List<String> libraryIds = new ArrayList<>();
    private List<PathMapping> embyMappings = new ArrayList<>();
    private List<PathMapping> downloaderMappings = new ArrayList<>();
    private String tvRoot = "";
    private String movieRoot = "";
    private String stagingRoot = "";
    private String ffprobe = "ffprobe";
    private String ffmpeg = "ffmpeg";
    private String aiBaseUrl = "https://api.deepseek.com";
    private String aiApiKey = "";
    private boolean aiKeyConfigured;
    private boolean clearAiKey;
    private String aiModel = "";
    private boolean aiEnabled;
    private String aiTestFingerprint = "";
    private int aiDailyRequests = 500;
    private long aiDailyTokens = 1_000_000;
    private int aiConcurrency = 2;
    private int downloadConcurrency = 2;
    private long reserveBytes = 2L * 1024 * 1024 * 1024;
    private int noProgressHours = 6;
    private int downloadTimeoutHours = 72;
    private String timezone = "Asia/Shanghai";
    public record PathMapping(String remoteRoot, String localRoot) {}
}
