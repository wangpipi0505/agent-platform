# 路线一落地验证证据 · 可见性规划智能体（2026-10-27）

> 本目录是原始证据；完整报告见 `文档/路线一验证报告-可见性规划智能体-20261027.md`（同目录另有 .docx / .pdf）。

验证目标：用真实系统 `G:\5-YG\space-ground-visible`（空间目标可见窗口计算）验证路线一（HTTP 服务技能化 + 智能体工具调用编排）的可用性。
验证依据：`文档/落地验证方案-三路线本体与智能体-20261026.md` 路线一（§1、§4.5、§9.1）。

## 1. 环境与固定数据集

| 项 | 值 |
| --- | --- |
| 空间服务 | `http://127.0.0.1:8080`，真实 MySQL `space_target_visible`（Flyway v10）+ Orekit `backend/orekit-data`，JDK17 jar，非 demo/H2 |
| 平台 | gateway 18080 / app 18081 / engine 18082 / skill 18083 / model 18084 / monitor 18085，PostgreSQL 25432 |
| 模型 | `model.entry 13` = `deepseek-flash`（capabilities `chat,tools`），经 28400 代理真实调用 |
| 观测星 | 2067 `SHIYAN 20B (SY-20B)`，TLE `1 54700U 22169B   26222.93157105 -.00000044  00000-0  35880-4 0  9998`，载荷 SPACE（距离 1000–4000 km，俯仰 -4–60°） |
| 目标 | 10752 `COSMOS 2499 DEB` |
| 时窗与步长 | `2026-08-10T22:40:00Z` → `2026-08-11T22:40:00Z`，coarseStepSec=60，fineStepSec=1 |
| 数据集说明 | 目录中 5414 颗星仅 5382 颗 TLE 可解析（id 1–32 为掩码 TLE，校验和错误）；仅 7 颗带 `PAYLOAD_JSON_`，其中 2067/2068/2374 为新格式 SPACE 载荷，故固定选 2067；目标库 25870 条，按几何可见性筛选后固定选 10752 |

直接 HTTP 基线（`direct-http-plan.json`）：`POST /api/v1/space/visibility/plan` → HTTP 200，1 条结果、4 段可见弧段，`totalSkippedPairs=0`。

## 2. 技能：12 项导入 → 5 项发布（四道门全过）

发布门（`SkillCatalog.publish`）：DRAFT + `connection_test`（真实 DEBUG 调用，非 mock，同 sha）+ `acceptance`（模型选技能命中率 ≥0.8，同 sha）+ `guard` 声明。

| 技能 | 版本 | sha256（前 12） | 连接测试 | 选取命中率 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `space-target-query` | 1 | `0f2db9df779c` | SUCCEEDED (call 215) | 1.0 | PUBLISHED |
| `space-visibility-plan` | 1 | `80889e9e0a1a` | SUCCEEDED (call 216) | 1.0 | PUBLISHED |
| `space-visibility-submit` | 1 | `213b4d463a8c` | SUCCEEDED (SUBMIT_QUERY) | 1.0 | PUBLISHED |
| `space-visibility-status` | 2 | `0756fa0d303c` | SUCCEEDED (draft v2) | 1.0 | PUBLISHED（v1 已 ARCHIVED） |
| `space-visibility-result` | 1 | `e601a7bc5f1f` | SUCCEEDED (call 218) | 1.0 | PUBLISHED |
| `space-satellite-options` | 1 | `dbb33f82e6` | — | — | DISABLED（5414 条 / ~550 KB 响应超输出预算） |
| `space-downlink-plan`、`space-downlink-validate` | 1 | — | — | — | DRAFT（路线一三场景未使用） |
| `ballistic-file-upload`、`ballistic-file-preview`、`trajectory-fit-package102`、`trajectory-simulate` | 1 | — | — | — | DRAFT（文件链未打通，见第 5 节） |
| `trajectory-retime` | — | — | — | — | **未导入**（历史 12 项导入清单中缺失） |

关键契约（`space-visibility-submit`，`213b4d463a8c`）：

```json
{"invocationKind":"SUBMIT_QUERY","httpMapping":{},"timeoutMs":120000,
 "reconciliation":{"endpoint":"http://127.0.0.1:8080/api/v1/space/visibility/tasks/{jobId}","method":"GET",
  "jobIdPath":"$.taskId","donePath":"$.status","doneValue":"SUCCEEDED","failedValue":"FAILED",
  "failureMessagePath":"$.errorMessage",
  "resultEndpoint":"http://127.0.0.1:8080/api/v1/space/visibility/tasks/{jobId}/result",
  "resultEndpointPath":"$","idempotencyKeyEcho":false}}
```

## 3. 场景执行结果（真实模型工具调用，trace 见 JSON）

### 场景 1 · 同步链（查目标 → 规划 → 解读）— 通过

- runId `r_3902cf0a84b844c9b858f49a01717864`，SUCCEEDED；`engine.tool_call 219` `space-visibility-plan` SUCCEEDED，参数与基线逐字段一致。
- 模型回答与直接 HTTP 完全一致（4 段，见下表），无臆造；tokens：prompt 6516 / completion 532。
- 证据：`scenario-1-sync-plan.json`、`tool-calls-db.json`（call 219）。
- 说明：计划要求“查星→查目标→规划”3 次调用；本次因固定了 2067/10752 且 `space-satellite-options` 因响应过大停用，模型直接调用了规划（1 次）。3 次调用链未达成，属技能集缺口而非编排失败。

| # | 起止（UTC） | 时长 | 观测星光照 |
| --- | --- | --- | --- |
| 1 | 12:19:58–12:22:21 | 143 s | SUNLIT |
| 2 | 13:12:23–13:14:22 | 119 s | UMBRA |
| 3 | 14:57:11–15:01:19 | 248 s | SUNLIT |
| 4 | 16:42:12–16:44:59 | 167 s | SUNLIT |

### 场景 2 · 异步链（提交 → 人工确认 → 自动对账 → 结果）— 部分通过

- runId `r_5e1e60c671634a30829a9c2a879bc052`；`tool_call 221` `space-visibility-submit` 进入 `WAITING_APPROVAL`（`nodeInstanceId 571`，confirmRoles `["ADMIN","APPROVER"]`，snapshotDigest `b6588a0b…`）。
- 审批接口契约：`POST /admin/approvals/{runId}/{nodeId}` 需 `{requestId, approved:true, snapshotDigest}`（传 `decision:"APPROVE"` → `400 INVALID_INPUT "approved必须为布尔值"`）。
- 审批后引擎恢复循环对 `UNKNOWN` 调用发起受控对账，采纳结果 → `tool_call 221` SUCCEEDED，`result.structured` 含完整 4 段弧段；run SUCCEEDED。全程只发送一次业务请求（幂等键 `Idempotency-Key`）。
- 缺陷记录：
  - run 级 `error` 仍残留 `{semantic:"RESULT_UNKNOWN"}`（对账成功后未清理，属状态一致性缺陷）。
  - 结果信封只含规划结果，**不含 taskId/status**，模型明确回复“任务返回中未包含 taskId 字段”；因此 `space-visibility-status`/`-result` 未在链中被调用，“提交 → 轮询状态 → 读结果”的显式任务态未验证。
  - 同幂等键重复提交“不产生重复任务”未实测（本轮只提交一次）。
- 证据：`scenario-2-async-submit.json`、`tool-calls-db.json`（call 221）。

### 场景 2b · 异步失败可读性 — 部分通过

- runId `r_9a617706a79949f18a5177373e5213fc`，观测星 1（掩码 TLE）提交 → `tool_call 225` FAILED，`semantic=REMOTE_FAILED`，reason `外部任务终态失败：wrong checksum of TLE line 1, expected 3 but got 6 (1 00000U 26178B …)`（`tool-calls-db.json`）。
- 但 run 直接 FAILED，`output={}`，用户侧只看到 `REMOTE_FAILED`，失败原因未进入对话。**失败原因在 trace 可读，在用户回答中不可读**，未满足“失败可读、非空结果”的验收口径。
- 证据：`scenario-2b-async-failure.json`。

## 4. 技能编排主体（智能体）

`space-visibility-agent`（CHAT，id 40）draftRev 4 / candidateSha256 `08222c1e3a98…`：

- `skillRefs`：5 个已发布技能钉版本（status@2，其余 @1）。
- `modelBinding.modelEntryId=13`，`guard{Roles:[ADMIN,EDITOR,APPROVER,SERVICE], attributes:{region:[LOCAL]}}`，`inputs[message]`，`output.maxTokens=4096`，`temperature=0.2`。
- `systemPrompt`（1396 字符）注入“可见性规划域”context pack：概念卡（观测星/目标/可见窗口/规划任务）、术语与单位口径（UTC `yyyy-MM-ddTHH:mm:ssZ`、km/°、coarseStepSec=60/fineStepSec=1）、技能使用规则（只读先查后算；提交由平台发起人工确认）、固定数据集示例（2067/10752）。
- **限制**：ontorepo 未部署（无进程、无 `.runtime`、库中无“可见性规划域”），context pack 为按本体设计手写成文后经 `systemPrompt` 注入，**未走 ontorepo API 自动生成**；`ContextAssembler` 本身没有 contextPack 字段，路线一只能以 literal 或已发布模板注入。

## 5. 应用发布门：5/6 通过，评测门禁阻断

`POST /admin/apps/space-visibility-agent/publish/precheck` → `result=BLOCKED`（`app-publish-gate.json`）：
结构 PASS、流程 PASS、引用有效性 PASS、守卫声明 PASS、守卫有效性 PASS、**评测门禁 ERROR**。

- 评测（`monitor.EvaluationService`）判定为 **整份 run output 的精确 JSON 相等**：`Json.tree(expected).equals(Json.tree(run.output))`。
- CHAT 应用 run output = `{text, usage{totalTokens,promptTokens,completionTokens}, actualModel{...}}`，其中 usage/actualModel 每次都变，**任何真实模型回答都不可能与前缀固定的 expected 完全相等**。
- 实测（`app-evaluation-report.json`）：问题“只回复可见弧段数量这个数字”，模型答案 `text="4"` 完全符合设计，expected `{"text":"4"}` 仍判 `passed=false`（REJECTED），因为 output 多出 usage/actualModel。
- 结论：自由文本智能体在当前评测断言下无法通过发布门；这是产品缺陷，不是应用配置问题（建议按 `output.text` 或断言模式比较，或允许忽略易变字段）。

## 6. 本次验证驱动的平台修改（代码在 `feat/v1.2-implementation` 工作区，未提交）

| 文件 | 改动 | 目的 |
| --- | --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java` | +58：`resultEndpoint`/`resultEndpointPath` 独立结果端点、`failedValue`/`failedValues`+`failureMessagePath` 终态失败映射、路径支持 `$`、可选键用 `Objects.toString` 读取、仅 `REMOTE_FAILED` 重新抛出 | 让“提交后另取结果”的真实异步 API 可接入；失败可读 |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java` | 对账路径区分 `unknown` 与已验证终态失败，FAILED 落 `skill.execution_fact` 后 `deliver` | 对账失败不再被吞成 UNKNOWN |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java` | +34：校验 `resultEndpoint`/`resultEndpointPath`/`failedValue(s)`/`failureMessagePath`；空 `httpMapping` 视为缺省 | 契约可表达两段式异步；导入的 SYNC 技能可改 SUBMIT_QUERY |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ExecutionRecovery.java` | 对 `UNKNOWN` 调用先请求 `/internal/skill-facts/{id}/reconcile`，非终态退避 30s→5s | 打通“自动轮询”；此前该端点无任何主代码调用方 |
| `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java` | 仅声明属性名的守卫，允许调用参数缺省（已验证 scope 即边界）+ 回归测试 | 只读技能可声明最小守卫并通过发布/运行 |
| `admin-common/src/main/java/cn/agentplatform/common/PlatformErrors.java` | `invalid(Exception)` 记录 `INVALID_INPUT_CAUSE` | 400 不再无痕吞掉根因 |

测试：`AsyncHttpExecutionTest` 新增 2 例（专用结果端点、终态失败可读）通过；整类 `Tests run: 29, Failures: 4, Errors: 5`，与 `git stash` 基线（27 例同样 9 失败）一致，无新增回归；`SkillRulesTest` 6/6 通过。

## 7. 结论（路线一可用性）

- **可用**：技能化（OpenAPI → 契约 → 真实连通测试 → 模型选取验收 → 发布钉版本）闭环成立；5 个技能四道门全过；CHAT 智能体以真实模型完成同步规划（结果与直连 HTTP 逐字段一致）与异步提交 + 人工确认 + 自动对账取结果，trace、参数、结果均可回放。
- **三个阻断项**（按严重度）：
  1. 应用发布门的评测断言对自由文本智能体不可用（精确 JSON 全等，含易变 usage 字段）→ 智能体无法发布上线。
  2. 异步技能不向模型暴露 taskId/status（结果信封只有业务结果），任务态查询（status/result 技能）无法在链中自然触发；run 级 error 对账成功后仍残留 RESULT_UNKNOWN。
  3. 文件链未打通：`HttpAsyncAdapter` 只支持 JSON 体，多段上传（multipart）无适配；`trajectory-retime` 未导入 → 场景 3 未执行。
- **附带缺口**：`space-satellite-options` 因响应体量停用导致“查星”步缺失；ontorepo 未部署使 context pack 只能手工注入。

## 8. 证据文件

| 文件 | 内容 |
| --- | --- |
| `direct-http-plan.json` | 直连 8080 的基线请求/4 段弧段 |
| `scenario-1-sync-plan.json` | 场景 1 run、trace、模型回答、usage |
| `scenario-2-async-submit.json` | 场景 2 run、审批、submit 调用、残留 error |
| `scenario-2b-async-failure.json` | 失败路径 run 与调用状态 |
| `tool-calls-db.json` | 调用 219/220/221/224/225 的库内参数、语义、守卫角色、对账次数（含 TLE 失败原因） |
| `skills-state.json` | 12 项空间/弹道/轨迹技能当前版本与状态 |
| `app-config.json` | 智能体配置快照（技能引用、模型绑定、守卫、context pack） |
| `app-publish-gate.json` | 预检 6 组结论（评测门禁 ERROR） |
| `app-evaluation-report.json` | REJECTED 的评测报告（actual 含 usage 的完整 output） |

复现脚本（`代码/agent-platform/.local/`）：`r1.py`（管理 API 客户端）、`converge.py`（契约收敛 + 两段式连通测试）、`guardfix.py`、`publish_query_skills.py`、`agent_app.py`、`run_agent.py`、`run_async.py`、`approve_run.py`、`finish_app.py`、`app_eval.py`、`settle_eval.py`、`collect_evidence.py`、`restart_skill.sh`。
