# O1c gateway 联合 GET status：只读候选

候选、未实现、未测试；只供 root 在 O1b Accepted 后最终化正式 brief。本文新增一份提案，不修改原提案、source/target、执行索引或 review。唯一工程及每条 shell workdir 均为 `G:/207/项目/算法重组工具/代码/agent-platform`。

## 1. 有限范围与继承事实

仅原 PORTAL → 真实 gateway `GET /admin/runs/{id}` → engine Q metadata HTTP → 同 originalPortal 独立 M exchange → engine status HTTP → gateway 完整有界响应。仅完整受保护 published pure FLOW；不扩 SSE、INTEGRATION、RR1/RR2、CHAT、技能/模型恢复、列表、debug/v1、后台执行。原 POST 的 GatewayRunAuthorization、PublishedRunProxy 与 app-version 执行根保持；status 不能变写票。原 v1.2/四契约/固定依赖/生产闭锁不动，无迁移、POM、生产配置或部署。

本次已接受基线为 O1a source578（后端386/前端192）、target1948。source manifest SHA `719caa92b7dc1c33ee620e923247bb57bad45cee5c0b9c210401dc9fde463510`，target manifest SHA `1404555e5bd1f0adfca1a3f74077d952dc9e9f62097a99dc60bf0ed2eed7bda2`。O1b 唯一作者仍在实施，本文没有读取其当前八文件、GraphRules、新测试或任何半成品；未来 O1c 的实际基线必须换成 O1b Accepted manifest，不能仍用578冒称最终值。

root 已冻结 O1b 输出补充 `docs/evidence/iam-stage-4-o1b-output-budget-supplement.md`（4772 bytes，SHA `88c895386a77977920f31af324865cd6eb6d159bbcbb79f4bcdbf7985e68b335`）。其要求：仅新 IAM status 完整 ApiResponse 的 UTF-8 bytes≤2*1024*1024，序列化过程中有界；超限403/RUN_STATUS_TOO_LARGE，非授权撤销；先序列化/终检，再取得实际 Servlet stream、再终检后首写。此为要求，不是本文已验证的 O1b 实现事实。

## 2. 已冻结 gateway 接缝的具体差异

| 接缝 | 实际冻结事实 | O1c 必须补的有限行为 |
|---|---|---|
| GatewayIngressBoundary | 私有 Purpose 只有 RUN/SESSION；RUN只收无query的POST `/admin/apps/{code}/runs`；claim只给RUN；真实filter、HandlerMethod、anchor、一次claim存在 | 新 STATUS purpose 和独立私构造 PortalStatusRequest，不能把原PortalRequest.appCode换成runId冒用RUN |
| GatewayController.proxy | IAM分支一次claim后只进PublishedRunProxy，缺bean503；非IAM旧循环独立 | 在相同实际handler的IAM分支明确分派STATUS到新proxy；无bean仍闭锁，绝不回退旧代理 |
| PublishedMetadataClient | app origin/四字段/versionNo int、4096限制整个response；source仅发authority；使用通用exchange只返回ServiceTicket | 新engine run metadata client，七字段/两个long、data4096与wire envelope区分；不用当前发布指针 |
| HttpCurrentAuthorityClient | exchangeGatewayPublishedRun固定POST/app-version；私有PortalExchange保存current exp、source exp、单调起点；GatewayRunAuthorization构造私有并跨线程本地检查 | 新固定status授权入口/私有证明；不能改旧proof接受任意action/resource |
| PublishedRunHttp | Apache client5.4.4/core5.3.4；get/post机械传输有界、2秒hop、cancel、no retry/redirect/cookie/authchallenge/compression；现get非200只保401/403/409/503且统一DEPENDENCY_REJECTED，404变503 | 共享机械transport；固定观察GET响应策略需保404与有限错误码，原get/POST行为不改 |
| PublishedRunProxy | 5秒总deadline；先metadata再独立M exchange；M来自exchange的current证据，**没有额外gateway authorize**；POST异步WriteListener逐chunk检查；错误只剩GATEWAY_RUN_REJECTED且状态不含404 | 新status专用编排和安全错误映射；不把整套POST body/async状态机用于GET，更不继承其错误折叠 |

N4b2 base 中 GatewayIngressBoundary/HttpCurrentAuthorityClient/PublishedRunProxy 均有更新overlay；仅base存在不代表最新版。本次先发现hash不等，停止读过期内容，再使用root指定新overlay。精确物理来源见§9。

## 3. ingress：新增只读用途，保留真实入口证明

候选 `GatewayIngressBoundary.Purpose.STATUS`：仅 REQUEST、GET、精确 `/admin/runs/r_[0-9a-f]{32}`、现有限空contextPath约束、无raw query（包括bare ?）、无body。不要新增contextPath兼容或宽解码别名。拒绝 Cookie/X-Api-Key、重复Authorization、机器/API key、自报tenant/principal/actor/resource/target/action头；拒绝content-length非零与Transfer-Encoding，并检查实际输入流EOF。沿真实 decoder/PortalClaimMapper、固定issuer/JWKS/audience/client_id、user/platform.access要求。身份及source原串只来自真实入站认证。

preHandle的HandlerMethod检查新增STATUS归属于原GatewayController.proxy实例/方法，不让现RUN?owns:ownsSession三元逻辑把STATUS错发session分支。已安装filter/chain、wrapper anchor、一次claim、dispatcher规则全部保留。新增 `claimStatus(request)` 返回 private constructor 的 PortalStatusRequest，最少持有同request anchor、owner、publicRunId、originalPortal、source expiry；不接受public Actor/VA/字符串工厂。原claim仍只RUN、claimSession仍只SESSION。

建议STATUS采用同步同请求有限编排，避免新增异步状态机；PortalStatusRequest需有固定 `requireLive(actualRequest)` 检查被claim后的**同一次**request/owner/用途/路径/invalid状态及source期限，不能直接调用旧Proof.check，因为其claimed=true会拒绝。afterCompletion/边界关闭失效；不能只比较token字符串或复用另请求capture。原RUN跨线程capture语义不顺带改。

gateway这里没有engine的AuthorityContext retained frame，不能伪造或把engine frame跨HTTP搬来。“同frame”落实为原已安装gateway ingress capture/request生命周期，再加独立gateway私有M证据；engine内部仍使用O1b实际retained frame。新capture不能用于POST、session、v1或另run。

## 4. Q/M、私有证明与metadata解析

### 4.1 两次独立交换与准确调用时点

1. gateway claim STATUS，记录非递增总deadline，核source/request仍live。
2. 固定 Q call：target=engine/action=`run.observation.metadata.read`，resource=`RunObservationSelectors.metadata(verifiedSubject,publicRunId)`；以**原source字符串**exchange一次，只向authority送source。
3. 固定engine origin GET `/internal/runs/{id}/observation-metadata`，只发送Q service ticket；先完成严格metadata解析/owner/path检查，再允许M。
4. 以**完全同一originalPortal**独立exchange一次，固定 M call：target=engine/action=`proxy.engine.get.RunController.status`，resource=`run-snapshot/publicRunId/acceptedSnapshotSha256`。不resume/forward Q，不复用旧执行grant，不从metadata.root生成app-version M。
5. 检查Q/M各自signed subject/resource/source/origin；应有不同grant/operation、相同原认证关联。固定client私有证据可从已验证claims比较originAuthenticationId，不能通过外部DTO/自报header比较。仅“source hash日志相同”是诊断证据，不能代替claims验证。
6. 发engine `GET /admin/runs/{id}`，仅M service ticket。engine自行查真实metadata/scopes，不发送owner/root作为信任输入。完整接收有界status后核JSON及runId，再进入最终写出。

原POST authorizer关键事实是gateway的M exchange已含current授权结果，并由private proof保存；不是再调用target=engine票的authorize来伪装gateway自身受权。O1c沿此机制：新增固定 `CurrentAuthorityClient.exchangeGatewayRunStatus(originalPortal,runSnapshot)` 默认拒绝，Http实现只有iam/gateway可mint `GatewayStatusAuthorization`。复用私有portalExchange/verifiedTicket/origin验证原语，但保留原POST proof类型与方法不变。新type constructor私有，固定STATUS call，保存owner client、已验original source期限、ticket/operation/grant/current exp、exchange前wall+monotonic起点；`requireVerified()`失败后锁存、client close即失效。校验期间不能重exchange延命；不得public VA/currentOnly/Runnable构造。

Q也需要真实时限检查：通用exchange只返回ServiceTicket，丢弃current decision expiry，因此不能声称其返回值支持完整Q current终检。建议增加窄 `exchangeGatewayRunObservationQuery(originalPortal,SubjectKey,runId)` 与私构造 `GatewayObservationQueryAuthorization`，同样复用PortalExchange内部证据，只能发metadata；禁止将Q proof传status或写者。其必要性仅是保留Q exchange已有证据并校验发出窗口，不增加在线authorize请求。若root选择保留通用Q exchange，正式brief必须明确只能检查source/ticket/operation而没有Q current本地证明，不能伪称同等保证；本候选优先前者。

新proof与gateway层私有已解析metadata值在一个请求编排中绑定：query subject/path、metadata.owner/runId/acceptedSHA、M subject/resource必须一致。metadata值可在client内private constructor生成；它仍是事实结果，不独立授权。proof不需要共享ResponseLease，不新增response_open/check/续期协议。未查询的远端撤权不会被本地检查自动发现。

### 4.2 O1a七字段与两个不同字节上限

响应必须来自固定可信engine origin，严格JSON解析：duplicate keys、trailing tokens、错误类型、缺字段/多字段均拒绝。外层固定code/message/data/traceId；成功code=OK，message字符串，traceId仅null/字符串。data恰runId/owner/root/appVersionId/appVersionNo/acceptedSnapshotSha256/initialCeilingSha256；owner恰tenantId/principalId，与已验source主体相同；root恰kind/id/versionDigest，kind=app-version，code@正版本结构和versionNo一致；两版本字段都是可精确转long的positive整数，不能继承PublishedMetadataClient的versionNo int。runId精确匹配请求，所有hash lowercase64hex。

O1a data序列化UTF-8≤4096，**不是整个envelope≤4096**。本候选建议metadata wire envelope独立有限预算8192 bytes，供root正式裁定，不是既有实现常量；失败不截断，也不新加生产配置。先在读取过程中按wire budget限额，再按原Json.mapper等价序列化data核4096（保留big integer/decimal精度；不得double损失）；此检查是O1a同口径的data投影，不把传输字节与canonical digest混淆。超过envelope预算（包括异常长traceId/message）失败503，不能通过抬到2MiB掩盖metadata边界。正式测试用合法接近4096的data证明不误将外层开销算进data限制；若现字段最大长度使该极值不可达，应报告实际可达边界，不造非法DTO强行过校验。

不存在/不归属均按engine真实404安全返回；归属却缺authority/scopes为403，不能fallback legacy。metadata失败时M exchange和status HTTP均为0。原执行app根/candidate只是PG事实，不是签名M内容；gateway不得从这些字段重算历史snapshot、判断scope、改写materials或请求当前发布指针。

## 5. 固定观察GET运输与错误响应

保留 `PublishedRunHttp` 作为机械transport，生产固定HTTPS origin/证书与host校验，隔离测试只显式127.0.0.1 HTTP；来源不取URL/header。GET也必须走原 `.disableAutomaticRetries()`，连接reset/丢响应/挑战/redirect不重发，不因GET幂等改用默认client。保留disableRedirectHandling/CookieManagement/AuthCaching/ContentCompression/ConnectionState和authenticationEnabled=false、hardCancellation、实际请求handle/定时cancel。每次发出只含target service Bearer、Accept JSON、Accept-Encoding identity，不转Cookie/原Portal/额外Authorization/自报身份。

实际beforeSend hook已位于5.4.4连接取得之后、MainClientExec发请求之前；callback仅消费已mint Q或M proof并检查capture/deadline，不允许由任意callback mint能力。完整body每次读取后和最终返回也有检查。沿原2秒hop、connect/connection-request限额；候选总5秒deadline从gateway接收/claim前后固定起点开始，不能每hop重置总预算或在M授权返回后重新给5秒。authority/JWK仍有其原固定机制，本文不声称已经解决common JDK网络强取消；若其调用超过总预算，返回后必须拒绝继续送业务请求，并据实区分物理取消范围。

传输解析继续用已验client/core配置：初始headers≤64、单行≤8192；独立chunk/trailer parser限制，合法chunked多头JSON通过，任何trailer（含未声明）、过长chunk extension/line失败；Content-Encoding/Set-Cookie/Location/Trailer拒绝，Content-Type仅单一JSON/UTF-8。上游Content-Length先限额，未知长度/chunked逐块实际累计限额，early EOF/格式异常按现parser拒绝；不要宣称尚未实测的全部HTTP request-smuggling防御。读取上限包含整个status envelope，固定2MiB，超限视为不可信下游响应503，不伪造engine返回RUN_STATUS_TOO_LARGE。

需要新增固定命名GET入口（候选 `getRunObservationMetadata`/`getRunStatus`）或内部private固定policy，复用机械execute；不开放任意调用者状态白名单/response规则。现get/post默认策略保持。新观察入口对有限200/400/401/403/404/503进行有界读取与同样header/JSON验证；3xx、auth challenge二次交换、其它状态、未知成功格式拒绝503。最终状态白名单以O1b Accepted实际异常层核对，不能预先宣称所有错误码已存在。

严格区分：

- 200/OK且data为完整status对象、runId匹配：保留engine原完整body字节（校验后不重新序列化成功body），不丢字段、不二次包装、不改变大数/时间/null。
- 404：仅本地固定安全“运行不存在”错误，隐藏不存在/别的owner差别；不透传任意下游message/HTML。
- 403/RUN_STATUS_TOO_LARGE：保留独立稳定code和安全消息，非撤权，不改execution状态，不自动重试。
- 已知401/403授权拒绝与503依赖故障：按实际受控白名单映射安全envelope；不用原POST一律GATEWAY_RUN_REJECTED覆盖所有语义。unknown code/status组合或错误data携带业务对象应503且不透传body。
- malformed/truncated/oversize/chunk/header污染：503安全envelope；不转Location/Set-Cookie/WWW-Authenticate等上游头、不令浏览器跟随下游挑战。

错误JSON也必须有界、严格字段/类型和有限code+status匹配，拒绝data业务对象；traceId不从任意下游反射泄露。403错误的真实性由固定可信engine transport及格式保证，不能把任意测试server声称的错误等同生产授权证据。实际engine合法错误映射与rawsocket恶意peer负例分开报告。

## 6. gateway最后一跳与资源所有权

建议新同步 `RunStatusProxy.forward(request,response,PortalStatusRequest)`；没有输入JSON，不启动新worker/异步SSE。返回前已获取完整≤2MiB engine envelope，并完成parse/runId核对。随后：检查同capture/request及M proof → 设置本地受限status/content-type/no-store等header → 取得本次实际ServletOutputStream → **再检查同capture/request+M proof+总deadline** → 首次write原完整成功bytes。不能在getOutputStream前检查后直接write；不得交converter第二次输出。错误走本地安全异常/envelope，不输出部分status成功再改错误。

若root决定复用有限POST非阻塞输出机械组件而非同步输出，必须先说明最小文件/API和capture失效与async生命周期如何处理；不能将原engine threadlocal frame带到WriteListener。新设计无需该复杂度；当前同步有限首写不承诺阻塞write内部连续撤权或5秒物理断开慢peer，与O1b限定一致。首写前到期/关闭/框架capture失效业务wire0；IO已部分输出就停止/关闭并记实际部分字节，不重发GET或换成功envelope。

新proxy/client/transport的close所有权明确：复用注入transport时client不重复close；owner bean关闭自身拥有的transport，cancel已安装HTTP handle，释放本请求bytes/capture/proof引用。新同步请求不新增private executor；无临时文件/spool。status最多2MiB只约束原始body缓存；JSON校验树和有界副本仍有额外内存，不能宣传整个请求堆≤2MiB。

原POST的PublishedRunProxy及GatewayRunAuthorization无行为改动；若必须共享代码，仅提取非授权机械原语，保留typed目的。新status不能接受原PortalRequest/app-version proof，Q proof不能传M consumer；不新增泛化read/write授权wrapper。

## 7. 候选文件与未来真实验收

精确工程相对路径/API候选；正式brief由root在O1b交还后锁定：

| 文件 | 改动目的 |
|---|---|
| `admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java` | STATUS route/filter/preHandle、私capture/claimStatus/同请求live检查；RUN/SESSION保留 |
| `admin-common/src/main/java/cn/agentplatform/common/authority/CurrentAuthorityClient.java` | 固定Q/M gateway方法默认拒绝，无通用proof factory |
| `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java` | 私Q/M证明、固定目的exchange，共享私有签名/期限原语，原POST/session unchanged |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java` | IAM admin GET STATUS分派、缺bean闭锁；原POST/legacy独立 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunObservationMetadataClient.java`（新增候选） | engine固定Q HTTP、七字段strict parse、私metadata值 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunStatusProxy.java`（新增候选） | 同source Q/M编排、M结果完整校验、最后一跳有界首写/错误映射 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java` | 固定观察GET响应策略，复用机械Apache，不改变旧get/post策略 |
| gateway既有实际装配位置（需实施前窄定位） | 安装新proxy/client，与实际GatewayController/Boundary/coverage成链；不放开全局IAM生产闭锁 |
| `platform-gateway/src/test/java/cn/agentplatform/common/authority/GatewayRunStatusChainPostgresTest.java`（新增候选） | 真实两端HTTP+PG/受控authority总链 |
| common现有gateway boundary/client定向测试；O1b Loopback fixture | 具体名称/改动待O1b final diff；只测新增目的、防混用和有限policy |

本次不预定改RunObservationSelectors/ServiceAuthorization/catalog/engine：O1a query与O1b status已有固定接缝。需要先从O1b交还API确认调用；不存在就报root，不读取作者半成品。装配文件未从当前源猜名字，不因为新类存在就认为真实bean安装完成。

未来红绿矩阵（全部未执行）：

| 场景 | 必须实际观测 |
|---|---|
| 完整成功链 | 浏览器raw Portal仅进gateway/authority，Q/M原source相同且各一次独立exchange；不同grant/operation，同原认证；engine只见相应service票；真实engine metadata/status/security/PG，浏览器收到同完整status字节 |
| 身份与入口 | cookies、额外/重复Authorization、自报owner、alias/rawquery/bare?/body/forward/错handler/uninstalled filter拒绝；记录到哪层，未到Q/M的数量为0 |
| metadata失败 | 不存在/其他owner统一404，缺authority403、fault503、七字段错/多/缺/重复/trailing/long越界/owner/run/SHA错；M exchange与status HTTP为0 |
| 资源与目的 | 同app两run互换、cross-tenant/owner、Q→M snapshot改变、错M resource；Q/status/POST/session/local/INTEGRATION互撞；authority按可信run映射拒绝，不只gateway helper拒绝 |
| engine业务错误 | 真实O1b超2MiB→403/RUN_STATUS_TOO_LARGE保留、授权403与依赖503区分；404安全映射；执行grant/blocked不改变，无新增run/worker/provider动作 |
| transport | GET连接reset/响应丢失只一次请求；301/302/authchallenge不得第二请求；原Portal不泄露到engine/redirect host；真实chunked多header成功，content-length/unknown-body超限、bad JSON/trailers/oversize lines拒绝 |
| 最后写出 | Q后/Mexchange后/connect后发送前/engine返回后/解析后/actual getOutputStream延时返回后推进clock或关闭client/capture，成功业务wire0；实际response wrapper取得真实stream后改变状态，不能只mock计数 |
| 资源与期限 | original source失效、wall rollback单调截止、Mcurrent/grant/op/ticket期限、总deadline不递增；取消实际HTTP handle并观察自有线程/端口退出；common JDK未硬断的限制如实写 |
| 保持范围 | 原POST主路径与session有限直接回归；legacy不使用新增cap；不重跑旧全套、不给SSE/真实IAM签收 |

按既有GatewayPublishedChainPostgresTest的独立module classpath/真实HTTP方法搭建，至少gateway和engine真实不同HTTP监听，优先双JVM；engine自身classpath加载真实O1b业务/PG，gateway不得导入engine业务JAR或反射兄弟target/classes。若由一个测试JVM编排独立模块classpath子进程，明确每个真实服务的进程/监听拓扑，不把mock upstream当联合成功。真实受理fixture创建完整FLOW/scopes，不用裸SQL伪造成功status；另raw socket恶意peer负例只证明transport。记录原source诊断hash/subject/target/path/计数时不得将真实token写报告。

## 8. O1b Accepted 后的具体diff复核门槛

1. 新status action/resource校验最终API，admin alias/dispatcher实际guard位置；StatusReadProof/ObservedView/ObservedStatus必须保持engine本地能力，gateway不反序列化它们。
2. 实际status 200 envelope、所有已存在错误code+status组合、缺authority与未知/他人run语义；基于真实异常处理决定观察GET映射白名单。
3. 2MiB完整envelope边界/403 RUN_STATUS_TOO_LARGE、真实stream后终检是否通过、原POST与legacy是否保留；不得从补充brief推断已绿。
4. O1b Loopback按run/owner/acceptedSHA可信登记方法、same-originalPortal的版本/origin信息可观测位置；复用其正式finite profile不重写授权history。
5. common selector及其它O1b实际改动与本候选CurrentAuthorityClient/HttpCurrentAuthorityClient的新方法组合是否有冲突；不预改O1b作者文件。
6. 确认O1a metadata最终七字段/4096 data仍保持；root正式确定metadata envelope有限预算与观察GET安全错误规则。
7. 新gateway实际bean装配与原boundary installation/coverage具体文件、方法signature；锁定文件集合/测试selectors再派发唯一source writer。
8. O1b正式source/target manifest和overlay谱系替代本次578/1948起点；需root移交写权，本文不能作为直接实施许可。

## 9. 冻结来源与本次有限核对

物理根 P=`G:/207/项目/算法重组工具/代码/agent-platform/`。各行物理路径为P加下列精确相对路径；SHA均是实际已核/已有manifest与root核相符值，不是当前source猜测。未读全量历史/旧10GB证据/依赖树。一次猜测旧time-order报告路径失败后改读确切composition并按root物理定位，无源或功能影响。

| 物理冻结路径（P之后） | Bytes / SHA-256 | 本次内容范围 |
|---|---|---|
| `docs/evidence/iam-stage-4-d4a-portal-session/frozen-overlay/admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java` | 18308 / `2525da73a86ce83f538c03745cec69e8e835965bfa41851eab4bccc3610c2442` | purpose/claim/install窄rg；96–219、229–280 |
| 同overlay `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java` | 36371 / `7f29e6397283d72c85562a616b8488578475f5cf1071e4bc70471c131b1da412` | proof/authorize窄rg；137–204 |
| 同overlay `admin-common/src/main/java/cn/agentplatform/common/authority/CurrentAuthorityClient.java` | 1519 / `e8198042f6cde633b43659eb0c920117848a15c377e05e10781e4b9f406e5874` | 1–25，SHA与O1a source-after相符 |
| `docs/evidence/iam-stage-5-n4b2-close-install-fix/frozen-overlay/platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java` | 19294 / `0621e61eedef1a9a6db6faac42597e4d0386772a60c426a4fd25231709e4548e` | 窄rg；26–71、247–352、360–412 |
| `docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java` | 10996 / `2aaf73d8ff93094dec7712f2c5672bf8b5b3e9ea858265d5371cf063828e239f` | 25–64 |
| 同N4b2 frozen `platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedMetadataClient.java` | 8222 / `203d6be3c08e82396c94af0257c4eb612cd2900e608ce3bd5f5a783de4f35304` | 1–171 |
| 同N4b2 frozen `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java` | 12788 / `443efcd7179ddaa366e4d933d9b4921d0b334a69b6f8a0c18ef86a60ae5ff5a9` | 窄rg；33–109、183–271 |

引用旧 `iam-next-run-observation-seam-proposal.md` 与原read-manifest中gateway定位，只用于找冻结原件；本次没有将其中尚未实现的旧metadata建议当新事实。Apache版本与行为来自本项目冻结实现注释/调用，本次未重新阅读第三方源码或声称额外上游验证。未来作者按固定版本窄读实际接缝即可。

交还后冻结本文；只提供候选范围/风险，无实现或测试成功声明。