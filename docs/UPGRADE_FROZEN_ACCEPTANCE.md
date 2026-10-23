# 真实人工标注与冻结验收集

当前仓库没有满足开发书 13.6 的真实人工标注集。本文件和执行器是采集／复验工具，不是已完成的验收证据。规则组合夹具、模型自行猜测的标签和小视频不能冒充人工标注。

将真实发布标题、规范作品 ID、原始来源引用、Emby 已有版本证据、完整质量规则和人工预期结论保存在独立 JSON 文件。全部案例至少 300 个、至少 30 部作品，覆盖开发书要求的全部类型与载体；在调整规则前划分 development 与 frozen，案例 ID 不重叠。

根字段：sourceKind 固定 REAL_RELEASES；approvedBy 为真实审核人；frozenAt 为 ISO 时间；development、frozen 为案例数组；frozenSha256 为冻结数组按项目 Gson 序列化后 UTF-8 的 SHA-256。审核、分类、输入和模型固定响应一起冻结；改动后必须重新标识版本，不能事后改变分类以隐藏失败。

每个案例包含：

| 字段 | 含义 |
| --- | --- |
| id、subjectId | 唯一案例 ID、实际规范作品 ID |
| sourceReferences | 至少一个真实发布页、资源身份或 Emby 证据引用；保存可复查原始摘要，不保存密钥 |
| category | SUFFICIENT 证据充分、SUPPLEMENTABLE 补证可解、AMBIGUOUS 确有歧义、NO_BETTER 无更优版、UNAVAILABLE 不可用；运行前固定 |
| policy | 完整质量规则，不能在报告里隐式换成另一套排序 |
| candidate、current | Release 结构化证据；current 可为 null；包含作品、季、章节类型／覆盖、字幕等字段 |
| recordedModelOutput | 可选固定模型结果，使用 items／fields／quote 结构，通过同一证据校验器再比较；保留模型、提示词版本及调用日期记录 |
| expectedUpgrade | 人工判断是否应自动升级 |
| sameIdentity | 人工核对新旧作品、季集及内容版本是否等价 |
| hardConditionsSatisfied | 人工核对新候选是否满足完整硬条件 |

Java 17 / Maven 运行：

```powershell
mvn -B -Dskip.frontend=true '-Dtest=UpgradeFrozenAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dupgrade.acceptance.dataset=D:/Acceptance/approved-cases.json' test
```

默认回归没有数据集时明确跳过此真实门槛，不能因其他测试通过而宣称冻结验收通过。显式执行会校验样本规模、作品数、来源、审核信息、分集隔离、冻结哈希和分类，并输出 upgrade-frozen-report.json。报告列出错误身份替换、错误硬条件／优先级替换、充分证据覆盖率、补证后覆盖率、无理由待确认和逐项差异；任何门槛失败都会使测试失败。

执行器覆盖固定证据的规则／模型结果判定。资源发现率、实际自动完成率、重复下载、重启、合集误删和真实服务故障仍需独立端到端报告，不能由这个报告推断。
