# 有状态模块依赖与本次决策

本文件按修复计划 T7 建立依赖清单。T7 不是本次 3.2.28.61 发布的前置条件；没有固定下载器、文件树和数据库夹具时，不把推测性的删减写成性能结果。

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

## 热点与测量边界

- 下载器网络调用：`TorrentUtil` 的登录、任务列表、添加、删除、移动和重命名；任务列表读取已经移除固定 1 秒等待，失败不再伪装成空列表。单次 RSS 周期现在通过 thread-local 的最多 5 秒 `SnapshotCycle` 复用成功快照，明确的添加/删除/标签/移动/重命名/缺集恢复写操作会使快照失效；不跨周期缓存，也不把失败结果写入快照。
- 文件扫描：`DownloadService`、`OwnershipService`、`MissingEpisodeRecoveryService`、`SubscriptionDeletionService` 处理下载目录和归属文件；写入/删除前仍需再次做路径和归属校验。
- 全局锁：RSS 下载写路径仍由现有任务协调与下载锁保护；本次没有把写操作改成并行，以免破坏缺集恢复、洗版和文件变更顺序。
- 待补固定采样：一次带明确订阅数量的 RSS 周期中，记录 downloader list/files 调用数、每订阅 DB 查询数、文件 walk 次数，以及网络调用是否位于 `DatabaseManager` 全局锁内。本机没有可复现的真实下载器夹具，因此本次不填虚构数值。

## 本次保留与后续候选

本次只收敛了下载器失败语义、刷新状态快照、无条件读取等待和周期内成功快照复用；没有删除状态枚举、数据库表、ownership/recovery/completion/backup 路径，也没有引入跨周期任务缓存。订阅、ownership、recovery、completion、backup 的持久状态和恢复路径继续由现有行为测试保护。批量查询、文件快照复用和完结迁移扫描收缩仍需真实采样与断电续跑证据，未在本版本中宣称完成。

现有 `DownloaderDeleteContractTest`、`DownloaderFailureContractTest`、`QBittorrentAuthenticationContractTest`、`TransmissionContractTest`、`OpenListWorkflowTest`、`MissingEpisodeRecoveryServiceTest`、`OwnershipServiceMoveTest` 和 `SubscriptionDeletionServiceTest` 继续作为行为门禁。
