# INT-01 统一接入和配置标准——最小公共标准子步（P席，2026-11-03）

基线：AP `5da6bd99f21efd258bcbe60d8d0e39782d2c6c8d`（upgrade/20261103）。交付：`contracts/assistant-integration.md`、`contracts/configuration-profile.md`（均 v0.1 草案，未激活；激活时按 freeze.json engineeringAddenda 模式登记）。

## 范围与白名单

仅新增两份契约文件；四契约原文、`capability-package.schema.json` v1、freeze.json 及历史 manifest 均未改动（`git diff 5da6bd99 -- contracts/` 仅含两个新文件）。

标准内容：L0～L3 免改分级；共享五对象（ConnectionProfile/EnvironmentBinding/AssistantExperience/IntegrationProfile/ConfigRelease）唯一归属与最小字段；修订/CAS 激活状态机与运行快照引用（bindingRefs）；信任边界与两条受控票据路径；11 个新增错误码最小登记集；SDK 兼容矩阵；路由/权限目录/IntegrationPolicy/生成链路六项登记要求；三个内嵌 JSON Schema v1 草案（host-event / page-context / experience-description）；Schema 通用边界（禁远程 $ref、additionalProperties 约束、预算不放宽）。

## 功能检查（共两轮，均通过）

- 第 1 轮（语法+语义锚点）：三个 JSON 块 `JSON.parse` 全通过并取得 `$id`；pageContext 的 `additionalProperties` 为约束 schema（修复了编写时一处误写 `additionalProps`，修正后进入本轮）；结果事件 schemaVersion=1.0、五对象归属完整、错误码≥11、引用既有契约（current-identity-mapping.md / error-envelope-boundaries.md / skill-invocation.md）均核通过。
- 第 2 轮（实例符合性，12 例）：以非三路线命名示例应用 `demo-app` 构造正例 3 个、负例 9 个，自写最小骨架校验器（required/const/enum/pattern/maxLength/minimum/type/integer/maxProperties/maxItems/propertyNames 黑名单与 pattern/additionalProperties:false/if-then 条件必填）。**全部 12 例按预期判定**：混入 secretRef、伪造 tenantId/userId、嵌套对象、未知 schemaVersion、非法 status、artifactId=0、result 缺 status、键名超pattern 等均被拒绝；正例全部通过。
- 检查器迭代说明：本轮校验器为一次性最小实现，迭代 5 次均为**校验器缺陷**（未实现 propertyNames/type/integer/if-then、叶节点早退、负例计数反转），被检 Schema 产物零改动；生产端实现（INT-05/06/07）须用 ajv 等标准校验器，不得复用本脚本。

## 未覆盖与后续

- 状态为草案：操作手册不得宣称可用；激活与 freeze.json 登记在 INT-01 收口时执行。
- `approved-connection-reference.schema.json`（INT-02）、条件/目标等语义 profile（SEM-01.2）不在本子步。
- 体验/事件端点的真实 HTTP 行为验证属 INT-03/06/07 的功能检查，本轮仅契约层。

## Git 与回退

精确暂存 3 个文件（2 契约+本报告），本地提交，不推送。回退： revert 本提交即撤回草案，无产品影响。
