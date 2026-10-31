# 字幕组与质量预设

预设配置更新：2026-10-31。原六组先后顺序由用户偏好指定，不代表客观翻译质量评分。资源需要逐条核对字幕语言、封装、分辨率和章节，不能根据组名推断其必然有简体或日文字幕。

| 标准名称 | 预设别名 | 资料来源 |
| --- | --- | --- |
| 桜都字幕组 | 桜都、樱都、樱都字幕组、櫻都字幕組、桜都字幕組 | [Mikan 发布组](https://mikanani.me/Home/PublishGroup/181) |
| 绿茶字幕组 | 绿茶、綠茶、綠茶字幕組 | [Mikan 发布组](https://mikanani.me/Home/PublishGroup/1009) |
| 北宇治字幕组 | 北宇治、北宇治字幕組 | [Mikan 发布组](https://mikanani.me/Home/PublishGroup/388) |
| 悠哈璃羽字幕社 | 悠哈璃羽、悠哈璃羽字幕组／字幕組、UHA-WINGS | [Mikan 发布实例](https://mikanani.me/Home/Bangumi/3799) |
| SweetSub | 英文大小写归一 | [官方字幕项目](https://github.com/SweetSub/SweetSub)、[Mikan 发布组](https://mikanani.me/Home/PublishGroup/212) |
| LoliHouse | 英文大小写归一 | [Mikan 联合发布实例](https://mikanani.me/Home/Bangumi/3953) |

扩展目录还包含以下七组，整套预设将它们排在原六组之后，后备顺序可继续调整：

| 标准名称 | 预设别名 | 资料来源 |
| --- | --- | --- |
| 喵萌奶茶屋 | Nekomoe kissaten、NekomoeKissaten | [官方字幕项目](https://github.com/Nekomoekissaten-SUB/Nekomoekissaten-Subs) |
| 动漫国字幕组 | 动漫国、動漫國、動漫國字幕組、DMG | [Mikan](https://mikanani.me/Home/PublishGroup/18) |
| 幻樱字幕组 | 幻樱、幻櫻、幻櫻字幕組 | [Mikan](https://mikanani.me/Home/PublishGroup/20) |
| 云光字幕组 | 云光、雲光、雲光字幕組 | [Mikan](https://mikanani.me/Home/PublishGroup/217) |
| 诸神字幕组 | 诸神、諸神、諸神字幕組、Kamigami、诸神kamigami字幕组 | [官方站点](https://subs.kamigami.org/) |
| 霜庭云花 | 霜庭雲花 | [Mikan](https://mikanani.me/Home/PublishGroup/346) |
| 拨雪寻春 | 撥雪尋春、Haruhana、HaruhanaSub | [官方字幕项目](https://github.com/HaruhanaSub/HaruhanaSub) |

简称和繁简字形是用于配置的名称归一规则；原始发布标题、来源字段与证据仍保留。`&`、`＆`、`+`、`×` 分隔的联合发布保留所有参与组，按最高排名参与组比较。不能把 `SweetSub&LoliHouse` 当成未收录的新组。组名不区分英文大小写，兼容全角字符及首尾空格。

## 六个可编辑预设

- **1080p 简体 · 全部字幕组**：最低 1080p、必须 CHS；1080p 排在 2160p 前；分辨率 → 字幕组 → 字幕组合。全部 13 组参与排序，前六组为桜都、绿茶、北宇治、悠哈璃羽、SweetSub、LoliHouse，其余为后备，未开启组白名单。
- **1080p 简日字幕优先**：最低 1080p、必须 CHS 和 JPN；分辨率 → 字幕组合 → 字幕组。日本语音轨不等于日文字幕，不要求双语同屏。
- **简繁多字幕 · 高分辨率**：最低 1080p、必须 CHS；字幕组合 → 分辨率 → 字幕组，分辨率按 2160、1080 排序。

- **1080p 繁体优先**：最低 1080p，必须 CHT，完整字幕组排序。
- **收藏 · BD 与内封优先**：最低 1080p、必须 CHS；来源 → 分辨率 → 字幕组合 → 封装 → 字幕组，BD 为偏好而非硬限制。
- **兼容播放 · AVC 优先**：最低 1080p、必须 CHS；分辨率 → 编码 → 字幕组合 → 字幕组，同分辨率优先 AVC。

“应用全部 13 组”把全部目录组加入当前排序，保留自定义后备和其他参数；“原六组优先”只把原六组移到前方。选择器用别名搜索，选中标签只显示标准名，页面分别显示目录和排序数量。

应用完整预设只修改编辑草稿；编辑已有规则时保留其 ID、名称和修订号，保存时仍执行版本冲突检查。仅应用六组顺序时保留自定义后备组与用户别名。预设不会自动修改全局默认、其他规则或正在执行的任务。

## 订阅监测与下载验证

季度目录每行默认勾选。取消时先核对 Mikan 对应的 Bangumi ID；失败保留原选择，成功后随季度订阅保存，刷新同季度保持取消，切换季度重新选择。取消仅作用于本季度任务，不覆盖全局作品排除，也不删除已有媒体；已开始的安装恢复流程继续完成。重新勾选并保存可恢复监测。

季度任务可选择“仅订阅监测”，暂不绑定 Emby。它仍核对首播日期、收集候选并计算规则结果；不会索引虚构媒体库，也不会创建自动下载或文件替换任务。后续关联真实媒体库后再使用完整洗版流程。

2026 年夏季按 **2026-07-01 至 2026-09-30 的首播日期**筛选；Mikan 当季目录中的春季续播作品不因此改算夏季首播。无资源、日期待核实和来源失败分别保留统计，不能从验收分母中删除。

本次用户已改为先验收订阅可用性，真实媒体替换延期。本地下载测试目录为项目内独立的 `subscription-test-downloads/`，已忽略提交；qBittorrent 使用独立 profile，不修改日常 profile，下载速度不限。真实网络资源结果保存在 `output/subscription-usability/`，与离线夹具和单元测试结果分开记录。
