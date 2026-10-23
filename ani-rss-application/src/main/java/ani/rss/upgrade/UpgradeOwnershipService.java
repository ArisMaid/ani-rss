package ani.rss.upgrade;

import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import org.springframework.stereotype.Service;

/** Records transfer ownership without manufacturing a subscription. */
@Service
public class UpgradeOwnershipService {
    public void pending(Job job,Release release,String saveRoot) {
        DatabaseManager.withConnection(c->{
            try(var s=c.prepareStatement("INSERT INTO download_ownership(ownership_id,downloader_type,remote_task_id,info_hash,subscription_id,save_root,state,created_at,updated_at,owner_kind,owner_id) VALUES(?,?,?,?,?,?, 'PENDING',?,?, 'UPGRADE_TARGET',?) ON CONFLICT(downloader_type,info_hash) DO UPDATE SET ownership_id=excluded.ownership_id,remote_task_id=excluded.remote_task_id,owner_id=excluded.owner_id,save_root=excluded.save_root,state='PENDING',updated_at=excluded.updated_at WHERE download_ownership.owner_kind='UPGRADE_TARGET' AND download_ownership.state='STOPPED'")) {
                s.setString(1,"upgrade:"+job.getId()); s.setString(2,job.getDownloaderType()); s.setString(3,job.getRemoteTaskId()); s.setString(4,release.getInfoHash().toLowerCase(java.util.Locale.ROOT));
                s.setString(5,""); s.setString(6,saveRoot); s.setLong(7,System.currentTimeMillis()); s.setLong(8,System.currentTimeMillis()); s.setString(9,job.getTargetId()); s.executeUpdate(); return null;
            }
        });
    }
    public void activate(Job job) {
        DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("UPDATE download_ownership SET remote_task_id=?,state='ACTIVE',updated_at=? WHERE ownership_id=? AND owner_kind='UPGRADE_TARGET' AND owner_id=?")) {
            s.setString(1,job.getRemoteTaskId()); s.setLong(2,System.currentTimeMillis()); s.setString(3,"upgrade:"+job.getId()); s.setString(4,job.getTargetId()); s.executeUpdate(); return null;
        } });
    }
    public boolean owns(Job job) {
        return DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("SELECT 1 FROM download_ownership WHERE ownership_id=? AND owner_kind='UPGRADE_TARGET' AND owner_id=? AND downloader_type=?")) {
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
