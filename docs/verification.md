# Fork v3.2.28.63 验证记录

本记录严格按 `FORK_V3.2.28.62_OPTIMIZATION_PLAN.md` 执行；附带开发书是验收规范，不是额外的生产授权。当前发布单元的行为代码提交为 `f550cdddf57be2bf73185327e003aa2cbce18fbc`，版本提交为 `0c625810`。真实账号、生产下载器、用户媒体和外部数据未连接；合成 fixture 不冒充生产验证。

## 当前发布单元：3.2.28.63

### 门禁与构建

- 前端 Vitest：5 个测试文件、33/33 通过。
- 生产 UI 构建：`pnpm exec vite build --outDir target/n08-dist-20260907-c --emptyOutDir false`，使用唯一目录且不清空历史输出；构建通过。
- bundle 门禁：static entry JS/CSS gzip 为 `105,198/48,953 B`；login `201,954/63,229 B`；home `192,174/61,822 B`；subscriptions `185,253/63,645 B`，均通过现有预算；`forbiddenModules=[]`，未新增首屏闭包。
- Java 完整门禁：`mvn -B -Pci -Dskip.frontend=true -Dfork.w6.output=docs/performance-data/w6-rss-v3.2.28.63-20260907.json verify`；311 tests、0 failures、0 errors、4 skipped；JaCoCo 达标，SpotBugs `BugInstance size is 0`，SBOM 生成成功。

### N01–N08 验收矩阵

| 工作包 | 状态 | 证据与边界 |
| --- | --- | --- |
| N01 无评分负缓存 JSON 安全 | 通过 | `PublicScoreServiceTest`、`MikanServiceTest`、`BgmInfoJsonTest`；有限评分、合法 0、无评分 `null` 和 retryable 分支均有覆盖 |
| N02 补载 pending 与手动重试 | 通过 | `mikan-loader.test.js`、`list-lifecycle.test.js`、Mikan/AnimeGarden 实际 View 生命周期；当前组显示 pending 并只重试剩余 ID |
| N03 AnimeGarden 上下文失效恢复 | 通过 | `AnimeGardenServiceTest`、Controller 409 `ANIME_GARDEN_LIST_EXPIRED`、513 条列表和一次受控重载测试 |
| N04 图片本地 busy 不污染源站冷却 | 通过 | `ImageCacheServiceTest`；本地读取争用使用短 Retry-After，不写入 public failure TTL |
| N05 图片 single-flight 二次检查 | 通过 | `ImageCacheServiceTest` 并发屏障用例；miss 交错仍只产生一次外部加载 |
| N06 manifest、旧文件追踪与 shutdown | 通过 | `ImageCacheServiceTest`；合并 writer、pending deletion、有界重试、排队 Future 完成和幂等关闭 |
| N07 RSS 统一时钟与写失效 | 通过 | `TorrentSnapshotCycleTest` 精确覆盖 5s 边界/dirty；真实 `RssTask → SubscriptionDownloadQueue → DownloadService → TorrentUtil/ItemsUtil` 见 W6 报告 |
| N08 8 秒慢轮询与页面恢复 | 通过 | 生产构建浏览器报告 page dwell `39,025ms`；每次 `8.010s`，hidden/visible 总请求 3，`maxInFlight=1`，手动刷新与可见恢复均已观察 |

### W6/W7 原始证据

- RSS 100 订阅报告：[`w6-rss-v3.2.28.63-20260907.json`](performance-data/w6-rss-v3.2.28.63-20260907.json)，`commit=f550cdd...`、`dirty=false`；RSS 请求 100、downloader connect 1、list 10、失败 0、snapshot hits/misses `90/10`、跨 5s 后读取 9 次、虚拟节流 `50,000ms`、墙钟 `1,177ms`。
- RSS 实际 add 报告：[`w6-rss-add-v3.2.28.63-20260907.json`](performance-data/w6-rss-add-v3.2.28.63-20260907.json)，同一真实服务链中 RSS 请求 1、`downloaderAddCalls=1`、结果 `SUCCESS`。
- Service/热列表报告：[`w6-services-v3.2.28.63-20260907.json`](performance-data/w6-services-v3.2.28.63-20260907.json) 与 [`w6-hot-list-v3.2.28.63-20260907.json`](performance-data/w6-hot-list-v3.2.28.63-20260907.json)，均 `dirty=false`；热列表 cold `90.6249ms`、进程内 hot p95 `0.0395ms`、重启持久缓存 `0.2971ms`。这些是 Service 计时，不是端到端 HTTP p95。
- 浏览器报告：[`n08-browser-v3.2.28.63-20260907.json`](performance-data/n08-browser-v3.2.28.63-20260907.json)，生产 Vite 输出、`dirty=false`；`pageErrors=[]`、`consoleErrors=[]`、`chunk404s=[]`、`mediaErrors=[]`，`visibleRecoveryObserved=true`。

### Java 跳过项

下列 4 项被 JUnit Assumptions 跳过，未隐藏在总数中：

| 测试 | 状态 | 原因 |
| --- | --- | --- |
| `ani.rss.commons.PathPolicyTest.rejectsSymbolicLinkComponents` | 未验证（原因） | 当前环境无法创建 symbolic link，测试显式假设 `symbolic links are not available` |
| `ani.rss.ownership.QuarantineServiceTest.refusesFilesReachedThroughEscapingSymbolicLink` | 未验证（原因） | 当前环境 symbolic links unavailable，测试显式 abort |
| `ani.rss.service.RestoreServiceTest.importsConfiguredRealUpstreamBackupSampleEndToEnd` | 未验证（原因） | 未提供 `-Dani.rss.upstreamBackupSample` 外部兼容样本 |
| `ani.rss.service.SubscriptionDeletionServiceTest.retainsSymbolicLinkThatUsesAGeneratedMetadataName` | 未验证（原因） | 当前环境 symbolic links unavailable，测试显式 abort |

### 当前未验证边界

- 真实 Mikan、Bangumi、AnimeGarden、图片代理和下载器的外网波动、认证、429/5xx、生产首屏和真实媒体解码。
- 真实账号、生产数据库锁/查询、用户媒体目录、文件 walk、下载器 files/move/rename/delete 和完整缺集恢复成本；W6 JSON 对未测字段保留 `null`。
- Docker Desktop/Linux engine 本地构建；镜像发布将以精确 tag workflow 的实际 digest/platform 结果为准。Docker Hub 是否发布取决于仓库凭据。

## 历史 v3.2.28.62 验收记录

### 已通过

- 代码基线：行为与测试提交 `19dfaf3a`，版本提交 `674f7b55`，最终浏览器证据提交 `4df5e7e2`。
- 前端 Vitest：4 个测试文件、27/27 通过，覆盖批量边界、部分对象、retryable、取消/恢复/重新查询和播放/恢复组件契约。
- 前端生产构建：使用唯一输出目录 `ani-rss-ui/w7-dist-20260907-f`，未运行 `clean`，构建通过。
- 静态 bundle 门禁：本地 Windows 构建的 `staticEntryClosure` JS `105,194`、CSS `48,953` gzip bytes；登录 `201,949/63,229`，首页 `192,168/61,822`，订阅 `185,246/63,645`，均在预算内。GitHub Actions Ubuntu run `34077134774` 观测到静态 JS `105,201`，因此门禁上限仅按该跨平台差异调整到 `105,201`；没有新增闭包或禁用模块。播放器、Markdown、备份模块未进入三条首屏闭包。
- Java 完整门禁：`mvn -B -Pci -Dskip.frontend=true verify`；302 tests、0 failures、0 errors、4 skipped，JaCoCo 达标，SpotBugs `BugInstance size is 0`，SBOM 生成成功。
- W6 Service fixture：96 条 Mikan、2 周、最大组 64；AnimeGarden 默认/搜索消费者可独立补载；50 个逻辑图片引用、25 个共享 URL；同 key 版本替换和 lease 流读取通过。
- W6 热列表：冷 `137.579ms`，30 次进程内热调用 p95 `0.0598ms`，重启持久缓存 `0.801ms`；这是 Service 计时，不是端到端 HTTP 延迟。
- W6 RSS 链路：实际 `RssTask → SubscriptionDownloadQueue → DownloadService → TorrentUtil/ItemsUtil`，100 个启用订阅全部 `SUCCESS`，RSS 请求 100、downloader connect/list 各 1，虚拟节流 `50,000ms`，实测墙钟 `1,226ms`。
- W7 浏览器：[`w7-browser-v3.2.28.62-20260907.json`](performance-data/w7-browser-v3.2.28.62-20260907.json) 在 commit `4df5e7e2`、`dirty:false` 上完成 8/8 场景：登录/首页/订阅 cold+hot、播放弹窗、设置页。所有场景 `pageErrors=0`、`consoleErrors=0`、`chunk404s=0`；HTTP 失败仅为未登录 CSRF/IP-login 的预期 401。
- W7 播放动态资源实际加载 46 个 JS/CSS、设置页 67 个 JS/CSS；播放合成媒体的解码事件单独记录为 `mediaErrors`，不冒充页面脚本错误。
- 发布：tag `v3.2.28.62` 指向精确提交 `cce1190e`，workflow `34077710357` 成功；GitHub Release 已上传 `ani-rss.jar`（SHA-256 `fa4706e7429f7bfc12e24f954068e7d783bb11f4674cf23483eae4d801bf68ca`）和 `ani-rss.exe`（SHA-256 `87db32444a247da78f8a11e6c535588892c04ab09b3acea57ba8856374961b71`）。GHCR 已核验 temurin `sha256:bb33815c239e18b150ec2717400b98b3acfcdca633099a7799e1255a9b011358`（linux/amd64、linux/arm64）、openj9 `sha256:54ec8316c552039a64f698c365816246395fe4aec8c9b3c2a04f322f7008bedd`（linux/amd64、linux/arm64）和 arm32v7 `sha256:4a7ff86fc683cc8476f9582f4682155e75ce3291a54e9640745747781b58fb06`（linux/arm/v7）；Docker Hub 因未配置凭据而跳过。

### 可重复命令

前端：

```text
cd ani-rss-ui
pnpm install --frozen-lockfile
pnpm test -- --run
pnpm exec vite build --outDir w7-dist-<unique> --emptyOutDir false
$env:BUNDLE_OUTPUT_DIR = 'w7-dist-<unique>'; pnpm check:bundle
```

后端（PowerShell 中将每个 `-D...` 参数整体传递）：

```text
& 'C:\Users\tendo\.cache\codex-runtimes\anirss-build\apache-maven-3.9.11\bin\mvn.cmd' '-B' '-Pci' '-Dskip.frontend=true' 'verify'
```

W6 原始报告：

- [`w6-services-v3.2.28.62-20260907.json`](performance-data/w6-services-v3.2.28.62-20260907.json)
- [`w6-hot-list-v3.2.28.62-20260907.json`](performance-data/w6-hot-list-v3.2.28.62-20260907.json)
- [`w6-rss-v3.2.28.62-20260907.json`](performance-data/w6-rss-v3.2.28.62-20260907.json)

### 仍为未验证

- 真实 Mikan、Bangumi、AnimeGarden、图片代理的 200ms/2s/超时/429/500 分布和真实外网波动。
- 真实账号登录、生产首屏 LCP、真实媒体格式解码、Range/字幕/外部播放器完整操作；本轮仅用本地合成接口验证前端链路。
- W6 RSS fixture 使用可解析空 RSS，因此真实 add/download、文件 walk、移动/重命名、缺集恢复路径的运行时计数仍为 `null`，不能从 100 个订阅推导。
- 真实下载器、生产数据库锁等待、DB 查询数和用户媒体目录恢复；W6 原始 JSON 已明确标记未测字段。
- Docker Desktop/Linux engine 本地构建仍未验证；多架构 GHCR 镜像和 GitHub Release 已以精确 tag workflow 实际结果核验，Docker Hub 因未配置凭据未发布。

计划状态只使用允许值：通过、进行中、失败、未开始、未验证（原因）。

| 验收项 | 状态 | 证据/边界 |
| --- | --- | --- |
| 部分资源继续补载 | 通过 | Java/前端契约测试 |
| 快批次立即更新 | 通过 | loader 与两个实际 View 测试 |
| 补载有界 | 通过 | batch≤48、并发≤2、20s 总预算、重试测试 |
| 隐藏恢复不永久 loading | 通过 | Mikan/AnimeGarden 生命周期测试 |
| 多列表互不失效 | 通过 | AnimeGarden 交错消费者测试 |
| 旧图片清理不删新文件 | 通过 | 同 key 锁、lease、替换竞态测试 |
| 失败图片状态有界 | 通过 | TTL、容量、维护和恢复测试 |
| 评分绝对 6h 新鲜期 | 通过 | SQLite TTL 与异步 writer 测试 |
| 实际热列表 p95 | 通过 | W6 30 样本原始 JSON；口径是 Service 计时 |
| 实际 RSS 100 订阅 | 通过 | W6 实际 Java 服务链；空 RSS 限制见上 |
| 实际页面慢轮询 | 未验证（原因） | 未连接真实 8 秒下载器并完成 30 秒生产页面停留 |
| 真实首屏包体 | 通过 | 静态 manifest + 生产 fixture 浏览器 8 场景 |
| 媒体与恢复契约 | 通过 | 合成媒体/恢复组件与 HTTP 测试；复杂媒体格式未验证 |
| 缺集补全与归属 | 通过 | 完整回归 302/0/0/4 skipped |
| T7 DB/network/file 费用采样 | 未验证（原因） | DB、lock、file-walk 计数在 W6 原始报告中保留为 null |
| 发布 | 通过 | `v3.2.28.62` / `cce1190e`；tag workflow `34077710357`、Release 附件和 GHCR 多架构 digest 已核验 |
