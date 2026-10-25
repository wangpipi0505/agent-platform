# G6 Task4 D1 实施报告

日期：2026-10-20。唯一工作目录：`G:/207/项目/算法重组工具/代码/agent-platform`。

当前交付：公共服务接收守卫、当前 Actor 投影/具体资源复核、启动前真实运行时路由覆盖。最终 common **145 tests / 0 failures / 0 errors / 0 skipped**，14:40:20 完成，耗时 33.286 s。此处不是 D2/D3/D4、E、完整 G6 或生产 IAM 验收。

生产 `IAM_TRUST_CHAIN_UNAVAILABLE` 工厂处理器闭锁保留，`BLOCKED_IAM_SCOPE_ACCEPTANCE` 与 T01 唯一完成标记不变。没有修改父 POM、六服务 src/POM、生产配置、原契约、外部文档或前序证据，没有 package、七 reactor 测试、主服务启停、真实 IAM/K8s 操作或发布。

## 实现与装配边界

新增生产类只有 `ServiceAuthorization`、`RouteCoverageVerifier`。修改 `SecurityConfiguration`、`Actor`、`AuthorityContext`。POM 和所有 Task3 协议、客户端公共接口、ResponseLease 七字段、材料/响应生命周期实现均未改。新增两测试类，迁移两个既有测试类的显式装配/Actor 上下文断言。精确九文件差异见 [changed-files.json](iam-stage-4-d1/changed-files.json)。

- `ServiceAuthorization.require(ServiceCall)` 从 Spring Security 已验 JWT 取得 caller/mode/target/action，从当前 MVC 匹配属性取得 method/template，匹配本机 serviceId 的精确能力行，再通过真实 `CurrentAuthorityClient.authorize` 取得在线决定。相同 action 不合并别名来源限制；检查 `operation.originKind`，因此 DELEGATED 恢复也不能洗掉 INTEGRATION 来源。
- `requireWorkload(ServiceCall)` 仅接受 workload 目录行并调用 `authorizeWorkload`，不绑定 AuthorityContext，也不构造 Actor。两种模式互斥。
- `preHandle` 在参数解析/业务处理前完成上述能力检查。服务票据资源只参与入口绑定，不代替业务资源判权。controller 解析实际资源 metadata 后必须调用 `requireResource(ResourceBinding)`。
- `AuthorityContext.requireFor(Actor, ResourceBinding)` 先核 tenant/sub 及不得扩大的本地 Actor、exact resource，再在线重新核实。刷新前先将帧标为失效；任何失败后，即使业务捕获异常，也不能重新读取旧 Actor/current。成功时再核 caller/call/delegation/operation/authorizationVersion，当前 Actor 只可进一步收窄。
- `Actor.current()` 对 delegated 取在线决定中的 Actor，并与入站 JWT 的 tenant/sub 比较；缺上下文拒绝。workload 继续拒绝用户投影。门户保留原明确入口语义；四字段和 `requireRole` 业务规则未改。
- 原 `bind(VerifiedAuthority)`、`current()`、非 LIFO、owner-thread、无子线程继承行为保留；普通绑定没有请求刷新依赖，不能用它伪装可在线复核的请求帧。后台调用仍显式使用客户端与已验对象。
- `afterCompletion` 与 `afterConcurrentHandlingStarted` 均关闭请求帧；同请求重入在覆盖旧清理句柄之前拒绝。异步业务必须显式捕获 VerifiedAuthority/lease，不能继承 ambient authority。
- `RouteCoverageVerifier` 遍历注入的全部 `RequestMappingInfoHandlerMapping` 的实际 handler，每个 method/template 别名都分别检查。普通 service 目录行（含 disabled）还必须存在本机 `ServiceAuthorization` 的全路径、无 exclude 的有效 `MappedInterceptor`；仅存在 bean、局部 include/exclude 不算覆盖。遗漏在 `SmartInitializingSingleton` 时失败，早于 webServer.start。
- 唯一内建框架覆盖例外是 Boot `BasicErrorController` 本类声明的 `error/errorHtml`、精确 `/error`、ANY；它不授予业务权限。没有 docs、框架包、`/admin/**`、`/internal/**` 的通配豁免。
- `Ingress(method, template, java.lang.reflect.Method)` 是明确非 service 入站的覆盖登记；必须匹配真实方法，且未使用登记使启动失败。该登记不提供身份验证或能力，门户/集成/POLICY_MACHINE 仍需 D3 专用验证链。
- 可选 health 仅 `GET /actuator/health`，同时绑定明确 handler Method。安全链只放行这个候选路径，拦截器仍比较框架实际模板与 Method；health 的相邻路径不放行。没有为 common 新增 actuator 依赖或产品健康 controller。

实际装配 API：

```java
// D3 必须使用经部署校验的 serviceId、精确目录、真实当前授权客户端。
ServiceAuthorization receiver =
    new ServiceAuthorization(serviceId, routes, currentAuthorityClient);

// 必须作为 Spring bean 登记；不是只放进 MVC addInterceptors。
MappedInterceptor mapped = receiver.mappedInterceptor();

// 集合来自该 servlet context 的全部 RequestMappingInfoHandlerMapping beans。
RouteCoverageVerifier coverage =
    new RouteCoverageVerifier(serviceId, routes, allMappings, explicitIngress);

SecurityFilterChain chain =
    SecurityConfiguration.serviceSecurity(http, serviceDecoder, receiver);

// 解析真实本域 metadata/归属之后、读取或发送受保护 payload 之前：
VerifiedAuthority fresh = receiver.requireResource(resolvedResource);
// 或已有 Actor 仅作绑定比较：
VerifiedAuthority forOutgoing = AuthorityContext.requireFor(actor, resolvedResource);
```

health 使用 `ServiceAuthorization(serviceId, routes, client, healthMethod)`，覆盖登记同时提供 `new Ingress("GET", "/actuator/health", healthMethod)`。不要重复注册 MVC 局部 interceptor，否则会形成重复接收检查。D1 fixture 是组件 receiver，不是已迁移的真实 engine/skill/app。

旧 `SecurityConfiguration.security` 增加明确 `AuthorityProperties deployment` 参数，依据部署 mode 区分可信语义，不再依赖 `decoder instanceof TrustedJwtDecoder`。既有 HTTP 测试使用包装后的 JwtDecoder，证明组合 decoder 不会退回 dev-HMAC/SERVICE 逻辑。生产工厂处理器仍先拒绝完整 iam 配置；新组件入口不是环境变量 ready 开关。

## 命名验证与真实行为

新 `ServiceAuthorizationTest` 13 项：

| 测试 | 有效断言 |
|---|---|
| signedOldAdminCannotOverrideOnlineEditorInRealController | 实签旧 JWT 含 ADMIN、region A/B；在线决定为 EDITOR、region A；APPROVER 业务计数 0，当前 EDITOR 正例 1；故意不靠 controller 的资源复核掩盖入口 Actor 投影 |
| integrationOriginCannotUseAdminAliasesEvenAfterDelegatedResume | INTEGRATION 与 DELEGATED 恢复均 v1=1、两个 admin 别名=0；实际 method/template，相同 action |
| exactIdentityCapabilityAndResolvedDomainResourceMatrixNeverEntersBusiness | caller/action/tenant/sub/resource/HEAD/disabled/未知路径拒绝；错误 target、portal/integration 直连、仅 ADMIN/SERVICE、结构/签名错误拒绝；每个负例业务 0，合法 delegated 1 |
| resourceRefreshRevocationExpiryAndUnavailableNeverReuseInitialSuccess | 初次成功后、实际资源复核前撤销/到期/503 均业务 0，无旧决定回退；入口过期 401，已验请求复核拒绝 403，依赖故障 503；授权失败不逃逸成 ServletException |
| requireForChecksLocalOwnerAndRejectsWiderActorWithoutUsingItAsAuthorityInput | 错租户/用户/更大 Actor 拒绝，合法当前主体在线复核成功；缺上下文拒绝 |
| exceptionsAsyncHandoffAndConcurrentRequestsLeaveNoAmbientFrame | 异常后同池线程无帧，异步交接原线程清理且 worker 无 ambient authority；两并发域资源各成功 1 次，并核各自 exact resource；合计 2 不冒充单请求计数 |
| bothWorkloadRowsAreUsableAndCannotBecomeDelegatedUserCapabilities | origin.check 与 credential.read 各正例 1；两方向错误 mode 均 0；Actor.current/AuthorityContext.current 均不可用于 workload |
| catchingFailedResourceRefreshCannotRecoverThePreviousActorProjection | 业务捕获刷新失败之后仍读不到旧 Actor |
| explicitlyRegisteredExactHealthNeedsNoBearerAndGrantsNoSiblingPath | 无 bearer 精确 health 200；liveness/info/prometheus 均 401；authority 网络决定数 0 |
| freshSignedDecisionCannotChangeAnyOriginalSubjectGrantOperationOrCallBinding | 在线实签证明尝试换 tenant/sub/grant/caller/version/action/resource/operation/roles/attributes 均拒绝，业务 0 |
| actorProjectionRequiresContextWithSameTenantAndSubject | 实签 JWT 与当前 proof 的 tenant/sub 不一致不能投影 |
| nestedServletDispatchCannotOverwriteTheRequestCleanupFrame | 有限单线程重入：真实 RSA/HTTP authority，加 servlet 请求夹具；第二 preHandle 拒绝，旧清理句柄不丢失 |
| secondaryMappingUsesTheSameRealHttpCurrentAuthorityGuard | 第二个实际 mapping 的已登记统计路由经过同一真实安全链/在线 authority；合法 1、撤权 0、错误 action 0 |

新 `RouteCoverageVerifierTest` 9 项：

| 测试 | 有效断言 |
|---|---|
| unknownRuntimeHandlerFailsBeforeWebServerStart | 未登记真实 handler 启动失败，webServer.start=0 |
| everyRuntimeAliasMustBeCoveredBeforeListening | 漏掉 v1 别名即失败，start=0 |
| everyRuntimeMethodMustBeCoveredBeforeListening | GET/POST 只覆盖 GET 即失败，start=0 |
| customDocsHaveNoFrameworkPackageOrPathExemption | `/v3/api-docs/custom` 无豁免，start=0 |
| explicitIngressRequiresExactHandlerMethodAndCannotBeAnUnusedBypass | 错 Method、未使用登记拒绝；通配 ingress 不合法 |
| fullyRegisteredRuntimeAliasesStartAndServeActualHttp | 完整登记可 start=1，端口有效；该纯覆盖 fixture 的无凭据 HTTP 被 Boot 401；真实授权 200 正例在上述 receiver 测试 |
| secondaryRequestMappingInfoBeanIsAlsoCheckedBeforeListenerStarts | 第二 mapping 的 `/actuator/info` 也被扫描并在 start=0 拒绝 |
| partialGuardDoesNotCountAsCoverageOfRegisteredCapabilities | 仅注册局部 include 的守卫不能掩盖普通能力的漏守卫，start=0 |
| disabledOnlyMappingAlsoNeedsARealDenialGuard | 只有 disabled handler 的 mapping 同样要求实际拒绝守卫，start=0 |

所有新端点仅测试目录的随机 loopback 端口，使用临时 RSA、LoopbackIssuer/LoopbackAuthority 及真实 HTTP 当前决定；没有 mock authorize 来证明受信链。固定资源 fixture 明确为两份版本化 metadata（`r-test/snapshot-v1`、`r-other/other-snapshot`），controller 根据 URL id 解析该 metadata，再比对授权资源；不是把票据资源和自身再比一次。没有声称这是 PG/六业务域对象验收。

既有 `PortalClaimMapperTest.actorNeverGrantsServiceBusinessRolesFromTrustedRsaClaims` 改为真实签名/当前 proof：缺上下文拒绝，绑定在线 proof 后仍无 SERVICE/GATEWAY_AUTH。既有 `TrustedJwtDecoderTest` 12 项继续通过，且接收测试使用包装 decoder。其余 C 测试保持原断言。

## 实际命令、红绿与失败分类

所有 shell 显式工作目录均为工程根。固定运行环境：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
```

Maven 统一前缀：

```text
mvn.cmd -o -B -ntp -pl admin-common test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp"
```

`V:/.local/tmp` 是前序本工程已使用的短路径，避免 Windows JDK Unix-domain socket 临时路径限制；不是启动新环境。下表列明该前缀之后的选择器、保存的真实输出和结果：

| 选择器/附加参数 | 原始输出 | 实际结果 |
|---|---|---|
| `-Dtest=ServiceAuthorizationTest,RouteCoverageVerifierTest` | red.log | 3 项真实断言失败、0 errors：旧 ADMIN 错放行 200；合法 v1 空接缝拒绝 403；未知 handler 没有阻止启动 |
| 同上 | green-first.log | 3/0/0/0 |
| `-Dtest=ServiceAuthorizationTest` | matrix-first.log | 7/0/0/0；日志暴露刷新过期逃逸，随后增加严格断言 |
| `-Dtest=ServiceAuthorizationTest#resourceRefreshRevocationExpiryAndUnavailableNeverReuseInitialSuccess+catchingFailedResourceRefreshCannotRecoverThePreviousActorProjection` | red-refresh-behavior.log | 2 项真实断言失败：ServletException 计数 1；捕获失败后旧 Actor 仍可读 |
| `-Dtest=ServiceAuthorizationTest#explicitlyRegisteredExactHealthNeedsNoBearerAndGrantsNoSiblingPath` | red-health.log | 1 项真实断言失败：应 200 实为 401 |
| `-Dtest=ServiceAuthorizationTest#nestedServletDispatchCannotOverwriteTheRequestCleanupFrame` | red-nested-dispatch.log | 1 项真实断言失败：第二 preHandle 未拒绝 |
| `-Dtest=ServiceAuthorizationTest,RouteCoverageVerifierTest,TrustedJwtDecoderTest` | green-focused-final.log | 31/0/0/0 |
| 无选择器 | first-full-context-regression.log | 142 项，141 通过、1 error；旧 Actor 无上下文投影假设与 D1 明确要求冲突 |
| `-Dtest=RouteCoverageVerifierTest#partialGuardDoesNotCountAsCoverageOfRegisteredCapabilities+disabledOnlyMappingAlsoNeedsARealDenialGuard` | red-mapping-guard.log | 2 项真实断言失败：局部守卫时启动未拒绝 |
| `-Dtest=ServiceAuthorizationTest,RouteCoverageVerifierTest,PortalClaimMapperTest,TrustedJwtDecoderTest` | green-mapping-guard.log | 40/0/0/0 |
| 无选择器 | final-common.log | **145/0/0/0，BUILD SUCCESS，14:40:20** |

上述日志均在 `iam-stage-4-d1/`。命名 red、最终 focused、首轮完整及最终完整 XML 分别保存在对应 `*-xml/` 与 `final-xml/`，最终逐测试清单见 [final-test-summary.json](iam-stage-4-d1/final-test-summary.json)。历史命名 `red-refresh-xml/` 实际对应 `matrix-first.log` 的 7 项绿轮，不算红；真正两项刷新行为红在 `red-refresh-behavior-xml/`。没有用缺类/编译错误当行为红，首轮完整 142 项的缺 context 异常（0 assertion failures / 1 error）也单独记作旧测试假设迁移问题。

另保留并明确排除出行为红统计：

- `initial-environment-failure.log`：首条 Maven 命令没有 `-o`/短 Unix-domain 参数，Windows loopback 临时路径失败，3 项不可用于行为结论。
- `test-clock-compilation.log`：测试直接引用 MutableClock 私有字段编译失败，改用既有 `advance` 后才运行行为测试。
- `nested-test-setup-failure.log`：测试用未 authenticated 的 JwtAuthenticationToken，修正测试构造后才得到重入行为红。
- `green-matrix.log`：26 项中“docs 无豁免”样本误选目录本来已有 `/v3/api-docs`，改用确实未登记的 `/v3/api-docs/custom`；不是通过修改生产逻辑掩盖失败。
- `green-matrix-2.log` 27 项通过，`green-focused.log` 30 项通过，是后续增加具名断言前的中间绿轮。
- 首轮完整失败后发现具体的多 mapping 守卫装配缺口，经 root 明确同意修复并重跑。最终绿轮之后没有为报告/清单反复跑套件。

新启动负例仅针对 `SpringApplication` 的失败日志设置临时 ListAppender、禁止该 logger 传播，断言失败事件含 throwable，finally 恢复 logger；输出简短的 rejection 原因与 start=0。所有原历史日志保留。原 `AuthorityConfigurationTest` 的旧预期启动堆栈 M1 未改，最终 rawlog 仍保留，不能误判为测试失败。

## 实际阅读的固定开源源码

本轮没有升级依赖。实际阅读及应用点：

- 前序冻结的 Spring Context **6.2.7** `AbstractApplicationContext.refresh/finishBeanFactoryInitialization/finishRefresh`：单例初始化先于 lifecycle.onRefresh。
- 本轮取自固定 Maven sources.jar 的 Spring Beans **6.2.7** `DefaultListableBeanFactory.preInstantiateSingletons`：全部单例创建后调用 `SmartInitializingSingleton`。
- 前序冻结 Boot **3.5.0** `ServletWebServerApplicationContext`、`WebServerStartStopLifecycle`、`TomcatWebServer.initialize/start`：初始化移除 connectors，`bindOnInit=false`，后续 `webServer.start` 才启动监听。
- 本轮 Spring WebMVC **6.2.7** `RequestMappingInfoHandlerMapping`：实际匹配写入 BEST_MATCHING_PATTERN_ATTRIBUTE；`DispatcherServlet`/`HandlerExecutionChain`：preHandle 在 controller 前，异步改走 afterConcurrentHandlingStarted，异常/正常完成回调只覆盖成功进入的 interceptor。
- 本轮同版 `AbstractHandlerMapping.initApplicationContext/detectMappedInterceptors/getAdaptedInterceptors`、`MappedInterceptor`：全局 bean 被所有 handler mapping 发现，区别于 MVC 局部配置；include/exclude 可能使“存在守卫”不等于“守卫覆盖”。

本轮源码与固定 URL/archive SHA/entry SHA 在 [upstream/source-sha256.json](iam-stage-4-d1/upstream/source-sha256.json)。前序源码位于 `iam-stage-1/upstream` 与 `iam-stage-4-inventory/upstream`，只读未修改。代码注释保留生命周期、原始 origin、帧失效、资源/后台边界及全局拦截器这些必要不变量。

## 冻结、补丁重放与归还

基线 63 份 common src/POM 原字节保存在 `baseline/`；最终 67 份保存在 `frozen/`。源码最终清单 `source-after.json`；target 294 文件的字节摘要 `target-frozen.json`；最终 XML 原件复制到 `final-xml/`。前序证据、父 POM、六模块 src/POM 共 **3115** 项保护检查全部一致，见 `protected-after-check.json`。

无 HEAD（`git rev-parse --verify HEAD` exit 128）/无 remote（`git remote -v` 空输出）。未造提交或空 diff。使用实际 `git diff --no-index --binary` 产生完整非空 patch，并规范化复制目录前缀：

`full.patch`：**161709 bytes**，SHA-256 **55eea451fdea3bf2bc7e669d51d67617eff4097b063679ce88c747adffb2569e**。

在单独 `replay/` 基线副本执行实际 `git apply --check`、实际 `git apply`，两次 exit 0，再对全部 **67** 文件逐原字节比较，完全一致。没有创建辅助仓库/HEAD/提交。Windows 写回 CRLF 导致 git apply 报告 **1499 lines add whitespace errors**；原 stderr 原样记录，未用 whitespace 修复改变应用结果。字节回放通过不等于无警告。

精确执行、cwd、退出码、stdout/stderr 在 [freeze-commands.json](iam-stage-4-d1/freeze-commands.json)，重放结论在 [replay-byte-check.json](iam-stage-4-d1/replay-byte-check.json)，可检查脚本在 [freeze.py](iam-stage-4-d1/freeze.py)。该脚本不运行 Maven，也不改工作源码。

145 绿轮及冻结之后已明确向 root 归还 common source/target；随后只写本报告/自己的证据摘要，不再改源码、不再跑测试。

root 接手后独立核对结果在 `.local/iam-stage-4-d1-root-handoff-check.json`；root 另将 294 份 target 原字节归档到 `.local/artifacts/iam-stage-4-d1-common-target` 并核 SHA 一致。该物理副本归属 root；作者的 `target-frozen.json` 是 live target 摘要清单，不能混称物理备份。

## 自审结论与后续边界

已修复的自审问题有：失败刷新仍暴露旧 Actor、过期复核逃逸为 ServletException、嵌套 servlet 分发覆盖帧清理句柄、只在 MVC 局部配置 interceptor 导致其它 mapping 漏守卫。每项有具名实际红与后续绿。

本轮不承诺在线授权与之后业务操作之间的全局原子性。真实 domain owner/resource version、子资源父子关系、PG 材料 scope、permit holder/provider 首发和实际 writer/reader 生命周期仍由 D3/E 落入真实业务消费点；当前 exact ResourceBinding 不放宽。没有任何测试宣称真实 IAM 供应商持久 grant、六服务实际迁移、真实 Actuator 产品配置、真实集群或容量已验收。

当前需要 D3 明确处理的装配边界：`Ingress` 拒绝 ANY/通配模板，因此 gateway 既有 `ANY /admin/**`、`ANY /v1/**` 两个代理 handler 不能直接使用普通 downstream service 接收装配。其它四个 gateway 精确入口可以明确 Ingress 登记。D3 必须在真实专用信任链下提供绑定具体 HandlerMethod 的受限代理登记，并完成正负启动/HTTP 验证；不得简单把 ANY 加入通用 service 能力或全局 disabled 豁免，也不得以 gateway 通配路径绕过其它下游守卫。root 已要求保持当前冻结、将该边界连同 D1 范围交独立 review 判断，不预判审查结论；D3 尚未完成，生产仍关闭。

旧 AuthorityConfigurationTest M1 仍是已知日志维护项；新 RouteCoverageVerifierTest 的预期失败日志已窄捕获。除上述跨阶段边界外，本轮具名范围测试与冻结检查均通过；最终是否签收 D1 以独立 review 为准。
