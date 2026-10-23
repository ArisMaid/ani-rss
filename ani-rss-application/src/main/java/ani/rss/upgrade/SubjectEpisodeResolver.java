package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class SubjectEpisodeResolver {
    private final UpgradeRepository repository;
    private final SubjectCatalogProvider catalog;
    public SubjectEpisodeResolver(UpgradeRepository repository,SubjectCatalogProvider catalog) { this.repository=repository; this.catalog=catalog; }
    public boolean enrichStep(Target target) {
        String id="chapters:"+target.getId();
        Cursor cursor=repository.get("upgrade_scan_cursors",id,Cursor.class).orElseGet(Cursor::new);
        if(cursor.isComplete()&&target.getMetadataAt()>System.currentTimeMillis()-604800000L) return true;
        if(cursor.isComplete()) { cursor.setOffset(0); cursor.setComplete(false); }
        if(cursor.getOffset()==0) {
            JsonObject detail=catalog.detail(target.getSubjectId());
            if(UpgradeHttp.integer(detail,"type",-1)!=2) throw new UpgradeHttp.SourceFailure("SUBJECT_NOT_ANIMATION",false);
            applyDetail(target,detail);
            String token=ConfigUtil.snapshot().getBgmToken(); Map<String,String> headers=token==null||token.isBlank()?Map.of():Map.of("Authorization","Bearer "+token);
            JsonObject relations=UpgradeHttp.get(UpgradeHttp.base(ConfigUtil.snapshot().getBgmApi())+"/v0/subjects/"+target.getSubjectId()+"/subjects",headers);
            for(JsonElement e:UpgradeHttp.array(relations,"data")) { String sid=UpgradeHttp.string(e.getAsJsonObject(),"id"); if(sid!=null&&!target.getRelatedSubjects().contains(sid)) target.getRelatedSubjects().add(sid); }
        }
        String token=ConfigUtil.snapshot().getBgmToken(); Map<String,String> headers=token==null||token.isBlank()?Map.of():Map.of("Authorization","Bearer "+token);
        JsonObject page=UpgradeHttp.get(UpgradeHttp.base(ConfigUtil.snapshot().getBgmApi())+"/v0/episodes?subject_id="+target.getSubjectId()+"&limit=100&offset="+cursor.getOffset(),headers);
        if(!page.has("data")||!page.has("total")) throw new UpgradeHttp.SourceFailure("BANGUMI_EPISODE_SCHEMA_CHANGED",false);
        JsonArray data=page.getAsJsonArray("data"); int total=page.get("total").getAsInt();
        if(data.isEmpty()&&cursor.getOffset()<total) throw new UpgradeHttp.SourceFailure("BANGUMI_EPISODE_PAGE_INCOMPLETE",true);
        for(JsonElement e:data) {
            JsonObject chapter=e.getAsJsonObject(); String sort=UpgradeHttp.string(chapter,"sort"); if(sort==null) continue;
            int type=UpgradeHttp.integer(chapter,"type",0); String kind=switch(type) { case 0->"NORMAL"; case 1->"SPECIAL"; case 2,3,4->"EXTRA"; default->"OTHER"; };
            String number=ReleaseAnalysisService.number(sort); target.getChapterTypes().put(kind+":"+number,kind); if("NORMAL".equals(kind)) target.getChapterTypes().put(number,kind);
            String chapterId=UpgradeHttp.string(chapter,"id"); if(chapterId!=null&&chapterId.matches("\\d+")) target.getChapterIds().put(kind+":"+number,chapterId);
            String chapterName=UpgradeHttp.string(chapter,"name"); if(chapterName!=null&&!chapterName.isBlank()) target.getChapterTitles().put(kind+":"+number,chapterName);
            String airDate=UpgradeHttp.string(chapter,"airdate"); if(airDate!=null&&airDate.matches("\\d{4}-\\d{2}-\\d{2}")) target.getChapterAirDates().put(kind+":"+number,airDate);
        }
        cursor.setId(id); cursor.setScope(target.getId()); cursor.setOffset(cursor.getOffset()+data.size()); cursor.setComplete(cursor.getOffset()>=total); cursor.setTotal(total);
        if(cursor.isComplete()) { cursor.setLastSuccess(System.currentTimeMillis()); target.setMetadataAt(System.currentTimeMillis()); }
        repository.save("upgrade_scan_cursors",id,target.getId(),null,cursor.isComplete()?"COMPLETE":"SCANNING",1,cursor);
        repository.save("upgrade_targets",target.getId(),target.getLibraryId(),target.getSubjectId()+":"+target.getLibraryId(),target.getState(),target.getRevision(),target);
        return cursor.isComplete();
    }
    public static void applyDetail(Target target,JsonObject detail) {
        String date=UpgradeHttp.string(detail,"date");
        if(date!=null) {
            try { LocalDate exact=LocalDate.parse(date); target.setPremiereDate(exact.toString()); target.setPremiereUpper(exact.toString()); }
            catch(RuntimeException e) { try { YearMonth month=YearMonth.parse(date); target.setPremiereDate(month.atDay(1).toString()); target.setPremiereUpper(month.atEndOfMonth().toString()); } catch(RuntimeException ignored) { target.setPremiereDate(null); target.setPremiereUpper(null); } }
        }
        target.setPremiereSource("BANGUMI_DETAIL"); target.setOriginalTitle(UpgradeHttp.string(detail,"name"));
        if(target.getPremiereDate()!=null&&target.getPremiereDate().equals(target.getPremiereUpper())&&"WAITING_PREMIERE_EVIDENCE".equals(target.getState())) target.setState("MONITORING");
        String chinese=UpgradeHttp.string(detail,"name_cn"); target.setTitle(chinese==null||chinese.isBlank()?target.getOriginalTitle():chinese);
        target.setType(Objects.toString(UpgradeHttp.string(detail,"platform"),target.getType())); target.setSummary(UpgradeHttp.string(detail,"summary"));
        if(detail.has("images")) target.setCover(UpgradeHttp.string(detail.getAsJsonObject("images"),"large"));
        for(JsonElement e:UpgradeHttp.array(detail,"infobox")) {
            JsonObject entry=e.getAsJsonObject(); String key=UpgradeHttp.string(entry,"key"); JsonElement value=entry.get("value");
            if(value==null) continue;
            if("别名".equals(key)&&value.isJsonArray()) for(JsonElement alias:value.getAsJsonArray()) { String name=UpgradeHttp.string(alias.getAsJsonObject(),"v"); if(name!=null&&!target.getAliases().contains(name)&&target.getAliases().size()<100) target.getAliases().add(name); }
            if(Set.of("导演","脚本","原作","动画制作","音乐").contains(Objects.toString(key,""))&&value.isJsonPrimitive()) target.getCredits().put(key,value.getAsString());
        }
        if(target.getMetadataAt()==0||target.getOutputRoot()==null||target.getOutputRoot().isBlank()) { var s=ConfigUtil.snapshot().getUpgrade(); target.setOutputRoot(isMovie(target)?s.getMovieRoot():s.getTvRoot()); }
    }
    public static boolean isMovie(Target target) { return Set.of("剧场版","Movie","电影").contains(Objects.toString(target.getType(),"")); }
    public static void normalizeChapters(Target target,Release release) {
        if(isMovie(target)) { release.getEpisodes().clear(); release.getEpisodes().add("MOVIE"); release.setChapterType("MOVIE"); return; }
        boolean explicitSpecial=java.util.regex.Pattern.compile("(?i)\\bSP[ ._-]*\\d|特别篇|特別篇").matcher(Objects.toString(release.getTitle(),"")).find();
        if("SPECIAL".equals(release.getChapterType())&&!explicitSpecial&&Set.of("OVA","OAD").contains(Objects.toString(target.getType(),""))&&!release.getEpisodes().isEmpty()
                &&release.getEpisodes().stream().allMatch(e->"NORMAL".equals(target.getChapterTypes().get("NORMAL:"+e)))) release.setChapterType("NORMAL");
    }
    public static Optional<String> exactChapterNumber(Target target,String name,String kind) {
        String normalized=normalized(name); if(normalized.length()<4) return Optional.empty();
        List<String> matches=target.getChapterTitles().entrySet().stream().filter(entry->entry.getKey().startsWith(kind+":")&&normalized.equals(normalized(entry.getValue())))
                .map(entry->entry.getKey().substring(kind.length()+1)).toList();
        return matches.size()==1?Optional.of(matches.get(0)):Optional.empty();
    }
    public static String normalized(String value) { return Objects.toString(value,"").toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\p{S}]",""); }
    public static boolean exactName(Target target,String name) {
        String n=normalized(name); if(n.length()<2) return false;
        List<String> names=new ArrayList<>(target.getAliases()); names.add(target.getTitle()); names.add(target.getOriginalTitle());
        return names.stream().anyMatch(s->n.equals(normalized(s)));
    }
    public static boolean sameYear(Target target,String premiere) { return target.getPremiereDate()!=null&&premiere!=null&&premiere.length()>=4&&premiere.substring(0,4).equals(target.getPremiereDate().substring(0,4)); }
}
