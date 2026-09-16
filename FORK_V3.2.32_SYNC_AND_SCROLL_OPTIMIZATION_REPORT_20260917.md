# Fork v3.2.32.69 同步与滚动优化验收报告

本报告严格对应 [`FORK_V3.2.32_SYNC_AND_SCROLL_OPTIMIZATION_PLAN.md`](FORK_V3.2.32_SYNC_AND_SCROLL_OPTIMIZATION_PLAN.md)。上游 `v3.2.32` 只作为行为和声明对照，没有整体合并；本地 fork 的认证、SQLite/ownership、缺集恢复、媒体访问、图片缓存和发布合同继续保留。

## 交付范围

| 工作包 | 结果 |
| --- | --- |
| U01 | 封面点击动作配置为 `edit`/`playlist`/`cover`，非法值归一化；两种卡片布局、失败占位、键盘焦点和事件冒泡边界完成。 |
| U03 | `.txt` → `.torrent` → 当前链接类型的缓存选择顺序完成；四种缓存场景加入后端测试。 |
| U04 | 内网限制前移到所有应用入口；API 保留既有 JSON 合同，静态拒绝为短 HTML 403/no-store，允许的 hash 静态资源保留长期缓存。 |
| U05/U06 | manifest 使用 `crossorigin="use-credentials"`；lockfile 增量更新并通过冻结安装。 |
| U07/U08 | jsoup/markdown-it/Vue/terser/Vite、Node/pnpm 声明同步；保留 fork extras，未做无关技术栈升级。 |
| S01 | 全量过滤/排序后分页，每页最多 60 条；星期分组全局切片后重建，筛选/排序/换页/删除/刷新生命周期符合方案。 |
| S02/S03 | native `title` 替代封面组 tooltip；订阅和已审查主滚动区使用 `always`，取消订阅页隐藏滚动条。 |
| S04 | 单一 5 秒后台跟踪链、120 秒累计预算、generation 防旧请求回写，deactivated/activated 不取消后端任务。 |

## 本地门禁

- `corepack pnpm@12.3.4 install --frozen-lockfile`：通过。
- `corepack pnpm@12.3.4 test -- --run`：5 个测试文件、35/35 通过。
- `corepack pnpm@12.3.4 run build`：Vite `8.3.0`，生产构建通过。
- Maven 不在本机 PATH，Java 完整回归、SpotBugs、打包和镜像由精确 tag 的 GitHub Actions workflow 执行；不把前端门禁冒充后端门禁。

## 浏览器修改前后记录

使用同一台 Windows 主机、同一默认 Playwright 窗口、同一隔离 HTTP fixture 数据和同一 Chrome for Testing `154.0.8037.0`。每个样本分别执行约 10 秒冷滚轮和约 10 秒热滚轮，记录滚轮事件数；没有采集 Performance 面板长任务或 FPS，因此不声称帧率提升。

| 样本 | 基线 v3.2.28.68：挂载卡片 / 卡片总 DOM | 优化后：挂载卡片 / subscription-page DOM | 基线滚动高度 | 优化后滚动高度 | 优化后分页/筛选 |
| ---: | ---: | ---: | ---: | ---: | --- |
| 60 | 60 / 60 | 60 / 1,655 | 3,963 | 3,963 | 单页；清空搜索后恢复 60 |
| 300 | 300 / 300 | 60 / 1,655 | 14,993 | 3,021 | 第 2 页为 61–120，换页 scrollTop=0 |
| 1000 | 1000 / 1000 | 60 / 1,657 | 45,392 | 2,725 | 第 2 页为 61–120；搜索 999 为 1 条，清空恢复 |

补充观察：三种优化后样本均只有 1 个列表滚动拥有者，均无 page error；冷/热滚轮各约 108 次事件，滚动位置能够到达可滚动末端。基线旧版本的滚动条带有 `hide-scrollbar`，优化后该类不存在并可发现；没有把 fixture 的空 cover 资源等待当作真实图片冷网验证。刷新后台作业和真实 SSO 仍按方案保持边界。

封面动作补充检查：空 cover 失败占位仍有 1 个可聚焦 `.cover-click-target`；默认/`edit`/`playlist` 的可访问标签分别为“编辑封面”“编辑订阅”“打开视频列表”，键盘 focus 成功，点击 `edit` 能打开编辑对话框，probe 未产生 page error。

## 版本与发布

- 版本：`3.2.32.69`，拟发布 tag：`v3.2.32.69`。
- 版本同步位置：根 POM、应用 POM、UI POM、`ani-rss-ui/src/js/config.js`、`UPDATE.md`。
- 发布方式：提交精确 commit 后推送 `master`，再推送精确 tag；tag workflow 负责 GitHub Release、`ani-rss.jar`/`ani-rss.exe` 和 GHCR temurin/openj9/arm32v7 镜像。workflow 完成后回填对应 run、附件 SHA-256 和 manifest digest。

## 未验证边界

- 本机没有 Maven，因此后端 Java 319 项历史门禁不能直接作为本轮结果；必须以本轮远端 workflow 为准。
- 未连接真实 Authentik/反向代理、下载器、外部番剧源、用户数据库、媒体文件或 Docker Linux engine；真实 SSO、公网策略和外部源波动不由 fixture 推导。
- 性能记录没有 FPS、长任务、Layout/Paint 的 Performance 面板样本；本报告只报告 DOM/滚动/分页/错误观察值。
- 基线临时 worktree 的 Git 元数据已移除；其构建产生的长路径文件未用批量删除命令清理，主工作区不依赖该目录。
