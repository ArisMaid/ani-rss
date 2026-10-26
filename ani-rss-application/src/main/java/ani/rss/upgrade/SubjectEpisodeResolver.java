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
        release.getEvidence().removeIf(e->"CHAPTER_IDENTITY_UNRESOLVED".equals(e.getSource()));
        release.getEvidence().removeIf(e->"SOURCE_SEASON_CONFLICT".equals(e.getSource()));
        if(release.getEvidence().stream().anyMatch(e->"USER_CORRECTION".equals(e.getSource())&&Set.of("fields","episodes","season").contains(e.getField()))) return;
        Integer expectedSeason=storySeason(target);
        if(expectedSeason!=null&&release.getSeason()!=null&&!expectedSeason.equals(release.getSeason())) {
            if(seasonLabelVariant(target,release)) {
                release.getEvidence().add(new Evidence("SOURCE_SEASON_LABEL_MAPPING","season","发布采用连续总集号，章节表证明归属作品季号 "+expectedSeason));
                release.setSeason(expectedSeason);
            } else release.getEvidence().add(new Evidence("SOURCE_SEASON_CONFLICT","identity","作品季号="+expectedSeason+"；发布季号="+release.getSeason()));
        }
        if(release.getSeason()==null&&expectedSeason!=null&&SourceIdentityRules.titleMatches(target,release.getTitle())) release.setSeason(expectedSeason);
        if(isMovie(target)) { release.getEpisodes().clear(); release.getEpisodes().add("MOVIE"); release.setChapterType("MOVIE"); return; }
        boolean explicitSpecial=java.util.regex.Pattern.compile("(?i)\\bSP[ ._-]*\\d|特别篇|特別篇").matcher(Objects.toString(release.getTitle(),"")).find();
        if("SPECIAL".equals(release.getChapterType())&&!explicitSpecial&&Set.of("OVA","OAD").contains(Objects.toString(target.getType(),""))&&!release.getEpisodes().isEmpty()
                &&release.getEpisodes().stream().allMatch(e->"NORMAL".equals(target.getChapterTypes().get("NORMAL:"+e)))) release.setChapterType("NORMAL");
        if("NORMAL".equals(release.getChapterType())&&!target.getChapterTypes().isEmpty()) {
            Set<String> mapped=new TreeSet<>();
            for(String episode:release.getEpisodes()) {
                String chapter=canonicalChapter(target,release.getSeason(),episode);
                String canonicalName=SubjectEpisodeResolver.normalized(target.getTitle());
                if(release.getSeason()==null&&!knownNormal(target,chapter)&&episode.matches("\\d+")&&canonicalName.length()>=4
                        &&SubjectEpisodeResolver.normalized(release.getTitle()).contains(canonicalName)) {
                    List<Integer> known=contiguousChapters(target); int local=Integer.parseInt(episode);
                    if(!known.isEmpty()&&local>=1&&local<=known.size()) chapter=known.get(local-1).toString();
                }
                mapped.add(chapter);
            }
            if(!mapped.equals(release.getEpisodes())) {
                release.getEvidence().add(new Evidence("CHAPTER_NUMBER_MAPPING","episodes",release.getEpisodes()+" -> "+mapped));
                release.setEpisodes(mapped);
            }
            if(target.getSeasonStartChapter()!=null&&release.getEpisodes().size()>1&&Objects.equals(release.getSeason(),expectedSeason)
                    &&Objects.toString(release.getTitle(),"").matches("(?s).*(?:总第|總第).*")) {
                Set<String> scope=new TreeSet<>(); for(String chapter:release.getEpisodes()) if(knownNormal(target,chapter)) scope.add(chapter);
                if(!scope.isEmpty()&&!scope.equals(release.getEpisodes())) {
                    release.getEvidence().add(new Evidence("CHAPTER_SCOPE_FILTER","episodes","完整季度合集只安装本作品章节 "+scope)); release.setEpisodes(scope);
                }
            }
            boolean corrected=release.getEvidence().stream().anyMatch(e->"USER_CORRECTION".equals(e.getSource())&&"episodes".equals(e.getField()));
            if(!corrected&&release.getEpisodes().stream().anyMatch(e->!knownNormal(target,e)&&!target.getEpisodeMapping().containsValue(e)))
                release.getEvidence().add(new Evidence("CHAPTER_IDENTITY_UNRESOLVED","episodes","发布章节不在已知作品章节集合中，继续补充映射证据"));
        }
    }
    public static Integer inferredSeason(Target target) {
        Integer story=storySeason(target); return story==null?target.getSeason():story;
    }
    public static Integer storySeason(Target target) {
        Set<Integer> seasons=new HashSet<>();
        List<String> names=new ArrayList<>(target.getAliases()); names.add(target.getTitle()); names.add(target.getOriginalTitle());
        for(String name:names) { Integer season=new ReleaseAnalysisService().parse(name,target.getSubjectId(),"METADATA").getSeason(); if(season!=null) seasons.add(season); }
        return seasons.size()==1?seasons.iterator().next():null;
    }
    private static List<Integer> contiguousChapters(Target target) {
        List<Integer> chapters=target.getChapterTypes().entrySet().stream().filter(e->"NORMAL".equals(e.getValue()))
                .map(Map.Entry::getKey).filter(k->k.matches("(?:NORMAL:)?\\d+"))
                .map(k->Integer.valueOf(k.replace("NORMAL:",""))).distinct().sorted().toList();
        if(chapters.isEmpty()||chapters.get(0)<=1||chapters.get(chapters.size()-1)-chapters.get(0)+1!=chapters.size()) return List.of();
        return chapters;
    }
    public static String canonicalChapter(Target target,Integer season,String number) {
        String explicit=target.getEpisodeMapping().get(season+":"+number); if(explicit!=null) return explicit;
        if(knownNormal(target,number)) return number;
        if(target.getSeasonStartChapter()!=null&&season!=null&&season.equals(inferredSeason(target))&&number.matches("\\d+")) {
            String mapped=Integer.toString(Integer.parseInt(number)+target.getSeasonStartChapter()-1);
            if(knownNormal(target,mapped)) return mapped;
            return number;
        }
        List<Integer> chapters=contiguousChapters(target);
        if(!chapters.isEmpty()&&season!=null&&season.equals(inferredSeason(target))&&number.matches("\\d+")) {
            int local=Integer.parseInt(number); if(local>=1&&local<=chapters.size()) return chapters.get(local-1).toString();
        }
        return number;
    }
    private static boolean knownNormal(Target target,String chapter) {
        return target.getChapterTypes().containsKey("NORMAL:"+chapter)||"NORMAL".equals(target.getChapterTypes().get(chapter));
    }
    public static boolean seasonLabelVariant(Target target,Release release) {
        if(!"NORMAL".equals(release.getChapterType())||release.getEpisodes().isEmpty()||!release.getEpisodes().stream().allMatch(e->knownNormal(target,e))) return false;
        List<Integer> chapters=contiguousChapters(target);
        String name=normalized(target.getTitle());
        return !chapters.isEmpty()||name.length()>=4&&normalized(release.getTitle()).contains(name);
    }
    public static String displayChapter(Target target,Integer season,String number) {
        List<String> explicit=target.getEpisodeMapping().entrySet().stream().filter(e->e.getValue().equals(number)&&e.getKey().startsWith(season+":"))
                .map(e->e.getKey().substring(e.getKey().indexOf(':')+1)).toList();
        if(explicit.size()==1) return explicit.get(0);
        if(target.getSeasonStartChapter()!=null&&season!=null&&season.equals(inferredSeason(target))&&number.matches("\\d+")&&knownNormal(target,number))
            return Integer.toString(Integer.parseInt(number)-target.getSeasonStartChapter()+1);
        List<Integer> chapters=contiguousChapters(target);
        if(!chapters.isEmpty()&&season!=null&&season.equals(inferredSeason(target))&&number.matches("\\d+")&&chapters.contains(Integer.valueOf(number)))
            return Integer.toString(Integer.parseInt(number)-chapters.get(0)+1);
        return number;
    }
    /** A declared complete season's absolute range anchors split-cour local numbering. */
    public static void learnSeasonStart(Target target,List<Release> facts) {
        Integer season=inferredSeason(target); if(season==null) return;
        List<Integer> known=contiguousChapters(target); if(known.isEmpty()) return;
        Map<Integer,String> anchors=new LinkedHashMap<>();
        for(Release fact:facts) {
            Release declared=new ReleaseAnalysisService().parse(fact.getTitle(),target.getSubjectId(),"SEASON_RANGE");
            if(!season.equals(declared.getSeason())||!Objects.toString(fact.getTitle(),"").matches("(?s).*(?:总第|總第).*")) continue;
            if(!Objects.toString(fact.getTitle(),"").matches("(?s).*(?:合集|全[集（( ]|全集).*")) continue;
            List<Integer> chapters=declared.getEpisodes().stream().filter(e->e.matches("\\d+")).map(Integer::valueOf).sorted().toList();
            if(chapters.size()<known.size()||chapters.isEmpty()||chapters.get(chapters.size()-1)-chapters.get(0)+1!=chapters.size()||!chapters.containsAll(known)) continue;
            anchors.putIfAbsent(chapters.get(0),fact.getTitle());
        }
        if(anchors.size()==1) { var anchor=anchors.entrySet().iterator().next(); target.setSeasonStartChapter(anchor.getKey()); target.setSeasonStartEvidence(anchor.getValue()); }
        else if(anchors.size()>1) target.setState("NEEDS_REVIEW");
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
