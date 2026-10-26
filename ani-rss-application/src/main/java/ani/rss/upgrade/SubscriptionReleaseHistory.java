package ani.rss.upgrade;

import ani.rss.entity.*;
import ani.rss.util.other.*;
import ani.rss.upgrade.UpgradeModels.*;
import java.util.*;

/** Share the durable discovery history with quality-enabled ordinary subscriptions. */
public final class SubscriptionReleaseHistory {
    private SubscriptionReleaseHistory() {}
    public static void recordSource(Ani subscription,String rss,boolean success) {
        if(!SubscriptionQualitySelector.enabled(subscription)||!Objects.equals(rss,subscription.getUrl())) return;
        var match=java.util.regex.Pattern.compile("/subject/(\\d+)").matcher(Objects.toString(subscription.getBgmUrl(),""));
        if(!match.find()) return;
        var repository=new UpgradeRepository();
        for(Target target:repository.targetsForSubject(match.group(1))) {
            String id="subscription-rss:"+subscription.getId()+":"+target.getId();
            Cursor cursor=repository.get("upgrade_scan_cursors",id,Cursor.class).orElseGet(Cursor::new);
            cursor.setId(id); cursor.setComplete(success); cursor.setError(success?null:"SUBSCRIPTION_RSS_FETCH_FAILED");
            if(success) cursor.setLastSuccess(System.currentTimeMillis());
            repository.save("upgrade_scan_cursors",id,target.getId(),null,success?"COMPLETE":"DEFERRED",1,cursor);
        }
    }
    public static List<Item> merge(Ani subscription,String rss,List<Item> current) {
        if(!SubscriptionQualitySelector.enabled(subscription)||!Objects.equals(rss,subscription.getUrl())) return current;
        var match=java.util.regex.Pattern.compile("/subject/(\\d+)").matcher(Objects.toString(subscription.getBgmUrl(),""));
        if(!match.find()) return current;
        var repository=new UpgradeRepository(); var parser=new ReleaseAnalysisService();
        List<Target> targets=repository.targetsForSubject(match.group(1));
        Map<String,Item> result=new LinkedHashMap<>();
        for(Item item:current) result.put(item.getInfoHash(),item);
        for(Target target:targets) {
            if(target.isExcluded()) continue;
            var provider=new ReleaseProvider(repository,parser);
            for(Item item:current) {
                if(SubjectEpisodeResolver.storySeason(target)!=null) item.setExpectedSourceSeason(SubjectEpisodeResolver.storySeason(target));
                if(SubjectEpisodeResolver.seasonLabelVariant(target,parser.parse(item.getTitle(),target.getSubjectId(),"RSS_IDENTITY"))) item.setSourceSeasonLabelVerified(true);
                if(!SourceIdentityRules.titleMatches(target,item.getTitle())) continue;
                Release release=parser.parse(item.getTitle(),target.getSubjectId(),"SUBSCRIPTION_RSS");
                release.setTargetId(target.getId()); release.setSourceId(item.getInfoHash()); release.setInfoHash(ReleaseAnalysisService.canonicalHash(item.getTorrent()));
                if(release.getInfoHash()==null) continue;
                release.setTorrentUrl(item.getTorrent()); release.setExpectedBytes(Objects.requireNonNullElse(item.getLength(),0L));
                if(item.getPubDate()!=null) release.setPublishedAt(item.getPubDate().getTime());
                provider.accept(release);
            }
            List<Release> facts=repository.all("upgrade_releases",target.getId(),null,Release.class);
            SubjectEpisodeResolver.learnSeasonStart(target,facts);
            for(Release release:facts) {
                if("MERGED".equals(release.getState())||release.getInfoHash()==null||release.getTorrentUrl()==null
                        ||release.getEvidence().stream().anyMatch(e->"SOURCE_IDENTITY_REJECTED".equals(e.getSource()))
                        ||!SourceIdentityRules.bound(release)&&!SourceIdentityRules.titleMatches(target,release.getTitle())) continue;
                Release identity=UpgradeModels.snapshot(release,Release.class); SubjectEpisodeResolver.normalizeChapters(target,identity);
                if(identity.getEvidence().stream().anyMatch(e->Set.of("CHAPTER_IDENTITY_UNRESOLVED","SOURCE_SEASON_CONFLICT").contains(e.getSource()))) continue;
                Integer expectedSeason=SubjectEpisodeResolver.storySeason(target);
                if(expectedSeason==null&&release.getSeason()!=null&&!release.getSeason().equals(subscription.getSeason())) continue;
                Item item=new Item().setTitle(release.getTitle()).setTorrent(release.getTorrentUrl()).setInfoHash(release.getInfoHash())
                        .setExpectedSourceSeason(SubjectEpisodeResolver.storySeason(target))
                        .setSourceSeasonLabelVerified(SubjectEpisodeResolver.seasonLabelVariant(target,parser.parse(release.getTitle(),target.getSubjectId(),"HISTORY_IDENTITY")))
                        .setSubgroup(String.join("&",release.getGroups())).setEpisode(1D).setReName(release.getTitle())
                        .setLength(release.getExpectedBytes()).setPubDate(release.getPublishedAt()>0?new Date(release.getPublishedAt()):null);
                if(ItemsUtil.isAllowedByDownloadRules(subscription,item,ConfigUtil.snapshot())) result.putIfAbsent(item.getInfoHash(),item);
            }
        }
        return new ArrayList<>(result.values());
    }
}
