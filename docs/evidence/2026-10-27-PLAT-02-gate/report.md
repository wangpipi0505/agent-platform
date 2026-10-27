# T02 / PLAT-02 发布门禁分层与显式豁免 — 验证证据

- 卡片：`文档/优化方案/派发卡片-B1-20261027.md`「卡片 4：T02」
- 仓库：`agent-platform`，模块 `app-management`，基线 `HEAD 78fbe27`，分支 `feat/v1.2-implementation`
- 执行时间：2026-10-27 20:09 起（Asia/Shanghai）
- 结论：11 条测试全绿（红阶段为 8 条）；迁移 V105 已在 `agent_platform_v12` 应用；回滚开关可用。

## 1. 门禁语义

发布预检（`AppService.precheck`）原先把「评测通过」写成单一硬门槛：评测未通过即整单不可发布，
运维在监控侧故障或报告缺失时只能改代码或绕过预检。T02 把它拆成三档 + 一个显式豁免出口：

| gate-mode | 评测未通过且无有效豁免 | 评测未通过但有有效豁免 | 评测依赖不可用 |
| --- | --- | --- | --- |
| `BLOCKING`（默认） | `BLOCKED` → 预检 409，不可提交 | `WAIVED` → 可提交 | `ERROR` → 409，**豁免不可绕过** |
| `ADVISORY` | `ADVISORY` → 预检通过并带 warning | `WAIVED` → 可提交 | `ERROR` → 409 |
| `OFF` | `SKIPPED` → 不评估评测结论，也不再咨询评测服务 | — | 不调用评测服务，按 `SKIPPED` 放行 |

不变量：

1. **豁免只对"评测结论不通过"生效**。依赖故障（`/internal/evaluations/accepted` 5xx/超时）是可用性
   事故，不是结论问题；若允许豁免绕过，监控侧一挂就退化成"谁都能发"，故 `ERROR` 在 `BLOCKING`/`ADVISORY`
   下、无论是否有豁免一律 409（fail-closed）。`OFF` 是唯一的例外：它不再调用评测服务，
   因此监控侧故障不会阻断已明确关闭门禁的发布通道。
2. **豁免绑定候选而非应用**：`(tenant_id, app_code, draft_rev, candidate_sha256)` 四元组精确命中。
   用户改一版草稿、换一次模型，`candidateSha256` 变化，旧豁免立即失效，必须重新审批。
3. **豁免有时限**：`expires_at <= approved_at + 24h`，应用层与 DB CHECK 双重约束；发布提交时**再复核一次**
   有效性（`AppService.commit` 内的 `waiverActive`），因为预检报告本身有 15 分钟窗口，
   不能让"报告还没过期"掩盖"豁免已经过期"。
4. **先落库后拦截**：BLOCKING 拦截时 `publish_report` 已经写入（含 `gate_mode`/`waiver_id`/`checks`），
   被挡下的尝试同样留痕，便于审计"谁在什么时候试图绕过门禁"。
5. **只增不改**：V105 给既有报告行回填 `'BLOCKING'`，历史发布结论口径不变。

## 2. 改动清单

| 文件 | 类型 | 规模 | 说明 |
| --- | --- | --- | --- |
| `app-management/src/main/java/cn/agentplatform/app/application/AppService.java` | 改 | 1549 行（+236 / −38） | precheck 门禁分层；豁免 CRUD；commit 豁免复核；`configurePublishGate` 测试钩子 |
| `app-management/src/main/java/cn/agentplatform/app/domain/PublishGate.java` | 新 | 26 行 | 三档枚举 + `parse`（未知值回落 `BLOCKING`） |
| `app-management/src/main/java/cn/agentplatform/app/domain/PublishWaiver.java` | 新 | 98 行 | 豁免 record、`grant` 校验（理由/时限/绑定）、`active`/`status`/`view` |
| `app-management/src/main/java/cn/agentplatform/app/api/PublishWaiverController.java` | 新 | 48 行 | `POST/DELETE /admin/apps/{code}/publish-waivers`，ADMIN 守卫 |
| `app-management/src/main/resources/db/migration/V105__publish_gate.sql` | 新 | 17 行 | 报告加两列 + 豁免表 + 部分索引 |
| `app-management/src/test/java/cn/agentplatform/app/application/PublishGateTest.java` | 新 | 492 行 | 11 条测试（含要求的 6 条失败路径） |
| `docs/evidence/2026-10-27-PLAT-02-gate/` | 新 | 本报告 + surefire 摘录 | — |

未触碰：monitor / skill-registry / engine-runtime / platform-gateway / admin-common、`AppService` 内的
模型冻结与草稿逻辑、任何 `pom.xml`、`application.yml`。

## 3. 迁移 V105

```sql
-- 发布门禁分层与显式豁免：报告记录当次门禁模式与生效豁免，豁免按候选精确绑定并受24小时上限约束。
-- 只增不改：既有报告一律回填 BLOCKING，历史发布结论不因新增列而改变。
ALTER TABLE app.publish_report ADD COLUMN gate_mode varchar(16) NOT NULL DEFAULT 'BLOCKING' CHECK (gate_mode IN ('BLOCKING','ADVISORY','OFF'));
ALTER TABLE app.publish_report ADD COLUMN waiver_id varchar(64);
CREATE TABLE app.publish_waiver (
 id varchar(64) NOT NULL, tenant_id varchar(128) NOT NULL, app_code varchar(64) NOT NULL,
 draft_rev bigint NOT NULL CHECK (draft_rev > 0), candidate_sha256 char(64) NOT NULL,
 reason varchar(512) NOT NULL CHECK (char_length(reason) >= 10),
 approved_by varchar(128) NOT NULL, approved_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL, revoked_at timestamptz, revoked_by varchar(128),
 PRIMARY KEY(tenant_id,id),
 CONSTRAINT publish_waiver_ttl CHECK (expires_at > approved_at AND expires_at <= approved_at + interval '24 hours'),
 FOREIGN KEY(tenant_id,app_code) REFERENCES app.application(tenant_id,code)
);
CREATE INDEX publish_waiver_active ON app.publish_waiver(tenant_id,app_code,draft_rev,candidate_sha256) WHERE revoked_at IS NULL;
```

`app.publish_report` 新增两列：`gate_mode`（`varchar(16)`，非空，默认 `'BLOCKING'`，取值受 CHECK 约束）、
`waiver_id`（`varchar(64)`，可空，无外键——豁免被删时报告仍需保留审计事实）。

状态：已应用到 `agent_platform_v12`（`app.flyway_schema_history` version=105 success）。**文件内容已锁定**，
任何修改都会 checksum 失败；后续调整必须走 V106。

## 4. 测试清单

| 测试 | 类型 | 断言要点 |
| --- | --- | --- |
| `blockingWithoutAcceptedReportRejectsWith409AndKeepsAuditRow` | 失败路径① | 409 `EVAL_GATE_BLOCKED`；报告已落库、`passed=false`、`gate_mode=BLOCKING`、`waiver_id` 为空、`checks` 内含「评测门禁/BLOCKED」 |
| `advisoryWithoutReportPublishesWithWarnings` | 失败路径② | `gate_mode=ADVISORY` 时预检 `passed=true` 且 `warnings` 非空；commit 成功出版本 1 |
| `onlyAdminMayGrantOrRevokeWaiver` | 失败路径③ | EDITOR 调 create/revoke 均 403；豁免表 0 行 |
| `adminWaiverUnblocksBlockingPublishAndIsRecorded` | 失败路径④ | ADMIN 豁免后 BLOCKING 可预检、`waiverId` 回传、报告 `waiver_id` 落库、check 为 `WAIVED`、commit 成功；理由 <10 字 400；TTL >24h 400 |
| `revokedAndExpiredWaiversNoLongerAllowPublishing` | 失败路径⑤ | 撤销后 409；重复撤销幂等（`REVOKED`）；过期豁免（DB 直改时间为 1 小时前）后 409 |
| `waiverBoundToAnotherCandidateNeverApplies` | 失败路径⑥ | 绑定另一 `candidateSha256` 的豁免不生效，预检 409 且报告 `waiver_id` 为空 |
| `offModePublishesWithoutEvaluationAndMarksSkipped` | 分层 | `gate_mode=OFF` 时 check 为 `SKIPPED`、可 commit |
| `offModeDoesNotDependOnEvaluationService` | 分层 | 评测服务 503 时 `OFF` 仍能预检通过并 commit（不咨询评测） |
| `evaluationOutageBlocksEvenInAdvisoryModeAndIgnoresWaivers` | fail-closed | ADVISORY + 已有豁免 + 评测 503 → 409，报告 `gate_mode=ADVISORY`、`waiver_id` 空、check 为 `ERROR` |
| `waiverRevokedAfterPrecheckBlocksCommit` | 提交复核 | 预检用豁免通过后撤销豁免 → commit 409（报告窗口不能掩盖更短的豁免窗口） |
| `rollbackSwitchDisablesWaiversAndDowngradesRelaxedModes` | 回滚 | `waiver-enabled=false` 时 ADVISORY 降级 BLOCKING（报告 `gate_mode=BLOCKING`、409）；豁免接口 403 `PUBLISH_WAIVER_DISABLED` |

测试基座与 `PublishTransactionTest` 一致：真实 PG（`jdbc:postgresql://127.0.0.1:25432/agent_platform_v12`，
用户 `agent_v12`，密码取 `AGENT_DB_PASSWORD`），Flyway 迁移到最新，`AppService` 手工构造，
`HttpServer` 桩 monitor 的 `/internal/evaluations/accepted`（默认 `accepted=false`，可切换为 503 模拟监控侧故障）
与 model 冻结/校验接口。

补充：手工构造的 `AppService` 不经过 Spring，`ServiceTokens.issue` 因部署模式未登记会一律抛
`AUTHORITY_CONTEXT_MISSING`，导致所有 remote 变 502。测试在 `@BeforeAll` 用
`SecurityConfiguration.bindDeployment(Environment)` 走真实部署登记（mode=test、serviceId=app、
server.address=127.0.0.1、test profile），这是既有 HTTP 测试相同的登记路径，不是绕过鉴权。

## 5. 红 → 绿

环境准备（每次执行）：

```bash
cd G:/207/项目/算法重组工具/代码/agent-platform
source .tools/env-load.sh
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -o -pl app-management test \
  -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 \
  -Dtest='PublishGate*Test' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
  -Dsurefire.failIfNoSpecifiedTests=false
```

### 红（2026-10-27T20:18:33，骨架态：旧单硬门禁 + 豁免方法抛 UnsupportedOperationException）

```
[ERROR] Tests run: 8, Failures: 5, Errors: 3, Skipped: 0
[ERROR]   PublishGateTest.advisoryWithoutReportPublishesWithWarnings:171 expected: <true> but was: <false>
[ERROR]   PublishGateTest.blockingWithoutAcceptedReportRejectsWith409AndKeepsAuditRow:155 Expected PlatformException to be thrown, but nothing was thrown
[ERROR]   PublishGateTest.offModePublishesWithoutEvaluationAndMarksSkipped:343 expected: <true> but was: <false>
[ERROR]   PublishGateTest.onlyAdminMayGrantOrRevokeWaiver:199 Unexpected exception type thrown, expected PlatformException but was UnsupportedOperationException
[ERROR]   PublishGateTest.rollbackSwitchDisablesWaiversAndDowngradesRelaxedModes:360 Expected PlatformException to be thrown, but nothing was thrown
[ERROR]   PublishGateTest.adminWaiverUnblocksBlockingPublishAndIsRecorded:226 ? UnsupportedOperation
[ERROR]   PublishGateTest.revokedAndExpiredWaiversNoLongerAllowPublishing:283 ? UnsupportedOperation
[ERROR]   PublishGateTest.waiverBoundToAnotherCandidateNeverApplies:327 ? UnsupportedOperation
```

说明：红阶段 5 条断言失败 + 3 条 Error 已能直接看到"新语义不存在"（不抛 409 / passed 仍为 false /
豁免方法未实现）；同次红跑还暴露了测试基座缺少部署登记（remote 502），该修复只改测试夹具、不改任何断言，
修复后重跑才进入真实分支——因此下表绿跑是"实现补齐"后的结果，而非放宽断言。

### 绿（同一命令，实现补齐后）

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.636 s -- in cn.agentplatform.app.application.PublishGateTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

原始 surefire 摘录见同目录 `surefire-PublishGateTest-green.txt`。

诚实说明：红跑覆盖的是当时已存在的 8 条（含要求的 6 条失败路径）。绿阶段为覆盖两条安全不变量又补了 3 条
（评测 503 时 ADVISORY 仍阻断、OFF 不依赖评测服务、预检后撤销豁免导致 commit 409），这 3 条写在实现之后，
没有对应的红记录；它们断言的行为是对已有实现的加固，不改变前 8 条的红→绿结论。

## 6. 回归（既有同类测试，非模块全量）

```
mvn ... -Dtest='PublishTransactionTest'
[ERROR] Tests run: 13, Failures: 0, Errors: 3, Skipped: 0
[ERROR]   PublishTransactionTest.candidateAndPublishedVersionUseIdenticalFrozenModels:173 ? Platform 依赖服务校验失败: /internal/model-bindings/freeze
[ERROR]   PublishTransactionTest.globalCredentialLookupRequiresDedicatedGatewayIdentity:347 ? Platform 缺少当前授权上下文
[ERROR]   PublishTransactionTest.integrationSecretOnlyShownOnceAndRevocationPersists:315 ? Platform 缺少当前授权上下文
```

三个 Error 全部落在本卡未修改的代码路径：前一个是模型冻结调用在"未登记部署模式"夹具下的 remote 失败，
后两个是 `Actor` 授权上下文缺失，均属卡片声明的 app-management 既有 Y-4 测试基座债务（20 个既有错误），
本卡按派单要求不修、不动；13 条中 10 条通过，提交事务主路径未被本次改动破坏。

## 7. 回滚开关

配置项：`agent.publish.waiver-enabled`（默认 `true`），`agent.publish.gate-mode`（默认 `BLOCKING`）。
两者用 `@Value` 绑定，**不新增配置类、不改 `application.yml`**，部署侧可用环境变量
`AGENT_PUBLISH_WAIVER_ENABLED=false` / `AGENT_PUBLISH_GATE_MODE=ADVISORY` 覆盖。

| 开关 | 行为 |
| --- | --- |
| `waiver-enabled=true`（默认） | 豁免接口可用；ADVISORY/OFF 按配置生效 |
| `waiver-enabled=false` | `POST/DELETE /admin/apps/{code}/publish-waivers` → 403 `PUBLISH_WAIVER_DISABLED`；`publishGate()` 强制返回 `BLOCKING`（ADVISORY/OFF 配置被忽略）；预检不再匹配历史豁免，报告 `gate_mode` 记 `BLOCKING` |
| 已存在豁免，之后关闭开关 | 预检不再采信（`waiver_id` 为空）；`commit` 对历史报告的 `waiver_id` 复核不变，撤销/过期仍会 409 |

恢复窗口：关闭开关只影响**之后**的预检与提交判定，不删除既有豁免行；重新打开后，
未撤未过期的豁免立即恢复生效。回滚不需要迁移回退（新列有新默认值，旧代码读不到也会忽略）。

## 8. 未覆盖项 / 端到端前置

1. **HTTP 层未验证**：本次只做方法级调用（控制器方法直调 + `requireRole("ADMIN")` 断言），
   未起 Spring 上下文验证路由注册、JWT 过滤器、`/admin/**` 放行规则。端到端需：启动 admin 服务 +
   gateway/monitor 依赖，用 ADMIN/EDITOR 两个 JWT 分别打 `POST/DELETE /admin/apps/{code}/publish-waivers`。
2. **未跑 `@SpringBootTest`**：模块全量有既有 Y-4 债务（派单要求不跑），因此 `@Value` 注入、
   控制器 Bean 装配只做了编译级保证。
3. **并发竞态未测**：ADMIN 撤销豁免与发布提交并发时的窗口。当前实现为"提交事务内复核"，最坏结果是
   409 让用户重试（不会误放行）；无锁、无版本号，未做压测。
4. **缺列表查询**：只有 create/revoke，没有 `GET /publish-waivers` 列表（控制台展示需要后续补）。
5. **审计下游未验证**：`PUBLISH_WAIVER_GRANT` / `PUBLISH_WAIVER_REVOKE` 已写 `app.lifecycle_log`，
   但审计报表/告警消费方未接入验证。
6. **OFF 模式的边界**：`gate-mode=OFF` 仅关闭评测门禁（不再调用 monitor），结构/参数/引用/模型冻结等
   其他校验仍全部执行；若需要"完全跳过预检"应另开开关，本卡未做。
7. **ADVISORY 文案**：warning 只有统一文案，未区分"无报告/报告不通过/有回归"。

## 9. 复现注意

- V105 已应用到共享测试库，重跑测试无需重复迁移；若要重置，需删除 `app.flyway_schema_history` 中
  version=105 的行并 drop `app.publish_waiver` 与两列后再跑（会破坏其他并发任务，勿在共享库操作）。
- 测试依赖 `.tools/env-load.sh` 导出的 `AGENT_DB_PASSWORD`；缺少时整类跳过（`@EnabledIfEnvironmentVariable`）。
- 本卡全程未执行 `git add` / `git commit`；工作区中 `docs/evidence/acceptance-audit-20261026/`、
  `docs/evidence/batch-1-usability-20261026.md`、`output/` 属其他并发任务产物，未触碰。
