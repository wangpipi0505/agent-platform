# O2b-D 正式任务：仅 common 固定 G/E与私有admission

C及I1/I2已Approved且root有限审计/八索引闭环，最终API已核；root分派后本任务持唯一批准source/有限target写权。唯一工程与每条shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。不改v1.2/四契约/POM，不commit，不部署。

基线：source `.local/iam-o2a-c-fixes/source-after.json` SHA `44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54`；target `.local/iam-o2a-c-fixes/target-after.json` SHA `65646da310fefd754a22b1284854291de3447551ff801a2b8da9f150473eebc9`；八索引checkpoint SHA `52f96f11541de3c4b3f7f66016793857d8c0dd880f810e4bd6131b11da5c3ac1`。唯一作者继承此基线，只更新授权delta，不扫旧target/全库。技术语义以 `docs/evidence/iam-stage-4-o2b-d-decisions.md`（SHA a75b813beb1666d999069a9c2077660ca60f0ecf43d022ebb8a1800a197ad84b）为准；接口/读源/矩阵引用冻结 `.local/iam-o2b-d-decision-draft.md`（SHA 0cc20b42b5877734e01e25fac1b775d3067797706d81fb36e73572a1f27f6789）。

## 唯一范围与交付边界

5个生产文件：目录 `admin-common/src/main/java/cn/agentplatform/common/authority/` 下修改 CurrentAuthorityClient.java、ResponseAuthorityClient.java、HttpCurrentAuthorityClient.java、RunObservationSelectors.java；新增 GatewayPortalEventsResponse.java。3个测试文件：目录 `admin-common/src/test/java/cn/agentplatform/common/authority/` 下修改 LoopbackAuthority.java；新增 GatewayPortalEventsAuthorityTest.java、PortalEventsChildAssociationTest.java。共8文件，不顺改旧测试/fixture。

不改其余文件；尤其不改ingress、业务源码、ServiceAuthorization、AuthorityContext、PortalEventsResponse、catalog。gateway EVENTS继续关闭，不新增G HTTP row或放宽public requirePurpose。D只交付common协议/私有生命周期；Q metadata HTTP、PG/SSE、TLS/manifest、私有wire及Servlet writer属E/F。

root已核C Accepted最终manifest中common/Controller未被fix改动，selector/holder isSuperseded实际签名和engine原open调用确认，见正式决策。D必须使原openPortalEvents拒child票，不能提前进入C公开SSE。

## 固定协议与私有证明

G：action `gateway.run.events.observe`，target/caller=gateway，delegated/PORTAL/PORTAL，严格run-snapshot。E：原 `proxy.engine.get.RunController.events`，target engine/caller gateway。G profile `portal-gateway-events-response.v1`；child profile `portal-engine-events-child-response.v1`。旧A `portal-run-events-response.v1`与无parent规则不变。

操作固定G `portal_gateway_events_open/check`，child `portal_events_child_exchange/open/check`。continuation/child-open/receipt的typ、token_use、aud逐字采用root决策稿“continuation固定G typ”段，不自行改名；各自最多55秒，外层decision用当前认证服务配置audience。严格权限时间不因verifier skew延长。

正式批准 .local/iam-o2b-d-decision-draft.md §3候选API（含root补充wrapper isSuperseded）：begin/openGatewayPortalEvents、exchange/openPortalEventsChild、checkGatewayPortalEvents及原checkPortalEvents。私有proof类型和fail-closed defaults不变。

begin真实decode originalPortal、取得真实subject后做Q exchange；Q仅查询授权。snapshot参数不是cap，G仍须同source独立exchange＋actual authorize。G ticket只通过新私有固定validator；现有route allowlist不接受G，不能为方便添加public例外。普通exchange/resume/forward/client不得mint G能力。

G admission仅由实际验签current、同client owner、原opening/Q/source mint。G wrapper只接受HttpCurrent私有证据，复用holder，不导出delegate/Actor/VA/Retained/continuation或接受caller时间造权。提供fresh capture/current/material及C同义isSuperseded：同锁先current/owner，旧代true、同代同evidence false，foreign/future拒绝。

E signed ticket与actual authorize current须一致携带child marker/关联摘要，才保存到Retained私有证据。child open核actual client、同真实receiver frame/current、原first anchor及一次claim；standalone open拒任何child marker，child open拒缺失/错配/foreign frame。包内测试可绑定actual Retained模拟receiver seam，不称真实engine HTTP成功；禁止currentOnly/VA bind/重签正例绕过。

## 一父一子、身份和时间

真实authenticated issuer原子维护 `NO_CHILD→RESERVED→OPEN`：G open确认parent；child_exchange核同源/关联/当前策略并保留唯一child/E grant-ticket；engine child_open消费真实E decision/ticket/关联，签receipt；gateway真验预期child。无retry/重开/第二child；client锁不代替issuer防跨frame重放，G continuation不传engine。

关联固定双方response/profile/action/target/caller/ticketJti/grant/op/各自initial-ceiling，同subject/source/origin/authenticationId/source摘要/run-snapshot/logical requestSHA/cursor，opening decision/nonce/各自raw requestSHA及不可递增deadline。G/E版本和roles可不同，分别使用fresh current；不能强制opening版本全等或借另一hop current挽救失效。新observer与历史run origin_authentication_id可不同，仅本轮Q/G/E须同真实原Portal。receipt不是G许可，也不认证后续任意manifest字节。

issuer容量64 parent、每parent最多1child；有效重放窗口内不为腾容量逐出消费记录，只在严格hard过期后有限清理，不建无界JTI缓存。正常LoopbackAuthority endpoint真实签发/验证，不能放宽ANY或动态信任请求自报owner。外部生产issuer支持是交付前置，不宣称fixture通过即已部署。

Opening自真实decode/Q开始固定单调锚点，总opening最长5秒，未来metadata/G网络也含内；自动到期、异常、放弃close、transfer CAS均有owner。Opening→G转移同一有限slot；不得未转移就永久占位，也不得active调用未退出先释放。复用原有bounded runtime，不另复制scheduler。

G hard=min(begin＋55、原source、Ggrant、Goperation)；E不晚于已签G hard、自身source/grant/op与receiver原始55秒。本地单调预算只能缩小，refresh/晚open/新hop不重置；短current remaining不是55秒连接寿命。服务JWT过期后，仅原合法holder可按专用check续核至原hard界；关闭观察不取消run。保留原实际退出、late result、close/stop/install/generation规则，不承诺强断阻塞peer或撤权SLA。

## 实施、验证与即时证据

应用TDD/verification：核scope/基线→真实签名HTTP fixture行为红→确认缺目标能力→授权生产实现→对应绿。每阶段立即保存完整命令/exit/stdout/stderr、actual XML/txt、hash/manifest/journal；不末尾补记，不因metadata失败重跑已成功测试。不跑PG/全仓，不引入版本变更。

新两类按冻结稿§8矩阵验证真实Q/G/E签名HTTP及actual receiver Retained；重点包括签名/绑定/marker负例、跨父子跨frame重放与并发双child、standalone隔离、新observer、票到期续核/硬界、capture/generation、Opening丢弃/transfer、阻塞退出/queue/close/shutdown及issuer容量。正例不伪重签或放宽gate。

最终必要common回归：新两类、A旧standalone profile/生命周期、selector、ServiceAuthorization。实际类名已锁定，见下方精确命令；不改旧测试使其绿，失败归因后仅在授权范围修。

首次非空private raw patch即在干净Accepted私有基线做真实check/apply，保留原始patch和命令；不末尾补造apply、apply后copy或commit。finite target仅收实际本轮createdFiles/inputFiles/resources、fixture JAR、actual Running测试XML/txt；新增dumpstream单独归档，不能纳overlay/历史逻辑target；增量核源名称/bytes/SHA，不扫旧大树。每阶段完成即固化证据；最终提供pre/post source、finite target、首次rawpatch/replay、执行journal、报告/handoff和self-inventory（仅自身清单可唯一自排除，含私有Git证据）。自有证据目录由正式brief指定；归还写权后冻结，不将作者交付称Approved。

## 残留门槛

无已知语义冲突。C限定切片已Accepted，禁止重派/重跑旧任务。若runtime无法在8文件内安全完成Opening自动到期/slot转移，须报具体阻碍，不削弱生命周期或暗扩scope。外部issuer及E/F边界不变。
## 具体执行与交还

适用AGENTS、implementation-brief、contracts README，Java21/Boot3.5.0/Security6.5及现有固定依赖；跨模块只common/test-fixture与HTTP，不业务JAR。自有local `.local/iam-o2b-d-common/`，evidence `docs/evidence/iam-stage-4-o2b-d-common/`，helper `.tools/iam-o2b-d-*`，新报告 `docs/evidence/iam-stage-4-o2b-d-common-report.md`。不得修改C/任何历史report、review、inventory、journal或原8索引，root在本任务独立Approved后收口。原M1/M2与retention等限制继承，不重跑或补造旧阶段。每次实际测试前source epoch，退出后即时raw/XML/txt和命令/退出，成功物理阶段journal/cache，不重复成功阶段。保留pure readonly准备稿，无新服务部署/环境配置。

唯一未改runner；先focused真实功能RED→实现→相同focused GREEN，固定最终epoch后只一次最终精确集合：

node .tools/run-iam-module-tests.mjs iam-stage-o2b-d-final admin-common GatewayPortalEventsAuthorityTest,PortalEventsChildAssociationTest,PortalEventsAuthorityProfileTest,PortalEventsReceiverClosedTest,RunObservationSelectorsTest,ServiceAuthorizationTest

此为common有限回归，不运行engine/PG/旧29/旧24/旧B65/全后端。若新Loopback语义导致既有类真实失败，先定位是否实现误改旧A，不能擅改旧测试或把setup当功能RED。source基线600，预期新增3（1prod+2test）后603，delta8；只更新批准范围，不全树hash旧source。latest source物理baseline从 `.local/iam-o2a-c-fixes/private-replay/` 按600项具名复制，已root物理verified，无需重复原root审计。target历史逻辑2096，只覆盖实际common compiler两阶段/资源/common tests JAR/actual Running6类报告；新的诊断单独归档，不以继承engine历史记录声称当前已编译。最后首次私有check/apply、source名称bytes/SHA一致，无apply后copy；own-inventory仅自身排除且含私有Git，交权后不改。返回短status、实际测试数、report/source/target/handoff/inventory identities，下一fresh independent reviewer/root有限审计；作者完成不等于Accepted。
