package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import java.util.*;

/** Durable, sorted claims. Expiration never authorizes stealing an unfinished file commit. */
public final class ChapterCommitLockService {
    private static final Set<String> RECOVERING=Set.of("INSTALLING","INSTALLED","CLEANING_OLD","CLEANUP_PENDING");
    public record Claim(String mediaKey,String subjectId,String libraryId,String chapterKey,String contentEdition,String operationId,long leaseUntil) {}
    private static List<Claim> claims(Job job,Target target,List<JobFile> files,long until) {
        Map<String,Claim> claims=new TreeMap<>();
        for(JobFile file:files) {
            Release release=file.getAttributes(); if(release==null) continue;
            for(String number:release.getEpisodes()) {
                String kind=Objects.toString(release.getChapterType(),"NORMAL"),edition=Objects.toString(release.getContentEdition(),"MAIN");
                // Always retain the canonical number alias: learning a Bangumi
                // chapter ID later must not bypass an older durable claim.
                String canonical="MOVIE".equals(kind)&&"MOVIE".equals(number)?"MOVIE":ReleaseAnalysisService.number(number);
                Set<String> chapters=new TreeSet<>(); chapters.add(kind+":number:"+canonical);
                String chapterId=target.getChapterIds().get(kind+":"+number);
                if(chapterId!=null) chapters.add(kind+":bgm:"+chapterId);
                for(String chapter:chapters) {
                    String key=GsonStatic.toJson(List.of(target.getSubjectId(),target.getLibraryId(),chapter,edition));
                    claims.put(key,new Claim(key,target.getSubjectId(),target.getLibraryId(),chapter,edition,job.getId(),until));
                }
            }
        }
        return new ArrayList<>(claims.values());
    }
    public boolean acquire(Job job,Target target,List<JobFile> files,long now) {
        List<Claim> requested=claims(job,target,files,now+300000);
        if(requested.isEmpty()) throw new IllegalArgumentException("提交缺少规范章节身份");
        return DatabaseManager.transaction(c->{
            // Preflight every sorted key before any write, so a batch cannot
            // leave behind partial claims when its last chapter is busy.
            for(Claim claim:requested) try(var statement=c.prepareStatement("SELECT l.operation_id,l.lease_until,j.state FROM upgrade_commit_locks l LEFT JOIN upgrade_jobs j ON j.id=l.operation_id WHERE l.media_key=? AND l.state='HELD'")) {
                statement.setString(1,claim.mediaKey());
                try(var rows=statement.executeQuery()) {
                    if(rows.next()&&!job.getId().equals(rows.getString(1))&&(rows.getLong(2)>now||RECOVERING.contains(Objects.toString(rows.getString(3),"")))) return false;
                }
            }
            for(Claim claim:requested) try(var statement=c.prepareStatement("INSERT INTO upgrade_commit_locks(media_key,subject_id,library_id,chapter_key,content_edition,operation_id,state,lease_until,updated_at) VALUES(?,?,?,?,?,?,'HELD',?,?) ON CONFLICT(media_key) DO UPDATE SET operation_id=excluded.operation_id,state='HELD',lease_until=excluded.lease_until,updated_at=excluded.updated_at")) {
                statement.setString(1,claim.mediaKey()); statement.setString(2,claim.subjectId()); statement.setString(3,claim.libraryId()); statement.setString(4,claim.chapterKey()); statement.setString(5,claim.contentEdition()); statement.setString(6,claim.operationId()); statement.setLong(7,claim.leaseUntil()); statement.setLong(8,now); statement.executeUpdate();
            }
            return true;
        });
    }
    public void release(String operationId) {
        DatabaseManager.withConnection(c->{ try(var statement=c.prepareStatement("UPDATE upgrade_commit_locks SET state='RELEASED',lease_until=0,updated_at=? WHERE operation_id=?")) {
            statement.setLong(1,System.currentTimeMillis()); statement.setString(2,operationId); statement.executeUpdate(); return null;
        } });
    }
    public boolean held(String subjectId,String libraryId,Target target,Release release) {
        Set<String> requested=null;
        if(release!=null&&!release.getEpisodes().isEmpty()) {
            Job temporary=new Job(); temporary.setId("inspection"); JobFile file=new JobFile(); file.setAttributes(release);
            requested=new HashSet<>(claims(temporary,target,List.of(file),0).stream().map(Claim::mediaKey).toList());
        }
        Set<String> keys=requested;
        return DatabaseManager.withConnection(c->{ try(var statement=c.prepareStatement("SELECT l.media_key,l.lease_until,j.state FROM upgrade_commit_locks l LEFT JOIN upgrade_jobs j ON j.id=l.operation_id WHERE l.subject_id=? AND l.library_id=? AND l.state='HELD'")) {
            statement.setString(1,subjectId); statement.setString(2,libraryId);
            try(var rows=statement.executeQuery()) { while(rows.next()) if((keys==null||keys.contains(rows.getString(1)))&&(rows.getLong(2)>System.currentTimeMillis()||RECOVERING.contains(Objects.toString(rows.getString(3),"")))) return true; return false; }
        } });
    }
    public List<Claim> list(String operationId) {
        return DatabaseManager.withConnection(c->{ try(var statement=c.prepareStatement("SELECT media_key,subject_id,library_id,chapter_key,content_edition,operation_id,lease_until FROM upgrade_commit_locks WHERE operation_id=? AND state='HELD' ORDER BY media_key")) {
            statement.setString(1,operationId); List<Claim> claims=new ArrayList<>();
            try(var rows=statement.executeQuery()) { while(rows.next()) claims.add(new Claim(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getLong(7))); } return claims;
        } });
    }
}
