# v3.2.37.70 同步记录

日期：2026-09-23。基线 fork v3.2.32.69；上游范围 v3.2.32（b29bd244）至 v3.2.37（e14aa468）。

## 功能映射

| 上游变更 | 处理 |
| --- | --- |
| 种子解析错误、合集下载修复 | 采用 Bencode 元数据适配器，原始 info 字节计算 hash，支持 v1/v2/hybrid 文件；hybrid 保持 v1 身份，纯 v2 使用 qBittorrent 截断身份与完整 btmh 磁力 |
| 合集磁力输入 | 同步 UI 与 60 秒原生元数据解析；单并发，忙碌立即提示，缓存复用于预览和提交，元数据解析不下载媒体内容 |
| 首页激活刷新 | 幂等启动一次完整刷新，保留下载请求 single-flight 和取消语义 |
| 默认下载位置 | Windows/macOS 新配置使用 Downloads，保留已有配置 |
| 重要操作确认、圆角 | 全量刷新/清日志改为模态确认；Dialog/MessageBox 共用圆角变量 |
| OpenList 停止支持提示 | 禁止新选择，提示迁移；保留 fork 已有工作流，避免破坏存量任务 |
| Chrome 自动填充 | 下载地址和凭据补 autocomplete=new-password |
| 安全设置、备份集中 | 设置标签更名，自动备份选项移至备份页；保留 v2 恢复协议，不复制上游直接解压覆盖和递归删除 |
| 错误日志 | 业务异常增加方法/类型/结果码上下文；不记录查询与请求体，保留 fork 的状态码与 operationId |
| Java 25、删除 ARM32 构建 | 有意保留 Java 17 与 ARM32，新增 jlibtorrent Java class major=61；ARM32 无原生解析库时提示使用文件上传 |
| 依赖 | bencode 1.4.1 替换 Eclipse 旧解析器，jlibtorrent 2.0.12.9；Vue 3.5.43、Element Plus 2.14.6、plugin-vue 6.0.9 |

未整体覆盖 fork 文件，不引入全局调度框架，不清理用户已有方案文件。

## 验证

- 本地前端：35/35 通过；冻结锁文件安装通过；生产 UI 构建和包体门禁通过。
- 新增三个必要的解析合同检查：精确原始 info hash、纯 v2 文件树、hybrid 双磁力与 v1 身份。用于防止解析器替换破坏下载归属，未新增大范围测试体系。
- 包体：模态确认与组件补丁引入小幅增量，按实际生产构建审查预算；继续强制 forbiddenModules 与懒路由边界。
- Java 本地无 Maven，后端、SpotBugs、W7/N08 由提交后的远端 build-test 验证，发布前必须通过。
- 真实账号、下载器与外部磁力 swarm 未连接；原生平台能力声明基于所打包的库，不等同多平台真实解析验收。

## 兼容与维护

磁力元数据原生库覆盖 Linux amd64/arm64、Windows、macOS arm64；ARM32 等平台请上传 .torrent 文件。缓存位于 config/cache/collection-metadata，必要时由管理员手动清理，不自动删除历史文件。原生临时目录只尝试删除空目录，非空时记录维护提示，禁止递归清理。

## 发布

待远端验证通过后，对精确提交打 v3.2.37.70，使用现有 tag workflow 发布 Release 和 GHCR temurin/openj9/arm32v7 镜像。构建结果随后回填。
