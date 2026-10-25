# O2b D 固定协议与私有生命周期正式决策

依据只读D决策草案（24448B/SHA0cc20b42b5877734e01e25fac1b775d3067797706d81fb36e73572a1f27f6789）及root已完成的实际API核对正式冻结。C及I1/I2修复现已Spec/Quality Approved C0/I0/M2，有限审计和八索引闭环；D是下一独立任务。本文不改v1.2/四契约，外部issuer仍待实际交付。

正式scope采用5prod+3test；G action gateway.run.events.observe，target/caller gateway，delegated/PORTAL/PORTAL，run-snapshot；不增加public requirePurpose或catalog row。G profile portal-gateway-events-response.v1；E child profile portal-engine-events-child-response.v1；旧A portal-run-events-response.v1及无parent分支严格保持。G操作 portal_gateway_events_open/check；E子操作 portal_events_child_exchange/open/check。只固定private G ticket-validation入口可接G，普通exchange/resume/forward不得mint G。不是公开内部路由例外。

continuation固定G typ=agent-portal-gateway-events-continuation+jwt/token_use=portal-gateway-events-continuation；E child typ=agent-portal-engine-events-child-continuation+jwt/token_use=portal-engine-events-child-continuation。child-open typ=agent-portal-events-child-open+jwt/token_use=portal-events-child-open；receipt typ=agent-portal-events-child-receipt+jwt/token_use=portal-events-child-receipt。各自最大55秒，严格权限时间不因verifier skew延长。aud明确为urn:agent-platform:service:engine（child-open/E）和urn:agent-platform:service:gateway（receipt/G），外层decision仍为当前认证服务配置audience。root仅为该aud风险窄查Initial frozen AuthorityProperties audience约束及HttpCurrent verifiedTicket402–451，确认既有serviceaud规则；未读C半成品。

采用真正私有GatewayEventsOpening→G opaque wrapper→私有child exchange authorization→实际receiver Retained child evidence。Q是待未来真实metadata HTTP用的私有查询授权；resource参数不是许可，G仍独立exchange+actual authorize。Opening固定从真实decode/Q开始记录原单调锚点，最长opening5秒（future metadata/G网络均含内），不得caller传起算时间/截止。Opening与G转移同一有限slot，不另占无限资源；需自动到期、异常、放弃close、真正阻塞调用退出与transfer CAS的有限生命周期，不能让未转移Opening永远占slot。若复用runtime确需超scope，具体报告root；不擅自复制另一套scheduler或改变A语义。

G/E本地holder复用原生命周期但固定封闭mode分支；wrapper不导出PortalEventsResponse delegate/VA/Actor/Retained/continuation。wrapper需支持fresh capture/current/material和必要同holder本地isSuperseded，语义沿C通过后的原方法；不能把短current remaining当原55秒连接寿命。G硬界取begin+55/source/Ggrant/Goperation最小；E不晚于已签G硬界和自身源/grant/op/receiver原始55，单调预算只能缩小。source/G/E各自current独立，版本/roles可不同；一个hop的fresh current不能挽救另一个hop。

authority必须在正常authenticated fixture endpoint真实签发/验证并原子消费一父一子：NO_CHILD→RESERVED→OPEN；没有自动重试/重开/第二child。client同样保留一次状态，但不能以client锁冒充issuer防跨frame replay。固定容量64 parent（每parent最多1child），有效重放窗口不得为容量逐出消费记录，过严格hard界后才有限清理。真实外部issuer支持仍是交付前置，不声称D已部署生产authority。签名ticket与实际authorize current须同时一致携带child marker/关联摘要，私有Retained才保存；standalone open显式拒任何child标记，child open拒缺失/错配/foreign frame。E服务JWT只控制受理，原合法holder可按专用check续核至原hard界。

关联至少固定双方response/profile/action/target/caller/ticketJti/grant/op/initial-ceiling、同subject/source/origin/authenticationId/source摘要/run-snapshot/logical-requestSHA/cursor、openingdecision/nonce/requestSHA与不可增加的deadline；本轮source与历史run origin_authentication_id可不同。G/E raw authority requestSHA各自独立，不把logical cursor规范值冒充原始HTTP摘要。receipt只绑定唯一child完成opening，不是G权限，也不认证任意后续manifest字节。

D只验证common protocol/私有生命周期、normal signed HTTP issuer/JWKS与包内真实Retained测试seam；实际gateway EVENTS ingress仍关闭。TLS/完整私有packet与预算/真实gateway材料验证和Servlet writer在E/F另行正式冻结。不得把签名关联当TLS/字节认证或声称Q metadata/PG/SSE全链通过。root已核C最终source manifest中common/Controller未被fix改动、selector实际purpose、holder isSuperseded和engine调用原open签名；C fixes仅Reader/Service，已独立Approved并完成有限审计及八索引。

## 已完成的Accepted C API核对

只读 .local/iam-o2a-c-root-review/after/ 的不可变快照，非正在修复的工作树。selector1–56完整：public requirePurpose对run-snapshot仍仅engine STATUS/EVENTS，因此G必须严格private validator，不可复用observationProof中的publicpurpose分支。holder1–100及具名public签名：currentCapture、requireLive(Capture)、isSuperseded(Capture)；Capture公开subject/resource/responseId/initialCursor/remainingNanos/requireMaterial(MaterialScopeV1)，G wrapper应对应这些真实签名且不泄漏delegate。固定isSuperseded同锁先requireCurrent、同owner、旧代true、同代同evidencefalse。Service冻结行41–44实际仍以原initial+PortalEventsBinding调用authority.openPortalEvents(initial,binding,owner::stop)，没有child分支；D standalone拒child可保护此旧入口。HttpCurrent Initial frozen具名requirePurpose定位仅190，固定EVENTS校验640/789；不是所有private authorize自动走publicpurpose。C fix仅Reader/Service；root已在Accepted最终600清单核common/Controller不变，并在冻结service快照确认原open调用不变，无需重复读这些代码。
