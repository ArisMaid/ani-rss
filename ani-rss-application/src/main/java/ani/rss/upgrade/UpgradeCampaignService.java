package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.exception.ApiProblemException;
import ani.rss.persistence.DatabaseManager;
import ani.rss.util.other.ConfigUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

@Service
public class UpgradeCampaignService {
    private final UpgradeRepository repository;
    private final QualityPolicyEngine policies;
    private final MikanQuarterCatalog mikan;
    public UpgradeCampaignService(UpgradeRepository repository,QualityPolicyEngine policies) { this(repository,policies,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public UpgradeCampaignService(UpgradeRepository repository,QualityPolicyEngine policies,MikanQuarterCatalog mikan) { this.repository=repository; this.policies=policies; this.mikan=mikan; }
    public List<ani.rss.entity.Mikan.Season> mikanSeasons() { return mikan.seasons(); }
    public List<MikanCatalogEntry> mikanPreview(int year,String season) { return mikan.preview(year,season); }
    public synchronized QualityPolicyEngine.Policy savePolicy(String id,QualityPolicyEngine.Policy policy) {
        policies.validate(policy);
        if(id==null) { id=UUID.randomUUID().toString(); policy.setRevision(1); }
        else {
            var previous=repository.get("upgrade_policies",id,QualityPolicyEngine.Policy.class).orElseThrow(()->new IllegalArgumentException("规则不存在"));
            if(previous.getRevision()!=policy.getRevision()) UpgradeModeService.conflict(previous.getRevision());
            policy.setRevision(previous.getRevision()+1);
        }
        policy.setId(id); repository.save("upgrade_policies",id,null,null,"ACTIVE",policy.getRevision(),policy); return policy;
    }
    public synchronized void deletePolicy(String id,long expectedRevision) {
        String global=nonBlank(ConfigUtil.snapshot().getUpgrade().getDefaultPolicyId());
        DatabaseManager.transaction(connection -> {
            var policy=repository.get("upgrade_policies",id,QualityPolicyEngine.Policy.class)
                    .orElseThrow(()->new ApiProblemException(HttpStatus.NOT_FOUND,"UPGRADE_POLICY_NOT_FOUND","规则不存在或已删除",null,Map.of("policyId",id)));
            if(policy.getRevision()!=expectedRevision) UpgradeModeService.conflict(policy.getRevision());
            Map<String,Object> references=new LinkedHashMap<>();
            if(id.equals(global)) references.put("globalDefault",true);
            for(var campaign:repository.all("upgrade_campaigns",null,null,Campaign.class))
                if(id.equals(campaign.getPolicyId())) references.put("campaign:"+campaign.getId(),campaign.getName());
            for(var target:repository.all("upgrade_targets",null,null,Target.class))
                if(id.equals(target.getPolicyOverride())) references.put("target:"+target.getId(),Objects.toString(target.getTitle(),target.getId()));
            for(var job:repository.all("upgrade_jobs",null,null,Job.class))
                if(id.equals(job.getPolicyId())&&(!Set.of(JobState.COMPLETED,JobState.CANCELLED,JobState.NOT_BETTER).contains(job.getState())||"CANCEL_STOP_PENDING".equals(job.getReasonCode())))
                    references.put("job:"+job.getId(),job.getState().name());
            if(!references.isEmpty()) throw new ApiProblemException(HttpStatus.CONFLICT,"UPGRADE_POLICY_IN_USE",
                    "规则正被全局默认、日期任务、作品覆盖或未完成执行引用，请先更换对应规则后再删除",null,Map.of("policyId",id,"references",references));
            // Preserve the last definition for historical audit, but never select it again.
            policy.setRevision(policy.getRevision()+1);
            repository.save("upgrade_policies",id,null,null,"DELETED",policy.getRevision(),policy);
            repository.event(id,"POLICY_DELETED","删除未被引用的质量规则，媒体文件保持不变");
            return null;
        });
    }
    public synchronized Campaign saveCampaign(String id,Campaign campaign) {
        if(campaign==null||campaign.getName()==null||campaign.getName().isBlank()||campaign.getName().length()>120)
            throw new IllegalArgumentException("请输入日期任务名称");
        MikanQuarterCatalog.normalize(campaign);
        if("MIKAN".equals(campaign.getCatalogSource())) {
            Campaign previous=id==null?null:repository.get("upgrade_campaigns",id,Campaign.class).orElse(null);
            // Pausing or changing a policy must remain possible during a source outage.
            if(previous==null||!Objects.equals(previous.getCatalogSource(),campaign.getCatalogSource())||!Objects.equals(previous.getMikanYear(),campaign.getMikanYear())||!Objects.equals(previous.getMikanSeason(),campaign.getMikanSeason())) mikan.validateSelection(campaign);
        }
        LocalDate start=LocalDate.parse(campaign.getStartDate()), end=LocalDate.parse(campaign.getEndDate());
        if(start.isAfter(end)) throw new IllegalArgumentException("起始日期不能晚于结束日期");
        if(!ConfigUtil.snapshot().getUpgrade().getLibraryIds().contains(campaign.getLibraryId())) throw new IllegalArgumentException("请选择已配置媒体库");
        String requested=nonBlank(campaign.getPolicyId());
        String effective=requested==null?nonBlank(ConfigUtil.snapshot().getUpgrade().getDefaultPolicyId()):requested;
        if(effective==null) throw new IllegalArgumentException("请先保存全局默认规则，或选择任务质量规则");
        repository.get("upgrade_policies",effective,QualityPolicyEngine.Policy.class).orElseThrow(()->new IllegalArgumentException("质量规则不存在"));
        campaign.setPolicyId(requested);
        if(id==null) { id=UUID.randomUUID().toString(); campaign.setCreatedAt(System.currentTimeMillis()); campaign.setRevision(1); }
        else {
            Campaign previous=repository.get("upgrade_campaigns",id,Campaign.class).orElseThrow(()->new IllegalArgumentException("日期任务不存在"));
            if(previous.getRevision()!=campaign.getRevision()) UpgradeModeService.conflict(previous.getRevision());
            campaign.setCreatedAt(previous.getCreatedAt()); campaign.setRevision(previous.getRevision()+1);
        }
        campaign.setId(id); repository.save("upgrade_campaigns",id,null,null,campaign.isEnabled()?"ENABLED":"PAUSED",campaign.getRevision(),campaign);
        Cursor cursor=new Cursor(); cursor.setId("catalog:"+id); cursor.setScope(id);
        repository.save("upgrade_scan_cursors",cursor.getId(),id,null,"PENDING",1,cursor);
        if("MIKAN".equals(campaign.getCatalogSource())) {
            Cursor mikanCursor=new Cursor(); mikanCursor.setId("mikan-catalog:"+id); mikanCursor.setScope(id);
            repository.save("upgrade_scan_cursors",mikanCursor.getId(),id,null,"PENDING",1,mikanCursor);
        }
        return campaign;
    }
    public QualityPolicyEngine.Policy effectivePolicy(Target target) {
        String id=nonBlank(target.getPolicyOverride());
        Campaign selected=selectCampaign(target);
        target.setCampaignId(selected==null||target.isExcluded()?null:selected.getId());
        if(id==null&&selected!=null) id=nonBlank(selected.getPolicyId());
        if(id==null) id=nonBlank(ConfigUtil.snapshot().getUpgrade().getDefaultPolicyId());
        if(id==null) throw new IllegalArgumentException("请保存全局默认规则或日期任务规则");
        return repository.get("upgrade_policies",id,QualityPolicyEngine.Policy.class).orElseThrow();
    }
    public boolean eligible(Target target) {
        if(target.isExcluded()) return false;
        return selectCampaign(target)!=null;
    }
    private Campaign selectCampaign(Target target) {
        return selectCampaign(target,repository.all("upgrade_campaigns",null,"ENABLED",Campaign.class));
    }
    /** Recompute the displayed selection without changing stored targets or queues. */
    public void refreshCampaignSelection(List<Target> targets) {
        List<Campaign> enabled=repository.all("upgrade_campaigns",null,"ENABLED",Campaign.class);
        for(Target target:targets) {
            Campaign selected=target.isExcluded()?null:selectCampaign(target,enabled);
            target.setCampaignId(selected==null?null:selected.getId());
        }
    }
    private static String nonBlank(String value) { return value==null||value.isBlank()?null:value.trim(); }
    private Campaign selectCampaign(Target target,List<Campaign> enabled) {
        if(target.getPremiereDate()==null) return null;
        return enabled.stream()
                .filter(c->Objects.equals(c.getLibraryId(),target.getLibraryId())&&target.getPremiereDate().compareTo(c.getStartDate())>=0&&Objects.toString(target.getPremiereUpper(),target.getPremiereDate()).compareTo(c.getEndDate())<=0)
                .sorted(Comparator.comparingInt(Campaign::getPriority).reversed().thenComparingLong(Campaign::getCreatedAt).thenComparing(Campaign::getId)).findFirst().orElse(null);
    }
}
