# 3.2.28.62 验证记录

本轮严格按 `FORK_REPAIR_FOLLOWUP_PLAN.md` 执行。该开发书是验收规范，不是额外的生产授权；未连接真实账号、下载器或外部数据，也没有把合成 fixture 当作生产验证。

## 已通过

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

## 可重复命令

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

## 仍为未验证

- 真实 Mikan、Bangumi、AnimeGarden、图片代理的 200ms/2s/超时/429/500 分布和真实外网波动。
- 真实账号登录、生产首屏 LCP、真实媒体格式解码、Range/字幕/外部播放器完整操作；本轮仅用本地合成接口验证前端链路。
- W6 RSS fixture 使用可解析空 RSS，因此真实 add/download、文件 walk、移动/重命名、缺集恢复路径的运行时计数仍为 `null`，不能从 100 个订阅推导。
- 真实下载器、生产数据库锁等待、DB 查询数和用户媒体目录恢复；W6 原始 JSON 已明确标记未测字段。
- Docker Desktop/Linux engine 本地构建；多架构镜像、GHCR/Docker Hub 推送和 GitHub Release 产物必须以精确 tag workflow 的实际结果为准。

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
| 发布 | 进行中 | 代码、版本和验收提交已冻结，等待 tag workflow 产物核验 |
