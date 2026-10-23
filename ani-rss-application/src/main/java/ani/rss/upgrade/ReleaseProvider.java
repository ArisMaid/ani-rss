package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ItemsUtil;
import com.google.gson.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ReleaseProvider {
    private final UpgradeRepository repository;
    private final ReleaseAnalysisService analysis;
    @jakarta.annotation.Resource private ani.rss.service.MikanService mikan;
    @jakarta.annotation.Resource private AiAnalysisClient ai;
    public ReleaseProvider(UpgradeRepository repository,ReleaseAnalysisService analysis) { this.repository=repository; this.analysis=analysis; }
    public boolean scanStep(Target target) {
        String id="garden:"+target.getId();
        Cursor cursor=repository.get("upgrade_scan_cursors",id,Cursor.class).orElseGet(Cursor::new);
        if(cursor.isComplete()) { cursor.setOffset(0); cursor.setComplete(false); cursor.setAliasIndex(-1); cursor.getQueryAliases().clear(); cursor.setFull(cursor.getLastFull()<System.currentTimeMillis()-86400000L); cursor.setScanStarted(System.currentTimeMillis()); }
        if(cursor.getScanStarted()==0) cursor.setScanStarted(System.currentTimeMillis());
        int pageNumber=cursor.getOffset()+1;
        String url="https://api.animes.garden/resources?pageSize=100&duplicate=false&page="+pageNumber;
        if(!cursor.isFull()&&cursor.getWatermark()>0) url+="&after="+UpgradeHttp.query(java.time.Instant.ofEpochMilli(cursor.getWatermark()-172800000L).toString());
        List<String> aliases=new ArrayList<>(); for(String name:List.of(Objects.toString(target.getTitle(),""),Objects.toString(target.getOriginalTitle(),""))) if(!name.isBlank()&&!aliases.contains(name)) aliases.add(name);
        for(String name:target.getAliases()) if(!name.isBlank()&&!aliases.contains(name)&&aliases.size()<8) aliases.add(name);
        if(cursor.getAliasIndex()>=0&&!cursor.getQueryAliases().isEmpty()) aliases=new ArrayList<>(cursor.getQueryAliases());
        else if(cursor.getAliasIndex()>=0&&ai!=null) {
            try { for(String name:ai.aliases(target)) if(!aliases.contains(name)&&aliases.size()<8) aliases.add(name); }
            catch(RuntimeException error) { repository.event(target.getId(),"AI_ALIAS_SEARCH_DEFERRED","模型别名建议暂不可用，继续已验证名称的检索"); }
        }
        if(cursor.getAliasIndex()>=0&&cursor.getQueryAliases().isEmpty()) cursor.setQueryAliases(new ArrayList<>(aliases));
        JsonObject response;
        if(cursor.getAliasIndex()<0) response=UpgradeHttp.get(url+"&subject="+target.getSubjectId(),Map.of());
        else if(cursor.getAliasIndex()<aliases.size()) response=UpgradeHttp.execute(ani.rss.util.basic.HttpReq.get(url),Map.of(),Map.of("include",List.of(aliases.get(cursor.getAliasIndex()))));
        else { cursor.setComplete(true); response=new JsonObject(); response.add("resources",new JsonArray()); }
        if(!response.has("resources")) throw new UpgradeHttp.SourceFailure("GARDEN_SCHEMA_CHANGED",false);
        JsonArray resources=response.getAsJsonArray("resources");
        Boolean pageComplete=null;
        if(response.has("pagination")) {
            if(!response.get("pagination").isJsonObject()||!response.getAsJsonObject("pagination").has("complete")) throw new UpgradeHttp.SourceFailure("GARDEN_PAGINATION_SCHEMA_CHANGED",false);
            pageComplete=response.getAsJsonObject("pagination").get("complete").getAsBoolean();
            if(!pageComplete&&resources.isEmpty()) throw new UpgradeHttp.SourceFailure("GARDEN_PAGE_INCOMPLETE",true);
        }
        for(JsonElement element:resources) {
            JsonObject item=element.getAsJsonObject();
            String title=UpgradeHttp.string(item,"title"); if(title==null) throw new UpgradeHttp.SourceFailure("GARDEN_RESOURCE_TITLE_MISSING",false);
            Release r=analysis.parse(title,target.getSubjectId(),"ANIMEGARDEN"); r.setTargetId(target.getId());
            String boundSubject=UpgradeHttp.string(item,"subjectId");
            if(boundSubject!=null&&!target.getSubjectId().equals(boundSubject)) continue;
            if(cursor.getAliasIndex()>=0&&!target.getSubjectId().equals(boundSubject)&&!verifiedTitle(target,title)) continue;
            r.setSourceId(UpgradeHttp.string(item,"id"));
            r.setProvider(UpgradeHttp.string(item,"provider")); r.setProviderId(UpgradeHttp.string(item,"providerId")); r.setPublisher(item.has("publisher")&&item.get("publisher").isJsonObject()?UpgradeHttp.string(item.getAsJsonObject("publisher"),"name"):UpgradeHttp.string(item,"publisher"));
            String published=UpgradeHttp.string(item,"createdAt"); try { r.setPublishedAt(java.time.Instant.parse(published).toEpochMilli()); } catch(RuntimeException ignored) { }
            if(item.has("size")&&!item.get("size").isJsonNull()) r.setExpectedBytes(item.get("size").getAsLong());
            if(r.getSourceId()==null) r.setSourceId(Objects.toString(UpgradeHttp.string(item,"provider"),"")+":"+UpgradeHttp.string(item,"providerId"));
            r.setInfoHash(ReleaseAnalysisService.canonicalHash(UpgradeHttp.string(item,"infoHash")));
            r.setTorrentUrl(UpgradeHttp.string(item,"magnet"));
            if(r.getTorrentUrl()==null) r.setTorrentUrl(UpgradeHttp.string(item,"downloadUrl"));
            if(r.getTorrentUrl()==null) r.setTorrentUrl(UpgradeHttp.string(item,"magnetUrl"));
            if(r.getInfoHash()==null) r.setInfoHash(ReleaseAnalysisService.canonicalHash(r.getTorrentUrl()));
            if(item.has("fansub")&&item.get("fansub").isJsonObject()) {
                String name=UpgradeHttp.string(item.getAsJsonObject("fansub"),"name");
                if(name!=null) { r.getGroups().clear(); r.getGroups().add(name); r.getEvidence().add(new Evidence("ANIMEGARDEN_FANSUB","groups",name)); }
            }
            accept(r);
        }
        cursor.setId(id); cursor.setScope(target.getId()); cursor.setOffset(pageNumber);
        if(pageComplete==null?resources.size()<100:pageComplete) {
            if(cursor.getAliasIndex()<0&&pageNumber==1&&resources.isEmpty()&&!aliases.isEmpty()) { cursor.setAliasIndex(0); cursor.setOffset(0); }
            else if(cursor.getAliasIndex()>=0&&cursor.getAliasIndex()+1<aliases.size()) { cursor.setAliasIndex(cursor.getAliasIndex()+1); cursor.setOffset(0); }
            else { cursor.setComplete(true); cursor.setLastSuccess(System.currentTimeMillis()); cursor.setWatermark(cursor.getScanStarted()); if(cursor.isFull()) cursor.setLastFull(System.currentTimeMillis()); }
        }
        repository.save("upgrade_scan_cursors",id,target.getId(),null,cursor.isComplete()?"COMPLETE":"SCANNING",1,cursor);
        return cursor.isComplete();
    }
    public void enrich(Release release) {
        if(!"ANIMEGARDEN".equals(release.getSource())||release.getProvider()==null||!release.getProvider().matches("[a-z0-9-]{1,30}")
                ||release.getProviderId()==null||!release.getProviderId().matches("[A-Za-z0-9_-]{1,100}")) return;
        JsonObject detail=UpgradeHttp.get("https://api.animes.garden/detail/"+release.getProvider()+"/"+release.getProviderId(),Map.of());
        if(detail.has("detail")&&detail.get("detail").isJsonObject()) detail=detail.getAsJsonObject("detail");
        if(!detail.has("files")&&!detail.has("description")) throw new UpgradeHttp.SourceFailure("GARDEN_DETAIL_SCHEMA_CHANGED",false);
        String description=org.jsoup.Jsoup.parse(Objects.toString(UpgradeHttp.string(detail,"description"),"")).text();
        description=description.substring(0,Math.min(8000,description.length()));
        List<String> filenames=new ArrayList<>(); Set<String> chapters=new TreeSet<>();
        for(JsonElement value:UpgradeHttp.array(detail,"files")) {
            if(!value.isJsonObject()||filenames.size()>=500) continue;
            String name=UpgradeHttp.string(value.getAsJsonObject(),"name"); if(name==null||name.length()>1000||name.matches("(?s).*[\\p{Cntrl}].*")) continue;
            String basename=name.replace('\\','/'); basename=basename.substring(basename.lastIndexOf('/')+1); filenames.add(basename);
            Release parsed=analysis.parse(basename,release.getSubjectId(),"RELEASE_FILE_LIST");
            if(basename.matches("(?i).*\\.(mkv|mp4|avi|webm|ts)$")&&Objects.equals(parsed.getChapterType(),release.getChapterType())
                    &&(parsed.getSeason()==null||release.getSeason()==null||parsed.getSeason().equals(release.getSeason()))) chapters.addAll(parsed.getEpisodes());
        }
        release.setFiles(filenames); release.setAnalysisContext((description+"\n"+String.join("\n",filenames)).substring(0,Math.min(12000,description.length()+1+String.join("\n",filenames).length())));
        if(release.getEpisodes().isEmpty()&&!chapters.isEmpty()&&chapters.size()<=500) release.setEpisodes(chapters);
        if(!description.isBlank()) release.getEvidence().add(new Evidence("ANIMEGARDEN_DETAIL","description",description));
        release.setDetailAt(System.currentTimeMillis()); accept(release);
    }
    public void rss(Target target) {
        Set<String> feeds=new LinkedHashSet<>(target.getRssUrls());
        // Existing subscription / AniBT links are source hints, never ownership.
        for(var subscription:ani.rss.util.other.AniUtil.ANI_LIST) if(Objects.toString(subscription.getBgmUrl(),"").matches(".*/subject/"+target.getSubjectId()+"(?:[/?#].*)?")) if(subscription.getUrl()!=null&&!subscription.getUrl().isBlank()) feeds.add(subscription.getUrl());
        Cursor mikanCursor=repository.get("upgrade_scan_cursors","mikan:"+target.getId(),Cursor.class).orElseGet(Cursor::new);
        feeds.addAll(mikanCursor.getDiscoveredFeeds());
        if(mikan!=null&&mikanCursor.getLastSuccess()<System.currentTimeMillis()-86400000L) {
            try {
                var matches=mikan.search(target.getTitle(),null);
                if(matches.getWeeks()!=null) for(var week:matches.getWeeks()) if(week.getItems()!=null) for(var item:week.getItems().stream().limit(8).toList()) {
                    if(!SubjectEpisodeResolver.exactName(target,item.getTitle())) continue;
                    var match=java.util.regex.Pattern.compile("/Home/Bangumi/(\\d+)").matcher(Objects.toString(item.getUrl(),"")); if(!match.find()) continue; String mid=match.group(1);
                    var detail=ani.rss.service.MikanService.getMikanInfo(mid);
                    if(!Objects.toString(detail.getBgmUrl(),"").matches(".*/subject/"+target.getSubjectId()+"(?:[/?#].*)?")) continue;
                    if(detail.getGroups()!=null) detail.getGroups().forEach(group->{ if(group.getRss()!=null) { feeds.add(group.getRss()); if(!mikanCursor.getDiscoveredFeeds().contains(group.getRss())) mikanCursor.getDiscoveredFeeds().add(group.getRss()); } });
                }
                mikanCursor.setId("mikan:"+target.getId()); mikanCursor.setScope(target.getId()); mikanCursor.setLastSuccess(System.currentTimeMillis()); mikanCursor.setComplete(true);
                repository.save("upgrade_scan_cursors",mikanCursor.getId(),target.getId(),null,"COMPLETE",1,mikanCursor);
            } catch(RuntimeException e) { repository.event(target.getId(),"MIKAN_SOURCE_DEFERRED","蜜柑补充源暂时不可用，保留 AnimeGarden 和自定义 RSS 的处理结果"); }
        }
        for(String url:feeds) {
            // Each feed is parsed without the subscription episode de-duplication.
            try { for(var raw:ItemsUtil.getItemsRaw(url)) {
                Release r=analysis.parse(raw.title(),target.getSubjectId(),"RSS"); r.setTargetId(target.getId());
                r.setSourceId(raw.id()); r.setTorrentUrl(raw.enclosure()); r.setInfoHash(ReleaseAnalysisService.canonicalHash(raw.enclosure()));
                accept(r);
            } } catch(RuntimeException e) { repository.event(target.getId(),"RSS_SOURCE_DEFERRED","一个补充 RSS 获取失败，其他源继续检索"); }
        }
    }
    public static boolean verifiedTitle(Target target,String title) {
        String normalized=SubjectEpisodeResolver.normalized(title); List<String> names=new ArrayList<>(target.getAliases()); names.add(target.getTitle()); names.add(target.getOriginalTitle());
        return names.stream().filter(Objects::nonNull).map(SubjectEpisodeResolver::normalized).anyMatch(n->n.length()>=4&&normalized.contains(n));
    }
    public Release accept(Release r) {
        String key=r.getTargetId()+":"+(r.getInfoHash()==null?r.getSource()+":"+r.getSourceId():r.getInfoHash());
        Release existing=repository.identity("upgrade_releases",key,Release.class).orElse(null);
        String sourceKey=r.getTargetId()+":"+r.getSource()+":"+r.getSourceId();
        JsonObject binding=repository.identity("upgrade_release_sources",sourceKey,JsonObject.class).orElse(null);
        Release provisional=binding==null?null:repository.get("upgrade_releases",UpgradeHttp.string(binding,"releaseId"),Release.class).orElse(null);
        if(provisional!=null&&provisional.getInfoHash()!=null&&r.getInfoHash()!=null&&!provisional.getInfoHash().equals(r.getInfoHash())) provisional=null;
        if(existing==null&&provisional!=null) { existing=provisional; if(r.getInfoHash()!=null) existing.setInfoHash(r.getInfoHash()); if(r.getTorrentUrl()!=null) existing.setTorrentUrl(r.getTorrentUrl()); }
        String source=r.getSource(),sourceId=r.getSourceId();
        if(existing!=null&&!Objects.equals(existing.getId(),r.getId())) {
            for(Evidence evidence:r.getEvidence()) if(!existing.getEvidence().contains(evidence)) existing.getEvidence().add(evidence);
            if(existing.getTorrentUrl()==null) existing.setTorrentUrl(r.getTorrentUrl());
            if(existing.getProviderId()==null&&Objects.equals(existing.getSource(),r.getSource())) { existing.setProvider(r.getProvider()); existing.setProviderId(r.getProviderId()); }
            r=existing;
        } else if(r.getId()==null) r.setId(UUID.randomUUID().toString());
        if(provisional!=null&&!Objects.equals(provisional.getId(),r.getId())) {
            for(Evidence evidence:provisional.getEvidence()) if(!r.getEvidence().contains(evidence)) r.getEvidence().add(evidence);
            provisional.setState("MERGED"); provisional.setReason("资源身份已归并至 "+r.getId());
            repository.save("upgrade_releases",provisional.getId(),provisional.getTargetId(),null,"MERGED",provisional.getRevision(),provisional);
        }
        key=r.getTargetId()+":"+(r.getInfoHash()==null?r.getSource()+":"+r.getSourceId():r.getInfoHash());
        repository.save("upgrade_releases",r.getId(),r.getTargetId(),key,r.getState(),r.getRevision(),r);
        repository.save("upgrade_release_sources",sourceKey,r.getId(),sourceKey,"FOUND",1,Map.of("releaseId",r.getId(),"source",source,"id",Objects.toString(sourceId,""),"publishedAt",r.getPublishedAt(),"publisher",Objects.toString(r.getPublisher(),""),"provider",Objects.toString(r.getProvider(),"")));
        return r;
    }
}
