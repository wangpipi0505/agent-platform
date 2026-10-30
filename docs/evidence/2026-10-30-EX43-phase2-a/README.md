# EX-43 第二阶段 A 组：app-management / skill-registry / engine-runtime 装配入站信任链

日期：2026-10-30
范围：把已入库的 admin-common IAM 入站基座（`AuthorityDecoders.iam`、`IamStartupGate`、
`CoverageGate`、`SecurityConfiguration.serviceTrustChain`）装配到三个业务服务。
未改 `admin-common/`、`authority-service/`、`platform-gateway/`、`model-gateway/`、`monitor/`、
`contracts/`、`scripts/`、`pom.xml`、迁移 SQL 与 `agent-service-authority.json`；未提交、未启动任何服务。

## 0. 环境与基线

- 分支 `feat/v1.2-implementation`；任务书写的 HEAD 是 `0acfa16d`，实际操作时工作树 HEAD 为
  `194bf99c`（B 组 gateway IAM 提交在此期间进入分支），本组改动全部保持未提交状态。
- 构建：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`；
  `mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl <module> test -Dtest='<class>'`。
- 本机无 `AGENT_DB_PASSWORD`，既有直连 PG 的测试在基线上就是红的，故回归作用域只取
  "基线可跑的非 DB 安全类测试"，并在 HEAD 干净工作树（`git worktree`）与当前工作树各跑一次同名对比。

## 1. 装配内容

每个服务新增一个只在 `agent.security.mode=iam` 生效的配置类
`<module>/src/main/java/cn/agentplatform/<svc>/infra/ServiceTrustChainConfiguration.java`：

| 事项 | 实现 |
| --- | --- |
| 链 1 | `@Bean ServiceTrustChain serviceTrustChain(HttpSecurity, JwtDecoder, ServiceAuthorization)` → `SecurityConfiguration.serviceTrustChain(...)`（`ServiceTrustChain.ORDER=-100` + `securityMatcher("/internal/**")`） |
| ServiceAuthorization | `new ServiceAuthorization("<svc>", new ServiceRoutePolicy(), currentAuthorityClient)`；client 优先复用上下文中已有的 `CurrentAuthorityClient`（skill 的 `ServiceTicketConfiguration`），缺失时构造 `HttpCurrentAuthorityClient`（`@ConditionalOnMissingBean`） |
| 拦截器 | 配置类实现 `WebMvcConfigurer`，`registry.addInterceptor(receiver.getObject().mappedInterceptor())`；必须是 include/exclude 均为 null 的 `MappedInterceptor`，否则 `RouteCoverageVerifier` 报 `ROUTE_GUARD_MISSING` |
| 解码器 | 不新建：复用公共 `SecurityConfiguration.jwtDecoder(...)` 的 iam 分支（`AuthorityDecoders.iam(deployment)`）；dev-hmac 分支保持原样（HS256 + audience 校验） |
| 启动门 | 不重复：公共 `authorityModeGuard` 已在 iam 下执行 `IamStartupGate.requireLocalClientSecret`，且 `ServiceTokens.registerDeployment` 只在那之后执行一次；服务侧不再调用、不重复注册 |
| 配置 | 各服务 `application.yml` 的 `agent.security` 增加 `service-id`（`app`/`skill`/`engine`）与注释：iam 客户端凭据只允许 `env:AGENT_SECRET_IAM_<SERVICE>_CLIENT` 引用，不写死密钥 |

`dev-hmac` 下配置类被 `@ConditionalOnProperty(name="agent.security.mode", havingValue="iam")` 排除，
服务保持原单链行为（这正是"dev-hmac 分支逐字保留"的保证），回滚只需切一个 property。

## 2. 测试命令与结果（原样输出）

三个模块命令相同（`<module>` 分别为 `app-management`、`skill-registry`、`engine-runtime`）：

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl <module> test -Dtest='IamInboundTrustChainTest'
```

```
########## app-management
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.820 s -- in cn.agentplatform.app.infra.IamInboundTrustChainTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
########## skill-registry
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 19.23 s -- in cn.agentplatform.skill.infra.IamInboundTrustChainTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
########## engine-runtime
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.488 s -- in cn.agentplatform.engine.infra.IamInboundTrustChainTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

11 条用例覆盖：`/internal/**` 无票 401（`SYS-AUTH-401`）、坏票 401（乱串/过期/换 aud/换 iss）、
`/auth/dev-token` 404、`/actuator/health` 200、目录无行 403（且 `authorityCalls==0`，证明确实"未调权先拒"）、
workload 票打 delegated-only 模板 403（模式不符，`authorityCalls==0`）、授权服务返回 500 → 503
（`IAM_AUTHORITY_UNAVAILABLE`，`authorityCalls==1`）、授权服务超时 → 503（`authorityCalls==1`）、
MVC 映射上确有该 `MappedInterceptor` 且 `CoverageGate.verify` 通过、`CoverageGate` 在 iam 抛
`ROUTE_COVERAGE_MISSING`/dev-hmac 仅告警、`ApplicationContextRunner` 证明 dev-hmac 不装配 iam 链。

测试夹具说明：`LoopbackAuthority`（test-jar）只用于签发票据/提供 JWKS；决策端点被替换成同进程
`HttpServer` 桩（`AtomicReference` 切 500/慢响应），故"授权服务不可达/超时"是确定性的、不依赖外部进程。

## 3. dev-hmac 回归（改动前后同名坏类对比）

```
mvn -B -ntp -o -Dmaven.repo.local=<绝对路径>/.tools/m2 -pl <module> test -Dtest='<既有安全类>'
```
在 HEAD 干净工作树（before，无本组改动）与当前工作树（after）各跑一次：

| 模块 | before 汇总 | after 汇总 | 结论 |
| --- | --- | --- | --- |
| app-management | `Tests run: 21, Failures: 0, Errors: 0, Skipped: 19` | 同左 | 逐类一致，零新增失败 |
| skill-registry | `Tests run: 29, Failures: 0, Errors: 0, Skipped: 4` | 同左 | 逐类一致，零新增失败 |
| engine-runtime | `Tests run: 9, Failures: 1, Errors: 0, Skipped: 6` | 同左 | 唯一坏类 `RunAuthorityDatabasePreflightTest.fixedDatabaseHistoryIsInspectedBeforeMigrationAllocation:9` 前后同在（本机无 PG，基线既有） |

作用域：app `PolicyResolverSecurityTest`/`EvaluationEnvironmentGateTest`/`CapabilityPackageBindingTest`/
`CapabilityPackageApplicationsTest`/`PublishGateTest`；skill `SkillClientsErrorTest`/
`ServiceTicketAuthorityTargetTest`/`AuthSchemeOutboundTest`/`HttpAuthErrorBoundaryTest`/
`SkillAuthSchemeRegistrationTest`/`Ex26AuthorityChainTest`/`ServiceTicketAuthorityWiringTest`；
engine `ToolInterceptorChainTest`/`CrossServiceTraceTest`/`CapabilityRunSnapshotTest`/
`RunAuthorityDatabasePreflightTest`/`EngineProcessCancellationTest`。

## 4. 红证据（每条变异 → 报错原文 → 已还原）

变异方式：临时改 `ServiceTrustChainConfiguration.java` → 跑该模块 `IamInboundTrustChainTest` → 立即
`cp` 备份还原（还原后用 `grep -c MUTATION` = 0 与全绿重跑双重确认）。

| 变异 | 结果（原样） | 还原 |
| --- | --- | --- |
| M1 去掉链 1 `@Bean`（三服务各一次） | app/skill/engine 均 `Tests run: 11, Failures: 2`：`anAuthorityDecisionOutageStays503:203`、`anAuthorityTimeoutStays503:214`，`org.opentest4j.AssertionFailedError: expected: 503 / but was: 403`（无链 1 后 `/internal/**` 落入链 2 的 denyAll） | 已还原 |
| M2 不注册 guard 拦截器（三服务各一次） | app `Failures: 5`（`:183`/`:193`/`:203`/`:214`/`:231`），skill `Failures: 5`（同左），engine `Failures: 3`（`:203`/`:214`/`:231`）；`expected: 403 but was: 200`、`expected: 503 but was: 200`——handler 被直接执行、目录/模式校验与调权全部消失；`:231` 报 `Expecting any element of: [] to satisfy the given assertions requirements but none did:` | 已还原 |
| M3 去掉 `@ConditionalOnProperty`（app） | `devHmacDeploymentsDoNotAssembleTheIamChain` 失败：`UnsatisfiedDependencyException: Error creating bean with name 'serviceAuthorization' ... NoSuchBeanDefinitionException: No qualifying bean of type 'cn.agentplatform.common.authority.AuthorityProperties' available` | 已还原 |

## 5. 目录缺口（`agent-service-authority.json` 禁改，仅报告）

`CoverageGate.verify` 把 `mode=disabled` 的目录行也视为缺失，故 iam 下这些服务会在启动自检阶段
抛 `IllegalStateException: ROUTE_COVERAGE_MISSING`：

| 服务 | 缺口行（`/internal/**`） |
| --- | --- |
| app-management | 1 条：`GET /internal/integration/{code}/{biz}`（目录行 `disabled`，`IntegrationController.java:52` 确有 handler）。另 `POST /internal/iam/integration-policy/resolve` 只在 `agent.policy-resolver.enabled=true` 时注册，目录无行（默认不开时不是缺口，开启即缺口） |
| skill-registry | 5 条，全 `disabled`：`GET /internal/skill-facts/{callId}/reconciliation-proofs/{attemptId}`、`GET /internal/statistics`、`POST /internal/skill-facts/{callId}/reconcile`、`POST /internal/skill-facts/{callId}/retry`、`POST /internal/skill-jobs/{callId}/callback` |
| engine-runtime | 2 条，全 `disabled`：`POST /internal/tool-calls/register`、`GET /internal/operations/overview` |

## 6. 偏差与未覆盖

1. **键名偏差**：任务书写 `agent.authority.service-id`，实际 `@ConfigurationProperties(prefix="agent.security")`
   且 `ignoreUnknownFields=false`，写 `agent.authority.*` 会启动失败（`The elements [agent.authority...] were left unbound`）。
   yml 因此用 `agent.security.service-id`；iam 的 authority 配置（issuer/jwks/token/decision/client）应由部署环境提供，
   未写进 yml（避免写死固定 URL 冒充真实部署）。
2. **启动门未在服务侧重复调用**：`IamStartupGate.requireLocalClientSecret` 与 `ServiceTokens.registerDeployment`
   已由公共 `authorityModeGuard` 在 iam 下唯一执行；服务侧重复调用会双重注册/掩盖配置错误，故服务 main 代码
   不含任何 `IAM_TRUST_CHAIN_UNAVAILABLE` 自抛点。
   **唯一改动既有断言**：`engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialHttpTest.java:498`
   原本断言 refresh 抛 `IAM_TRUST_CHAIN_UNAVAILABLE`（旧基座的自抛码）。该码已不存在，现由共享启动门抛
   `IAM_CLIENT_SECRET_MISSING`；临时探针（`AnnotationConfigApplicationContext` + 同一组 iam 属性）实测原文：
   `IllegalStateException -> IAM_CLIENT_SECRET_MISSING: env:AGENT_SECRET_IAM_ENGINE_CLIENT`，
   断言据此改为 `IAM_CLIENT_SECRET_MISSING` 并加注释说明来源，测试意图（iam 默认部署在监听器之前拒绝启动）不变。
   该测试类在本机因 PG 不可达（`CannotGetJdbcConnection`）基线即红，无法本地跑绿，故只对该断言做了等价替换。
3. **iam 下真实启动仍被目录缺口阻断**：三个服务都属"装配完成 + 单测覆盖，但 iam 真实启动会因
   `ROUTE_COVERAGE_MISSING` 失败"，缺口清单见 §5。补行属于目录维护（本任务禁改）。
4. **无 200 端到端正例**：本组用例只覆盖 401/403/503/404 与 dev-hmac 回归；skill/engine 目录里没有
   `caller=gateway` 的 `/internal/**` 行（app 仅 `GET /internal/apps/{code}/published-metadata` 一条且需
   metadata-selector 声明），端到端 200 需要额外的 forward/resume 两步换票，超出本组范围。
5. **未启动任何服务**（任务约束），iam 启动验证只到"上下文级 + `CoverageGate` 单测"；真实部署还需
   `AGENT_SECRET_IAM_<SERVICE>_CLIENT`、authority-service 可达与目录行补齐。
6. 测试环境注意点（已写入测试注释/本文件供后来者）：`LoopbackAuthority` 的固定时钟是
   2026-10-20，外层 `AuthorityDecoders.iam` 用系统时钟校验 `exp`，夹具必须先
   `advanceClock` 到墙钟；另外 MVC 拦截器抛出的 `PlatformException` 会被
   `FrameworkServlet` 包成 `ServletException`，必须有 `PlatformErrors`（`@RestControllerAdvice`）
   在场才能得到正确 403/503，测试上下文已 `@Import(PlatformErrors.class)`。
7. 改动面：新增 3 个配置类、3 个测试类与 3 处 yml 段落（+16 行），另替换 1 处既有断言（见 §6.2）；其余既有文件零改动。
