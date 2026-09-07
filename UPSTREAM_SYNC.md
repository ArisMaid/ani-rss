# Fork 上游同步规则

本 fork 当前发布版本为 `3.2.28.62`（标签 `v3.2.28.62` 指向 `cce1190e`），上游前三段基线仍为 `v3.2.28`；本轮最终代码证据提交为 `4df5e7e2`。后续同步只审阅当前基线标签到目标标签之间的上游提交，不重复引入更早历史。

同步时必须保留以下本地合同：

1. 缺集恢复、SQLite 归属与备份迁移边界。
2. Cookie + CSRF 会话、401/403 语义、内网访问限制和 SSRF 防护。
3. 媒体短期句柄、外部播放器兼容和下载器失败不可伪装为空。
4. Mikan 列表先返回、按需/可取消评分、48 项批量上限、最多 2 个 worker 和 20s 总预算。
5. AnimeGarden subjects 先返回；补载允许部分对象和 retryable；48 项数值 ID 限制；不同消费者互不清空快照。
6. 公共图片认证 GET、跨会话 SHA-256 缓存、ETag、TTL、同 key lock/read lease、失败 30s/5000 上限和 manifest 恢复。
7. 评分 DB freshness 使用 `min(expires_at, updated_at+6h)`；异步写入携带观测时刻计算的绝对到期时间。
8. 懒路由/懒弹窗、静态 hash 资源长缓存与 HTML/API 不缓存；bundle 必须保留静态入口和实际场景两种口径。
9. 隐藏/deactivated 取消当前 generation，恢复后按 needsListReload 重取；备份超时保留 operationId 并提供重新查询入口。
10. 验证和打包流程不得用 `clean` 删除既有输出；发布必须由精确 commit 的 tag 工作流完成。
11. 版本号前三段跟随上游，第四段本地递增，并同步根 POM、模块 POM、前端默认版本和 `UPDATE.md`。

详细合同见 [`docs/fork-contracts.md`](docs/fork-contracts.md)，测量边界见 [`docs/performance-baseline.md`](docs/performance-baseline.md)，验收结果见 [`docs/verification.md`](docs/verification.md)。

## 同步审计步骤

1. 从当前四段版本号的前三段确定上游基线标签。
2. 只审阅该基线标签到目标标签之间的提交与差异；同时检查目标上游与 fork 的最终文件差异，不能只看提交标题。
3. 按能力映射“上游旧入口 → 新入口 → fork API → 对应行为测试”；目录改名按能力迁移。
4. 每项 fork 差异标记为“保留”“被上游等效替代”“明确淘汰”或“待确认”，并写出依据。
5. 同步 UI 时检查 `http.js` 使用点、v2/legacy 响应、媒体句柄、恢复状态和 package scripts；同步下载/清理功能运行合同测试。
6. 同步构建时比较首屏模块图、每场景包体预算和生产浏览器冒烟，保留 pageerror、console error、chunk 404 回归。
7. 版本沿用四段编号；同步上游前三段时第四段本地修订号递增一次，不夹带无关新功能。
8. 每次同步提交附一页验收记录，至少包含功能、网络次数、首屏包、已验证项目和未验证项目。

## 3.2.28.62 本轮审计记录

- 保留：R01/R02 部分对象 + retryable、R04 有界补载、R05 生成代际、R06 图片 lock/lease、R07 失败状态上限、R09 6h 评分 freshness、W6/W7 合成证据。
- 新增测试：后端 AnimeGarden/Cache/Image/Score 和 W6 三条服务链；前端 loader、两个实际 View lifecycle、播放和恢复契约；CI 生产唯一目录/bundle/browser smoke。
- 明确后置：真实外部源波动、真实下载器 8 秒页面停留、T7 DB/network/file 费用计数、Docker/Linux engine 本地构建。
- 结果：完整 Java `verify` 为 302 tests、0 failures、0 errors、4 skipped；W7 原始报告在 `4df5e7e2` 上 `dirty:false`。

Fork 版本号使用 `上游版本.本地修订号` 格式。例如 `3.2.1.48` 表示基于上游 `v3.2.1`，本地修订号为 `48`。从 `3.2.1.48` 同步上游 `v3.2.2` 时，审阅 `v3.2.1..v3.2.2`，发布版本为 `3.2.2.49`。
