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
