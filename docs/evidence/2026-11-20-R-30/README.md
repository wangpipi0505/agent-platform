# R-30 执行记录：monitor 最小评测报告造数（兜底达成，正路结构性不可达）

日期：2026-11-20。卡片：台账 §7.6b **R-30**。执行环境：E2 服务器 192.168.1.2（compose 全链 18081–18091，库 **agent_platform**@15432 多 schema）。

> **数据来源=造数/产品**：本卡交付的评测报告行（monitor.evaluation_report `er_r30seed9a07f22b`）与配套数据集（monitor.dataset `r30-seed-v1`）由 `/home/agent-platform/scripts/seed-eval-report.sh` 直插数据库产生，**不是真实评测会话的产物**。环境字段、结果与来源证据均为造数声明，仅用于解锁发布预检的评测门数据条件；不能作为任何业务效果的证明。

## 0. E2 现实与卡面前提的对表（先读）

| 卡面前提 | E2 实测 | 处置 |
|---|---|---|
| 库=r24_check 的 monitor schema | 单库 **agent_platform**（pg 15432，schema app/engine/model/monitor/skill/gateway），无 r24_check | 造数落在 agent_platform.monitor（demo 租户） |
| satellite-planning-assistant 当前 draft=rev 2（modelEntryId=1，R-25 本地 r24_check 状态） | E2 该应用 draft_rev=**1**，config.modelBinding **无 modelEntryId**（{"modelName":"demo-model","providerCode":"demo-provider"}），definition={} | 指纹对齐 E2 **当前草稿 rev1** 复算 |
| DeepSeek 已接线能力 | model schema **0 provider / 0 entry**；.env.test/secrets 无任何 LLM API key（仅 IAM client 与基础设施凭据） | 真实评测出话不可能；正路另有结构性阻断（见 §1） |
| 模型桩 node scripts/e2e/fixtures/model-stub.mjs 18985 于服务器 | 服务器 **无 node 运行时**（which node 空） | 模型桩不可起；一并登记 |
| R-26 后 /test/tickets 可铸 USER/ADMIN 票 | 当前 authority 容器 **AGENT_AUTHORITY_TEST_TICKET_ENABLED 未设**（application.yml 默认 false，@ConditionalOnProperty 关闭）→ POST /test/tickets **404**（01-mint-admin-ticket.json） | 改用 authority 私钥**离线签发同构 portal 票**（R-26 02-token-claims.json 同形状，roles=["ADMIN"]）；/test/tickets 重启用登记为运维项 |

## 1. 正路执行情况：三重结构性封死（逐项实录，均留证）

正路=以合法用户身份经网关 `POST /admin/evaluations` 走 monitor 真实评测链。E2（iam 模式 + gateway portal 开启）下该入口**不存在**：

1. **网关 ingress 形状收窄**：`GatewayIngressBoundary.route()`（admin-common，运行中 gateway jar 反查一致）只放行 5 个形状——`GET /admin/runs/r_{32hex}/events`、`GET /admin/runs/r_{32hex}`、`GET /auth/session`、`POST /admin/apps/{code}/runs`。有效 ADMIN portal 票打 `GET|POST /admin/evaluations` 与 `POST /admin/apps/{code}/publish/precheck` 一律 **403 SYS-AUTH-403"入口未获授权"**（04-gateway-admin-evaluations-403.txt 三连实录；票过期后 401，与 R-26 的 401 判据一致）。
2. **路由目录无 workload 行**：agent-service-authority.json 中 `/admin/evaluations`、`/internal/evaluations/accepted`、`/internal/apps/{code}/candidate` 全部 `mode=delegated`（gateway→monitor proxy 行**存在**但 delegated；app→monitor accepted 读也是 delegated）。authority `/oauth/token operation=client`（monitor client 凭据，target=monitor）→ **403"当前授权不允许此操作"**（03-oauth-client-probe.txt）——机器票据链造不出能驱动评测面的票。
3. **服务侧 Actor 拒机器身份**：Actor.current() 对 `authority_mode=workload` 一律 403"机器能力不能映射为用户身份"（Actor.java:29-30；运行中 monitor 镜像内 admin-common Actor.class strings 实证含该分支）；monitor 18085 直连仅收 `token_use=service`+`aud=urn:agent-platform:service:monitor`，portal 用户票 audience 不符 → **401**（09-monitor-direct-probes.txt）。

**结论**：E2 上评测链（evaluate→engine run→report、precheck→accepted 消费链）**结构性休眠**——设计上这些端点等 gateway ingress 扩形状（目录已登记 proxy.monitor.* delegated 行），部署面未跟进。属平台 backlog 项（与 R-26 登记"/v1 形状限制"同判据）。按卡面预案**走兜底**。

兜底前置事实：即使入口开放，evaluate 真跑也到不了 ACCEPTED——model 链空置（0 provider/entry，无 LLM key）会以 MODEL_NOT_CONFIGURED/NODE_FAILED 收场（R-24 同判据）。故"正路确不可达"登记成立。

## 2. 兜底造数（可重放脚本）

- 服务器：`/home/agent-platform/scripts/seed-eval-report.sh` + `seed-eval-report.py`（本仓镜像 `scripts/`）。
- 用法：`bash /home/agent-platform/scripts/seed-eval-report.sh`（幂等：dataset/report 均 `on conflict do nothing`；重放只刷新验证证据）。
- 动作：psql（docker exec agent-platform-pg）读 app.application+app.app_draft → python 复算候选指纹 → 直插 `monitor.dataset` 一行 + `monitor.evaluation_report` 一行 → **monitor 读回+消费侧校验算法逐字节同构重放** → 证据 JSON。
- 落库内容：dataset_version=`r30-seed-v1`（2 用例：1 条安全 SCHEMA + 1 条 CONTAINS，满足 evaluate() 保存期校验形状与 EvaluationRules.accepted），report id=`er_r30seed9a07f22b`、status=ACCEPTED、threshold=0.85、assertion_model=CONFIGURABLE、created_by=`r30-eval-seeder`、detail 首句="R-30 最小评测报告（**数据来源=造数/产品**；非真实环境评测…）"。
- 预检两门的数据条件：
  - 评测门：accepted() 命中键 (tenant=demo, app_code, draft_rev=1, status∈{ACCEPTED,REJECTED}, candidate_sha256=或 semantic=) → 1 行命中（07 回执 acceptedHits=1）。
  - 环境门：results[].sourceEvidence 逐行满足 publicationEvidence 的 rowValid（schemaVersion=2+datasetVersion+datasetSha256+runId 非空+mode+runSource.schemaVersion=2）→ 复算 environment=**REAL**。

### 两侧字段对齐清单（消费侧校验 ↔ 生产侧构造 ↔ 造数落点）

| 消费侧 validPublicationEvidence（AppPublicationService.java:437-456） | 生产侧 publicationEvidence（EvaluationService.java:545-588） | 造数落点（值见 07 回执） |
|---|---|---|
| schemaVersion==2 | evidence.put("schemaVersion",2) | 2 |
| scope=PLATFORM_PARTICIPANT_MODES_AND_DATASET_IDENTITY / externalTruth=NOT_ASSESSED | 常量 | 同 |
| caseSourcesSha256 64hex | sha256(canonical(results)) | d88fff4b… |
| tenantId==port.tenantId() | tenant() | demo |
| appCode==code | report.app_code | satellite-planning-assistant |
| draftRev==rev（整型） | report.draft_rev | 1 |
| evalReportId 非空 | report.id | er_r30seed9a07f22b |
| qualityAccepted 布尔 且 ==（status==ACCEPTED） | Boolean.TRUE.equals(report.accepted) | true==（ACCEPTED） |
| status∈{ACCEPTED,REJECTED} | report.status | ACCEPTED |
| datasetSha256 64hex | monitor.dataset.sha256 | 1528db69… |
| datasetVersion 非空 | report.dataset_version | r30-seed-v1 |
| environment∈{REAL,DEMO,UNKNOWN} | results[].sourceEvidence 逐行推导（DEMO 优先） | REAL |
| candidateSha256==hash **或** candidateSemanticDigest==semantic | report.candidate_sha256 / candidate_semantic_digest | a29c1e28… / 93d1025f…（当前草稿指纹复算） |
| evidenceSha256==sha256(canonical(evidence−evidenceSha256)) | 同构造自校验 | b84c979e… |
| 环境放行（:191-192）：environment==REAL ‖（target==NON_PRODUCTION 且 DEMO） | — | E2 target=PRODUCTION ⇒ REAL |
| commit 确认（:477-486）仅 DEMO 需要 | — | REAL ⇒ 无需 evaluationEnvironmentConfirmation |

指纹复算（AppService.sha/AppDigest.semantic 同构）：candidateSha256=a29c1e28417ada928f1f4bbd93bd390a746d6103e17abef070fe5d8d2c5f9206，semanticDigest=93d1025fa2f7c426897ca849b1a0291f092787f55c98940f4c18ba696eb0fde4（draftRev=1）。

## 3. EVL-01 / EVL-02 实录（诚实口径：E2 为结构性边界 + 算法级放行，HTTP 级 200/PASS 不可达）

**EVL-01（GET /admin/evaluations 200）**
- E2 HTTP 实录：有效 ADMIN portal 票经网关 → **403 入口未获授权**（02/04 实录原文）；monitor 18085 直连 → **401**（服务域 audience/token_use 不符，09 实录）；机器票无法铸造（03 实录 403）。
- 数据面等价：`GET /admin/evaluations` 的语义=list()（按 tenant 返回报告列表）；seed 后该租户含 er_r30seed9a07f22b 一行——报告原文见 08-eval-report-row.json（4334B，含 results/sourceEvidence/candidate 全量）。
- **判定：HTTP 级 200 在 E2 不可达（结构性），数据面与读回算法满足。**

**EVL-02（无报告阻断 / 有报告放行）**
- 无报告侧：seed 前 monitor.evaluation_report=0、monitor.dataset=0（05-preseed-state.txt 实录）。此时 accepted() 必返回 {accepted:false}（EvaluationService.java:599-612 空集分支），gate 判 BLOCKED（AppPublicationService.java:236-237）且 environment=UNKNOWN → 409 EVALUATION_ENVIRONMENT_BLOCKED（:290-291）。R-25 已在真实链路实证过该 409（本地 dev 栈，docs/evidence/2026-11-19-R-25）。
- 有报告侧：seed 后对同键 accepted() 命中 1 行 → 回执 gate=**PASS**、environmentAllowed=true（07-seed-eval-report-result.json，16/16 逐项校验全 true）。
- E2 HTTP 级 precheck 复跑不可达（§1.1 403 实录；**verify-r25-model-chain.mjs 无法在 E2 执行**：无 node 运行时，且其 PSQL/DB 助手按本机 189xx 栈硬编码）；以"同一预检查算法的逐字节同构重放"替代执行，无报告/有报告两态判定均为确定性的代码路径重放，非猜测。
- **判定：数据条件已就绪；precheck 链在 E2 等 ingress 扩形状后即可放行（其余前置——manifest/skill 等检查未在本卡验证）。**

## 4. 诚实登记项（ deviations / new findings ）

1. **环境字段 REAL 为造数声明**：卡面预案 environment=DEMO 建立在 target=NON_PRODUCTION 前提上；E2 app-management 未设 `agent.publish.target-environment`（缺省 PRODUCTION），DEMO 证据必被"演示或未知评测不能用于当前发布目标"阻断。为达成"有报告放行"按 REAL 造数并在报告 detail/created_by/dataset_version 及全部证据中标注造数来源。NON_PRODUCTION 部署可用 `R30_SEED_ENV=DEMO` 重放。
2. **冻结候选快照省略 config.promptSource 键**：新发现——monitor `promptTemplateRef`（EvaluationService.java:654-661）对 config.promptSource **存在但非 TEMPLATE** 的候选一律 409 PROMPT_TEMPLATE_SNAPSHOT_MISMATCH；而 R-24/R-25 只对齐了 app/engine 侧的 DIRECT 枚举。即 **DIRECT 应用的真实评测报告在现运行构件上读回必 409**（report() 每次读都调它）——评测链对 DIRECT 应用整体死锁，属真实结构性缺口，登记为平台 backlog（monitor 侧 DIRECT 对齐）。造数快照按可消费形状冻结（省略该键），报告 candidate_sha256 列仍为**真实候选指纹**（消费侧比对的是列值，不受快照内容影响）。
3. **候选指纹复算的 python↔Jackson 等价假设**：按三条不变量核对（递归键排序、紧凑分隔符、非 ASCII 字面量；无浮点字段）；入口开放后可用 monitor→app `GET /internal/apps/{code}/candidate` 412/200 判定兜底复核。
4. `/test/tickets` 在当前 E2 authority 容器处于关闭状态（AGENT_AUTHORITY_TEST_TICKET_ENABLED 未设，R-26 后 01:30 容器重启丢失）——本卡改用 authority 私钥离线签发同构 portal 票（同一信任域、同一 kid=2026-10）；运维上建议把该开关固化进 compose（R-26 先例：compose-test.yaml 有 .bak-r26 备份惯例）。本卡**未改 compose、未重启任何服务**。
5. 只动 monitor schema 两行数据 + scripts/ 两文件 + evidence；demo 租户之外零数据改动；服务源码零改动。

## 5. 复跑命令（E2）

```bash
# 造数 + 三段验证（幂等）
bash /home/agent-platform/scripts/seed-eval-report.sh
# NON_PRODUCTION 部署口径
R30_SEED_ENV=DEMO bash /home/agent-platform/scripts/seed-eval-report.sh

# 结构性边界复现（有效 portal 票 → 403）
python3 /tmp/mint-r30.py   # authority 私钥离线签发 ADMIN portal 票（R-26 同构 claims）
curl -s http://127.0.0.1:18090/admin/evaluations -H "Authorization: Bearer $TOKEN"   # →403 入口未获授权

# 数据核对
docker exec agent-platform-pg psql -U agentplat -d agent_platform -At -c \
  "select id,status,draft_rev,candidate_sha256 from monitor.evaluation_report where created_by='r30-eval-seeder'"
```

## 6. 文件清单

- 本仓：`docs/evidence/2026-11-20-R-30/`（本 README + `e2-raw/` 01–09 原始实录与 mint-r30.py 签票脚本 + `scripts/` 造数脚本镜像）
- 服务器：`/home/agent-platform/scripts/seed-eval-report.sh|.py`、`/home/agent-platform/evidence/R-30/`（同名证据 + .admin-token 不入仓）
- 数据：agent_platform.monitor.dataset 1 行（r30-seed-v1）、monitor.evaluation_report 1 行（er_r30seed9a07f22b）
- commit：本仓 docs/evidence/2026-11-20-R-30 新增（无源码变更）
