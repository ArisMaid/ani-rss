# ani-rss fork 修复与性能收缩开发计划

> 实施对象：gbt5.6-luna。本文是开发任务说明，不代表已经实施或通过性能测试。
> 审查日期：2026-09-07。先读第 0、1、3 节，再按第 12 节顺序实施。

## 0. 基线、范围与执行约束

### 0.1 本次实际审查基线

| 对象 | 精确版本 |
|---|---|
| 当前工作分支 | `master` |
| fork HEAD | `562454f287645531e09bb2ada65afd31dbfa4091` |
| fork 应用版本 | `3.2.28.58` |
| 对齐比较的上游标签 | `v3.2.28`，`f4d3530836b9731f9bc8c7a2704929f5c45aaa87` |
| 已 fetch 的最新上游 | `upstream/main`，`299e7a9bae0ac9b146a44ddc295d300ed30ab3ac`（v3.2.29） |
| 上游地址 | `https://github.com/wushuo894/ani-rss` |
| fork 地址 | `https://github.com/ArisMaid/ani-rss` |

`git diff v3.2.28 HEAD` 共 291 个文件变化，29414 行增加、4498 行删除。此数包含测试、构建、文件移动等，不能当作冗余代码量。

必须区分：

1. `v3.2.28 → fork HEAD`：同功能版本下的 fork 差异。
2. `v3.2.28 → upstream/main`：尚未同步的上游功能。本次 fetch 看到 v3.2.29 新增自定义点击封面行为及版本提交。
3. fork 自己的同步提交：尤其 `7f6f5f90`，需要检查上游新页面是否重新接入 fork 后端契约。

本次是静态源码、调用链及提交差异审查；没有连接用户生产实例、实际下载器或运行浏览器性能测试，也未执行完整构建。后文所有毫秒、并发和容量要求均为拟定验收条件，不是测量结果。

### 0.2 默认实施边界

先修复确定的前后端契约断裂，再优化请求与资源加载，最后精简有状态机制。维持上游现有页面、功能和使用方式；不要恢复旧 UI。不要把“接近上游”理解为复制上游整个目录。

本文不授权运行生产恢复、触发真实下载、删除媒体、发布版本或推送仓库。实施只使用隔离测试配置、模拟 HTTP 服务和测试下载器。

项目用户规则：禁止批量删除文件或目录。不能通过 Maven clean、Vite 清空输出目录、脚本或循环规避这一限制。确有清理需要时由用户手动清理；单文件删除必须指定明确路径。PowerShell 使用参数数组及 `-LiteralPath`，不要拼接待执行命令。本文编写阶段未删除文件。

### 0.3 已确认要求与剩余决策

用户在审查期间明确确认：蜜柑列表、评分、封面及订阅刷新都较慢，评分尤其需要重复刷新；**缺集补全必须保留**；选择 **先修复性能与契约，再分阶段精简数据机制**。这两条是实施硬约束，不能由 Luna 自行改为“移除补全以减少状态”或“整体回滚上游”。

| 编号 | 待确认信息 | 未答复时的处理 |
|---|---|---|
| D1 | 内网/公网、反向代理路径、下载器、最明显卡顿入口及规模 | 使用两种访问路径的隔离测试；不调整生产部署或下载行为 |
| D2（已确认） | 先性能与契约，后分阶段精简；保留缺集补全 | 不移除缺集补全、不更换数据库；状态机制只按第 10 节渐进优化 |
| D3 | 用户期望“删除下载器任务但保留文件”与“本地文件缺失”的补全行为 | 以当前重复下载修复契约为准；不新增自动重下策略 |
| D4 | 公共远程封面是否需要严格的会话隔离 | 默认只对公开且无凭据的远程封面共享内容缓存；访问仍要求登录；私有图片沿用当前隔离 |

其余信息缺失不妨碍 4、5、6 节的确定性修复。整体回归上游不在已确认范围，禁止自行用本计划把 SQLite 数据转换回 JSON。

## 1. 审查结论与可核查证据

表中路径相对仓库根目录；方法名用于定位，避免后续改动使行号失效。

| ID / 优先级 | 已读证据 | 结论及影响 |
|---|---|---|
| F01 / P0 | `ani-rss-ui/src/view/play/PlayStartView.vue:show` 用 `toApiFile(filename)`；后端 `controller/PlayController.java:getPlayItem` 把媒体句柄写入 filename；`controller/FileController.java:verifyFileFormat` 仅允许图片 | 视频/字幕句柄被送往旧图片接口，播放契约不匹配。应接回现有媒体接口 |
| F02 / P0 | UI `view/config/basic/BackupView.vue:callback` 只看 code=200；`controller/ConfigController.java:importConfig` 只调用 `restoreService.stage` | “导入成功”实际可能只是暂存校验，缺 confirm/status 流程；INVALID 也可能装在成功响应中 |
| F03 / P1 | `view/home/MikanView.vue:list` 只调用 mikan；`src/js/http.js:mikanScores` 无调用点；`service/MikanService.java:list` 只附缓存评分并后台补齐 | 冷评分不会在当前页面自动显示，用户重开页面才可能看到。后端工作已经发生但 UI 没消费结果 |
| F04 / P1 | `http.js:preloadDefaultMikanList` 无调用点，注释仍称首页预载且弹窗懒加载；全 src 未发现 defineAsyncComponent | 历史优化代码和现行 UI 脱节。不要为了让死代码“有用”恢复首页大范围预取 |
| F05 / P1 | `SafeImageView.vue` POST images 后 GET id；`ImageCacheService:cache` 键含 sessionBinding；`ImageController:image` 设置 private,no-store | 每个新挂载图片至少一次解析 POST；多会话重复下载同一公共封面；浏览器不能复用图片实体 |
| F06 / P1 | `ImageCacheService` 仅内存记录随机文件；trimExpired 仅新增成功时触发，removeEntry 先移除索引再吞掉删除错误 | 重启后旧文件不在索引，类内没有恢复/清理它们的流程；失败删除注释声称重试但已丢失记录。需落实缓存生命周期，不能宣称已测到磁盘泄漏规模 |
| F07 / P1 | `router/index.js` 静态导入全部页面；`main.js` 全量图标注册；播放及设置下钻组件也是静态引用 | 大量非首屏代码进入首屏依赖图。实际字节量需测量；不能仅改 chunk 名就声称优化 |
| F08 / P1 | `DashboardView.vue:startPolling` 每 5 秒 setInterval(loadTorrents)，loadTorrents 无并发闸；有 deactivated/unmounted 清理 | 慢请求时可重叠；切出浏览器标签未暂停。已有路由停用清理，应复用而非再叠一个后台调度器 |
| F09 / P1 | `MikanService:list` 每次可安排 96 个冷评分补齐，另有相邻季预取线程；`PublicScoreService:newWarmupExecutor` 用 newFixedThreadPool | 实际活动请求受 semaphore 限制，但工作队列并非注释所称有界；不同查询可累计无用工作。队列超时还在工作开始时计时，未包含排队等待 |
| F10 / P1 | `AnimeGardenService:list` 依次 getBgmCover、subjects、getBgmScores；`CacheService:getBgmCover` 每调用一次即发网络请求 | 可选封面/评分阻塞列表；和蜜柑的非阻塞设计不一致 |
| F11 / P1 | `api.js` 对任何 401/403 都 clearAuthentication 并安排 reload | 普通权限拒绝会造成退出及多个刷新定时器；需按错误语义处理，不应把所有 403 当会话失效 |
| F12 / P1 | `package.json` 仅 dev/build/preview；`verify.ps1`、`verify.sh` 仍要求 lint/typecheck/test；`build-test.yml` 为手动触发并带镜像发布 | 现有验证入口失配，无法保护前端契约及包体回退 |
| F13 / P2 | `PublicScoreService` 同时有同步批处理、后台补齐、单条/批量两层缓存读、临时线程池、两个补齐池及持久化池 | 可收敛调度和重复路径；不是“所有并发保护都应删除”。AnimeGarden 仍调用同步 getBgmScores，迁移它之前不能删 |
| F14 / P2 | `AuthService:validateRequest` 已在 request attribute 缓存认证结果 | 重复调用 validateRequest 不等于重复执行完整密码校验。不能以调用次数为理由重写认证系统 |
| F15 / P2 | `DatabaseManager` 单连接全局锁；ownership/recovery/completion 贯穿 DownloadService、启动、文件操作、备份恢复 | 高耦合属实；数据库锁实际等待量和删减收益未测。回滚成上游会触及真实持久化语义 |
| F16 / P1 | `MikanView.vue:list` 无请求序号/取消；每个请求 finally 都设置 loading=false | 搜索 A 慢、B 快时 A 可覆盖 B；旧请求结束可提前撤掉新请求 loading |
| F17 / P1 | `refreshAll` 仅入队；`SubscriptionView:refreshAni` 收到响应立即 getList 后结束 loading | UI 没有观察真实刷新完成，容易仍显示旧数据；应区分受理和完成 |
| F18 / P1 | `DownloadService:downloadAni` 全方法全局锁，每订阅取 torrents；`TorrentUtil:getTorrentsInfosResult` 固定 sleep(1000)，RssTask 每订阅 sleep(500) | N 个启用订阅仅这两处就带来约 1.5N 秒主动等待，未计网络、备用 RSS 与写操作等待；不是实测总耗时 |
| F19 / P1 | `TorrentUtil:getTorrentsInfos` 把失败返回为空列表，downloadAni 调用该包装 | 调用方丢失故障语义；是否造成某次重下需复现，但实现优化不能继续把故障快照当已知空列表 |

### 1.1 已确认的同步回退来源

`7f6f5f90`（sync v3.2.20）新增 `src/view/home/MikanView.vue` 与新 router，同时删除 package.json 中 lint、typecheck、test、check 及包体检查入口。旧代码的优化未按新页面调用链迁移。应在该提交前的旧目录定位历史实现，不能因为文件路径变化而断言对应功能“上游不需要”。

`9e27b5f8` 修复生产 UI 白屏；当前 vite.config.js 明确注明强制 Element Plus 分块曾产生初始化循环。保留默认分块图，优先从 import 边界减小入口。`562454f2` 后评分 API 孤立、播放及恢复契约仍失配。

`04ea2538` 和 `f7e1df87` 涉及 qB 清理后重复下载、缺集恢复重复提交。它们对应用户数据行为，不能与预取/会话图片设计一起批量回退。

### 1.2 fork 差异处理清单

| 差异区域 | 处理决定 |
|---|---|
| 列表、公开评分、图片缓存 | 保留需求，按本文削减请求与调度层数 |
| 前端 API 适配、媒体句柄、恢复 API | 保留现有后端契约，修复新 UI 接入；不建设 v3 API |
| 会话、CSRF、凭据脱敏、Cookie 隔离 | 保留核心；只修错误语义、死兼容分支与热点重复工作 |
| SQLite ConfigStore/SubscriptionRepository | 保留存储格式；不新增 ORM、连接池或通用仓储框架 |
| 归属、隔离、补全、完结迁移 | 保留数据与行为；后续有证据再合并重复查询或状态 |
| 下载器结果分类、qB 登录、Transmission dialect、OpenList | 保留已覆盖的具体兼容修复，模拟测试验证；不要只测 qB 就删其他实现 |
| PathPolicy、原子写入、备份校验 | 保留有效的数据边界；不因“安全冗余”而恢复任意路径读取/删除 |
| JsonCompatibility、版本标识、Java 17 兼容、打包修复 | 保留实际仍用的兼容入口；通过调用/依赖检查后才能逐项清理 |
| 依赖覆盖、SpotBugs、SBOM、verify 脚本 | 先修可运行性；依赖安全分析与日常功能门禁分层，不盲目升级/降级依赖 |
| UPDATE、README、UPSTREAM_SYNC | 修正文档中的过期功能/规则，补行为级同步清单 |

## 2. 目标与不做的事情

最终目标：标题列表先可交互，评分和图片随后完成；没有用户需求时不发外网请求；同一资源并发请求只访问外部一次；页面切换不会继续制造无效请求；同步上游能自动发现 fork 契约丢失。

本轮不做 UI 重设计、全量 TypeScript 改造、全新鉴权、数据库替换、微服务、通用任务总线、Redis、Service Worker、WebSocket/SSE 评分推送、全应用 HTTP 客户端重写或依赖升级专项。任何新依赖必须对应本文明确测试或实现需求。

先以行为减少复杂度，不以“删掉多少行”验收。每种缓存写清 key、TTL、上限、失效入口、失败语义；每个队列写清线程数、容量、拒绝行为、关闭方式。不要新增面向用户的十几项调参配置；参数先集中为代码常量。

## 3. 基线测试与度量（T0）

### 3.1 测试环境

在新隔离配置目录/临时测试目录中启动。用测试 fixture 创建 100 条订阅、96 条蜜柑条目、50 张小图片；必要时补 500 订阅规模。禁止读取真实账号数据作为 fixture。HTTP stub 模拟蜜柑列表、详情、Bangumi、图片及下载器，记录方法、路径、时间、并发与次数。测试公共图片地址策略时注入测试 transport/DNS，不放宽生产校验。

测量 fork 当前版本与每阶段结果；如测上游对照，用相同夹具与相同硬件，独立配置目录，不能让上游读取 fork 数据库。

### 3.2 必须输出的基线

新建 `docs/performance-baseline.md`，记录提交、运行时版本、机器、代理模式、样本数和原始数据位置。场景：

1. 无登录首页、已登录首页、直接进入订阅页；记录首屏实际加载的 JS/CSS 压缩字节及模块依赖，不把所有 dist 大小当首屏大小。
2. 首次打开蜜柑、二次打开、换季、连续搜索、关闭、隐藏标签页；分开记录列表返回、可交互、第一张图、第一项评分、所选分组评分完成。
3. 后端冷缓存、进程内热缓存、进程重启保留持久缓存三种状态；不要都称“冷启动”。
4. 外部响应 200ms/2s/超时/429/500，图片重复 URL、多会话相同 URL。
5. 下载器响应 8 秒、首页停留 30 秒，记录最大并发及总请求数。
6. 数据库调用数与锁等待（仅临时计时或测试桩），线程池活动数/队列长度/网络请求数，缓存目录字节数。
7. 100 个启用订阅、下载器/RSS stub 立即返回且无写操作的完整 RSS 刷新；分列主动 sleep、网络等待、业务执行三段时间。记录 POST 受理到后台实际结束及 UI 反映结果的延迟。

### 3.3 拟定验收阈值

| 项目 | 必须成立的验收 |
|---|---|
| 列表与可选资源 | stub 封面/评分挂起时，列表仍返回；不受 5 秒封面或 12 秒评分预算拖延 |
| 热列表 | 隔离基准 p95 目标 ≤200ms，至少 30 次；超标先找本地阻塞，不放宽门槛冒充完成 |
| 同资源并发 | 20 个相同 key 并发请求，成功加载仅 1 次外部请求；失败结束后的下一轮可恢复 |
| 图片 | 新路径每张图没有解析 POST；缓存有效时重新挂载不产生实体重传或外部下载 |
| 按需请求 | 未打开列表不预取季度/评分；未激活星期不加载其评分；未进入视口的封面不主动获取 |
| 轮询 | 下载器慢于轮询间隔时同页最大在途=1；停用/隐藏后不再启动下一次 |
| 首屏包 | 登录首屏不下载播放器、Markdown 渲染器、设置深层页面；目标入口压缩 JS 比当前减少 ≥25%，以实际首屏依赖闭包统计 |
| 并发预算 | 全局评分外部请求 ≤4，等待队列 ≤96；图片外部请求 ≤6，等待队列 ≤48（不同测试配置可注入） |
| 稳定性 | 多轮开关弹窗后没有线性增长的 timer、observer、对象 URL 或待执行任务 |

25% 和 200ms 属目标；若当前结构/环境使目标不合理，提交数据与阻塞原因让用户调整。不能用删功能、改 fixture 或增机器资源达标。

## 4. 先修契约断裂（T1）

### 4.1 播放与字幕

改动：`PlayStartView.vue`、`ArtplayerView.vue`，必要时仅补 `http.js` 注释及类型。

1. 保持 `PlayItem.filename` 当前代表媒体句柄的 wire format，本轮不批量改名。修正 Java 注释中“路径/base64”的过期说明。
2. 播放 src 使用 `toApiMedia(pi.filename)`；外置字幕 `subtitle.url` 同样按句柄转换。不要对句柄再次当文件名加密或拼入 api/file。
3. `http.getSubtitles` 目前 base64 编码句柄，后端对应解码再 resolve，二者匹配；保留此传输方式，不能只改一端。
4. 内封字幕请求不阻塞基础视频展示。先创建播放器，再以播放器已安装版本支持的字幕更新接口追加字幕；若暂不支持动态更新，先确保基础视频可播放并提供明确字幕加载状态，不因字幕失败关闭播放器。
5. 每次打开记录 generation，关闭后迟到字幕结果不得写回；记录本次创建的 blob URL，关闭/切片时逐个 revoke。网络取消不能当用户错误弹窗。
6. 点击外部播放器时调用现有 `externalMediaHandle(handle)`，使用返回 `.data.handle` 构造媒体 URL；只在点击时签发。不能把依赖 Cookie 的站内 URL直接传给外部播放器，不能把会话 token 放 URL。保持各播放器现有 deep-link 编码格式。
7. 处理句柄过期：提示重新获取视频列表，不递归重试，不改为任意路径读取。

测试：真实小测试媒体对应 GET 200、Range 206、HEAD 无响应体、非法 Range 416；测试媒体须满足列表当前 20MB 过滤或绕过列表测试流接口。站内带会话播放、外部句柄无 Cookie 可播放、外部句柄到期拒绝；字幕失败不影响播放，关闭后对象 URL 释放。至少一个浏览器操作测试经过页面而不是只调用 MediaController。

### 4.2 备份导入

改动：`BackupView.vue`，复用 `stageRestore`、`confirmRestore`、`restoreStatus`。不要让旧 `importConfig` 一上传就执行覆盖，以免其他客户端意外触发破坏操作。

界面固定流程：选择文件 → stage → 展示校验结果/覆盖范围 → 用户点击一次确认 → confirm → 有限状态轮询 → 完成或失败。移除上传前无具体内容的重复确认；真正覆盖前的确认保留一次。

状态规则严格使用已有后端枚举：

- `INVALID`：展示 errors，不提供确认，不显示成功。
- `VALIDATED`：展示版本、legacy、warnings 及影响说明；允许确认。
- `QUEUED/STOPPING/SWITCHING`：显示进行中，禁用再次确认；每 1 秒在上一请求完成后再查状态。
- `SUCCEEDED`：才提示导入完成。恢复可能使会话失效，允许重新登录，不以一次 401 推断成功。
- `ROLLED_BACK`：提示恢复未完成且已回滚；`FAILED/MAINTENANCE_REQUIRED` 展示明确状态与 operationId，停止轮询。不得自动再次 confirm。

轮询最多 120 秒，超时显示“状态尚未确认，可重新查询”，保留 operationId；不能改成失败或再次提交。operationId 可存在当前 tab 的 sessionStorage（不存文件内容或凭据），终态确认后移除，以便重新登录后续查。关闭视图停止前端轮询，不取消已经开始的后端恢复。网络断开和重新登录后通过 operationId 重查；若后端不支持恢复后查询，显示“结果待确认”，由重新读取的配置/状态确认，不能伪造成功。

测试覆盖 INVALID/VALIDATED/SUCCEEDED/ROLLED_BACK/MAINTENANCE_REQUIRED、双击确认只发一次、断线不重提、两种备份格式（legacy JSON 和 fork SQLite）均使用合成数据。既有 RestoreServiceTest/BackupArchiveTest 必须继续通过。

### 4.3 API 错误语义

`api.js` 的新旧响应都统一为具有 `status/code/message` 的 ApiError。401 由一个会话失效入口处理一次；普通 403（如 PRIVATE_NETWORK_REQUIRED）保留登录状态并展示错误，不刷新页面。CSRF 失败若暂被后端归为认证失败，先明确错误 code 再决定恢复逻辑，不自动重放写请求。

`silent` 只控制提示，不吞错误。AbortError 不弹提示。不要增加全局自动重试。对登录初始化增加有界超时及 loading/错误状态，避免 MainView 的 ready 永远为 false；网络故障不能串行触发所有旧凭据迁移回退。旧凭据迁移仅在明确认证失败且确有旧凭据时尝试。

## 5. 恢复列表的按需补齐（T2）

### 5.1 蜜柑页面请求生命周期

改动：`MikanView.vue`、`http.js`，可新增单个 `src/js/mikan-loader.js` 保存可测试的页面请求逻辑；不要创建通用查询框架。

1. 每次 show/search/change 开始递增 listGeneration，取消旧列表请求和评分轮询；参数用当前快照，不能异步继续读取变化中的 text/season。
2. http.mikan 支持现有 options.signal；结果、错误、finally 更新 UI 前均检查 generation。关闭弹窗与卸载执行相同失效逻辑。
3. 列表立即渲染，列表 loading 不等待评分、封面或字幕组。
4. 首轮仅补当前 activeName 星期内缺少评分的条目，切换星期取消旧轮询并启动当前组。选中分组大于 48 ID 时分批、串行，每批去重，ID 从后端条目 URL 的 `/Home/Bangumi/<数字>` 解析，不依赖客户端任意 URL作为评分输入。
5. 调用现有 `mikanScores(ids)`，按 scores 的 mikanId 合并 score/bgmId。检查有限数值，未命中不覆盖现有正评分；保留 mikanId 与 bgmId 类型一致，使用字符串比较。
6. 已订阅标记保留列表值；新获得 bgmId 时可依据 subscribedBgmIds 补 true，不能因评分未完成把已有 exists 变 false。
7. 仅轮询 retryableMikanIds，等待序列 0.5s、1s、2s，此后每 2s；一次激活最多 20s，并使用完成后调度，不用 setInterval。不重发已经获得最终结果的 ID。超时保持列表可用，显示可手动重试，不无限后台刷。
8. showScore=false 时不发评分请求；从 false 切 true 且弹窗可见时再补齐。合并评分时不自动重排正在点击的列表。
9. 关闭、隐藏浏览器标签或卸载取消私有轮询/请求。共享请求不因某个观察者关闭而影响其他观察者：本轮优先只做组件请求序号，别把取消共享 Promise 混入通用 API。
10. 字幕组请求同样用 groupGeneration/inFlight 标记；切换 A→B 时 A 返回不能撤掉 B 的 loading，成功缓存按 URL 存储，只让当前组显示结果。

### 5.2 预载收缩

删除 `preloadDefaultMikanList` 的死分支与过期注释；本轮默认不在首页发蜜柑预取。后端已有 10 分钟列表缓存，先不再叠前端 30 秒 payload 缓存。重开弹窗允许一次轻量列表请求，以刷新 exists 等用户状态。

`MikanService.list` 改成“读取缓存评分、绝不安排全季评分网络任务”；可先调用现有 `getCachedMikanScoreLookupAndWarm(infos, 0)` 过渡，随后合并成语义明确的 cache-only 方法。真正补齐只由 scores 接口的所需 ID 驱动。

取消 `prefetchAdjacentSeasonAsync` 的调用、独有执行器/集合及只服务此功能的方法。保留季节列表 stale-while-refresh；不把相邻季缓存和用户真正查询的缓存同时删除。若以后用户明确要求邻季预载，再以可测量收益单独加入。

测试必须同时断言：冷列表立即返回、当前星期评分会更新、未访问星期没有详情/评分请求、关闭后不再轮询、A慢/B快不串结果、失败恢复、showScore 关闭时零评分网络调用。不要只用“存在 mikanScores 字符串”的源码测试代替行为测试。

## 6. 前端资源与轮询（T3）

### 6.1 从 import 边界减少首屏

1. router 五个页面改为 `component: () => import('...')`，路径、hash router、startupPage 白名单保持不变。
2. MainView 对主布局按登录后需要加载；避免其他静态 import 把整条页面依赖又拉回来。
3. 添加弹窗、播放器、Markdown/关于、重设置子页在第一次打开时才挂载与 import。仅写 defineAsyncComponent 但始终渲染仍会立即加载；需 v-if + 首次加载标记。
4. 处理 ref.show 调用竞态：建议父层 await import/nextTick 后调用 show，或改为明确 open prop；选择一种并写组件测试。不得用任意 setTimeout 等组件出现。
5. 保留 KeepAlive 的页面状态语义，不能懒加载后丢失返回时的订阅筛选条件。
6. 图标最后处理：扫描模板字符串 icon="X"、动态配置图标名及脚本直接导入，建立实际使用的显式 registry。直接组件引用保持局部导入。未知动态名使用已有通用图标兜底，并把发现项列入测试；不能只删除 `import *` 使按钮空白。
7. 本阶段保留 Element Plus 样式策略，避免同时调整全量 CSS 和自动样式引入；包体证据表明 CSS 是主要瓶颈时另开小提交。
8. vite 继续默认 graph splitting，不恢复手工 Element Plus/vendor 切块。测试生产构建而非只看 dev server；验证登录、路由导航、首次打开弹窗都无循环初始化错误。

### 6.2 Dashboard 轮询

将 setInterval 改为完成后 setTimeout(5000)，设置 active/visible 与 inFlight，首次 loadAll 与 polling 复用同一个 loadTorrents 入口。手动刷新在已有读取中等待同一个 Promise，不重复发请求。

deactivated/unmounted/visibility hidden 时清 timer 并使结果 generation 失效；支持 signal 时取消当前读请求。重新激活只立即刷新一次，再恢复计时。轮询失败静默记录页面状态，不每 5 秒弹窗；手动刷新失败仍可提示。不给普通业务写请求添加此去重逻辑。

### 6.3 静态资源缓存

在 WebFilter/WebMvcConfig 的现有资源链内调整：内容带 hash 的 assets JS/CSS/图片可长缓存；index.html 与 OAuth HTML 用可重新验证策略；用户自定义 CSS/JS 不视为不可变资源。增加 webp/字体等实际构建类型支持，以实际输出清单为准。

保留 gzip 资源协商，验证 Content-Encoding 与 Vary: Accept-Encoding。不要仅生成 .gz 却不给浏览器使用。API/config/auth 错误响应不得继承静态缓存策略。部署升级后旧 HTML 不应永久指向已经不存在的 chunk。

### 6.4 订阅刷新：减少固定等待并显示真实结果（T3b）

本任务属于第一轮性能修复，缺集补全行为保持，不等待 T7 才处理。改动 `RssTask`、`SubscriptionDownloadQueue`、`TorrentUtil`、`DownloadService`、`ListAni`、`AniController.listAni`、`SubscriptionView/SubscriptionListView`。

**先修只读下载器查询：**

1. 移除 `getTorrentsInfosResult` 在每次读取前无条件 sleep(1000)。当前方法的延迟同时影响 RSS 周期和下载器视图，不是只保护写操作。真正请求限速由周期读取复用控制；本提交不删除 TorrentUtil 的添加、删除、重命名后等待。
2. `downloadAni` 获取 `DownloaderResult`，失败时退出该订阅本轮并记录故障，不再以 `[]` 进入缺集判断、归属 reconciliation 或自动下载。对 UI 可显示最后一次成功快照及错误状态，但业务决策不能把旧快照当实时事实。
3. 给同一 RSS worker 引入一个小型周期上下文（普通内部类即可）：当前下载器身份、最后成功快照、读取时间、dirty 标记。按需获取，最大复用 5 秒；add/delete/move/rename 等影响决策的写入后 dirty=true，下次决策前重新取。配置切换立即失效，失败不缓存成功空值。不要把快照放全局永久缓存。
4. 同一轮只读订阅不会每个都拉完整任务列表。完成态可能在周期内变化，所以必须有 5 秒上限；下载数量计算要计入当前轮已成功新建任务，不能用旧 count 导致突破 downloadCount。如果写路径难以完整标脏，先仅完成第 1/2 步，列出遗漏写路径后补齐第 3 步，不提交不完整快照缓存。
5. `downloadAni` 全局写串行暂保留；不要直接去掉 LOCK 后 parallelStream，这会与缺集补全、洗版、文件变更竞态。后续可把纯 RSS 抓取搬出写锁，但需把解析结果与订阅版本绑定并在提交前重校验；本轮不是必做项。
6. RssTask 的每订阅 500ms 和备用 RSS 的 1 秒延迟第一轮保留，作为单独基线。若进一步减少，改为按源 host 的最小间隔并测 429，不简单全部删除。用户希望更快不代表允许加大源站并发。

**再修刷新反馈：**

1. `SubscriptionDownloadQueue` 暴露只读状态快照 `running`，RssTask 附加本轮 `failedCount` 和 `finishedAt`；都只驻内存，不增加数据库表或任务状态机。队列清空后状态才变 idle；登录失败/被停止计入异常结束，不能当正常刷新完成。
2. 在现有 `ListAni` 响应新增可选 `refresh` 对象，旧客户端忽略它。`listAni` 只读内存状态，不能为获取状态触发下载器登录/网络请求。
3. UI 受理后显示“刷新进行中”；每次 listAni 完成后等 2 秒再查，直到 refresh.running=false，最终数据显示本次已完成变更。idle 且 failedCount>0 提示“刷新结束，部分失败”，不说全部成功。
4. 每次 getList 返回 Promise，刷新期间更新列表不清空旧列表、不重置筛选或滚动位置；用 generation 防竞态。手动重复点击在 running 时禁用；不因客户端超时重发 refreshAll。
5. 只在用户发起刷新或进入页面发现 running 时跟进；隐藏/停用停止页面请求，恢复可见再查询一次。120 秒后停止自动查询并显示“后台仍在运行，可查看日志/更新状态”，不宣称失败。普通定时 RSS 不让前端永久 2 秒轮询。
6. `listAni` 当前会给 runtime Ani 写 sort/pinyin/weekLabel；改为响应快照上计算展示字段，不能让只读列表轮询反复重排/修改下载运行对象。先使用已有 snapshot 方法，必要时缓存 title→拼音的纯计算结果，禁止缓存整份含业务状态的 ListAni 长达数分钟。

验收：受理与完成区分清楚；后台结束后可见页面 2 秒加一次本地请求耗时内更新；100 个启用、无写入订阅的夹具不再有 getTorrentsInfosResult 造成的固定 100 秒等待；慢 RSS/下载器失败不会退出登录或触发重复缺集下载；完成数/错误状态不需要新持久化机制。测试计时优先注入 sleeper/clock，不实际让单测睡 150 秒。

## 7. 公共封面从双请求改为可缓存单请求（T4）

### 7.1 分两步实施，避免重写取图网络栈

第一步先修浏览器缓存与 URL→ID 复用，建立用例；第二步迁移公共封面到单 GET。第一步是可回滚过渡提交，不是最终交付。最终删除公共图片的前端解析队列，不同时改 SafeImageFetcher 的 DNS/代理底层。

### 7.2 最终契约

新增 `GET /api/v2/images?url=<URL编码的公开图片地址>`，与现有 POST 集合端点及 GET /{id} 可并存。继续要求有效会话；不把接口变成无认证的公共代理。前端 SafeImageView 直接生成该 URL，使用原生 loading=lazy、decoding=async 和明确宽高。优先不再用 IntersectionObserver；若浏览器原生加载在现有滚动容器下验收不满足，再保留一个简单 observer，不能双重排队。

该 GET 仅用于公共封面：不发送网站账号 Cookie/Authorization，不接受 userinfo，不承载私有带凭据图片。带签名/令牌的私有地址继续现有 POST 会话路径，不因命中共享缓存绕过范围限制。公开来源先覆盖蜜柑/Bangumi/AniBT/AnimeGarden 的真实响应；允许来源规则随管理员配置的 Mikan host 更新，不另加面向用户的复杂策略 UI。

现有 SafeImageFetcher 的 scheme、重定向每跳地址校验、私网显式允许、代理配置、最大字节/类型检查保留。不要恢复已 410 的旧 proxyImage 绕开它。公共 URL 查询字符串避免进入业务日志，反向代理部署说明提醒该路径的 access log 脱敏即可，不搭建通用日志系统。

### 7.3 缓存模型

改 ImageCacheService，把“会话引用”与“公共内容”分离；公共内容键为完整规范 URL 的 SHA-256。去掉 fragment、规范 scheme/host/default port，保留 path 和 query 的全部语义，不删除 query 参数、不重排可能参与签名的参数。私有路径不共享。

公共缓存默认保持内容新鲜期 2 小时，浏览器 `Cache-Control: private, max-age=3600`，返回内容 hash ETag；先认证，再处理 If-None-Match。相同 URL 内容变化后 ETag 变化；手动刷新封面应使对应条目失效，不添加每次渲染变化的随机 t。

缓存文件名固定由 key 生成，元数据记录 contentType、etag、fetchedAt、expiresAt、length，不保存会话 token。为了重启复用，选一个专用小 manifest 文件，原子写入；不把图片内容塞 SQLite，不再建设新仓储体系。单 writer 串行写 manifest，网络 I/O 不持有 manifest 锁；如尚有旧随机文件，不自动把它们当作已验证公共缓存。

容量初值：5000 项、256MiB，任一超限按最旧 fetchedAt 淘汰。定时 10 分钟低频清理，启动时仅核对专属缓存目录元数据，不递归扫描配置/下载目录。旧无索引文件输出具体路径清单由用户手动清理，遵守禁止批量删除的规则。产品运行期已有缓存淘汰是否可保留，应与用户规则明确区分：实施代理不能执行批量清理；如用户要求产品本身也禁止批量删除，则该维护功能只产生待清理清单，不自动删除。

每 key 使用共享 Future 合并在途加载，先 putIfAbsent 决定唯一生产者，在提交失败/成功/异常的所有路径完成 Future，并用 remove(key, sameFuture) 收尾。不要持有按 hash 分片的 monitor 去做 10 秒网络操作，避免不同 key 因哈希碰撞互相阻塞。

外部图片最多 6 并发、48 排队；饱和返回可重试错误与 Retry-After，不在 servlet 线程无上限等待。短失败缓存 30 秒，成功不缓存异常；前端只显示占位与手动重试，不对每张失败图无限重试。byte 限制延用 10MiB，无 Content-Length 也计数中止。

### 7.4 与会话旧接口的兼容

旧 POST 和 /{id} 暂保留用于历史客户端及私有路径，可让公共图旧引用指向同一公共 blob，但旧引用的会话检查不变。确认仓库内无公共路径消费者后，仅删除前端 `cacheImage` 公共队列；不要立即删除服务端接口。后续有明确版本淘汰窗口再处理。

测试：相同图多组件/多会话外部下载一次；认证缺失不返回缓存；304 无实体；过期重新加载；重启复用；配置 host 切换；非法类型/超大体积/重定向私网被拒绝；代理模式仍能加载；缓存写失败不会产生成功索引；不存在的元数据文件不会导致服务无法启动。

## 8. 收敛评分与列表后端（T5）

### 8.1 PublicScoreService

目标保留一个评分调度池、两种业务 key（mikan映射/bgm评分）、两个业务缓存；不再同时维护同步批处理线程池和后台预热线程池。

实施顺序：先新增 cache-only/read-and-schedule 两个清晰入口并迁移消费者，再删除没有调用点的 `getMikanScores/getMikanScoreLookup/getCachedMikanScores/warmMikanScores` 或内部重复代码。`getBgmScores` 仍被 AnimeGarden 使用，按 8.3 迁移后才可删同步调用路径。

一个固定 4 工作者 ThreadPoolExecutor + ArrayBlockingQueue(96)，AbortPolicy。任务粒度为一次映射请求或一次评分请求。映射成功后提交评分任务，但工作者不得同步等待自己池内的子任务，否则会饥饿死锁。队列拒绝要清理 flight 并把该 ID 留在 retryable；后续页面轮询可以重新安排。不要 CallerRunsPolicy 把外网访问搬回列表 HTTP 线程。

排队截止时间在 enqueue 时记录，用单调时钟；启动时过期直接结束并释放 flight。单次 HTTP timeout 延用 4 秒；每 ID 一次正常请求，现有备用数据源最多一次 fallback，计入同一个明确预算。429/超时短冷却先用 30 秒（429 尊重有界 Retry-After），不要现有 500ms 冷却触发持续打源站。

负缓存区分：“明确不存在/确实没评分”与“网络失败”。前者可 10 分钟；后者短失败冷却，不把不可达固化成 score=0 的长期成功。分数 0 是可表达结果，不代表异常。映射缓存沿用 14 天持久 TTL、评分统一 6 小时新鲜期；读取现有 12 小时持久评分时按新策略取较早过期时间，不清库。未来若保留旧分数，需把 stale 状态和绝对过期时间明确写进模型，不能无限续命。

保留批量持久缓存读取，禁止每条 ID 再同步读 SQLite。持久写复用现有有界单 writer，容量 256，队列满只丢可重建缓存写，不影响内存成功或业务数据。此 writer 不属于网络调度池，不能因“一个池”要求把慢 SQLite 与网络再次耦合。不要让缓存失败导致下载/配置库重置。

### 8.2 MikanService 列表

保留：季节 10 分钟、搜索 45 秒、季节 stale 最多 1 小时、刷新 1 worker/8 queue。移除相邻季预取和整季评分预热。

缓存 key 包含 Mikan host、搜索 text、season 参数；默认季度在跨季度时不能无限沿用空 season 的陈旧数据。exists 由当前订阅实时覆盖，缓存不可存用户专属订阅标记。只在响应边界拷贝一次可变列表，不同时为读缓存、持久化、评分补齐反复 Gson 往返。

当前 listLoadLocks 在 finally 删除 monitor，失败场景中“等待旧 monitor 的线程”和“拿到新 monitor 的线程”可能并行重试。改成 per-key Future，冷请求等待同一个 Future；stale 请求直接返回旧值且只 schedule 一个 Future。未来外部失败不得留永不完成的 Future。加屏障并发测试证明失败后仍只存在单个生产者。

### 8.3 AnimeGarden

默认列表先取 subjects，封面索引与评分不再串行阻塞它。CacheService 的公共封面索引增加专用内存缓存（1 小时、最多 1 个索引快照）和单次在途复用；失效可以先返旧快照后台刷新，不借用全应用泛型缓存架构。

`AnimeGardenService.list` 返回缓存已有封面/评分，没有则返回占位，subjects 的 exists 仍动态计算。给当前列表补载增加一个具体端点 `POST /api/animeGardenEnrichment`，请求为最多 48 个 bgm subject ID，响应 `{subjects: {id: {cover, score}}, retryableSubjectIds: []}`。端点只接受数值 ID，并只查已加载 subjects 集合中的条目；复用评分调度与封面索引，禁止每 ID 重新下载整个 cover 索引。

前端当前星期补齐，生命周期、20 秒上限和不自动重新排序复用蜜柑的简单计时工具；不抽象统一的“数据源插件”。若业务特别依赖按评分排序，提供用户主动排序或在第一次补齐结束、尚未交互时一次排序，具体行为须与用户确认，默认不让列表跳动。

测试：封面请求超时、评分超时均不阻塞 subjects；当前分组最终有资源；未访问分组不发映射请求；缓存失败时标题仍可用。

## 9. HTTP 与安全冗余的具体边界（T6）

### 9.1 本轮允许精简

- 重复的前端图片排队/解析请求、整季/邻季预取、未使用的旧同步评分入口、过期注释。
- api.js 重复错误构造/重复刷新行为，但保留明确错误码。
- SafeImageFetcher 内“只为自写格式尺寸解析存在”的方法可在后续单独评估：只有替代实现支持当前 JPEG/PNG/GIF/WebP/BMP 且有坏输入测试后才能替换。第一轮不重写其 861 行网络/格式逻辑，不能为减少行数放弃 MIME/字节边界。
- AuthService 旧登录迁移分支可单独列出仍支持的升级来源，用户确认淘汰后移除。保留 HttpOnly 会话、CSRF 和账号变更后会话失效。

### 9.2 不应错误归类为冗余

PathPolicy 阻止越界、归属检查阻止误删其他下载任务、原子写入防半写、恢复回滚防数据中断、Cookie 作用域隔离防下载器凭据串用，都有具体业务结果。不能一律回退到上游代码。

`HttpReq` 的 generic requests 不带 Cookie；下载器自己的 ScopedCookieJar 保留。图片已使用池化 Apache 客户端，不得用“未复用连接”作为无证据重写理由。其他 Hutool 请求是否复用 TCP，需要连接计数确认，不能因为每次构造 HttpRequest 就断言每次新建 TCP。

暂只做请求域内改进：公开元数据限制并发、合理超时、可选内容不阻塞、相同 key 去重。下载器 POST、文件移动、通知发送不能套读请求重试策略。连接超时后的写操作可能已经执行，重复提交必须由当前业务幂等逻辑处理。

## 10. 有状态模块的后续收缩（T7，数据格式保持）

这是独立阶段，不是性能修复完成的前置条件。不得为减少 diff 直接删除 ownership/recovery/completion/persistence/backup 目录。

### 10.1 先记录依赖与费用

在 `docs/stateful-simplification.md` 列出：数据库表→Repository→Service→入口，所有状态→进入条件→退出条件→是否可恢复，下载器网络调用点、文件扫描点、全局锁包围范围。覆盖 Runner、DownloadService、MissingEpisodeRecoveryService、OwnershipService、QuarantineService、SubscriptionDeletionService、CompletionMigrationService、RestoreService。

采样记录一次 RSS 周期：下载器 list/files 调用次数、每订阅 DB 查询数、文件 walk 次数。定位网络调用是否位于 DatabaseManager 全局锁之内。不能把真实网络/文件操作放进仅有名称好看的“事务抽象”。

### 10.2 可执行优化

1. 同一轮下载器快照在当前周期复用，按配置身份隔离；只在明确写操作后失效。不要跨周期永久缓存任务列表。
2. 同订阅批量查询归属及恢复记录，消除 N 次相同 DB 查询；保留唯一键、状态转换与提交顺序。
3. 文件存在性与路径检查在同一个操作快照内复用；真正执行写/删/移动前仍验证目标，不能用长 TTL 文件缓存决定数据删除。
4. 完结迁移已落库且终态的记录不每轮重新做远程扫描；启动时仍恢复非终态。用测试证明断电续跑而不是删恢复分支。
5. 不改变持久状态枚举和值；暂把无法证明必要但已有持久状态标为后续候选，不删除数据库行。

### 10.3 必须保留的行为测试

- qB 删除已完成任务但媒体还在，多次 RSS 刷新不重下。
- 本地文件缺失/下载器不可达两种情况不同：不可达不能推断缺集并发起恢复。
- 正常提交与恢复提交同一集，只允许一次实际下载。
- 改下载路径/移动后再刷新，归属正确且不重复下载。
- 删除订阅且 deleteFiles=false 保留媒体；true 仅影响当前订阅归属范围；共享目录/其他任务不受影响。
- 洗版、备用 RSS、合集、多文件 torrent、完结迁移仍按现有契约运行。
- qB/Transmission/Aria2/OpenList 的故障不伪装成“空列表成功”；单个故障不阻塞无关的只读页面。
- 恢复中断能回滚或明确进入 MAINTENANCE_REQUIRED，不假装成功后继续任务。

沿用现有 DownloaderDeleteContractTest、DownloaderFailureContractTest、QBittorrentAuthenticationContractTest、TransmissionContractTest、OpenListWorkflowTest、MissingEpisodeRecoveryServiceTest、OwnershipServiceMoveTest、SubscriptionDeletionServiceTest 等。修改失败测试前先判断它是在保护用户行为还是镜像旧实现，不能为了降低代码量删除行为测试。

整体移除状态机不在本次确认范围；只有用户今后改变范围时才能另立计划，且仍须保留缺集补全能力。该新计划必须提供旧数据读入、映射表、迁移备份、双向兼容窗口和回滚读取能力。本文不包含也不授权该迁移。

## 11. 修好验证和上游同步方式（T8）

### 11.1 可运行的最小验证体系

恢复前端真正执行的 `test`（Vitest + Vue Test Utils + 必需 DOM 环境）及 `test:watch`；覆盖本文交互契约。先不恢复全仓 TypeScript/typecheck、全套 lint 规则与不相关依赖。verify 脚本删除不存在的调用，或仅在已建立对应工具时调用，绝不能用空脚本返回 0。

新增轻量 `check:bundle`：读取 Vite manifest/构建输出，递归计算登录与首个路由实际依赖的 JS/CSS gzip 字节；限制禁止首屏出现播放器等模块。基线版本和阈值写入文件，禁止检查失败时自动更新基线。保留一次生产浏览器冒烟，捕获 pageerror 和 chunk 404。

Java 保留现有 JUnit 及 ci profile。日常 PR 门禁跑后端相关测试、前端测试、构建、关键页面冒烟；完整后端回归在合并前完成。SpotBugs/SBOM/依赖审计可单列任务，安全审计低级别项不阻塞每一次本地性能迭代；发布门禁另行维护。不要为了让扫描绿而增加大范围 exclude 或无关依赖覆盖。

新增 PR 只验证 workflow，不能沿用会登录注册表和 push 镜像的 build-test 作为唯一 PR 检查。已有发布工作流保留独立手动/发布事件，不在本轮触发。

### 11.2 命令要求

PowerShell 不拼接命令，使用原生命令参数。先确认本机/仓库工具版本与实际 lockfile。示意命令（不是本次已执行记录）：

```powershell
git status --short
git diff v3.2.28 HEAD --stat
pnpm --dir ani-rss-ui install --frozen-lockfile
pnpm --dir ani-rss-ui test
mvn -B -pl ani-rss-application -am -Dskip.frontend=true test
```

单模块 Java 测试若需要 UI 工件，先在隔离输出构建该工件，不能假定 reactor 自动满足所有资源依赖。完整 verify 前检查 UI POM `prepare-package` 的 clean 绑定：当前会批量清理嵌入资源。遵守用户规则，应在新的隔离构建目录完成、禁用该清理执行并证明输出目录为空，或请用户手动清理。`vite build` 默认可能清空旧 dist，应使用新的 outDir 并禁用 emptyOutDir；不直接运行现有 verify 脚本绕过限制。

最终记录每个实际执行命令和结果。只运行了单测不得写“生产验证通过”；构建失败的外部依赖问题标为未验证，不能跳过后称完成。

### 11.3 重写 UPSTREAM_SYNC.md 的第 3 条

现有“保留本地安全、兼容性和性能改动”过于笼统，改为下面规则，并建立 `docs/fork-contracts.md`：

1. 比较正确上游标签范围；同时审查目标上游与 fork 的最终文件差异，不能仅看提交标题。
2. 按能力映射：上游旧入口→新入口→fork API→对应行为测试。目录改名时仍迁移能力，不能只解决文本冲突。
3. 每项 fork 差异标记保留/被上游等效替代/明确淘汰/待确认，并写依据。不强制保留所有历史补丁。
4. 同步 UI 时同时检查 http.js 使用点、v2/legacy 响应、媒体句柄、恢复状态与 package scripts；任何孤立 API 都要解释是否兼容用途。
5. 同步构建时比较首屏模块图、包体预算和生产冒烟；保留白屏回归用例。
6. 同步下载/清理功能必须运行第 10.3 节行为用例。
7. 版本四段编号沿用现行规则；当前优化不必夹带 v3.2.29。后续同步自定义封面点击功能时，特别检查本地封面/远程封面/播放器句柄三个分支。
8. 每次同步提交附一页验收记录：功能、网络次数、首屏包、已验证/未验证项目。不能以“mvn 编译成功”代替页面正确。

## 12. 交付拆分、依赖与回滚

| 提交任务 | 前置 | 主要交付 | 完成条件 |
|---|---|---|---|
| T0 基线与测试基础 | 无 | 基准说明、HTTP fixtures、最小前端 test | 当前问题有失败用例/可复现记录 |
| T1 契约修复 | T0 | 播放/外部播放/字幕、恢复状态、API 错误 | F01/F02/F11 对应用例通过 |
| T2 蜜柑生命周期 | T0/T1 API | 首组评分补载、竞态修复、移除无用预取 | F03/F04/F16 修复；非需求请求=0 |
| T3 首屏与轮询 | T0 | 路由/弹窗 lazy、轮询去重、静态缓存 | 生产无白屏、慢下载器并发=1、包体证据 |
| T3b 订阅刷新 | T0/T1 API | 删除无条件读取等待、短周期快照、刷新真实状态 | 固定等待减少、故障不当空列表、缺集补全不回退 |
| T4 图片 | T0/T1 API | 两步图片缓存迁移 | 单 GET、重用/重启/错误/权限测试通过 |
| T5 后端列表/评分 | T2 | 有界池、取消同步列表补齐、AnimeGarden enrichment | 冷标题独立、任务有界、失败可恢复 |
| T6 有证据的冗余清理 | T1–T5 | 无调用死代码/注释、接口兼容清单 | 不增加行为差异，现有用例通过 |
| T7 有状态热点收缩 | T0 测量 | 批量查询与周期快照，保持格式 | 第 10.3 全通过；无测量证据则报告无需修改 |
| T8 持续验证与同步规则 | 测试框架先建，其余持续更新 | PR checks、fork-contracts、同步规则 | 合并前全套回归与生产冒烟完成 |

一个提交只解决一组行为，不要把鉴权重写、数据库迁移混入图片优化。可分别验收 T1/T2/T3，不等待状态机制重构才能交付修复。每步写清改动文件、前后行为、测试、测量和剩余限制。

回滚：优先 revert 当前功能提交并保留数据；前端/后端耦合改动同一发布单元交付。图片旧接口保留提供兼容，公共缓存损坏可被忽略重建，不能删除业务库。涉及数据库 schema 的新计划不得沿用“直接回滚二进制”假设。测试快照不能包含用户秘密。

## 13. 交给 Luna 的执行指令

1. 阅读本文基线，重新核对 HEAD 与工作区。如果 HEAD 变化，增量重审涉及文件并更新证据；不要按旧行号盲改。
2. 先完成 T0 最小夹具和失败用例；再按任务顺序实施。每个任务结束都给出具体证据，不一次性提交全仓重构。
3. 本文默认值可用于首次实现；D2 已确认先性能后精简且保留缺集补全，不重复询问。D3 涉及新增数据行为才需确认；没有答案时维持当前行为并继续独立性能修复，不自行扩大范围。
4. 复用已有 API/服务/测试；只有本文明确列出的两个具体补载/图片契约允许增加接口。其他新接口先说明旧接口无法满足的原因。
5. 代码比原来更短不是验收。需要证明：少发了什么请求，哪个阻塞被移除，哪些数据行为保持。
6. 不删媒体/配置、不触发真实恢复/下载、不运行会发布镜像的验证脚本；遵守禁止批量删除与 PowerShell 参数规则。
7. 最终交付更新基线报告、fork-contracts、UPSTREAM_SYNC、CHANGELOG/UPDATE（按仓库实际使用）、测试记录；列明未验证真实环境，不用“全部优化完成”掩盖待确认项。
