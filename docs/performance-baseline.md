# Fork 性能基线与验收记录

本文件只记录实际执行结果。目标阈值、隔离夹具结果和真实实例结果分开列出，任何未连接真实账号、下载器或浏览器的项目都不标记为生产验证。

## 版本与环境

最近一次隔离夹具运行：待 3.2.28.60 最终提交后生成；原始 JSON 与提交哈希保持一一对应。

| 项目 | 实际值 |
| --- | --- |
| 对照提交 | `562454f287645531e09bb2ada65afd31dbfa4091` (`3.2.28.58`) |
| 当前提交 | `待最终提交` (`3.2.28.60`) |
| Node / pnpm | Node `v24.16.0` / pnpm `11.19.0` |
| Java / Maven | Temurin `25.0.4.1` / Apache Maven `3.9.11`，项目按 Java 17 release 编译 |
| 机器 | Windows `win32/x64`；CPU 型号记录在原始 JSON |
| HTTP 模式 | `127.0.0.1` 隔离 HTTP stub；不读取真实账号数据 |
| fixture | 100 个订阅、96 个 Mikan 条目、50 张内存小图片 |
| 原始数据 | 最终提交后由 `pnpm measure:baseline` 生成 |

## 可重复命令

```text
cd ani-rss-ui
pnpm install --frozen-lockfile
pnpm test
pnpm build:verify
pnpm check:bundle
pnpm measure:baseline
```

后端编译使用隔离 Maven 路径，PowerShell 参数必须整体引用：

```text
& 'C:\Users\tendo\.cache\codex-runtimes\anirss-build\apache-maven-3.9.11\bin\mvn.cmd' -B '-Dskip.frontend=true' '-DskipTests' '-pl' 'ani-rss-application' '-am' 'compile'
```

`measure:baseline` 启动一次本地 HTTP stub，生成新的 `docs/performance-data/t0-<commit>.json`。它测量请求调度和前端纯逻辑，不冒充完整 Java 服务或真实上游性能。

## 隔离 fixture 实测结果

| 场景 | 实测结果 | 验收解释 |
| --- | --- | --- |
| 热 Mikan 列表 | 最终提交后重跑 | 仅证明本地 stub 往返，不证明真实 Mikan p95 |
| Mikan 评分 | 96 条，批量上限 48；外部 stub 请求 `2`；剩余 retryable `0` | 证明首轮覆盖两个批次；真实评分源仍未测 |
| 同资源图片 | 20 个消费者、相同 key 外部请求 `1` | 证明 fixture 单飞；Java `ImageCacheServiceTest` 另测认证/重启缓存 |
| 图片失败恢复 | 首次失败后下一轮恢复，恢复实体 `15` bytes；含恢复共 `3` 次外部请求 | 证明 fixture 可恢复；30 秒失败缓存由 Java 实现测试/审查 |
| 慢下载器轮询 | 响应模拟 8 秒；隐藏后只启动 `1` 个请求；最大在途 `1` | 仅验证前端调度模型，真实下载器未连接 |
| RSS 刷新 | 最终提交后重跑 | 证明 fixture 不包含固定等待；RssTask/真实源仍需 Java/下载器环境复核 |
| 首屏依赖闭包 | JS gzip `105,196` bytes，CSS gzip `48,953` bytes | 对照 `562454f2` 实测首入口 JS 闭包 `431,801` bytes，减少约 `75.6%`；门禁见 `scripts/bundle-budget.json` |

## 已通过的代码门禁

- 前端 Vitest：6 tests passed，包含 96 条评分分批、失败重试、截止时间和首批悬挂不阻塞后续批次场景。
- `pnpm build:verify`：隔离输出构建通过。
- `pnpm check:bundle`：按 manifest 入口闭包统计，播放器/Markdown/备份内容禁入，25% JS 门槛通过。
- 后端 Java release 编译通过。

## 仍未宣称完成的指标

- 真实 Mikan、Bangumi、AnimeGarden、图片代理响应的 200ms/2s/超时/429/500 分布。
- 真实浏览器登录、首屏 LCP、路由/弹窗操作、媒体 Range/字幕/外部播放器操作。
- 真实下载器 8 秒响应、100 订阅 RSS 周期的锁等待、数据库调用、文件 walk 和缺集恢复行为。
- 运行中 Java 图片缓存的失败 30 秒冷却、容量淘汰失败报告和进程重启后的真实目录复用。
- Docker Desktop/Linux engine 本地构建；多架构镜像和 GitHub Release 只在发布工作流成功后才能单独列为已验证。

这些项目需要隔离账号、下载器、媒体句柄或浏览器服务；没有这些外部条件时保留为待验证，不用 stub 数值替代。
