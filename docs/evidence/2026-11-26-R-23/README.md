# R-23 外部系统联调（E2 实跑） — 执行记录 2026-11-26

## 冻结候选实测

2026-11-26 的冻结候选已由 E2 独立 Java 8 宿主跑通。健康返回 READY，任务 `task-3295f74daa744968a7eb` 到达 SUCCEEDED，宿主收到详情、事件、结果和产物。相同 requestId 重试返回了同一任务。首次缺凭据的 503、一次上游模型失败后的超时，以及最终成功样本分别留存。原预置凭据 1–3 保留；本次成功宿主凭据由管理员经正式接口签发，测试结束后回收。

[宿主回执与独立复核](e2-host-download.json) · [实际下载的文件](e2-downloaded-result.json) · [缺凭据负向](e2-host-preflight.txt) · [宿主超时样本](e2-host-timeout.json)。独立下载读取了 606 字节，SHA-256 `8440ae069ec7b0c3d884eeab6472c1ddaddc7154a8b296f8519f27d65c56371f`，与含引号的 HTTP ETag 和回执 digest 匹配。

## 切换前记录

结论先行：**平台侧"集成任务受理"在 E2 上不可完成**。真实 ApiKey 鉴权、身份声明头、委派出票（gateway → engine → app 全链 permit）、集成策略回环均已打通；但任务受理在运行准入处被拒，且两种应用类型都无可行路径：

- 已发布 **CHAT** 应用 + `mode=AGENT` → `403 RUN_AUTHORITY_DENIED 运行授权或材料范围不匹配`
- 已发布 **FLOW** 应用 → 不带 `mode` → `403 SERVICE_AGENT_ONLY 集成方仅可提交 AGENT 任务`；带 `mode=AGENT` → `409 CHAT_REQUIRED 单步 AGENT 任务需要已发布的对话应用`

原始输出见 `api-probe.txt`。

## 1. 环境与置备（仅动 E2 服务器，未改代码）

E2 服务栈在 11-26 10:51 前被停止（Exited 143，仅 pg/redis/prometheus/grafana/otel 存活）。为跑 R-23 做了以下运维置备，全部记在此处作为诚实边界：

| 事项 | 做法 | 备注 |
|---|---|---|
| 起栈 | `docker compose --env-file .env.r23 -f compose-test.yaml -f compose-r23-override.yaml up -d` | 新增 `.env.r23`：7 个镜像指向 11-25 实际构建（`r22-20261125-e2` 等），原 `.env.test` 全指 11-19 的 `0.1.0` |
| authority 镜像 | override 固定为 `agent-platform-v12/authority-service:r22-20261125-e2` | `compose-test.yaml:68` 硬编码 `0.1.0`，不随环境变量；旧镜像路由目录 `rows=336 workload=2`，缺 `integration.credential.issue-source` |
| 委派 | override 设 `AGENT_AUTHORITY_DELEGATION_ENABLED=true` | 默认关，关时审计 `reason=DELEGATION_DISABLED` |
| 集成策略回环 | authority 侧 `AGENT_AUTHORITY_INTEGRATION_POLICY_{URL,ISSUER,KEY_PATH,KID}`；app 侧 `AGENT_POLICY_RESOLVER_{ENABLED,ISSUER,PUBLIC_JWKS}` | 此前两侧均未配置 → 审计 `reason=INTEGRATION_POLICY_UNAVAILABLE`（503）；JWKS 由 `docker exec agent-platform-authority openssl rsa -in /run/secrets/authority-rsa.pem -pubout` 导出模数生成 |
| 集成凭据 | 直接在 `app.integration_credential` 插入 2 行（`satellite-planning-assistant`、`user-governance-assistant`） | **未走 ADMIN 签发 API**：`IntegrationService.issue()` 要求 ADMIN，而门户身份源（`IDENTITY_URL=127.0.0.1:19070`）当前不可用。`verifyApiKey` 只比对 `secret_sha256`，不解析 `secret_ref`，故直置可用 |
| 角色映射 | `role_mapping={"USER":"AGENT_OPERATOR"}` | `AGENT_VIEWER` 不够：`IntegrationRuntimeRoutes.permitsRoles` → `IntegrationTaskRoutes.submitRoles` 要求 OPERATOR/ADMIN |

## 2. 探测结果（完整输出见 `api-probe.txt`）

| # | 探测 | 结果 |
|---|---|---|
| 1 | `GET /v1/apps/satellite-planning-assistant/input-schema`（真实 ApiKey + `X-User-Id/Roles/Attributes`） | `200 OK`，`type=CHAT`，`inputs=[]` |
| 2 | `POST /v1/apps/satellite-planning-assistant/tasks` `{"mode":"AGENT","goal":…,"requestId":…}` | `403 RUN_AUTHORITY_DENIED`（traceId `3fc64251-aad8-48d7-bdbf-092970e68b23`） |
| 3 | `GET /v1/apps/user-governance-assistant/input-schema` | `200 OK`，`type=FLOW`，`inputs=[message]` |
| 4 | `POST …/user-governance-assistant/tasks` `{"inputs":{…}}` | `403 SERVICE_AGENT_ONLY` |
| 5 | `POST …/user-governance-assistant/tasks` `{"mode":"AGENT",…}` | `409 CHAT_REQUIRED` |
| 6 | 无凭据 / 错误 secret | `401 SYS-AUTH-401` / `401 INVALID_CREDENTIAL` |

授权链本身是通的（authority 审计逐跳 permit）：`gateway → engine: proxy.engine.post.TaskController.create`、`engine → app: app.published.read`、`app: app.integration.published.metadata.read`，`subject=demo/space-visibility:sgv-op-1`，`grant_ref=grt-b9cde141-…`。

## 3. 代码级根因

1. **集成面只收 AGENT 模式**：`engine-runtime/src/main/java/cn/agentplatform/engine/application/Tasks.java:107` —— `if (!"AGENT".equals(request.path("mode").textValue())) throw 403 SERVICE_AGENT_ONLY`。
2. **AGENT 模式要求已发布 CHAT 应用**：`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:810-811` —— `409 CHAT_REQUIRED`。
3. **CHAT 首轮准入校验冻结模型引用**：`RunService.java:537-543` `requireFirstChat` → `InitialChatRules.requireSources` / `FrozenModels.reference(snapshot, entryId(snapshot))`。而 demo 应用版本快照的 `config.modelBinding` 是 `{"modelName":"demo-model","providerCode":"demo-provider"}`（无 `modelEntryId`），`dependencyVersions.modelRefs` 为空 —— 与冻结引用契约不符，因此被拒为 `RUN_AUTHORITY_DENIED`（`RunAuthorityStore.denied()` 的通用 403，掩盖了具体字段）。
4. **FLOW 即使放开也过不了图校验**：`engine-runtime/src/main/java/cn/agentplatform/engine/domain/GraphRules.java:156-190` `requirePublishedFlow` 只允许 `START/END/CODE/CONDITION` 节点，而 `user-governance-assistant` 的 9 节点含 `SKILL`/`APPROVAL`。

对照：`r22e2-real-v2`（11-25 PR-B1 实际跑通的那个 CHAT 应用）的 `config.modelBinding={"modelEntryId":1}` 且 `dependencyVersions.modelRefs` 有 `bindingId=1`、`sha256=b04638ca…` —— 说明**运行期要求"冻结模型引用"，而 V901 demo 种子生成的应用不满足该形状**。

## 4. 结论与建议

- R-23 不能记为"通过"：外部宿主即便拿到合法 ApiKey，也无法提交任何任务。这不是配置问题，是**种子数据形状 + 集成面受理口径**两处不一致。
- 已可采信的部分：集成鉴权与身份声明、委派链、策略回环、元数据读取两项（CHAT/FLOW 各一）。
- 建议（已登 R-44，0.5–1 人日）：①V901 种子按冻结模型引用生成 demo 应用（或提供"重发布 demo 应用"的一次性脚本）；②明确集成面是否只支持 AGENT/CHAT —— 若 FLOW 也要支持，`SERVICE_AGENT_ONLY` 与 `requirePublishedFlow` 的节点白名单需一并放开；③把 `RUN_AUTHORITY_DENIED` 拆成可定位的 reason 码（当前 4 处不同校验共用同一码，排障只能读源码）。

## 5. 续跑记录（2026-11-26 11:30–11:40，配额链路修复后）

受理面在形制合规应用上已能通过，但执行面仍被阻断，分两层：

| 层 | 现象 | 处置 |
|---|---|---|
| 分发配额 | `run` 停在 RUNNING；model-gateway 抛 `分布式模型配额暂不可用，请稍后重试`（`ModelQuota` → 503 `SYS-MODEL-503`） | 根因：`model-gateway/application.yml` 用 `AGENT_REDIS_HOST/PORT/PASSWORD`（默认 `127.0.0.1:26379`），而 E2 的 Redis 在 **16379** 且原本无口令。已在 E2 置 `requirepass`（`config rewrite`），并在 override 为 model/engine/gateway/app 显式注入 `AGENT_REDIS_*`；重启后 `18084/actuator/health` = UP |
| 模型材料 | 配额修复后 model-gateway 返回 `cn.agentplatform.common.PlatformException: 材料范围缺失或已改变` | 待定性：疑与 `dependencyVersions.modelRefs` 的 `sha256` 与运行期材料快照比对有关；`r22e2-real-v2` 的 `modelBindingRef.sha256=b04638ca…` 由 11-25 冻结时计算，本轮仅重启服务未重冻结 |

本轮提交（同一凭据、`requestId` 递增）：

| requestId | taskId | 结果 |
|---|---|---|
| `r23-e2-1` | `task-84f0ce20c29c43919ef6` | 200 受理，RUNNING |
| `r23-e2-2` | `task-63da852f63794dc596fa` | 200 受理，RUNNING |
| `r23-e2-3` | `task-22b793d8b891451eb6e6` | 200 受理，RUNNING |
| `r23-e2-4` | `task-33a3af9ad9d943ce912c` | 200 受理，RUNNING（重放同号） |
| `r23-e2-5` | `task-0a6ffe07e33b45c8bafc` | 200 受理，RUNNING（配额修复后，卡在材料范围） |

结论订正：**受理面（任务创建/详情/事件/结果/重放/幂等）在 E2 已完整可用**；执行面缺"模型材料范围"一环，属平台缺陷而非外部宿主问题 —— 已登 R-45。

原始输出：`api-probe.txt`（六项探测）、`api-accept.txt`（受理面五端点转录）、`api-run-blocked.txt`（配额与材料范围阻断转录）。
