package ani.rss.upgrade;

import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import org.springframework.stereotype.Service;

/** Records transfer ownership without manufacturing a subscription. */
@Service
public class UpgradeOwnershipService {
    public void pending(Job job,Release release,String saveRoot) {
        DatabaseManager.withConnection(c->{
            try(var s=c.prepareStatement("INSERT INTO download_ownership(ownership_id,downloader_type,remote_task_id,info_hash,subscription_id,save_root,state,created_at,updated_at,owner_kind,owner_id) VALUES(?,?,?,?,?,?, 'PENDING',?,?, 'UPGRADE_TARGET',?) ON CONFLICT(downloader_type,info_hash) DO UPDATE SET ownership_id=excluded.ownership_id,remote_task_id=excluded.remote_task_id,owner_id=excluded.owner_id,save_root=excluded.save_root,state='PENDING',updated_at=excluded.updated_at WHERE download_ownership.owner_kind='UPGRADE_TARGET' AND (download_ownership.state='STOPPED' OR (download_ownership.ownership_id=excluded.ownership_id AND download_ownership.save_root=excluded.save_root))")) {
                s.setString(1,"upgrade:"+job.getId()); s.setString(2,job.getDownloaderType()); s.setString(3,job.getRemoteTaskId()); s.setString(4,release.getInfoHash().toLowerCase(java.util.Locale.ROOT));
                s.setString(5,""); s.setString(6,saveRoot); s.setLong(7,System.currentTimeMillis()); s.setLong(8,System.currentTimeMillis()); s.setString(9,job.getTargetId()); if(s.executeUpdate()!=1) throw new IllegalStateException("DOWNLOAD_OWNERSHIP_CONFLICT"); return null;
            }
        });
    }
    public void activate(Job job) {
        DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("UPDATE download_ownership SET remote_task_id=?,state='ACTIVE',updated_at=? WHERE ownership_id=? AND owner_kind='UPGRADE_TARGET' AND owner_id=?")) {
            s.setString(1,job.getRemoteTaskId()); s.setLong(2,System.currentTimeMillis()); s.setString(3,"upgrade:"+job.getId()); s.setString(4,job.getTargetId()); s.executeUpdate(); return null;
        } });
    }
    public boolean owns(Job job) {
        return DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("SELECT 1 FROM download_ownership WHERE ownership_id=? AND owner_kind='UPGRADE_TARGET' AND owner_id=? AND downloader_type=? AND state IN ('ACTIVE','PENDING')")) {
            s.setString(1,"upgrade:"+job.getId()); s.setString(2,job.getTargetId()); s.setString(3,job.getDownloaderType());
            try(var rows=s.executeQuery()) { return rows.next(); }
        } });
    }
    public java.util.Optional<String> infoHash(Job job) {
        return DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("SELECT info_hash FROM download_ownership WHERE ownership_id=? AND owner_kind='UPGRADE_TARGET' AND owner_id=? AND downloader_type=?")) {
            s.setString(1,"upgrade:"+job.getId()); s.setString(2,job.getTargetId()); s.setString(3,job.getDownloaderType());
            try(var rows=s.executeQuery()) { return rows.next()?java.util.Optional.ofNullable(rows.getString(1)):java.util.Optional.empty(); }
        } });
    }
    public String saveRoot(Job job) {
        return DatabaseManager.withConnection(c->{
            try(var s=c.prepareStatement("SELECT save_root FROM download_ownership WHERE ownership_id=? AND owner_id=? AND owner_kind='UPGRADE_TARGET' AND state IN ('ACTIVE','PENDING')")) {
                s.setString(1,"upgrade:"+job.getId()); s.setString(2,job.getTargetId());
                try(var r=s.executeQuery()) { return r.next()?r.getString(1):null; }
            }
        });
    }
    static boolean sameSaveRoot(ani.rss.entity.torrent.TorrentsInfo task,String expected) {
        return expected!=null&&!expected.isBlank()&&task.getSavePath()!=null&&!task.getSavePath().isBlank()
                &&LibraryPathMappingService.normalizeRemote(task.getSavePath()).equals(LibraryPathMappingService.normalizeRemote(expected));
    }
    record InstalledTransfer(Job job,String remoteId,String saveRoot,boolean stopped) {}
    /** Only the job that actually installed this version can authorise disposal of its transfer. */
    java.util.Optional<InstalledTransfer> installedTransfer(Version version) {
        if(version.getDownloadHash()==null||version.getDownloaderType()==null) return java.util.Optional.empty();
        return DatabaseManager.withConnection(c->{
            String sql="""
                    SELECT j.payload,o.remote_task_id,o.save_root,o.state
                    FROM download_ownership o JOIN upgrade_jobs j ON o.ownership_id='upgrade:'||j.id
                    JOIN upgrade_job_files f ON f.parent_id=j.id
                    WHERE o.owner_kind='UPGRADE_TARGET' AND o.downloader_type=? AND o.info_hash=?
                      AND o.owner_id=j.parent_id AND j.parent_id=? AND o.state IN ('ACTIVE','STOPPED')
                      AND json_extract(f.payload,'$.action')='INSTALL' AND json_extract(f.payload,'$.versionId')=?
                    """;
            try(var s=c.prepareStatement(sql)) {
                s.setString(1,version.getDownloaderType()); s.setString(2,version.getDownloadHash().toLowerCase(java.util.Locale.ROOT));
                s.setString(3,version.getTargetId()); s.setString(4,version.getId());
                try(var r=s.executeQuery()) {
                    if(!r.next()) return java.util.Optional.empty();
                    return java.util.Optional.of(new InstalledTransfer(ani.rss.commons.GsonStatic.fromJson(r.getString(1),Job.class),
                            r.getString(2),r.getString(3),"STOPPED".equals(r.getString(4))));
                }
            }
        });
    }
    public void transfer(Job from,Job to) {
        DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("UPDATE download_ownership SET ownership_id=?,owner_id=?,updated_at=? WHERE ownership_id=? AND owner_kind='UPGRADE_TARGET' AND owner_id=? AND downloader_type=?")) {
            s.setString(1,"upgrade:"+to.getId()); s.setString(2,to.getTargetId()); s.setLong(3,System.currentTimeMillis()); s.setString(4,"upgrade:"+from.getId()); s.setString(5,from.getTargetId()); s.setString(6,from.getDownloaderType());
            if(s.executeUpdate()!=1) throw new IllegalStateException("共享下载归属转移冲突"); return null;
        } });
    }
    public void stopped(Job job) {
        DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("UPDATE download_ownership SET remote_task_id=NULL,state='STOPPED',updated_at=? WHERE ownership_id=? AND owner_kind='UPGRADE_TARGET' AND owner_id=?")) {
            s.setLong(1,System.currentTimeMillis()); s.setString(2,"upgrade:"+job.getId()); s.setString(3,job.getTargetId()); s.executeUpdate(); return null;
        } });
    }
}
