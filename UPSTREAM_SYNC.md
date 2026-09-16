# Fork 上游同步规则

本 fork 当前发布版本为 `3.2.32.69`（拟由 tag `v3.2.32.69` 发布；上一版 `v3.2.28.68` 精确指向 `2587e9bcae147022c7a4b8d2c03c715f83e33bf3`），本轮对照上游 `v3.2.32` commit `b29bd244a082e8f16de57103af388893dbb526f6`，严格落实 [`FORK_V3.2.32_SYNC_AND_SCROLL_OPTIMIZATION_PLAN.md`](FORK_V3.2.32_SYNC_AND_SCROLL_OPTIMIZATION_PLAN.md)，实施记录见 [`FORK_V3.2.32_SYNC_AND_SCROLL_OPTIMIZATION_REPORT_20260917.md`](FORK_V3.2.32_SYNC_AND_SCROLL_OPTIMIZATION_REPORT_20260917.md)。上游只作为局部行为对照，没有整体合并；认证、Cookie + CSRF、SQLite/ownership、缺集恢复、媒体访问、图片缓存和既有构建合同继续以 fork 版本为准。

同步时必须保留以下本地合同：

1. 缺集恢复、SQLite 归属与备份迁移边界。
2. Cookie + CSRF 会话、401/403 语义、内网访问限制和 SSRF 防护。
3. 媒体短期句柄、外部播放器兼容和下载器失败不可伪装为空。
4. Mikan 列表先返回、按需/可取消评分、48 项批量上限、最多 2 个 worker 和 20s 总预算。
5. AnimeGarden subjects 先返回；补载允许部分对象和 retryable；48 项数值 ID 限制；不同消费者互不清空快照。
6. 公共图片认证 GET、跨会话 SHA-256 缓存、ETag、TTL、同 key lock/read lease、失败 30s/5000 上限和 manifest 恢复。
7. 评分 DB freshness 使用 `min(expires_at, updated_at+6h)`；异步写入携带观测时刻计算的绝对到期时间。
8. 懒路由/懒弹窗、静态 hash 资源长缓存与 HTML/API 不缓存；bundle 必须保留静态入口和实际场景两种口径。
9. 隐藏/deactivated 取消当前 generation，恢复后按 needsListReload 重取；备份超时保留 operationId 并提供重新查询入口。
10. 验证和打包流程不得用 `clean` 删除既有输出；发布必须由精确 commit 的 tag 工作流完成。
11. 版本号前三段跟随上游，第四段本地递增，并同步根 POM、模块 POM、前端默认版本和 `UPDATE.md`。

详细合同见 [`docs/fork-contracts.md`](docs/fork-contracts.md)，测量边界见 [`docs/performance-baseline.md`](docs/performance-baseline.md)，验收结果见 [`docs/verification.md`](docs/verification.md)。

## 3.2.32.69 本轮同步与滚动优化记录

- 上游对照：目标为 `v3.2.32` commit `b29bd244a082e8f16de57103af388893dbb526f6`；仅按能力映射局部同步，没有整体合并上游，fork 的 Cookie + CSRF、SQLite/ownership、缺集恢复、媒体句柄、图片缓存、可信代理和构建发布合同继续保留。
- U01 封面动作：已同步为 fork 本地等效实现。`cover-click-action` 仅接受 `edit`/`playlist`/`cover`，无效值归一化为 `cover`；设置页增加“点击封面”，卡片和封面两种布局复用现有编辑/播放/封面事件，标题仍打开 Bangumi，失败占位也可操作，`showPlaylist` 只控制快捷入口可见性；保留 lazy/async/失败/generation 行为并补充键盘可达性。
- U03 种子缓存：已修复同 hash 的常规缓存选择顺序：优先现有 `.txt`，其次现有 `.torrent`，均不存在时才按当前链接类型返回目标；`saveTorrent`/`getMagnet` 语义未改。新增四种临时目录回归覆盖。
- U04 网络限制：`innerIp` 现在在静态转发和缓存头之前覆盖所有应用入口；API 继续使用 v2 Problem JSON/legacy JSON，index/普通静态资源返回短 403 HTML 且 `no-store`，hash 静态资源在允许访问时保留长期缓存；`AuthUtil.getIp`、可信代理、IPv4/IPv6 策略未旁路。
- U05/U06：manifest 增加 `crossorigin="use-credentials"`；pnpm lockfile 使用目标 pnpm 冻结安装更新，未整文件覆盖，保留 fork 测试和工具依赖。
- U07/U08：jsoup `1.23.2`、markdown-it `^15.0.2`、Vue `^3.5.42`、terser `^5.51.2`、Vite `^8.3.0`，Node `v26.8.1`、pnpm `12.3.4` 已分别同步到 POM、package、lockfile 和 CI；NFO/日志没有引入不必要的业务改动。当前本机没有 Maven，Java/SpotBugs/打包门禁由远端 workflow 继续执行。
- S01 分页：保留全量数据用于已有搜索、日期、启用状态、星期和排序；顺序仍是过滤 → 排序/星期顺序 → 全局分页 → 当前页分组，固定每页 60 条，星期分组跨页允许重复标题；切换筛选/排序重置页码和滚动，换页归零，刷新/编辑保留页码，删除后夹紧末页，批量操作仍面向全部匹配结果。
- S02/S03：分组说明改为文本 + native `title`，移除封面组 tooltip；封面 hover 缩放仅在 fine pointer 下启用。订阅列表和已审查的首页、设置、Mikan、AnimeGarden、AniBT、播放列表、资源弹窗统一使用可发现的 `el-scrollbar always`，移除订阅页 `hide-scrollbar` 和无用全局规则，保持单一滚动拥有者。
- S04 刷新生命周期：订阅列表刷新使用单一约 5 秒客户端跟踪链和 120 秒累计等待预算；后台刷新不覆盖已有列表 loading/滚动，deactivated 只停止客户端 timer 并使旧 generation 失效，不取消后端任务；重新激活后重取一次并在仍运行时恢复单链轮询，超时提示后台可能仍在刷新。
- 保留合同：`.68` 的公共/私有图片加载、`SafeImageView` 生命周期、缓存失败边界、懒路由和 hash 静态缓存未被上游覆盖；TorrentUtil、WebFilter 新增测试分别覆盖缓存优先级和 API/静态限制语义。
- 本地证据：前端 5 个测试文件 35/35；`corepack pnpm@12.3.4 install --frozen-lockfile`；Vite `8.3.0` 生产构建成功。真实浏览器使用同一 Playwright Chrome for Testing `154.0.8037.0` 与隔离 fixture，对 v3.2.28.68 基线和当前工作树各测 60/300/1000 条，记录见 [`FORK_V3.2.32_SYNC_AND_SCROLL_OPTIMIZATION_REPORT_20260917.md`](FORK_V3.2.32_SYNC_AND_SCROLL_OPTIMIZATION_REPORT_20260917.md)。未声称 FPS；Performance 长任务、真实图片冷网、真实刷新后端、Maven 和外部 SSO 仍需远端/真实环境验证。
- 发布：版本按规则定为 `3.2.32.69`；当前工作树在本记录写入时尚未创建精确 tag，待提交后由 `v3.2.32.69` tag workflow 完成 GitHub Release 和 GHCR 镜像，并回填 run、附件哈希及 manifest digest。

## 同步审计步骤

1. 从当前四段版本号的前三段确定上游基线标签。
2. 只审阅该基线标签到目标标签之间的提交与差异；同时检查目标上游与 fork 的最终文件差异，不能只看提交标题。
3. 按能力映射“上游旧入口 → 新入口 → fork API → 对应行为测试”；目录改名按能力迁移。
4. 每项 fork 差异标记为“保留”“被上游等效替代”“明确淘汰”或“待确认”，并写出依据。
5. 同步 UI 时检查 `http.js` 使用点、v2/legacy 响应、媒体句柄、恢复状态和 package scripts；同步下载/清理功能运行合同测试。
6. 同步构建时比较首屏模块图、每场景包体预算和生产浏览器冒烟，保留 pageerror、console error、chunk 404 回归。
7. 版本沿用四段编号；同步上游前三段时第四段本地修订号递增一次，不夹带无关新功能。
8. 每次同步提交附一页验收记录，至少包含功能、网络次数、首屏包、已验证项目和未验证项目。

## 3.2.28.68 本轮资源加载优化记录

- 保留：`.67` 已交付的功能修复、懒路由/静态缓存合同、唯一生产 dist 门禁、W7/N08 浏览器证据和精确 tag 发布流程。
- 修复：`SafeImageView` 增加 active 生命周期、取消与 generation 保护；私有图片请求实现有界队列、同 URL 独立消费者取消、等待/总超时与底层 finally 归还；公共图片任务增加有界等待和启动截止时间；封面启用 lazy/async 与失败占位；订阅过滤移除整表 JSON 深拷贝。
- 本地证据：前端 35/35；生产 UI 构建 `target/resource-optimization-20260914` 通过；bundle gate static `105229/48953`、login `202916/63229`、home `193135/61822`、subscriptions `187368/63970`（JS/CSS gzip），`forbiddenModules=[]`；W7/N08 fixture 浏览器 smoke 错误计数均为 0。
- 后端与发布证据：本机未安装 Maven；[`build-test` run 34815224498](https://github.com/ArisMaid/ani-rss/actions/runs/34815224498) 与精确 tag [`build` run 34815586170](https://github.com/ArisMaid/ani-rss/actions/runs/34815586170) 均成功，分别完成 319 项后端测试（0 failures）及 Release/镜像发布。[GitHub Release v3.2.28.68](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.68) 为非 draft、非 prerelease；`ani-rss.jar` SHA-256 为 `8e57ac08b8ff843b8cb58f124f7d56a2eebfd47bd0175544f67a6cd312b3ae96`，`ani-rss.exe` SHA-256 为 `e2cf1ecc5a87637a16ca32e9f01d2b0e1e1c09ff1cd839122186e8e8986b92de`。GHCR manifest index 为 temurin `sha256:2ea6dcbec150464a178fc417bf44069de98e4a90a1e278d78ea2d70c5c86b2ff`（linux/amd64、linux/arm64）、openj9 `sha256:369b31be9eaa498dbe781366f85498bccbb66122e188054d42f349e401c50aed`（linux/amd64、linux/arm64）和 arm32v7 `sha256:3cc683d09611227aa19fe77388be0c6bedeb74d10324b3432ca4faa793c21cbe`（linux/arm/v7）。Docker Hub 因未配置凭据按 workflow 条件跳过；GHCR 三组镜像均已成功发布。

## 3.2.28.67 本轮功能修复记录

- 保留：`.66` 已交付的列表状态、公共图片缓存锁序、维护暂停 SOP、版本规则、唯一生产 dist 门禁和发布流程合同。
- 修复：Mikan 评分批次更新后，展示副本按最新有限评分降序重排，未知评分置后并使用稳定 URL key；首页显式按需注册 `AniCoverView`，非空当天订阅才加载卡片；首页日期与停更天数读取轮询/刷新周期更新的响应式时间；设置项改名为“订阅排序”。
- 明确边界：不增加评分等待、排序服务、轮询线程、后端接口、订阅数据迁移或新的长期测试矩阵；本轮真实页面检查使用隔离 HTTP fixture，不代表真实下载器、账号或外部源验证。
- 本地证据：前端 35/35；生产 UI 唯一目录 `ani-rss-ui/target/release-67-dist-20260909` 构建通过；bundle gate static `105227/48953`、login `202044/63229`、home `192264/61822`、subscriptions `186271/63912`（JS/CSS gzip），`forbiddenModules=[]`。
- 远端 [`build-test` run 34341684631](https://github.com/ArisMaid/ani-rss/actions/runs/34341684631) 与 [`v3.2.28.67` tag build run 34342179506](https://github.com/ArisMaid/ani-rss/actions/runs/34342179506) 均成功；[GitHub Release v3.2.28.67](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.67) 已发布且为非 draft、非 prerelease。Release 附件 `ani-rss.jar` SHA-256 为 `bf14c585b45ef6e1dc3eb4cf5e383fed9df0b19ba5b127df5ed11de3b8e8b646`，`ani-rss.exe` SHA-256 为 `4fb6b1c7d43ca21e6055b94e0b28d508d5a8e949ebcbed6a6a349b46d0d09548`；GHCR manifest index 为 temurin `sha256:d207f3ce31dbab4e7702d4dc6df55cd7c19bf4e9b72feb4702b85cf7f1d4e4fd`（linux/amd64、linux/arm64）、openj9 `sha256:159b37df9b99bf6479f08f9918f76ffcfa992be2d3c1006f5c36219e2b34f2ef`（linux/amd64、linux/arm64）和 arm32v7 `sha256:2f09c2cb6c5fafaa723acded7b369fd4f301373f2e1ac76fb87da307838f4c8f`（linux/arm/v7）。Docker Hub 登录因未配置凭据按 workflow 条件跳过；GHCR 三组镜像已成功发布。

## 3.2.28.66 本轮审计记录

- 保留：F01–F39 的缺集恢复、ownership、认证/CSRF、媒体句柄、备份、下载器失败、懒路由、静态缓存、RSS monotonic clock、8 秒轮询、tag 工作流和同一生产 dist 门禁合同。
- 修复：AnimeGarden 匹配确认和批量入口在交付前再次校验当前列表快照，失效时清理匹配交互；ImageCache 将 pending 预留、发布和本次失败撤销收进现有 key lock；维护暂停保持到重启，暂停时先允许热缓存读取并停止后续淘汰、pending 清理和新持久化。
- 明确保留边界：不新增恢复事务、状态枚举、管理 API、并发注入框架或浏览器矩阵；pending 交错只做静态锁序复核，异常文件按管理员 SOP 处理，代理不执行批量/递归删除。
- 待确认：真实外部源/下载器/账号/媒体/数据库费用、生产浏览器完整交互、删除失败故障注入、Docker Desktop/Linux engine 本地构建，以及强杀/断电/系统 I/O 阻塞下的缓存索引一致性。
- 证据：本地 Java 319 tests、0 failures、0 errors、4 skipped；ImageCacheServiceTest 19/19；前端 35/35；唯一目录生产 UI build 与 bundle gate 通过。远端 [`build-test` run 34217520584](https://github.com/ArisMaid/ani-rss/actions/runs/34217520584) 与 [`v3.2.28.66` tag build run 34218050737](https://github.com/ArisMaid/ani-rss/actions/runs/34218050737) 成功；Release 附件和 GHCR manifest digest 已回填到 [`docs/verification.md`](docs/verification.md) 与 [`docs/performance-baseline.md`](docs/performance-baseline.md)。

## 3.2.28.65 本轮审计记录

- 保留：F01–F30、F34 的缺集恢复、ownership、认证/CSRF、媒体句柄、备份、下载器失败、懒路由、静态缓存、RSS monotonic clock、8 秒轮询、tag 工作流和同一生产 dist 门禁合同。
- 修复：AnimeGarden 成功重载不再迁移旧 enrichment/group/selection；`ANIME_GARDEN_LIST_EXPIRED` 只进入人工重新加载入口；ImageCache 先按实际新 bytes/临时文件准入并有限淘汰，pending 达上限或历史 manifest 超限时暂停新增与重写；close final flush 排入既有单 writer，最多等待 5 秒。
- 明确淘汰：v3.2.28.64 中“过期一次受控自动 reload”和“close 最终严格追平”的表述；近期封面缓存允许重建，清理积压由维护 SOP 处理。
- 待确认：真实外部源/下载器/账号/媒体/数据库费用、生产浏览器完整交互、Docker Desktop/Linux engine 本地构建，以及强杀/断电/系统 I/O 阻塞下的缓存索引一致性。
- 证据：Java 319 tests、0 failures、0 errors、4 skipped；ImageCacheServiceTest 19/19；前端 35/35；bundle gate 通过。`build-test` run `34193929884` 与 tag build run `34194388721` 成功；Release 附件和 GHCR digest 已回填到 [`docs/verification.md`](docs/verification.md) 与 [`docs/performance-baseline.md`](docs/performance-baseline.md)。

## 3.2.28.64 本轮审计记录

- 保留：F01–F29 的缺集恢复、ownership、认证/CSRF、媒体句柄、备份、下载器失败、懒路由、静态缓存、RSS monotonic clock、8 秒轮询和 tag 发布合同。
- 新增并强化：R63-01/02 的请求归属与列表级恢复；R63-03/04 的 manifest revision 单 writer、close 最终 flush 与生命周期诊断；R63-05 的 pending deletion 路径并集预算、准入和退避；R63-06 的 CI N08 同构建产物门禁。
- 被上游等效替代：无。本轮未执行上游提交合并，没有把冲突解决成功误记为行为等效。
- 明确淘汰：无。未删除原有恢复、归属、完成、备份、下载和媒体路径。
- 待确认：真实外部源/下载器/账号/媒体/数据库费用、Linux engine 本地构建；4 个 Java 测试仍因符号链接或外部样本缺失跳过，详见 [`docs/verification.md`](docs/verification.md)。
- 证据：Java 319 tests、前端 35/35；W7/N08 raw reports 均记录 `commit=bff06cb...` 和 `dirty=false`；远端 `build-test` run `34109933350` 与 tag build run `34110438143` 均成功，Release 附件和 GHCR digest 已核验并记录在 [`docs/verification.md`](docs/verification.md)。

## 3.2.28.63 本轮审计记录

- 保留：F01–F21 的缺集恢复、ownership、认证/CSRF、媒体句柄、备份、下载器失败、懒路由、静态缓存和 tag 发布合同；本轮没有删除状态枚举、数据库表或下载/删除/移动策略。
- 保留并强化：F22 无评分有限 JSON 语义；F23 pending/手动重试；F24 AnimeGarden 409/一次受控重载与 10,000/100,000 有界索引；F25–F27 图片 busy、single-flight、manifest、pending deletion 和 shutdown；F28 RSS 同一 monotonic clock；F29 8 秒页面轮询 single-flight。
- 被上游等效替代：无。本轮未执行上游提交合并，因此没有把冲突解决误记为行为等效。
- 明确淘汰：无。未删除原有恢复、归属、完成、备份、下载和媒体路径。
- 待确认：真实外部源/下载器/账号/媒体/数据库费用、Linux engine 本地构建；符号链接和真实 upstream backup sample 4 个测试因环境/样本缺失跳过，详见 [`docs/verification.md`](docs/verification.md)。
- 证据：完整 Java `verify` 为 311 tests、0 failures、0 errors、4 skipped；前端为 33/33；W6/W7 原始报告均记录 `commit=f550cdd...` 和 `dirty=false`。

## 3.2.28.62 本轮审计记录

- 保留：R01/R02 部分对象 + retryable、R04 有界补载、R05 生成代际、R06 图片 lock/lease、R07 失败状态上限、R09 6h 评分 freshness、W6/W7 合成证据。
- 新增测试：后端 AnimeGarden/Cache/Image/Score 和 W6 三条服务链；前端 loader、两个实际 View lifecycle、播放和恢复契约；CI 生产唯一目录/bundle/browser smoke。
- 明确后置：真实外部源波动、真实下载器 8 秒页面停留、T7 DB/network/file 费用计数、Docker/Linux engine 本地构建。
- 结果：完整 Java `verify` 为 302 tests、0 failures、0 errors、4 skipped；W7 原始报告在 `4df5e7e2` 上 `dirty:false`。

Fork 版本号使用 `上游版本.本地修订号` 格式。例如 `3.2.1.48` 表示基于上游 `v3.2.1`，本地修订号为 `48`。从 `3.2.1.48` 同步上游 `v3.2.2` 时，审阅 `v3.2.1..v3.2.2`，发布版本为 `3.2.2.49`。
