package ani.rss.upgrade;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import java.util.*;

/** Apply the same editable quality ordering before ordinary RSS episode deduplication. */
public final class SubscriptionQualitySelector {
    public static final String COLLECTION_TAG="ani-rss-collection";
    public static final String CONTAINER_CHECK_TAG="ani-rss-container-check";
    public static final String PREPARED_TAG="ani-rss-files-prepared";
    public enum FileSelection { SELECT, SKIP, UNKNOWN }
    private SubscriptionQualitySelector() {}
    public static boolean enabled(Ani ani) { return ani.getQualityPolicyId()!=null&&!ani.getQualityPolicyId().isBlank(); }
    public static Set<String> containers(Ani ani) {
        return enabled(ani)?policy(ani).getContainers():Set.of();
    }
    private static QualityPolicyEngine.Policy policy(Ani ani) {
        return new UpgradeRepository().get("upgrade_policies",ani.getQualityPolicyId(),QualityPolicyEngine.Policy.class)
                .orElseThrow(()->new IllegalArgumentException("订阅引用的质量规则不存在，请重新选择规则"));
    }
    public static boolean permitsFiles(Set<String> allowed,Collection<String> filenames) {
        if(allowed.isEmpty()) return true;
        boolean found=false;
        for(String name:filenames) {
            String ext=cn.hutool.core.io.FileUtil.extName(name).toLowerCase(Locale.ROOT);
            if(allowed.contains(ext)) found=true;
            else if(Set.of("mkv","mp4","avi","webm","ts","m2ts","mov","wmv","mpg","mpeg","m4v","iso","flv").contains(ext)) return false;
        }
        return found;
    }
    public static List<Item> select(Ani ani,List<Item> items) {
        if(!enabled(ani)) return items;
        return select(ani,items,policy(ani));
    }
    static List<Item> select(Ani ani,List<Item> items,QualityPolicyEngine.Policy policy) {
        var engine=new QualityPolicyEngine(); var parser=new ReleaseAnalysisService();
        Set<Integer> namedSeasons=new HashSet<>();
        for(String name:List.of(Objects.toString(ani.getTitle(),""),Objects.toString(ani.getJpTitle(),""),Objects.toString(ani.getMikanTitle(),""))) {
            Integer season=parser.parse(name,ani.getBgmUrl(),"SUBSCRIPTION_METADATA").getSeason(); if(season!=null) namedSeasons.add(season);
        }
        Map<String,Item> best=new TreeMap<>(); Map<String,UpgradeModels.Release> attributes=new HashMap<>();
        Map<Item,UpgradeModels.Release> parsed=new IdentityHashMap<>();
        for(Item item:items) parsed.put(item,parser.parse(item.getTitle(),Objects.toString(ani.getBgmUrl(),ani.getId()),"SUBSCRIPTION_RSS"));
        List<Item> ordered=new ArrayList<>(items);
        ordered.sort(Comparator.comparing((Item item)->parsed.get(item).getGroups().isEmpty())
                .thenComparing(Comparator.comparingInt((Item item)->parsed.get(item).getEpisodes().size()).reversed())
                .thenComparing(item->Objects.toString(item.getInfoHash(),item.getTitle())));
        for(Item item:ordered) {
            if(item.getEpisode()==null) continue;
            String chapter=ReleaseAnalysisService.number(item.getEpisode().toString());
            var release=parsed.get(item);
            Integer expectedSeason=item.getExpectedSourceSeason()!=null?item.getExpectedSourceSeason():namedSeasons.size()==1?namedSeasons.iterator().next():null;
            if(release.getSeason()!=null&&expectedSeason!=null&&!release.getSeason().equals(expectedSeason)&&!Boolean.TRUE.equals(item.getSourceSeasonLabelVerified())) continue;
            if(!Boolean.TRUE.equals(ani.getOva())&&!"NORMAL".equals(release.getChapterType())) continue;
            if(release.getEpisodes().size()>1&&!Boolean.TRUE.equals(ani.getCustomEpisode())) {
                Set<String> coverage=new TreeSet<>();
                for(String e:release.getEpisodes()) coverage.add(ReleaseAnalysisService.number(new java.math.BigDecimal(e).add(java.math.BigDecimal.valueOf(sourceOffset(ani,item))).toPlainString()));
                release.setEpisodes(coverage);
            } else if(!Boolean.TRUE.equals(ani.getOva())||release.getEpisodes().isEmpty()) release.setEpisodes(new TreeSet<>(Set.of(chapter)));
            if(Boolean.TRUE.equals(ani.getOva())&&!release.getEpisodes().isEmpty()) item.setEpisode(Double.valueOf(release.getEpisodes().stream().min(Comparator.comparing(java.math.BigDecimal::new)).orElse(chapter)));
            release.setSeason(ani.getSeason());
            if(engine.discover(policy,release,null,null).verdict()!=QualityPolicyEngine.Verdict.UPGRADE) continue;
            if(!release.getGroups().isEmpty()) item.setSubgroup(String.join("&",release.getGroups()));
            for(String covered:release.getEpisodes()) {
                String key=release.getChapterType()+":"+release.getContentEdition()+":"+covered;
                var previous=attributes.get(key);
                if(previous!=null&&engine.discover(policy,release,previous,covered).verdict()!=QualityPolicyEngine.Verdict.UPGRADE) continue;
                best.put(key,item); attributes.put(key,release);
            }
        }
        List<Item> selected=new ArrayList<>(new LinkedHashSet<>(best.values()));
        for(Item item:selected) {
            Set<String> chapters=new TreeSet<>();
            for(var entry:best.entrySet()) if(entry.getValue()==item) chapters.add(entry.getKey().substring(entry.getKey().lastIndexOf(':')+1));
            item.setSelectedEpisodes(chapters);
        }
        return selected;
    }
    public static boolean preserveBatchFilenames(Ani ani,String taskName,List<String> filenames) {
        if(!enabled(ani)) return false;
        var parser=new ReleaseAnalysisService();
        if(parser.parse(taskName,"batch","TASK").getEpisodes().size()>1) return true;
        return filenames.stream().filter(name->name.matches("(?i).*\\.(mkv|mp4|avi|webm|ts)$"))
                .flatMap(name->parser.parse(name,"batch","FILE").getEpisodes().stream()).distinct().limit(2).count()>1;
    }
    public static Set<String> coveredEpisodes(Ani ani,Item item) {
        if(item.getSelectedEpisodes()!=null&&!item.getSelectedEpisodes().isEmpty()) return Set.copyOf(item.getSelectedEpisodes());
        var parsed=new ReleaseAnalysisService().parse(item.getTitle(),ani.getBgmUrl(),"SUBSCRIPTION_RSS");
        if(!Boolean.TRUE.equals(ani.getCustomEpisode())&&parsed.getEpisodes().size()>1) {
            Set<String> result=new TreeSet<>();
            for(String chapter:parsed.getEpisodes()) result.add(ReleaseAnalysisService.number(new java.math.BigDecimal(chapter).add(java.math.BigDecimal.valueOf(sourceOffset(ani,item))).toPlainString()));
            return result;
        }
        return item.getEpisode()==null?Set.of():Set.of(ReleaseAnalysisService.number(item.getEpisode().toString()));
    }
    public static int sourceOffset(Ani ani,Item item) {
        return item.getSourceOffset()!=null?item.getSourceOffset():Objects.requireNonNullElse(ani.getOffset(),0);
    }
    public static String selectionTag(Item item) {
        return item.getSelectedEpisodes()==null||item.getSelectedEpisodes().isEmpty()?null:"ani-rss-chapters="+String.join("|",item.getSelectedEpisodes());
    }
    public static Set<String> selectedChapters(List<String> tags) {
        if(tags==null) return Set.of();
        return tags.stream().filter(tag->tag.startsWith("ani-rss-chapters="))
                .flatMap(tag->Arrays.stream(tag.substring("ani-rss-chapters=".length()).split("\\|")))
                .filter(e->e.matches("\\d+(?:\\.\\d+)?")).collect(java.util.stream.Collectors.toSet());
    }
    public static int taskOffset(Ani ani,List<String> tags) {
        for(String tag:tags) if(tag.startsWith("ani-rss-offset=")) {
            try { return Integer.parseInt(tag.substring("ani-rss-offset=".length())); }
            catch(NumberFormatException ignored) { }
        }
        return Objects.requireNonNullElse(ani.getOffset(),0);
    }
    /** Unclassified attachments are preserved; unclassified videos need chapter evidence. */
    public static FileSelection selectedFile(Ani ani,String filename,Set<String> selected,int offset) {
        if(selected.isEmpty()) return FileSelection.UNKNOWN;
        var parsed=new ReleaseAnalysisService().parse(filename,ani.getBgmUrl(),"FILE_SELECTION");
        if(!Boolean.TRUE.equals(ani.getOva())&&Set.of("SPECIAL","EXTRA").contains(parsed.getChapterType())) return FileSelection.SKIP;
        if(parsed.getEpisodes().isEmpty()) return FileSelection.UNKNOWN;
        Set<String> episodes=new TreeSet<>();
        for(String e:parsed.getEpisodes()) episodes.add(ReleaseAnalysisService.number(new java.math.BigDecimal(e).add(java.math.BigDecimal.valueOf(offset)).toPlainString()));
        if(Collections.disjoint(episodes,selected)) return FileSelection.SKIP;
        if(!selected.containsAll(episodes)) throw new IllegalArgumentException("BATCH_FILE_PARTIAL_CHAPTER_OVERLAP");
        return FileSelection.SELECT;
    }
    public static boolean alreadyCoveredBatch(Ani ani,Item item,List<TorrentsInfo> tasks,java.util.function.Predicate<TorrentsInfo> owned) {
        if(!enabled(ani)) return false;
        Set<String> required=coveredEpisodes(ani,item); if(required.size()<2) return false;
        var parser=new ReleaseAnalysisService(); var declared=parser.parse(item.getTitle(),ani.getBgmUrl(),"BATCH"); Set<String> present=new HashSet<>();
        String hash=ReleaseAnalysisService.canonicalHash(item.getTorrent()); if(hash==null) hash=Objects.toString(item.getInfoHash(),"");
        for(TorrentsInfo task:tasks) {
            if(task.getHash()==null||task.getHash().equalsIgnoreCase(hash)||!owned.test(task)) continue;
            var actual=parser.parse(task.getName(),ani.getBgmUrl(),"OWNED_TASK");
            if(!Objects.equals(actual.getSeason(),ani.getSeason())||!Objects.equals(actual.getChapterType(),declared.getChapterType())||!Objects.equals(actual.getContentEdition(),declared.getContentEdition())) continue;
            Set<String> selected=selectedChapters(task.getTagList());
            present.addAll(selected.isEmpty()?actual.getEpisodes():selected);
        }
        return present.containsAll(required);
    }
    public static Set<String> missingBatchChapters(Ani ani,Item item,List<TorrentsInfo> tasks,
            java.util.function.Predicate<TorrentsInfo> owned,Map<String,Item> previousItems,QualityPolicyEngine.Policy policy) {
        Set<String> missing=new TreeSet<>(coveredEpisodes(ani,item));
        if(new ReleaseAnalysisService().parse(item.getTitle(),ani.getBgmUrl(),"RSS").getEpisodes().size()<2) return missing;
        var engine=new QualityPolicyEngine(); var parser=new ReleaseAnalysisService();
        var candidate=parser.parse(item.getTitle(),ani.getBgmUrl(),"RSS"); Integer declaredSeason=candidate.getSeason(); candidate.setSeason(ani.getSeason());
        String candidateHash=ReleaseAnalysisService.canonicalHash(item.getTorrent());
        if(candidateHash==null) candidateHash=item.getInfoHash();
        for(var task:tasks) {
            if(Objects.equals(candidateHash,task.getHash())||!owned.test(task)||task.getState()==null
                    ||Set.of("error","missingFiles").contains(task.getState().name())) continue;
            Item previous=previousItems.get(task.getHash()); if(previous==null) continue;
            var prior=parser.parse(previous.getTitle(),ani.getBgmUrl(),"OWNED_RSS");
            if(declaredSeason!=null&&prior.getSeason()!=null&&!declaredSeason.equals(prior.getSeason())) continue;
            prior.setSeason(ani.getSeason());
            if(!Objects.equals(candidate.getChapterType(),prior.getChapterType())||!Objects.equals(candidate.getContentEdition(),prior.getContentEdition())) continue;
            Set<String> selected=selectedChapters(task.getTagList());
            Set<String> coverage=selected.isEmpty()?coveredEpisodes(ani,previous):selected;
            candidate.setEpisodes(coveredEpisodes(ani,item)); prior.setEpisodes(coverage);
            for(String chapter:new HashSet<>(missing)) if(coverage.contains(chapter)) {
                if(engine.discover(policy,candidate,prior,chapter).verdict()==QualityPolicyEngine.Verdict.NOT_BETTER) missing.remove(chapter);
            }
        }
        return missing;
    }
}
