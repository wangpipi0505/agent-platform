# O2b F1：严格私有报文解码与本地有限预算

本文件为独立新工程当前实施任务的正式要求。前置 O2b E（含 I1/I2 修复）已经独立复审 Approved C0/I0/M0、两份有限审计通过并闭合八索引。F1只完成可独立验收的 wire 接收组件；F2再接实际来源、完整业务材料验证和公开SSE。用户已授权按设计持续开发及隔离测试，无需再次请求实施许可。

## 约束、基线与唯一写权

所有shell cwd固定 `G:/207/项目/算法重组工具/代码/agent-platform`。遵循本工程AGENTS、implementation-brief、contracts README及适用TDD/SDD/verification/Java/ponytail。新工程与相邻旧实现完全隔离；不得读取或复用旧源码/依赖/进程/数据。v1.2、四契约、时间安排不动，不commit、不部署。Java21/Boot3.5.0/Jackson2.19.0固定，不升级或新增依赖，不引入跨业务JAR。

Accepted基线：source608，`.local/iam-o2b-e-fixes/source-after.json`，116139B/SHA `303ec0625e9b8491d834cbd0ce27a38c0dcffab77104e333b38f960e102a5adf`；target历史逻辑2148，`.local/iam-o2b-e-fixes/target-after.json`，447924B/SHA `30422b7bcf37c2e31ef6c596ced94543d432bf847bde17b513640d6dba403f51`；完整只读基线 `.local/iam-o2b-e-fixes/replay/`。八索引checkpoint `.local/checkpoint-iam-o2b-e-docs-before/manifest.json`，5496B/SHA `4949eda71d84b68b5c9f65d420a867c7bc4b509157f738fe94b2faa5d9656171`。输入、十二个真实encoder向量的file/bytes/SHA及授权四路径集中于 `.local/iam-o2b-f1-frozen-inputs.json`。

唯一source/target writer仅新增以下4源码文件，无修改/删除其他source，source最终612/delta4/new4：

- platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketDecoder.java
- platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudget.java
- platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsPacketDecoderTest.java
- platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudgetTest.java

这里采用infra目录承载传输解码和资源设施；此前只读候选稿的application位置不作为绑定要求。无需阅读整个累计计划或重读旧608源。Controller/common/assembly/main/HTTP/TLS/配置/pom/catalog/schema/前端/旧测试全部0写；无Spring stereotype、无新authority fixture或通用parser/lease框架。gateway EVENTS继续默认关闭，STATUS/POST原行为不改。若具体接缝证明四文件不足，先把证据报root，不自行扩写。

原E45、修复新RED3/初始3pass1E/纠正1pass、原两份patch/一次apply/全部回放及审计均冻结；不重跑、不覆盖、不补造。只继承Accepted结论及限制，不称全IAM/全后端/生产容量/部署完成。

## 任务边界与数据出口

decoder只证明当前一个包的格式、精确段摘要和COMMIT完整性。成功结果明确命名 `UntrustedPacket`，注释和测试都注明其未授权：不证明engine来源、receipt签名、parent关联、run归属、floor/current、业务row SHA或可公开输出。没有SSE、VerifiedWindow、AuthorizedPacket、G Session、伪造DTO权限或公开proof factory。

完整semantic verifier、真实G/currentCapture/acceptChildReceipt、原Q事实来源、state/event BigDecimal业务规范化、公开SSE编码、游标推进、HTTP/TLS及Servlet生命周期均在F2。G和E已有实际自动renew，F1不造scheduler。

最小API在两类内部完成：实际资源Owner由固定共享budget正常取得（非权限）；decoder绑定一个Owner且Owner整个生命周期只能认领一个decoder/一个输入对象。可令Owner由后续application持有，decoder借用；两infra类为必要跨包调用提供有限public入口，所有resource token/packet构造器私有，不生成无用DTO文件群。不得允许换InputStream续ordinal或同Owner并行开多个decoder绕过两slot限制。

`readNext(InputStream)`在包边界EOF可返回empty，仅指传输结束；半包EOF或协议/容量失败使decoder terminal，不跳包重用。成功只在COMMIT与所有结构检查完成后返回一个packet。一次最多一个未close packet，不能预取下一包。decoder借用上游InputStream，关闭decoder不替F2做HTTP graceful drain/abort；stream真实关闭由F2负责。

优先保留一份有界wire段，采用流式JSON结构扫描；F1不建立metadata/payload DOM。header小型不可变scalar facts可保留，metadata/payload只提供受Owner/packet存活与active约束的只读有界借用InputStream等最小出口。不得返回可修改byte[]、JsonNode、完整大String或未收费clone。借用wrapper不能持有绕过close清理的原始array副本；close后必须拒绝继续读取。调用者自己的接收buffer/model为F2独立计费，F1不宣称已覆盖。小header facts也不成为授权依据。

active read/parse/段借用实际退出以前，packet/decoder/Owner.close不能归还仍在使用的reservation；允许明确拒绝并由实际owner稍后重试，无需新异步回收线程。取消标志/Thread interrupt/Future状态不等于退出。成功返回时将wire reservation转给packet，异常在实际finally回收尚未转移资源；close幂等、防重复减账、清除本组件仍持有引用。F2以后须等真实任务、upstream abort/close和actual async.complete退出再close响应Owner。

## 固定wire v1与严格结构

完整继承正式 `iam-stage-4-o2b-e-packet-decisions.md` 及wire/header supplements中的wire要求；它们原E文件保持冻结。prefix24B big-endian：APEV、u16 version1、u16 kind1 HELLO/2 WINDOW/3 CAPACITY、u64 ordinal（0..Long.MAX_VALUE）、u32 headerLen、u32 metadataLen。header1..8192，metadata1..8388608，payloadBytes从完整受限header读取且0..262144。COMMIT44B：APCM、同ordinal、SHA256(prefix+header+metadata+payload精确bytes)。最大wire8659012B，先checked-long及各交集检查再分配，禁止不可信长度先转int/分配。

HELLO仅ordinal0一次，后续连续+1；未知version/kind、乱序/重复/跳号/溢出、magic或COMMIT/段SHA不符、任何半段EOF均拒绝。不以EOF、下一包或下次read补足上个包。每次底层读取至多当前段剩余量及明确的有限copy chunk，无下一包预取；允许底层返回短读。hash增量更新，不拼额外完整wire副本，不用readAllBytes/无界BAOS/toByteArray。

header恰10键：childResponseId/runId/resourceSha256/initialCursor/afterSeq/eventSeq/eventType/payloadBytes/metadataSha256/payloadSha256。严格值类型：childResponseId非blank且≤128 UTF16单位；runId为r_加32小写hex；SHA为64小写hex；cursor非负long、after≥initial；eventSeq正long或null、eventType原非blank字符串或null、payloadBytes整数范围。数值必须JSON integer token，拒溢出/浮点改型，不能用double。header大小/结构限额仍取交集。

HELLO metadata恰receipt:string，ASCII紧凑JWS外形且≤4096，仅外形不验签、不解析后自授关联。CAPACITY恰code，为BUFFER_LIMIT/EVENT_LIMIT；只能在HELLO后。两者eventSeq/type为null、payload0；HELLO after=initial。WINDOW根恰14键 runDbId/owner/acceptedSnapshotSha/initialCeiling/initialCeilingSha256/provenance/rootScopes/stateScope/eventScope/scopes/state/eventIdentity/window/hasEvent，校根直接类型及hasEvent与header/payload的空值一致性：false对应null eventIdentity/eventScope/eventSeq/eventType及0payload；true对应object identity/string scope/非null eventSeq/type及非空JSON payload（JSON null为4B）。完整nested schema、集合唯一性、state/window/refs与业务关系留F2，F1不能把根shape通过称完整验材。

JSON只接受严格UTF8一个值，无BOM/非法序列/未配对surrogate/重复键/尾随第二值。防自动UTF16/32探测绕过UTF8要求。header depth8/tokens2048/string与name4096/number19；metadata depth80/tokens262144/string与name1048576/number1000。字符串限额按UTF16 code units，与E的String.length/getTextLength一致，UTF8 wire字节另限；token包含FIELD_NAME和END_OBJECT/END_ARRAY。必须遍历全部token、强制完成lazy string，不能skipChildren跳过嵌套检查。固定根unknown键拒绝，动态payload/attributes/entitlement键不能被错误套静态字段白名单。

payload只检查一个严格JSON值和有限结构，不做业务schema、row SHA或数值有损转换。接收结构上限depth64/tokens262144/string与name1048576/number1000：原AcceptedReader整个event row已经以root=true满足这些限制后才编码payload，F1单独payload采用相同上限不会额外缩窄原合法事件（whole-row还占wrapper深度/token）；推导见root followup notes具名代码范围。这不是修改四契约的普遍承诺。

scope各自depth32/tokens65536/string65536/≤1MiB、records/parents≤4096及精确schema/业务规范化全部留F2。E writer的字典序与集合排序不能替代业务摘要；F1摘要始终对原bytes计算，不先重排再验，不宣称RFC8785/JCS。不得新增ASCII64 eventType正则，保留Unicode和空格；公开CR/LF安全与最终frame在F2处理。

## 资源预算及实际provider证明

固定共享两Owner、零queue、共享192MiB（201326592B），每Owner总reservation≤96MiB（100663296B）。这是可执行的保守费用模型，不声称Java heap测量值。Owner count与账本需线程安全、checked arithmetic、分配前收费、失败rollback，不引入后台线程或跨Owner大byte pool。

每包wire backing只保留一次prefix/header/metadata/payload/commit，总length≤8659012B；按实际length+每array保守对象头（至少24B）收费，不按logical count给实际扩容免费。读/hash/UTF8 scratch、header scalar/model、parser、dup sets/name/context及临时scalar必须另列，并在分配前收费。streaming JSON工作集固定预留64MiB（67108864B）后才创建任何该包parser/结构预扫；三个JSON段顺序处理，不能同时创建多个parser。这个额度包含无DOM扫描的字符分段、聚合、字段名集合、上下文、数字与decoder余量，实施者必须给可核的实际固定provider分配推导，不能仅常量断言。packet保留raw时，全部raw reservation持续存在；parser退出且相关引用不再被本组件缓存后才能归还其阶段费用。

只用本工程Jackson2.19.0标准约束/strict duplicate detection/有限逐token检查，不自写通用JSON语法。root已具名阅读的上游起点在 `.local/iam-o2b-f1-parser-primary-notes.md`；默认recycler可跨包留存、单TextBuffer segment上限不等于总上界、string约束可能近似、duplicate检测持有HashSet。需核实际所选byte/Reader parser、IOContext/BufferRecycler具体路径并记录所读版本/方法/范围；明确禁用或持续收费可能跨Owner滞留的recycler/symbol/intern缓存。不能因为close调用过就忽略仍在packet/parser/工厂图中的大引用。

如64MiB不能覆盖选定无DOM parser的可证明上界，报告具体分配路径与候选最小修正给root；不静默扩预算、放宽wire/测试或拿一次GC heap峰值替代证明。HTTP/TLS/Servlet/JDK外部buffer不在F1账本，F2会另计；E16MiB不是gateway预算证据。后续F2的DOM、normalization、公开frame和transport仍须从同一Owner/global预算取得额度，容量不足可失败，不承诺最大8MiB与最大token等组合均可装入。

## TDD与有限验收

先写关键失败行为测试；必要时只加能编译的最小签名stub，compile失败/不存在selector不算功能RED。保存真实功能RED（明确断言与错误路径），再实现；有需要的focused修正只重验具体失败，成功阶段不要为记录补跑。新增代码不注册入口，无旧API行为变化，最终仅两新测试类gateway定向执行，不跑原E45/common/PGsuite/全后端。

DecoderTest至少覆盖：十二个Accepted E最终真实向量（file/bytes/SHA见frozen-inputs）与小块增量输入；HELLO/event/null/noevent/terminal/reset/CAPACITY/≥65parents；每段截断/边界EOF；magic/version/kind/ordinal/长度unsigned/COMMIT/segment SHA；duplicate/unknown/trailing/BOM/UTF16/非法UTF8/未配对surrogate；depth/token/string/number与wire限额的边界和+1。测试结构错误时修正外层SHA，避免只触发摘要错误而未检查目标约束。机械恶意向量可在测试内构造，明确其不代表正常PG/issuer正例；实际PG type varchar32，>64字符只做wire测试，正常32个Unicode字符超过64UTF8 bytes仍应保真。

BudgetTest至少验证真实分配/预收费/rollback、两Owner第三拒绝、单Owner单decoder单packet、上一packet未close不能下一read、close后访问拒绝、blocked InputStream/借用期间close不释放、实际退出再回收、异常无残留、不预取下一包、不可信大长度在分配前拒绝。不能只断言日志计数；以真实已分配数组/实际parser backing观察、受控输入读取与静态provider模型结合证明。没有网络/PG/TLS/Servlet或authority模拟测试。

真实向量receipt只作为未认证wire被读入；不得把它说成F1签名/授权通过。F1四文件可以用新工程既有公开Json/Digest等约定，但不跨模块import engine.Reader/Writer，也不链接业务JAR/反射另一个target。正常fixture输入是冻结bytes，不在gateway重实现engine writer生产算法。

每stage实际进程退出后再变更：command/cwd/exit/stdout/stderr/raw、实际Running对应XML/txt、source-epoch、compiler原始.lst立即归档。实际编译必须对应当前bytes，不能用copy保留mtime和旧class造RED/GREEN。新final采用唯一 `iam-stage-o2b-f1-final`、platform-gateway、两新类有限selector；runner是否支持先只读核既有helper，不自行修改。测试数按真实XML，不预造。

## 证据与交还

自有 `.local/iam-o2b-f1-gateway-decoder/`、`docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/`、`.tools/iam-o2b-f1-*`；报告 `docs/evidence/iam-stage-4-o2b-f1-gateway-decoder-report.md`。不得改原E artifacts/八索引/其他root notes。完整报告说明实现、实际provider范围/预算模型、借鉴来源、方法边界、RED/GREEN/失败和真实命令/限制；只已读源码可声称对照。

从Accepted E fixes replay按source608列出的字节复制建立新的完整私有Git baseline；只生成本次不同delta4/new4 raw patch，首次check/apply各一次，最终完整612 names/bytes/SHA精确一致，无after-apply source copy/no commit。物理命令结果在断言/依赖后继前先持久化；成功phase/hash缓存，元数据失败只恢复未完成部分，不重复成功测试/PG/git replay。

有限target仅实际common/gateway compiler input/created、资源、实际common fixtureJAR及actual Running报告；原始.lst直接copy字节，不扫旧target全树，dump/dumpstream独立记录。target-after是历史逻辑lineage，不冒称全后端当前classpath。source before/after/delta4/new4、四文件快照、全部stage/私有.git/输入向量引用或自有copy/真实分配证据/patch/replay/helper/report都纳own inventory，只排inventory自身，handoff提供file/bytes/SHA/count/schema、准确ownedRoots/sourceWrites/写权。

完成后冻结并交还全部source/target/local/evidence/helpers/report写权，回消息仅DONE/测试摘要/report-handoff身份/concerns。root生成四文件complete-context包→fresh独立review（spec+quality）→root有限artifact审计→通过后才限定F1 Accepted/更新八索引；随后进入F2。最终措辞必须是“严格wire decoder及本地预算通过，输出未授权材料；完整来源/授权/验材/公开输出仍待F2”，不得把F1当全F闭环。
