# Fork 合同清单

这些合同是本地 fork 相对上游必须长期保留的边界。后续同步上游时，先检查合同，再处理实现差异。

| ID | 合同 | 主要证据 |
| --- | --- | --- |
| F01 | 缺集恢复、SQLite 归属记录和旧备份迁移不能被 UI/性能改动绕过 | `recovery/`, `persistence/`, `backup/` 测试 |
| F02 | Cookie 会话、CSRF、401/403 语义和内网边界保持有效 | `AuthServiceTest`, `WebFilterTest`, `CustomExceptionHandlerTest` |
| F03 | 图片/媒体抓取必须经过 SSRF、路径和会话归属校验 | `SafeImageFetcherTest`, `ImageCacheServiceTest`, `MediaHandleServiceTest` |
| F04 | 播放器和外部播放器使用短期媒体句柄，不把 Cookie 依赖 URL 交给第三方 | `MediaHandleService`, `ArtplayerView.vue` |
| F05 | 备份导入保持校验、预览、确认、状态轮询和失败回滚阶段 | `RestoreServiceTest`, `BackupView.vue` |
| F06 | 下载器登录/任务快照失败不能被解释为空任务或正常完成 | `DownloaderFailureContractTest`, `DownloadService`, `TorrentUtil` |
| F07 | Mikan 列表保持缓存与当前季语义；评分批量上限 48、受控并发和可取消 | `MikanServiceTest`, `PublicScoreServiceTest`, `mikan-loader.test.js` |
| F08 | 重型路由、播放器、备份和对话框按需加载，首屏不提前触发外部请求 | `router/index.js`, `check-bundle.mjs` |
| F09 | 生产静态资源只有带 hash 的资产长缓存；HTML/API 不得被误缓存 | `WebFilter.java`, `WebFilterTest` |
| F10 | 验证脚本不得使用 `clean` 或删除历史输出作为前置条件 | `package.sh`, `verify.ps1`, `verify.sh`, `ani-rss-ui/pom.xml` |
| F11 | 上游同步保持前三段基线 + 本地修订号版本规则，并更新默认前端版本 | `UPSTREAM_SYNC.md`, 根 POM、UI POM、`src/js/config.js` |
| F12 | 发布镜像必须由可审计的 tag 工作流构建；测试工作流不能推送 `test` 镜像 | `.github/workflows/build.yml`, `.github/workflows/build-test.yml` |
| F16 | 任何新增后台预热都必须有单飞、队列/并发上限和生命周期关闭 | `PublicScoreService`, `ImageCacheService`, `MikanService` |
| F17 | 旧请求只能更新当前 generation；AbortError 静默，真实错误可见 | `MikanView.vue`, `DashboardView.vue`, `api.js` |
| F18 | 公共图片缓存跨会话共享但仍要求当前请求认证，且 manifest 可恢复 | `ImageController`, `ImageCacheServiceTest` |
| F19 | 发布前不能宣称未执行的真实上游、下载器、浏览器或多架构验证 | 本文件与 `performance-baseline.md` 的未测项 |

## 同步审计顺序

1. 对照目标上游标签，限定审阅范围为当前基线到目标标签之间。
2. 先运行安全、恢复、下载器失败和认证合同测试。
3. 再运行前端孤立构建、bundle 门禁和 Mikan/播放器交互检查。
4. 最后更新版本、`UPDATE.md`、本文件与发布工作流。

## 3.2.28.61 验收映射

本版本的关键实现与证据如下；“未验证”表示没有把隔离 stub 或单元测试冒充真实环境。

| 能力 | 当前入口 | 证据 |
| --- | --- | --- |
| Mikan 列表与评分 | `MikanService`、`mikanScores`、`MikanView.vue` | `MikanServiceTest`、`MikanListPersistentCacheTest`、`PublicScoreServiceTest`、前端 6 个 loader 测试 |
| AnimeGarden 非阻塞补载 | `animeGardenList`、`animeGardenEnrichment`、`AnimeGardenView.vue` | Java 编译、前端生产构建；真实 AnimeGarden/评分源未连接 |
| 图片公共缓存 | `GET /api/v2/images?url=...`、`ImageCacheService` | `ImageCacheServiceTest` 6 项，覆盖认证、单飞、跨会话、manifest 重启、失败冷却 |
| 下载器快照与失败状态 | `TorrentUtil.SnapshotCycle`、`RssTask`、`DownloadService` | 全量后端回归 289 tests，0 failures，0 errors，4 skipped |
| 恢复/媒体/认证合同 | v2 restore/media、Cookie + CSRF、401/403 | 全量后端回归；真实浏览器、Range、外部播放器仍未连接 |
| 首屏与发布门禁 | manifest bundle checker、tag workflow | 首入口 JS gzip 105,196 B；多架构镜像需 tag workflow 实际成功后才算已发布 |
