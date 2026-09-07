# Fork 性能基线与验收记录

本文件只记录实际执行结果。静态 bundle、浏览器启动资源和 Java Service fixture 分开统计；合成边界不冒充真实外部服务性能。

## 版本、对照与环境

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

## 原始数据

- [`w6-services-v3.2.28.62-20260907.json`](performance-data/w6-services-v3.2.28.62-20260907.json)：96 Mikan、AnimeGarden 两类消费者、50 个逻辑图片引用。
- [`w6-hot-list-v3.2.28.62-20260907.json`](performance-data/w6-hot-list-v3.2.28.62-20260907.json)：冷/30 次进程内热/重启持久缓存。
- [`w6-rss-v3.2.28.62-20260907.json`](performance-data/w6-rss-v3.2.28.62-20260907.json)：实际 RssTask 服务链和 100 个启用订阅。
- [`w7-browser-v3.2.28.62-20260907.json`](performance-data/w7-browser-v3.2.28.62-20260907.json)：生产 Vite 输出的 8 个浏览器场景；`commit=4df5e7e2`、`dirty=false`。

## 发布产物

tag `v3.2.28.62` 指向 `cce1190e`。GitHub Release 已上传 `ani-rss.jar`（SHA-256 `fa4706e7429f7bfc12e24f954068e7d783bb11f4674cf23483eae4d801bf68ca`）和 `ani-rss.exe`（SHA-256 `87db32444a247da78f8a11e6c535588892c04ab09b3acea57ba8856374961b71`）。GHCR manifest index 已核验：

| 镜像 tag | index digest | 平台 |
| --- | --- | --- |
| `ghcr.io/arismaid/ani-rss:v3.2.28.62` | `sha256:bb33815c239e18b150ec2717400b98b3acfcdca633099a7799e1255a9b011358` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.62-openj9` | `sha256:54ec8316c552039a64f698c365816246395fe4aec8c9b3c2a04f322f7008bedd` | `linux/amd64`, `linux/arm64` |
| `ghcr.io/arismaid/ani-rss:v3.2.28.62-arm32v7` | `sha256:4a7ff86fc683cc8476f9582f4682155e75ce3291a54e9640745747781b58fb06` | `linux/arm/v7` |

Docker Hub 推送因 workflow 未检测到凭据而跳过；这不是 GHCR 发布失败。

## 静态 bundle 门禁

构建输出为唯一目录 `ani-rss-ui/w7-dist-20260907-f`，使用固定 gzip 统计和 Vite manifest/module map；没有自动更新预算。

| 场景 | JS gzip | CSS gzip | 预算结果 |
| --- | ---: | ---: | --- |
| `staticEntryClosure` | 105,194 B | 48,953 B | 通过 |
| login | 201,949 B | 63,229 B | 通过 |
| home | 192,168 B | 61,822 B | 通过 |
| subscriptions | 185,246 B | 63,645 B | 通过 |

所有场景都没有播放器、Markdown 或备份模块；首页和订阅场景增加的共享入口引用已写入 [`ani-rss-ui/scripts/bundle-budget.json`](../ani-rss-ui/scripts/bundle-budget.json)，并保留原因、旧值和新值。GitHub Actions Ubuntu build-test `34077134774` 的静态 JS 为 `105,201 B`，本地 Windows 为 `105,194 B`；门禁上限仅增加到该已观测值 `105,201 B`，这是跨平台构建输出差异，不作为性能增长结论。历史 `562454f2` 的 431,801 B 首入口数字只作为旧口径说明，不推导本轮真实首屏下降比例。

## W7 浏览器资源

使用生产输出启动本地 fixture。登录、首页、订阅各自先在同一 session 中跑 cold，再跑 hot；播放和设置使用干净 session。每个场景等待明确 UI marker，并保留 500ms 稳定窗口。

| 场景 | JS/CSS 资源数 | 就绪时间 | page/console/chunk 错误 | 备注 |
| --- | ---: | ---: | --- | --- |
| login cold/hot | 42 / 42 | 1,809.4 / 1,997.7 ms | 0 / 0 / 0 | 未登录 CSRF/IP-login 各返回预期 401 |
| home cold/hot | 37 / 37 | 2,027.0 / 1,937.5 ms | 0 / 0 / 0 | 真实首页组件与轮询 fixture |
| subscriptions cold/hot | 42 / 42 | 1,748.1 / 1,981.4 ms | 0 / 0 / 0 | 真实订阅列表组件 |
| player | 46 | 3,477.7 ms | 0 / 0 / 0 | 实际点击播放列表并加载 ArtPlayer；合成视频解码事件列为 `mediaErrors` |
| settings | 67 | 1,798.9 ms | 0 / 0 / 0 | 实际设置页动态路由 |

浏览器传输统计、请求 URL、编码和错误原始值均保留在 W7 JSON；四条 HTTP 401 是故意的未登录边界，其他 HTTP 错误为 0。

## W6 Service fixture

| 场景 | 实测结果 | 口径限制 |
| --- | --- | --- |
| Mikan/AnimeGarden/图片 | 96 条 Mikan、2 周、最大组 64；AnimeGarden default/search 可独立补载；50 引用/25 URL；图片上游请求 27 | Mikan、AnimeGarden loader 和图片 HTTP 是合成边界；生产 Service、缓存和 lease 为真实实现 |
| 热 Mikan 列表 | cold `137.579ms`；30 次 in-process hot p95 `0.0598ms`；restart-persistent `0.801ms` | 单 Service 计时，DB 查询/锁等待/HTTP 为 null，不是生产 HTTP p95 |
| RSS 100 订阅 | RSS 请求 100；downloader connect/list 各 1；100/100 `SUCCESS`；虚拟节流 `50,000ms`；墙钟 `1,226ms` | 空 RSS 不进入 add/download、files、move、rename 或缺集恢复写路径；对应计数明确为 null |

## 可重复命令

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
