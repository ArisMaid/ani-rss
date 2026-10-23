package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.Release;
import lombok.Data;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class QualityPolicyEngine {
    public enum Dimension { SUBTITLES, GROUP, RESOLUTION, SOURCE, PACKAGING, CODEC, CORRECTION }
    public enum Verdict { UPGRADE, NOT_BETTER, REJECTED, WAITING_EVIDENCE, IDENTITY_CONFLICT }
    @Data public static class Policy {
        private String id;
        private String name = "默认质量规则";
        private long revision = 1;
        private List<Dimension> dimensions = new ArrayList<>(List.of(Dimension.SUBTITLES,Dimension.GROUP,Dimension.RESOLUTION));
        private List<String> subtitles = new ArrayList<>(List.of("CHS+CHT+JPN","CHS+JPN","CHS+CHT","CHS","CHT+JPN","CHT"));
        private List<String> groups = new ArrayList<>();
        private Map<String,String> groupAliases = new LinkedHashMap<>();
        private List<Integer> resolutions = new ArrayList<>(List.of(2160,1080,720,480));
        private List<String> sources = new ArrayList<>(List.of("BD","WEB","TV","DVD"));
        private List<String> packaging = new ArrayList<>(List.of("SOFT","EXTERNAL","HARD"));
        private List<String> codecs = new ArrayList<>(List.of("AV1","HEVC","AVC"));
        private int minimumResolution = 720;
        private boolean requireChinese = true;
        private Set<String> requiredLanguages = new TreeSet<>();
        private boolean requireBilingual;
        private Set<String> allowedGroups = new TreeSet<>();
        private Set<String> blockedGroups = new TreeSet<>();
        private Set<String> containers = new TreeSet<>(List.of("mkv","mp4","avi","webm","ts"));
    }
    public record Decision(Verdict verdict, String reasonCode, String decisiveField, List<String> explanation) {}
    private enum Condition { YES, NO, UNKNOWN }
    private record Interval(int best, int worst) {}

    public void validate(Policy p) {
        if (p == null || p.getName() == null || p.getName().isBlank() || p.getName().length()>120
                || p.getDimensions() == null || p.getDimensions().isEmpty()
                || new HashSet<>(p.getDimensions()).size()!=p.getDimensions().size()
                || p.getMinimumResolution()<0 || p.getMinimumResolution()>16384)
            throw new IllegalArgumentException("质量规则名称、维度或最低分辨率无效");
        for (List<?> list : List.of(p.getSubtitles(),p.getGroups(),p.getResolutions(),p.getSources(),p.getPackaging(),p.getCodecs()))
            if (list.size()>200 || list.stream().anyMatch(Objects::isNull) || new HashSet<>(list).size()!=list.size())
                throw new IllegalArgumentException("规则排序列表重复或过长");
        if (p.getGroupAliases().size()>500) throw new IllegalArgumentException("字幕组别名过多");
    }

    public Decision evaluate(Policy p, Release candidate, Release current) {
        validate(p);
        List<String> why = new ArrayList<>();
        if (candidate == null || candidate.getSubjectId()==null || candidate.getEpisodes().isEmpty())
            return decision(Verdict.WAITING_EVIDENCE,"CHAPTER_IDENTITY_MISSING","identity",why,"需要作品与章节覆盖证据");
        if (current != null && (!Objects.equals(candidate.getSubjectId(),current.getSubjectId())
                || !Objects.equals(candidate.getSeason(),current.getSeason())
                || !Objects.equals(candidate.getChapterType(),current.getChapterType())
                || !Objects.equals(candidate.getContentEdition(),current.getContentEdition())
                || !candidate.getEpisodes().containsAll(current.getEpisodes())))
            return decision(Verdict.IDENTITY_CONFLICT,"COVERAGE_IDENTITY_CONFLICT","identity",why,"作品、季、章节类型或多集覆盖不一致");
        Condition hard = conditions(p,candidate,why);
        if (hard==Condition.NO) return new Decision(Verdict.REJECTED,"HARD_CONDITION_FAILED","hardConditions",why);
        if (hard==Condition.UNKNOWN) return new Decision(Verdict.WAITING_EVIDENCE,"HARD_CONDITION_UNKNOWN","hardConditions",why);
        if (current==null) return decision(Verdict.UPGRADE,"FIRST_DOWNLOAD","coverage",why,"缺少已有版本，候选满足全部硬条件");
        if (conditions(p,current,new ArrayList<>())==Condition.NO)
            return decision(Verdict.UPGRADE,"REPAIR_HARD_CONDITION","hardConditions",why,"已有版本违反硬条件，新版本满足，执行修复升级");
        for (Dimension d:p.getDimensions()) {
            if (d==Dimension.GROUP && p.getGroups().isEmpty()) continue;
            Interval next=rank(p,d,candidate), old=rank(p,d,current);
            if (next.worst<old.best)
                return decision(Verdict.UPGRADE,"PRIORITY_IMPROVED",d.name(),why,d+" 优先级改善；低优先级下降不影响本次结论");
            if (next.best>old.worst)
                return decision(Verdict.NOT_BETTER,"PRIORITY_LOWER",d.name(),why,d+" 低于已有版本");
            if (next.best==next.worst && old.best==old.worst && next.best==old.best) { why.add(d+" 同级"); continue; }
            // Equal unknown observations are not equal quality: either can occupy
            // a decisive rank. Unknown is deliberately distinct from unlisted.
            return decision(Verdict.WAITING_EVIDENCE,"DECISIVE_FIELD_UNKNOWN",d.name(),why,"需要补充 "+d+"，该字段可能改变优先级结论");
        }
        return decision(Verdict.NOT_BETTER,"SAME_QUALITY","",why,"所有参与维度同级，保留已有版本");
    }

    private static Decision decision(Verdict v,String code,String field,List<String> why,String text) {
        why.add(text); return new Decision(v,code,field,List.copyOf(why));
    }
    private Condition conditions(Policy p,Release r,List<String> why) {
        boolean unknown=false;
        if (r.getResolution()==null) { if(p.getMinimumResolution()>0) { why.add("最低分辨率证据缺失"); unknown=true; } }
        else if(r.getResolution()<p.getMinimumResolution()) { why.add("低于最低 "+p.getMinimumResolution()+"p"); return Condition.NO; }
        if (p.isRequireChinese() && Collections.disjoint(r.getLanguages(),Set.of("CHS","CHT","ZHO"))) {
            if(r.getLanguages().isEmpty()) { why.add("中文字幕能力未知，日语音轨不能作为日文字幕"); unknown=true; }
            else { why.add("没有中文字幕"); return Condition.NO; }
        }
        if (!r.getLanguages().containsAll(p.getRequiredLanguages())) {
            Set<String> missing=new HashSet<>(p.getRequiredLanguages()); missing.removeAll(r.getLanguages());
            if(r.getLanguages().isEmpty() || r.getLanguages().contains("ZHO")&&Set.of("CHS","CHT").containsAll(missing)) { why.add("必须语言或中文脚本能力未知"); unknown=true; }
            else { why.add("缺少必须字幕语言 "+p.getRequiredLanguages()); return Condition.NO; }
        }
        if (p.isRequireBilingual() && !"BILINGUAL".equals(r.getSubtitleLayout())) {
            if(r.getSubtitleLayout()==null) { why.add("双语同屏未知"); unknown=true; }
            else { why.add("未提供双语同屏"); return Condition.NO; }
        }
        Set<String> groups=canonicalGroups(p,r);
        if(groups.stream().anyMatch(p.getBlockedGroups()::contains)) { why.add("字幕组在黑名单"); return Condition.NO; }
        if(!p.getAllowedGroups().isEmpty() && groups.stream().noneMatch(p.getAllowedGroups()::contains)) {
            if(groups.isEmpty()) { why.add("必须字幕组未知"); unknown=true; }
            else { why.add("字幕组不在允许列表"); return Condition.NO; }
        }
        if(!p.getContainers().isEmpty()) {
            if(r.getContainer()==null) { why.add("容器尚未确认"); unknown=true; }
            else if(!p.getContainers().contains(r.getContainer().toLowerCase(Locale.ROOT))) { why.add("不支持的资源格式"); return Condition.NO; }
        }
        return unknown?Condition.UNKNOWN:Condition.YES;
    }
    private Interval rank(Policy p,Dimension d,Release r) {
        return switch(d) {
            case RESOLUTION -> index(p.getResolutions(),r.getResolution());
            case SUBTITLES -> subtitleRank(p,r);
            case GROUP -> {
                Set<String> groups=canonicalGroups(p,r);
                String joint=String.join(" & ",groups);
                if(p.getGroups().contains(joint)) yield index(p.getGroups(),joint);
                if(groups.isEmpty()) yield new Interval(0,p.getGroups().size());
                int best=groups.stream().mapToInt(g->p.getGroups().contains(g)?p.getGroups().indexOf(g):p.getGroups().size()).min().orElse(p.getGroups().size());
                yield new Interval(best,best);
            }
            case SOURCE -> index(p.getSources(),r.getSourceType());
            case PACKAGING -> index(p.getPackaging(),r.getSubtitlePackaging());
            case CODEC -> index(p.getCodecs(),r.getCodec());
            case CORRECTION -> r.getCorrection()==null ? new Interval(-100,0):new Interval(-r.getCorrection(),-r.getCorrection());
        };
    }
    private Interval subtitleRank(Policy p,Release r) {
        if(!r.getLanguages().contains("ZHO")) return index(p.getSubtitles(),r.getLanguages().isEmpty()?null:String.join("+",new TreeSet<>(r.getLanguages())));
        int best=p.getSubtitles().size(),worst=0;
        for(Set<String> scripts:List.of(Set.of("CHS"),Set.of("CHT"),Set.of("CHS","CHT"))) {
            Set<String> languages=new TreeSet<>(r.getLanguages()); languages.remove("ZHO"); languages.addAll(scripts);
            Interval rank=index(p.getSubtitles(),String.join("+",languages)); best=Math.min(best,rank.best); worst=Math.max(worst,rank.worst);
        }
        return new Interval(best,worst);
    }
    private Set<String> canonicalGroups(Policy p,Release r) {
        Set<String> groups=new TreeSet<>();
        for(String g:r.getGroups()) groups.add(p.getGroupAliases().getOrDefault(g,g));
        return groups;
    }
    private static <T> Interval index(List<T> list,T value) {
        if(value==null) return new Interval(0,list.size());
        int index=list.indexOf(value); if(index<0) index=list.size(); return new Interval(index,index);
    }
}
