# O1c 接口最终化笔记：对照 O1b 冻结交还

状态：只读准备，O1c 未实现、未测试；正式 dispatch 仍须 O1b 独立评审、root 有限审计及封存通过。本次只新增本文，不修改冻结候选、草案、源码、target、八索引或 review。每条 shell 明确 cwd=`G:/207/项目/算法重组工具/代码/agent-platform`。无测试/probe/服务/子代理。

## 1. 实际基线及适用裁决

已物理核对：

| 工程相对路径 | Bytes | SHA-256 |
|---|---:|---|
| `docs/evidence/iam-stage-4-o1b-readonly-status/handoff.json` | 3313 | `3cdc4358db7d7307809f961082502c33f7d568d1331ded969e7f7dc32e40e7e0` |
| `docs/evidence/iam-stage-4-o1b-readonly-status-report.md` | 16075 | `6a4665f2807788b4a1c6c2938e6d43b278bdb0fc3a945bf6f07d260b105ab33c` |
| `docs/evidence/iam-stage-4-o1b-readonly-status/source-after.json` | 109789 | `a956230f2e97c552e026196748fb67519c77acc1bcfaeee747df744cac25f8de` |
| `docs/evidence/iam-stage-4-o1b-readonly-status/target-after.json` | 406646 | `06e1fa0bd59c8e95e0f54a11cc59960d48f5f3d6670d958be4950decc49932f6` |
| `.local/iam-o1c-brief-draft.md`（本次读取版本） | 8218 | `590cd4da3032170ce823a6383e5ac08bad6800a37505cdd608a80db5e569343f` |
| `.local/iam-o1c-root-assembly-preflight.json` | 1699 | `44f7f6cf64d73220e0f25c057ac9e39e4cb1b0a3de626fc2a8c5da3e0f65a887` |

O1b 逻辑 source579（后端387+前端192）、target1968；10份source overlay优先于O1a10份overlay，其余按D4a/N4b2 composition继承。root最新交接：fresh review提出两个待最终定级Important——新fixture边source/target与真实admission所需from/to不一致，以及StatusBuffer只限count未证明backing capacity≤2MiB；root准备fresh narrow fix，当前head不得称Approved。本文核的是这次已冻结head，修复后必须只对新的具体delta核API/manifest、合法seed关系及buffer实际容量，不沿用当前报告的通过数证明修复head。没有读取当前工作树源或扫target。O1b报告的最后覆盖是final04新类17/0/0，加不同轮final01的common3/GraphRules1/原status2/O1a4，不能写成一次27例全绿；本文没有重验那些行为。

原O1c候选25436 bytes / `dc065f0df3d6af2ccd21343db0bfe598ea5d2b3a0c518f69d35cf72545a14aa5`保持冻结。root草案已裁定：Q总wire8192/data4096；M完整wire2MiB；engine内部401→gateway503；浏览器401仅明确原Portal无效/到期；无stale Content-Length；实际stream取得后终检；有限显式装配，不宣称G6E/F完整Boot。本文按这些裁决收敛，不重新讨论两套方案。

## 2. O1b本次冻结API：可复用接缝，不能跨服务搬能力

### 2.1 共同selector

冻结 `RunObservationSelectors`：

- public `KIND="run-observation-selector-v1"`、`ACTION="run.observation.metadata.read"` 保留。
- 新 public `STATUS_ACTION="proxy.engine.get.RunController.status"`。
- 新 `public static ResourceBinding status(String run,String acceptedSha)`：run匹配`r_[0-9a-f]{32}`，sha为lowercase64hex；返回run-snapshot，格式错403/RUN_AUTHORITY_DENIED。
- `metadata(SubjectKey,String)`仍是原五元canonical tuple/descriptor hash，主体非空白≤128，错格式400/INVALID_RUN_OBSERVATION_SELECTOR。
- `requirePurpose`26–39：status action或run-snapshot任一命中就要求另一者匹配、gateway→engine、delegated、PORTAL/PORTAL；其它动作不能用run-snapshot。Q双向规则保持。O1c直接调用现API，不另写不同canonical schema，不新改selector/catalog/ServiceAuthorization。

### 2.2 engine HTTP及内部观察结果

`RunController.status(String id,HttpServletRequest,HttpServletResponse) throws IOException` 仍三alias同Java方法，但IAM实际只允许admin GET、REQUEST、精确handler类型/方法、模板/URI、无rawquery/body。IAM进入`RunService.observeStatus(id,Actor.current())`；legacy返回原ApiResponse。完整ApiResponse原mapper流式写有界StatusBuffer（2*1024*1024），超限403/RUN_STATUS_TOO_LARGE；序列化后及实际stream取得后均`observed.requireCurrent()`；没有成功Content-Length，标准chunked可出现；返回null，避免converter第二写。

`RunService.ObservedStatus` public final、constructor private，仅`value()`与`requireCurrent()`；`observeStatus(String,Actor)`返回它。`TraceStore.ObservedView`同样private constructor；内部StatusReadProof包内可见、constructor private、绑定创建线程/同retained frame/PG binding/预检scope集合。`statusObserved`入口拒绝ambient→事务外refresh→单RR；真实PG绑定/签名SHA/全部scope检查后才mint与读snapshot/refs；commit后再检查。

这些是engine本地API与能力，**gateway仅经HTTP拿JSON**；禁止反序列化ObservedStatus/ObservedView、调用engine Java类或把engine frame传gateway。O1c不改engine任何生产文件。

`RunAuthorityStore.loadObservationBinding(JdbcTemplate,SubjectKey,String)`已经实际实现为package-private纯PG方法：owner/publicId窄查，TASK/FULL、unsupported/tool存在性排除，load(true)三根完整验证；无行404/RUN_NOT_FOUND，归属但不完整403/RUN_AUTHORITY_DENIED。这是engine内部行为，gateway不能自己查询PG取得读许可。

### 2.3 Loopback可信登记

实际公共fixture API（测试源，非生产协议）：

- `registerObservation(SubjectKey owner,String run,String acceptedSha)`：用`RunObservationSelectors.status`建绑定；putIfAbsent；同run不同绑定抛`IMMUTABLE_OBSERVATION_BINDING`。
- `denyObservation(String run)`：按run拒绝。
- `clearFiniteGrants()`会清grants/operations/decisions/requests/**observations及deniedObservations**；因此每轮reset后需从受理事实重新登记，不能以为它只清票。
- status只接受exchange/authorize，拒绝resume/forward/integration；exchange核已验source；authorize另核call等于grant.originalCall，再核registry owner+完整resource。Q仍从已验source tenant/sub与tuple[3]重建selector。

O1b fixture的`metadataThenStatus`223–230先真实Q HTTP，随后以实际RunAuthorityStore.load得到binding登记，再以**Q response data的acceptedSnapshotSha**构造M。这不是“Q响应自己赋权”：registry值来自受理后PG可信事实。O1c多进程测试应由engine fixture经私有stdin/stdout管理通道输出受理后的有限owner/run/SHA，再由父测试登记authority；不能让浏览器请求或新生产HTTP endpoint登记。

有限grant上限仍是explicitPortalActor为空时2、设定时32。O1c真实受理seed加Q/M超过2时应显式设置可信fixture actor或按case清理并重登记，不放宽生产策略/改历史以凑通过。M精确拒绝跨run/SHA由此现profile提供，无需重写Loopback新的run registry。

## 3. 错误与完整body：供正式brief锁定的映射清单

`ApiResponse`现四字段code/message/data/traceId；OK message为空字符串。`PlatformErrors`对PlatformException保持其status/code，data=null，traceId来自本地MDC；非法参数400/INVALID_INPUT；ResourceAccessException为502/DEPENDENCY_UNAVAILABLE。由decoder/filter拒绝的401不保证有同一业务JSON。O1c应安全重建错误，不能假设每个非200 body可解析为ApiResponse，也不能把内部401传给前端清门户身份。

| 来源/组合 | 新观察GET出口要求 |
|---|---|
| gateway自己可信原Portal明确无效/到期 | 401，固定安全Portal错误；这是唯一浏览器401来源 |
| engine Q/M任意401（包括空body/框架认证响应） | 安全503内部依赖/凭据不可用；不透传WWW-Authenticate/任意body；不重exchange或重试 |
| 404 + 合法`RUN_NOT_FOUND`错误envelope | 固定安全404，未知/他人run同文；不泄露root/owner |
| 403 + `RUN_STATUS_TOO_LARGE` | 保留403与独立code，安全消息；非authority、不得清blocked/grant或触发登出 |
| 403 + 已核授权code | 保留403并按有限安全code/message映射；本次已见`SYS-AUTH-403`、`AUTHORITY_EXPIRED`、`AUTHORITY_CONTEXT_MISSING`、`RUN_AUTHORITY_DENIED`、`MATERIAL_SCOPE_MISSING_OR_CHANGED`；如受控authority实际返回`AUTHORITY_DENIED`，在真实HTTP证据中确认为该固定组合再加入 |
| 403 + `RUN_METADATA_TOO_LARGE`（O1a元数据projection错误） | Q投影/依赖失败，建议安全503，不能伪成M RUN_STATUS_TOO_LARGE或用户撤权 |
| engine400（如INVALID_RUN_OBSERVATION_REQUEST/INVALID_RUN_OBSERVATION_SELECTOR/INVALID_INPUT） | gateway自己已生成固定合法内部请求，故作为内部协议失败安全503；浏览器坏请求应在本地ingress拒绝 |
| engine502/503、未知code/status、非法/超限/重复/trailing/错误字段JSON | 安全503，有限错误消息；不透传内部异常 |

此表是新观察GET的映射，不改旧PublishedRunHttp.get/POST语义。对计划保留的403/404须严格验证错误envelope四字段、data=null、message/trace类型与上界；未知403不能因状态码一样就当可信授权拒绝。上游401/502/503/3xx等可以直接关闭/释放有界响应并本地映射503，不需要为了验证一个不会透传的body无界读取；所有分支不得追踪redirect/authchallenge。

**额外真实风险：** 冻结GatewayIngressBoundary.IngressFilter的286–289把`chain.doFilter`也置于`catch(JwtException) -> reject(401)`内。新Q/M客户端若把内部authority响应/service-ticket/JWK验证的原始JwtException漏出，会绕过上述映射误登出。新固定Q/M方法必须分阶段分类：原Portal decode明确失败→本地Portal401；之后内部凭据验证异常→安全503（明确已验证拒绝仍403）。不能把整个Q/M过程所有JwtException都写成invalidPortal；不需为此重构旧RUN/SESSION全局catch。无法证明原Portal无效的验证服务故障不得伪称门户已失效。

成功M响应：真实wire≤2097152，严格单JSON文档/duplicate/trailing拒绝、ApiResponse OK/data对象/请求runId相同，随后原bytes转发。保持数字词法、大integer/decimal、时间/null/节点事件完整性；不重序列化成功tree。Q wire≤8192，data≤4096使用与O1a投影一致口径；两个版本均positive long，拒绝额外/遗漏字段；不把whole envelope限为4096。trace不转发浏览器自报值，生成none或本地有界trace；不要用非法DTO硬凑不可达边界。

## 4. 新gateway STATUS构造与有限装配：最小确定方案

### 4.1 已有构造事实

`GatewayIngressBoundary`已有三参`(Object controller,TrustedJwtDecoder,Clock)`与四参`(Object controller,Object sessionController,TrustedJwtDecoder,Clock)`；前者委托session=null。精确绑定实际GatewayController.proxy(request,response)实例/方法、void返回；session独立实际handler。STATUS与RUN使用同一proxy handler，不需要第二controller类型或宽handler列表。

`GatewayIamAssembly`真实为无@Component/@Configuration stereotype的final类，只显式import生效。82行全文仅boundary、chain、interceptor、真实filter registration与coverage六个@Bean；没有PublishedRunProxy/metadata/transport bean。Boundary当前由controller+optional session创建。`GatewayApplication`仅正常扫描gateway/common，没有显式import该有限assembly。因此不能为了O1c把它加stereotype或改main解除生产闭锁。

### 4.2 root已同意的最小有限装配增量

1. Boundary保留两个已有constructor默认STATUS disabled；新增固定五参候选`(Object controller,Object sessionController,boolean statusInstalled,TrustedJwtDecoder decoder,Clock clock)`。此boolean仅由实际有限assembly根据status bean存在设置，不来自request/property自报。旧RUN/SESSION不受影响。
2. `GatewayIamAssembly.gatewayIngressBoundary(...)`增加`ObjectProvider<RunStatusProxy>`，仅`getIfAvailable()!=null`时启STATUS；仍精确绑定同controller proxy。没有status bean时STATUS在filter入口即闭锁，POST/session保持既有装配。避免无条件new metadata client要求engine origin，破坏现run-only/session测试。
3. GatewayController新增optional RunStatusProxy依赖；其proxy method签名仍`void proxy(HttpServletRequest,HttpServletResponse)`，boundary反射不改。STATUS只有精确admin GET分支才能`claimStatus`并调用新proxy；POST仍原claim+PublishedRunProxy；其余IAM路径拒绝，无legacy fallback。构造注入若保留四参直接测试兼容overload，新正式注入constructor必须唯一可识别（显式@Autowired或等价），不能产生Spring歧义；回报全部受影响直接构造测试定位。
4. 新PortalStatusRequest private constructor，固定source/subject/runId/anchor/owner capture；`requireLive(actualRequest)`检查claimed后的同frame/REQUEST/路径/invalid状态/期限。不能直接调用现Proof.check的claimed拒绝逻辑。source到期单独401；capture错配/被关闭为明确拒绝，不混为token失效。
5. **具体有限bean配置放新测试Receiver**（按已验N4b2装配方式）：显式import GatewayController/GatewayIamAssembly/PlatformErrors；@Bean唯一具名`runStatusHttp`（PublishedRunHttp，destroyMethod=close）；@Bean RunObservationMetadataClient借该transport与已配gateway authority client；@Bean RunStatusProxy再借同transport/metadata/authority。127.0.0.1 HTTP只在这个测试bean constructor显式literal true；生产公开默认constructor保持HTTPS。不新增生产loopback开关，不为组装新增通用配置层。
6. metadata/proxy只借用transport，不各自close它；proxy.close只将自身closed失效并取消本proxy安装的请求handle，metadata.close只失效自己；独立transport owner bean最后close。Q/M proof依附唯一gateway HttpCurrentAuthorityClient并随其close失效；该client、decoder、authority/engine子进程分别由fixture唯一owner收束。若POST同时作为回归安装，其transport用独立qualified bean，避免两个无名称PublishedRunHttp导致注入歧义或借用方关闭POST资源。

这样已有finite assembly可实际安装STATUS，并真实走guard/handler；新生产业务类参与有限装配，不宣称主应用正常扫描就有完整IAM/POST/status beans。无需改GatewayApplication、SecurityConfiguration生产闭锁、PortalSessionController或添加全局response advice。

## 5. Q/M proof及首写接口收敛

固定新接口候选保留在CurrentAuthorityClient/HttpCurrentAuthorityClient，默认拒绝：

- `exchangeGatewayRunObservationQuery(originalPortal,SubjectKey,publicRunId)` → 私构造GatewayObservationQueryAuthorization。
- `exchangeGatewayRunStatus(originalPortal,ResourceBinding runSnapshot)` → 私构造GatewayStatusAuthorization。

同原portalExchange签名验证私有原语，分别固定Q与M call，不允许调用者传任意ServiceCall或purpose。保存source expiry与current/ticket/grant/operation expiry**分开**，wall与exchange前monotonic预算，owner close及失败锁存。Q/M均是exchange给出的current证明，不再增加gateway-target错误的online authorize；engine各自guard/refresh按O1a/O1b既有实现执行。测试明确统计exchange/authorize调用位置，不能说整个链只有两次authority HTTP。

metadata client只接私STATUS capture或固定已验subject/run信息，结果私构造；严验Q subject/resource与source、response owner/run及acceptedSHA。M新exchange必须用同一originalPortal，并核同source authentication关联、subject/resource；Q/M不同grant/operation。若需比较私证据origin，增加固定包内/受限比较方法或在Http实现内部验证，不暴露raw签名current给调用者拼proof；不为此新建泛化授权框架。

新proxy用同请求同步路径，总deadline五秒不递增，HTTP hop≤两秒/剩余总预算，连接建立后实际send前/每次body读取后/最终读取后均本地proof检查。前后网络不重试/重exchange。validated bytes →安全header（不设置stale成功Content-Length）→实际getOutputStream→同capture/M/source/deadline再检→首次write。无需ResponseLease/异步SSE/磁盘spool；慢peer一次阻塞write内的撤权硬断不在承诺内。

O1b已实测发生write异常后MVC拒绝delegate close，所以O1c不能以“close必然下达”作唯一oracle。保留primary IOException与suppressed close失败，已提交仅停止，不附加错误/成功JSON、不再次GET；真实peer部分bytes/EOF和日志unknown应分别记录。新transport仍复用原Apache5.4.4/core5.3.4无retry/redirect/cookie/authchallenge/compression与header/chunk/trailer边界。既有HttpCurrentAuthorityClient的JDK全链强取消未在本次解决，不顺手改协议。

## 6. 精确生产文件与测试范围

建议正式scope：6份既有生产文件修改、2份生产类新增；root仍以实现前具体API确认锁定。

| 工程相对路径 | 本切片变化 |
|---|---|
| `admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java` | 上述固定STATUS安装/route/capture/claim/live检查；旧构造默认off |
| `admin-common/src/main/java/cn/agentplatform/common/authority/CurrentAuthorityClient.java` | 两固定Q/M方法默认拒绝 |
| `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java` | 私Q/M mint、期限/原source分类、内部JwtException不冒泡401；原RUN/session不改目的 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java` | optional status proxy与有限分派；实际proxy方法签名不变 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamAssembly.java` | optional status bean决定Boundary安装标记，不无条件创建client/transport |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java` | 固定观察GET响应策略；原get/POST策略不改；共享机械HTTP |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunObservationMetadataClient.java`（新） | 严格Q HTTP/七字段/8192与4096/private result |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunStatusProxy.java`（新） | 同source编排、M读取/安全映射、完整bytes首写/close所有权 |

默认不改O1b生产/GraphRules、RunObservationSelectors、ServiceAuthorization/catalog、PublishedRunProxy/PublishedMetadataClient、Json/PlatformErrors、GatewayApplication、生产配置/POM。原proof类型与POST独立，不读票变写票。

测试建议新建：

- common `admin-common/src/test/java/cn/agentplatform/common/authority/GatewayStatusAuthorizationTest.java`：固定Q/M、source/current分类、签名错误、monotonic/owner close；直接调用真实受控authority，无public proof工厂。
- gateway `platform-gateway/src/test/java/cn/agentplatform/gateway/application/RunObservationGetTransportTest.java`：实际rawsocket/Apache协议负例与code映射，仅作为transport证据。
- gateway `platform-gateway/src/test/java/cn/agentplatform/common/authority/GatewayRunStatusChainPostgresTest.java`：有限Receiver真实装配、父gateway JVM+独立engine子进程/PG、原Portal→出口完整链与首写窗口。
- engine `engine-runtime/src/test/java/cn/agentplatform/common/authority/GatewayRunStatusEngineFixtureMain.java`：新专用子进程fixture；只为O1c测试，不改已有POST fixture。真实导入RunController、O1a metadata controller/service、RunService/TraceStore等和实际guard/coverage；schema V200–217、专用DB，生产受理后受信管理通道给run/owner/SHA。scheduler不启动，依赖自有失败端点计数。

旧测试只按变更直接挑选。已核真实名字可用：`PortalSessionHttpTest#normalLegacyStartsWithoutIamClientAndRunOnlyFiniteAssemblyKeepsOriginalBoundary`、`PortalSessionHttpTest#installedExactHandlerReturnsOnlyCurrentSessionWithNoBusinessCalls`；原POST链`GatewayPublishedChainPostgresTest#portalThroughRealMetadataMainRunAppPgAndOwnedFlowReturnsFullStatusAndReplay`可作为直接分派/transport回归。不需要为了本次重跑O1b所有17或旧POST几十个rawsocket方法。

新链关键selector候选（须作者在红之前真正建好并锁定）：`realGatewayQueryAndStatusReturnOriginalBytes`、`internal401NeverBecomesPortalLogout`、`missingStatusBeanKeepsIngressClosed`、`metadataFailureStopsBeforeMainExchange`、`sameOwnerDifferentRunCannotSwapMainTicket`、`statusLimitAndMalformedBackendNeverReleaseBusinessBytes`、`actualOutputStreamAcquisitionRechecksCaptureAndMain`、`partialOutputNeverAppendsOrRetries`。名称是建议，未伪称现成测试。

## 7. 执行建议与真实观测门槛

固定runner、ROOT cwd、Java21、专用m2离线；逗号/#合法，无+。以下是未来建议，不是已执行命令：

1. 具名行为红：`node .tools/run-iam-module-tests.mjs iam-stage-o1c-red01 admin-common,engine-runtime,platform-gateway "GatewayRunStatusChainPostgresTest#realGatewayQueryAndStatusReturnOriginalBytes"`。先确认engine fixture编译、独立module classpath生成和可信seed成功；构造/缺类/setup失败不是行为红。
2. common定向：`node .tools/run-iam-module-tests.mjs iam-stage-o1c-common01 admin-common GatewayStatusAuthorizationTest`。
3. 新gateway收口：`node .tools/run-iam-module-tests.mjs iam-stage-o1c-green01 admin-common,engine-runtime,platform-gateway GatewayRunStatusChainPostgresTest,RunObservationGetTransportTest`。
4. 直接回归：`node .tools/run-iam-module-tests.mjs iam-stage-o1c-regression01 platform-gateway "PortalSessionHttpTest#normalLegacyStartsWithoutIamClientAndRunOnlyFiniteAssemblyKeepsOriginalBoundary,PortalSessionHttpTest#installedExactHandlerReturnsOnlyCurrentSessionWithNoBusinessCalls,GatewayPublishedChainPostgresTest#portalThroughRealMetadataMainRunAppPgAndOwnedFlowReturnsFullStatusAndReplay"`。若runner实有selector语法限制，先按已读实际runner最小拆分，不改runner或伪称同轮通过。

测试child classpath不能直接复用历史`.local/iam-n4b2-gateway-published-chain`文本当新谱系，也不把engine JAR加gateway测试classpath。继承已有Child pattern：真实module自身target/test-classes/classes、admin-common、固定.tools/m2；源码/依赖列表命令事前锁定，在O1c自有目录生成classpath；父只经stdin/stdout控制与实际HTTP通信。既有N4b2 Child明确支持init/command/ready；新fixture可以继承这个机制概念，不复制旧报告/凭据。

真实链必须同时观测：

- 两次same-originalPortal独立exchange、实际签名subject/origin绑定，不泄露rawtoken；engine只收自己的Q/M ServiceTicket，不能在engine看原Portal；无额外gateway authorize，无retry/redirect/authchallenge二次请求、Cookie/第二Authorization。
- Q metadata控制SQL/三根manifest与payload0，错误时M=0；M读真实O1b同RR材料，返回完整status数值原bytes；普通窗口run/node/attempt/event/tool及provider调用不增，受控并发writer另列。
- 200完整字段；Q七字段/long/data/wire上限；M恰2MiB与+1（真实engine过大403/RUN_STATUS_TOO_LARGE，恶意peer超wire503）；404安全隐藏、已知403、内部401→503与真正Portal401分别测。
- unknown/malformed/empty/duplicate/trailing/wrongrun/额外字段metadata、unsafe headers/chunk/trailer均无成功业务字节；合法多头chunked真实成功。错误envelope允许字节，不把wire0错指整socket空。
- source/Q/M/总deadline各阶段、connect后发出前、engine返回后、实际stream获取后检查；wall rollback不延长；owner/capture关闭失效。受控实际response wrapper先取得super stream再改clock/frame，而非内部假hook。
- 自有HTTP clients/decoder/transport/authority server/engine process/parentcontext的明确close、真实PID/port记录；PG不影响其它任务。部分IO的实际peer bytes与unknown日志一致，不承诺物理硬断SLA。

## 8. 仍需root明确的有限裁定与结论

不存在需真实外部环境才能完成接口设计的阻碍；用户已授权隔离端点。root已同意§4的optional安装+测试Receiver bean方案，以及内部JwtException不能落入旧ingress401 catch的要求。正式brief仍应锁定错误表中Q RUN_METADATA_TOO_LARGE及内部400按安全503、新生产8文件与4个专用测试/fixture文件范围、具名红绿命令和真实classpaths准备方式。上述均为常规内部决定，不需重复向用户请求授权。

O1b两项新review发现经窄修复、独立审计/评审完成后，以root正式Accepted标识替换本文的“待审”前置；若review改变selector/Loopback/HTTP异常或source manifest，只针对相关最终delta复核，不能把当前笔记当最终Accepted接口证明。正式brief优先于候选/本文。原O1c提案与旧notes均不改。

## 9. 实际读取清单（冻结来源）

所有物理路径以下列绝对前缀加相对文件定位，避免当前source混入：

- O=`G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-4-o1b-readonly-status/frozen-overlay/`
- D=`G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-4-d4a-portal-session/frozen-overlay/`
- N=`G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/`

SHA/bytes为完整文件身份，内容范围只表示本次真正读取片段/符号输出；不代表全文件审计。O1b report/handoff及root草案/assembly preflight全文读，source-delta/composition全文读；source-after仅指定名/metadata定位，target-after仅hash，未扫描target树。旧候选未重读全文。

| 原件 | Bytes / SHA-256 | 内容范围 |
|---|---|---|
| O `admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java` | 2918 / `3e53c32a47856fc52f0bc61f45ea40fea70143875c0aec9e76274035e44adf24` | 1–41 |
| O `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java` | 49744 / `523d93b484f47065f15d1b998f182bb95d2f1bf79d154bff10685fa65a0f7518` | 窄rg；26–61、193–251、395–410（402–407截断后补读） |
| O `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java` | 26458 / `56c7c98a0c121b964438635a0b98d556625332dcd642bcaed7c2c7e2560f03a2` | status/错误窄rg；66–108 |
| O `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 116314 / `52a45545ebcf9ffd28802057b8e8c42fa09ea39081253a4442c35acc6a97d189` | 1580–1614 |
| O `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java` | 76375 / `2187eea017f65173ce01e23bf268647445d845a3533ca7a43234967734f58516` | proof/API窄rg；351–418 |
| O `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java` | 20003 / `b49d3408c70f3ca553a1a12d4161b1508c60eadddadfeeb316e9a6955c9cf764` | helper/错误窄rg；105–121 |
| O `engine-runtime/src/main/java/cn/agentplatform/engine/domain/GraphRules.java` | 10716 / `be53806c692a450cf83b6d86460bb2964d7b9b2999b616a809cf9d8d5f40f392` | 仅PlatformException错误码rg |
| O `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java` | 57225 / `d8fbe7d3073ccaf263effe9e969147a171adf993a76926752d24da1491fc9ea6` | 测试名/装配/错误rg；215–234 |
| D `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamAssembly.java` | 3607 / `5ca4d980a9936747e34bc699a3090057ccba8bb5fffffa1d64c403a5fd244b52` | 1–82 |
| D `admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java` | 18308 / `2525da73a86ce83f538c03745cec69e8e835965bfa41851eab4bccc3610c2442` | 40–95、280–322，其它沿先前已读冻结发现 |
| D `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java` | 36371 / `7f29e6397283d72c85562a616b8488578475f5cf1071e4bc70471c131b1da412` | 仅错误/query/invalidPortal窄rg |
| D `platform-gateway/src/test/java/cn/agentplatform/common/authority/PortalSessionHttpTest.java` | 18327 / `1ec9dbd9553681d052125b5b5ec9270931c9340f96b79284d9a911d6506a0c67` | 仅@Import/测试名/assembly rg |
| N `platform-gateway/src/main/java/cn/agentplatform/gateway/GatewayApplication.java` | 500 / `6ba9e3dc5640412320a53c749df1c9d269e6ae5ccf5727829b9e32c9624c64ab` | 1–12 |
| N `admin-common/src/main/java/cn/agentplatform/common/PlatformErrors.java` | 1360 / `ad964316ff6af8f810f19689fc10bf363c88ad2d30a5c3edea716b753d3e7443` | 1–32 |
| N `admin-common/src/main/java/cn/agentplatform/common/ApiResponse.java` | 368 / `f3530705dbc359804b109c32dd5d232b7c774f4a6e0b267aa60a002e96c8c765` | 1–10 |
| N `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProtocol.java` | 5776 / `bd2a47118d5d9f1cb9dfe06dbc574c6792f239393fe2e732b21b1d6196b38b2f` | 错误/denied/expired窄rg |
| N `admin-common/src/main/java/cn/agentplatform/common/authority/MaterialAuthorization.java` | 3752 / `0834dc440e06341322cb0e4aeadb17227d3d245735821ca106b48fbfdb54b72b` | 错误/denied窄rg |
| N `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java` | 5283 / `d3a038ba6ef7e2263a685b644753db3abe425ccd42a81e707b9d9cd04df6037c` | 错误窄rg |
| N `platform-gateway/src/test/java/cn/agentplatform/common/authority/GatewayPublishedChainPostgresTest.java` | 32387 / `632b11e58628024a3e154a306f036735f93d1872711f984318fbc4b9396d6c00` | classpath/测试名/bean窄rg；45–100、615–695 |
| N `engine-runtime/src/test/java/cn/agentplatform/common/authority/GatewayPublishedEngineFixtureMain.java` | 13674 / `a1014f155ea7f6acd1b0682073094cb15d9db72a58dda837d805596941241b7f` | main/bean窄rg；177–235 |

本次读前先比对旧N4b2 assembly与新D4a overlay：旧3405字节不匹配，未拿它作最终装配依据。所有新O1b内容只读冻结overlay。未把工具输出截断部分当已读全文。交还后本文冻结，由root据此形成正式brief。