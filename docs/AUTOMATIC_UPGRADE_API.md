# 自动化洗版 API 与配置示例

浏览器请求前缀为 `/api/v2/upgrade`。沿用应用的登录 Cookie、CSRF 与错误响应；不提供免认证入口。以下 JSON 不包含真实密钥。

| 方法与相对地址 | 内容 |
| --- | --- |
| GET `/status` | 唯一模式、配置修订号、任务／候选计数、扫描覆盖、工具状态及每日 AI 消耗 |
| PUT `/mode` | `{"mode":"OFF","expectedRevision":12}`；可选 OFF、LEGACY、AUTOMATIC。修订号冲突为 409 |
| GET／POST `/policies` | 列出／创建完整质量规则 |
| GET `/policy-presets` | 已核对来源的字幕组标准名、别名，以及三个可编辑质量预设；读取不会改写已保存规则 |
| POST `/targets/{id}/reanalyze` | 从已保存标题、种子地址补全缺失字段并重新决策，不请求外部补证；保留人工修正和已验证字段。遵循当前模式与媒体库门禁，AUTOMATIC 下符合条件的已关联目标可产生下载任务 |
| PUT `/policies/{id}` | 提交完整规则及读取时的 revision；成功后 revision 增加 |
| DELETE `/policies/{id}?expectedRevision=1` | 删除未被引用的规则，成功返回 204；过期修订号或正在被引用返回 409，不存在／已删除返回 404 |
| POST `/evaluate` | `{"policy":{...},"candidate":{...},"current":{...}}`；只比较草稿，不保存 |
| POST `/policies/{id}/evaluate` | `{"candidate":{...},"current":{...}}`；使用已保存规则 |
| GET `/mikan/seasons` | 读取配置的 Mikan 站点实际年份／季度菜单，不生成不存在的季度 |
| GET `/mikan/preview?year=2024&season=春` | 只读季度目录预览，返回 mikanId、title、category、url、cover；不创建订阅、目标或下载 |
| GET／POST `/campaigns` | 列出／创建季度订阅或兼容的日期任务；启用的新任务立即排入目录发现 |
| PUT `/campaigns/{id}` | 提交完整任务及 revision；缩小范围或暂停不删除媒体 |
| POST `/campaigns/{id}/scan` | 异步发现与候选检索，202 返回操作 ID |
| GET `/targets` | 分页目标档案 |
| PUT `/targets/{id}/override` | policyId、excluded、embySeriesId、season、episodeMapping、rssUrls；UNASSIGNED 完结档案可额外指定 libraryId，已关联目标不能直接转库 |
| GET `/candidates` | targetId、state 可选；保留同集不同 hash 的候选 |
| POST `/candidates/{id}/resolve` | 修正章节、字幕、字幕组等字段；仍需实际媒体核验，无强制删除接口 |
| GET `/preview` | targetId 可选；分页只读比较报告，含规则版本、候选证据、旧版与索引新鲜度，不创建任务 |
| GET `/versions` | targetId 必填；已接管／安装版本及保护状态 |
| PUT `/versions/{id}/protection` | `{"protectedVersion":true}`；保护状态参与文件清理门禁 |
| GET `/jobs` | targetId、state 可选；各阶段执行记录 |
| GET `/jobs/{id}` | 持久任务、明确文件清单、事件及 commitLocks 章节占用清单 |
| POST `/jobs/{id}/retry` | 重试可恢复阶段；安装中的不可变清单继续收敛 |
| POST `/jobs/{id}/cancel` | 取消安装前任务并异步停止独占自动下载，保留文件；共享任务转移归属，普通下载保持运行；文件提交已开始则拒绝取消 |
| POST `/emby/import-notification` | 显式复制一次现有 Emby 通知配置 |
| POST `/emby/test` | 媒体库列表、实际路径映射与可读性样例 |
| POST `/emby/sync` | `{"libraryId":"123"}`，202 返回索引任务 |
| GET `/ai/models` | 请求配置服务的可用模型列表 |
| POST `/ai/test` | 小型 JSON 输出自检；计入请求和 token 预算 |

分页使用 `offset=0&limit=50`，limit 范围 1–200；返回 items、offset、limit、hasMore。预演返回 decisions、reasonDistribution 与分页字段。预演只反映保存的证据，不代表已通过实际文件校验或获得生产验收。

目标扫描先读取独立 RSS，再推进 AnimeGarden 分页；某一源失败或尚未翻页完成时，已发现资源仍参与决策，失败源继续按原任务状态重试。每个 RSS 保存独立游标、成功时间、条目数和错误状态；成功读取缓存 15 分钟，失败不占用成功缓存。种子文件 URL 中的 40 位十六进制文件名用于候选归并，实际提交下载前仍核对种子内容的 info hash。

规则编辑保持原 ID 并增加 revision。维度及字幕组合、字幕组、分辨率、来源、封装、编码列表均按从高到低保存。删除检查全局默认、所有日期任务（包括暂停）、作品覆盖（包括排除）及未完成任务；先更换引用后再删除，不隐式替换正在使用的规则。删除后的定义保留用于历史审计，不再出现在规则列表或参与选择；已有媒体、任务与历史记录保持不变。

候选修正 DTO 支持 subjectId、episodes、season、groups、languages、subtitleLayout、subtitlePackaging、resolution、chapterType、contentEdition。身份不能改成另一作品，冲突返回 409／UPGRADE_RESOURCE_IDENTITY_CONFLICT；数值章节规范化，例如 01.0 保存为 1。章节类型支持 NORMAL／SPECIAL／EXTRA／OTHER／MOVIE，内容版本支持 MAIN／DIRECTORS_CUT／RECAP／EXTENDED。ZHO 表示已确认中文字幕但尚未确认简繁，不能作为特定简繁脚本证据。修正之后仍执行文件与提交验证。

`job.baselineFingerprint` 是安装前复核相关 active 版本的指纹。`BASELINE_CHANGED` 会回到 VERIFYING 重新比较；`CHAPTER_COMMIT_BUSY` 表示等待其他提交占用，不是人工身份冲突。`commitLocks` 包含 mediaKey、subjectId、libraryId、chapterKey、contentEdition、operationId、leaseUntil；未收敛提交不因租约过期失去占用，COMPLETED 后释放。

`job.decisionFingerprint` 用于下载后不合格／待修正结果的复评。相同输入下继续尝试其他资源；规则、解析字段、基线或保护状态改变后复用原传输回到 VERIFYING。`NOT_BETTER_AFTER_VERIFICATION` 表示实际媒体未证明值得安装，下载内容保留，也可手动重试验证。

`status.coverageMetrics` 的分母为全部持久目标，包含暂停、排除、等待播出、无资源和待确认目标。分子分别为发现资源、完成安装清理、新版 Emby 收录、人工核对、含不可用资源、服务失败重试和 AI 预算阻塞涉及的作品数；不同分子可能重叠，不能相加作为完成率。这里的“含不可用资源”包含超时或损坏候选，不等同于已证明种群永久死亡。

## 路径配置

连接与路径设置通过现有 `/api/v2/config` 中的 upgrade 字段保存。提交时保留同次读取的完整配置和 upgradeModeRevision；旧页面覆盖新配置会返回 409。读取秘密字段返回空值和 `embyKeyConfigured`／`aiKeyConfigured`，空输入保留旧值；显式清除使用 clearEmbyKey／clearAiKey。

```json
{
  "libraryIds": ["123"],
  "embyMappings": [{"remoteRoot":"/media/anime","localRoot":"D:\\TestMedia\\Anime"}],
  "downloaderMappings": [{"remoteRoot":"/downloads","localRoot":"D:\\TestDownloads"}],
  "tvRoot":"D:\\TestMedia\\Anime",
  "movieRoot":"D:\\TestMedia\\Movies",
  "stagingRoot":"D:\\TestDownloads",
  "ffprobe":"ffprobe",
  "ffmpeg":"ffmpeg",
  "aiBaseUrl":"https://api.deepseek.com",
  "aiModel":"填写服务当前提供并已通过自检的模型 ID",
  "aiEnabled":false,
  "aiConcurrency":2,
  "aiDailyRequests":500,
  "aiDailyTokens":1000000,
  "downloadConcurrency":2,
  "reserveBytes":2147483648,
  "noProgressHours":6,
  "downloadTimeoutHours":72,
  "timezone":"Asia/Shanghai"
}
```

示例电影目录也必须添加对应 Emby 映射。配置根目录需预先存在，暂存不得覆盖或位于媒体库根目录内。普通下载器连接仍在原设置页配置，自动下载任务使用独立归属与暂存路径。

Mikan 季度订阅示例：

```json
{"name":"2024 春季动画","catalogSource":"MIKAN","mikanYear":2024,"mikanSeason":"春","libraryId":"123","policyId":"已保存规则 ID","priority":10,"enabled":true,"revision":1}
```

`mikanSeason` 使用冬／春／夏／秋，分别对应 1–3／4–6／7–9／10–12 月。后端根据年份、季度生成 startDate／endDate，忽略客户端传入的日期；新建或改变季度时核对实际上游菜单。暂停、改规则或优先级且不改变所选季度时不依赖上游在线。复用 Mikan 列表缓存且不触发评分预热和原版订阅。

`libraryId` 留空或为 `UNASSIGNED` 时保存为“仅订阅监测”：无需已配置的 Emby 库，仍执行目录发现、候选检索和规则判断，不索引虚构媒体库、不创建自动下载与文件替换。只有关联已配置的真实媒体库后才允许完整洗版流程。规则预设的含义和资料来源见 [字幕组预设](FANSUB_PRESETS.md)。

扫描先保存 Mikan 目录快照，每步处理至多三条，通过 Mikan 条目的 Bangumi 链接核对作品身份，再读取首播事实。只关联同源、同 mikanId 的实际 RSS 链接，优先使用全字幕组聚合 RSS，保留已有手工 RSS 与排除设置。继续扫描 Bangumi 全部动画类型，补充无 Mikan 资源的作品；季度页上的往年长篇作品按首播范围排除。来源失败不冒充空目录，映射缺失保留事件与覆盖未完整标记；游标恢复、手动重扫及每日刷新覆盖两个来源。

旧日期任务示例（catalogSource 省略或为 DATE）：

```json
{"name":"2024 春季动画","startDate":"2024-04-01","endDate":"2024-06-30","libraryId":"123","policyId":"已保存规则 ID","priority":10,"enabled":true,"revision":1}
```

日期任务的 policyId 可为 null／空值，表示动态使用已保存的全局 defaultPolicyId；保存时所引用的默认规则必须存在。显式任务规则及单作品覆盖均采用完整规则，不与默认值逐字段合并。GET targets 按当前任务启用状态、优先级及首播区间重新计算 campaignId，不写回目标或创建任务。

单作品映射示例：

```json
{"policyId":null,"excluded":false,"embySeriesId":"456","season":2,"episodeMapping":{"2:1":"1","2:2":"2"},"rssUrls":[]}
```

## 本地测试与部署前核对

`GET /api/v2/upgrade/targets/{id}/coverage` 返回 `expectedKnown`、`sourceScanComplete`、`expectedChapters`、`eligibleChapters`、`installedChapters`、`missingChapters`、`qualifyingResources`、`identityConflicts` 和 `waitingEvidence`。状态区分 `WAITING_METADATA`、`SOURCE_INCOMPLETE`、`NO_QUALIFYING_RESOURCE`、`COVERAGE_GAPS` 和 `COVERED`；候选覆盖不代表下载完成。旧版来源游标需完成新版作品及别名查询后才能声称扫描完整。

质量订阅使用 `Ani.qualityPolicyId`；合集保存 `Item.selectedEpisodes`，qBittorrent 获得元数据后按章节选择视频/字幕，公共附件保留。未知视频章节或不能拆分的部分覆盖不会被当作验证成功。作品档案的 `seasonStartChapter` 与 `seasonStartEvidence` 保存完整季度总集数的换算依据，人工修正不被自动重分析覆盖。

先保持 OFF，完成依赖自检、所选媒体库完整索引、规则保存和只读预演导出。确认首播范围、实际输出目录、季集身份及保护状态，再在隔离测试库开启自动模式。检查新视频可播放、旧文件逐项结果、共享字幕／源保留、Emby 新版本收录和重启恢复。

备份完整配置数据库与审计清单。切回旧应用前先关闭自动模式并收敛未完成文件提交。数据库备份不能恢复已经删除的视频；旧应用不能接管新任务。真实服务验收与未完成门槛见 [验证记录](AUTOMATIC_UPGRADE_VALIDATION.md)。

完整生产检查项见 [部署前检查清单](AUTOMATIC_UPGRADE_DEPLOYMENT_CHECKLIST.md)。现有完整备份采用 SQLite 一致性快照，包含自动化表、schema 版本、逐文件记录、取消标记和持久章节锁；媒体视频不在这些状态记录的恢复承诺内。
