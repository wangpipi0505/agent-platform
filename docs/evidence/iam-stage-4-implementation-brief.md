# G6 Task4 六服务迁移分步交接（准备稿，不授权提前写代码）

唯一新工程 G:/207/项目/算法重组工具/代码/agent-platform；用户授权持续开发与隔离端点，无生产IAM/K8s环境，不再索要环境。原v1.2及四契约、Java21/Boot3.5/Security6.5/Nimbus9.37.3/Vue3.5保持。

本文件原Task4逐字摘自已批准计划8b2fb5e7；只是把同任务的代码与审查分成连续可核对的交付，未删验收、未改语义。当前Task3仍在实施，不授权Task4提前修改common或其他模块。Task3最终接口/完整测试/源码及独立审查由root正式交接后补充。当前主服务仍运行962阶段固定JAR，新common82增量未部署。

## 执行与依赖

原D/Task4最后的RR1真实PG材料流明确依赖E/Task5；不能用两个缓存chunk或common组件通过代替这一项，也不能为了顺序表面整齐提前把D全部标完成。保留原D/E集成关系，以下只安排内部工作顺序：

1. D1：common ServiceAuthorization、RouteCoverageVerifier及必要安全链接缝。先完成可信service票据与当前决定、method/template/action/caller/target、请求上下文/具体resource的闭环能力。固定路由清单仍只是能力目录；资源必须由真实handler参数/域对象解析。没有守卫的服务不得因为全局mode开关而暴露。实际服务迁移前生产IAM启动闭锁继续保持。
2. D2：独立app policy resolver与PG revision。按原2.6固定method/path、独立policy签名域、完整DTO及单snapshot参数化SQL；新迁移实施前重核最大版本。真正修改policy/revoke等入口后再验证旧grant失效、resolver→authority=0，不用静态ACTIVE或原secretRef接口替代。
3. D3：网关portal/session、ApiKey受限桥接、机器bootstrap与六服务HTTP clients/receivers迁移。逐服务登记和真实HTTP验证；禁止半迁移放行。顺序取决于Task3实际接口及D1/D2结果，root可按独立模块再拆，但每段均留完整差异/命名测试和复审。
4. D4：门户provider前端配套。独立于业务payload的会话generation/迟到401隔离、内存token与显式重新连接；保留SDK ticket()协议。依赖D3真实session投影，单元假响应不能签收真实浏览器链。
5. E/Task5：持久grant/current、材料scope与真实消费点。原Task5全部要求另行移交，不被D组件通过代替；D阶段已准备的holder/stream钩子随E一起完成真实PG及跨域manifest路径。
6. D/E联合闭环：执行原Task4 R1/RR1真实流、原Task5 RR2真实恢复，以及各自负向矩阵；完整D、E仅在所有对应验收完成并独立复核后标闭。随后原F有限跨服务真实矩阵/统一回归、G部署门禁。

这些是同一批准任务的顺序细化，不是新增产品功能。一次只派一个实施者，未闭合段落保持明确blocked，不拼数字或以拒绝所有正例代替可用实现。

## 后续移交前root检查

- Task3新增共同材料纯类型若已提前落地，Task5复用其精确接口，不另造重复模型；真实PG/schema/store/消费仍需实现与行为测试。
- Task3静态ServiceTokens开发兼容若使用明确system property，D阶段必须统一Spring配置与静态入口的模式来源。不能仅密钥存在、默认profile或测试名称而放行；旧测试需显式独立test配置，并finally还原全局属性。
- root只读核实Actor.current当前仍从入站JWT投影roles/attributes；D上线后不能忽略在线决定返回的更窄Actor而继续使用尚未过期旧票据里的角色。D1允许最小修改该静态投影接缝，Actor四字段与业务角色规则不变：受信delegated请求使用经当前决定核实且tenant/principal一致的上下文投影，缺上下文默认拒绝；workload仍不能变成用户。后台继续显式传verified对象，不借此重新引入跨线程ambient身份。用实签旧票据含ADMIN而当前决定收窄的HTTP反例证明旧角色不能进入业务守卫；C阶段入口闭锁，本条不是已部署漏洞结论。
- 全部出站调用迁移含RestClient、HttpURLConnection、SSE reader、api-docs聚合；未知target拒绝，禁自动redirect。不得把用户JWT、ApiKey或integration HMAC传成内部票据。
- ServiceRoutePolicy中的disabled行继续拒绝作为普通service capability；其中gateway六行的实际reason为“Gateway ingress/dispatch only; no service capability granted”，并非关闭所有合法门户/集成入口。D应分别验证门户、集成、service及POLICY_MACHINE的精确入口；不能把服务目录直接当公共入口allowlist。独立policy resolver不混进普通workload。真实新增/auth/session及材料endpoint须按各自域登记，runtime handler清单核对不是静态源文件计数。
- D1信任域应来自validated部署模式及明确验证入口；当前SecurityConfiguration仅在前置隔离阶段以decoder instanceof TrustedJwtDecoder区分旧测试路径，后续gateway若组合独立portal/integration decoder，不能因此落回旧HMAC/service角色规则。生产始终保持用途互斥、无验签失败后回退。
- root已核同handler别名可共用action但integrationAllowed不同：RunController.status的/admin/runs/{id}、/admin/debug-runs/{id}/status为false，/v1/runs/{id}为true。D1须按实际method/template对应行执行集成来源限制；不能因action相同而把v1允许扩到admin。来源/原始来源均取已验operation，不信任HTTP自报。
- 运行时覆盖的启动时点另见iam-stage-4-inventory/startup-review-note.md：已读固定Boot/Context实际源码，仅为准备；D1仍需有限启动负向证明完整handler覆盖在接收请求前失败，不能用ApplicationReady后检查替代。
- owner/tenant SQL、原批准confirmRoles、回调签名/body/attempt、UNKNOWN/事实/取消边界保留。材料scope缺失的旧记录不能默认补权。
- 不读取相邻旧代码/config/cache/data/process；测试只用新工程专用PG和临时测试密钥。主六服务/JAR及长驻辅助进程保持，实际迁移+统一回归+复审后由root组织新固定制品，不能复用旧硬编码checkpoint脚本。
- 发布仍BLOCKED_IAM_SCOPE_ACCEPTANCE；有限夹具通过不签真实IAM/集群/容量。T01唯一完成状态不得因子步骤通过自动改变。

## 原Task4完整正文

### Task3审查转交的跨任务核验（尚未完成）

Task3首轮独立审查为Needs fixes，I1/I2由原作者集中返修；不能据本准备稿提前进入D。该审查中的跨任务事项由root明确接受为后续必验依赖：

- 当前有限fixture的resume确实从原Grant派生，初始上限未增加；真实IAM必须持久保持同一原grant ceiling，D/E在受理、保存、恢复、材料消费时再次保留可信上限。DelegationRef本身没有完整ceiling，不能只看引用或签名字段相互一致便签收真实持久链。
- 实际provider首发之后才可markResponseAccepted；实际writer、subscription/HTTP reader的关闭回调、final/finally收尾与父响应关联必须落入真实循环，不能由请求body/header触发，也不能用两个预缓存chunk替代。
- permit的producer/selector在C中做存在性及后续值匹配；D/E须从真实run/invocation/attempt/round/outputAttempt、资源版本及固定生产者连接导出并核对允许关系。authority签过任意字符串不代替本域归属/连接来源核验；只接受原受理连接的私有manifest，不新开任意HTTP补查。
- PG material_scope与payload/事实同事务、完整初始ceiling及父材料要求/labels并集、先metadata判权后同快照取payload、真实原字节摘要和六服务guard均仍待实现与实际矩阵；C纯集合/组件测试不能关闭这些事项。
- C首版forward仅支持同一确切ResourceBinding。D/E若实际业务hop需要子资源，要提出并验证可信父子关系，不可在本地凭字符串猜测或放宽为任意资源；此限制未被签成全业务路径可用。

非阻断维护项M1：旧AuthorityConfigurationTest的预期启动异常产生大量堆栈，后续所属测试维护时捕获/约束预期日志，保留负向断言；不为消噪放松安全配置或篡改本轮原日志。最终全工程review应复核此项。

### Task 4: 六服务 HTTP 迁移与 ApiKey/SDK 连通性

**Files:**
- Create: `admin-common/src/main/java/cn/agentplatform/common/authority/ServiceAuthorization.java`、`RouteCoverageVerifier.java`。
- Modify common: `SecurityConfiguration.java`（生产 downstream 全部业务路径仅受限 service ticket；health 独立允许）；`ApiKeyAuthenticator.java` 签名保持。
- Modify gateway: `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java`、`api/DevAuthentication.java`、`application/IntegrationAccess.java`、`domain/IntegrationPolicy.java`。
- Create gateway: `platform-gateway/src/main/java/cn/agentplatform/gateway/api/PortalSessionController.java`（§2.7新增 `/auth/session`，root允许纳入配套，独立复核通过后实施）。
- Modify app: `app-management/src/main/java/cn/agentplatform/app/application/AppService.java`、`IntegrationService.java`、`DraftMetadataService.java`。
- Create app: `app-management/src/main/java/cn/agentplatform/app/api/IntegrationPolicyResolverController.java`、`application/IntegrationPolicyResolver.java`、`domain/PolicyResolveRequest.java`、`domain/PolicyResolveResult.java`、`infra/PolicyResolverSecurity.java`；候选 `app-management/src/main/resources/db/migration/V104__integration_policy_revision.sql`，按实际连续编号重核。Modify `app-management/src/main/java/cn/agentplatform/app/api/IntegrationController.java` 与上述 IntegrationService 所有策略/密钥变更入口，保证revision原事务递增。
- Modify engine: `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RuntimeClients.java`、`TestInputClient.java`。
- Modify streaming receivers: `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java`、`model-gateway/src/main/java/cn/agentplatform/model/api/ModelController.java`、`model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java`、`infra/FrozenRequestConnector.java`（末项同属model Java包根，真实provider发送前钩子与流资源取消）。
- Modify skill: `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java`、`application/SkillExecution.java`、`application/ReconciliationProofs.java`、`application/SkillCallbacks.java`、`api/SkillCallbackController.java`、`api/SkillController.java`。
- Modify monitor: `monitor/src/main/java/cn/agentplatform/monitor/application/EvaluationService.java`、`StatisticsService.java`。
- Modify all six: `<module>/src/main/resources/application.yml`，逐文件显式 service-id（gateway/app/engine/skill/model/monitor），无生产凭据默认。
- Test: `platform-gateway/src/test/java/cn/agentplatform/gateway/IntegrationSecurityTest.java`、新增 `ServiceAuthorityHttpTest.java`；每业务服务新增 `src/test/java/cn/agentplatform/<app|engine|skill|model|monitor>/ServiceAuthorityHttpTest.java`。
- Test additions: `engine-runtime/src/test/java/cn/agentplatform/engine/infra/StreamAuthorityHttpTest.java`、`model-gateway/src/test/java/cn/agentplatform/model/application/ModelStreamAuthorityHttpTest.java`、`app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java`。
- Frontend配套（随本计划独立复核）：Create `web/shared/portal-auth.ts`、`web/tests/portal-auth.test.ts`；Modify `web/shared/api.ts`、`web/admin/src/main.ts`、`web/admin/src/App.vue`、`web/shared/run-observer.ts`、`web/debug-panel/events.ts`；保留 `web/assistant.js/src/main.ts` 的 ticket() 对外协议，其共用observer仅增加授权reset停止态。

**Interfaces:** `ServiceAuthorization.require(ServiceCall)` 比对已验 caller/target/action，并在线 authorize 返回 VerifiedAuthority；`requireWorkload(ServiceCall)` 只对策略列出的纯机器路径调用 authorizeWorkload，返回 WorkloadAuthority；`requireResource(ResourceBinding)` 在实际 controller 参数/域对象解析后、任何发送/读取受保护 payload 前再核验绑定。现有 HTTP client 方法可以保留 Actor 参数供本地所有权比较，但取票必须另传授权上下文/显式后台 context，生产不存在“只有 Actor 就出票”的成功路径。

- [ ] 先建立每个服务的真实随机端口 Spring Boot HTTP 测试，至少一对 gateway→engine 和 engine→skill 经过各自 SecurityFilterChain；逐个测试正确票据/错误 caller/target/method/action/tenant/subject/资源、portal 直连 downstream、integration 直连 downstream、仅 ADMIN/SERVICE 内部调用。每例请求有限一次，检查 controller/adapter 计数为 0 或 1。
- [ ] 执行 `mvn.cmd -B -ntp test "-Dmaven.repo.local=.tools/m2" "-Dtest=ServiceAuthorityHttpTest,IntegrationSecurityTest" -Dsurefire.failIfNoSpecifiedTests=false`，预期现有共享角色可绕过若干负向，记录真实红色原因。
- [ ] 将每一 `.header("Authorization", "Bearer " + ServiceTokens.issue(actor))` 和 HttpURLConnection 对应路径改成显式调用取票；保留业务响应及错误语义。核心模板：

```java
ServiceCall call = routes.require(localServiceId, target, method, routeTemplate);
VerifiedAuthority current = authorityContext.requireFor(actor, resource);
ServiceTicket ticket = tickets.issue(current,
    new ServiceCall(call.target(), call.action(), resource));
outgoing.header("Authorization", "Bearer " + ticket.value());
```

在此任务定义 `AuthorityContext.requireFor(Actor,ResourceBinding)`，先比较 tenant/sub 与当前已验上下文，再调用 authority 当前核实；无上下文拒绝。caller 仍由本机 config/外部 IAM 认定。`RuntimeClients.base` 未知 target 抛 403，不再 default model。网关 SSE、api-docs 聚合、model stream 分别迁移，不遗漏非 RestClient 路径；关闭自动 redirect。
- [ ] gateway bootstrap 改用仅 `integration.credential.read/origin.check` 的机器 client ticket，app 根据已验 caller/action 允许；禁止 tenant `_gateway` 字符串成为通用跨租户豁免。集成 profile 完成后才开启生产 ApiKey bridge；现有错误密钥、跨应用、策略变化、吊销、未注册 Origin、SDK session/approval/artifact 路径全部保留测试。入站 `X-User-*` 只用于已成功 ApiKey 认证后的受限声明，不进入 portal 身份路径。
- [ ] **R3 独立resolver红→绿：** 先写真实app随机端口+专用PG测试，现有gateway credential endpoint不允许代替新端点。按 §2.6 实现独立chain/DTO/参数化SQL与revision事务；不调用Actor.current/SecretVault/authority。固定method、caller/action/body摘要各错一项401/403；跨credential/subject404/403；合法DTO不含secretRef。测试以真实ADMIN既有policy/revoke接口更新PG，LoopbackAuthority再次exchange/resume/authorize旧grant均拒绝，新认证按新政策可成功。命令：`mvn.cmd -B -ntp -pl app-management,platform-gateway -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=IntegrationPolicyResolverHttpTest,IntegrationSecurityTest" -Dsurefire.failIfNoSpecifiedTests=false`；记录resolver→authority=0，不能仅mock resolver结果。
- [ ] **R1 真实stream红→绿：** 在RunController writer、ModelController虚拟线程、ModelCalls doOnNext/final、RuntimeClients reader、GatewayController buffer写出前显式传递并检查同一ResponseLease；provider首次发送钩子在FrozenRequestConnector核当前业务授权，异步捕获Actor不够。watchdog关idle资源，complete/error/timeout重复关闭无泄漏。命令：`mvn.cmd -B -ntp -pl engine-runtime,model-gateway,platform-gateway -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=StreamAuthorityHttpTest,ModelStreamAuthorityHttpTest" -Dsurefire.failIfNoSpecifiedTests=false`。每条实际流以2个chunk+final闩锁样本测试过期/撤权/503；普通流第二chunk/final=0，INTEGRATION既在途响应可完成但下一provider发送=0，断观察流cancel-run请求=0，线程/订阅释放可观察。模型返回toolCalls后不得因drain执行工具。
- [ ] **门户配套红→绿：** 写portal-auth.test覆盖无provider、session401/403/503、迟到401、新宿主token、停止观察而无POST重放；先失败，再实现 §2.7 provider消费与gateway session当前投影。命令（工程根）：`npm.cmd --prefix web test -- tests/portal-auth.test.ts tests/login.test.ts tests/stream-auth.test.ts`。生产入口不调用dev-token；开发模式/SDK原ticket测试保持。浏览器真实签名链在Task6，不以mock fetch单测宣称管理端可用。
- [ ] 每服务启动 route coverage：全部 handler 明确登记、未使用 handler 禁用；替换散落 hard-coded `ROLE_SERVICE`、`agent-platform-internal`、GATEWAY_AUTH 规则。业务 ADMIN/EDITOR、tenant SQL、归属、审批原检查保持；回调仍验证签名/body/attempt，不因机器票据而绕过原验证。
- [ ] 重跑同命令和原 IntegrationSecurityTest；票据 jti/aud 在每跳不同，捕获业务接收 header 断言不等于 portal token、integration token、ApiKey。部分迁移期间 iam 模式不得开放半套可用入口。

- [ ] **RR1 真实材料交界红→绿（依赖E的表/接口）：** StreamAuthorityHttpTest 创建真实 PG run_event+material_scope，第一事件已发送后，以闩锁停在第二事件 nextEventRef 之前且未读 payload，分别令 decision/grant到期、authority撤权、503；放行后断言 INTEGRATION 经 readForResponse 真读合法第二事件并完成，不能预缓存payload。分别加入同run超ceiling、错manifest/scope摘要、错version、其他run和hardDeadline到期，拒绝前/后payload读取计数按§2.4.1分界且wire业务输出0。相同状态PORTAL第二payload读取/输出0，不进入该能力。ModelStreamAuthorityHttpTest 暂停真实在途provider的final生成，失效后走model持久scope→readForResponse→私有manifest+final→RuntimeClients.acceptForResponse→ModelExchange事实记录正例；错producer/responseId/请求摘要/manifest/version/payload digest、缺帧/乱序/跨连接重放均不释放final。drain跨域新manifest HTTP=0，旧current普通read拒绝，后续model/skill/new-SSE请求0；deadline/关闭后permit复用拒绝。运行上一stream命令留真实行为红绿，不能只测两个缓存chunk。

