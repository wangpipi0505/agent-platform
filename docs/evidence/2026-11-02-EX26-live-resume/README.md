# EX-26 服务级验收：真实恢复链 resume → authorize → recordCheck

日期：2026-11-02
范围：`engine-runtime`（生产消费方 `RunAuthority.currentRetained`）× 真实 authority-service（127.0.0.1:18086）× 真实 PG。

## 结论

生产恢复链在**真实服务 + 真实库**上跑通：从库里读回 `engine.run_authority` 绑定 → 用真实 delegation grant
向 18086 换票（`resume`）→ 采纳（`authorize`）→ `RunAuthorityStore.strict` 全字段校验 → `MaterialAuthorization`
覆盖校验 → `recordCheck` 以 CAS 落库。

- 正例通过：proof 非空、caller=engine、target/action=`engine`/`run.advance`、operation `DELEGATED`/`PORTAL`、
  天花板与 origin 与 grant 逐字段相同，`last_decision_id/last_checked_at/check_version` 均已推进、`blocked_reason` 为空。
- 负例通过：过期 grant 记 `AUTHORITY_EXPIRED`（不触网、不静默放行）；不可达授权服务记
  `AUTHORITY_UNAVAILABLE` + `retry_not_before`（有界重试，退避期内不再推进 CAS）。
- 既有断言未改动；未提交、未 push、未改产品源码（只新增两个测试文件）。

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.997 s -- in cn.agentplatform.engine.application.RunAuthorityLiveResumeTest
```

## 环境

| 项 | 值 |
| --- | --- |
| authority-service | `http://127.0.0.1:18086`，`agent.authority.mode=iam`，issuer `http://127.0.0.1:18086`，kid `ex26-20261030` |
| 人身份 | usermgmt stub `http://127.0.0.1:18070`，tenant `local-v12`，audience `urn:agent-platform:portal`，client `gateway` |
| PostgreSQL | `127.0.0.1:25432`，库 `agent_platform_engine_test`，用户 `agent_v12` |
| 代码状态 | HEAD `59ef2b42`（含 EX-26 的恢复票消费修复） |

需要的环境变量（**只登记变量名，值不落盘**；取值方式见 `.local/ex26-authority-run/env.sh` 与 `.tools/env-load.sh`）：

- `EX26_LIVE_RESUME=true` —— 门控；未置 true 时本用例整体跳过（离线构建不连网）。
- `AGENT_DB_PASSWORD` —— PG 口令。
- `AGENT_SECRET_IAM_GATEWAY_CLIENT` —— 门户 session / exchange 的 Basic 凭据。
- `AGENT_SECRET_IAM_ENGINE_CLIENT` —— engine 侧 resume / authorize 的 Basic 凭据。
- 可选：`EX26_AUTHORITY_BASE`（默认 `http://127.0.0.1:18086`）、`EX26_LIVE_DB_URL`
  （默认 `jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test`）、`AGENT_DB_USER`（默认 `agent_v12`）。

## 部署前置条件（本次实际发生：旧 jar 过期）

第一次探测发现运行中的 18086 签出的会话票**没有 `origin_authentication_id`**。原因是运行实例的 jar 构建于
2026-10-30 15:50，而 origin 由 `f5f4baa5`（EX-44 1a）引入——旧构建下 `authorizeRetained` 只能返回
`RetainedAuthorization.currentOnly`，`RunAuthorityStore.strict` 的 `requireOriginAuthenticationId()` 必然拒绝，
恢复链在旧部署上**不可能**通过。这不是测试可绕过的差异，属于部署构建过期。

处理（不改源码，只重建 + 重启）：

```bash
# 1) 停掉旧进程（Windows 上 jar 被占用会让 repackage 失败）
taskkill //PID <18086 的监听 pid> //F
# 2) 用当前工作树重建
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -o -pl authority-service -am package -DskipTests
# 3) 用既有证据脚本重启（env.sh 提供 iam/密钥/端口）
bash .local/ex26-authority-run/start-authority.sh
```

- 新 jar：`authority-service/target/authority-service-0.1.0-SNAPSHOT.jar`，2026-11-02 16:23:24 构建。
- 重启后会话票带 `origin_authentication_id=oa1:<32hex>`（用例 `portalSession()` 里显式断言，缺它就给出“运行实例早于 EX-44，重启后重跑”的失败信息）。
- 说明：重建时的**工作树**含未提交的 EX-45 观测窗改动（`AuthorityServiceProperties.eventsHardWindowSeconds`、
  `DelegatedAuthority` 的 S3/S5 硬窗读配置、删掉一处 DEBUG 打印）。这些改动不在 resume/authorize 路径上，
  但“这份证据对应哪个构建”必须写清楚：**HEAD + 当时工作树**，不是干净 HEAD。

## 用例表

新增文件（均为测试源码，不进入生产 classpath）：

- `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunAuthorityLiveResumeTest.java`
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/LiveMaterialScopeDigest.java`
  （只做可见性转发：`MaterialScopeStore.payloadDigest` 是包内方法，测试不复制 normalize 逻辑，避免“摘要双实现”）

| 用例 | 断言要点 |
| --- | --- |
| `currentRetainedResumesAuthorizesAndRecordsCheck` | 换出的票 `authority_mode=delegated`、caller/target=engine、scope=`run.advance`、aud=`urn:agent-platform:service:engine`；`currentRetained` 返回的 proof 非空、caller=engine、call=`engine/run.advance/app-a@1`、operation `DELEGATED`+`PORTAL`、`hasInitialProof()`、天花板= grant 天花板、origin= grant origin；库中 `last_decision_id`=本次决策、`last_checked_at` 非空、`blocked_reason`/`retry_not_before` 为空、`check_version=1` |
| `expiredGrantRecordsAuthorityExpiredWithoutNetwork` | 403 `AUTHORITY_EXPIRED`；库中 `blocked_reason='AUTHORITY_EXPIRED'`、`last_decision_id` NULL、`retry_not_before` **NULL**、`check_version=1` |
| `unreachableAuthorityRecordsBoundedRetry` | 503 `IAM_AUTHORITY_UNAVAILABLE`；库中 `blocked_reason='AUTHORITY_UNAVAILABLE'`、`retry_not_before` 非空、`check_version=1`；第二次调用仍 503 且 `check_version` 不变（退避期内不写库） |

## 命令原文与结果

```bash
cd "G:/207/项目/算法重组工具/代码/agent-platform"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
set -a
. ./.local/ex26-authority-run/env.sh
. ./.tools/env-load.sh
set +a
export EX26_LIVE_RESUME=true
mvn -o -pl engine-runtime test -Dtest=RunAuthorityLiveResumeTest -DfailIfNoTests=false
```

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.997 s -- in cn.agentplatform.engine.application.RunAuthorityLiveResumeTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

默认（不设 `EX26_LIVE_RESUME`）跳过，保证离线构建不连网：

```
[WARNING] Tests run: 3, Failures: 0, Errors: 0, Skipped: 3, Time elapsed: 0.005 s -- in cn.agentplatform.engine.application.RunAuthorityLiveResumeTest
```

## `engine.run_authority` 必需列清单

测试行不是“随便填”：`RunAuthorityStore.load` / `MaterialScopeStore.scopes` 会反向逐字段校验。
“必须与 grant 一致”的列一旦漂移，`strict(...)` 会以 `RUN_AUTHORITY_DENIED` 拒绝（这正是恢复期要防的）。

| 列 | 来源 | 约束 |
| --- | --- | --- |
| `run_id` | 新造 run | PK，FK → `engine.run(id)` |
| `tenant_id` / `principal_id` | grant 天花板 `tenantId`/`principalId` | **必须与 grant 一致**（owner 三方一致：行、`initial_ceiling`、`engine.run`） |
| `grant_ref` | grant `grant_ref` | **必须一致**（`DelegationRef` 相等的一部分） |
| `grant_expires_at` | grant `grant_expires_at`（epoch 秒 → `Instant.ofEpochSecond`，纳秒必须为 0） | **必须一致**（`DelegationRef` 用 record equals 比 Instant，微秒/纳秒差一点都拒绝） |
| `initial_ceiling` | grant 天花板 Actor 的规范形状 `{tenantId,principalId,roles,attributes}`（**不是**票面的 snake_case 形状） | 必须恰好 4 字段；attributes 只允许 `region/dataLabels/bizSystemCode/userId`，值非空去重，`bizSystemCode`/`userId` 恰好 1 个 |
| `resource_digest` | grant resource `version_digest` | 64 位小写 hex；**必须与 grant 一致** |
| `source_kind` | 常量 `'PORTAL'` | CHECK 限定 |
| `origin_authentication_id` | grant `oa1:<32hex>` | **必须与 grant 一致**（`strict` 比对） |
| `app_version_id` | 造 run 时的 `app_version_id`（>0） | 必须等于 `engine.run.app_version_id` |
| `app_version_no` | resource id 的 `@no` 部分 | `root_id = app_code + '@' + app_version_no` |
| `root_kind` | 常量 `'app-version'` | CHECK 限定 |
| `root_id` | `app_code@no` | **必须与 grant resource id 一致** |
| `initial_ceiling_sha256` | `MaterialAuthorization.ceilingSha256(actor)` | 64 hex；必须等于按 `initial_ceiling` 重算的值 |
| `accepted_snapshot_sha256` | `MaterialScopeStore.payloadDigest(db,"run-snapshot",runId)` | 64 hex；三个材料范围的 `material_version` 都必须等于它 |
| `admission_decision_id` | 本次真实 authorize 的 `decision_id` | 必须等于三个 scope 的 `sourceDecisionId` |
| `authorization_version` | 本次真实 authorize 的 `authorization_version` | 必须等于三个 scope 的 `sourceAuthorizationVersion` |
| `operation_id` | 本次真实 authorize 的 `token operation_id` | 非空（本链不参与比对） |
| `last_decision_id` / `last_checked_at` / `blocked_reason` / `retry_not_before` / `check_version` | `recordCheck` 写入 | 触发器只放行这 5 列 |

另外三条隐性要求（不写在这张表里但同样 hard-fail）：

1. `engine.run` 必须存在且 `app_code`/`app_version_id` 与上面一致；`CHAT`/`TASK` 触发要求 `app_version_id` 非空。
2. `engine.material_scope` 必须有**恰好 3 行**根范围（`run-snapshot`/`run-inputs`/`run-version-refs`），
   `material.id` = `engine.run.public_id`，`scope_sha256 = MaterialAuthorization.scopeDigest(scope)`，
   `run-version-refs.parentScopeSha256 = [run-snapshot.scopeSha256]`。
3. 天花板 attributes 里 `dataLabels` 是**义务**不是权限：它只进 `requiredDataLabels`，不进 `requiredEntitlements`
   （后者 = attributes 去掉 `dataLabels`）。

## 审计证据（18086 日志，同一次正例）

```
delegation.audit endpoint=decision outcome=permit operation=exchange caller=gateway target=engine action=proxy.engine.post.RunController.run decision_id=dec-c83287ff-... grant_ref=grt-f3ff147b-... grant_expires_at=1793608073
delegation.audit endpoint=decision outcome=permit operation=authorize caller=engine target=engine action=proxy.engine.post.RunController.run decision_id=dec-7e8cbda0-... subject_caller=gateway ticket_jti=tkt-f35e5b11-... grant_ref=grt-f3ff147b-... subject=local-v12/admin
delegation.audit endpoint=token outcome=permit operation=resume caller=engine target=engine action=run.advance decision_id=dec-09051460-... grant_ref=grt-f3ff147b-... grant_expires_at=1793608073
delegation.audit endpoint=token outcome=permit operation=resume caller=engine target=engine action=run.advance decision_id=dec-1d4760d9-... grant_ref=grt-f3ff147b-... grant_expires_at=1793608073
delegation.audit endpoint=decision outcome=permit operation=authorize caller=engine target=engine action=run.advance decision_id=dec-1a9b6f99-... subject_caller=engine ticket_jti=tkt-a364dc6a-... grant_ref=grt-f3ff147b-... subject=local-v12/admin
```

解读：exchange 产生 gateway→engine 的 delegated 票；第一次 authorize（action=`proxy.engine...run`，`subject_caller=gateway`）
建立可被 resume 命中的决策；`resume` 两次（用例先主动换一张票做票面断言，再走 `RunAuthority`）
都用同一 `grant_ref`；最后一次 authorize（action=`run.advance`，`subject_caller=engine`）就是 `RunAuthority` 的采纳。
`endpoint=token` 对应 `/oauth/token`，`endpoint=decision` 对应 `/internal/authorize`。

## 负例结果（含与预期的差异）

1. **过期**：库里 `grant_expires_at` 改到过去 → `RunAuthority` 在触网前就 `recordCheck(...,"AUTHORITY_EXPIRED",...)`
   并抛 403 `AUTHORITY_EXPIRED`。库中 `blocked_reason='AUTHORITY_EXPIRED'`、`check_version=1`、`last_decision_id` NULL。
   **`retry_not_before` 为空**——这点与“负例要求 `retry_not_before` 非空”的预期不同，是产品既有约束（V216 的
   `CHECK ((blocked_reason='AUTHORITY_UNAVAILABLE')=(retry_not_before IS NOT NULL) OR (...))` 与
   `RunAuthorityStore.recordCheck` 的 `retry = "AUTHORITY_UNAVAILABLE".equals(blocked) ? checked.plus(backoff) : null`）：
   过期是终态，给它重试时间等于错误地承诺“过一会儿就能恢复”。**非空退避由用例 3 覆盖**。
2. **不可达**：客户端指向 `http://127.0.0.1:1` → `BoundedIamHttp` 转 503 `IAM_AUTHORITY_UNAVAILABLE`，
   `RunAuthority` 归一成 `blocked_reason='AUTHORITY_UNAVAILABLE'`、`retry_not_before=checked+30s`、`check_version=1`；
   退避期内第二次调用仍 503 且 `check_version` 保持 1（不写库、不重复探测）。对外异常码是传输层的
   `IAM_AUTHORITY_UNAVAILABLE`（`RunAuthority` 记录的是归一后的原因），用例按此断言。

## 清理策略

`engine.run_authority` 与 `engine.material_scope` 都带 BEFORE UPDATE OR DELETE 触发器
（`RUN_AUTHORITY_IMMUTABLE` / `MATERIAL_SCOPE_IMMUTABLE`），用户口径的“`@AfterEach` 按 run_id 删除即可”
在这里会直接报错。用例在**单事务**内 `set local session_replication_role = replica` 后按
`material_scope → run_authority → run` 顺序删除（`agent_v12` 是库 owner/superuser），失败即回滚。
跑完核对：`engine.run_authority`、`engine.material_scope`、`public_id like 'ex26-live-%'` 均为 0 行。

## 未覆盖项

- **创建侧**：用例用 JDBC 直接写 `run`/`run_authority`/`material_scope`，没有走
  `RunAuthorityStore.createPublished` + `ServiceAuthorization` preHandle + `AuthorityContext.requireBound`。
  因此“发布受理把绑定写对”这一半仍由单元/集成用例覆盖，真实 run 走到恢复仍需完整 publish 流程。
- **入口 HTTP**：只调 `RunAuthority.currentRetained`，没有经过 `RunController` 的恢复端点与认证过滤器。
- **篡改类负例**：origin 不一致、天花板漂移、grant_ref 换绑等由协议级
  `skill-registry/src/test/java/cn/agentplatform/common/authority/Ex26AuthorityChainTest.java` 与
  `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityLocalCurrentPostgresTest.java` 覆盖，
  本次不重复。
- **并发**：未覆盖多实例同时 `recordCheck` 的 CAS 竞争（单进程串行跑三次）。
- **时间竞态**：未覆盖 resume 票据在 authorize 前一刻过期、以及 `retry_not_before` 到点后的恢复成功路径。
- **obligations 更宽的场景**：测试行的 `obligations` 只对齐了 roles/entitlements/dataLabels 的最小集合，
  没有覆盖 provider/export 追加义务数据的场景。
- **部署新鲜度**：本证据要求 18086 由当前源码构建（含 EX-44 origin）。若 CI/离线环境仍用旧 jar，
  用例会在 `portalSession()` 直接失败并指出“运行实例早于 EX-44”，而不是静默通过。
