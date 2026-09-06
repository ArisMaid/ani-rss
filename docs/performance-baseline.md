# Fork 性能基线与验收记录

本文件记录本次 fork 修复的可复现基线。它只记录已经执行或明确待执行的内容，不把目标值写成已测结果。

## 版本与环境

| 项目 | 基线/本次验证值 |
| --- | --- |
| 修复前提交 | `562454f287645531e09bb2ada65afd31dbfa4091` |
| 修复前版本 | `3.2.28.58` |
| 目标版本 | `3.2.28.59` |
| Node / pnpm | Node `v24.16.0` / pnpm `11.19.0` |
| Java | Temurin `25.0.4.1`，项目编译目标仍为 Java 17 |
| Maven | 隔离运行时 Apache Maven `3.9.11` |
| Docker | Docker CLI 可用；本机 Linux engine 未启动，因此没有本地镜像结果 |

## 可重复命令

前端验证不写入 `dist`，使用隔离目录：

```text
cd ani-rss-ui
pnpm install --frozen-lockfile
pnpm test
pnpm build:verify
pnpm check:bundle
```

后端验证不调用 `clean`：

```text
mvn -B -Dskip.frontend=true verify --file pom.xml
```

发布构建由 `package.sh` 和带 `v*` 标签的 GitHub Actions 完成；它们使用正常的 `package` 阶段，不以清理历史输出作为验证前置条件。

## 重点场景

| 场景 | 观测项 | 验收状态 |
| --- | --- | --- |
| 登录首屏 | 路由/对话框首次 bundle 不包含播放器、Markdown 或备份大块 | 已由 `check:bundle` 检查入口依赖；数值随构建产物记录 |
| Mikan 列表 | 列表先返回；评分只在当前星期、最多 48 条一批、20 秒上限 | 已实现；后端 `MikanServiceTest`、`PublicScoreServiceTest` 与前端测试通过 |
| Mikan 折叠 | 同一资源单飞；切换后旧请求不能覆盖新结果 | 已实现；需在带真实 Mikan 数据的浏览器会话复核 |
| 播放器 | 视频先显示；内封字幕异步加载并撤销 Blob URL；外部播放先换取句柄 | 已实现；需在带媒体句柄的浏览器会话复核 |
| 图片 | 公共 URL 使用认证 GET、SHA-256 共享缓存、ETag 和 2 小时 TTL | 已实现；`ImageCacheServiceTest` 5 项通过 |
| 订阅刷新 | 后端状态从 running 到完成/失败，前端按完成状态结束 loading | 已实现；需在真实下载器连接下复核 |
| 下载器故障 | 获取任务快照失败时不当作空列表 | 已实现；源码契约检查与后端编译通过 |

尚未在本机宣称的指标：真实上游响应 P50/P95、浏览器首屏 LCP、下载器真实刷新耗时和多架构镜像耗时。这些指标需要固定的网络、账号与下载器夹具；发布流水线成功后再补充实际值。
