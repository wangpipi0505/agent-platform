# EX-43 第一阶段（admin-common）：IAM 入站装配与启动门

- 日期：2026-10-30
- 范围：仅 `admin-common`（网关/app/skill/engine 的装配属第二阶段，Q5）
- 设计依据：`文档/设计/EX-43-IAM入站装配与启动门-设计-20261030.md`（位于仓库上一级，§3.1/§3.2/§3.3/§5）
- 已确认决策（用户 2026-10-30 "按建议"）：Q1 `/auth/dev-token` 在 iam 下 404；Q2 仅 `/actuator/health`、`/actuator/health/**` 放开；Q3 目录自检 iam 阻断、dev 仅告警；Q4 SSE 票只认 `Authorization` 头；Q5 分两批，本阶段只做公共底座。
- 本阶段未改 `pom.xml`、未新增依赖、未启动任何服务（18080–18086 未触碰）、未 `git commit/push`。

## 1. 变更清单

| 文件 | 行数 | 类型 |
| --- | --- | --- |
| `admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java` | 367 | 修改 |
| `admin-common/src/main/java/cn/agentplatform/common/authority/RouteCoverageVerifier.java` | 255 | 修改（抽公共判定） |
| `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityDecoders.java` | 98 | 新增 |
| `admin-common/src/main/java/cn/agentplatform/common/authority/IamStartupGate.java` | 37 | 新增 |
| `admin-common/src/main/java/cn/agentplatform/common/authority/CoverageGate.java` | 60 | 新增 |
| `admin-common/src/main/java/cn/agentplatform/common/authority/ServiceTrustChain.java` | 43 | 新增 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/IamInboundGateTest.java` | 76 | 新增 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/IamInboundChainTest.java` | 295 | 新增 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/IamInboundDecoderTest.java` | 89 | 新增 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/IamTestEnvironments.java` | 60 | 新增 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/AuthorityConfigurationTest.java` | 232 | 修改（iam 期望码随设计更新） |
| `admin-common/src/test/java/cn/agentplatform/common/authority/ServiceTokensDeploymentModeTest.java` | 203 | 修改（同上） |

`git diff --stat`（不含新增文件）：`SecurityConfiguration.java +179/-...`、`RouteCoverageVerifier.java` 294 行改动、两个既有测试共 18 行改动。

## 2. 实现要点

### 2.1 解码器工厂（§3.4）

`AuthorityDecoders.iam(AuthorityProperties)`：

- authority 域：`NimbusJwtDecoder.withJwkSetUri(authority.jwks-uri).jwsAlgorithm(RS256)`，`jwtProcessorCustomizer` 把 JOSE `typ` 校验设为 `at+jwt`（Spring 默认只允许 `JWT`，与 EX-42 的 `at+jwt` 票据不兼容），校验器 = `JwtValidators.createDefaultWithIssuer(issuer)` + 自定义 validator：`token_use == "service"` 且 `aud` 含 `urn:agent-platform:service:<self>`。
- portal 域（仅 `portal.enabled()`，即 gateway）：`token_use == "user"` 且 `aud` 含 `portal.audience`（`portal-gateway`）。
- `IssuerRoutingDecoder` 只按**未验签的 `iss`** 选择已固定配置的域；未知 `iss` 直接 `BadJwtException`，不碰任何 key set（防止伪造票据选择 URL/探测授权服务）。
- 构造期不取 JWKS：`RemoteJWKSet` 首次验签才拉取；失败经 `BoundedIamHttp` → 503 `IAM_AUTHORITY_UNAVAILABLE`（`IamInboundChainTest#authorityOutageStays503AndIsNotReportedAs401Or403`）。
- `dev-hmac`/`test` 走 `jwtDecoder(...)` 原有 HS256 分支，逐字未动。

### 2.2 启动门（§3.2）

`authorityModeGuard`（BFPP，`Order.HIGHEST`）现在：

1. `bindValidatedDeployment(environment)`：Binder + `validateDeployment(...)`；iam 下若失败原因是 config 类（`IAM_CONFIG_REQUIRED`/`IAM_CLIENT_SERVICE_MISMATCH`/`IAM_SECRET_REF_INVALID`/`IAM_ALGORITHM_INVALID`/`IAM_AUDIENCE_INVALID`/`IAM_URI_INVALID`/`IAM_BUDGET_INVALID`）包装为 `IAM_CONFIG_INCOMPLETE: <detail>`；
2. iam 下 `IamStartupGate.requireLocalClientSecret(...)`：`client-secret-ref` 必须是 `env:<NAME>` 且本机 `Environment`/`System.getenv` 能取到非空值，否则 `IAM_CLIENT_SECRET_MISSING: <ref>`；**不发网络请求**；
3. 只有通过后才 `ServiceTokens.registerDeployment(mode)` 并注册 `authorityProperties` 单例（iam 不会在门失败前把 JVM 锁死）。

目录自检（§3.2 第 4 步）由新增 `routeCoverageGate`（`SmartInitializingSingleton`）承担，因为 BFPP 阶段 `RequestMappingHandlerMapping` 尚未实例化。`CoverageGate.verify(...)` 复用 `RouteCoverageVerifier.handlerRoutes(...)` / `catalogRow(...)`：iam 下任一 `/internal/**` handler 无可用 catalog 行（无行或 `mode=disabled`）→ `IllegalStateException("ROUTE_COVERAGE_MISSING: <service> <method> <template> (<reason>)")`；dev-hmac/test 只 `log.warn`。`SmartInitializingSingleton` 回调发生在 `finishBeanFactoryInitialization`，早于 `WebServerStartStopLifecycle`，因此 iam 失败时监听端口不会打开。

### 2.3 双链支撑（§3.1）

- `SecurityConfiguration.serviceTrustChain(http, decoder, receiver)`：先 `http.securityMatcher("/internal/**")`，再委托现有 `serviceSecurity(...)`，返回 `ServiceTrustChain`（`Ordered`，`ORDER=-100`）。服务只要把返回值声明成 `SecurityFilterChain` bean，链 1 就排在链 2 之前，与 bean 声明顺序无关。
- `security(...)` 在 `trusted`（iam）时对 `/internal/**` 执行 `denyAll()`；dev-hmac 保留原 `!trusted && ROLE_SERVICE` 语义。
- `security(...)` 的 `permitAll` 仅保留 health 两项与原有 `/auth/dev-token`、`/embed/ticket`、`/assistant.js`（其余 actuator 端点走 `anyRequest` → 401）。

### 2.4 失败语义（§3.3）

- 无票/坏票/过期/aud 不符 → `SYS-AUTH-401`（`authError`，链 1、链 2 一致）；
- 有效票但无 catalog 行/mode 不符/resource 不符 → `SYS-AUTH-403`（`ServiceAuthorization` 既有 `SYS-AUTH-403`）；
- 授权服务不可达/JWKS 失败/超时 → `PlatformException(503, IAM_AUTHORITY_UNAVAILABLE)` 经 `serviceSecurity` 的 `PlatformException` 捕获过滤器原样 503，**不折叠成 401/403**；
- 启动期 config/目录问题 → 启动失败码（`IAM_CONFIG_INCOMPLETE` / `IAM_CLIENT_SECRET_MISSING` / `ROUTE_COVERAGE_MISSING`）。

### 2.5 iam 下 `/auth/dev-token` = 404（Q1）

`security(...)` 在 `trusted` 时注册 `OncePerRequestFilter`（位于 `BearerTokenAuthenticationFilter` 之前）：命中 `/auth/dev-token` 直接写 404 + `{"code":"NOT_FOUND"}` 并终止，不再交给 `authorizeHttpRequests`。用 404 而非 403，避免 `denyAll` 暴露路由存在。

## 3. 与设计文档的偏差及原因

1. **`serviceSecurity` 未加 `securityMatcher("/internal/**")`**。设计 §3.1 描述链 1 以该 matcher 限定，但现存 `serviceSecurity` 从未设置 matcher（它是"整站单链"语义，`ServiceAuthorizationTest` 等既有测试依赖它对 `/actuator/health` 的放行）。为了满足"保持签名 + 既有测试继续绿"，matcher 放在新入口 `serviceTrustChain(...)` 中设置；生产装配必须用新入口，`serviceSecurity` 仅作兼容保留。
2. **`security(...)` 由包私有改为 `public`**。原测试用反射 `getDeclaredMethod("security", ...)` 调用；新链测试与第二阶段服务需要直接调用。参数列表与返回类型未变（仍为 5 参），`TrustedJwtDecoderTest` 反射调用不受影响。
3. **`IAM_CONFIG_INCOMPLETE` 是新增包装码**。`AuthorityProperties.validate` 里原本只有 `IAM_CONFIG_REQUIRED` 等细分码（且本阶段不允许改 record 语义）。实现保留细分码并在 iam 下包装成设计要求的 `IAM_CONFIG_INCOMPLETE: <detail>`，既有断言/文档兼容。
4. **目录自检落在 `SmartInitializingSingleton` 而非 BFPP**。BFPP 阶段没有 `RequestMappingHandlerMapping`；`SmartInitializingSingleton` 仍在 WebServer 启动前执行。`bindDeployment(...)` 保持"只 bind+validate+register"的旧语义（`ServiceTokensDeploymentModeTest#legacy-then-iam` 依赖它成功），校验式入口是新方法 `bindValidatedDeployment(...)`。
5. **client-secret 校验不做 SecretVault 解密**。`SecretVault.resolve` 需要 `Actor.current()`（启动期无 SecurityContext）且可能触发文件/密钥解析。启动门只做"引用形状 + 本机环境变量可解析"，不解密、不发网络；运行期第一次使用票据时再走 `SecretVault`。
6. **`CoverageGate` 把 `mode=disabled` 行也视为缺失**。设计 §3.2 说"所有 handler 都要有 catalog 行"，但 `disabled` 行表示能力被关闭；iam 下放行会导致请求 403 而非启动即失败，故按"不可用能力 = 缺失"处理（dev 仍只 warn）。

## 4. 测试证据

### 4.1 验收命令（绿）

```
cd "G:/207/项目/算法重组工具/代码/agent-platform"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl admin-common test \
  -Dtest='IamInbound*Test,AuthorityConfigurationTest,AuthorityPolicyTest,RouteCoverageVerifierTest,ServiceAuthorizationTest'
```

原文（2026-10-30 16:2x）：

```
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 17.14 s -- in ...AuthorityConfigurationTest
Tests run: 14, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.190 s -- in ...AuthorityPolicyTest
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.098 s -- in ...IamInboundChainTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.263 s -- in ...IamInboundDecoderTest
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.222 s -- in ...IamInboundGateTest
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.259 s -- in ...RouteCoverageVerifierTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.689 s -- in ...ServiceAuthorizationTest
Tests run: 91, Failures: 1, Errors: 0, Skipped: 0
```

唯一失败 `AuthorityPolicyTest#catalogCoversEveryActualControllerTemplateWithoutGrantingGatewayWildcards` 是 **HEAD 既有失败**，见 §5；与本阶段改动无关。

新增 14 条测试全部绿：`IamInboundGateTest` 5、`IamInboundDecoderTest` 4、`IamInboundChainTest` 5。

### 4.2 红证据（移除实现后必红）

用一次性源码变异（改完立即还原，`git status` 仍为 12 项）验证"实现缺失时红"：

| 变异 | 测试 | 结果原文 |
| --- | --- | --- |
| dev-token 过滤器返回 403 而非 404 | `IamInboundChainTest#iamApplicationChainAllowsHealthDeniesInternalAndHidesDevToken` | `Tests run: 1, Failures: 1 ... expected: 404 but was: 403` |
| iam 下 `/internal/**` 由 `denyAll()` 改为 `permitAll()` | 同上 | `Tests run: 1, Failures: 1 ... expected: 401 but was: 200` |
| `CoverageGate` 永不阻断 | `IamInboundGateTest` | `Tests run: 5, Failures: 2 ...`（`missingInternalRouteBlocksIamButOnlyWarnsInDev`、`disabledCatalogRowAlsoBlocksIamStartup`） |
| 跳过 `requireLocalClientSecret` | `IamInboundGateTest#unresolvableClientSecretFailsWithIamClientSecretMissing` | `Tests run: 1, Failures: 1, Errors: 0, Skipped: 0` |
| `serviceTrustChain` 去掉 `securityMatcher("/internal/**")` | `IamInboundChainTest#internalChainRunsBeforeTheApplicationChainRegardlessOfBeanDeclarationOrder` | `Tests run: 1, Failures: 1 ... expected: 401 but was: 200` |
| `AuthorityDecoders` 去掉 audience validator | `IamInboundDecoderTest` | `Tests run: 4, Failures: 2 ...`（`serviceDeploymentRejectsWrongAudience...`、`gatewayDeploymentAcceptsBoth...`） |

解码器的 `at+jwt` typ 校验也是实测出来的：不加 `setJWSTypeVerifier` 时 3 条解码/链测试报 `BadJOSEException: JOSE header typ (type) at+jwt not allowed`。

### 4.3 全模块回归（后台跑全量）

`mvn -pl admin-common test`（384 条）：除 §5 的 1 个失败 + 6 个既有错误外全绿；新增测试与 `TrustedJwtDecoderTest`（12）、`CommonBoundaryTest`（4）、`ServiceAuthorizationTest`（14）、`RouteCoverageVerifierTest`（9）均绿。

## 5. 预存在失败（HEAD 即红，非本阶段引入）

在同一提交 `47e8a026` 上用 `git stash push -u -- admin-common` 暂存本阶段全部改动后复跑，结果与带改动时一致：

1. `AuthorityPolicyTest#catalogCoversEveryActualControllerTemplateWithoutGrantingGatewayWildcards`：HEAD 失败。原因：`platform-gateway/src/main/java/cn/agentplatform/gateway/api/PortalSessionController.java:13` 是 `public final class PortalSessionController`，而测试用 `source.indexOf("public class ")` 定位类声明。属该文件与测试断言不匹配，本阶段未修改两者（禁止改六个服务模块）。
   ```
   [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
   ```
2. `ServiceTokensDeploymentModeTest` 5 个 fork 场景 + `LocalRunAdvanceAuthorityTest#sameRootAppReadAndDebugGrantsCannotBecomeLocalMain`：HEAD 同样 6 个 ERROR。根因是 Windows 控制台编码（子进程输出的中文为 GBK）与 `Files.readString` 默认 UTF-8 不匹配：`java.nio.charset.MalformedInputException: Input length = 1`。属环境问题，与本阶段无关。
   ```
   [ERROR] Tests run: 11, Failures: 0, Errors: 6, Skipped: 0
   ```

## 6. 未覆盖 / 已知风险

- 未做 SSE `?access_token=` 的显式拒绝测试：属第二阶段各服务（`RunEventsProxy`），本阶段只保证 JWT 过滤器只读 `Authorization` 头。
- `/actuator/health/**` 子路径未单独断言；`/actuator/env` 已断言 401。
- `routeCoverageGate` bean 未在完整应用上下文里跑"缺行 → 启动失败"（只用 `CoverageGate.verify` 单测 + 既有 `RouteCoverageVerifierTest` 的 `webServer.start=0` 证据）。
- gateway 的 portal 面 `/admin/**`、`/auth/session` 特例未在本阶段改动/测试；第二阶段接入时需回归。
- `CoverageGate` 对 gateway 的 `ANY` 映射（`/admin/**`、`/v1/**`）按模板逐条查 catalog，与 `RouteCoverageVerifier` 的 gateway 特判可能存在差异，第二阶段需用 gateway 全量 handler 跑一次。

## 7. 第二阶段各服务改动清单

每个服务：

1. 装配链 1：`@Bean ServiceTrustChain serviceTrustChain(HttpSecurity http, @Qualifier("serviceDecoder") JwtDecoder decoder, ServiceAuthorization receiver)` → `SecurityConfiguration.serviceTrustChain(http, decoder, receiver)`；`ServiceAuthorization` 用本服务 `service-id` + `ServiceRoutePolicy` + `CurrentAuthorityClient` 构造，并注册 `authorization.mappedInterceptor()`（`RouteCoverageVerifier` 会检查该 guard）。
2. 装配链 2：保留 `SecurityConfiguration.security(...)`（或 `@Import(SecurityConfiguration.class)` 后直接注入）；确认 `/internal/**` 只由链 1 匹配、链 2 在 iam 下 `denyAll`。
3. 解码器：iam 下用 `AuthorityDecoders.iam(deployment)`（或直接复用 `jwtDecoder` bean）；删除服务内自建 HS256 解码器的生产分支；确认 `typ=at+jwt` 票据可用。
4. 启动门：配置 `agent.security.mode=iam`、`service-id`、`authority.*`；环境变量 `AGENT_SECRET_IAM_<SERVICE>_CLIENT` 必须在启动时可见；新增/删除 `/internal/**` handler 必须同步 `agent-service-authority.json`，否则 iam 启动被 `ROUTE_COVERAGE_MISSING` 阻断。
5. gateway 额外：`portal.*` 全量配置；portal 票只走 gateway 链 2；SSE 票只从 `Authorization` 头取（Q4）。
6. 清理：移除各服务对 `IAM_TRUST_CHAIN_UNAVAILABLE` 的期望/开关/文档；不得在服务内重复 `ServiceTokens.registerDeployment`。
7. 验收（每服务）：`/actuator/health` 200；`/internal/**` 无票 401、坏票 401、无 catalog 行/模式不符 403、授权服务断电 503；`/auth/dev-token` 404；dev-hmac 回滚跑既有测试零新增失败。
