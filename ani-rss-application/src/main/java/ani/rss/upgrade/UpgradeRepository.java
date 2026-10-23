package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.persistence.DatabaseManager;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class UpgradeRepository {
    public static final List<String> TABLES = List.of("upgrade_policies", "upgrade_campaigns", "upgrade_targets",
            "upgrade_target_campaigns", "emby_library_items", "upgrade_releases", "upgrade_release_sources",
            "upgrade_versions", "upgrade_jobs", "upgrade_job_files", "upgrade_scan_cursors", "upgrade_ai_cache", "upgrade_events");

    private static String table(String table) {
        if (!TABLES.contains(table)) throw new IllegalArgumentException("未知洗版数据类型");
        return table;
    }

    public <T> Optional<T> get(String table, String id, Class<T> type) {
        return DatabaseManager.withConnection(c -> {
            String expiry="upgrade_ai_cache".equals(table)?" AND (state<>'CACHED' OR updated_at>"+(System.currentTimeMillis()-java.time.Duration.ofDays(90).toMillis())+")":"";
            if("upgrade_policies".equals(table)) expiry=" AND state<>'DELETED'";
            try (var s = c.prepareStatement("SELECT payload FROM " + table(table) + " WHERE id=?"+expiry)) {
                s.setString(1, id);
                try (var r = s.executeQuery()) { return r.next() ? Optional.of(GsonStatic.fromJson(r.getString(1),type)) : Optional.empty(); }
            }
        });
    }

    public <T> Optional<T> identity(String table, String key, Class<T> type) {
        return DatabaseManager.withConnection(c -> {
            try (var s = c.prepareStatement("SELECT payload FROM " + table(table) + " WHERE identity_key=?")) {
                s.setString(1,key);
                try (var r = s.executeQuery()) { return r.next() ? Optional.of(GsonStatic.fromJson(r.getString(1),type)) : Optional.empty(); }
            }
        });
    }

    public <T> List<T> list(String table, String parent, String state, int offset, int limit, Class<T> type) {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("分页范围为 1–200");
        return DatabaseManager.withConnection(c -> {
            String sql = "SELECT payload FROM " + table(table) + " WHERE (? IS NULL OR parent_id=?) AND (? IS NULL OR state=?) ORDER BY updated_at,id LIMIT ? OFFSET ?";
            try (var s = c.prepareStatement(sql)) {
                s.setString(1,parent); s.setString(2,parent); s.setString(3,state); s.setString(4,state); s.setInt(5,limit); s.setInt(6,offset);
                try (var r = s.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    while (r.next()) rows.add(GsonStatic.fromJson(r.getString(1),type));
                    return rows;
                }
            }
        });
    }

    public void save(String table, String id, String parent, String key, String state, long revision, Object value) {
        String json = GsonStatic.toJson(value);
        var fields=com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        if (json.length() > 1_048_576) throw new IllegalArgumentException("洗版记录超过大小限制");
        DatabaseManager.withConnection(c -> {
            try (var s = c.prepareStatement("INSERT INTO " + table(table)
                    + "(id,parent_id,identity_key,state,revision,updated_at,payload,subject_id,library_id,resource_hash,retry_at,lease_until,generation) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT(id) DO UPDATE SET parent_id=excluded.parent_id,identity_key=excluded.identity_key,state=excluded.state,revision=excluded.revision,updated_at=excluded.updated_at,payload=excluded.payload,subject_id=excluded.subject_id,library_id=excluded.library_id,resource_hash=excluded.resource_hash,retry_at=excluded.retry_at,lease_until=excluded.lease_until,generation=excluded.generation")) {
                s.setString(1,id); s.setString(2,parent == null ? "" : parent); s.setString(3,key); s.setString(4,state == null ? "" : state);
                s.setLong(5,revision); s.setLong(6,System.currentTimeMillis()); s.setString(7,json);
                s.setString(8,UpgradeHttp.string(fields,"subjectId")); s.setString(9,UpgradeHttp.string(fields,"libraryId")); s.setString(10,UpgradeHttp.string(fields,"infoHash"));
                s.setLong(11,longField(fields,"retryAt")); s.setLong(12,longField(fields,"leaseUntil")); s.setLong(13,longField(fields,"generation"));
                s.executeUpdate(); return null;
            }
        });
    }

    private static long longField(com.google.gson.JsonObject fields,String name) { return fields.has(name)&&!fields.get(name).isJsonNull()?fields.get(name).getAsLong():0; }
    public boolean cancelRequested(String id) {
        return DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("SELECT cancel_requested FROM upgrade_jobs WHERE id=?")) { s.setString(1,id); try(var r=s.executeQuery()) { return r.next()&&r.getBoolean(1); } } });
    }
    public void requestCancellation(String id) {
        DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("UPDATE upgrade_jobs SET cancel_requested=1 WHERE id=? AND state NOT IN ('INSTALLING','INSTALLED','CLEANING_OLD','CLEANUP_PENDING','COMPLETED')")) {
            s.setString(1,id); if(s.executeUpdate()!=1) throw new IllegalArgumentException("文件提交已开始，需先收敛当前提交"); return null;
        } });
    }
    public void resetCancellation(String id) { DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("UPDATE upgrade_jobs SET cancel_requested=0 WHERE id=?")) { s.setString(1,id); s.executeUpdate(); return null; } }); }
    public void resetPlannedFiles(String jobId) {
        DatabaseManager.withConnection(c->{ try(var s=c.prepareStatement("DELETE FROM upgrade_job_files WHERE parent_id=? AND state='PLANNED'")) { s.setString(1,jobId); s.executeUpdate(); return null; } });
    }

    public Map<String,Long> counts(String table) {
        return DatabaseManager.withConnection(c -> {
            try (var s = c.prepareStatement("SELECT state,COUNT(*) FROM " + table(table) + " GROUP BY state"); var r = s.executeQuery()) {
                Map<String,Long> values = new LinkedHashMap<>();
                while(r.next()) values.put(r.getString(1),r.getLong(2)); return values;
            }
        });
    }

    public <T> List<T> all(String table,String parent,String state,Class<T> type) {
        List<T> result=new ArrayList<>();
        for(int offset=0;;offset+=200) {
            List<T> page=list(table,parent,state,offset,200,type); result.addAll(page);
            if(page.size()<200) return result;
        }
    }

    public void event(String operationId, String code, String message) {
        String id = UUID.randomUUID().toString();
        save("upgrade_events",id,operationId,null,code,1,Map.of("id",id,"code",code,"message",message,"at",System.currentTimeMillis()));
    }
    public int pruneExpiredEvents(long now) {
        return DatabaseManager.withConnection(c->{
            try(var statement=c.prepareStatement("DELETE FROM upgrade_events WHERE updated_at<? AND state<>'COMPLETION_ARCHIVE' "
                    +"AND parent_id NOT IN (SELECT id FROM upgrade_versions WHERE state='ACTIVE') "
                    +"AND parent_id NOT IN (SELECT parent_id FROM upgrade_versions WHERE state='ACTIVE') "
                    +"AND parent_id NOT IN (SELECT id FROM upgrade_jobs WHERE state NOT IN ('COMPLETED','CANCELLED','FAILED','NOT_BETTER') OR json_extract(payload,'$.reasonCode')='CANCEL_STOP_PENDING') "
                    +"AND parent_id NOT IN (SELECT j.id FROM upgrade_jobs j JOIN upgrade_versions v ON v.parent_id=j.parent_id WHERE v.state='ACTIVE')")) {
                statement.setLong(1,now-java.time.Duration.ofDays(180).toMillis()); return statement.executeUpdate();
            }
        });
    }
    public Map<String,Long> coverageMetrics() {
        return DatabaseManager.withConnection(c->{
            String sql="SELECT COUNT(*) AS totalTargets,"
                    +" COALESCE(SUM(EXISTS(SELECT 1 FROM upgrade_releases r WHERE r.parent_id=t.id AND r.state<>'MERGED')),0) AS discoveredResources,"
                    +" COALESCE(SUM(EXISTS(SELECT 1 FROM upgrade_jobs j WHERE j.parent_id=t.id AND j.state='COMPLETED' AND json_extract(j.payload,'$.kind')='UPGRADE')),0) AS installedAndCleaned,"
                    +" COALESCE(SUM(EXISTS(SELECT 1 FROM upgrade_versions v WHERE v.parent_id=t.id AND v.state='ACTIVE' AND json_extract(v.payload,'$.origin')='AUTOMATIC_UPGRADE' AND json_extract(v.payload,'$.embyItemId') IS NOT NULL)),0) AS embyCollected,"
                    +" COALESCE(SUM(t.state='NEEDS_REVIEW' OR EXISTS(SELECT 1 FROM upgrade_jobs j WHERE j.parent_id=t.id AND j.state='NEEDS_REVIEW')),0) AS needsReview,"
                    +" COALESCE(SUM(EXISTS(SELECT 1 FROM upgrade_releases r WHERE r.parent_id=t.id AND r.state='UNAVAILABLE')),0) AS unavailableResources,"
                    +" COALESCE(SUM(EXISTS(SELECT 1 FROM upgrade_jobs j WHERE j.parent_id=t.id AND j.state='RETRY_WAIT' AND COALESCE(json_extract(j.payload,'$.reasonCode'),'') NOT LIKE 'AI_%')),0) AS serviceFailures,"
                    +" COALESCE(SUM(EXISTS(SELECT 1 FROM upgrade_releases r WHERE r.parent_id=t.id AND r.state='WAITING_AI' AND json_extract(r.payload,'$.reason') LIKE '%AI_DAILY_BUDGET%')),0) AS aiBudgetBlocked"
                    +" FROM upgrade_targets t";
            try(var statement=c.createStatement();var rows=statement.executeQuery(sql)) {
                Map<String,Long> result=new LinkedHashMap<>(); if(rows.next()) for(String name:List.of("totalTargets","discoveredResources","installedAndCleaned","embyCollected","needsReview","unavailableResources","serviceFailures","aiBudgetBlocked")) result.put(name,rows.getLong(name)); return result;
            }
        });
    }
}
