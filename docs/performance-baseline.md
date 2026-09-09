# Fork 性能基线与验收记录

本文件只记录实际执行结果。静态 bundle、浏览器启动资源和 Java Service fixture 分开统计；合成边界不冒充真实外部服务性能。当前发布单元为 `v3.2.28.67`，历史版本仅作对照，不把旧版更早的首入口数字当作本轮下降结论。

## 当前发布单元：v3.2.28.67

| 项目 | 实际值 |
| --- | --- |
| 版本/标签 | `3.2.28.67` / `v3.2.28.67`；精确提交 `7dfa07a5cce27d92330dc3562fc2b887b755e7d3` |
| 功能修复 | Mikan 异步评分排序、首页订阅卡片按需注册、跨天日期响应式刷新、设置项命名澄清 |
| Node / pnpm | Node `v24.16.0` / pnpm `11.19.0` |
| 外部边界 | 隔离 HTTP fixture 和本地浏览器检查；不读取生产账号或文件 |

### 本轮实际测量

- 前端 Vitest：5 个文件、35/35；未新增永久测试文件。
- 生产 UI 使用唯一目录 `ani-rss-ui/target/release-67-dist-20260909` 构建，未清理既有输出；bundle gate 通过。

| 场景 | JS gzip | CSS gzip | 预算结果 |
| --- | ---: | ---: | --- |
| `staticEntryClosure` | 105,227 B | 48,953 B | 通过 |
| login | 202,044 B | 63,229 B | 通过 |
| home | 192,264 B | 61,822 B | 通过 |
| subscriptions | 186,271 B | 63,912 B | 通过 |

首页 `AniCoverView` 是非空当天订阅才触发的按需 chunk；上述静态闭包数字不代表完整非空首页冷加载网络总量。浏览器检查使用隔离 fixture，不推导真实外部源吞吐或下载器延迟。

### 当前发布产物

[`build-test` run 34341684631](https://github.com/ArisMaid/ani-rss/actions/runs/34341684631) 与 [`v3.2.28.67` tag build run 34342179506](https://github.com/ArisMaid/ani-rss/actions/runs/34342179506) 均成功；[GitHub Release v3.2.28.67](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.67) 已发布。`ani-rss.jar` SHA-256 为 `bf14c585b45ef6e1dc3eb4cf5e383fed9df0b19ba5b127df5ed11de3b8e8b646`，`ani-rss.exe` SHA-256 为 `4fb6b1c7d43ca21e6055b94e0b28d508d5a8e949ebcbed6a6a349b46d0d09548`。

| 镜像 tag | manifest index digest | 平台 |
| --- | --- | --- |
| `ghcr.io/arismaid/ani-rss:v3.2.28.67` | `sha256:d207f3ce31dbab4e7702d4dc6df55cd7c19bf4e9b72feb4702b85cf7f1d4e4fd` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.67-openj9` | `sha256:159b37df9b99bf6479f08f9918f76ffcfa992be2d3c1006f5c36219e2b34f2ef` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.67-arm32v7` | `sha256:2f09c2cb6c5fafaa723acded7b369fd4f301373f2e1ac76fb87da307838f4c8f` | `linux/arm/v7` |

Docker Hub 登录因未配置凭据按 workflow 条件跳过；GHCR 三组镜像已成功发布。本轮不重建 `v3.2.28.66`。

## 历史对照：v3.2.28.66

| 项目 | 实际值 |
| --- | --- |
| 版本/标签 | `3.2.28.66` / `v3.2.28.66`；精确指向 `7110dbc3fb8c2c61a9aed10b25179d5eb3d2d628` |
| 修复提交 | `25b3a05c`（AnimeGarden）、`30cdda4d`（ImageCache） |
| Node / pnpm | Node `v24.16.0` / pnpm `11.19.0` |
| Java / Maven | Temurin `25.0.4.1` / Apache Maven `3.9.11`，项目按 Java 17 release 编译 |
| 外部边界 | 本地测试与 `127.0.0.1` 合成 fixture；不读取生产账号或文件 |

### 本轮实际测量

- 前端 Vitest：5 个文件、35/35；Java 完整门禁 319/0/0/4 skipped；ImageCacheService 定向回归 19/19。
- 生产 UI 使用唯一目录 `ani-rss-ui/target/release-66-dist-20260908` 构建，未清理既有输出；bundle gate 通过。

| 场景 | JS gzip | CSS gzip | 预算结果 |
| --- | ---: | ---: | --- |
| `staticEntryClosure` | 105,198 B | 48,953 B | 通过 |
| login | 201,952 B | 63,229 B | 通过 |
| home | 192,171 B | 61,822 B | 通过 |
| subscriptions | 185,252 B | 63,645 B | 通过 |

本轮没有新增 W6/W7 性能报告或浏览器矩阵，不能从状态修复或单元测试推导生产吞吐、真实浏览器首屏或外部源延迟提升。

### 当前发布产物

GitHub Actions [`build-test` run 34217520584](https://github.com/ArisMaid/ani-rss/actions/runs/34217520584) 与 tag build [`34218050737`](https://github.com/ArisMaid/ani-rss/actions/runs/34218050737) 均成功；[GitHub Release v3.2.28.66](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.66) 已上传 `ani-rss.jar`（SHA-256 `d4cec166687140c1dbaf9fb9080f84bc834497804b7ed04f9c4e8b5d1a1d0c93`）和 `ani-rss.exe`（SHA-256 `eed79eb382704aa3a351f158a0bcf842f2a9f43ba089da65eb473af4a618cf28`）。

| 镜像 tag | manifest index digest | 平台 |
| --- | --- | --- |
| `ghcr.io/arismaid/ani-rss:v3.2.28.66` | `sha256:e07a41a796cf18d945426abfb3401d733feb0b0454fba1eb71ce60374d50cc0e` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.66-openj9` | `sha256:968509e112362318f1e596362391d14dc7e9d62e878203c3ab4d84f955f6bb00` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.66-arm32v7` | `sha256:752e2b60fd23a78486ee17b95eda17339eca04ee2e1230875dc86082c6873742` | `linux/arm/v7` |

Docker Hub 登录因 secrets 未配置而按 workflow 条件跳过；GHCR 三组镜像已成功发布。

## 历史对照：v3.2.28.65

| 项目 | 实际值 |
| --- | --- |
| 版本/标签 | `3.2.28.65` / `v3.2.28.65`；tag 精确指向 `fa32cb97e74b207b163e902483663e8b10137f12` |
| 修复提交 | `2d248fb2`（AnimeGarden）、`6fb81a17`（ImageCache）、`6abdd335`（close） |
| Node / pnpm | Node `v24.16.0` / pnpm `11.19.0` |
| Java / Maven | Temurin `25.0.4.1` / Apache Maven `3.9.11`，项目按 Java 17 release 编译 |
| 外部边界 | 本地测试与 `127.0.0.1` 合成 fixture；不读取生产账号或文件 |

### 本轮实际测量

- 前端 Vitest：5 个文件、35/35；Java 完整门禁 319/0/0/4 skipped；ImageCacheService 定向回归 19/19。
- 生产 UI 使用唯一目录 `ani-rss-ui/target/release-65-dist-20260908` 构建，未清理既有输出；bundle gate 通过。

| 场景 | JS gzip | CSS gzip | 预算结果 |
| --- | ---: | ---: | --- |
| `staticEntryClosure` | 105,197 B | 48,953 B | 通过 |
| login | 201,952 B | 63,229 B | 通过 |
| home | 192,170 B | 61,822 B | 通过 |
| subscriptions | 185,249 B | 63,645 B | 通过 |

本轮没有新增 W6/W7 性能报告，不能从状态修复或单元测试推导生产吞吐、真实浏览器首屏或外部源延迟提升。

### 当前发布产物

GitHub Actions build run [`34194388721`](https://github.com/ArisMaid/ani-rss/actions/runs/34194388721) 成功；[GitHub Release v3.2.28.65](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.65) 已上传 `ani-rss.jar`（SHA-256 `1d0864f0322596e9b87e6211b09b8a248c31b6146e48d2a67d9672fc47ab4427`）和 `ani-rss.exe`（SHA-256 `6f458fdccd567fb7e2f7ed8b137514146d1eb41b98734338b8332f23b9485100`）。

| 镜像 tag | manifest index digest | 平台 |
| --- | --- | --- |
| `ghcr.io/arismaid/ani-rss:v3.2.28.65` | `sha256:df45b9b853cd84d2ce81215de36a58ee00d5d250bc21b724b10c842e8fcc8331` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.65-openj9` | `sha256:0d3cec909ba03616b51169303c8132224fe32a528396c0e62934719d94cbf4ee` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.65-arm32v7` | `sha256:382c7ddb2101bfa15ca6723d5266d1aff197121a59881c3a2e5fa071329fe9be` | `linux/arm/v7` |

Docker Hub 登录因 secrets 未配置而按 workflow 条件跳过；GHCR 三组镜像已成功发布。

## 历史对照：v3.2.28.64

| 项目 | 实际值 |
| --- | --- |
| 版本/标签 | `3.2.28.64` / `v3.2.28.64`；tag 精确指向 `6fd88343bbdb3ef4b16500693496907356f72f5f` |
| 被测行为代码 | `bff06cb0618be5d77c7185f7dd09c6dc5cd0ebb0` |
| 主要实现提交 | `da4a0c23`；ImageCache 修正 `bff06cb0` |
| 版本提交 | `21048e34` |
| 主对照 | `v3.2.28.63`，用于历史行为/包体对照 |
| Node / pnpm | Node `v24.16.0` / pnpm `11.19.0` |
| Java / Maven | Temurin `25.0.4.1` / Apache Maven `3.9.11`，项目按 Java 17 release 编译 |
| 机器 | Windows `amd64`；浏览器 raw JSON 记录 CPU/架构 |
| 外部边界 | `127.0.0.1` 合成 HTTP、loader/downloader stub；不读取生产账号或文件 |

### 原始数据与静态 bundle

- [`w7-browser-v3.2.28.64-20260907.json`](performance-data/w7-browser-v3.2.28.64-20260907.json)：唯一生产 dist 的 login/home/subscriptions cold 场景；`commit=bff06cb...`、`dirty=false`，四类页面错误均为 0。
- [`n08-browser-v3.2.28.64-20260907.json`](performance-data/n08-browser-v3.2.28.64-20260907.json)：唯一生产 dist 的 8 秒慢轮询；停留 `39,036ms`，总请求 3、hidden 请求 1、`maxInFlight=1`，手动刷新和可见恢复均为 true。
- Java 完整门禁为 319/0/0/4 skipped，前端为 35/35；ImageCacheService 定向回归为 19/19。

| 场景 | JS gzip | CSS gzip | 预算结果 |
| --- | ---: | ---: | --- |
| `staticEntryClosure` | 105,197 B | 48,953 B | 通过 |
| login | 201,953 B | 63,229 B | 通过 |
| home | 192,172 B | 61,822 B | 通过 |
| subscriptions | 185,251 B | 63,645 B | 通过 |

没有从 ImageCache 单元测试推导生产吞吐或百分比提升；真实外部源、下载器、账号、媒体、数据库费用和 Linux engine 仍保持未验证边界。

### 当前发布产物

GitHub Actions build run [`34110438143`](https://github.com/ArisMaid/ani-rss/actions/runs/34110438143) 成功；[GitHub Release v3.2.28.64](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.64) 已上传 `ani-rss.jar`（SHA-256 `1b9fbb12ae6381bac8b2c721561b2c1d7ed6f6ecfe67d4a6afda9a9fe48057e5`）和 `ani-rss.exe`（SHA-256 `51c947ecc7e2594b788cdd2df5bbfe3a1d68a08995b3bcbe6037dace375854ec`）。

| 镜像 tag | manifest index digest | 平台 |
| --- | --- | --- |
| `ghcr.io/arismaid/ani-rss:v3.2.28.64` | `sha256:6ec394b5ec4040b06f839d5708a21e59a99354a9a8fd10877f9c253ba5edc89f` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.64-openj9` | `sha256:36119d0eed47d1eeb455e0aed8112397511a58bf3421f421eceb2797ea41ef31` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.64-arm32v7` | `sha256:0bcff0e023167e6c54dad5750ff6c95e6d05b871ffa86b19dc92eb1383d9dc4d` | `linux/arm/v7` |

Docker Hub 登录因 secrets 未配置而按 workflow 条件跳过；GHCR 发布已成功。

## 历史对照：v3.2.28.63

| 项目 | 实际值 |
| --- | --- |
| 版本/标签 | `3.2.28.63` / `v3.2.28.63`；tag 精确指向 `ea9bde31a7d8440fff2778d995f563ace3c8c857` |
| 被测行为代码 | `f550cdddf57be2bf73185327e003aa2cbce18fbc` |
| 版本提交 | `0c625810` |
| 主对照 | `v3.2.28.62`，发布提交 `cce1190ecda820ff5a495265c59b9d729ab8a734` |
| Node / pnpm | Node `v24.16.0` / pnpm `11.19.0` |
| Java / Maven | Temurin `25.0.4.1` / Apache Maven `3.9.9`，项目按 Java 17 release 编译 |
| 机器 | Windows 11 `amd64`；浏览器 raw JSON 记录 CPU/架构 |
| 外部边界 | `127.0.0.1` 合成 HTTP、loader 和 downloader stub；不读取生产账号或文件 |

### 原始数据与时钟

- [`w6-services-v3.2.28.63-20260907.json`](performance-data/w6-services-v3.2.28.63-20260907.json)：96 个 Mikan 条目、2 周、AnimeGarden 两类消费者、50 个逻辑图片引用；`commit=f550cdd...`、`dirty=false`。
- [`w6-hot-list-v3.2.28.63-20260907.json`](performance-data/w6-hot-list-v3.2.28.63-20260907.json)：cold、30 次进程内 hot、重启持久缓存；cold `90.6249ms`，hot p95 `0.0395ms`，restart `0.2971ms`。
- [`w6-rss-v3.2.28.63-20260907.json`](performance-data/w6-rss-v3.2.28.63-20260907.json)：实际 RSS 服务链、100 个订阅、`clockMode=shared-fake-monotonic-and-rss-sleeper`；请求 100、list 10、snapshot hits/misses `90/10`、虚拟节流 `50,000ms`、墙钟 `1,177ms`。
- [`w6-rss-add-v3.2.28.63-20260907.json`](performance-data/w6-rss-add-v3.2.28.63-20260907.json)：真实 add fixture，RSS 请求 1、downloader add 1、`SUCCESS`。
- [`n08-browser-v3.2.28.63-20260907.json`](performance-data/n08-browser-v3.2.28.63-20260907.json)：生产构建的 8 秒慢轮询页面，实际停留 `39,025ms`；hidden 请求 1，visible/manual-refresh 后总请求 3，`maxInFlight=1`，每次约 `8,010ms`，无 page/console/chunk/media 错误。

W6 RSS 的旧测试曾只推进虚拟 sleep 而没有推进 `TorrentUtil` 的真实 `nanoTime`，因此旧版“虚拟 50 秒、list 一次”不能证明 5 秒过期。本版本在 `TorrentSnapshotCycle` 注入同一 fake monotonic clock，sleep 和快照年龄共用时间轴，并对 `age >= 5s` 与 dirty 读取做了单元及实际服务链验证。W6 仍明确把 DB、lock、file-walk 和 downloader files/move/rename/delete 未测字段写为 `null`。

### 静态 bundle 门禁

本次生产输出为唯一目录 `ani-rss-ui/target/n08-dist-20260907-c`，执行 `pnpm check:bundle` 时通过 `BUNDLE_OUTPUT_DIR` 指向该目录；没有清空目录或自动更新预算。

| 场景 | JS gzip | CSS gzip | 预算结果 |
| --- | ---: | ---: | --- |
| `staticEntryClosure` | 105,198 B | 48,953 B | 通过 |
| login | 201,954 B | 63,229 B | 通过 |
| home | 192,174 B | 61,822 B | 通过 |
| subscriptions | 185,253 B | 63,645 B | 通过 |

与 `v3.2.28.62` 的本地基线相比，当前 static JS 的变化是跨平台构建输出范围内的个位数差异；没有把它宣传成整体页面性能提升，也没有将播放器、Markdown、备份动态依赖漏计进首屏。

### 当前边界

浏览器 API/auth 和 RSS/downloader transport 是隔离 fixture；Vue 生产构建、真实 loader、router、组件交互和 Java 服务链是真实逻辑。未验证真实外部源、生产账号、下载器、媒体、数据库费用和 Linux engine；GHCR 平台结论见下方实际 tag workflow 结果。

### 当前发布产物

GitHub Actions build workflow [`34098183511`](https://github.com/ArisMaid/ani-rss/actions/runs/34098183511) 成功；[GitHub Release v3.2.28.63](https://github.com/ArisMaid/ani-rss/releases/tag/v3.2.28.63) 已上传 `ani-rss.jar`（SHA-256 `50c274304cc6673bbcffd17ef8e6c11a31a122534041c61ce9be12d395501ee2`）和 `ani-rss.exe`（SHA-256 `f1ba758e7bdbbad09f782e790e4d3c679b0920e002287c0b51f2b454fd7db013`）。

| 镜像 tag | manifest index digest | 平台 |
| --- | --- | --- |
| `ghcr.io/arismaid/ani-rss:v3.2.28.63` | `sha256:4c5b0feb9785dc879b84ccfef8f3463fb84d56cfdd1a3d990a11bae751a84d46` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.63-openj9` | `sha256:b59623c65e44bd59a4344eed509086dc502268156ba8a12518b559d9f7e59705` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.63-arm32v7` | `sha256:35b42191fe094c8f37eb8f7e22adc4e0e02589258fd71a6f75a5badbbe1b1548` | `linux/arm/v7` |

Docker Hub 登录步骤因 secrets 未配置而按 workflow 条件跳过；GHCR 发布已成功，Docker Hub 不在本版本发布范围内。

## 历史 v3.2.28.62 记录

### 版本、对照与环境

| 项目 | 实际值 |
| --- | --- |
| 发布版本 | `3.2.28.62`，已发布标签 `v3.2.28.62`；tag workflow `34077710357` |
| 行为代码提交 | `19dfaf3a` |
| 版本提交 | `674f7b55` |
| 最终证据提交 | `4df5e7e2` |
| 最新 fork 主对照 | `02990a97dcb993040ade2c121b94ebd9eefe2f3e` (`v3.2.28.61`) |
| 历史目标对照 | `562454f287645531e09bb2ada65afd31dbfa4091`，仅用于历史说明 |
| Node / pnpm | Node `v24.16.0` / pnpm `11.19.0` |
| Java / Maven | Temurin `25.0.4.1` / Apache Maven `3.9.11`，项目按 Java 17 release 编译 |
| 机器 | Windows 11 `amd64`；细节见原始 JSON |
| 外部边界 | `127.0.0.1` 合成 HTTP、loader 和 downloader stub；不读取生产账号或文件 |

### 原始数据

- [`w6-services-v3.2.28.62-20260907.json`](performance-data/w6-services-v3.2.28.62-20260907.json)：96 Mikan、AnimeGarden 两类消费者、50 个逻辑图片引用。
- [`w6-hot-list-v3.2.28.62-20260907.json`](performance-data/w6-hot-list-v3.2.28.62-20260907.json)：冷/30 次进程内热/重启持久缓存。
- [`w6-rss-v3.2.28.62-20260907.json`](performance-data/w6-rss-v3.2.28.62-20260907.json)：实际 RssTask 服务链和 100 个启用订阅。
- [`w7-browser-v3.2.28.62-20260907.json`](performance-data/w7-browser-v3.2.28.62-20260907.json)：生产 Vite 输出的 8 个浏览器场景；`commit=4df5e7e2`、`dirty=false`。

### 发布产物

tag `v3.2.28.62` 指向 `cce1190e`。GitHub Release 已上传 `ani-rss.jar`（SHA-256 `fa4706e7429f7bfc12e24f954068e7d783bb11f4674cf23483eae4d801bf68ca`）和 `ani-rss.exe`（SHA-256 `87db32444a247da78f8a11e6c535588892c04ab09b3acea57ba8856374961b71`）。GHCR manifest index 已核验：

| 镜像 tag | index digest | 平台 |
| --- | --- | --- |
| `ghcr.io/arismaid/ani-rss:v3.2.28.62` | `sha256:bb33815c239e18b150ec2717400b98b3acfcdca633099a7799e1255a9b011358` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.62-openj9` | `sha256:54ec8316c552039a64f698c365816246395fe4aec8c9b3c2a04f322f7008bedd` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.62-arm32v7` | `sha256:4a7ff86fc683cc8476f9582f4682155e75ce3291a54e9640745747781b58fb06` | `linux/arm/v7` |

Docker Hub 推送因 workflow 未检测到凭据而跳过；这不是 GHCR 发布失败。

### 静态 bundle 门禁

构建输出为唯一目录 `ani-rss-ui/w7-dist-20260907-f`，使用固定 gzip 统计和 Vite manifest/module map；没有自动更新预算。

| 场景 | JS gzip | CSS gzip | 预算结果 |
| --- | ---: | ---: | --- |
| `staticEntryClosure` | 105,194 B | 48,953 B | 通过 |
| login | 201,949 B | 63,229 B | 通过 |
| home | 192,168 B | 61,822 B | 通过 |
| subscriptions | 185,246 B | 63,645 B | 通过 |

所有场景都没有播放器、Markdown 或备份模块；首页和订阅场景增加的共享入口引用已写入 [`ani-rss-ui/scripts/bundle-budget.json`](../ani-rss-ui/scripts/bundle-budget.json)，并保留原因、旧值和新值。GitHub Actions Ubuntu build-test `34077134774` 的静态 JS 为 `105,201 B`，本地 Windows 为 `105,194 B`；门禁上限仅增加到该已观测值 `105,201 B`，这是跨平台构建输出差异，不作为性能增长结论。历史 `562454f2` 的 431,801 B 首入口数字只作为旧口径说明，不推导本轮真实首屏下降比例。

### W7 浏览器资源

使用生产输出启动本地 fixture。登录、首页、订阅各自先在同一 session 中跑 cold，再跑 hot；播放和设置使用干净 session。每个场景等待明确 UI marker，并保留 500ms 稳定窗口。

| 场景 | JS/CSS 资源数 | 就绪时间 | page/console/chunk 错误 | 备注 |
| --- | ---: | ---: | --- | --- |
| login cold/hot | 42 / 42 | 1,809.4 / 1,997.7 ms | 0 / 0 / 0 | 未登录 CSRF/IP-login 各返回预期 401 |
| home cold/hot | 37 / 37 | 2,027.0 / 1,937.5 ms | 0 / 0 / 0 | 真实首页组件与轮询 fixture |
| subscriptions cold/hot | 42 / 42 | 1,748.1 / 1,981.4 ms | 0 / 0 / 0 | 真实订阅列表组件 |
| player | 46 | 3,477.7 ms | 0 / 0 / 0 | 实际点击播放列表并加载 ArtPlayer；合成视频解码事件列为 `mediaErrors` |
| settings | 67 | 1,798.9 ms | 0 / 0 / 0 | 实际设置页动态路由 |

浏览器传输统计、请求 URL、编码和错误原始值均保留在 W7 JSON；四条 HTTP 401 是故意的未登录边界，其他 HTTP 错误为 0。

### W6 Service fixture

| 场景 | 实测结果 | 口径限制 |
| --- | --- | --- |
| Mikan/AnimeGarden/图片 | 96 条 Mikan、2 周、最大组 64；AnimeGarden default/search 可独立补载；50 引用/25 URL；图片上游请求 27 | Mikan、AnimeGarden loader 和图片 HTTP 是合成边界；生产 Service、缓存和 lease 为真实实现 |
| 热 Mikan 列表 | cold `137.579ms`；30 次 in-process hot p95 `0.0598ms`；restart-persistent `0.801ms` | 单 Service 计时，DB 查询/锁等待/HTTP 为 null，不是生产 HTTP p95 |
| RSS 100 订阅 | RSS 请求 100；downloader connect/list 各 1；100/100 `SUCCESS`；虚拟节流 `50,000ms`；墙钟 `1,226ms` | 空 RSS 不进入 add/download、files、move、rename 或缺集恢复写路径；对应计数明确为 null |

### 可重复命令

```text
cd ani-rss-ui
pnpm install --frozen-lockfile
pnpm test -- --run
pnpm exec vite build --outDir w7-dist-<unique> --emptyOutDir false
$env:BUNDLE_OUTPUT_DIR = 'w7-dist-<unique>'; pnpm check:bundle
```

```text
& 'C:\Users\tendo\.cache\codex-runtimes\anirss-build\apache-maven-3.9.11\bin\mvn.cmd' '-B' '-Pci' '-Dskip.frontend=true' 'verify'
```

禁止以 `clean`、批量删除或覆盖历史输出作为测量前置条件。生产外部源、下载器和数据库费用采样继续按“未验证（原因）”记录，不能以 fixture 数字替代。
