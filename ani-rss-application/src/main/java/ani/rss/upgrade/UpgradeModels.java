package ani.rss.upgrade;

import lombok.Data;
import java.util.*;

public final class UpgradeModels {
    private UpgradeModels() {}
    public enum JobState { DISCOVERED, ANALYZING, ELIGIBLE, QUEUED, DOWNLOADING, VERIFYING, READY_TO_COMMIT,
        INSTALLING, INSTALLED, CLEANING_OLD, COMPLETED, NO_RESOURCE, NOT_BETTER, WAITING_EVIDENCE, WAITING_AI,
        NEEDS_REVIEW, RETRY_WAIT, PAUSED, CANCELLED, FAILED, CLEANUP_PENDING }

    @Data public static class Campaign {
        private String id;
        private String name;
        private String startDate;
        private String endDate;
        private String catalogSource;
        private Integer mikanYear;
        private String mikanSeason;
        private String policyId;
        private String libraryId;
        private int priority;
        private boolean enabled = true;
        private long createdAt = System.currentTimeMillis();
        private long revision = 1;
    }
    @Data public static class Target {
        private String id;
        private String subjectId;
        private String title;
        private String originalTitle;
        private List<String> aliases = new ArrayList<>();
        private String type;
        private boolean finished;
        private String premiereDate;
        private String premiereSource;
        private String premiereUpper;
        private String summary;
        private String cover;
        private Map<String,String> credits = new LinkedHashMap<>();
        private Map<String,String> chapterTypes = new LinkedHashMap<>();
        private Map<String,String> chapterIds = new LinkedHashMap<>();
        private Map<String,String> chapterTitles = new LinkedHashMap<>();
        private Map<String,String> chapterAirDates = new LinkedHashMap<>();
        private List<String> relatedSubjects = new ArrayList<>();
        private long metadataAt;
        private String libraryId;
        private String outputRoot;
        private String outputDirectory;
        private String policyOverride;
        private String campaignId;
        private boolean excluded;
        private String state = "MONITORING";
        private String embySeriesId;
        private Integer season;
        private Map<String,String> episodeMapping = new LinkedHashMap<>();
        private List<String> rssUrls = new ArrayList<>();
        private long nextScan;
        private long revision = 1;
    }
    @Data @lombok.EqualsAndHashCode(of={"source","field","quote","sourceReference","verificationLevel"}) public static class Evidence {
        private String source;
        private String field;
        private String quote;
        private String sourceType;
        private String sourceReference;
        private String evidenceSnippet;
        private String verificationLevel = "DECLARED";
        private long observedAt = System.currentTimeMillis();
        public Evidence() {}
        public Evidence(String source, String field, String quote) { this.source=source; this.field=field; this.quote=quote; this.sourceType=source; this.sourceReference=source; this.evidenceSnippet=quote; }
    }
    @Data public static class Release {
        private String id;
        private String targetId;
        private String subjectId;
        private String source;
        private String sourceId;
        private String infoHash;
        private String torrentUrl;
        private String title;
        private Integer season;
        private String analysisContext;
        private long expectedBytes;
        private long publishedAt;
        private String publisher;
        private String provider;
        private String providerId;
        private long detailAt;
        private Set<String> episodes = new TreeSet<>();
        private String chapterType = "NORMAL";
        private String contentEdition = "MAIN";
        private Integer resolution;
        private Set<String> groups = new TreeSet<>();
        private Set<String> languages = new TreeSet<>();
        private String subtitleLayout;
        private String subtitlePackaging;
        private String sourceType;
        private String codec;
        private String container;
        private Integer correction;
        private List<String> files = new ArrayList<>();
        private List<Evidence> evidence = new ArrayList<>();
        private String state = "ANALYZING";
        private String reason;
        private long retryAt;
        private long revision = 1;
    }
    @Data public static class Version {
        private String id;
        private String targetId;
        private String libraryId;
        private String embyItemId;
        private String mediaSourceId;
        private String path;
        private String remotePath;
        private String origin;
        private String kind = "VIDEO";
        private String sourcePath;
        private Release attributes;
        private long size;
        private long modifiedAt;
        private String sha256;
        private String fileKey;
        private String downloadHash;
        private String downloaderType;
        private boolean active = true;
        private boolean protectedVersion;
        private boolean accessible;
        private long indexAt;
        private String indexBatch;
        private Set<String> relatedVersionIds = new TreeSet<>();
    }
    @Data public static class Job {
        private String id;
        private String kind = "UPGRADE";
        private String targetId;
        private String releaseId;
        private String campaignId;
        private String identityKey;
        private JobState state = JobState.DISCOVERED;
        private String reasonCode;
        private String message;
        private boolean retryable;
        private long generation;
        private long policyRevision;
        private long targetRevision;
        private long releaseRevision;
        private String baselineFingerprint;
        private String decisionFingerprint;
        private String policyId;
        private long createdAt = System.currentTimeMillis();
        private long retryAt;
        private long leaseUntil;
        private int attempts;
        private String stagingPath;
        private String remoteTaskId;
        private long downloadedBytes;
        private long reservedBytes;
        private String downloaderType;
        private String downloaderIdentity;
        private long lastProgressAt = System.currentTimeMillis();
        private List<String> versionIds = new ArrayList<>();
    }
    @Data public static class JobFile {
        private String id;
        private String jobId;
        private String action;
        private String kind = "VIDEO";
        private String source;
        private String temporary;
        private String destination;
        private String allowedRoot;
        private String versionId;
        private String state = "PLANNED";
        private long size;
        private long modifiedAt;
        private String fileKey;
        private String sha256;
        private String error;
        private Release attributes;
    }
    @Data public static class Cursor {
        private List<MikanCatalogEntry> mikanEntries;
        private String completedBatch;
        private List<String> discoveredFeeds = new ArrayList<>();
        private List<String> queryAliases = new ArrayList<>();
        private boolean coverageIncomplete;
        private boolean daily;
        private boolean full = true;
        private long watermark;
        private long scanStarted;
        private long lastFull;
        private int aliasIndex = -1;
        private String id;
        private String scope;
        private int offset;
        private String shard;
        private boolean complete;
        private long lastSuccess;
        private long retryAt;
        private int total;
        private String error;
    }
    @Data public static class MikanCatalogEntry {
        private String mikanId;
        private String title;
        private String category;
        private String url;
        private String cover;
    }
}
