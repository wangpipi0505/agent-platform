# 错误码契约（error-codes）

C0-2 契约冻结（台账#C0-2），2026-11-13。
来源：接口稿《04-接口设计-20261112》**§2 全表**（通用约定 + 核心错误码）；主方案 §3.2 行为规则。
使用纪律（台账 §4.1 代码规范）：**新增错误码必须先登记本表再使用**；沿用 admin-common 错误信封（code/message/category/retryable），禁止自造摘要算法与信封变体。

## 1. 响应信封约定（沿用现状，新增接口必须遵守）

| 项 | 约定 |
| --- | --- |
| 信封 | `{code, message, data, traceId}`；错误另有 `userMessage / category / retryable(仅429=true)` |
| category 枚举 | `AUTH` \| `QUOTA` \| `PARAM` \| `TRANSPORT` \| `SYSTEM` \| `BUSINESS` |
| 认证 | 门户 JWT（tenant+principal claims）；服务间走 ServiceTicket；浏览器自报身份永远无效 |
| 幂等 | 写操作携带 `requestId`；`UNIQUE(tenant, principal, request_id)` 去重；同参重放返回原结果，异参返回 409 `REQUEST_REPLAY` |
| 状态冲突 | 期望状态版本 CAS：请求带 `expectedStateVersion`，不一致 409 `STATE_VERSION_CONFLICT` |
| 分页 | `page`/`pageSize`（默认 1/20，上限 100），响应含 `total` |
| 时间/标识 | ISO-8601 UTC；对外标识一律 public_id/taskId 等业务号，不暴露内部 bigint |
| 事件游标 | 所有事件类接口 `afterSeq` 排他增量补发（`nextAfterSeq`=最后扫描 seq，不 +1） |
| 403/404 口径 | 已认证主体访问存在但无权读取的资源 → 403 并审计拒绝原因；资源不存在或嵌套路径归属不匹配 → 404 |

## 2. 核心错误码全表（接口稿 §2 新增 12 项 + CAS 冲突项）

| code | HTTP | category（建议） | 场景 |
| --- | --- | --- | --- |
| `PLAN_DIGEST_CHANGED` | 409 | BUSINESS | 执行/确认请求携带的 plan digest 与固化版本不符 |
| `PLAN_REVISION_CONFLICT` | 409 | BUSINESS | 修订基线 baseRevision 过期（他人已出新版本） |
| `PLAN_NOT_CONFIRMED` | 409 | BUSINESS | execute 所选版本 VALID 但无确认凭据**或凭据已过期**（提示确认/重新确认） |
| `PLAN_NOT_VALID` | 409 | BUSINESS | confirm 所选版本校验未通过（INVALID/PENDING），不可确认 |
| `REQUEST_REPLAY` | 409 | BUSINESS | 同一 requestId 用于不同参数/版本（含计划确认请求号复用于另一版本，评审六轮 04） |
| `RECONCILIATION_EVIDENCE_CONFLICT` | 409 | BUSINESS | 对账处置与提交当时的对账证据不符（如对已受理调用提交 RETRY，评审十轮 P1-2） |
| `SESSION_TURN_ACTIVE` | 409 | BUSINESS | 同会话上一 turn 仍在执行（现状语义保留） |
| `CONTEXT_BUDGET_EXCEEDED` | 422 | BUSINESS | 必需材料超窗口且无法裁剪（指明缺口清单） |
| `SUBAGENT_DEPTH_LIMIT` | 422 | BUSINESS | 子代理深度超限 |
| `RESOURCE_NOT_LOADABLE` | 422 | BUSINESS | 轻量装载失败（指明模型/插件/技能/连接与原因，1109 §5.3） |
| `APPROVAL_UNAVAILABLE` | 409 | BUSINESS | 审批通道不可用＝fail-closed 拒绝 |
| `MEMORY_VERSION_CONFLICT` | 409 | BUSINESS | 记忆更新期望版本号过期 |
| `STATE_VERSION_CONFLICT` | 409 | BUSINESS | 状态版本 CAS 不一致（§2 通用约定表；task/step/dispatch 终态提交等场景） |
| `SUBAGENT_NO_VALID_CANDIDATE` | —（任务终态原因码） | BUSINESS | 全部规划子代理无有效候选时父任务 FAILED 原因码（主方案 §5.5；经 TASK_FINISHED 事件 reasonCode 与任务详情暴露，非 HTTP 响应码。T2-7 登记转正，接口稿 §2 回写随文档同步） |
| `TASK_DISPATCH_STALE` | 409 | BUSINESS | 分派三要素（owner+generation+租约）不匹配的晚到回执：先以 RECONCILIATION_REQUIRED 事件落对账留痕（独立事务，可追溯不覆盖）再拒绝，成功结果与下游就绪只提交一次（T2-6；R-3 登记 2026-11-18） |
| `TASK_STEP_STALE` | 409 | BUSINESS | 步骤结果按事务内当前 state_version 条件更新未命中——晚到更新不覆盖（task 级 CAS 场景沿用 `STATE_VERSION_CONFLICT`；T2-6；R-3 登记 2026-11-18） |
| `COMPACTION_PAIRING_BROKEN` | 409 | BUSINESS | 压缩区间将未闭合的工具调用/结果配对拆开（区间内存在有 call 无 result 的 callId）——硬性约束禁止本次压缩（T2-9；R-3 登记 2026-11-18） |
| `INTEGRATION_PATH_FORBIDDEN` | —（审计事件码） | SECURITY | SERVICE/AGENT 集成票据访问白名单外路径：审计行 `AUDIT INTEGRATION_PATH_FORBIDDEN`（凭据标识+主体+路径+traceId，无密钥本体），对外响应恒为 403 `APP_BINDING_DENIED`"出票仅可访问绑定应用的已登记接口"（T2-15；R-3 登记 2026-11-18） |

> **外部集成方说明（R-3）**：路径越权的对外响应码恒为 403 `APP_BINDING_DENIED`；`INTEGRATION_PATH_FORBIDDEN` 仅用于平台审计行，不随响应信封暴露。集成方按 `APP_BINDING_DENIED` 处理：核对出票 policy 白名单与所调路径/方法后重新出票，勿对同票据重试同路径。前端对 `TASK_DISPATCH_STALE`/`TASK_STEP_STALE`/`COMPACTION_PAIRING_BROKEN` 的兜底文案见 `web/shared/reason-code-text.ts`（恢复面板/对账列/失败摘要按码附文案）。

## 3. 待登记区（主方案引用、尚未列入接口稿 §2 表——使用前须先回写接口稿 §2）

> 以下错误码在主方案正文中出现，但接口稿 §2 错误码表未收录。按台账 §3.2 红线"新增错误码必须先登记接口稿 §2 表再使用"，实现卡动用前先完成登记回写（澄清结论回写文档后才能继续该卡，台账 §1.2）。本契约为其预留登记位，作为 R-REG/评审核对清单。

| code | 出处 | 拟用场景 | 状态 |
| --- | --- | --- | --- |
| `MODEL_SWITCH_COMPACTION_FAILED` | 主方案 §5.4 | 切换模型的窗口检查失败（按目标模型窗口检查历史，浓缩未完成即拒绝，保留原请求头） | 待登记（T2-9 使用前） |
| `SUBAGENT_NO_VALID_CANDIDATE` | 主方案 §5.5（数据库稿 §4.2 subtask_receipt） | 全部规划子代理无有效候选时父任务 FAILED 原因码 | 已登记 §2（T2-7，2026-11-14） |
| `OUTPUT_SCHEMA_INVALID` | 主方案 §6.7（接口稿 §5.5 retry.on） | 模型输出 Schema 校验失败：既是 retry.on 重试条件值，也是 `OUTPUT_INVALID` 的诊断映射目标；写 `model_invocation.output_validation` | 待登记（T2-10 使用前） |
| `PLAN_NOT_CONFIRMED` 引导链 | 接口稿 §5.2 | 前端收到后按确认权展示"重新确认所选版本"动作（非新错误码，行为约定） | 已在 §2 表，无需动作 |

## 4. 现有沿用错误码（不重复定义，引用现状）

平台既有错误码（`OUTPUT_INVALID`、审批/发布门禁、信封边界等）沿用 `contracts/error-envelope-boundaries.md` 与现有实现；新契约增量仅本文件 §2/§3 所列。`OUTPUT_INVALID` 在任务诊断投影为 `OUTPUT_SCHEMA_INVALID`（存量发布沿用原 `onInvalid` 行为，接口稿 §5.5）。
