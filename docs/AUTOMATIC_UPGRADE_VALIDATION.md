# 自动化洗版本地验证记录

日期：2026-10-09、2026-10-10。工作分支仍为原本的本地 master，没有 commit、tag、push 或 GitHub 同步。

## 2026-10-10：Mikan 季度订阅与设置页样式迭代

- 日期任务新增 Mikan 年份／季度来源字段，后端生成整个季度的首播范围并核对真实菜单；已有自定义日期原样保留，可显式转换。旧任务暂停或只调整规则时不依赖 Mikan 在线。
- Mikan 目录使用既有持久缓存，不触发评分预热或原版订阅。目录快照与逐条游标持久化，每步至多三条；核对 Bangumi 身份、首播范围与同源同条目的 RSS，优先聚合 RSS，保留手工源、别名与排除设置。Bangumi 继续补充全部动画类型；错误和缺失映射进入可见覆盖记录。手动重扫及每日刷新同时重置两个目录游标。
- Java 17 全量 Maven 回归（19:55）：460 项，0 失败、0 错误、5 项环境／真实人工数据集跳过。最后补充 Mikan 已验证标题别名与聚合 RSS 断言后，8 项 Mikan 契约再次通过（19:58）。记录为 `output/upgrade-quarter-backend-tests.log`、`output/upgrade-quarter-final-contract-tests.log`。
- Vue/Vitest：40 项通过。生产构建、bundle 门禁及最终 JAR 打包（20:07）通过，构建未清空输出目录。记录为 `output/upgrade-quarter-ui-tests.log`、`output/upgrade-quarter-ui-build.log`、`output/upgrade-quarter-bundle.log`、`output/upgrade-quarter-package.log`。
- 真实公共 HTTP 读取 2024 春季目录及 Mikan 4069 条目详情成功，实际详情存在 Bangumi 605533 链接、聚合和分组 RSS。原始页面保存在 `output/mikan-quarter-live-spring-2024.html`、`output/mikan-quarter-live-detail.html`；该证据证明公共源可访问，不等同于真实媒体替换验收。
- 新 JAR 的真实浏览器／本地 API 流程无网络拦截：2024 秋季 105 条、春季 84 条、2013 秋季 57 条；2013 下拉仅显示上游提供的秋季。自动任务名随季度变化，首播范围正确。专用规则新建、重开编辑、取消及删除通过，现有规则列表不变；未创建季度任务、目标或下载。模式仍为 AUTOMATIC／revision 10。记录为 `output/playwright/upgrade-quarter-browser-flow.log`。
- 390×844 下页面宽度／scrollWidth 均为 390，订阅弹窗内容均为 335，保存按钮位于视口内；设置表单页面没有横向溢出。桌面使用设置页的 PageHeaderView、分段标签、独立滚动与 SettingsItem。截图为 `output/playwright/upgrade-quarter-desktop.png`、`upgrade-quarter-mobile.png`、`upgrade-quarter-settings-desktop.png`、`upgrade-quarter-settings-mobile.png`、`upgrade-quarter-policies-desktop.png`、`upgrade-quarter-overview-desktop.png`。正常流程无新增浏览器错误或异常。
- 故障 UI 检查仅拦截一次目录请求返回 503 夹具：显示来源异常后点击刷新通过真实 API 恢复，手工名称在年份改变后保留。记录为 `output/playwright/upgrade-quarter-error-flow.log`；该故障负例的预期 HTTP 错误与正常流程分开记录。
- 最终实例监听 `127.0.0.1:17789`，PID 48024；JAR SHA-256 为 `8958FA6746C333C500975DF688135FF150761C60956192448F61C2463BB7CB53`。独立配置和启动信息位于 `output/upgrade-local-instance.json`。真实 Emby、下载器、AI 与生产媒体验收仍沿用下文未完成项。

## 已验证

- Java 17 全量 Maven 回归（2026-10-09 20:37）：452 项，0 失败、0 错误，5 项跳过。其中 4 项为既有环境条件，新增 1 项是未提供真实人工标注冻结数据集的验收门禁，不能记作通过。本次包含 SHA-256 校验、文件／下载器故障回归、规则层级、完整备份合同，以及保存后的规则编辑和删除引用保护。
- Vue/Vitest（20:34）：38 项通过；更新后的前端生产构建和 bundle 检查（20:43）通过。
- 14 张独立持久表、迁移至 schema 12、候选身份唯一、数据库重开、模式与修订号冲突处理。本地已有 schema 11 测试库实际升级至 12 后正常启动。
- 规则中的逐级比较、硬条件修复、未知值区间、联合字幕组、同级不替换和章节覆盖。
- 路径段最长匹配、Windows／UNC／Linux 路径、根目录与普通文件限制、模型伪造引用和字段隔离。
- 安装前旧版保留、单文件精确删除、不关联集保留、外部修改保留旧版并转清理待重试、部分复制失败、INSTALLED 恢复、模式切换拒绝新提交。
- 三下载器分别通过实际适配器及编排的本地 HTTP 契约：数据库重开后同 hash 不重复提交、完整文件清单、下载中与完成状态、整包不删文件停止、停止失败后重试、任务消失后对账与重新提交、远端 ID 被另一已知 hash 复用时不接管或停止。qBittorrent／Transmission 引用统一为稳定 hash，Aria2 保持 GID；不是三个真实服务的端到端验收。
- AnimeGarden 237 条历史分页与同集不同 hash 保留、跨源合并；Bangumi 月窗口降为日片；Emby 分页失败保留最后完整批次。属于接口夹具验证。
- 真实 ffmpeg 生成 720p 小视频，真实 ffprobe、三处抽样解码与 SHA-256 校验通过。
- NFO 字段补缺、锁定值保留、错误 Provider ID 拒绝覆盖。
- 下载取消后的独占传输停止失败重试、共享下载归属与空间预约转移、普通下载保留；停止重试不恢复已取消安装。
- 日文汉字不充当中文字幕；通用中文轨可满足中文门槛但不伪造简繁；电影文件编号、内容版本隔离、绝对集数明确名称匹配和模型章节建议的实际 ID／唯一来源约束。
- 发布详情补充文件覆盖、短页的明确继续标志、180 天日志保留保护、90 天模型缓存失效，以及全量目标统计保留暂停目标分母。
- 新文件落盘后数据库登记前的恢复、明确单个部分复制文件的恢复、旧任务停止失败时新旧文件均保留及清理重试。
- 跨卷硬链接失败后注入实际输出流部分写入与 IOException，复制归属事先记录，数据库重开后首次重试收敛；未登记归属的同名临时文件在重开及重试后仍保留，不转为可删除文件。空间不足不安装或删除，恢复容量后继续；同卷预约共享、释放、转移以及可用空间下降／大余量不发生算术绕过。
- READY_TO_COMMIT、INSTALLING、INSTALLED、CLEANING_OLD、CLEANUP_PENDING 五个持久状态边界注入模拟进程退出，数据库重开后至少一份正确内容及无关文件不变，恢复后只有一份新 active 版本；另测旧文件已 unlink 而逐文件日志尚未保存的退出窗口。这里是进程退出模拟与实际 SQLite 重开，不声称真实外部下载器或跨物理卷已经验收。
- 两个旧文件中第一份精确删除成功、第二份被拒绝后，两个新版可用、仅一个旧版保留，重开后只清理剩余项；真实 NTFS 硬链接安装和旧链接删除不损伤其他链接。Emby 定向刷新 HTTP 503 后重试不会重复媒体安装／删除，NFO 已有字段与 active 版本 ID 保持不变；已有归档在默认根目录之外但位于授权映射内亦通过。
- 作品／章节／媒体库／内容版本的持久提交锁；批次排序一次抢占、失败无部分占用、数据库重开及新增 Bangumi ID 后继续冲突，未收敛提交不能因租约过期被抢走；不同库、章节和内容版本可分别占用。
- 等待安装期间相关 active 基线变化会退回 VERIFYING，无新版落盘和旧版删除；无关章节及证据刷新不会误触发失效。清理失败保留持久锁，成功完成释放；OFF 模式下普通缺集恢复仍受未收敛提交保护。
- 候选作品身份冲突返回稳定 409／UPGRADE_RESOURCE_IDENTITY_CONFLICT；人工数值章节修正规范化。
- 真实内封字幕文本读取：纯日文不沿用错误 CHS 声明，中文文本纠正错误 jpn 标签；外挂文本优先于错误文件名，UTF-16 BOM 读取与已观察到的内封能力保留。明确硬字幕不因轨道缺失而被否定，图片字幕及无法完整转换的文本保留声明补证边界。
- 已有归档位于默认输出根目录之外时仍参与普通流程的持久冲突检查，相似目录不误匹配。
- 下载后不合格候选不会反复占据最佳位置而堵住后续资源；重复扫描不重复排队，规则、解析字段、基线与保护变化后复用传输重验。已完成下载释放预约，安装预约另行检查。
- 独立 OVA／OAD 正片按上游 NORMAL:编号 匹配，显式 SP 不转正片；保护旧版本允许安装更优的新版本，夹具中的旧文件保留、新文件安装、两个 active 版本及无旧文件删除清单均已验证。
- 日期任务规则可留空，按“作品完整覆盖 > 任务完整规则 > 当前全局默认”选择，不保存默认规则的隐式快照；默认变化、同优先级创建时间、稳定平局、日期上下界、媒体库隔离、暂停与排除、更新 CAS 均通过。GET targets 只重新计算显示的生效任务，不写目标或创建队列。
- 实际完整备份 ZIP 经 manifest 校验和解包，全部 13 张普通自动表及持久提交锁的逐列内容与源 SQLite 一致，包含 schema 12、取消标记、租约和证据；源数据库在备份后改变不影响已导出快照。属于独立测试数据的一致性备份／解包验证，未替换当前运行实例的数据。
- 已保存规则编辑保持 ID，并增加修订号，全部排序及别名在数据库重开后保持。未被引用的规则可以删除；过期修订号、全局默认、暂停日期任务、排除作品覆盖、未收敛安装／清理及停止重试均阻止误删引用。已完成执行的历史不阻止删除，旧定义保留用于审计且不能重新选用；规则专项 13 项通过。

日志位于 `output/upgrade-tests.log`、`output/upgrade-file-downloader-fault-tests.log`、`output/upgrade-commit-fault-tests.log`、`output/upgrade-campaign-tests.log`、`output/upgrade-backup-tests.log`、`output/upgrade-ui-tests.log`、`output/upgrade-ui-build.log`、`output/upgrade-ui-bundle.log`、`output/upgrade-package.log`。日志属于本地生成内容，不应直接加入发布包。

20:37 后的用户反馈修复记录在 `output/upgrade-rule-feedback-tests.log`、`output/upgrade-feedback-backend-tests.log`、`output/upgrade-feedback-ui-tests.log`、`output/upgrade-feedback-ui-build.log`、`output/upgrade-feedback-ui-bundle.log`、`output/upgrade-feedback-package.log`；此组全量结果及最新服务信息优先于此前日志。

## 包体积变化

新页面按路由延迟加载。改为显式注册模板使用的 Element Plus 图标后，首页 JavaScript 闭包约 164 KB gzip，低于原 196000 字节门限。新路由改变共享样式拆分，首页 CSS 为 62076 字节 gzip，原门限 62000 增加 80 字节至 62080，原因与测量值写入 bundle-budget.json。其他 JS 门限和禁止模块检查未放宽。

## 尚未通过的计划验收门槛

1. 真实人工标注样本：至少 30 部作品、300 对候选，开发集与冻结验证集分离；误替换率、自动覆盖率、发现完整度和待确认原因分布需要实际统计。合成组合测试不能声称达到该门槛。
2. 真实独立 Emby 测试库与 qBittorrent／Transmission／Aria2 的端到端下载、字幕、安装和删旧，含多版本、合集、断网、重启、低磁盘空间场景。
3. 有效 DeepSeek 模型列表、JSON 输出、限流和真实预算消耗验证。当前没有配置账户密钥，也没有花费用户模型额度。
4. AnimeGarden 早先直接请求曾出现 TLS EOF；2026-10-09 08:38 再次只读请求已成功，返回 1 条资源与 resources／pagination／filter 字段。单次连通性不能代替历史分页、作品匹配完整度和持续运行验收；实际全链路覆盖仍待测。
5. Docker 全发布架构镜像构建、自检和运行，及生产媒体库只读预览。此次没有发布镜像、接入生产库或删除真实媒体。

Docker 客户端存在，但 Docker Desktop Linux daemon 的 npipe 未就绪，未形成镜像运行证据。真实服务连接、密钥和测试库尚未配置。没有用虚构标签生成“人工标注”或通过缩小分母填写覆盖率。

## 阶段逐项结论

| 开发书阶段 | 当前实现与可复现证据 | 阶段验收结论 |
| --- | --- | --- |
| P0 原契约保护 | 全量既有回归；原版／普通下载／完结／文件归属回归 | 真实人工标签集未完成，阶段未完整验收 |
| P1 模式与持久基础 | schema 12、14 张独立表、模式 CAS、配置迁移、重开与取消竞争回归；排序章节持久锁及基线复核 | 本地契约通过；普通流程共享冲突检查，实际文件变更仍由全局屏障串行 |
| P2 Emby 接入 | 分页、保留封存批次、Provider／季限制、多个 MediaSource、NFO／外挂字幕、提交前复核 | HTTP 夹具通过；真实隔离库与权限／挂载组合待验 |
| P3 来源与发现 | 月／日分片、历史分页、明确不完整状态、48 小时回看、Mikan／RSS、hash 合并与发布详情 | 保存的接口契约通过；实际网络覆盖与完整度未验 |
| P4 DeepSeek | 批量解析、引文／枚举验证、候选身份及章节建议、别名检索、缓存、预算、429／熔断；实际字幕文本优先于矛盾标签 | 固定响应及实际字幕工具回归通过；真实账户模型、费用和效果未验 |
| P5 规则与预演 | 硬条件、逐级比较、未知区间、联合字幕组、内容版本、只读分页导出；下载后负面结论指纹和后续候选轮换 | 合成及固定回归通过；冻结集准确性与自动覆盖率门槛未通过 |
| P6 下载与文件 | 三适配器生命周期 HTTP 契约、独立归属、暂存、媒体工具实调用、逐文件安装删除、五个提交状态边界模拟退出、部分写入／删除恢复与共享硬链接保留；保护旧版同时安装更优新版本 | 已列故障夹具通过；三个真实下载器、真实进程重启和跨物理卷／ACL／只读挂载／低空间组合仍待验 |
| P7 联动与 UI | 独立完结档案、通知限制、补缺 NFO、Emby 定向刷新、五个视图 | 模式／设置／规则基本流程、只读导出及夹具候选修正／版本保护／清理重试已验；真实完整闭环待验 |
| P8 整体验收 | 全量回归、构建、依赖自检、API／恢复／配置／冻结集运行说明 | 外部验收门槛未满足，不能标记计划全部完成 |

冻结集运行器与格式见 [UPGRADE_FROZEN_ACCEPTANCE.md](UPGRADE_FROZEN_ACCEPTANCE.md)。源码测试默认明确跳过该外部门禁；传入真实数据集后输出分类、原因分布、错误替换数与覆盖率。运行器只能验证所提供标签下的比较结果，不能单独证明发现完整度或真实文件闭环。

开发书要求的独立 [生产部署前检查清单](AUTOMATIC_UPGRADE_DEPLOYMENT_CHECKLIST.md) 已补充。未满足的外部验收项保持未勾选，不能把清单文档存在作为实际部署通过。

## 最新服务与浏览器证据

- 最新打包成功：2026-10-09 20:45。Java 17 进程 PID 53412，启动于 20:45:25，仅监听 127.0.0.1:17789。启动信息和包 SHA-256 以 `output/upgrade-local-instance.json` 为准。
- 首页 HTTP 200，认证后的新状态接口 HTTP 200，ffmpeg／ffprobe 可用；保留用户测试时已选择的 AUTOMATIC／revision 10，任务与目标为空，独立测试配置没有真实 Emby、下载器或 DeepSeek 密钥。
- 此前浏览器实测 LEGACY → AUTOMATIC → OFF；自动开关启用时原版关闭，并显示提示。旧修订写入返回 409；不带 CSRF 的写入按既有认证契约返回 401，状态未改变。
- 实际只读导出生成 `output/playwright/upgrade-empty-preview.json`，readOnly=true；前后任务计数相同。当前无目标，因此导出为空，不代表生产只读预演已完成。
- 浏览器网络夹具演示候选证据、字段修正、版本保护、安装／删除记录、清理失败重试。请求及截图在 `output/playwright/upgrade-ui-fixture-requests.log`、`upgrade-cleanup-fixture.png`；这些只证明页面行为，不能证明真实文件已安装或删除。夹具仅存在浏览器路由，已移除，未写入服务数据库。
- 19:54 包的日期任务浏览器网络夹具已验证：质量规则留空时显示“使用全局默认规则”，保存请求的 policyId 为 null，输出库及起止日期正确；路由移除后实际后台任务和目标计数保持不变。记录在 `output/playwright/upgrade-global-default-flow.log`。该流程证明真实页面提交合同，未创建真实日期任务或调用外部服务。
- 桌面与 390×844 手机视口截图在 `output/playwright/upgrade-overview-desktop.png`、`upgrade-overview-mobile.png`；手机页面 document 宽度和 scrollWidth 均为 390，没有页面横向溢出。
- 浏览器正常流程未观察到 Vue／JavaScript 运行错误；认证与冲突负例产生预期 HTTP 401／409 控制台记录。
- 20:45 启动的新包已完成重新登录、认证状态读取及页面刷新：AUTOMATIC／revision 10、零任务／零目标、无正在提交的操作；没有 Emby 地址、已选媒体库或下载器地址，AI 未启用。状态与连接存在性记录在 `output/playwright/upgrade-feedback-final-status.log`，刷新控制台在 `output/playwright/upgrade-feedback-final-console.log`，为 0 错误、0 警告；配置检查仅输出存在性，没有输出密钥。19:54 Docker daemon 只读探测仍返回 desktop-linux 引擎 npipe 不存在，没有镜像运行证据。
- 本次用户反馈使用真实浏览器及真实本地 API，未拦截网络：新建专用规则、调整维度与六类排序／最低分辨率／别名、保存、刷新重读、取消删除及确认删除均通过；已删除专用测试规则，原有两条规则、用户模式及任务／目标保持不变。记录在 `output/playwright/upgrade-rule-feedback-flow.log`。
- 侧栏名称改为“洗版”，桌面文字位于菜单项范围内。390×844 下页面宽度／scrollWidth 同为 390，规则弹窗内容宽度／scrollWidth 同为 334，保存按钮完整可见；排序按钮不再被横向裁切。截图在 `output/playwright/upgrade-policies-fixed-desktop.png`、`upgrade-rule-editor-fixed-desktop.png`、`upgrade-rule-editor-fixed-mobile.png`，只读视觉核对记录在 `output/playwright/upgrade-rule-editor-visual-check.log`，既有规则未保存或修改。
- `output/upgrade-live-source-probe.json` 记录最新公共源连通性，`output/upgrade-docker-probe.log` 记录 Docker daemon 不可用；`git diff --check` 通过。

在这些门槛完成前，应称为“本地开发测试版”，不能宣称开发书已全部验收完成或可直接无人值守接管生产库。
