package ani.rss.upgrade;

import ani.rss.entity.Ani;
import ani.rss.util.other.ConfigUtil;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Single-instance file mutation barrier shared with the original lifecycle. */
public final class UpgradeFileCoordinator {
    public static final ReentrantLock LOCK=new ReentrantLock(true);
    private UpgradeFileCoordinator() {}
    public static Lease lease() { LOCK.lock(); return new Lease(); }
    public static boolean recoveryPending() {
        var counts=new UpgradeRepository().counts("upgrade_jobs");
        return java.util.stream.Stream.of("INSTALLING","INSTALLED","CLEANING_OLD","CLEANUP_PENDING").anyMatch(state->counts.getOrDefault(state,0L)>0);
    }
    public static final class Lease implements AutoCloseable {
        private Lease() {}
        @Override public void close() { LOCK.unlock(); }
    }
    public static boolean managed(Ani subscription) {
        if(persistentConflict(subscription,null)) return true;
        if(subscription==null||ConfigUtil.CONFIG.getUpgradeMode()!=UpgradeMode.AUTOMATIC||subscription.getBgmUrl()==null) return false;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("/subject/(\\d+)(?:/|$|[?#])").matcher(subscription.getBgmUrl());
        if(!m.find()) return false;
        String subjectId=m.group(1);
        UpgradeRepository repository=new UpgradeRepository();
        Path save=Path.of(new ani.rss.service.DownloadService().getDownloadPath(subscription,ConfigUtil.snapshot())).toAbsolutePath().normalize();
        return repository.all("upgrade_targets",null,null,UpgradeModels.Target.class).stream()
                .filter(t->Objects.equals(subjectId,t.getSubjectId())&&!t.isExcluded())
                .filter(t->new UpgradeCampaignService(repository,new QualityPolicyEngine()).eligible(t))
                .anyMatch(t->matchesOutput(t,save));
    }
    /** Recovery claims remain authoritative after mode changes and campaign pauses. */
    public static boolean persistentConflict(Ani subscription,UpgradeModels.Release release) {
        if(subscription==null||subscription.getBgmUrl()==null) return false;
        var match=java.util.regex.Pattern.compile("/subject/(\\d+)(?:/|$|[?#])").matcher(subscription.getBgmUrl());
        if(!match.find()) return false;
        Path save=Path.of(new ani.rss.service.DownloadService().getDownloadPath(subscription,ConfigUtil.snapshot())).toAbsolutePath().normalize();
        var locks=new ChapterCommitLockService();
        for(var target:new UpgradeRepository().all("upgrade_targets",null,null,UpgradeModels.Target.class)) {
            if(!match.group(1).equals(target.getSubjectId())) continue;
            if(matchesOutput(target,save)&&locks.held(target.getSubjectId(),target.getLibraryId(),target,release)) return true;
        }
        return false;
    }
    private static boolean matchesOutput(UpgradeModels.Target target,Path save) {
        return java.util.stream.Stream.of(target.getOutputRoot(),target.getOutputDirectory()).filter(Objects::nonNull).filter(root->!root.isBlank())
                .anyMatch(root->save.startsWith(Path.of(root).toAbsolutePath().normalize()));
    }
    public static void migrated(Ani subscription,String oldRoot,String newRoot) {
        if(subscription.getBgmUrl()==null) return;
        var match=java.util.regex.Pattern.compile("/subject/(\\d+)").matcher(subscription.getBgmUrl()); if(!match.find()) return;
        UpgradeRepository repository=new UpgradeRepository(); Path previous=Path.of(oldRoot).toAbsolutePath().normalize(),next=Path.of(newRoot).toAbsolutePath().normalize();
        // Preserve the independent identity and exact known file manifest even
        // when automatic upgrade was not configured before completion.
        var ownership=new ani.rss.ownership.OwnershipRepository();
        Map<String,Object> archive=new LinkedHashMap<>(); archive.put("subjectId",match.group(1)); archive.put("subscriptionId",subscription.getId());
        archive.put("title",subscription.getTitle()); archive.put("originalTitle",subscription.getJpTitle()); archive.put("season",subscription.getSeason());
        archive.put("directory",next.toString()); archive.put("rss",subscription.getUrl()); archive.put("premiereVerified",false);
        archive.put("files",ownership.listBySubscription(subscription.getId()).stream().map(owner->Map.of("ownership",owner,"files",ownership.listFiles(owner.ownershipId()))).toList());
        String archiveId="completion-archive:"+subscription.getId(); repository.save("upgrade_events",archiveId,match.group(1),archiveId,"COMPLETION_ARCHIVE",1,archive);
        if(repository.all("upgrade_targets",null,null,UpgradeModels.Target.class).stream().noneMatch(t->match.group(1).equals(t.getSubjectId()))) {
            var saved=new UpgradeModels.Target(); saved.setId(UUID.randomUUID().toString()); saved.setSubjectId(match.group(1)); saved.setLibraryId("UNASSIGNED");
            saved.setTitle(subscription.getTitle()); saved.setOriginalTitle(subscription.getJpTitle()); saved.setFinished(true); saved.setState("WAITING_LIBRARY"); saved.setOutputDirectory(next.toString());
            saved.setPremiereSource("COMPLETION_ARCHIVE_UNVERIFIED");
            if(subscription.getUrl()!=null&&!subscription.getUrl().isBlank()) saved.getRssUrls().add(subscription.getUrl());
            repository.save("upgrade_targets",saved.getId(),saved.getLibraryId(),saved.getSubjectId()+":"+saved.getLibraryId(),saved.getState(),1,saved);
        }
        for(var target:repository.all("upgrade_targets",null,null,UpgradeModels.Target.class)) {
            if(!match.group(1).equals(target.getSubjectId())) continue;
            boolean moved=false;
            for(var version:repository.all("upgrade_versions",target.getId(),"ACTIVE",UpgradeModels.Version.class)) {
                if(version.getPath()==null||!Path.of(version.getPath()).toAbsolutePath().normalize().startsWith(previous)) continue;
                Path destination=next.resolve(previous.relativize(Path.of(version.getPath()).toAbsolutePath().normalize()));
                try {
                    if(!java.nio.file.Files.isRegularFile(destination,java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    var attrs=java.nio.file.Files.readAttributes(destination,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    version.setPath(destination.toString()); version.setSize(attrs.size()); version.setModifiedAt(attrs.lastModifiedTime().toMillis()); version.setFileKey(Objects.toString(attrs.fileKey(),null));
                    repository.save("upgrade_versions",version.getId(),target.getId(),null,"ACTIVE",1,version); moved=true;
                } catch(java.io.IOException e) { repository.event(target.getId(),"MIGRATION_PATH_RECHECK_REQUIRED","完结迁移后的版本路径需要重新核对"); }
            }
            if(moved||"UNASSIGNED".equals(target.getLibraryId())) { target.setOutputDirectory(next.toString()); target.setFinished(true); target.setRevision(target.getRevision()+1); repository.save("upgrade_targets",target.getId(),target.getLibraryId(),target.getSubjectId()+":"+target.getLibraryId(),target.getState(),target.getRevision(),target); repository.event(target.getId(),"COMPLETION_TARGET_PRESERVED","完结迁移已同步版本位置，持续监控档案保留"); }
        }
    }
}
