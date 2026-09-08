# Fork 合同清单

这些合同是本地 fork 相对上游必须长期保留的边界。后续同步上游时，先检查合同，再处理实现差异；附加开发书只定义验收边界，不改变用户授权范围。

| ID | 合同 | 主要证据 |
| --- | --- | --- |
| F01 | 缺集恢复、SQLite 归属记录和旧备份迁移不能被 UI/性能改动绕过 | `recovery/`、`persistence/`、`backup/` 测试 |
| F02 | Cookie 会话、CSRF、401/403 语义和内网边界保持有效 | `AuthServiceTest`、`WebFilterTest`、`CustomExceptionHandlerTest` |
| F03 | 图片/媒体抓取必须经过 SSRF、路径和会话归属校验 | `SafeImageFetcherTest`、`ImageCacheServiceTest`、`MediaHandleServiceTest` |
| F04 | 播放器和外部播放器使用短期媒体句柄，不把 Cookie 依赖 URL 交给第三方 | `MediaHandleService`、`ArtplayerView.vue`、W7 player |
| F05 | 备份导入保持校验、预览、确认、状态轮询、超时重新查询和失败回滚阶段 | `RestoreServiceTest`、`BackupView.vue`、restore contract test |
| F06 | 下载器登录/任务快照失败不能被解释为空任务或正常完成 | `DownloaderFailureContractTest`、`DownloadService`、`TorrentUtil` |
| F07 | Mikan 列表先返回；评分批量上限 48、最多 2 个并发 worker、总预算 20s、失败可重试且可取消 | `MikanServiceTest`、`PublicScoreServiceTest`、`mikan-loader.test.js` |
| F08 | AnimeGarden subjects 先返回，补载对象允许部分字段，失败返回 retryable，不伪造完整成功；单消费者最多 48 个 ID | `AnimeGardenServiceTest`、`AnimeGardenView.vue` |
| F09 | Mikan/AnimeGarden 隐藏或 deactivated 时取消当前 generation；恢复后按 needsListReload 重取，不永久 loading | `list-lifecycle.test.js`、两个实际 View |
| F10 | 不同 AnimeGarden 列表消费者不会因全局清空快照互相失效；快照按消费者/查询隔离并有界 | `AnimeGardenServiceTest`、`AnimeGardenController` |
| F11 | 公共图片同 key 的旧文件清理不能删除新文件；写入和读取分别受同 key lock/read lease 保护，active reader 结束前不淘汰 | `ImageCacheServiceTest`、`ImageController` |
| F12 | 图片失败记录只保留安全摘要，TTL 30s、容量 5000、维护时主动 trim；失败不把完整 URL/body/Throwable 写入状态 | `ImageCacheServiceTest`、`ImageCacheService` |
| F13 | 评分持久缓存有效期按观测时刻计算绝对到期时间；DB freshness 为 `min(expires_at, updated_at+6h)`，缺失/非法 updatedAt 命中失败 | `PublicScorePersistentCacheTest`、`PublicScoreCacheRepository` |
| F14 | 所有新增后台预热有 single-flight、队列/并发上限、异常可见且生命周期可关闭 | `PublicScoreService`、`ImageCacheService`、`MikanService` |
| F15 | 旧请求只能更新当前 generation；AbortError 静默，真实错误可见；父级取消要清理 listener/timer | `MikanView.vue`、`AnimeGardenView.vue`、`mikan-loader.test.js` |
| F16 | 重型路由、播放器、备份和对话框按需加载，首屏不提前触发外部请求 | `router/index.js`、`check-bundle.mjs`、W7 JSON |
| F17 | 生产静态资源只有带 hash 的资产长缓存；HTML/API 不得被误缓存 | `WebFilter.java`、`WebFilterTest`、W7 fixture |
| F18 | 验证脚本不得使用 `clean` 或删除历史输出作为前置条件；每次构建/fixture 使用唯一目录 | `package.sh`、`verify.ps1`、workflow、W6/W7 reports |
| F19 | 上游同步保持前三段基线 + 本地修订号版本规则，并更新根 POM、模块 POM、前端默认版本与发布说明 | `UPSTREAM_SYNC.md`、POM、`config.js` |
| F20 | 发布镜像必须由可审计的 tag 工作流构建；测试工作流不能推送 `test` 镜像 | `.github/workflows/build.yml`、`.github/workflows/build-test.yml` |
| F21 | 发布前不能宣称未执行的真实上游、下载器、浏览器或多架构验证 | 本文件、`verification.md`、`performance-baseline.md` |

## 3.2.28.64 增量合同

| ID | 合同 | 主要证据 |
| --- | --- | --- |
| F30 | Mikan 补载请求必须绑定 query generation/group context；切组、取消、恢复和 finally 不能回写其他组状态，失败只归属自己的请求 | `MikanView.vue`、`list-lifecycle.test.js`、前端 35/35 |
| F31 | AnimeGarden 列表层显式返回 success/failed/aborted/stale；列表恢复不能被 enrichment 失败吞掉；（v3.2.28.64 历史合同：过期曾允许一次受控自动 reload，已由 v3.2.28.65 的 F35/F36 替代） | `AnimeGardenView.vue`、`list-lifecycle.test.js`、W7 production fixture |
| F32 | ImageCache manifest 快照、revision 捕获和 dirty/persisted 状态由同一状态锁协调；单 writer 负责正常写与 close 的有界尽力 flush，生命周期为 OPEN/CLOSING/CLOSED | `ImageCacheServiceTest` manifest/close/timeout 用例 |
| F33 | pending deletion 按规范化路径去重并计入 tracked/reserved bytes/files；准入、轮转维护、attempts/nextRetryAt 和 bounded retry 不得耗尽关闭预算，达到维护上限时暂停新增持久缓存 | `ImageCacheServiceTest` union-budget、pending、capacity、shutdown 用例 |
| F34 | CI 的 W7/N08 必须使用同一个唯一生产 dist，报告路径/构建目录隔离，报告上传 always，超时失败不得被 continue-on-error 隐藏 | `.github/workflows/build-test.yml`、W7/N08 raw reports |

## 3.2.28.65 增量合同

| ID | 合同 | 主要证据 |
| --- | --- | --- |
| F35 | AnimeGarden 成功重载以新列表快照重建补载、分组和未提交选择；请求等待期间可保留旧展示，但不合并旧快照状态；失败显示“列表未更新”并使旧快照失效 | `AnimeGardenView.vue`、`list-lifecycle.test.js`、前端 35/35 |
| F36 | AnimeGarden 遇到 `ANIME_GARDEN_LIST_EXPIRED` 后只显示人工“重新加载列表”入口；不自动恢复、不因隐藏/恢复触发重载；无效列表禁止补载、切组和批量操作 | `AnimeGardenView.vue`、`list-lifecycle.test.js` |
| F37 | 公共图片缓存按新图片实际字节和一个临时文件做准入；准入失败前不创建/写入临时文件，先按有限旧条目回收，容量忙不写入源站失败负缓存 | `ImageCacheService`、`ImageCacheServiceTest` |
| F38 | pending deletion 在切换正式索引前必须预留；达到 1024 或历史 manifest 超限时保留追踪、暂停新增持久缓存和 manifest 重写，并由管理员 SOP 处理 | `ImageCacheService`、`ImageCacheServiceTest` |
| F39 | ImageCache 进入 CLOSING 后拒绝新下载；最终 manifest 写入排入已有单 writer，close 最多等待 5 秒，success 仅代表本次快照写成功，不保证临近关闭变化永久落盘 | `ImageCacheService`、`ImageCacheServiceTest` close/timeout 用例 |

本版本的 F35–F39 优先采用人工产品入口、有限资源边界和缓存维护 SOP；不承诺强杀、断电、系统 I/O 阻塞下的近期封面索引零丢失。封面缓存可重建，维护时不得由代理执行批量或递归删除。

## 3.2.28.63 增量合同

| ID | 合同 | 主要证据 |
| --- | --- | --- |
| F22 | 评分内部负缓存不得使用 NaN；公开响应只返回有限数值、合法 `0` 或 `null`，无评分已终结项不进入 retryable，网络失败仍可重试 | `PublicScoreServiceTest`、`MikanServiceTest`、`BgmInfoJsonTest` |
| F23 | Mikan/AnimeGarden 补载在当前查询世代内显式维护 `pending/loading/complete/incomplete/failed`；用户可重试剩余 ID，双击不重复启动，取消不伪装失败 | `mikan-loader.test.js`、`list-lifecycle.test.js`、`MikanView.vue`、`AnimeGardenView.vue` |
| F24 | AnimeGarden 合法但不在当前上下文的 ID 使用机器码 `ANIME_GARDEN_LIST_EXPIRED` 和 HTTP 409；快照最多登记 10,000 ID、总引用最多 100,000，前端一次受控重载后停止循环 | `AnimeGardenServiceTest`、Controller 测试、513 条列表测试、View lifecycle 测试 |
| F25 | 图片本地 reader/队列争用返回短 Retry-After 且不写入源站 30s failure cache；源站失败与本地内容错误保留独立类别 | `ImageCacheServiceTest`、`ImageController` |
| F26 | 同 key miss 在取得 flight 后必须二次检查缓存/冷却；同一外部加载只允许一个生产者，所有 Future 都在成功、失败、取消和关闭路径完成 | `ImageCacheServiceTest` 并发 barrier、shutdown 用例 |
| F27 | manifest 通过单 writer/revision/dirty 合并写入；旧路径删除失败进入 bounded pending deletion，维护重试且不丢计数；关闭完成 queued flights 和最后一次有界 flush | `ImageCacheServiceTest` manifest、pending deletion、shutdown 用例 |
| F28 | RSS snapshot 的年龄和 500ms sleep 由同一个 monotonic clock 推进；`age >= 5s` 过期，add/delete 等 mutation dirty 后立即重读，不跨线程虚构失效 | `TorrentSnapshotCycleTest`、`RssApplicationChainPerformanceTest`、W6 raw reports |
| F29 | 生产页面下载器轮询在 8s 响应下始终 `maxInFlight=1`；hidden 不启动下一次，visible 可恢复，手动刷新不并发第二个请求且取消不报错 | `dashboard-polling.test.js`、N08 slow-polling raw report |

## 同步审计顺序

1. 从当前四段版本号的前三段确定上游基线标签，只审阅基线到目标标签之间的提交与最终差异。
2. 先运行安全、恢复、下载器失败、认证和媒体句柄合同测试。
3. 再运行前端纯逻辑/组件测试、生产构建、bundle 门禁和 W7 浏览器场景。
4. 运行 W6 fixture、30 样本热列表、100 订阅 RSS 服务链和完整 Java `verify`。
5. 最后更新版本、`UPDATE.md`、本文件、性能/验收记录和发布工作流结果。

同步时将每项差异标记为“保留”“被上游等效替代”“明确淘汰”或“待确认”，不能以冲突解决成功代替行为验证。

## 3.2.28.62 验收映射

| 能力 | 当前入口 | 证据 | 边界 |
| --- | --- | --- | --- |
| Mikan 列表、部分评分与取消 | `MikanService`、`PublicScoreService`、`MikanView.vue` | Java tests、前端 12 个 loader tests、W6 hot-list | loader/评分源为合成边界 |
| AnimeGarden 非阻塞补载 | `animeGardenList`、`animeGardenEnrichment`、`AnimeGardenView.vue` | Java 交错消费者测试、View lifecycle tests、W6 services | 真实 AnimeGarden/评分源未连接 |
| 图片公共缓存 | `ImageController`、`ImageCacheService` | 同 key 竞态、lease、manifest 重启、失败 TTL/容量 tests、W6 stream lease | 真实生产媒体目录未连接 |
| 评分持久缓存 | `PublicScoreCacheRepository`、`PublicScoreService` | absolute expiry、updatedAt freshness、异步 writer tests | 未使用生产数据库 |
| 下载器/RSS 服务链 | `RssTask`、`SubscriptionDownloadQueue`、`DownloadService`、`TorrentUtil` | W6 raw report、完整回归 | 空 RSS 不覆盖文件变更路径 |
| 播放/恢复 | 媒体句柄、`ArtplayerView.vue`、`BackupView.vue` | play/restore contract tests、W7 player/settings | 复杂媒体解码和真实恢复目录未验证 |
| 首屏与发布门禁 | manifest module map、bundle checker、tag workflow | W7 静态/浏览器报告、CI workflow | 多架构产物需 tag workflow 实际结果 |
