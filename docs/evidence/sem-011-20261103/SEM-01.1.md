# SEM-01.1 最小只读契约（P席，2026-11-03）

基线：AP `5da6bd99`（upgrade/20261103，本批前 HEAD `6c137894`）；OR `91935bb`（本批前 HEAD `cbdc118`）。

## 交付

- AP `contracts/semantic-runtime.md`（v0.1草案）：只读profile对象模型（四元组/bindingRef/ObjectReference/FactItem/FactSet/EvidenceRef/Timeliness/status+errors）、时效与UNKNOWN规则、7个`SEMANTIC_*`错误码登记集、授权与撤权规则、与SEM-05.0既有returnSpec共存量、兼容演进规则。
- AP `contracts/semantic-runtime-v1.schema.json`（`urn:agent-platform:semantic-runtime:1.0`，draft 2020-12）：全对象`additionalProperties:false`、`$defs`+本地`$ref`、分支必填if/then、`decisionRef:false`（read-only Schema级禁止）、`releaseId` UUID（OR openapi实测形态）附加pattern强制。
- OR `contracts/semantic-runtime.md`（v0.1同步稿）：OR只读消费口径登记，不改OR运行代码。

## 功能检查（两轮，均通过）

- 第1轮（16例）：Schema JSON解析通过后，4正例（OK完整属性+关系+三类证据、NO_MATCH+UNKNOWN时效、ERROR纯错误、external引用+NULL事实）+12负例（decisionRef、ERROR带factSet、ABSENT带value、UNKNOWN缺原因、摘要非64hex、未声明字段、关系缺目标、releaseId非UUID、attribute混relation字段、未知schemaVersion、value嵌套对象、错误码不在登记集）**全部按预期判定**。
  - 迭代说明：①检查器补`$ref`解析与布尔false-schema处理（检查器缺陷，产物零改动）；②暴露契约设计问题1处——relation PRESENT被无条件要求`value`与`relatedObjectRef`语义重复，修正为按factKind分支（attribute PRESENT需value；relation PRESENT需relatedObjectRef），md§2.4同步；③修复编辑引入的尾逗号JSON错误。均为第1轮内收敛。
- 第2轮（10项交叉一致性）：md错误码与schema枚举双向一致、decisionRef禁止落地、schemaVersion/urn三文件一致、15+字段表字段均存在、UUID pattern存在、PRESENT分支语义md↔schema一致、OR稿UUID口径一致。

## 未覆盖与后续

- 草案未激活：激活时按freeze.json engineeringAddenda登记；生产端实现（SEM-03/05）必须用标准校验器（ajv+format），不得复用本轮自写校验脚本。
- SEM-01.2（判定/条件/目标profile封闭Schema与兼容边界）未开工；`factSetDigest`规范化算法在01.2冻结前由实现登记。
- OR运行代码零改动（02.1/03.x各自白名单实施）。

## Git 与回退

AP精确暂存3文件（2契约+本报告，`git add --sparse`）、OR精确暂存1文件；本地提交不推送。回退：revert对应提交即撤回草案，无产品影响。
