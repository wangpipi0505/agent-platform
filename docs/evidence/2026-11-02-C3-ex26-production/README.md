# C3 / EX-26：IAM 受理授权续期（resume）落地证据

日期：2026-11-02
范围：`authority-service` 新增 `resume` 操作 + `engine-runtime` 在生产容器里装配 `RunAuthority`。

## 1. 问题

1. `RunService` 的 5 参 `@Autowired` 构造调 `this(s, c, f, a, scheduler, null)`，`runAuthority` 与
   `admissionStore` 永远是 null，`admissionProof(...)` 第一行就抛 403 `IAM_ADMISSION_UNAVAILABLE`
   ——IAM 模式下所有候选受理都是 403，运行压根起不来。
2. 可用组件（`RunAuthority`、`RunAuthorityStore`）已经存在且有测试，只是没人装配。
3. `RunAuthority.currentRetained` 会调 `HttpCurrentAuthorityClient.resume(...)`，而 authority-service
   的 `AuthorityController.TICKET_OPERATIONS` 只认 `client`，`DECISION_OPERATIONS` 没有 `resume`：
   tokenUri 直接用 403 `tokenUri 不接受 operation=resume` 拒掉。

## 2. 改法

### A. authority-service：`resume` 用已持久化的原委托换短票据

- `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java`（+4 行）：
  `issueTicket` 在 `exchange` 分流之后、`TICKET_OPERATIONS` 白名单之前加
  `if (DelegatedAuthority.RESUME.equals(request.operation())) return delegated.resume(clientId, request);`。
  客户端 `resume` 走 tokenUri（`query(..., token=true)`），这里必须与 `forward`/`exchange` 同级分流，
  否则落进 `client` 白名单检查。
- `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java`（+148/-2 行）：
  - 新常量 `RESUME = "resume"`；
  - 新方法 `public Map<String, Object> resume(String clientId, AuthorityRequest request)`：
    校验顺序 = `grant_ref` 非空 → 找登记（caller/target 一致）→ 资源 root 相等 → 未撤销 → 未过期 →
    action 在范围内；然后**复用** `operation(...)`、`ticket(...)`、`authorityClaims(...)` 造票，
    不复制粘贴签发逻辑；最后 `Map.of("assertion", ..., "ticket", ...)`，审计一条 `operation=resume outcome=permit`。
  - 新私有 `resumeGrant(...)` 只做校验、返回原决策；
  - `operation(...)` 加 10 参重载（sourceKind/originKind 可传），原 8 参委托到 `"PORTAL"`，既有调用点零改动；
    resume 传 `source_kind="DELEGATED"`、`origin_kind` 沿用原 operation 的 `PORTAL`
    —— 与客户端 `ServiceRoutePolicy.localRunAdvance("engine", call, "delegated", "DELEGATED", "PORTAL")` 一致。
  - 新票复用原委托的 `grant_ref`、`initial_ceiling`（客户端 `verifyCeiling` 会重算 `ceiling_sha256` 比对）、
    `grant_expires_at`、actor；`exp = min(iat + ticketSeconds, grantExpiresAt)`，不延长原 grant。
  - 过期返回 **403** `AUTHORITY_EXPIRED`（reason `RESUME_GRANT_EXPIRED`），与卡片要求一致；
    见第 4 节与仓库 `AuthorityException.expired`（409）的差异说明。
- `authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java`（+25 行）：
  新 `public List<Decision> findByGrantRef(String grantRef)`，线性扫描 `entries`，**不过滤过期**
  （过期与不存在必须可区分），不建索引（容量上界 256，注释说明为什么线性可接受）。
- 新测试 `authority-service/src/test/java/cn/agentplatform/common/authority/DelegatedResumeContractTest.java`（243 行，5 例）：
  ① 正常换票（真实 `HttpCurrentAuthorityClient` 走 `POST /oauth/token`）；
  ② 原委托过期 → 403 `AUTHORITY_EXPIRED`；
  ③ resource 越权（换到别的 app-version root）→ 403 `SYS-AUTH-403` + `reason=RESUME_ESCALATION`；
  ④ 撤销后 → 403 + `reason=RESUME_REVOKED`；
  ⑤ grant_ref 未签发过 → 403 + `reason=RESUME_GRANT_UNKNOWN`。

### B. engine-runtime：只在 `agent.security.mode=iam` 装配

- 新 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityConfiguration.java`（71 行）：
  `@Configuration(proxyBeanMethods=false)` + `@ConditionalOnProperty(name="agent.security.mode",havingValue="iam")`；
  - `RunAuthorityStore runAuthorityStore(DataSource, ObjectProvider<Clock>)`，`@ConditionalOnMissingBean`；
  - `RunAuthority runAuthority(RunAuthorityStore, ObjectProvider<HttpCurrentAuthorityClient>, ObjectProvider<Clock>, Environment)`：
    authority 客户端缺失时 `IllegalStateException("IAM_AUTHORITY_CLIENT_REQUIRED")`——宁可启动失败，
    也不装配一个永远 403 的"已装配"状态；
  - backoff 读 `agent.engine.run-authority-backoff-seconds`，默认 30，必须落在 1..60，越界启动失败
    `RUN_AUTHORITY_BACKOFF_OUT_OF_RANGE`。
  - `HttpCurrentAuthorityClient` 由 `ServiceTrustChainConfiguration`（101-108 行）在本模式内声明，
    这里只 `ObjectProvider` 复用，不重复声明（重复会导致注入歧义）。
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java`（+23/-2 行）：
  `runAuthority` / `admissionStore` 去掉 `final`，新增两个 `@Autowired(required=false)` setter，
  沿用 `contextPacks` / `runQuota` 的既有可选注入惯例；public 构造签名与语义不变，
  其余 10 处 `new RunService(...)` 夹具不受影响。
- 新测试 `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunAuthorityAssemblyTest.java`（95 行，4 例）：
  ① `agent.security.mode=iam` 时 `RunService` 的两个字段与容器 bean **同一实例**；
  ② 非 iam 模式两个 bean 都不存在、字段为 null（旧装配不变、默认拒绝）；
  ③ backoff=61 启动失败；
  ④ 容器里没有 authority 客户端时启动失败。

## 3. 红绿原文

环境：`JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`，
`source .tools/env-load.sh`，先 `mvn -pl admin-common install -DskipTests -q`。
注：日志里中文提示因控制台编码显示为乱码（源文件为 UTF-8，不影响断言与退出码）。

### A. 红（先证明缺分流就必 403）

命令：

```
mvn -pl authority-service test -Dtest=DelegatedResumeContractTest
```

（此时 `AuthorityController.issueTicket` 的 resume 分流被临时注释，测试编译已通过。）

```
[ERROR] Tests run: 5, Failures: 4, Errors: 1, Skipped: 0, Time elapsed: 2.731 s <<< FAILURE! -- in cn.agentplatform.common.authority.DelegatedResumeContractTest
```

审计原文（5 条用例都是同一条拒绝；`reason=` 后的中文在控制台呈乱码）：

```
authority.audit endpoint=token outcome=deny client_id=engine operation=resume target=engine action=run.advance status=403 code=SYS-AUTH-403 reason=tokenUri 不接受 operation=resume
```

失败摘要：

```
[ERROR]   DelegatedResumeContractTest.resumeRejectsAGrantReferenceThatWasNeverIssuedHere:163
[ERROR]   DelegatedResumeContractTest.resumeRejectsAGrantThatHasExpired:116
[ERROR]   DelegatedResumeContractTest.resumeRejectsAResourceOutsideTheGrantedRoot:133
[ERROR]   DelegatedResumeContractTest.resumeRejectsARevokedGrant:151
[ERROR]   DelegatedResumeContractTest.resumeExchangesTheStoredGrantForANewDelegatedTicket ? PlatformException: 平台侧授权服务拒绝了该调用（403）
[ERROR] Tests run: 5, Failures: 4, Errors: 1, Skipped: 0
```

### A. 绿（恢复分流后）

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.756 s -- in cn.agentplatform.common.authority.DelegatedResumeContractTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### B. 红（先证明没有 setter 注入时字段仍为 null）

命令：

```
mvn -pl engine-runtime test -Dtest=RunAuthorityAssemblyTest
```

（此时两个新 setter 的方法体被临时清空。）

```
[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.615 s <<< FAILURE! -- in cn.agentplatform.engine.application.RunAuthorityAssemblyTest
[ERROR]   RunAuthorityAssemblyTest.iamModeWiresTheAdmissionDependenciesIntoTheProductionRunService:43->lambda$iamModeWiresTheAdmissionDependenciesIntoTheProductionRunService$2:50
[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

### B. 绿（恢复注入后）

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.668 s -- in cn.agentplatform.engine.application.RunAuthorityAssemblyTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 4. 偏差与未覆盖

1. **过期状态码**：卡片要求 403 `AUTHORITY_EXPIRED`，仓库既有 `AuthorityException.expired(...)` 是 **409**
   （EX-42 为 forward 定的"过期可区分"）。本卡按卡片实现 403；副作用是平台侧 `BoundedIamHttp` 只看状态码，
   在引擎本地没先发现过期时会把审计 reason 记成 `AUTHORITY_DENIED` 而不是 `AUTHORITY_EXPIRED`
   （`RunAuthority` 会先按 `binding.delegation().expiresAt()` 本地判过期，因此绝大多数情况仍是 `AUTHORITY_EXPIRED`）。
2. **resume 的登记源是内存**：`DecisionStore` 仍是单实例内存（EX-46 明确迁 PG），所以 authority-service
   重启后原委托丢失，resume 只能回 403 `RESUME_GRANT_UNKNOWN`；本卡没有把 grant 落到 PG，也没有改 EX-46 的边界。
3. **撤销语义是推断的**：仓库没有撤销 API/字段（`grep revok|撤销|REVOK` 无匹配）。这里把
   `DecisionStore.Decision.consumedAt != null`（一次性消费标记，`consume(id, consumer)`）当作"不再可用"，
   被 sweep/容量淘汰后查不到则落到 `RESUME_GRANT_UNKNOWN`。产品若日后定义真正的撤销，需要替换这一处判断。
4. **`HttpCurrentAuthorityClient` 已有 bean**：`engine-runtime` 在 iam 模式下由
   `ServiceTrustChainConfiguration.serviceCurrentAuthorityClient`（101-108 行，返回类型即该类）声明；
   新配置类用 `ObjectProvider` 复用，未重复声明。
5. **没有 `(engine, engine, run.advance, delegated)` 路由行**：`admin-common/src/main/resources/agent-service-authority.json`
   里没有这一条（grep `run.advance` 全无），所以服务端 action 校验用 `ServiceRoutePolicy.localRunAdvance`
   这条本地判据，不查 `routes.permits`。若日后在目录中登记该行，可改为目录校验。
6. **候选过滤比卡片更紧**：`resumeGrant` 要求换票方等于原决策的 `caller()`，而卡片只要求
   target/action/resource 在 ceiling 内。这是为了防止别的服务拿同一个 `grant_ref` 换票。
7. **装配测试不需要 PG 门控**：`RunAuthorityStore` 构造只 `new JdbcTemplate(source)`，不建连接，
   所以 `RunAuthorityAssemblyTest` 用 mock `DataSource` 即可，**未加** `@EnabledIfEnvironmentVariable(AGENT_DB_PASSWORD)`，
   不设该变量也**不会 Skip**（也不需要）。真实 SQL 路径仍由 `RunAuthorityPersistencePostgresTest` 覆盖（本卡未跑）。
8. **测试文件路径**：卡片建议 `authority-service/src/test/java/cn/agentplatform/authority/...`，实际放在
   `cn/agentplatform/common/authority/`——`WorkloadCredentialResolver` 的 3 参构造是包内可见，
   真实 `HttpCurrentAuthorityClient` 只能从同包测试构造。
9. **两次临时红都是 TEMP-RED**：分别注释了 Controller 的 resume 分流、RunService 的 setter 赋值，
   取完证已恢复并复跑绿；最终代码里 `grep TEMP-RED` 无匹配。
10. **未跑模块全量测试**（卡片约束）；未跑端到端引擎→authority PG 链路；resume 的并发幂等未单独压测
    （每次 resume 都生成新 `jti`/`operation_id` 且不写 store，本身无共享写）。

## 5. 证据文件

- `authority-resume-red.log` / `authority-resume-green.log`
- `engine-assembly-red.log` / `engine-assembly-green.log`

仓库 `.gitignore` 第 7 行是 `**/*.log`，这些文件不入库；因此本 README 已贴出判定用的原文
（`Tests run:`、审计行、失败摘要）。需要完整日志时用上面的命令重跑即可复现。
