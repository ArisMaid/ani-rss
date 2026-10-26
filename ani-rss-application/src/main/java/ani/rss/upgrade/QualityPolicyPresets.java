package ani.rss.upgrade;

import java.util.*;

/** Curated names and editable preferences, not a universal translation-quality ranking. */
public final class QualityPolicyPresets {
    private QualityPolicyPresets() {}
    public record Fansub(String name, List<String> aliases, String source) {
        public Fansub { aliases=List.copyOf(aliases); }
    }
    public record Preset(String id,String name,String description,QualityPolicyEngine.Policy policy) {
        public Preset { policy=UpgradeModels.snapshot(policy,QualityPolicyEngine.Policy.class); }
        @Override public QualityPolicyEngine.Policy policy() { return UpgradeModels.snapshot(policy,QualityPolicyEngine.Policy.class); }
    }
    public record Catalog(String updatedAt,List<Fansub> groups,List<Preset> presets,String note) {
        public Catalog { groups=List.copyOf(groups); presets=List.copyOf(presets); }
    }
    public static Catalog catalog() {
        List<Fansub> groups=List.of(
                new Fansub("桜都字幕组",List.of("桜都","樱都","樱都字幕组","櫻都字幕組","桜都字幕組"),"https://mikanani.me/Home/PublishGroup/181"),
                new Fansub("绿茶字幕组",List.of("绿茶","綠茶","綠茶字幕組"),"https://mikanani.me/Home/PublishGroup/1009"),
                new Fansub("北宇治字幕组",List.of("北宇治","北宇治字幕組"),"https://mikanani.me/Home/PublishGroup/388"),
                new Fansub("悠哈璃羽字幕社",List.of("悠哈璃羽","悠哈璃羽字幕组","悠哈璃羽字幕組","UHA-WINGS"),"https://mikanani.me/Home/Bangumi/3799"),
                new Fansub("SweetSub",List.of(),"https://github.com/SweetSub/SweetSub"),
                new Fansub("LoliHouse",List.of(),"https://mikanani.me/Home/Bangumi/3953"));
        List<Preset> presets=new ArrayList<>();
        var preferred=policy(groups); preferred.setName("1080p 简体 · 六组偏好");
        preferred.setDimensions(List.of(QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.GROUP,QualityPolicyEngine.Dimension.SUBTITLES));
        presets.add(new Preset("1080p-chs-preferred",preferred.getName(),"1080p 优先、最低 1080p，必须含简体；按六组顺序比较，其他组为后备。",preferred));
        var bilingual=policy(groups); bilingual.setName("1080p 简日字幕优先");
        bilingual.setDimensions(List.of(QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.SUBTITLES,QualityPolicyEngine.Dimension.GROUP));
        bilingual.setRequiredLanguages(new TreeSet<>(List.of("CHS","JPN")));
        presets.add(new Preset("1080p-chs-jpn",bilingual.getName(),"必须同时提供简体与日文字幕；音轨不能代替日文字幕。",bilingual));
        var multilingual=policy(groups); multilingual.setName("简繁多字幕 · 高分辨率");
        multilingual.setDimensions(List.of(QualityPolicyEngine.Dimension.SUBTITLES,QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.GROUP));
        multilingual.setResolutions(List.of(2160,1080,720,480));
        presets.add(new Preset("multisub-high-resolution",multilingual.getName(),"必须含简体；优先更多字幕组合，再比较分辨率与字幕组。",multilingual));
        return new Catalog("2026-10-24",groups,presets,"六组顺序来自用户偏好，可自行调整；组名与发布格式参考所列来源。联合发布保留各参与组，按最高排名参与组比较。预设不会自动改变已保存规则。");
    }
    private static QualityPolicyEngine.Policy policy(List<Fansub> groups) {
        var p=new QualityPolicyEngine.Policy();
        p.setMinimumResolution(1080); p.setRequiredLanguages(new TreeSet<>(Set.of("CHS")));
        p.setResolutions(new ArrayList<>(List.of(1080,2160,720,480)));
        p.setGroups(new ArrayList<>(groups.stream().map(Fansub::name).toList()));
        Map<String,String> aliases=new LinkedHashMap<>();
        groups.forEach(group->group.aliases().forEach(alias->aliases.put(alias,group.name())));
        p.setGroupAliases(aliases); return p;
    }
}
