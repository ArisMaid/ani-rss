# Fork v3.2.28.66 验证记录

本记录严格按 `FORK_V3.2.28.65_REVIEW_AND_REPAIR_PLAN.md` 执行；附带开发书是验收规范，不是额外的生产授权。v3.2.28.66 保持 v3.2.28 的上游前三段和本地第四段递增规则；真实账号、生产下载器、用户媒体和外部数据未连接，合成 fixture 不冒充生产验证。

## 当前发布单元：3.2.28.66

### 门禁与构建

- 前端 Vitest：5 个测试文件、35/35 通过；现有 `list-lifecycle.test.js` 保持 AnimeGarden 列表过期人工入口、失败重载和生命周期恢复边界。
- 生产 UI 构建：`pnpm exec vite build --outDir target/release-66-dist-20260908 --emptyOutDir false`，使用唯一输出目录且未清理既有输出；构建通过。
- bundle 门禁：static entry JS/CSS gzip 为 `105,198/48,953 B`；login `201,952/63,229 B`；home `192,171/61,822 B`；subscriptions `185,252/63,645 B`，均通过既有预算；`forbiddenModules=[]`。
- Java 完整门禁：`mvn -B -Pci -Dskip.frontend=true verify`；319 tests、0 failures、0 errors、4 skipped；JaCoCo 达标，SpotBugs `BugInstance size is 0`，CycloneDX SBOM 生成成功；`ImageCacheServiceTest` 定向 19/19。

### v3.2.28.65 审查修复验收矩阵

| 工作包 | 状态 | 实现与证据 |
| --- | --- | --- |
| P2-01 AnimeGarden 匹配最终确认 | 通过 | `25b3a05c`；确认入口复核主列表/快照/请求状态，失效时关闭并清空匹配选择；批量入口校验并复制提交快照；前端 35/35 |
| P2-02 pending 预留临界区 | 通过 | `30cdda4d`；预留、move、entry 替换和本次失败撤销在同一 key lock 内排序，既有 pending 不会被失败清理移除；静态交错复核，未故障注入；ImageCache 19/19 |
| P2-03 维护暂停 SOP | 通过 | `30cdda4d`；暂停保持到重启，无关 pending 删除不解除；暂停时先允许热缓存读取，再阻止过期删除/新 fetch，维护只裁剪失败记录；日志和三份合同/SOP 说明已更新 |
| 版本与本地发布门禁 | 通过 | 版本 `3.2.28.66`；本地完整 Java、前端、生产 UI 和 bundle 门禁均通过；远端 tag workflow、Release 与 GHCR 证据已核验 |

### 人工检查与边界

- 人工路径检查：匹配弹窗确认入口只调用 `confirmMatch`，列表失效路径调用 `resetMatchInteraction`；有效列表确认复制 payload 后 emit，避免清理状态覆盖已提交选择。未新增永久测试文件或浏览器矩阵。
- pending 交错验收为代码锁序静态复核，未注入删除失败或并发屏障；现有 19 项测试通过不能宣称新交错已动态覆盖。
- 真实 Mikan、Bangumi、AnimeGarden、图片代理和下载器的外网波动、认证、429/5xx、生产媒体和数据库/文件费用仍未连接。
- Docker Desktop/Linux engine 本地构建、强杀/断电/系统 I/O 阻塞下的 manifest 零丢失未验证；近期封面缓存按方案允许重建，异常清理由维护 SOP 处理。

### 发布状态

- `build-test` run [`34217520584`](https://github.com/ArisMaid/ani-rss/actions/runs/34217520584) 成功；tag `v3.2.28.66` 精确指向 `7110dbc3fb8c2c61a9aed10b25179d5eb3d2d628`，正式 build run [`34218050737`](https://github.com/ArisMaid/ani-rss/actions/runs/34218050737) 成功；[GitHub Release v3.2.28.66](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.66) 已发布。
- Release 附件：`ani-rss.jar` SHA-256 `d4cec166687140c1dbaf9fb9080f84bc834497804b7ed04f9c4e8b5d1a1d0c93`；`ani-rss.exe` SHA-256 `eed79eb382704aa3a351f158a0bcf842f2a9f43ba089da65eb473af4a618cf28`。
- GHCR 已核验 manifest index：temurin `ghcr.io/arismaid/ani-rss:v3.2.28.66` / `sha256:e07a41a796cf18d945426abfb3401d733feb0b0454fba1eb71ce60374d50cc0e`（linux/amd64、linux/arm64）；openj9 `ghcr.io/arismaid/ani-rss:v3.2.28.66-openj9` / `sha256:968509e112362318f1e596362391d14dc7e9d62e878203c3ab4d84f955f6bb00`（linux/amd64、linux/arm64）；arm32v7 `ghcr.io/arismaid/ani-rss:v3.2.28.66-arm32v7` / `sha256:752e2b60fd23a78486ee17b95eda17339eca04ee2e1230875dc86082c6873742`（linux/arm/v7）。
- Docker Hub 登录按 workflow 条件跳过（未配置 `DOCKER_USERNAME`/`DOCKER_PASSWORD`）；GHCR 三组镜像均已成功发布。

## 历史 v3.2.28.65 验收记录

### 门禁与构建

- 前端 Vitest：5 个测试文件、35/35 通过；`list-lifecycle.test.js` 覆盖 AnimeGarden 列表过期人工入口、失败重载和生命周期恢复边界。
- 生产 UI 构建：`pnpm exec vite build --outDir target/release-65-dist-20260908 --emptyOutDir false`，使用唯一输出目录且未清理既有输出；构建通过。
- bundle 门禁：static entry JS/CSS gzip 为 `105,197/48,953 B`；login `201,952/63,229 B`；home `192,170/61,822 B`；subscriptions `185,249/63,645 B`，均通过既有预算；`forbiddenModules=[]`。
- Java 完整门禁：`mvn -B -Pci -Dskip.frontend=true verify`；319 tests、0 failures、0 errors、4 skipped；JaCoCo 达标，SpotBugs `BugInstance size is 0`，CycloneDX SBOM 生成成功；`ImageCacheServiceTest` 定向 19/19。

### v3.2.28.64 审查修复验收矩阵

| 工作包 | 状态 | 实现与证据 |
| --- | --- | --- |
| A/B AnimeGarden 新快照与人工恢复 | 通过 | `2d248fb2`；成功重载清除旧补载/分组/选择状态，过期不再自动 list；前端 35/35 通过 |
| C/D 图片准入与 pending 上限 | 通过 | `6fb81a17`；按实际 bytes 和临时文件预留后写入，先有限淘汰；pending 预留失败暂停新增并保留 manifest 追踪；`ImageCacheServiceTest` 19/19 |
| E 关闭尽力保存 | 通过 | `6abdd335`；final flush 排入已有 manifest executor，清理延迟重试，close 最多等待 5 秒；success 仅表示本次快照写成功 |
| 版本与发布门禁 | 通过 | 版本 `3.2.28.65`；tag `v3.2.28.65` 精确指向 `fa32cb97e74b207b163e902483663e8b10137f12`；正式 build workflow 已完成 |

### 发布状态

- `build-test` run [`34193929884`](https://github.com/ArisMaid/ani-rss/actions/runs/34193929884) 与正式 build run [`34194388721`](https://github.com/ArisMaid/ani-rss/actions/runs/34194388721) 均成功；[GitHub Release v3.2.28.65](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.65) 为非 draft、非 prerelease。
- Release 附件：`ani-rss.jar` SHA-256 `1d0864f0322596e9b87e6211b09b8a248c31b6146e48d2a67d9672fc47ab4427`；`ani-rss.exe` SHA-256 `6f458fdccd567fb7e2f7ed8b137514146d1eb41b98734338b8332f23b9485100`。
- GHCR 已核验 manifest index：`ghcr.io/arismaid/ani-rss:v3.2.28.65` / `sha256:df45b9b853cd84d2ce81215de36a58ee00d5d250bc21b724b10c842e8fcc8331`（linux/amd64、linux/arm64）；`ghcr.io/arismaid/ani-rss:v3.2.28.65-openj9` / `sha256:0d3cec909ba03616b51169303c8132224fe32a528396c0e62934719d94cbf4ee`（linux/amd64、linux/arm64）；`ghcr.io/arismaid/ani-rss:v3.2.28.65-arm32v7` / `sha256:382c7ddb2101bfa15ca6723d5266d1aff197121a59881c3a2e5fa071329fe9be`（linux/arm/v7）。
- Docker Hub 登录按 workflow 条件跳过（未配置 `DOCKER_USERNAME`/`DOCKER_PASSWORD`）；GHCR 三组镜像已成功发布。

### 当前未验证边界

- 未新增浏览器矩阵；W7/N08 只作为既有发布门禁，不能证明 AnimeGarden 过期人工交互的完整生产流程。
- 真实 Mikan、Bangumi、AnimeGarden、图片代理和下载器的外网波动、认证、429/5xx、生产媒体和数据库/文件费用仍未连接。
- Docker Desktop/Linux engine 本地构建、强杀/断电/系统 I/O 阻塞下的 manifest 零丢失未验证；近期封面缓存按方案允许重建，异常清理由维护 SOP 处理。

## 历史 v3.2.28.63 验收记录

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
| v3.2.28.63 发布 | 通过 | tag `v3.2.28.63` / `ea9bde31`；workflow `34098183511`、GitHub Release、GHCR 三类 digest/platform 已核验 |

### W6/W7 原始证据

- RSS 100 订阅报告：[`w6-rss-v3.2.28.63-20260907.json`](performance-data/w6-rss-v3.2.28.63-20260907.json)，`commit=f550cdd...`、`dirty=false`；RSS 请求 100、downloader connect 1、list 10、失败 0、snapshot hits/misses `90/10`、跨 5s 后读取 9 次、虚拟节流 `50,000ms`、墙钟 `1,177ms`。
- RSS 实际 add 报告：[`w6-rss-add-v3.2.28.63-20260907.json`](performance-data/w6-rss-add-v3.2.28.63-20260907.json)，同一真实服务链中 RSS 请求 1、`downloaderAddCalls=1`、结果 `SUCCESS`。
- Service/热列表报告：[`w6-services-v3.2.28.63-20260907.json`](performance-data/w6-services-v3.2.28.63-20260907.json) 与 [`w6-hot-list-v3.2.28.63-20260907.json`](performance-data/w6-hot-list-v3.2.28.63-20260907.json)，均 `dirty=false`；热列表 cold `90.6249ms`、进程内 hot p95 `0.0395ms`、重启持久缓存 `0.2971ms`。这些是 Service 计时，不是端到端 HTTP p95。
- 浏览器报告：[`n08-browser-v3.2.28.63-20260907.json`](performance-data/n08-browser-v3.2.28.63-20260907.json)，生产 Vite 输出、`dirty=false`；`pageErrors=[]`、`consoleErrors=[]`、`chunk404s=[]`、`mediaErrors=[]`，`visibleRecoveryObserved=true`。

### 发布结果

- GitHub Actions build workflow [`34098183511`](https://github.com/ArisMaid/ani-rss/actions/runs/34098183511) 成功，tag `v3.2.28.63` 精确指向 `ea9bde31a7d8440fff2778d995f563ace3c8c857`；[GitHub Release v3.2.28.63](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.63) 已发布。
- Release 附件：`ani-rss.jar` SHA-256 `50c274304cc6673bbcffd17ef8e6c11a31a122534041c61ce9be12d395501ee2`；`ani-rss.exe` SHA-256 `f1ba758e7bdbbad09f782e790e4d3c679b0920e002287c0b51f2b454fd7db013`。
- GHCR 已核验版本 tag 的 manifest index：temurin `ghcr.io/arismaid/ani-rss:v3.2.28.63` / `sha256:4c5b0feb9785dc879b84ccfef8f3463fb84d56cfdd1a3d990a11bae751a84d46`（linux/amd64、linux/arm64）；openj9 `ghcr.io/arismaid/ani-rss:v3.2.28.63-openj9` / `sha256:b59623c65e44bd59a4344eed509086dc502268156ba8a12518b559d9f7e59705`（linux/amd64、linux/arm64）；arm32v7 `ghcr.io/arismaid/ani-rss:v3.2.28.63-arm32v7` / `sha256:35b42191fe094c8f37eb8f7e22adc4e0e02589258fd71a6f75a5badbbe1b1548`（linux/arm/v7）。
- Docker Hub 登录步骤按 workflow 条件跳过（未配置 `DOCKER_USERNAME`/`DOCKER_PASSWORD`）；这不影响 GHCR 发布。Docker Desktop/Linux engine 本地构建仍未验证。

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
- Docker Desktop/Linux engine 本地构建，以及真实外部源、下载器、账号和媒体链路仍未验证；GHCR 的版本 tag、digest 和平台已由上面的 tag workflow 实际核验。

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
