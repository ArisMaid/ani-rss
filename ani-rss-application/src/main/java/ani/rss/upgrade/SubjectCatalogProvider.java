package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class SubjectCatalogProvider {
    private final UpgradeRepository repository;
    private final MikanQuarterCatalog mikan;
    public SubjectCatalogProvider(UpgradeRepository repository) { this(repository,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public SubjectCatalogProvider(UpgradeRepository repository,MikanQuarterCatalog mikan) { this.repository=repository; this.mikan=mikan; }
    public JsonObject detail(String subjectId) {
        if(!subjectId.matches("\\d+")) throw new IllegalArgumentException("作品 ID 无效");
        return UpgradeHttp.get(UpgradeHttp.base(ConfigUtil.snapshot().getBgmApi())+"/v0/subjects/"+subjectId,headers());
    }
    private Map<String,String> headers() {
        String token=ConfigUtil.snapshot().getBgmToken();
        return token==null||token.isBlank()?Map.of():Map.of("Authorization","Bearer "+token);
    }
    public boolean scanStep(Campaign campaign) {
        if("MIKAN".equals(campaign.getCatalogSource())&&!mikan.scanStep(campaign,this)) return false;
        String id="catalog:"+campaign.getId();
        Cursor cursor=repository.get("upgrade_scan_cursors",id,Cursor.class).orElseGet(Cursor::new);
        LocalDate start=LocalDate.parse(campaign.getStartDate()), end=LocalDate.parse(campaign.getEndDate());
        if(cursor.isComplete()) return true;
        LocalDate shard=cursor.getShard()==null?start:LocalDate.parse(cursor.getShard());
        LocalDate shardEnd=cursor.isDaily()?shard:shard.withDayOfMonth(shard.lengthOfMonth()); if(shardEnd.isAfter(end)) shardEnd=end;
        Map<String,Object> request=Map.of("keyword","","sort","rank","filter",Map.of("type",List.of(2),"air_date",List.of(">="+shard,"<="+shardEnd)));
        JsonObject page=UpgradeHttp.post(UpgradeHttp.base(ConfigUtil.snapshot().getBgmApi())+"/v0/search/subjects?limit=100&offset="+cursor.getOffset(),headers(),request);
        if(!page.has("data")||!page.has("total")) throw new UpgradeHttp.SourceFailure("BANGUMI_SCHEMA_CHANGED",false);
        JsonArray data=page.getAsJsonArray("data"); int total=page.get("total").getAsInt();
        if(total>=1000&&!cursor.isDaily()) {
            cursor.setId(id); cursor.setScope(campaign.getId()); cursor.setShard(shard.toString()); cursor.setDaily(true); cursor.setOffset(0); cursor.setTotal(total); cursor.setError("月份结果达到上游窗口边界，切换逐日分页");
            repository.save("upgrade_scan_cursors",id,campaign.getId(),null,"SCANNING",1,cursor); return false;
        }
        if(total>=1000&&cursor.isDaily()) { cursor.setCoverageIncomplete(true); cursor.setError("单日结果达到窗口边界，目录覆盖尚未完整"); repository.event(campaign.getId(),"CATALOG_COVERAGE_INCOMPLETE",cursor.getError()); }
        if(data.isEmpty()&&cursor.getOffset()<total) throw new UpgradeHttp.SourceFailure("CATALOG_PAGE_TRUNCATED",true);
        for(JsonElement element:data) {
            accept(campaign,element.getAsJsonObject(),"BANGUMI_SEARCH");
        }
        cursor.setId(id); cursor.setScope(campaign.getId()); cursor.setOffset(cursor.getOffset()+data.size()); cursor.setTotal(total);
        if(cursor.getOffset()>=total) {
            shard=shardEnd.plusDays(1); cursor.setOffset(0);
            if(shard.isAfter(end)) { cursor.setComplete(true); cursor.setLastSuccess(System.currentTimeMillis()); }
        }
        cursor.setShard(shard.toString()); if(total<1000&&!cursor.isCoverageIncomplete()) cursor.setError(null);
        if(cursor.isComplete()&&"MIKAN".equals(campaign.getCatalogSource())) {
            Cursor mikanCursor=repository.get("upgrade_scan_cursors","mikan-catalog:"+campaign.getId(),Cursor.class).orElseThrow();
            if(mikanCursor.isCoverageIncomplete()) { cursor.setCoverageIncomplete(true); cursor.setError("Mikan 条目仍有待核对证据："+mikanCursor.getError()); }
        }
        repository.save("upgrade_scan_cursors",id,campaign.getId(),null,cursor.isComplete()?(cursor.isCoverageIncomplete()?"INCOMPLETE":"COMPLETE"):"SCANNING",1,cursor);
        return cursor.isComplete();
    }
    Target accept(Campaign campaign,JsonObject subject,String source) {
            LocalDate start=LocalDate.parse(campaign.getStartDate()),end=LocalDate.parse(campaign.getEndDate());
            String sid=UpgradeHttp.string(subject,"id");
            String date=UpgradeHttp.string(subject,"date");
            if(sid==null) throw new UpgradeHttp.SourceFailure("BANGUMI_SUBJECT_ID_MISSING",false);
            LocalDate premiere=null,upper=null;
            try { premiere=LocalDate.parse(date); upper=premiere; } catch(RuntimeException e) {
                try { YearMonth month=YearMonth.parse(date); premiere=month.atDay(1); upper=month.atEndOfMonth(); } catch(RuntimeException ignored) { }
            }
            if(premiere!=null&&(upper.isBefore(start)||premiere.isAfter(end))) return null;
            String key=sid+":"+campaign.getLibraryId();
            Target t=repository.identity("upgrade_targets",key,Target.class).orElse(null);
            if(t==null&&ConfigUtil.snapshot().getUpgrade().getLibraryIds().size()==1) t=repository.identity("upgrade_targets",sid+":UNASSIGNED",Target.class).orElse(null);
            if(t==null) t=new Target();
            if(t.getId()==null) t.setId(UUID.randomUUID().toString());
            t.setSubjectId(sid); t.setLibraryId(campaign.getLibraryId());
            if(t.getMetadataAt()==0||t.getPremiereDate()==null) { t.setPremiereDate(premiere==null?null:premiere.toString()); t.setPremiereUpper(upper==null?null:upper.toString()); t.setPremiereSource(source); }
            if(t.getPremiereDate()==null||t.getPremiereDate().compareTo(campaign.getStartDate())<0||Objects.toString(t.getPremiereUpper(),t.getPremiereDate()).compareTo(campaign.getEndDate())>0) t.setState("WAITING_PREMIERE_EVIDENCE");
            t.setOriginalTitle(UpgradeHttp.string(subject,"name")); t.setTitle(UpgradeHttp.string(subject,"name_cn"));
            if(t.getTitle()==null||t.getTitle().isBlank()) t.setTitle(t.getOriginalTitle());
            if(t.getMetadataAt()==0) t.setType(Objects.toString(UpgradeHttp.string(subject,"platform"),"ANIMATION"));
            Campaign effective=t.getCampaignId()==null?null:repository.get("upgrade_campaigns",t.getCampaignId(),Campaign.class).orElse(null);
            if(effective==null||campaign.getPriority()>effective.getPriority()||campaign.getPriority()==effective.getPriority()&&campaign.getCreatedAt()<effective.getCreatedAt()) t.setCampaignId(campaign.getId());
            UpgradeSettings s=ConfigUtil.snapshot().getUpgrade();
            if(t.getOutputRoot()==null||t.getOutputRoot().isBlank()) t.setOutputRoot(SubjectEpisodeResolver.isMovie(t)?s.getMovieRoot():s.getTvRoot());
            repository.save("upgrade_targets",t.getId(),t.getLibraryId(),key,t.getState(),t.getRevision(),t);
            repository.save("upgrade_target_campaigns",t.getId()+":"+campaign.getId(),t.getId(),null,"INCLUDED",1,Map.of("targetId",t.getId(),"campaignId",campaign.getId()));
            String metadataKey="SUBJECT:"+t.getId(); Job metadata=repository.identity("upgrade_jobs",metadataKey,Job.class).orElseGet(Job::new);
            if(t.getMetadataAt()<System.currentTimeMillis()-604800000L) {
                if(metadata.getId()==null) metadata.setId(UUID.randomUUID().toString()); metadata.setKind("SUBJECT"); metadata.setIdentityKey(metadataKey); metadata.setTargetId(t.getId()); metadata.setState(JobState.QUEUED);
                repository.save("upgrade_jobs",metadata.getId(),t.getId(),metadataKey,"QUEUED",1,metadata);
            }
            return t;
    }
}
