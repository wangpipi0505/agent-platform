# 任务状态机与 availableActions 契约（task-states）

C0-2 契约冻结（台账#C0-2），2026-11-13。
来源与裁决：状态枚举以**数据库稿《05-数据库设计-20261112》§4.2（task.state / task_step.state）为唯一权威**；操作矩阵与恢复资格按接口稿《04-接口设计-20261112》§5.2 与前端稿《03-前端页面设计重构方案-20261112》§5.3（评审九/十轮口径）；计划确认/执行门禁以主方案 §5.6 为权威。

## 1. 任务状态枚举（task.state，13 值）

| 状态 | 含义 |
| --- | --- |
| `ACCEPTED` | 已受理（受理即钉住发布快照与 mode） |
| `PLANNING` | 规划中（mode=PLAN） |
| `PLAN_READY` | 计划就绪待确认（validationStatus=VALID 且未确认/待选定） |
| `NEEDS_INPUT` | 计划缺输入，待补参（POST plans supplement / patch 生成新修订） |
| `WAITING_DEPENDENCIES` | 等待上游任务（只读等待） |
| `QUEUED` | 已排队 |
| `RUNNING` | 执行中 |
| `WAITING_APPROVAL` | 等待审批（高危操作确认等） |
| `WAITING_EXTERNAL` | 等待外部系统 |
| `WAITING_RECONCILIATION` | 等待对账（未决调用按核对结果处置） |
| `SUCCEEDED` | 成功（终态） |
| `FAILED` | 失败（终态） |
| `CANCELLED` | 取消（终态） |

**RESUMABLE 不是状态**（行为规则红线 4，评审十轮）：恢复资格由事实计算（任务处于运行/等待类状态 + 执行租约失效 + 存在可续检查点）→ 服务端在 `availableActions` 返回 `RESUME`；数据库枚举**不加**该值。

## 2. 任务模式与意图（受理即固定）

| 字段 | 枚举 | 规则 |
| --- | --- | --- |
| `task.mode` | `PLAN` \| `AGENT` | 提交时指定、**受理即固定不可变**（1109 §1.1）；省略按 PARAM 类错误拒绝，不做隐式默认（评审十七轮）；SERVICE 首期仅 AGENT |
| `task.purpose` | `PLAN` \| `EXECUTE` | 规划任务/执行任务；execute 创建的执行任务继承来源任务工具上界、会话关联与发布快照 |
| `task_step.state` | `BLOCKED/READY/QUEUED/RUNNING/WAITING_APPROVAL/WAITING_EXTERNAL/WAITING_RECONCILIATION/SUCCEEDED/SKIPPED/FAILED/CANCELLED` | 步骤级投影（DB §4.2）；RESUMABLE 同样不入枚举；CONDITION 为假 → `SKIPPED` |

## 3. availableActions 枚举（服务端计算，前端只渲染不自判——红线 8）

动作全集：`VIEW_DEPENDENCIES`、`CANCEL`、`GOTO_CONFIRM`、`VIEW_EXTERNAL`、`TRACK_EXTERNAL`、`ADOPT_EXTERNAL`、`RETRY`、`DISCLOSE`、`RESUME`、`REISSUE`、`EXPORT`。

| 状态 | availableActions（按状态与权限计算） | 说明 |
| --- | --- | --- |
| `WAITING_DEPENDENCIES` | `[VIEW_DEPENDENCIES, CANCEL]` | 只读等待，展示上游任务与缺失输入 |
| `WAITING_APPROVAL` | `[GOTO_CONFIRM, CANCEL]` | 直达对应确认入口（计划 confirm / InlineApproval） |
| `WAITING_EXTERNAL` | `[VIEW_EXTERNAL, CANCEL]` | 展示外部任务号与已等待时长 |
| `WAITING_RECONCILIATION` | 按**核对结果**返回 `TRACK_EXTERNAL`/`ADOPT_EXTERNAL`/`RETRY`（仅可查证"未受理"时）/`DISCLOSE` + `CANCEL`；每个动作**绑定具体 callId**，提交走对账端点 `POST /v1/tasks/{taskId}/steps/{stepId}/calls/{callId}/reconcile` | 服务端提交时按当时对账证据复核，不符 → 409 `RECONCILIATION_EVIDENCE_CONFLICT`；前端不提供自由"重试" |
| 恢复资格成立 | 叠加 `RESUME`（+`CANCEL`） | 手动/自动恢复同入口先到先得：自动已持租约未提交时返回 pending |
| `RUNNING` | `[CANCEL]` | — |
| 终态（SUCCEEDED/FAILED/CANCELLED） | `[REISSUE, EXPORT]` | 原任务只读；助手端可将目标带回会话作为草稿重新提交 |

计划相关动作不经 availableActions 矩阵、由专用布尔驱动：`canConfirm`/`canExecute`（服务端按确认权与凭据有效期计算，针对**当前所选修订**）；`PLAN_READY` 状态下确认/执行入口随 `plan.confirmation` 呈现（见 §5）。

## 4. 任务事件目录（task_event.type，10 类，DB §4.2 权威）

`TASK_ACCEPTED`、`PLAN_VALIDATED`、`STEP_READY`、`STEP_STARTED`、`STEP_FINISHED`、`SUBTASK_REGISTERED`、`SUBTASK_SETTLED`、`RECONCILIATION_REQUIRED`、`TASK_RESUMED`、`TASK_FINISHED`。

- `TASK_RESUMED` 负载：恢复标识、`recoverySource`（MANUAL/AUTO）、`reasonCode`（EXTERNAL_RESULT_UNKNOWN/LEASE_EXPIRED/EXECUTION_INTERRUPTED/MANUAL_REQUEST）、`checkpoint:{eventWatermark, runRefs}`、`reusedSteps`、`blockedReasons`；与状态 CAS 同事务写入；详情/resume/事件增量共用同一角色投影（USER 仅 reason 摘要，授权 OPERATOR/ADMIN 另见 checkpoint/reusedSteps/blockedReasons）。
- 事件流 `afterSeq` 排他游标，`nextAfterSeq` = 最后扫描 seq（不 +1），补发不重不漏（T2-11）。

## 5. 计划对象状态

| 对象 | 枚举 | 规则 |
| --- | --- | --- |
| `plan_revision.validationStatus` | `PENDING` \| `VALID` \| `INVALID` | 只有 `VALID` 可确认/执行（409 `PLAN_NOT_VALID`） |
| `plan.confirmation.state`（投影） | `UNCONFIRMED` \| `CONFIRMED_VALID` \| `CONFIRMED_EXPIRED` | 按当前所选修订的最新确认凭据（最大 confirm_seq）与 `expires_at` 计算；过期等同未确认（execute → 409 `PLAN_NOT_CONFIRMED`，提示重新确认） |
| `subtask_receipt.merged_decision` | `PENDING` \| `ADOPTED` \| `DISCARDED` | 父任务在全部子任务终态或预算截止后**只合并一次**；无有效候选记 `SUBAGENT_NO_VALID_CANDIDATE` |
| `task_dispatch.state` | `PENDING` \| `LEASED` \| `DONE` | 分派三要素：dispatch_key=重复受理去重；state_version CAS=拒绝过期更新；lease+generation=当前写入权（旧代次提交拒绝/入对账） |

## 6. 主要迁移路径（按接口稿时序图 §3.2/§3.4 固化）

```text
ACCEPTED ──mode=AGENT──▶ QUEUED/RUNNING ──▶ RUNNING（WAITING_APPROVAL/WAITING_EXTERNAL/
         ──mode=PLAN───▶ PLANNING ──▶ PLAN_READY（确认 confirm）──execute──▶ 执行任务（purpose=EXECUTE）
                            │                 │
                            └─▶ NEEDS_INPUT ──补参/修订（baseRevision CAS，旧确认凭据不继承）──▶ PLANNING
RUNNING ─回执未知─▶ WAITING_RECONCILIATION ─对账处置─▶ RUNNING / 终态
运行或等待态 ─租约失效+可续检查点─▶ （投影 RESUME，非持久状态）─resume─▶ RUNNING（写 TASK_RESUMED）
任意运行/等待态 ─cancel──▶ CANCELLED；失败路径 ──▶ FAILED；全部步骤提交 ──▶ SUCCEEDED
```

未在本文列出的迁移/动作，实现卡（T2-2/T2-5/T2-6）按接口稿时序图固化；需新增状态值或动作枚举时，**先回写本契约与数据库稿再实现**（契约先行纪律；枚举扩展走新迁移）。

## 7. 首个模型调用失败时的任务投影（R-48）

模型服务持久化上游 503 并发送失败终止帧后，引擎结束相应运行和尝试。任务详情 `GET /v1/tasks/{id}` 和结果 `GET /v1/tasks/{id}/result` 均返回 `state=FAILED`、`reason=SYS-MODEL-503`、`retryable=false`。客户端可以用原 requestId 重取同一任务。

若发送已登记、引擎中断且未收到终止帧，恢复方须在有效委派内取得过期租约。任务以 `MODEL_RESULT_UNKNOWN` 收口，调用事实保留 `UNKNOWN`，避免重复向供应商发送。

## 本人 PLAN 任务的门户授权

网关按 HTTP 方法和完整任务路径签发门户委派。引擎按已验证租户、主体、任务业务号和持久化应用读取归属，排除集成凭据创建的任务，再以同一任务资源向 app 查询当前平台授权。不存在的任务返回 404，异主或回收授权后返回 403。平台范围按每项能力独立求取。

| 操作 | 所需平台能力 | 默认平台角色 |
| --- | --- | --- |
| 本人任务、事件、结果、步骤调用及计划版本读取 | agent.view | ADMIN、BUILDER、USER、OPERATOR |
| 本人修订、确认 | agent.plan.revise、agent.plan.confirm 分别判定 | ADMIN、BUILDER、USER |
| 执行已确认的本人计划 | agent.plan.execute | ADMIN、BUILDER、USER |
| 运维角色执行已确认的本人计划 | agent.plan.operate，执行请求带 operationReason | OPERATOR |

所有操作要求该应用仍在 agent.view 范围内。修订读返回的 canConfirm、canExecute 同时受修订状态与当前授权约束。操作员执行时在子任务的 TASK_ACCEPTED 事件记录操作原因、主体及所选版本。相同执行请求号重取同一子任务。工作区草稿导入、代理他人和独立审批仍遵循各自接口的权限。
