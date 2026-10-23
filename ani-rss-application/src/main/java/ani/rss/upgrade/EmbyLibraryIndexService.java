package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.persistence.DatabaseManager;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.*;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

@Service
public class EmbyLibraryIndexService {
    private final UpgradeRepository repository;
    private final LibraryPathMappingService paths;
    private final ReleaseAnalysisService analysis;
    @jakarta.annotation.Resource private AiAnalysisClient ai;
    public EmbyLibraryIndexService(UpgradeRepository repository,LibraryPathMappingService paths,ReleaseAnalysisService analysis) {
        this.repository=repository; this.paths=paths; this.analysis=analysis;
    }
    private JsonObject get(UpgradeSettings s,String path) {
        if(s.getEmbyApiKey()==null||s.getEmbyApiKey().isBlank()) throw new IllegalArgumentException("请配置 Emby API Key");
        return UpgradeHttp.get(UpgradeHttp.base(s.getEmbyUrl())+path,Map.of("X-Emby-Token",s.getEmbyApiKey()));
    }
    public JsonObject detail(String id) { return get(ConfigUtil.snapshot().getUpgrade(),"/Items/"+UpgradeHttp.query(id)); }
    public Map<String,Object> test() {
        UpgradeSettings s=ConfigUtil.snapshot().getUpgrade();
        JsonObject libraries=libraries();
        List<Map<String,Object>> samples=new ArrayList<>();
        for(String library:s.getLibraryIds()) {
            JsonObject sample=get(s,"/Items?ParentId="+UpgradeHttp.query(library)+"&Recursive=true&IncludeItemTypes=Episode,Movie&Fields=Path,MediaSources&Limit=1");
            for(JsonElement e:UpgradeHttp.array(sample,"Items")) { String remote=UpgradeHttp.string(e.getAsJsonObject(),"Path");
                try { Path mapped=paths.map(remote,s.getEmbyMappings()); paths.requireSafe(mapped,UpgradeCommitService.allowedRoot(mapped,s),true); samples.add(Map.of("libraryId",library,"remote",Objects.toString(remote,""),"local",mapped.toString(),"readable",Files.isReadable(mapped))); }
                catch(Exception error) { samples.add(Map.of("libraryId",library,"remote",Objects.toString(remote,""),"readable",false,"reason","路径未映射或不可访问")); }
            }
        }
        return Map.of("connected",true,"libraries",libraries,"pathSamples",samples);
    }
    public JsonObject libraries() { return get(ConfigUtil.snapshot().getUpgrade(),"/Items?IncludeItemTypes=CollectionFolder&Recursive=false"); }

    public boolean scanStep(String libraryId) {
        UpgradeSettings s=ConfigUtil.snapshot().getUpgrade();
        if(!s.getLibraryIds().contains(libraryId)) throw new IllegalArgumentException("媒体库未被选择");
        String cursorId="emby:"+libraryId;
        Cursor cursor=repository.get("upgrade_scan_cursors",cursorId,Cursor.class).orElseGet(Cursor::new);
        if(cursor.isComplete()) { cursor.setOffset(0); cursor.setFull(cursor.getLastFull()<System.currentTimeMillis()-86400000L); cursor.setShard(UUID.randomUUID().toString()); cursor.setComplete(false); cursor.setScanStarted(System.currentTimeMillis()); }
        if(cursor.getShard()==null) cursor.setShard(UUID.randomUUID().toString());
        cursor.setId(cursorId); cursor.setScope(libraryId);
        String url="/Items?ParentId="+UpgradeHttp.query(libraryId)+"&Recursive=true&IncludeItemTypes=Series,Season,Episode,Movie,Video&Fields=ProviderIds,Path,MediaSources,MediaStreams,ParentId,PremiereDate,OriginalTitle,DateLastMediaAdded,LockedFields&SortBy=SortName&SortOrder=Ascending&Limit=200&StartIndex="+cursor.getOffset();
        if(!cursor.isFull()&&cursor.getLastSuccess()>0) url+="&MinDateLastSaved="+UpgradeHttp.query(java.time.Instant.ofEpochMilli(cursor.getLastSuccess()-600000).toString());
        try {
        JsonObject page=get(s,url);
        if(!page.has("Items")||!page.has("TotalRecordCount")) throw new UpgradeHttp.SourceFailure("EMBY_SCHEMA_CHANGED",false);
        JsonArray items=page.getAsJsonArray("Items"); int total=page.get("TotalRecordCount").getAsInt();
        if(items.isEmpty()&&cursor.getOffset()<total) throw new UpgradeHttp.SourceFailure("EMBY_PAGE_INCOMPLETE",true);
        for(JsonElement element:items) {
            JsonObject item=element.getAsJsonObject(); String id=UpgradeHttp.string(item,"Id");
            if(id==null) throw new UpgradeHttp.SourceFailure("EMBY_ITEM_ID_MISSING",false);
            item.addProperty("upgradeLibraryId",libraryId); item.addProperty("upgradeIndexBatch",cursor.getShard());
            repository.save("emby_library_items",cursor.getShard()+":"+id,libraryId,cursor.getShard()+":"+id,"INDEXING",1,item);
        }
        cursor.setOffset(cursor.getOffset()+items.size()); cursor.setTotal(total);
        cursor.setComplete(cursor.getOffset()>=total);
        if(cursor.isComplete()) {
            // Incremental pages have their own staging batch too. A failed page
            // can never overwrite the last usable snapshot.
            List<JsonObject> previous=cursor.isFull()||cursor.getCompletedBatch()==null?List.of():batchItems(libraryId,cursor.getCompletedBatch());
            Set<String> changed=new HashSet<>();
            List<JsonObject> received=batchItems(libraryId,cursor.getShard());
            for(JsonObject item:received) changed.add(UpgradeHttp.string(item,"Id"));
            if(received.size()!=cursor.getOffset()) throw new UpgradeHttp.SourceFailure("EMBY_PAGE_DUPLICATED",true);
            DatabaseManager.transaction(connection->{
                for(JsonObject item:previous) {
                    String itemId=UpgradeHttp.string(item,"Id"); if(changed.contains(itemId)) continue;
                    item.addProperty("upgradeIndexBatch",cursor.getShard());
                    repository.save("emby_library_items",cursor.getShard()+":"+itemId,libraryId,cursor.getShard()+":"+itemId,"INDEXED",1,item);
                }
                cursor.setLastSuccess(System.currentTimeMillis());
                if(cursor.isFull()) cursor.setLastFull(System.currentTimeMillis());
                cursor.setCompletedBatch(cursor.getShard()); cursor.setError(null);
                repository.save("upgrade_scan_cursors",cursorId,libraryId,null,"COMPLETE",1,cursor);
                return null;
            });
        }
        repository.save("upgrade_scan_cursors",cursorId,libraryId,null,cursor.isComplete()?"COMPLETE":"INDEXING",1,cursor);
        return cursor.isComplete();
        } catch(RuntimeException error) {
            cursor.setComplete(false); cursor.setError(Objects.toString(error.getMessage(),"EMBY_SCAN_FAILED"));
            repository.save("upgrade_scan_cursors",cursorId,libraryId,null,"STALE",1,cursor);
            throw error;
        }
    }
    private List<JsonObject> batchItems(String libraryId,String batch) {
        return repository.all("emby_library_items",libraryId,null,JsonObject.class).stream()
                .filter(item->Objects.equals(batch,UpgradeHttp.string(item,"upgradeIndexBatch"))).toList();
    }
    public boolean fresh(String libraryId) {
        return repository.get("upgrade_scan_cursors","emby:"+libraryId,Cursor.class)
                .filter(c->c.getError()==null&&c.getLastSuccess()>System.currentTimeMillis()-86_400_000L).isPresent();
    }

    public List<Version> adopt(Target target) throws java.io.IOException {
        UpgradeSettings s=ConfigUtil.snapshot().getUpgrade();
        Cursor cursor=repository.get("upgrade_scan_cursors","emby:"+target.getLibraryId(),Cursor.class).orElse(null);
        if(cursor==null||cursor.getCompletedBatch()==null) return List.of();
        if("NEEDS_REVIEW".equals(target.getState())) target.setState("MONITORING");
        String previousSeries=target.getEmbySeriesId(); Integer previousSeason=target.getSeason(); String previousDirectory=target.getOutputDirectory();
        Map<String,JsonObject> parents=new HashMap<>();
        List<JsonObject> snapshot=batchItems(target.getLibraryId(),cursor.getCompletedBatch());
        for(JsonObject item:snapshot) parents.put(UpgradeHttp.string(item,"Id"),item);
        Map<String,Set<Integer>> seasons=new HashMap<>();
        for(JsonObject item:snapshot) {
            String series=UpgradeHttp.string(item,"SeriesId"); int number=UpgradeHttp.integer(item,"ParentIndexNumber",-1);
            if(series!=null&&number>0) seasons.computeIfAbsent(series,key->new HashSet<>()).add(number);
        }
        Set<String> observedPaths=new HashSet<>();
        List<Version> result=new ArrayList<>();
            for(JsonObject item:snapshot) {
                String type=UpgradeHttp.string(item,"Type"); if(!Set.of("Episode","Movie","Video").contains(type)) continue;
                String id=UpgradeHttp.string(item,"Id"); String series=UpgradeHttp.string(item,"SeriesId");
                JsonObject provider=item.has("ProviderIds")?item.getAsJsonObject("ProviderIds"):new JsonObject();
                boolean inheritedProvider=false;
                JsonObject parent=item;
                if(series!=null) {
                    parent=parents.computeIfAbsent(series,key->repository.get("emby_library_items",cursor.getCompletedBatch()+":"+key,JsonObject.class).orElseGet(()->detail(key)));
                    if(bangumiId(provider)==null&&parent.has("ProviderIds")) { provider=parent.getAsJsonObject("ProviderIds"); inheritedProvider=true; }
                }
                String bgm=bangumiId(provider);
                int actualSeason=UpgradeHttp.integer(item,"ParentIndexNumber",-1);
                JsonObject season=item;
                String seasonId=UpgradeHttp.string(item,"SeasonId");
                if(seasonId!=null) season=parents.computeIfAbsent(seasonId,key->detail(key));
                String seasonBgm=season==item?null:bangumiId(season.has("ProviderIds")?season.getAsJsonObject("ProviderIds"):new JsonObject());
                if(seasonBgm!=null) { bgm=seasonBgm; inheritedProvider=false; }
                boolean mapped=Objects.equals(target.getEmbySeriesId(),series) && target.getSeason()!=null
                        && target.getSeason()==actualSeason;
                boolean exactProvider=Objects.equals(target.getSubjectId(),bgm);
                boolean seasonProven=series==null||!inheritedProvider&&bgm!=null||target.getSeason()!=null&&target.getSeason()==actualSeason
                        ||samePremiere(target,UpgradeHttp.string(season,"PremiereDate"))
                        ||seasons.getOrDefault(series,Set.of()).size()==1&&SubjectEpisodeResolver.sameYear(target,UpgradeHttp.string(parent,"PremiereDate"));
                if(!mapped&&(bgm==null||exactProvider)&&seasonProven&&SubjectEpisodeResolver.exactName(target,UpgradeHttp.string(parent,"Name"))) {
                    String premiere=UpgradeHttp.string(season,"PremiereDate");
                    if(premiere==null) premiere=UpgradeHttp.string(parent,"PremiereDate");
                    if(SubjectEpisodeResolver.sameYear(target,premiere)&&(target.getSeason()==null||target.getSeason()==actualSeason)) {
                        String parentName=UpgradeHttp.string(parent,"Name");
                        long competing=repository.all("upgrade_targets",target.getLibraryId(),null,Target.class).stream().filter(t->SubjectEpisodeResolver.exactName(t,parentName)).count();
                        if(competing==1) { mapped=true; target.setEmbySeriesId(series); target.setSeason(UpgradeHttp.integer(item,"ParentIndexNumber",1)); repository.save("upgrade_targets",target.getId(),target.getLibraryId(),target.getSubjectId()+":"+target.getLibraryId(),target.getState(),target.getRevision(),target); }
                    }
                }
                if(!mapped&&bgm==null&&ai!=null&&series!=null&&target.getSeason()!=null&&target.getSeason()==UpgradeHttp.integer(item,"ParentIndexNumber",-1)&&SubjectEpisodeResolver.sameYear(target,UpgradeHttp.string(parent,"PremiereDate"))) {
                    JsonObject actual=new JsonObject(); actual.addProperty("id",series); actual.addProperty("name",UpgradeHttp.string(parent,"Name")); actual.addProperty("year",UpgradeHttp.string(parent,"PremiereDate")); actual.addProperty("season",target.getSeason());
                    try { mapped=ai.disambiguate(target,List.of(actual)).filter(series::equals).isPresent(); } catch(RuntimeException e) { repository.event(target.getId(),"EMBY_IDENTITY_AI_WAIT","已有版本匹配等待 AI 补证，其他明确版本继续处理"); }
                }
                if(!mapped&&(!exactProvider||!seasonProven)) continue;
                if(series!=null&&!mapped&&target.getSeason()!=null&&target.getSeason()!=UpgradeHttp.integer(item,"ParentIndexNumber",-1)) continue;
                if(series!=null&&target.getSeason()==null&&actualSeason>0) { target.setSeason(actualSeason); target.setEmbySeriesId(series); }
                if(!item.has("MediaSources")||item.getAsJsonArray("MediaSources").isEmpty()) item=detail(id);
                JsonArray sources=UpgradeHttp.array(item,"MediaSources");
                for(JsonElement sourceElement:sources) {
                    JsonObject source=sourceElement.getAsJsonObject(); String remote=UpgradeHttp.string(source,"Path");
                    Version v=new Version(); v.setId(target.getId()+":"+id+":"+Objects.toString(UpgradeHttp.string(source,"Id"),remote));
                    v.setTargetId(target.getId()); v.setLibraryId(target.getLibraryId()); v.setEmbyItemId(id);
                    v.setMediaSourceId(UpgradeHttp.string(source,"Id")); v.setRemotePath(remote); v.setOrigin("EMBY_ADOPTED");
                    v.setIndexAt(cursor.getLastSuccess()); v.setIndexBatch(cursor.getCompletedBatch());
                    Release attributes=analysis.parse(remote==null?"":Path.of(remote.replace('\\','/')).getFileName().toString(),target.getSubjectId(),"EMBY");
                    int episode=UpgradeHttp.integer(item,"IndexNumber",-1);
                    if("Movie".equals(type)) { attributes.getEpisodes().clear(); attributes.getEpisodes().add("MOVIE"); attributes.setChapterType("MOVIE"); }
                    else if(episode>=0) {
                        String embyKey=UpgradeHttp.integer(item,"ParentIndexNumber",1)+":"+episode;
                        String kind=actualSeason==0?"SPECIAL":"NORMAL";
                        String chapterNumber=target.getEpisodeMapping().get(embyKey);
                        if(chapterNumber==null) {
                            Optional<String> resolved=SubjectEpisodeResolver.exactChapterNumber(target,UpgradeHttp.string(item,"Name"),kind);
                            if(resolved.isEmpty()&&ai!=null&&!target.getChapterTypes().containsKey(kind+":"+episode)&&!target.getChapterIds().isEmpty()) {
                                JsonObject fact=new JsonObject(); fact.addProperty("name",UpgradeHttp.string(item,"Name")); String date=UpgradeHttp.string(item,"PremiereDate");
                                if(date!=null&&date.length()>=10) fact.addProperty("airDate",date.substring(0,10)); fact.addProperty("season",actualSeason); fact.addProperty("number",episode);
                                try { resolved=ai.chapterMapping(target,kind,fact); } catch(RuntimeException error) { repository.event(target.getId(),"CHAPTER_MAPPING_AI_WAIT","章节映射辅助暂不可用，保留已有文件"); }
                            }
                            chapterNumber=resolved.orElse(Integer.toString(episode));
                            if(resolved.isEmpty()&&!target.getChapterTypes().isEmpty()&&!target.getChapterTypes().containsKey(kind+":"+episode)) { target.setState("NEEDS_REVIEW"); repository.event(target.getId(),"CHAPTER_MAPPING_UNRESOLVED","Emby 章节不在上游章节集合中，需要核对绝对集数映射"); }
                            if(!chapterNumber.equals(Integer.toString(episode))) target.getEpisodeMapping().put(embyKey,chapterNumber);
                        }
                        attributes.getEpisodes().clear(); attributes.getEpisodes().add(chapterNumber); attributes.setChapterType(kind);
                        attributes.setSeason(target.getSeason());
                        String chapter=target.getChapterTypes().get(kind+":"+chapterNumber); if(chapter!=null) attributes.setChapterType(chapter);
                    }
                    for(JsonElement stream:UpgradeHttp.array(source,"MediaStreams")) {
                        JsonObject st=stream.getAsJsonObject();
                        if("Video".equals(UpgradeHttp.string(st,"Type"))) attributes.setResolution(UpgradeHttp.integer(st,"Height",0));
                        if("Subtitle".equals(UpgradeHttp.string(st,"Type"))) mergeSubtitle(attributes,st);
                    }
                    String container=UpgradeHttp.string(source,"Container"); if(container!=null) attributes.setContainer(container.toLowerCase(Locale.ROOT)); v.setAttributes(attributes);
                    try {
                        Path local=paths.map(remote,s.getEmbyMappings()); v.setPath(local.toString());
                        var a=Files.readAttributes(local,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                        v.setAccessible(a.isRegularFile()&&!a.isSymbolicLink()); v.setSize(a.size()); v.setModifiedAt(a.lastModifiedTime().toMillis()); v.setFileKey(Objects.toString(a.fileKey(),null));
                        var metadata=ExistingMetadataEvidence.read(local,UpgradeCommitService.allowedRoot(local,s),target.getEmbySeriesId()==null);
                        if(metadata.bangumiId()!=null&&!metadata.bangumiId().equals(target.getSubjectId())) {
                            repository.event(target.getId(),"EXISTING_NFO_PROVIDER_CONFLICT","已有文件的 NFO 作品 ID 与目标冲突，保留文件并暂停身份判断"); target.setState("NEEDS_REVIEW"); continue;
                        }
                        attributes.setAnalysisContext(metadata.text());
                        Release supplement=analysis.parse(metadata.text(),target.getSubjectId(),"EXISTING_NFO");
                        if(attributes.getGroups().isEmpty()) attributes.getGroups().addAll(supplement.getGroups());
                        if(attributes.getGroups().isEmpty()) attributes.getGroups().addAll(metadata.groups());
                        attributes.getLanguages().addAll(supplement.getLanguages());
                    } catch(Exception e) { v.setAccessible(false); repository.event(target.getId(),"EXISTING_FILE_EVIDENCE_DEFERRED","已有文件或 NFO 无法读取，保留现有版本，等待路径或元数据修复"); }
                    Version existing=repository.get("upgrade_versions",v.getId(),Version.class).orElse(null);
                    if(v.getPath()!=null) {
                        observedPaths.add(v.getPath());
                        Version installed=repository.all("upgrade_versions",target.getId(),null,Version.class).stream().filter(old->"AUTOMATIC_UPGRADE".equals(old.getOrigin())&&Objects.equals(old.getPath(),v.getPath())).findFirst().orElse(null);
                        if(installed!=null) {
                            installed.setEmbyItemId(id); installed.setMediaSourceId(v.getMediaSourceId()); installed.setRemotePath(remote);
                            installed.setIndexAt(v.getIndexAt()); installed.setIndexBatch(v.getIndexBatch());
                            repository.save("upgrade_versions",installed.getId(),target.getId(),null,installed.isActive()?"ACTIVE":"RETIRED",1,installed); result.add(installed); continue;
                        }
                    }
                    if(existing!=null) { v.setProtectedVersion(existing.isProtectedVersion()); v.setActive(existing.isActive()); }
                    if(ai!=null) { try { attributes.setId(v.getId()); ai.analyze(List.of(attributes)); } catch(RuntimeException ignored) { } }
                    repository.save("upgrade_versions",v.getId(),target.getId(),null,v.isActive()?"ACTIVE":"RETIRED",1,v); result.add(v);
                    for(JsonElement stream:UpgradeHttp.array(source,"MediaStreams")) {
                        JsonObject track=stream.getAsJsonObject(); String subtitlePath=UpgradeHttp.string(track,"Path");
                        if(!"Subtitle".equals(UpgradeHttp.string(track,"Type"))||subtitlePath==null||!track.has("IsExternal")||!track.get("IsExternal").getAsBoolean()) continue;
                        try {
                            Path local=paths.map(subtitlePath,s.getEmbyMappings()); paths.requireSafe(local,UpgradeCommitService.allowedRoot(local,s),true);
                            String subtitleId=target.getId()+":subtitle:"+cn.hutool.crypto.SecureUtil.sha256(subtitlePath);
                            Version subtitle=repository.get("upgrade_versions",subtitleId,Version.class).orElseGet(Version::new);
                            subtitle.setId(subtitleId); subtitle.setKind("SUBTITLE"); subtitle.setTargetId(target.getId()); subtitle.setLibraryId(target.getLibraryId()); subtitle.setEmbyItemId(id); subtitle.setMediaSourceId(v.getMediaSourceId());
                            subtitle.setPath(local.toString()); subtitle.setRemotePath(subtitlePath); subtitle.setOrigin("EMBY_ADOPTED"); subtitle.setIndexAt(cursor.getLastSuccess()); subtitle.setIndexBatch(cursor.getCompletedBatch());
                            subtitle.setAttributes(attributes); subtitle.getRelatedVersionIds().add(v.getId()); subtitle.setAccessible(true); subtitle.setSize(Files.size(local)); subtitle.setModifiedAt(Files.getLastModifiedTime(local).toMillis());
                            observedPaths.add(local.toString()); repository.save("upgrade_versions",subtitleId,target.getId(),null,subtitle.isActive()?"ACTIVE":"RETIRED",1,subtitle);
                        } catch(java.io.IOException|IllegalArgumentException error) { repository.event(v.getId(),"EXTERNAL_SUBTITLE_UNMAPPED","已有外挂字幕路径无法安全接管，保留该字幕文件"); }
                    }
                }
            }
        // Only the sealed snapshot can establish absence. Incremental snapshots
        // retain unchanged items, so absence is introduced only by a full scan.
        for(Version old:repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class)) {
            JsonObject indexed=parents.get(old.getEmbyItemId());
            boolean missing=indexed==null||indexed.has("MediaSources")&&UpgradeHttp.array(indexed,"MediaSources").asList().stream()
                    .map(JsonElement::getAsJsonObject).noneMatch(source->Objects.equals(old.getMediaSourceId(),UpgradeHttp.string(source,"Id"))||Objects.equals(old.getRemotePath(),UpgradeHttp.string(source,"Path")));
            if("EMBY_ADOPTED".equals(old.getOrigin())&&old.getPath()!=null&&!observedPaths.contains(old.getPath())&&missing&&old.getIndexAt()<cursor.getLastFull()) {
                old.setActive(false); repository.save("upgrade_versions",old.getId(),target.getId(),null,"RETIRED",1,old);
            }
        }
        if(target.getOutputDirectory()==null) {
            Set<Path> directories=new HashSet<>(); for(Version version:result) if(version.isActive()&&version.isAccessible()&&version.getPath()!=null) directories.add(Path.of(version.getPath()).getParent());
            if(directories.size()==1) { Path directory=directories.iterator().next(); target.setOutputDirectory(directory.toString()); target.setOutputRoot(UpgradeCommitService.allowedRoot(directory,s).toString()); }
        }
        if(!Objects.equals(previousSeries,target.getEmbySeriesId())||!Objects.equals(previousSeason,target.getSeason())||!Objects.equals(previousDirectory,target.getOutputDirectory())) target.setRevision(target.getRevision()+1);
        repository.save("upgrade_targets",target.getId(),target.getLibraryId(),target.getSubjectId()+":"+target.getLibraryId(),target.getState(),target.getRevision(),target);
        return result;
    }
    private static String bangumiId(JsonObject provider) {
        for(var field:provider.entrySet()) if(Set.of("bangumi","bgm").contains(field.getKey().toLowerCase(Locale.ROOT))&&field.getValue().isJsonPrimitive()) return field.getValue().getAsString();
        return null;
    }
    private static boolean samePremiere(Target target,String premiere) {
        return target.getPremiereDate()!=null&&target.getPremiereDate().equals(target.getPremiereUpper())&&premiere!=null&&premiere.startsWith(target.getPremiereDate());
    }
    public void validateAdopted(Version version) throws java.io.IOException {
        if(!"EMBY_ADOPTED".equals(version.getOrigin())) return;
        JsonObject item=detail(version.getEmbyItemId());
        String subject=item.has("ProviderIds")?bangumiId(item.getAsJsonObject("ProviderIds")):null;
        if(subject!=null&&!Objects.equals(subject,version.getAttributes().getSubjectId())) throw new java.io.IOException("EMBY_BASELINE_SUBJECT_CHANGED");
        Target target=repository.get("upgrade_targets",version.getTargetId(),Target.class).orElseThrow();
        int season=UpgradeHttp.integer(item,"ParentIndexNumber",-1),episode=UpgradeHttp.integer(item,"IndexNumber",-1);
        if(season>=0&&target.getSeason()!=null&&season!=target.getSeason()) throw new java.io.IOException("EMBY_BASELINE_SEASON_CHANGED");
        if(episode>=0&&!"MOVIE".equals(version.getAttributes().getChapterType())&&!version.getAttributes().getEpisodes().contains(target.getEpisodeMapping().getOrDefault(season+":"+episode,Integer.toString(episode)))) throw new java.io.IOException("EMBY_BASELINE_CHAPTER_CHANGED");
        boolean matching=false;
        for(JsonElement element:UpgradeHttp.array(item,"MediaSources")) {
            JsonObject source=element.getAsJsonObject();
            if(!Objects.equals(version.getMediaSourceId(),UpgradeHttp.string(source,"Id"))) continue;
            if("SUBTITLE".equals(version.getKind())) {
                for(JsonElement elementTrack:UpgradeHttp.array(source,"MediaStreams")) {
                    JsonObject track=elementTrack.getAsJsonObject(); if(!"Subtitle".equals(UpgradeHttp.string(track,"Type"))) continue;
                    try { if(paths.map(UpgradeHttp.string(track,"Path"),ConfigUtil.snapshot().getUpgrade().getEmbyMappings()).equals(Path.of(version.getPath()))) matching=true; } catch(RuntimeException ignored) { }
                }
            } else try { matching=paths.map(UpgradeHttp.string(source,"Path"),ConfigUtil.snapshot().getUpgrade().getEmbyMappings()).equals(Path.of(version.getPath())); } catch(RuntimeException ignored) { }
        }
        if(!matching) throw new java.io.IOException("EMBY_BASELINE_IDENTITY_CHANGED");
    }
    public static void mergeSubtitle(Release r,JsonObject st) {
        String lang=Objects.toString(UpgradeHttp.string(st,"Language"),"").toLowerCase(Locale.ROOT);
        String title=Objects.toString(UpgradeHttp.string(st,"Title"),"");
        if(Set.of("jpn","ja").contains(lang)) r.getLanguages().add("JPN");
        if(title.matches("(?is).*(简|簡|CHS).*")) r.getLanguages().add("CHS");
        if(title.matches("(?is).*(繁|CHT).*")) r.getLanguages().add("CHT");
        // Generic 'chi/zho' does not establish simplified/traditional script.
        if("zh-hans".equals(lang)) r.getLanguages().add("CHS");
        if("zh-hant".equals(lang)) r.getLanguages().add("CHT");
        if(Set.of("chi","zho","zh").contains(lang)&&!r.getLanguages().contains("CHS")&&!r.getLanguages().contains("CHT")) r.getLanguages().add("ZHO");
        if(r.getLanguages().contains("CHS")||r.getLanguages().contains("CHT")) r.getLanguages().remove("ZHO");
    }
    public void refresh(String itemId) {
        UpgradeSettings s=ConfigUtil.snapshot().getUpgrade();
        UpgradeHttp.post(UpgradeHttp.base(s.getEmbyUrl())+"/Items/"+UpgradeHttp.query(itemId)+"/Refresh?Recursive=false&MetadataRefreshMode=Default&ImageRefreshMode=Default&ReplaceAllMetadata=false&ReplaceAllImages=false",Map.of("X-Emby-Token",s.getEmbyApiKey()),null);
    }
    public String remotePath(Path local) throws java.io.IOException {
        Path normalized=local.toAbsolutePath().normalize(); UpgradeSettings.PathMapping best=null; int length=-1;
        for(var mapping:ConfigUtil.snapshot().getUpgrade().getEmbyMappings()) {
            Path root=Path.of(mapping.localRoot()).toAbsolutePath().normalize();
            if(normalized.startsWith(root)&&root.getNameCount()>length) { best=mapping; length=root.getNameCount(); }
        }
        if(best==null) throw new java.io.IOException("EMBY_OUTPUT_PATH_NOT_MAPPED");
        String suffix=Path.of(best.localRoot()).toAbsolutePath().normalize().relativize(normalized).toString().replace('\\','/');
        return LibraryPathMappingService.normalizeRemote(best.remoteRoot())+(suffix.isEmpty()?"":"/"+suffix);
    }
    public void reportChanges(List<Map<String,String>> updates) {
        if(updates.isEmpty()) return;
        var s=ConfigUtil.snapshot().getUpgrade();
        for(int offset=0;offset<updates.size();offset+=100) UpgradeHttp.post(UpgradeHttp.base(s.getEmbyUrl())+"/Library/Media/Updated",Map.of("X-Emby-Token",s.getEmbyApiKey()),Map.of("Updates",updates.subList(offset,Math.min(offset+100,updates.size()))));
    }
}
