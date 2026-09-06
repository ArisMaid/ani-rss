# 3.2.28.60 验证记录

## 已通过

- 前端 Vitest：6/6 通过，覆盖 48 项分批、失败重试、公共批次不饥饿和截止时间取消。
- 前端 `pnpm build:verify`：隔离输出 `.verify-dist` 构建通过，未运行 `clean`。
- 前端 `pnpm check:bundle`：按 Vite manifest 递归入口闭包统计，播放器、Markdown、播放页和备份页未进入 `index.html` 首入口，25% JS 门槛通过。
- T0 fixture：100 个订阅、96 个 Mikan 条目、50 张小图片、8 秒下载器 stub；热列表 30 次 p95 `1.409ms`，同 key 20 个消费者外部请求 `1`，轮询最大在途 `1`，RSS 主动 sleep `0ms`。
- 后端 Java 17 release 编译通过。
- 相关后端测试：37 tests，0 failures，0 errors。
- 后端完整回归：289 tests，0 failures，0 errors，4 skipped。
- 首入口实际 gzip：JS `105,196` bytes、CSS `48,953` bytes；对照提交 `562454f2` 的 JS `431,801` bytes，JS 下降约 `75.6%`。
- 发布包工作流已保留为 tag 触发；镜像使用 `linux/amd64`、`linux/arm64`、`linux/arm/v7` 构建矩阵。

## 浏览器冒烟

在生产 `vite preview` 输出上完成了 Playwright CLI 的未登录页面冒烟：为 custom CSS/JS、CSRF 和 IP-login 提供隔离 stub，最终登录页面 `pageerror=0`、console errors=0、warnings=0。该结果只证明静态入口和未登录路由，不等价于真实账号的完整页面交互。

## 尚未验证

- 真实 Mikan、Bangumi、AnimeGarden、图片代理的 200ms/2s/超时/429/500 分布。
- 真实登录后首页、订阅/Mikan/AnimeGarden 分组交互、首屏 LCP、chunk 404 和媒体 Range/字幕/外部播放器操作。
- 真实下载器 8 秒响应、100 订阅 RSS 周期的锁等待、数据库调用、文件 walk 和缺集恢复。
- 真实运行中的图片缓存容量淘汰、失败清理重试、manifest 文件系统异常恢复。
- Docker Desktop/Linux engine 本地构建；本机 engine 不可用。
- GitHub Actions 多架构镜像、GHCR/Docker Hub 推送及 GitHub Release 产物，必须等待 `v3.2.28.60` tag workflow 成功后再标记为已发布。

任何未完成项在发布流水线成功前都不标记为“已发布”；即使发布成功，以上真实环境项目仍保持“未验证”。
