# O2b F1 严格 wire decoder 与本地有限预算实施报告

当前状态：作者实施与唯一 final20 已通过；独立byte-corrected补丁在新baseline首次check0/apply0且完整612字节已验证；原文本规范化回放完整字节失败仍保留，待root独立review/audit。

严格wire decoder及本地预算通过，输出未授权材料；完整来源/授权/验材/公开输出仍待F2。不是全F、全IAM、全后端、生产容量或部署完成。EVENTS依然默认关闭，没有新增HTTP/TLS/Controller/SSE/assembly入口；无commit/deploy。

## 正式输入与变更范围

完整先读正式 brief、AGENTS、implementation-brief、contracts README、frozen inputs与root followup；root boundary clarification按后续绑定读取。本轮使用Java/TDD/SDD implementer/verification/ponytail工作流，未创建新的implementation subagent。遵循root唯四文件授权与冻结旧成果约束，未重跑原E45/fixes成功阶段、PGsuite、common测试或旧patch回放；八索引不写。没有访问相邻旧工程或跨业务JAR。

Accepted source608输入身份沿 `.local/iam-o2b-f1-gateway-decoder/source-before.json` (116139B / SHA256 `303ec0625e9b8491d834cbd0ce27a38c0dcffab77104e333b38f960e102a5adf`)，原Accepted manifest SHA303ec062...；target历史逻辑2148沿 `.local/iam-o2b-f1-gateway-decoder/target-before.json` (447924B / SHA256 `30422b7bcf37c2e31ef6c596ced94543d432bf847bde17b513640d6dba403f51`)，原Accepted manifest SHA30422b7...。本轮source612/delta4/new4，删除/修改既有source均0。

四个唯一新增源码：

- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketDecoder.java` (19120B / SHA256 `0ecf83482fde638be4dd4ad7ae0fefb4ed932bd0ff6347c86334e7100388ba1e`)
- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudget.java` (3537B / SHA256 `c59d9add3cd90b98d51472489a9e596db7aaf01eb322bec6699849760251ebbd`)
- `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsPacketDecoderTest.java` (16600B / SHA256 `2894c560a19ef41db6fd9605d63e2b5599ecaa33bfcced011e220ebadadf2fc9`)
- `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudgetTest.java` (11199B / SHA256 `6d9d394f23377afed8ab72a4b29b2ab02a8cb91a595f51c51d57ffc00b7060a4`)

最终完整source manifest：`.local/iam-o2b-f1-gateway-decoder/source-after.json` (117016B / SHA256 `b391ee9fe99f69b51066006bdb468bcf4750cf2f6bd16a2dc22ae458fae301f4`)（612行），delta/new各4行分别见 `docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/source-delta.json` (880B / SHA256 `24b9dec641d455f7905fe9a9ad2c5f806413667df64bcdcbb314de617a800719`)、`docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/new-files.json` (880B / SHA256 `24b9dec641d455f7905fe9a9ad2c5f806413667df64bcdcbb314de617a800719`)。四文件当前字节与最终epoch一致；小header facts不构成授权证明。

## 实现/API与限制

固定共享 PortalEventsPacketBudget.SHARED 的私有构造器防额外pool；2Owner/零queue、global201326592B、owner100663296B。Owner基础65536B、Reservation每个128B，分配前checked arithmetic收费，失败rollback。Owner只认领一个decoder一生，decoder只绑定同一InputStream实例，不可换流续ordinal。一次至多一未close packet；readNext不预取下一包、短读合法、半包/协议/预算失败terminal。边界EOF仅empty，不构成业务终态。

decoder只在完整COMMIT、精确段SHA、严格结构通过后返回私有构造的UntrustedPacket。Prefix24/COMMIT44、APEV/APCM、version1、kind1..3、u64非负ordinal连续；HELLO仅0一次、CAPACITY后不可追加包。无String/byte[]/JsonNode大副本出口。metadata()/payload()返回受packet/Owner存活约束的只读有界InputStream；每wrapper单独收费并只持packet而不持独立raw array。packet close清wire/header与token；close后不能继续读。调用者接收buffer/model属于F2另收费。

关闭策略为显式拒绝：read/parse尚在物理执行或borrow尚未close时，packet/decoder/Owner.close拒绝并保留账本；实际退出后调用者重试。interrupt/Future取消标志不退账，无后台回收/scheduler。decoder借用upstream，不替F2关闭真实HTTP流或执行Servlet complete。Owner公开reserve允许F2为自己的额外model/frame占同一global费用；该resource token没有业务权能。

JSON为显式REPORT UTF8→ReaderBasedJsonParser，完全遍历token并完成lazy string，严格dup/单value/unknown root/无BOM/非法UTF8与未配对surrogate。Header恰10标量、WINDOW恰14根键/直接类型/hasEvent一致性、HELLO只验compactASCII JWS外形、CAPACITY只有两个code。动态payload/attributes不套静态白名单，不做state/event规范化、row SHA、nested完整业务schema或任何签名验证。eventType保留原始Unicode/空格，不添加ASCII64正则。摘要始终计算原wire bytes，未宣称JCS。

## 64MiB固定provider费用与实际分配

静态具名路径/具体上界与生命周期完整列于 `docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/provider-model.md` (7474B / SHA256 `d2a7f7eeb4540b4fb0f8930d8334b715922882befcbe8cd1fb95601d284de502`)，机器公式/本地Jackson JAR/primary文件身份与实际观察列于 `docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/provider-evidence.json` (8707B / SHA256 `801d6eae881f467bda81f07a00aea25efa5c4706fe498628e57faf9f8ca0ecfc`)。

模型上界为256×131074 + 2×8388608 + 10×1114112 + 4194304 = 65667584B < 67108864B，余1441280B。分别覆盖存活name String/数组头/tree-node/resize表、name UTF16字符内容、最大scalar的分段聚合与UTF16/Latin1短暂副本、reader/parser/factory/context/recycler/hash/小模型余量。wire在其外按实际length+32B收费，prefix/header临时数组另收费，没有扩容赠送。所有费用先预留，三个段顺序创建局部factory/parser；禁canonicalization/intern/threadlocal recycler，不让关闭parser仍持有的局部图进入packet或共享缓存。scan不让Jackson异常/cause带parser图逃逸后再退账。

实际观察（最终XML/raw system-out）：

- `F1_PROVIDER_BACKING parser=com.fasterxml.jackson.core.json.ReaderBasedJsonParser inputChars=4000 aggregatedChars=1048576 scanCharge=67108864`
- `F1_REAL_SCAN_ALLOCATION threadAllocatedBytes=5412560 wireFixtureBytes=1049915 proofKind=allocation-observation-not-live-heap-bound`
- `F1_WIRE_ALLOCATION bytes=494 charged=654 prechargedScan=true`

这是保守可执行分配费用模型；实际backing与ThreadMXBean累计分配只佐证执行分支，不能代替静态总上界，未称GC/heap测量上界。HTTP/TLS/Servlet/JDK外部transport、F2 DOM/normalization/frame不在该64MiB既有成本内。

## 真实TDD与各阶段

所有shell cwd均为G:/207/项目/算法重组工具/代码/agent-platform。阶段由自有helper显式Maven离线只选platform-gateway两新增类/具体方法，-am只建立common依赖，没有common Running。每次实际进程退出后先持久化command/exit/stdout/stderr/raw，再归档实际Running对应XML/txt、source-epoch与compiler原始.lst；四新源mtime显式touch使当前字节真正javac，未通过旧class伪造RED/GREEN。

- `iam-stage-o2b-f1-red`: exit1，2 tests / 2F / 0E / 0S。command `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-red/command.json` (501B / SHA256 `ff6371f9b282ce88f3eeae9e1d4f3e493fe3722f648059856b3f14d2a0cb2d28`)；epoch `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-red/source-epoch.json` (117013B / SHA256 `f73e0130466b97e06bbf3e15ad6102455bc25dc1b764b9615135b7b84edb3c99`)；raw `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-red/raw.log` (8306B / SHA256 `8ffbeca1c532ec3641d15fe4a140c77e3322cd1403748a23f2ea6ed6e8df9c18`).
- `iam-stage-o2b-f1-green`: exit1，0 tests / 0F / 0E / 0S。command `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-green/command.json` (434B / SHA256 `14c80abc815757bf6921c18db46767efcd1f2084eea005c84a99d7e79361c12b`)；epoch `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-green/source-epoch.json` (117015B / SHA256 `8488af1e2fdd18a6533c799b98ca19dd4055885261ad622968de02526a7496c0`)；raw `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-green/raw.log` (8138B / SHA256 `8f72570d01028ff9b3177d881f65ae5c7cb3789203aa95549902f5cea5916e89`).
- `iam-stage-o2b-f1-green-compiled`: exit1，17 tests / 2F / 0E / 0S。command `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-green-compiled/command.json` (434B / SHA256 `14c80abc815757bf6921c18db46767efcd1f2084eea005c84a99d7e79361c12b`)；epoch `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-green-compiled/source-epoch.json` (117015B / SHA256 `40f9b5272ed221bba8ed526fbe1b4aa316d83aed4b606df61e8c162ff268d680`)；raw `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-green-compiled/raw.log` (9352B / SHA256 `b313330a29b436690c051cdc574bbc5da58d2c44218b78389645a969ca57df64`).
- `iam-stage-o2b-f1-focused`: exit0，5 tests / 0F / 0E / 0S。command `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-focused/command.json` (823B / SHA256 `0c330ff723879cf4dea37986391c55447f4849e7018865180dc1765560ea30e8`)；epoch `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-focused/source-epoch.json` (117016B / SHA256 `7d2640ece2813868343b59cce286891e75432fa04be9adc5816089df203fa55f`)；raw `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-focused/raw.log` (5898B / SHA256 `d8d9e1cd7b28c6cae0905a38c8bafc62f3fae53d20b7ada1ebbd0466a22f8aba`).
- `iam-stage-o2b-f1-final`: exit0，20 tests / 0F / 0E / 0S。command `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-final/command.json` (434B / SHA256 `e9ed5ffbee10c912956677b10f23a6c058a0be2f67a9279c46dffaad38307893`)；epoch `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-final/source-epoch.json` (117016B / SHA256 `b391ee9fe99f69b51066006bdb468bcf4750cf2f6bd16a2dc22ae458fae301f4`)；raw `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-final/raw.log` (6236B / SHA256 `f6a3ffeb0c7de830970a765c4dbbf629253af8d3043c7ffc578212e1ce527b5d`).

首功能RED为真实断言：twoOwnersThirdRejectedAndCloseRestoresSlots要求2但stub0；magicFailureIsTerminal要求IOException但stub返回empty，2F/0E。后续首green是JsonFactory.Feature disable没有三参重载造成编译失败、0Running，不算功能RED；分别调用disable后，compiled17的decoder10全通过。budget首两失败来自测试忘记borrow自身384B收费，首断言失败又保留测试borrow，导致后续全局残留断言失败；修正预期在borrow之后取live，未放松生产预算。focused仅两受影响用例及新增精确budget/provider/eventType验收5通过。另一次带+的selector被own helper grammar在启动前拒绝，0进程/0test，不算RED；随后用逗号重复具名selector执行。未补造“所有20例逐一历史RED”。

唯一final iam-stage-o2b-f1-final：Budget9+Decoder11=20 tests，0F/0E/0S；真实javac16 production/13 test，common源码未变所以Maven提示up-to-date，无common测试Running。final成功后不再测试/改source，收集与metadata恢复不重跑成功阶段。结果与逐方法XML身份以 `docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/test-results.json` (15494B / SHA256 `9f4a8dbff20a3799d2cf62573f0f3a62ffab04915b8b92e9daedb4311ab24c39`) 为准。

## 验收覆盖与交集

- 12个Accepted E真实encoder文件逐file/bytes/SHA核对，经底层每次≤7B短读共25包；覆盖HELLO/event/null/noevent/terminal/reset/CAPACITY和metadata≥65parents。receipt始终作为未认证wire读取，未称F1验签/授权成功。具体输入身份 `.local/iam-o2b-f1-gateway-decoder/input-vectors.json` (2859B / SHA256 `b3e568480d5349a5f516810f67e08c553557ee90bcf12a91ab96debb98a94ecb`)。
- Prefix/header/metadata/payload/COMMIT截断、边界EOF、magic/version/kind/重复HELLO/乱序跳号/unsigned长度、COMMIT magic/ordinal/SHA与两段SHA。Long.MAX_VALUE耗尽用内部field机械设值衔接正常HELLO再送完整max包，不伪称发送了2^63个包。
- 重复键（含nested）、unknown根键、尾随第二值/BOM/UTF16/overlongUTF8/编码surrogate/转义未配对surrogate；结构恶意包由测试packet构造器重算精确段SHA与COMMIT，不把外SHA拒绝当深层限制证明。
- 可达metadata depth80/81、token262144/262145（含end）、string/name1048576/+1、number1000/+1；payload depth64/65。payload256KiB比1MiB string与262144 token更先约束，header恰10 scalar使depth8/token2048孤立最大值不可达，未放松schema伪造正例。header integer Long.MAX_VALUE/20digits、child128/129、receipt4096/4097、eventType4096/4097与32Unicode字（96UTF8B）保真。
- 最大wire8659012B由真实header8192+metadata8388608+payload262144+prefix/commit构成；段长度0/+1/u32max在分配前拒绝。
- 两Owner第三拒绝、owner96MiB/global192MiB精确边界/+1/checked溢出rollback、输入绑定、单decoder/单packet、不预取、close后读取拒绝、借用期间关闭拒绝、真实阻塞read被interrupt仍持账、实际退出再回收。反射只检查本新增类/实际Jackson provider或机械ordinal seam，不链接/反射其他业务target，无生产testhook。

## 有限target与回放

有限target仅实际common/gateway compiler input/created列表、这些列表指向的classes、已知source资源、实际common fixture JAR以及本轮actual Running报告。293行overlay `.local/iam-o2b-f1-gateway-decoder/finite-target-overlay.json` (65818B / SHA256 `f73bb8d131d88cc5507510ac08d43d605734dbe06f1879a15c916cb34be84fa0`)；历史逻辑target2148→2167行 `.local/iam-o2b-f1-gateway-decoder/target-after.json` (452264B / SHA256 `86cf879d8893d7b49bcec0e261a262e1339596f2ce5c431e37190cb07c7530f0`)，不冒称全后端当前classpath。原始.lst byte copy与计数见 `docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/compiler-lists.json` (5196B / SHA256 `6ad7e66e02eb630814e5b7a27219cbd892b88d8f6cb57f8aa05fa3feef09a346`)。dump/dumpstream单列 `docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/diagnostics.json` (1435B / SHA256 `68f98235a8f53611bf404d7e8527b9d56fa017360d6b8c97421cdf7b9e19bfcd`)，没有扫描旧target全树。

原Accepted608按manifest精确copy建立新privateGitbaseline，无commit。原首次source.patch是delta4/new4，首次check/apply各0且只一次，但完整612核对发现两production只有末行CRLF被repo.gitattributes规范化为LF（source3537/19120B，replay3536/19119B）。发现后没有重跑test、没有after-apply source copy/擅自改source或原结果，已上报root。依root正式恢复裁定 `docs/evidence/iam-stage-4-o2b-f1-byte-replay-recovery.md` (2871B / SHA256 `d355760e725da552003f3f83ad401cfc9b21c7409388cb92ffa4a7306e0fa1f7`)，另建byte-corrected-replay，从Accepted608各文件只copy一次，before add写私有.git/info/attributes的 * -text -diff，并实际check-attr证实四路径text/diff unset。从同一未改source生成另一份不同binary/full-index补丁source-byte-corrected.patch，在新baseline仅首次check/apply各0后逐612 names/bytes/SHA精确相等。没有after-apply copy或修改旧replay/原patch；没有重测或修改source/target。原失败manifest/两mismatch保存于 `docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/original-replay-incident.json` (2596B / SHA256 `98252f1ee1ec22e46c8cc92d0e0f74f197695dc88bb120b9b64041499b18f661`)。新独立恢复记录：{"status":"BYTE_CORRECTIVE_ARTIFACT_FULL612_EXACT","files":612,"namesBytesShaExact":true,"delta":4,"new":4,"patch":{"file":"docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/source-byte-corrected.patch","bytes":22378,"sha256":"84170b4f2c090cc051e4f9c50b7c1d9ab66fcd1ecb873ede327b453bb7348101"},"commands":{"file":"docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/byte-corrected-replay-commands.json","bytes":41838,"sha256":"0a1c5dbf108f490438d5e2cdb7205500bd186fc8af3f470e3327f886e44d52d5"},"source":{"file":".local/iam-o2b-f1-gateway-decoder/byte-corrected-replay-source.json","bytes":117016,"sha256":"b391ee9fe99f69b51066006bdb468bcf4750cf2f6bd16a2dc22ae458fae301f4"},"replayRoot":".local/iam-o2b-f1-gateway-decoder/byte-corrected-replay","check":0,"apply":0,"checkAttempts":1,"applyAttempts":1,"noSourceCopiesAfterBaseline":true,"noSourceCopiesAfterApply":true,"commitsCreated":0,"originalFailedReplay":{"root":".local/iam-o2b-f1-gateway-decoder/replay","incident":{"file":"docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/original-replay-incident.json","bytes":2596,"sha256":"98252f1ee1ec22e46c8cc92d0e0f74f197695dc88bb120b9b64041499b18f661"},"patch":{"file":"docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/source.patch","bytes":52743,"sha256":"d443c66e6f47668c07d01d4794ea29c747d31cc80d7cc561bc5c6ef754cc8a6e"},"commands":{"file":"docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/replay-commands.json","bytes":79423,"sha256":"878801a4cf47f866077075a43b2d0494edd12a7441dd831dbab2fbfbaf9617ea"},"source":{"file":".local/iam-o2b-f1-gateway-decoder/original-failed-replay-source.json","bytes":117016,"sha256":"47b8d5d27974fb0094ab2a6cc4073bd85b32462f7da0b4fa0fe661e0492a3c3f"}},"noSourceTargetTestChanges":true,"noTestRerun":true}

## 自审与交还

四文件实现只完成本brief，未增加authority fixture/通用parser或lease框架。独立review与root有限审计未完成前，不使用F1 Accepted称谓。完整nested schema/集合唯一性/scope限额与业务规范化、真实G/receipt/current、TLS来源、公开SSE与cursor推进均保留F2。

seal时handoff/own-inventory覆盖自有local/evidence/helpers/report、private.git、源快照、实际阶段与target overlay、input向量引用及provider源码身份；inventory只排自身。报告身份/handoff/inventory最终file/bytes/SHA在交还消息给root。全部source/target/evidence/helper/report写权在seal后交还并冻结。
