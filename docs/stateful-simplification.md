# 有状态模块依赖与本次决策（v3.2.28.66）

本文件按开发书 T7 建立依赖清单。`3.2.28.66` 在既有状态链合同上收敛 AnimeGarden 匹配交付、公共图片 pending 临界区和维护暂停恢复语义；真实数据库、文件系统和外部下载器成本仍明确后置。

## v3.2.28.66 新增状态验证

- AnimeGarden 匹配弹窗是当前列表快照的临时交互状态：确认前再次检查主列表、有效性、待重载、列表请求和弹窗可见性；列表失效/关闭/重载会立即关闭并清空匹配选择。批量添加在入口取得选择快照，后续列表补载失败不回滚已开始提交。
- AnimeGarden 列表成功重载建立新的快照代际：请求等待时可继续展示旧列表，成功后清除 enrichment、group、selectName 和未提交选择；过期/失败快照只保留人工重新加载入口，不因隐藏恢复自动重试。
- ImageCache 生命周期明确为 `OPEN → CLOSING → CLOSED`；进入 CLOSING 后拒绝新下载，final flush 排入现有单 writer，close 最多等待 5 秒；success 只表示该次快照写成功，近期封面索引允许重建。
- manifest 的 revision、entry/pending 快照、dirty 和 persistedRevision 在同一状态锁下捕获；不会在快照内容与 revision 之间留下可观察窗口。
- pending deletion 以规范化路径做并集计费，active entry 与同路径 pending 不重复计数；reserved bytes/files 参加准入。新图片先按实际 bytes 与一个临时文件申请预算；旧路径 pending 预留、move、entry 替换和本次失败撤销均在同一 key lock 内完成。
- 维护暂停是进程内保持到重启的管理员信号，不因无关 pending 删除自动解除。暂停时仍允许返回可用热缓存，随后阻止过期 entry 删除、新 fetch、公共 entry 淘汰和 pending 重试；维护线程只裁剪失败记录，处理残留由 SOP 完成后重启。
- `ImageCacheServiceTest` 定向 19/19、Java 完整门禁 319/0/0/4 skipped、前端 35/35 通过；上述是状态不变量证据，不是生产吞吐、文件系统故障率或浏览器交互测量。

## 依赖地图

| 状态/模块 | 持久层或来源 | 主要 Service / 入口 | 恢复与退出 |
| --- | --- | --- | --- |
| 订阅、下载记录、RSS 配置 | SQLite / `ConfigStore`、订阅 Repository | `Runner`、`AniController`、`DownloadService`、`RssTask` | 启动加载；删除订阅按归属策略退出 |
| ownership 归属与迁移 | SQLite ownership Repository | `OwnershipService`、`OwnershipController`、`DownloadService` | 状态转换可恢复；移动/删除前保留唯一键和归属校验 |
| 缺集恢复 | SQLite recovery Repository | `MissingEpisodeRecoveryService`、`RssTask` | `PENDING/RUNNING` 等非终态启动时继续；终态不重复提交 |
| quarantine | SQLite quarantine 表 | `QuarantineService`、quarantine Controller | 未过期可恢复；过期按明确 purge 操作退出 |
| 订阅删除 | SQLite 订阅/ownership/recovery | `SubscriptionDeletionService` | `deleteFiles=false` 保留媒体；操作完成后归属退出 |
| 完结迁移 | SQLite completion 表 | `CompletionMigrationService`、启动任务 | 已落库终态不应每轮扫描；非终态需能续跑 |
| 备份恢复 | 配置目录 restore staging/rollback、SQLite | `RestoreService`、`RestoreController` | `VALIDATED` 后 confirm；失败回滚或进入 `MAINTENANCE_REQUIRED` |

## 本轮实际采样

### RSS/下载器链

W6 原始报告 [`w6-rss-v3.2.28.63-20260907.json`](performance-data/w6-rss-v3.2.28.63-20260907.json) 使用临时 SQLite/config 根目录和 100 个启用订阅，实际调用：

- `RssTask → SubscriptionDownloadQueue → DownloadService → TorrentUtil → ItemsUtil`。
- 合成 RSS HTTP 请求 100，downloader connect 1、list 10，100 个订阅结果全部 `SUCCESS`；snapshot hits/misses 为 `90/10`，跨 5s 边界后的读取为 9 次。
- 每订阅 500ms 节流保留为虚拟时间 `50,000ms`；墙钟执行 `1,177ms`。sleep 和 snapshot age 共用 fake monotonic clock，`age >= 5s` 会重新读取。
- 另有 [`w6-rss-add-v3.2.28.63-20260907.json`](performance-data/w6-rss-add-v3.2.28.63-20260907.json) 覆盖同一真实服务链的可处理 RSS item：RSS 请求 1、downloader add 1、结果 `SUCCESS`。
- 100 订阅 fixture 的 RSS body 是合法空 channel，因此 files/delete/move/rename、缺集恢复和文件 walk 没有被触发；这些计数按 schema 保持 `null`，没有用订阅数量猜测。

### Service/缓存链

W6 services 报告 [`w6-services-v3.2.28.63-20260907.json`](performance-data/w6-services-v3.2.28.63-20260907.json) 固定了 96 个 Mikan 条目、两周、最大组 64、AnimeGarden default/search 两类独立消费者、50 个逻辑图片引用和 25 个共享 URL。生产 Service、SQLite 缓存、图片同 key lock/read lease 和 stream handle 被执行；loader、图片本地 HTTP transport 是合成边界。DB query、lock wait、file walk、队列深度没有在本轮虚构计数。

W6 hot-list 报告 [`w6-hot-list-v3.2.28.63-20260907.json`](performance-data/w6-hot-list-v3.2.28.63-20260907.json) 包含冷调用、30 次进程内热调用以及清空内存后的 SQLite restart-persistent 调用；当前结果为 cold `90.6249ms`、hot p95 `0.0395ms`、restart `0.2971ms`。p95 只代表 Service 单调时钟计时，不代表真实 HTTP 或生产锁等待。

## 保留与后置

本次保留所有订阅、ownership、recovery、completion、backup 状态机和现有退出/恢复路径；只收敛了下载器失败语义、RSS 周期内成功快照复用、图片缓存边界、评分持久 TTL 和按需补载。没有删除状态枚举、数据库表、归属记录，也没有引入跨周期任务缓存。

以下项目仍是“未验证（原因）”，不是“已优化”：

- 真实下载器的登录、任务列表、文件清单、add/delete/move/rename 和失败分布。
- DB 查询次数、全局锁等待、文件 walk 次数、快照 dirty 写路径和断电续跑成本。
- 完整 RSS 场景中的缺集恢复、洗版、多文件 torrent、备用 RSS 和完结迁移。

`DownloaderDeleteContractTest`、`DownloaderFailureContractTest`、`QBittorrentAuthenticationContractTest`、`TransmissionContractTest`、`OpenListWorkflowTest`、`MissingEpisodeRecoveryServiceTest`、`OwnershipServiceMoveTest` 和 `SubscriptionDeletionServiceTest` 继续作为行为门禁；当前完整回归为 319/0/0/4 skipped。4 个 skipped 的名称和原因见 [`docs/verification.md`](verification.md)。
