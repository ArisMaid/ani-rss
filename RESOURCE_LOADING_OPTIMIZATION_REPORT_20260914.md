# 资源加载优化实施记录

日期：2026-09-14

基准：`724c2ade116ab31e902ad8b9e15cf23b72b07921`（v3.2.28.67 发布记录）。目标发布版本为 `3.2.28.68`；本记录描述当前工作区对 `RESOURCE_LOADING_OPTIMIZATION_PLAN_20260914.md` 的实施，正式发布状态以精确 commit 的 `v3.2.28.68` tag workflow 为准。

## 已实施

- `SafeImageView` 增加 `active` 生命周期开关、换源/停用/卸载取消、重新激活后的懒观察器恢复、generation 防旧结果覆盖，以及单图占位和人工重试。资源弹窗将实际可见状态传入 `active`。
- 私有图片 POST 队列保持 6 个运行位置，等待容量收紧为 12；同 URL 任务保留共享底层请求但为每个消费者独立处理取消；队列等待上限为 3 秒、入队起总上限为 15 秒，单调时钟和底层 Promise `finally` 保证位置只归还一次。
- 公开图片缓存线程池保持 6 个工作线程、等待容量收紧为 12；任务记录入队时间和 3 秒最晚启动时间，工作线程在缓存检查后、上游抓取前丢弃过期任务，并将可重试忙碌错误完成给共享 future，不写入源站失败缓存。
- `AniCoverView`、`AniCardView` 使用 `loading="lazy"` 与 `decoding="async"`，失败显示占位；`SubscriptionListView.changeFilterList` 移除整表 JSON 深拷贝，但继续创建新的展示条目和排序数组。

## 本地验证

- `ani-rss-ui`: Vitest `35/35` 通过。
- 生产 Vite 构建：`ani-rss-ui/target/resource-optimization-20260914`，3499 modules transformed，成功。
- bundle checker：`forbiddenModules=[]`；最终构建实际 gzip 为 static `105229/48953`、login `202916/63229`、home `193135/61822`、subscriptions `187368/63970`（JS/CSS）。本轮生命周期和队列逻辑增加的 JS 体积已在 `ani-rss-ui/scripts/bundle-budget.json` 中单独记录，并只增加相应场景的明确余量。
- `git diff --check` 通过。
- 现有 W7 fixture 浏览器 smoke：`target/resource-optimization-20260914/w7-browser-report-final-20260914.json`；login/home/subscriptions 冷启动均页面错误、控制台错误、chunk 404、媒体错误为 0。
- 现有 N08 慢轮询 fixture：`target/resource-optimization-20260914/n08-browser-report-final-20260914.json`；停留 `39036ms`，`maxInFlight=1`，总请求 3，隐藏期间请求 1，手动刷新后可见恢复，错误计数均为 0。

## 尚未在本机完成的边界

- 本机未安装 Maven，`mvn -pl ani-rss-application -am -DskipTests compile` 无法启动；因此 Java 编译、SpotBugs 和后端回归需由 CI/具备 Maven 的环境执行。
- 本轮未接入真实外部图片源、生产账号、Docker/Linux 引擎或长列表性能录制；不能据此宣称所有环境的卡顿已消失。W7/N08 是页面和轮询回归证据，不等同于慢图片源、同图多消费者、KeepAlive/弹窗取消和失败重试的端到端压力证明；这些仍应在具备受控上游的环境中继续验收。
