# O2a A 阶段 API 基线与关闭门禁核对

状态：只读接口核对，未实现、未测试。使用原 O1c 冻结 source585/overlay12及accepted lineage；O1c仍待I1窄修复与最终Accepted，不能把本文件当作已确认最终基线。root已读的O2三份候选保持冻结。唯一新增本文件；未读活动source/target或测试正文，未运行服务/测试/probe。全部命令workdir为 `G:/207/项目/算法重组工具/代码/agent-platform`。

## 1. 本次结论：A可以common-only独立交付，receiver继续关闭

root已选择唯一方案：**A保留 `RunObservationSelectors.requirePurpose` 的receiver规则为STATUS-only，不将EVENTS接入；C安全handler整体就绪时才一起扩receiver目的。** 不同时增加一个备用硬拒flag/新readiness框架。

A只实现固定PORTAL response签名fresh current/private holder及common测试。新EVENTS response-call校验与HTTP receiver准入校验有不同用途；前者允许内部已验证admission开启固定响应能力，后者在A仍明确拒绝run-snapshot EVENTS。不得为了让common测试方便而扩receiver规则。

`EnginePublishedInvocationGuard` 是先前名称误称，不存在已定位的独立生产类。本次实际查证的是 `ServiceAuthorization.mappedInterceptor/preHandle` → `RunController.events` → `TraceStore.run/protectedRun`。root另确认fixture中的observations interceptor只是计数；本文没有读取它，也不把测试计数器当生产防线。

## 2. 实际 HTTP 调用链与关闭点

### 2.1 现生产入口

`ServiceAuthorization.mappedInterceptor()` 38–39返回覆盖映射的MappedInterceptor；`preHandle` 55–64从已验JWT形成ServiceCall，先执行 `requireRetained(call)`，成功后才 `AuthorityContext.bindRetained(current, refreshSupplier)` 并存frame。

`requireRetained` 75–88首先调用 `route(jwt,call,"delegated")`，之后才在78行在线 `authority.authorizeRetained`。`route` 103–106在路由表匹配前调用 MetadataSelectors 与 RunObservationSelectors.requirePurpose；后者29–33明确：resource.kind=run-snapshot时，action必须STATUS，target engine、caller gateway、delegated且source/origin PORTAL。EVENTS即使catalog行、JWT签名、subject/resource都正确，也在这里拒绝；不会进入online authorize或绑定frame。

如果将来仅把EVENTS加入这个receiver规则，catalog已有admin/debug/v1三个events alias，同名action均可匹配；ServiceAuthorization自身并不知道安全events handler是否就绪。不能把catalog存在误当新读能力已安装。

### 2.2 旧 handler 的额外拒绝不是 readiness 门禁

冻结RunController.events:197–209直接Actor.current、`store.run(id,actor)`，随后解析cursor并订阅；没有独立IAM EVENTS安全分支。TraceStore.run:64–74先 `protectedRun(id,actor)`；protectedRun:277–287只查同owner/tenant run是否存在run_authority。存在则拒绝，不存在run返回404；未受保护legacy run随后会进入 `select * from engine.run`，再进入旧裸事件SQL/写出循环。

因此正常已受理IAM run目前还有这一拒绝，但它不验证EVENTS call的run-snapshot与URL run一致，也不是“新handler未就绪”的稳定门禁。若receiver被提前放开，而同owner legacy run没有run_authority，旧路径存在继续读/订阅的可能；即使authority通常不签该资源，也不能把这一假设当下游readiness证明。故A保留上游receiver闭门，不能依赖protectedRun作为唯一安全措施。

GatewayIngressBoundary原O1c冻结版本只有RUN/SESSION/STATUS；102–113的精确route也未允许events。这是gateway当前的额外关闭状态，不足以替代engine直接HTTP入口的上述闭门。

### 2.3 A的可验闭门不变量

A后 `RunObservationSelectors.requirePurpose` 的既有方法体保持STATUS/Q行为，新的EVENTS常量/helper不得被该方法调用。测试用实际签名run-snapshot EVENTS票据访问已装ServiceAuthorization的mapped receiver，须在route阶段失败，online authorize次数0、frame绑定0、业务handler计数0。admin/debug/v1、错误method与跨run请求均不能因新增response API而通过。

common receiver测试只证明真实Servlet/interceptor门禁，业务handler使用有限计数sentinel，不声称已运行真实engine旧SQL。实际engine闭门/ready切换和PG payload0在C由真实engine HTTP测试补齐。production调用链的源证据已明确该route拒绝发生在Controller之前。

## 3. O1c实际新增接口与旧response API接缝

| 接口/实现 | 本次确认的实际能力 | 对A的结论 |
|---|---|---|
| CurrentAuthorityClient `exchangeGatewayRunObservationQuery(String,SubjectKey,String)` / `exchangeGatewayRunStatus(String,ResourceBinding)` | 两个default拒绝方法；实现返回HttpCurrent里的私有Q/M proof类型 | A不改此接口，不增加gateway EVENTS exchange wrapper；O2b再处理 |
| HttpCurrent `observationExchange`:177–197 | gateway-only原Portal decode→真实exchange→source/subject/origin绑定；内部Jwt/expiry按既有类别归一 | 不是engine response-open能力；保留原行为 |
| `ObservationProof`:199–216 | 私有owner HttpCurrent引用、sourceDigest、exchange证据；source/internal期限与单调elapsed终检；失败终态 | 只有本地短窗口，没有response续核；不得延长成SSE |
| Q/M public opaque wrappers:218–239 | private constructors；Q/M `.ticket()/requireVerified()`；M.requireSameQuery核同owner实例、同原sourceDigest/origin/subject/run，且grant/operation不同 | 可继承“同owner不能混proof”的原则，不能借Q票/STATUS proof mint EVENTS holder |
| `authorizeRetained`:298–331 | 实际decoder、签名current query、ticket jti/caller/grant/op/version/initial/origin/expiry逐项核验；private constructor生成Retained | 是A生产open可接受的验证来源；currentOnly不足 |
| `RetainedAuthorization`:342–370 | private constructor；public currentOnly无initial evidence；requireVerified核current/原ticket期限。当前没有minting HttpCurrent owner字段 | 不能直接跨线程作为持久材料能力；A需明确source owner与同frame mint |
| `AuthorityContext`:28–69 | package-private bindRetained；requireInitialFor在线refresh后替换frame mapping；requireBound按对象identity核仍为当前mapping | 已有可信绑定点够用，不新增可从Actor/VA绑定initial的公共工厂 |
| ResponseAuthorityClient | 现只有 `open(VerifiedAuthority,ResponseBinding)`、`check(ResponseLease)` | 保留旧API；A增加固定PORTAL sibling方法，不改旧drain语义 |
| HttpCurrent旧open/check:455–507 | 真实response_open/check、nonce/request签名、consumer与opening/call绑定；ResponseLease只存checkedUntil等，PORTAL无fresh材料current字段 | 不能原样用旧lease延长过期openingAuthority；新profile解析独立 |

一个需明确区分的实际字段：`HttpCurrent.query`:438–445生成的外层 `request_sha256` 是**本次authority HTTP request对象的摘要**，与nonce/client/call一同验证；Retained中的requestSha256来自该在线验证。它不是浏览器原URI摘要，也不是观察cursor的逻辑摘要。A的PortalEventsBinding应另有明确的 `observationRequestSha256`（或同等不混淆命名），嵌在profile binding中签名；绝不能覆盖原query.request_sha256校验。B/C才接规范cursor解析，A只验证binding的受限数值/资源及profile一致性。

## 4. A唯一建议的exact production scope

路径统一相对 `admin-common/src/main/java/cn/agentplatform/common/authority/`。建议固定为**3个现有production文件＋2个新production文件**；不改engine/gateway/catalog/AuthorityContext/MaterialAuthorization。若实现发现需要额外文件，须用具体阻碍报root，不自行扩大。

1. **RunObservationSelectors.java**：增加EVENTS_ACTION、events资源纯构造以及package-private `requirePortalEventsResponseCall(...)`。后者只供新HttpCurrent固定profile入口与受控authority fixture验证engine/gateway/PORTAL/run-snapshot目的；不是HTTP receiver gate。现public `requirePurpose` 方法的STATUS/Q规则不改，旧非新目的行为不扩不缩。
2. **ResponseAuthorityClient.java**：增加下面固定方法，默认明确拒绝，使旧实现不会意外获得能力：
   - `PortalEventsResponse openPortalEvents(HttpCurrentAuthorityClient.RetainedAuthorization initial, PortalEventsBinding binding, Runnable cancel)`。
   - `void checkPortalEvents(PortalEventsResponse response)`。
   原 `open/check`保持。check只更新同owner实例下的已验证response，不返回可伪造的public当前角色record。
3. **HttpCurrentAuthorityClient.java**：实现上述方法与严格独立的PORTAL事件profile解析、私有admission/evidence工厂；不改Q/M/POST的purpose和时限。对Retained私有构造添加一个**仅内部的minting owner引用**：actual authorizeRetained用this，currentOnly用null。构造仍private，旧公开方法与requireVerified语义保持；只有新open使用owner identity。此字段不是额外claim，不改变已验来源事实。
4. **新增 PortalEventsBinding.java**：有限不可变请求值，包含responseId、run-snapshot、有效cursor、observationRequestSha256、最初admission锚定deadline等明确字段；所有长度/格式/范围受限。它自身是数据，不是admission或permission；不会提供open/token/Actor转换。
5. **新增 PortalEventsResponse.java**：final opaque holder与嵌套Capture/内部generation状态。构造非public，只有同包接收**HttpCurrent私有构造生成的opaque evidence**的工厂；没有接收Actor/VA/Map直接成功的构造。包含owner实例、binding、固定来源/操作、签名current、停止位、单调期限、单claim、单飞renew与取消/watchdog生命周期。

MaterialAuthorization现同包的validate/cover等纯规则可直接复用，无须A修改文件或新建MaterialPolicyEngine。新Capture.requireMaterial只证明本capture下owner/格式/摘要/策略覆盖，不能证明scope来自真实PG或完整parent floor；后者仍由B实际reader验证，才可mint engine EventEmission。

A的test scope建议：修改现 `admin-common/src/test/java/.../LoopbackAuthority.java` 的固定PORTAL事件open/check/current响应与有限E exchange/authorize fixture分支；新增 `PortalEventsAuthorityProfileTest.java`、`PortalEventsReceiverClosedTest.java`。不读/改O1c正在修复的transport test，不新增POM或生产配置。具体type/method名称可由root写正式brief后固定，但上述mint/consume和五文件边界不能因改名弱化。

## 5. 真正可mint admission的顺序与可跨线程边界

### 5.1 production open 的唯一来源

新 `HttpCurrent.openPortalEvents(initial,binding,cancel)` 内部顺序：

1. 本client未关闭，mode=iam、serviceId=engine；`initial`必须有private验证证据且minting owner==this。public `RetainedAuthorization.currentOnly(VA)`、另一个HttpCurrent实例的Retained均拒绝。
2. `AuthorityContext.requireBound(initial)`：必须仍是当前真实receiver frame中的同一mapping；`initial.requireVerified()`仍满足原ticket/current期限。若调用方先用requireInitialFor刷新，必须传刷新返回对象，不能传旧mapping。
3. 从这份已验current校验caller=gateway、target=engine、action=EVENTS、source/origin=PORTAL、subject/operation/grant/resource；binding资源必须等于call与operation资源，cursor/request摘要有界，deadline不扩大原source/grant/响应窗口。snapshot与PG run真正关联由B核，common不假称有数据库owner证据。
4. 仅在此方法内部创建 **private nested PortalEventsAdmission**，捕获minting client、原mapping identity、已验call/source/nonce/request证据、原线程/开启时间；这个内部类不导出工厂，也不允许调用者补initial/origin/clock。它只用于本次open，不能从Actor/VA/DTO装配。
5. 事务外实际authority open；签名/nonce/外层requestSha/client/call全部核验，再核独立观察binding、opening/response/source/grant/操作、fresh current及continuation用途。完成网络调用后再次requireBound(initial)、检查原ticket/source等opening边界，防open期间原frame已失效或被替换。
6. 只有验证成功且原frame仍有效才mint `PortalEventsResponse`。由它获取的Capture之后可以在另一线程使用；不把Retained/AuthorityContext传给worker延长生命。

这个过程中没有“任意current的public工厂”。future C调用者是实际ServiceAuthorization已绑定的同owner HttpCurrent对象；controller/service在原frame内调用open。finite assembly必须将同一个HttpCurrent实例同时作为current-authorize与response-client提供，不能两个不同实例混用，再以“配置一样”绕过owner检查。

### 5.2 common测试如何合法建立frame

现有 `AuthorityContext.bindRetained(RetainedAuthorization,Supplier<RetainedAuthorization>)` 已是package-private，位于真实AuthorityContext.java:28；production调用者是ServiceAuthorization:63。A不新增或放宽绑定点。

common包内测试可先让真实authority/JWKS签发E，再实际调用engine HttpCurrent.authorizeRetained(E,call)，取得private constructor产生的真实Retained；然后仅在测试receiver seam用现bindRetained绑定它，并提供实际authorizeRetained refresh supplier；如需刷新，通过requireInitialFor获得新mapping后再调用open。没有直接newVA/手填claims替代actual authorize。测试说明必须写“可信包内receiver seam＋实际authority HTTP”，不能称HTTP engine events成功，也不能把这段test binding移为public生产工厂。

与此同时独立的ReceiverClosedTest用正常mappedInterceptor请求，仍因STATUS-only规则在route阶段拒绝。这两个测试目的不同：前者验证common新profile，后者证明它尚未接通旧业务handler；不得为了打通前者改后者。

### 5.3 holder跨线程，但同owner/同response

只有新holder/Capture可跨线程，Retained/AuthorityContext不延长。每次消费核同client owner、holder/generation/binding、有效current与stop；同tenant/principal并不足够，另run/response/client实例均不可混用。check只允许owner==this；迟到renew不能复活已关闭holder。生命周期、短current与硬期限沿已冻结O2a notes，不在本文件重复展开。A不实现HTTP cursor parser、PG、writer或O2b parent/child。
## 6. A验证矩阵及B/C必须接的门禁

A最小证据分两组：profile组使用真实签名E→actual authorize→包内receiver frame→open→跨线程Capture/check；拒绝VA/currentOnly、无/旧/关闭frame、不同minting client、Q/STATUS/POST或错误来源资源、签名/绑定错误、迟到renew与closed重放。receiver组使用正常mappedInterceptor，断言新profile存在后EVENTS仍在route阶段拒绝、online authorize/frame/handler均未发生；旧Q/STATUS/POST行为保持。这是common能力与关闭证明，不声称engine HTTP/PG已跑通。其余生命周期/材料用例引用前份O2a notes，A不扩大范围。
**B依赖：** bounded metadata/initial projection、真实父scope全部校验、实际snapshot/event payload摘要、private EventEmission与root批准的有限容量策略。B仍不改receiver STATUS-only规则，也不把common Capture直接视为PG证明。A不实现8MiB预算、SQL、slot或writer。

**C唯一打开动作：** 原receiver目的扩展与实际IAM admin events安全dispatch/readiness检查同一正式scope交付，并在实际engine HTTP/PG上验。只有安全events service/slot/holder/reader/writer完整装配，且精确admin GET/规范cursor绑定满足，才进入新handler；否则拒绝，不fallback旧store.run/裸SQL路径。debug/v1共享action仍显式关闭。既有requirePurpose放行后，此精确readiness拒绝应在Controller入口/新的专用dispatch调用、任何legacy store.run之前完成，并测试缺bean与未就绪构造。A没有提前建立第二门禁机制。

C机械容量按root建议沿ChunkBus窄slot-only lease＋完整frame capacity计费，旧用户语义不变；不新增独立无限额度旁路。这些仅记录依赖，不加入A的exact scope。

建议A测试命令（尚未运行，名称需正式brief固定）：

`mvn.cmd -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=PortalEventsAuthorityProfileTest,PortalEventsReceiverClosedTest" -Dsurefire.failIfNoSpecifiedTests=false`

相关现有common regression由正式brief按已有类名补入；本文未读取test正文，不伪列已验证方法数。A不以真实PG/完整HTTP engine成功作完成标准；这些属于B/C且仍未实现。

## 7. 冻结基线、实际读源及Accepted后复核

原O1c `handoff.json`称source585/backend393/frontend192，source delta12（改6＋增6）；composition继承O1b fix final01/source2→O1b10→O1a10→D4a/I1→N4b2，再覆O1c frozen-source。物理清单已核：

- `docs/evidence/iam-stage-4-o1c-gateway-status/source-after.json` 111133 B / `047196ce0cfc82590e6fb68b7bba71a0ad99d9a17e493284711b6dcc529edd7e`。
- composition.json 772 B / `aa34ca3dc9d357c3cad733a8d207fa9d240be57f9cb4e52f2d242926d041f0c1`。
- source-delta.json 4725 B / `49952d053ab9c60f50b34228ef87df88164a2c8b07f916963cee244e92fc0ced`。

root报告原冻结有限审计PASS2192、freshreview I1待窄修复；writer仅PublishedRunHttp＋其transport test。本文没有阅读这些修复中的文件或旧target。Accepted后仍须比较最终manifest，不能仅凭“预计不改common”省略最终确认。

前缀 C=`docs/evidence/iam-stage-4-o1c-gateway-status/frozen-source/`；A1=`docs/evidence/iam-stage-4-o1a-run-metadata/frozen-overlay/`；B=`docs/evidence/iam-stage-4-o1b-readonly-status/frozen-overlay/`；F=`.local/iam-o1b-review-fixes/final01/source/`；N=`docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/`。均相对唯一工程根，表中均为实际物理路径而非current简称。

| 来源文件 | bytes / SHA256 | 本次范围 |
|---|---|---|
| C admin-common/src/main/java/cn/agentplatform/common/authority/CurrentAuthorityClient.java | 1909 / `67673327873db463dd277905323fb3b06910161cebc294282cf0720ffcc5d0f0` | 全小接口 |
| C 同authority包 HttpCurrentAuthorityClient.java | 42373 / `5464fdd4a3654ce543f1da7c4697a4bbcdacb628b4c912d05a632e8b2ad05765` | rg具名API；166–239、292–377、386–405、417–453、455–509；未读全文件 |
| C 同authority包 GatewayIngressBoundary.java | 21602 / `ea1735f7f099bfb65b9ba4b2dd4c27dde616c1cf97dd7286a2a7c88ec5a70698` | rg purposes；98–114 |
| A1 同authority包 ServiceAuthorization.java | 9322 / `8b97f99496135e8d3af0f874528f536a1164a7d285ad0b03c7feff4197727281` | rg方法；16–125 |
| B 同authority包 RunObservationSelectors.java | 2918 / `3e53c32a47856fc52f0bc61f45ea40fea70143875c0aec9e76274035e44adf24` | 8–38 |
| N 同authority包 AuthorityContext.java | 5283 / `d3a038ba6ef7e2263a685b644753db3abe425ccd42a81e707b9d9cd04df6037c` | rg方法；9–30、28–77 |
| N 同authority包 ResponseAuthorityClient.java | 294 / `a52d6e4f1eda133ba5a76f718be1f311350cea144b6163efc65965f82dfeb1ef` | 现6行接口 |
| N admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java | 17013 / `f864681861e0d777b2b51489767e60408b6a6fa6307cfa0c1b4120b19ad9ff7a` | 具名rg；185–244附近serviceSecurity；未读全文件 |
| F engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java | 27185 / `06ee90387fb6fb47e82695f59d288a14c56783a34d53cfd3c8bb7ba3f46ba366` | 本次rg IAM/status/events/guard；197–209及裸SQL窗口沿既有精确冻结窄读，不重复整文件 |
| B engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java | 76375 / `2187eea017f65173ce01e23bf268647445d845a3533ca7a43234967734f58516` | 具名rg；60–83、273–287、1047–1062 |

为纠正误称，只在source585清单筛类名/engine文件名，并在已定位冻结RunService/RuntimeClients做EnginePublishedInvocationGuard具名rg，未发现该类；没有扫描全仓或读fixture测试正文。ServiceAuthorization首次猜B路径不存在，按实际lineage定位A1并核SHA；未以缺文件作为安全结论。

最终Accepted复核只需：确认上表common/engine hashes仍相等，review修复确实只在transport范围，及最终source manifest/冻结优先级；若有差异，只读新冻结差异范围后调整正式A scope。本文不修改旧O2 notes，不重复展开SSE协议/容量参数。

交还后本文冻结；root负责正式A brief与Accepted门槛，当前不授权实现。