# Outbox 子步骤提交白名单建议（待最终 Full 与 ROOT 封口）

本文件只提出精确文件范围，不授权或执行 stage/commit。当前源码候选607B与准备证据保持冻结；独立静态审阅仍由其作者完成，实际 collector/运行证据仍由运行代理维护。本轮仅新增本文件及 `proposed-commit-scope.json`，未改源码、属性、旧档、collector、index，未运行测试或连接数据库。

## 只属于本子步骤的内容

源码仅1份：`skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java`，SHA256 `607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8`。

现有确定证据仅：

- `docs/evidence/2026-10-28-EX-27/outbox-actor-preflight.md`。
- 独立作者完成并由 ROOT 确认的 **outbox 专属**静态审阅文件；目前精确文件名未确认，JSON 留空，不能借此纳入 SDK/算法审阅。
- `docs/evidence/2026-10-28-EX-27/runs/outbox-fix-prepare-20261029-084356-913/` 下当前8份文件，JSON 已逐个列出路径与原字节 SHA：before/candidate Java 快照、candidate.diff、candidate.md、file-manifest.json、module-source-sha256.json、static-checks.json、tenant-mapping.json。
- `docs/evidence/2026-10-28-EX-27/runs/outbox-collector-prepare-20261029-084702-676/` 下当前9份文件。JSON 只记录本次观察值，owner最终冻结前不作为可提交的最终 SHA。这里包含4份 PS1、collector-scope.md、static-check.json、preparation-attempts.json、packaging-static.json、局部 .gitignore。
- 本文与 `proposed-commit-scope.json`。
- ROOT 后续授予的**一个新 outbox 实际 RunId**的安全命令、准入、目标/Full 方法级清单、汇总、原因对照、有限结果报告与最终完整文件清单；当前 RunId/文件尚未确定，JSON 留空，不能用泛目录暂存补齐。
- 必要时纳入现有 `docs/evidence/2026-10-28-EX-27/.gitattributes`，但差异只能是下述逐文件精确属性规则。此建议没有修改它。

最终只能以 JSON 中封口后的逐文件列表暂存，不执行 `git add -A`、`git add docs/evidence/2026-10-28-EX-27` 或对所有 untracked 做归属推断。实际运行日志/XML/PG原始stderr继续按该 RunId 局部 `.gitignore` 保留本地，不 `-f` 强制入库。

## SDK/EX-03 历史与联合候选的边界

旧 Full `runs/algorithm-fix-20261029-032753-387` 的 outbox 新增F是本次真实失败的外部历史依赖，只链接其 `result.md`、Full summary、原源码清单及相关方法记录。原 EX-03 Full673、EX-00原664基线、SDK历史PG取证、SDK静态审阅、算法诊断/准备包也只是外部依赖。**不把任何 `runs/algorithm-*`、`algorithm-*.md`、EX-03证据树或旧 SDK raw/XML 复制进 outbox 提交。**不通过改名把这些文件包装成本子步骤新增证据。

同样排除 SDK `AlgorithmOffloadInterruptionTest.java`、ArtifactStore、SkillTaskResultTest、SkillOutbox、SkillExecution、Actor、迁移以及 EX-03 的 Importer/Catalog/Test 三源码；这些不属于本子步骤的代码白名单。相邻 EX-08/EX-17、旧用户审计、output 和用户其他未跟踪资料也不纳入。工作区三份主入口位于 AP 仓库之外，由 ROOT 另行维护，不凭本白名单放入 AP 提交。

已核对当前联合冻结上下文与837 HEAD：SDK Test当前43EC，HEAD为BE6C；EX-03三份当前4145/7C98/97D0，也各自不同于HEAD。它们都属于其他候选。因而即使本轮 Full674 的方法门后续通过，它验证的是**149源码的冻结联合候选上下文**；只检出 outbox 单个提交不能声称复现相同 Full674。ROOT 应按各自归属独立提交并串联最终 commit/context，或者在验收报告明确保留联合候选 SHA 依赖；不可为了让提交“看起来自足”混入其他候选源码/诊断包。

## 原字节快照与 Git 属性

根规则为 `* text=auto eol=lf`，当前 EX-27 属性仅覆盖既有SSE证据，未覆盖新 outbox 文件。两个源快照目前都是无BOM的LF，因此本次LF归一化不会改变它们；为明确保护原字节快照，建议在既有 EX-27 `.gitattributes` 追加下面**两条精确路径**，不能匹配真实 Test 源码或 `*.java`：

```gitattributes
/runs/outbox-fix-prepare-20261029-084356-913/SkillExecutionTest.before.java -text -whitespace
/runs/outbox-fix-prepare-20261029-084356-913/SkillExecutionTest.candidate.java -text -whitespace
```

另外有9份已散列文件目前含CRLF；自动转LF会改变现有 raw SHA/文件清单，不能重写或重新归一化冻结证据来掩盖差异。JSON 的 `proposedExactAttributesRules` 已精确列出这些路径及当前 CRLF 数/SHA，建议对其逐文件添加同样的 `-text -whitespace`：

- outbox-fix-prepare：`file-manifest.json`、`module-source-sha256.json`、`static-checks.json`、`tenant-mapping.json`。
- outbox-collector-prepare：`preflight.ps1`、`run-verify.ps1`、`packaging-static.json`、`preparation-attempts.json`、`static-check.json`。

collector 的4份PS1都带UTF-8 BOM；上述两份还包含CRLF。owner冻结后需复核实际文件SHA，保持冻结脚本BOM和字节原样。其余已观察LF证据没有本次换行归一化风险，无需扩大为目录通配属性。实际新 RunId 产生后再检查其安全证据中被 raw SHA 引用的CRLF/原字节文件，必要规则只能是该 RunId 的最终精确文件名。不能提前添加 `/runs/outbox-*`、`/runs/**`、`*.json`、`*.ps1` 或任何源码/全局例外。

属性规则本身会改变Git处理方式，最终需由ROOT逐条复核差异，再验证暂存 blob 与原文件的 SHA 一致；本建议没有做暂存验证，也没有改原属性。

## 封口前必须补齐的事实

1. 确认独立 outbox 静态审阅的最终路径与结论。
2. 运行代理交回一个实际RunId及全部有限结果；28→674的身份、源码/环境、原116原因、native/collector/方法门分开记录。原 Full 新增F与所有旧失败历史保持。
3. 冻结实际RunId的安全逐文件清单与collector最终SHA，补入本JSON的空槽；只检查文件，不靠 broad add 吸入新材料。
4. 完成必要精确属性规则，并由ROOT验证暂存范围、blob字节、唯一源码差异与依赖候选提交次序。仍不得把旧失败/新证据的native0或局部门通过写成全模块绿。

当前 Full 结果仍待交回；本建议不是已通过的提交清单，也没有宣称 EX-27 或 EX-03 完成。
