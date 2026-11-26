# Agent Note: 按功能同步上游 v3.2.55–v3.3.1 至 3.3.1.81

Status: implemented

## Problem

上游 `wushuo894/ani-rss` 从 `v3.2.54` 演进至 `v3.3.1`，包含 Bangumi 日期解析修复、跟随 Bangumi 正篇编号的自动偏移策略、首页删除订阅后刷新修复、AnimeGarden 封面直连、AstrBot 通知（含封面上传）、通知管理页布局与快捷开关、WASM 播放器异步加载提示、默认登录凭据提醒、`ani-rss-desktop` 更新托管以及前后端依赖更新。

直接合并上游会破坏本 fork 多项长期合同：
1. 上游升级至 JDK 25 并在部分路径使用 Java 21+ `SequencedCollection` API（`getFirst` / `addFirst`），会破坏本 fork 的 Java 17 编译与 ARM32 镜像合同；
2. 上游在 `v3.3.1` 移除了 `ani-rss.exe`、macOS 打包、`autoStart` 开机自启及 Windows/macOS 自更新；
3. 上游改动未覆盖本 fork 的 `AuthService` 认证体系、`ConfigService` 密钥脱敏回填、主备 RSS 独立偏移、AnimeGarden 快照隔离（F08/F10/F24/F35/F36）及首屏按需加载预算（F16）。

## Decision

按能力逐项同步 `v3.2.55–v3.3.1` 的功能与缺陷修复，版本号按四段规则迭代为 `3.3.1.81`（`3.3.1.80` 打包时因上游 `wushuo894/ani-rss-macos` 归档已返回 404 未发布，随后在 `package.sh` 移除 `macos` profile 并递增至 `.81`），全部改动严格适配并保留既有 fork 合同：

1. **日期、重命名与下载器**：
   - `DateAdapter` 改用线程安全的 `DateUtil.formatDate` / `DateUtil.parse`，新增 `yyyy-MM` 解析，并对 `yyyy-MM-dd` 前缀先用 `LocalDate.parse` 校验以拒绝越界日期。
   - `RenameUtil` 不再剥离标题中的 `+NCOPED`；`qBittorrent.start` 仅调用 `/api/v2/torrents/start`（移除因 `requireSuccess` 抛异常而不可达的 `/torrents/resume` 回退）。
2. **自动偏移策略（`OffsetTypeEnum` / `BaseOffset` / `AutoOffset` / `BgmOffset`）**：
   - `Config.offsetType` 默认为 `AUTO_OFFSET`，旧配置缺失时在 `ConfigUtil.format` 归一化为 `AUTO_OFFSET`。
   - `BgmOffset` 使用 `Map<String, Integer>` 以 `subjectId` 缓存最小正篇集数（避免以可变实体 `Ani` 作键在 `setSeason(1)` 后破坏哈希），过滤空的 `sort` 值并默认回退到 `1`。
   - `AniUtil.getAni` 在启用自动偏移时复用同一策略实例计算主备 RSS，并保留每个源以 `offset=0` 副本独立解析的设计，同时保留策略对 `ani.season` 的调整。
   - `BgmUtil.getEpisodeId` 与 `getEpisodeTitleMap` 依据 `offsetType` 匹配 `sort` 或 `ep`（缓存键包含 `offsetType`），全程维持 Java 17 兼容。
3. **AnimeGarden 封面与快照边界**：
   - `AnimeGardenService` 对合法数字 `id` 直接拼装 `https://bgm.animes.garden/bangumi/subject/{id}/poster.jpeg?quality=small`，不再阻塞或依赖 `cache.wushuo.top/bgm/cover`，同时保留快照隔离、48 项上限、`ANIME_GARDEN_LIST_EXPIRED`（HTTP 409）与评分重试合同。
4. **AstrBot 通知与密钥保护**：
   - 新增 `NotificationTypeEnum.ASTRBOT`、`NotificationConfig` 默认字段、`NotificationUtil` `Map.ofEntries` 注册与 `AstrBotNotification`（禁用重定向，本地封面路径经 `PathPolicy` 限定在 `config/files` 内，缺失时降级纯文本）。
   - `ConfigService.clearSecrets` 与 `preserveNotificationSecrets` 纳入 `astrBotApiKey`，确保对外脱敏且仅按唯一通知身份回填。
5. **默认登录凭据提醒与首屏预算**：
   - `AuthService.isDefaultLogin(Config)` 同时识别未配置状态以及 MD5 / Argon2 形态的默认 `admin/admin`；`ConfigService.config()` 在清空密码前计算 `login.isDefault`，`setConfig()` 在落盘前清除该瞬态字段。
   - 前端将提醒弹窗拆为懒加载组件 `DefaultLoginDialogView.vue`，仅在 `login.isDefault === true` 且未勾选“不再提醒”时加载，避免将 `el-dialog` / `el-checkbox` 打入首屏闭包；跳转目标对齐 fork 安全页签 `/settings?tab=login`（`ConfigView.vue` 同时兼容 `tab=security`）。
   - `ani-rss-ui/scripts/bundle-budget.json` 仅为 `element-plus 2.14.8` 共享样式增量（+330 B）放宽三项 CSS 预算，全部 JS 预算与 `forbiddenModules = []` 保持不变。
6. **前端交互与桌面更新兼容**：
   - `DashboardView.vue` 为懒加载 `DelAniView` 绑定 `@callback="reloadAfterDelete"`，删除订阅后同步刷新首页与订阅缓存列表。
   - `NotificationView.vue` 采用自适应网格、启用/禁用状态点与快捷启停菜单；`PlayStartView.vue` 保持 WASM/标准双引擎切换并补齐 WASM 按需加载提示与失败反馈；`AfdianView.vue` 移除旧讨论链接。
   - `BaseUpdate.getInstance()` 优先响应 `Global.args().contains("--ani-rss-desktop=true")` 返回 `DesktopUpdate`（暂存 `.pending` 后退出），同时完整保留 Windows EXE、开机自启、`linux/` 的 `master` 分支地址以及 Java 17 发布合同；因上游已移除 `wushuo894/ani-rss-macos` 归档（HTTP 404），`package.sh` 调整为 `-P windows` 不再触发旧版 macOS 启动器下载。
7. **依赖同步**：
   - 后端同步 Spring Boot `4.2.0`、Spring AI `2.1.0-M1`、CommonMark `0.32.0`、springdoc `3.2.0` 及与 Boot 4.2.0 一致的 Jackson/Logback BOM；前端同步 Element Plus `2.14.8`、Vue `3.5.46`、vue-router `4.6.5`、Vite `8.3.8`、`@vitejs/plugin-vue 6.0.11` 与 `terser 5.51.4`。

## Alternatives considered

1. **直接合并上游 `v3.3.1` 并在冲突处手工修复**
   - 最强理由：保留 Git 合并拓扑，后续追踪上游提交更直接。
   - 不采用原因：fork 与上游在认证、归属、自动洗版、图片缓存、前端分包及 Java 17/EXE 发布合同上深度分叉，整仓合并会产生大面积冲突并有静默覆盖既有合同的高风险。
2. **跟随上游删除 `ani-rss.exe` 与 `autoStart` 开机自启**
   - 最强理由：减少 `windows`/`macos` Maven profile 与 `ani.rss.start.*` 维护面，与上游 `ani-rss-desktop` 路线完全一致。
   - 不采用原因：本 fork 当前未独立构建分发 `ani-rss-desktop`，Release 仍向用户交付 `ani-rss.exe`；在保留既有打包与开机自启的同时支持 `--ani-rss-desktop=true`，可做到零破坏兼容。
3. **将默认登录提醒对话框直接内联在 `MainLayoutView.vue`**
   - 最强理由：组件结构更少一个文件，与上游实现形式一致。
   - 不采用原因：实测内联会向 `home`/`subscriptions` 首屏闭包引入约 1–2.3 KB CSS gzip 与 7.6 KB JS gzip，违反 F16 首屏按需加载原则；拆分为懒加载组件 `DefaultLoginDialogView.vue` 后仅默认凭据用户按需加载。

## Consequences

- **收益**：
  - 完整获得上游 `v3.2.55–v3.3.1` 的功能演进与缺陷修复，并修复了上游 `BgmOffset` 以可变实体作 Map 键、`DateAdapter` 越界日期宽松解析、以及 `MainLayoutView` 首屏体积膨胀三处隐患。
  - 全部既有 fork 合同（Java 17、ARM32、Windows EXE、AuthService、密钥脱敏、主备 RSS 偏移、AnimeGarden 快照隔离、首屏禁入检查）保持完整。
- **代价与限制**：
  - 本 fork 仍需在后续同步上游时继续按能力审计而非直接 `git merge`，并继续维护 `windows`/`macos` 打包与 `ani.rss.start.*`。
  - 后端使用 Spring AI `2.1.0-M1` 里程碑版本以匹配 Spring Boot `4.2.0`，后续需关注正式版发布并及时替换。
  - AstrBot 与 qBittorrent 启动行为在本地通过隔离 HTTP 夹具验证，桌面端真实替换重启流程未在 `ani-rss-desktop` 实机联调。

## Testing

- **后端定向验证（JDK 17）**：10 个定向测试类共 55 项全部通过（覆盖 `DateAdapterTest`、`BgmOffsetTest`、`Upstream52SubscriptionTest`、`AnimeGardenServiceTest`、`W6FixturePerformanceTest`、`AstrBotNotificationTest`、`ConfigServiceSecretTest`、`AuthServiceTest`、`BaseUpdateTest`、`DownloaderFailureContractTest`），SpotBugs Max/Medium 零告警。
- **前端本地验证（Node 26.12.1 / pnpm 12.15.0）**：10 个测试文件共 56 项全部通过，`pnpm install --frozen-lockfile`、唯一目录生产构建及 `pnpm check:bundle` 通过（`forbiddenModules = []`）。
- **远端 CI 与发布验证**：推送 `master` 后通过 GitHub Actions `build-test`（全量 `mvn -B verify -Pci` + 前端 + W7/N08 浏览器 smoke）及 `v3.3.1.81` 精确 tag `build` 工作流（Release 附件与 GHCR 三组多架构镜像）核验。
