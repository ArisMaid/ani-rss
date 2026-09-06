# 本次修复验证记录

## 已通过

- 前端 Vitest：3 个测试通过。
- 前端 `pnpm build:verify`：隔离输出 `.verify-dist` 构建通过。
- 前端 `pnpm check:bundle`：入口依赖门禁通过。
- 后端源码编译：Java 17 release 编译通过。
- 受影响后端测试：`ImageCacheServiceTest` 5、`MikanServiceTest` 6、`PublicScoreServiceTest` 15、`WebFilterTest` 1、`BackupServiceTest` 2 全部通过。
- 后端完整回归：286 tests，0 Failure，0 Error，4 skipped。
- 生产依赖审计：`pnpm audit --prod --audit-level high` 无已知漏洞；`nanoid` 已通过 workspace override 固定到 `3.3.18`。

## 需要发布环境复核

- 真实 Mikan、下载器和媒体句柄交互。
- Docker Desktop/Linux engine 本地构建与 smoke test；本机 engine 当前不可用。
- GitHub Actions 多架构构建、GHCR 推送和 GitHub Release 产物。

任何未完成项在发布流水线成功前都不标记为“已发布”。
