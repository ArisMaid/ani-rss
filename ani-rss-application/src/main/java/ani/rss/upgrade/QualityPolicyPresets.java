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
                new Fansub("LoliHouse",List.of(),"https://mikanani.me/Home/Bangumi/3953"),
                new Fansub("喵萌奶茶屋",List.of("Nekomoe kissaten","NekomoeKissaten"),"https://github.com/Nekomoekissaten-SUB/Nekomoekissaten-Subs"),
                new Fansub("动漫国字幕组",List.of("动漫国","動漫國","動漫國字幕組","DMG"),"https://mikanani.me/Home/PublishGroup/18"),
                new Fansub("幻樱字幕组",List.of("幻樱","幻櫻","幻櫻字幕組"),"https://mikanani.me/Home/PublishGroup/20"),
                new Fansub("云光字幕组",List.of("云光","雲光","雲光字幕組"),"https://mikanani.me/Home/PublishGroup/217"),
                new Fansub("诸神字幕组",List.of("诸神","諸神","諸神字幕組","Kamigami","诸神kamigami字幕组"),"https://subs.kamigami.org/"),
                new Fansub("霜庭云花",List.of("霜庭雲花"),"https://mikanani.me/Home/PublishGroup/346"),
                new Fansub("拨雪寻春",List.of("撥雪尋春","Haruhana","HaruhanaSub"),"https://github.com/HaruhanaSub/HaruhanaSub"));
        List<Preset> presets=new ArrayList<>();
        var preferred=policy(groups); preferred.setName("1080p 简体 · 全部字幕组");
        preferred.setDimensions(List.of(QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.GROUP,QualityPolicyEngine.Dimension.SUBTITLES));
        presets.add(new Preset("1080p-chs-preferred",preferred.getName(),"1080p 优先、最低 1080p，必须含简体；原六组优先，其余预设组按后备顺序比较，可自行调整。",preferred));
        var bilingual=policy(groups); bilingual.setName("1080p 简日字幕优先");
        bilingual.setDimensions(List.of(QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.SUBTITLES,QualityPolicyEngine.Dimension.GROUP));
        bilingual.setRequiredLanguages(new TreeSet<>(List.of("CHS","JPN")));
        presets.add(new Preset("1080p-chs-jpn",bilingual.getName(),"必须同时提供简体与日文字幕；音轨不能代替日文字幕。",bilingual));
        var multilingual=policy(groups); multilingual.setName("简繁多字幕 · 高分辨率");
        multilingual.setDimensions(List.of(QualityPolicyEngine.Dimension.SUBTITLES,QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.GROUP));
        multilingual.setResolutions(List.of(2160,1080,720,480));
        presets.add(new Preset("multisub-high-resolution",multilingual.getName(),"必须含简体；优先更多字幕组合，再比较分辨率与字幕组。",multilingual));
        var traditional=policy(groups); traditional.setName("1080p 繁体优先");
        traditional.setRequiredLanguages(new TreeSet<>(Set.of("CHT")));
        traditional.setSubtitles(List.of("CHS+CHT+JPN","CHT+JPN","CHS+CHT","CHT","CHS+JPN","CHS"));
        presets.add(new Preset("1080p-cht",traditional.getName(),"最低 1080p，必须繁体字幕；原六组优先，包含全部预设组。",traditional));
        var archive=policy(groups); archive.setName("收藏 · BD 与内封优先");
        archive.setDimensions(List.of(QualityPolicyEngine.Dimension.SOURCE,QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.SUBTITLES,QualityPolicyEngine.Dimension.PACKAGING,QualityPolicyEngine.Dimension.GROUP));
        archive.setResolutions(List.of(2160,1080,720,480));
        presets.add(new Preset("archive-bd",archive.getName(),"最低 1080p、必须简体；BD 来源优先，再比较分辨率、字幕组合与封装。BD 是偏好而非硬限制。",archive));
        var compatible=policy(groups); compatible.setName("兼容播放 · AVC 优先");
        compatible.setDimensions(List.of(QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.CODEC,QualityPolicyEngine.Dimension.SUBTITLES,QualityPolicyEngine.Dimension.GROUP));
        compatible.setCodecs(List.of("AVC","HEVC","AV1"));
        presets.add(new Preset("compatible-avc",compatible.getName(),"最低 1080p、必须简体；同分辨率优先 H.264 / AVC，不保证所有设备均可直放。",compatible));
        return new Catalog("2026-10-28",groups,presets,"原有六组顺序保留，整套预设包含全部字幕组，扩展组作为后备顺序，不代表质量排名。支持按名称与别名搜索；联合发布按最高排名参与组比较。预设只修改草稿，保存后生效。");
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
