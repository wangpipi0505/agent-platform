# O2b E：引擎子响应与完整材料私有报文决策

本文件是新工程内部传输实现决策，v1.2及四契约不变。前置D与独立selector迁移均已Accepted；source603 SHA `ab0310201aff0a59e87f73b0692bf6e89b050591d7f33c2b4f099da3a97283c4`，target历史逻辑2118 SHA `f04cd892a8842083b1d546795a5ce146aa3b4edec794f90f3fa0f2e58035a893`。D八索引checkpoint为`.local/checkpoint-iam-o2b-d-docs-before/manifest.json`（5411B/SHA `2b125131305a08bbdf6f3c734a744a3296d258da666cc4d25b5c9c724ba87dab`）。只验E，引擎来源TLS、网关完整材料验证和公开SSE为后续F。

## 授权与表示互斥

固定私有MIME及唯一Accept值为`application/vnd.agent-platform.portal-events.v1`，Content-Type同值，无参数/charset/多值。保持RunController原三参events方法、固定action、admin/debug/v1三个映射；produces精确增加该MIME，debug/v1仍按实际安全服务拒绝。

IAM普通表示只调用既有`openPortalEvents`，它在claim/open前拒绝child；IAM私有表示只调用`openPortalEventsChild`，它要求实际签名ticket/current留存的私有child证据、actual owner/frame/first anchor。两侧任何失败均不fallback、不另开holder。私有Accept的非IAM请求在legacy Actor/SQL前403；匿名请求沿安全filter 401。识别私有media type须用受限的MediaType解析，不能字符串contains；重复Accept、私有混合列表/参数/不精确值400，完全不支持的表示可沿Spring406。固定私有值的识别与严格拒绝不得影响原普通表示规则。四组合实际HTTP验证：child/private成功、child/public拒绝、standalone/private拒绝、standalone/public保持C。

## 固定wire v1

HTTP body由连续完整packet组成。每packet前缀24B，big-endian：magic4=`APEV`、version u16=1、kind u16（1 HELLO/2 WINDOW/3 CAPACITY）、ordinal u64（0..Long.MAX_VALUE）、headerLen u32、metadataLen u32。headerLen为1..8192，metadataLen为1..8388608；payloadLen只来自完整受限header的payloadBytes，为0..262144。所有总长先checked-long计算，不依不可信长度抢先分配。最大wire为8659012B。

尾部COMMIT固定44B：magic4=`APCM`、同ordinal u64、32B SHA256。摘要覆盖本packet前缀、header、metadata、payload的全部精确字节，不含COMMIT。HELLO ordinal=0且仅一次，后续连续+1，溢出关闭。无COMMIT/截断/EOF不是完成，不能从下一packet补上一packet。未知版本/kind、重复/乱序ordinal均协议失败。

header恰10键：childResponseId（实际child Capture.responseId，非空且≤128字符）、runId（r_加32小写hex）、resourceSha256（小写64hex）、initialCursor/afterSeq（非负long，after≥initial）、eventSeq（正long或null）、eventType（非空字符串或null）、payloadBytes、metadataSha256、payloadSha256。resource kind固定run-snapshot；不接受header改选。空payload摘要为SHA256空bytes。HELLO after=initial；HELLO、无event WINDOW及CAPACITY的eventSeq/type均null且payloadBytes=0。

eventType保留C实际已验原字符串，不采用准备稿新建议的`[A-Za-z_]{1,64}`缩窄。root具名窄核C-fixed Reader130/136/194/202：既有规则为非空及CONTROL_BYTES上限，并非该正则。原值必须与eventIdentity.type逐字一致；若完整header超过8192则按容量失败，不截断/改名、不当授权失败。最终公开SSE字符安全由F明确验证，E不声称已经完成该出口。

JSON严格UTF8，无BOM/非法序列/未配对surrogate、重复键或尾随第二值。固定schema拒unknown，业务payload/attributes/entitlement字典内动态键沿原语义。header解析上界depth8/tokens2048/string4096/number19；metadata depth80/tokens262144/string1048576/number1000；单scope沿既有depth32/tokens65536/string65536/≤1MiB、parents≤4096、scope records≤4096。上限取交集，不承诺4096项都能装入8MiB。E writer用同一固定无空白UTF8编码器生成header/metadata，对象键按字典序、集合排序；持久化parent数组保留原顺序。此规则不是RFC8785/JCS承诺，wire SHA与业务row/scope SHA分开。

## 三种packet的材料

HELLO metadata恰`{receipt:string}`，使用实际`authority.portalEventsChildReceipt(childHolder)`正常导出的紧凑ASCII JWS≤4096B，无payload。HELLO和首WINDOW在同一次fresh `WindowEmission.consume(action,beforeRelease)`内连续输出：该次RR全部校验成功后才能HELLO，随后首WINDOW；旧opening preflight已关闭结果不是输出证明。HELLO完整但首WINDOW失败时，F最多建立关联，不能公开status/event。

F须先对同一String调用实际G.acceptChildReceipt成功，再严格解析已验claims的child_response_id/parent_response_id，匹配header/G responseId/后续packet；D方法返回void不授权验签前拿DTO造权。receipt只出现HELLO，不是逐packet current或字节认证。该F接收规则为后续义务，本轮不实现网关。

WINDOW metadata恰14键：runDbId（正long）、owner（tenantId/principalId）、acceptedSnapshotSha（等header resourceSha256）、initialCeiling、initialCeilingSha256、provenance、rootScopes、stateScope、eventScope、scopes、state、eventIdentity、window、hasEvent。

initialCeiling恰tenantId/principalId/roles/attributes，roles为不重复字符串数组，attributes为字符串到不重复字符串数组字典。SHA按既有纯规则复算，owner必须完整一致。

provenance恰8键：executionRoot（kind/id/versionDigest）、appVersionId/appVersionNo（正long）、originAuthenticationId、grantRef、operationId、admissionDecisionId、authorizationVersion。来自同RR真实受理binding，后五为非空字符串，各≤1024 UTF8，超限容量失败而不截断。历史origin不必等于新observer。历史grant/operation opaque ID是完整provenance，不得因“无grant”删去；可用service JWT/subject token/continuation/client secret一律不导出。

rootScopes恰run-snapshot/run-inputs/run-version-refs三个hash。scopes元素恰`{revision:正long,scope:MaterialScopeV1}`，按revision再scopeSha排序；重复hash/revision冲突拒绝。scope完整保留现有11键：schemaVersion、material（producer/kind/id/version/payloadSha256）、owner（tenantId/principalId）、sourceDecisionId、sourceAuthorizationVersion、initialCeilingSha256、requiredRoles、requiredDataLabels、requiredEntitlements、parentScopeSha256、scopeSha256。不得截断parents或把source版本统一改为opening版本。

scopes是同RR三根、当前state/可选selected event、两者直接父的完整并集；每个父也带其完整parents数组。它不是递归祖先闭包。不能缺/多/错父或按最弱角色子集选择scope；不能仅给聚合floor/摘要。新observer仍独立满足全部当前材料的owner/initial/floor/labels。

state保留C完整11键id/public_id/app_code/status/event_seq/stop_reason/started_at/ended_at/mode/trigger_type/output_ref。id=runDbId，public_id=runId，app_code非空，status沿C，event_seq非负；其余原值可null，不填默认/改格式。时间为原投影UTC微秒字符串，不反解析降精度。stateScope的payload SHA对应完整state。

window恰status/watermark/oldest/afterSeq，status/watermark对应state，oldest正long或null且≤watermark，afterSeq对应header。hasEvent=false时eventScope/eventIdentity/header.eventSeq/type均null且payloadBytes=0；hasEvent=true时eventScope存在，eventIdentity恰id/run_id/seq/type/created_at，run_id=runDbId，seq/type=header，seq>afterSeq且≤watermark。

payload只含event.payload紧凑JSON bytes；JSON null是4B，不能与无event 0B混淆。F组合eventIdentity和payload，重建恰六字段id/run_id/seq/type/payload/created_at的完整row，按现有MaterialScopeStore业务规范化（对象键排序、数组不变、BigDecimal.stripTrailingZeros、零统一整数0、scale≤0转BigInteger、其余Decimal）及固定Jackson UTF8输出复算event scope payload SHA；state同规则。scope/initial沿既有MaterialAuthorization纯规则，不能以wire SHA代替row SHA。

after>watermark为CURSOR_AHEAD；oldest存在且after<oldest−1，或空表after<watermark，为CURSOR_EXPIRED；终态且after≥watermark才drained。reset优先于event。首次完整WINDOW才可供F公开stream_status，积压逐event发送，终态排空关闭。idle empty结果释放后有限poll，不新增公开heartbeat，不造无材料WINDOW；续核/watchdog独立。floor/digest/owner错误直接关闭，不能伪装容量或终态。

CAPACITY metadata恰`{code:"BUFFER_LIMIT"|"EVENT_LIMIT"}`，无payload/event，必须已有完整HELLO且actual holder live，packet边界最多一次后关闭，不夹未经验证state/watermark。payload超限用EVENT_LIMIT，header/metadata/模型/编码容量用BUFFER_LIMIT。原workspace准入满沿C有限等待/429。授权/协议错误不用CAPACITY。F只有自己的G仍live时才可映射无run事实的stream_reset。

## 同RR、预算与释放

Reader只从原readWindow唯一RR保留完整initial/provenance/roots/scopes/state/eventIdentity/window到私有PacketMaterial，不另查DB、不改C原验证或B readNext。commit返回/live/cleanup后才能mint并转移原2 permit；材料仅经同步WindowBorrow guarded出口编码，和payload共享outputActive，拒nested/concurrent。无public DTO工厂、mutable map/JsonNode/内部数组导出；持久化parent已解析模型可复用，但新增持有/描述符必须收费。

同一次borrow第一遍count/hash每段，超限时该packet零字节；第二遍同一不可变模型/同capture流式写并复核count/hash相等，live才COMMIT。getOutputStream返回后、每write/flush前后、COMMIT前后均guard；编码/网络不得在RR内。不分配8MiB完整wire buffer，不toByteArray/几何BAOS，不建第二scheduler。

保留原270437B frame reservation（262245+8192）、16MiB response共享池、2 reader permit及原全局上限。header≤8192 backing计入原frame且不得与原scratch重复免费使用；metadata generator/编码scratch总reservation≤65536B；descriptor/sort引用总reservation≤65536B；隔离sink copy每次≤8192+24B；payload clone≤262144+24B沿原收费。新增项须在分配前预留原B total192MiB ledger，释放顺序有实际finally；不能把“预算保留”当实际未计费分配，也不能单凭名义64KiB说Jackson真实backing已受限。若实测模型不足，收紧实现或报root，不扩上限/暗借大buffer/测试放宽。模型64MiB、DB metadata8MiB、scope1MiB、parents/records4096与wire8MiB交集全保留。

必须沿C-fixed consume(action,beforeRelease)：entry/final guard失败也先actual async.complete，再cleanup/B/frame/slot归还。HELLO+首WINDOW及getStream/write/flush都在action；终态/错误complete在beforeRelease。timeout/error/onComplete只stop，实际阻塞write/complete仍持额；队列只留小owner，writer重新fresh RR。不得改既有B readNext/consume语义。

superseded只在本packet prefix尚未写时按C最多2次fresh重读；HELLO已commit而WINDOW未开始时可同after重读但不重复HELLO；本packet有字节则关闭，不换generation拼包。engine只有完整COMMIT及flush后才推进cursor；F只有完整公开event写出后推进。EOF不是terminal/COMMIT证明；先前完整packet不回滚。

未committed沿C白名单ApiResponse/status及application/json；async已接管由owner处理，不抛MVC重派。私有packet开始后不夹SSE/JSON错误，只有满足上述边界时可CAPACITY，其他关闭。public分支不含receipt/manifest，private不回退public。

## E验收和F信任边界

E是encoder/engine接收端，不偷偷实现网关decoder。E codec测试输出准确HELLO/WINDOW（event/empty/null/terminal/cursor reset）/CAPACITY向量，核固定prefix/commit/长度/digest、数值/Unicode/escaping、overflow/guard/reentry/失败无COMMIT无cursoradvance。只写最小test检查器读自身输出；未知/dup/trailing/任意截断输入的生产接收拒绝矩阵留F，不以test-only parser宣称F已实现。

PG正例真实writer受理后登记observation，至少65直接父且传完整parents/provenance，核同RR/root/state/event行摘要、current收窄/追加/终态/篡改/保留及实际metadata长度和ledger。HTTP必须正常G/E签名issuer→actual ServiceAuthorization filter/frame→PG→private wire，核双向open/Accept互斥、无child/非IAM/别run/receipt一次、真实阻塞get/write/flush/complete期间不回收；support不能代替HTTP frame。

F将收齐完整packet并验证COMMIT/schema/association/完整材料与fresh G current后才公开id/event/data。固定engine TLS身份/CA/必要mTLS负责来源，engine可信PG负责row membership/latest/同RR选择。F独立验完整scope/直接父集合/floor/currentG及state/event摘要。本切片不导出三root大payload，不能声称F重算三root payload SHA。receipt/摘要不替代TLS。E不宣称生产issuer部署、网关EVENTS开放、真实TLS/网关材料验证、公开SSE全链、生产容量或socket强断SLA已验收。

准备依据为冻结`.local/iam-o2b-e-private-packet-preparation.md`与`.local/iam-o2b-e-followup-decisions.md`；本正式决策优先。root已完整核读后者1–96 EOF（19707B/SHA7711be624b2f41ec7803b1546df9ef9c050302863c3fca6943ec73e134941888），明确修正eventType新正则及E/F encoder-decoder范围，未修改原准备稿。
