# O2a B 有限事件材料读取器实施报告

作者状态：**DONE_WITH_CONCERNS，尚非 Accepted**。无 commit、发布、部署或打包。最终交付为 root 明确授权的 **labels-fix epoch：final02 两类31/31通过**。v1 的 final01 仍为 **6类65方法、64通过、0 assertion failure、1既有测试setup error**，原始 `BUILD FAILURE` 与 v1 源码/target/patch/replay 均保留；不能称全回归通过。两epoch不合并为一个suite，最终身份见第9节。

## 1. 冻结依据与范围

完整读取正式 brief、preparation、项目 AGENTS、implementation-brief/contracts 及直接涉及的本工程实现；采用 TDD、实施者模板、自检及 verification-before-completion。原8路径不扩展，C/HTTP/SSE、receiver、application 装配、POM、migration、前端、八执行索引均未修改。所有 shell 的 cwd 为 `G:/207/项目/算法重组工具/代码/agent-platform`。

正式 brief：19412 B，SHA `879d6077463aafc5e063a3eb0af58b3600cd4f27d512afbff8d449fa8eb7a340`。preparation：14456 B，SHA `93db0efc47403968dbb367b756d186864ce5621827d9dd9c169941b3eaf1b46f`。

root 三份补充决定均已实际阅读，分别约束额外表示预算、公共纯校验缓存及既有回归冲突：

- [预算决定](iam-stage-4-o2a-b-budget-decision.md)：3120 B / `f4204a3d8bc99e00d0fbeccc66151d4c491fd63b6e63c5c27c08cb04cd7f9cd2`。
- [缓存寿命决定](iam-stage-4-o2a-b-parser-cache-decision.md)：2359 B / `e7c184bc8402301526da23e08bf6c60d725131ede442d20cc752fc36741f8cc9`。
- [既有测试决定](iam-stage-4-o2a-b-inherited-test-decision.md)：3076 B / `646ea3769a7a5ae749ee0c288682ec39a8d5cc7a601a0fde5da5f20b264d3309`。

Accepted589 已在实施前一次逐项校验并复制到 `.local/iam-o2a-b-material-reader/baseline/`；该成功阶段没有重新执行。source-before：111999 B / `01121a4739ff5a0299cce9e5ecaa0ab5d433cd0be177ffd5d03f9410c67fe346`。v1 source-after：**593 项**，112856 B / `c6fcbc29f7cb3a125cb36bc0073971908be0a704b03a26a88c0697a657266ff1`；labels-fix 最终 source 身份见第9节。

逐文件差异恰为以下8项；v1 bytes/SHA 在 [changed-files.json](iam-stage-4-o2a-b-material-reader/changed-files.json)，最终在 [labels-fix/changed-files.json](iam-stage-4-o2a-b-material-reader/labels-fix/changed-files.json)。

| 文件 | 最终改动 |
|---|---|
| PortalEventsResponse.java | Capture 仅增加 initialCursor/remainingNanos 两个 synchronized(holder)、先 requireLive 的本地 getter |
| RunAuthorityStore.java | 新 package-only 有限 control/initial observation binding；不调用旧 load/scopes |
| MaterialScopeStore.java | 新有限 probe、CASE text、完整 scope/parent/floor/locator/root/event 校验；复用纯规则，旧 writer/status 不改用途 |
| TraceStore.java | 仅 requireSnapshotRelations 从 private 改包可见，旧调用/语义不变 |
| 新 RunEventReader.java | DataSource owner、2 permit、独立只读 RR、单调期限、预算、单事件 emission/borrow |
| 新 RunEventReaderPostgresTest.java | 最终18方法（v1为17），真实独占 PG、生产 writer、生命周期及测量 |
| 新 RunEventReaderBudgetTest.java | 最终13方法，parser/累加/表示/签名 Capture 机械边界 |
| 新 RunEventReaderAuthorityFixture.java | test-only 真签名 E/JWKS/JWT/authorizeRetained/trusted-frame helper |

初期一次文件名定位过宽，列到了本工程历史 evidence 的名称，随后全部改为 manifest/具名文件读取；没有据此读取或校验旧 target 树，更没有访问相邻旧工程。不存在的 fixture/迁移文件名误猜随后由 manifest/具名目录定位纠正。

## 2. 生产行为与权限边界

唯一入口是 `RunEventReader(DataSource).readNext(PortalEventsResponse,long)`。reader 不注册 Bean/route，没有 Actor、执行 Retained、STATUS/Q 或公开材料 DTO 替代入口。Capture 在 PG 外取得；负 afterSeq 或低于签名初始游标在 permit/SQL 前拒绝。afterSeq 大于初始游标可作为本轮进度；跨轮单调由 C 保证。

所有 ambient transaction（同库 RR 也包括）拒绝。reader 自建同库 readOnly REPEATABLE_READ；无在线 authority/open/renew 调用。owner/publicId 查找仍给未知/他人404；TASK/FULL、unsupported/hasTools、控制关系和 accepted snapshot 资源不符拒绝。同 owner 的新登录/不同 origin 可读旧 run；过期历史 grant 不恢复为执行 proof，也不独自阻断当前观察。

有限读取顺序：

1. owner/control/initial 的固定投影与 UTF-8/type probe，control<=256KiB、initial<=1MiB，随后 CASE 和受限 parser；核 owner、root/app/version、accepted/candidate、initial digest 和历史 provenance。
2. 三根的身份、scope 字节、JSON/parent 类型和数量；下一事件固定 `run_id AND seq>after ORDER BY seq LIMIT1`，只 locator/seq/type 标量。
3. selected scope 的有限全文给出完整父 hash 数组；按同 run、精确 hash 集、`material_revision<selectedRevision` 取所有直接父，限额+1用于拒绝，缺失/重复/未知/超限不当完整成功。三根去重。
4. 所有 scope 单份/总量/records 先过预算，再读取完整 CASE scope；完整 digest/schema/producer/kind/locator/owner/initial/version/parent 唯一性和各自 provenance 校验；直接父校验真实 row/locator/hash/先后。父完整数组保留并逐份计费；不递归祖先 payload、不换 latest。
5. 所有 scope 均由同 Capture.requireMaterial 验证，三根按严格 admission 关系，selected 按完整直接父求 floor；保留 dataLabels 义务。全部完成前 root/event payload getter 为0。
6. snapshot/refs 先 probe、root 合计预算再 CASE；不消费 inputs，完全不读取 inputs payload。沿既有精确数字/对象 key 排序摘要、accepted hash、snapshotRelations 和 GraphRules/pure FLOW 关系。最后读取固定 `id,run_id,seq,type,payload,created_at` 完整事件投影并验完整行摘要，UTC微秒与原 writer 一致；公开 payload 的256KiB独立限额不被完整行1MiB替代。

空轮仍核必要绑定、三根及消费的 snapshot/refs，返回 empty；它不代表 run terminal。批次/解析、payload前、事务内末尾、`tx.execute` 真正 commit 返回后、mint前和 emission 使用均检查同 capture。generation/stop/过期/迟到取消返回的结果丢弃。

进入时 nanoTime 建一个保守总 anchor，与同 Capture 当前剩余取 min。参数 prepare/bind 后设置 PG LOCAL statement_timeout，值为剩余纳秒的毫秒 floor，0 永不设置，不足1ms不执行 SQL；最多3次有限重设用于 SET 自身跨毫秒边界，未稳定即拒绝。完成 SET 关闭后的 loop 末尾是执行前最后 live/remaining 检查。不能消除检查到 JDBC 发出之间的时间，也不声称期限在某个瞬时物理打断已阻塞 JDBC。最终新生成 timeout-trajectory 文件记录 driver 调用时的剩余纳秒和已设置 timeout；断言容纳1ms观察间隔，不把它解释成授权延长。

## 3. Permit、emission 和缓存寿命

reader 仅2个 Semaphore.tryAcquire workspace。准备、实际 JDBC/parser、commit 后 pending emission、同步 active consume 均持原 permit；成功 mint 原子移交同一 lease，没有释放/重申请或第二结果池。第三个立即 STREAM_CAPACITY。逻辑 close/Future.cancel(false)不提前归还仍在 JDBC/consumer 中的 permit。空轮/异常在真实 finally 释放。

EventEmission 构造器 private、一次性消费。最终 Info 仅为不可变绑定/标量/计数，不含标签集合；不导出 JsonNode/String/byte[] 大 payload getter/copy。Borrow 支持 guarded Info/live 和同步 OutputStream 编码写入 payload/标签。pending close 清 payload/labels 大引用并释放一次；active close 标 closing，禁止后续使用，真正 consumer finally 后清引用并释放；consumer 异常和旧 generation 同样不泄漏，旧 emission 不因新 current 复活。标签修复的独立 RED/GREEN 和预算解释见第9节。

B 两个 Jackson factory 禁用 USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING、CANONICALIZE_FIELD_NAMES、INTERN_FIELD_NAMES，自身 canonical/编码用同一 non-recycling 本地 writer。否则“临时”parser buffer/字段名表可能跨调用保留。

不可省略的 common scopeDigest/ceiling 校验仍使用原 Json。实际2.19 JsonRecyclerPools.ThreadLocalPool.acquirePooled 调 BufferRecyclers.getBufferRecycler；Json.mapper().copy() 实际测试也取得同一实例。BufferRecycler 固定4 byte+4 char槽，公开 alloc 方法 getAndSet(null)；empty 默认 byte合计20000B、char8400个=16800B，总36800B，包含在固定8MiB scratch中。entry drain 防借用已有大缓存；实际 finally 对当前线程逐槽取出丢弃、不归还，不全局 release/反射/遍历线程。非空结果在 mint/transfer 前先清理，cleanup 失败不会遗失 pending lease；异常 finally 保留原异常并确保释放。consume 的实际 finally 同理。

独立 RED08 观察当前线程262144-char buffer未清；GREEN06通过，另一个线程262145-char buffer不受影响。公共权限算法、全局 factory 和 A 生命周期没有改动。

## 4. 容量及表示账本

原限额全部同时生效：scope/initial1MiB；metadata8MiB；records4096；每scope parents4096；control256KiB且计metadata；root8MiB；event完整行1MiB；公开payload256KiB。metadata parser depth32/token65536/string/name65536/number1000；root depth64/token262144/string/name1048576/number1000。token 含字段名；streaming pass 在 DOM 前强制完成 text、检查重复字段/尾随token/类型，再建立 DOM。long 累加使用 `amount>cap-current`，避免溢出。

root 另批准 model64MiB、canonical8MiB、全部同时存活表示192MiB/workspace，两 permit owner384MiB。这些是额外相交预算，不改变 raw/writer/schema/历史，也不承诺所有单项 raw 合法结构都被接受。

| 表示 | 分配前计费/生命周期 |
|---|---|
| scalar plan/maps/固定结构/cache清理 | 每workspace预留8MiB，含至多4097个有限probe、map/array backing、8个默认recycler槽 |
| JDBC raw/UTF-8、UTF-16、长度检查编码副本/driver slack | 取全文前按已probe UTF-8×6 reserve，逐记录真正退出后释放；单份root最大48MiB |
| streaming token、duplicate detector、字段名/text、segment/consolidation、第二遍DOM parser临时区 | parse前固定64MiB reserve，两个parser实际退出后释放，禁止factory/线程保留大buffer |
| DOM/DTO、完整父数组和decoded strings、规范化/排序节点/集合backing slack | DOM前按512/token + 8/raw-byte累加，model合计<=64MiB；逐记录转换但保守账本不回收已计model额度 |
| canonical字符segments/结果String/UTF-8 digest副本、编码 | 先按precision/scale及树字段/字符累计校验encoded上界<=8MiB，再以×5 reserve（最多40MiB）；真正转换返回后释放 |

model估算单元包含DOM节点、DTO/父数组、normalization/sort副本和集合扩容slack，不能等同于精确JVM对象大小。512/token 给小节点/集合结构留量，8/raw-byte 给 decoded Unicode/string/完整父hash表示留量；probe的schema受限scalar列和固定数量容器由8MiB独立scratch覆盖。raw和canonical不借用这些上限。parser与canonical阶段不重叠（parser close后才进入canonical），最坏阶段分别64+48+64+8=184MiB、64+48+40+8=160MiB；总 Ledger.reserve 仍在每次分配前以192MiB硬拒绝，并在finally释放临时预留。短指数 decimal→巨大整数在toBigIntegerExact之前按precision/scale估计拒绝，未捕获OOM假装预算成功。

这是保守可达表示/预留模型，不是JOL对象堆或GC峰值实测。deploy compose/k8s的1Gi容器、MaxRAMPercentage70约716.8MiB heap；384MiB之外约332.8MiB仅为算术余量，不证明整个引擎或生产容量。部署未修改。

4096父至少需要4097记录，受总records约束，不存在这里声称的4096父端到端正例。C/D/H公式仅辅助；实际样本记录完整重复父数组，不按唯一hash压缩计费。

## 5. 真 PG 样本与机械容量

独占数据库 `agent_platform_iam_o2a_b_material_v12`，先严格 current_database 白名单；本工程账号/密码仅从 .env 进入子进程，未打印。正常 engine Flyway到217（18业务migration，Flyway日志包含其schema记录）；不删除测试库/历史、不裸插/删改任何业务 material_scope。

每份样本均为新15节点 `START→13 CODE(expression字符串1)→END`、14条from/to边；实际 GraphRules + RunService.createReservedSessionTurn storage sentinel，以及错误 source/target 边对照。生产 createPublished/15次createNode 同事务，事务外实际 currentRetained(run.advance)+TraceStore.event；未启动 worker/调度。

| 文件（任务evidence目录） | 实际结果 |
|---|---|
| writer-sample-0de19b58-16bc-49b1-8458-060c1a6cf169.json，GREEN02 | N15/A0/E1/U0，直接父67，所需scope68，H2180，scope191375B，max5182B |
| representation-sample-2bedc046-e89e-49d1-84fa-961f44941c39.json，GREEN04新增测量 | 另一run，scope191440B/max5183B；parser临时预留当时32MiB，总reserved峰46551054B |
| writer-sample-693a8f16-e5c3-44bd-89e8-1750a4451b98.json，final01 | N15/A0/E1/U0，67父/68scope/H2180，scope191440B/max5183B |
| representation-sample-ab027ec9-d490-465b-b55c-c8c73d3a6a1b.json，final01 | 最终64MiB parser预留下，model4607048B、总reserved峰80105486B、raw预留峰31098B、canonical预留峰7864320B、5967tokens、parser实际getTextCharacters返回backing最大5183chars、event实际数组backing58B |

**canonical实际1199B只观测 B 本地 `w.digest` 的normalized UTF-8峰值，不含 common scopeDigest/Json.canonical 的直接实测**，不能称全局canonical实际峰。common校验仍被每份scope UTF-8×6的encoded上界和×5临时预留覆盖；本最终scope最大5183B，对应common保守encoded<=31098B、临时<=155490B，并与model、raw/临时总账本相交。首次writer-sample中的eventEncodedBackingBytes来自输出size口径，不独自证明ByteArrayOutputStream扩容容量；最终新增measurement用OutputStream实际收到的原byte[]长度58直接核对emission backing。

root允许final后一次仅具名immutable run的尺寸补录：`r_e401e36a9bc34090bf7bee7f9a9473da`，id35，snapshot1355B、refs13B，合计1368B，均object。这是 final 后单独scalar observation，不假称原measurement trace已记录。先 `current_database` 严格校验，再只取id/public_id/两octet_length及固定类型，未取payload/scope/history。原始CSV、实际命令/cwd/exit与SHA在 [root-size-post-final/artifacts.json](iam-stage-4-o2a-b-material-reader/root-size-post-final/artifacts.json)。结果JSON192B / `0e8068a16f217dd14e2adb0d8a87e9f01a7240d64925da87938f0fa2023f71de`。

真实writer产生 `padding` 字符串长度262144的event，reader首先触发 EVENT_PAYLOAD，event_text getter0；这证明真实writer容量拒绝，不是通过缩writer/schema造样。精确1MiB/+1、8MiB/+1采用真实PG参数化VALUES的相同octet_length/type/CASE形状；metadata/root合计先probe再经实际Limits路径拒绝，+1不获取大text。它们是机械SQL数据集，不冒称业务授权正例。精确parser/parents/records/long溢出同样是机械路径；不能由单项边界推导一份结构同时合法达到全部边界。

所有样本/SQL/trajectory身份见 [samples.json](iam-stage-4-o2a-b-material-reader/samples.json)。各样本不同run/epoch，原件未合并改写，没有重跑成功的独立物理测量来修补元数据。

## 6. 测试、RED/GREEN与失败修复

逐阶段实际命令、cwd、exit、Running类及raw/XML身份在 [commands.json](iam-stage-4-o2a-b-material-reader/commands.json)，23319B / `87dd81d2880c3992b0ed8773cb9aa06040af17f1805ba1e19ae84f164e8360f5`。使用原runner且每stage唯一，无`+`连接方法。编译/配置错误不算行为RED；成功后只新增/实际失败方法再focused，最终affected command一次。

| Stage | 实际结果/解释 |
|---|---|
| red01 | 2 errors，fixture先构建client后设置Portal actor导致ceiling配置错，不算RED；改test helper初始化顺序 |
| red02 | 真writer正例建立成功后，默认拒绝reader无法产emission；签名Capture缺两getter，预期行为RED |
| red03 | 3失败：原无限parser/空预算在depth/累计预算等负例未拒绝，行为RED |
| green01 | 2类5方法通过 |
| red04 | test访问package字段编译错误，raw独立保留，不算RED；改用受信test helper |
| red05 | 1失败，精确precision/scale测试拦截原toBigIntegerExact expansion；不实际分配巨大整数，行为RED |
| green02 | 2类17新增具名方法通过；不与green01加总成一个suite |
| red06 | 1失败：参数准备延迟后SQL沿用更早较大statement_timeout |
| red07 | 1失败：尚未采用root新的64/8/192MiB表示硬限额 |
| green03 | 4方法，2预算通过、2PG errors；额外第二次millis检查与已完成SET的floor形成边界误拒绝，原栈保留 |
| green04 | 仅2个失败PG方法通过；将prepare/bind移到SET之前，SET关闭后loop末尾作为唯一执行前终检，仍检查同capture/remaining和不足1ms拒绝 |
| green05 | 2类3新增方法通过：PG聚合8MiB/+1、pending close与一次消费、canonical/long溢出 |
| red08 | 1失败：当前线程262144-char recycler buffer在B实际退出后仍保留 |
| green06 | 仅新增cache寿命方法通过，另一线程缓存保持不变 |
| final01 | **6类65，64pass/0failure/1error**；新B两类30全绿，既有Initial测试setup冲突迁移pending |

v1 原正式最终命令（已冻结，不重跑）：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2a-b-final01 engine-runtime RunEventReaderPostgresTest,RunEventReaderBudgetTest,FlowMaterialScopePostgresTest,FlowMaterialStatusPostgresTest,InitialMaterialScopePostgresTest,GraphRulesTest
```

实际 Running：GraphRules1、FlowMaterialScope11、FlowMaterialStatus11、InitialMaterialScope12（1error）、RunEventReaderBudget13、RunEventReaderPostgres17。65不是编译文件数、历史XML或多轮累计。存在既有deprecation/unchecked编译提示与Flyway INFO；不称输出完全无警告。

v1 final raw：38318B / `268579ab99fad5b83276cc67e08b4d0c19c04ca9a08166b813cdcb922631a0ad`。Initial XML：55936B / `c743031e7e7cd218deae64e03017848d58a77a24f6e758ac96fe8ee56605fff7`。两者保留在任务evidence的 `iam-stage-o2a-b-final01/`，root已独立核。失败栈在旧测试132→exchange140→portalExchange151→query455→denied11，尚未到133行read断言；旧测试用STATUS+app-version ROOT违反Accepted A的run-snapshot selector。B没有改它或放宽权限。root已决定单文件迁移另做，v1源epoch/原最终状态不变；labels-fix另有第9节新epoch，既有测试迁移仍需C开启前闭环。

实际行为覆盖包括：完整scope后才payload、metadata拒绝payload0、真实payload摘要篡改payload>0且无emission；同连接RR/readonly/autocommit=false；ambient RC/RR拒绝；同RR并发append下一轮见新seq；新登录不同origin、历史grant过期和他人/tenant/resource拒绝；本地getter过期/关闭/generation且0HTTP；commit返回后撤权；两workspace第三个拒绝；真实PG表锁期间逻辑cancel不早归还；pending/active close、阻塞consumer、异常/重复消费/旧generation无泄漏；scope父数组、token/name/string/number/depth、重复字段/trailing/类型、累加溢出及表示交集。

## 7. 源码、target lineage、patch/replay与封存

本节列 v1 冻结身份，原件不改；最终 labels-fix 身份另列第9节。v1 source差异manifest1686B / `4f990488c698cede279580e1d8c30abf977a63e1a555cf4919c40a9e45f758f4`。旧测试始终未修改。

target不是纯编译树：原逻辑2023项 manifest419609B / `244535cb02df60585217088e321f2b6ac44aaca20de22821a080d66d41967e20` 原样继承。只从本次common/engine的实际createdFiles/inputFiles、对应resources、common test-fixture JAR和6个实际Running XML/txt取有限465候选。

- target-overlay：102384B / `bb89a514cbbeb799c92649b640acd0721e2e9c9a402198d581946ca45fcff590`。
- target-after：**2053逻辑项**，426227B / `e948c378f034bb2f30b321daf92ac28c1ad6de0716c2e5ed92edc31f2194d59b`。
- compiler-inputs：32607B / `1f866533bbc766230b53f5c3556b08527b4c6287719eac59b88db033a1c174b6`，包括真实路径；createdFiles/inputFiles自身也进overlay。
- 新14份诊断仅单独归档，diagnostics.json3055B / `f0157fcade0dbd16c9de3f39d5971a1d338442fbcb47602631e44238e41fe454`；不进入compiler/logical overlay。历史908 dumpstream条目不重扫、不改删，逻辑清单继承。target成功仅指有限产物捕获完成，`finalTestStatus=FAIL_EXISTING_TEST`明确保留。

byte-exact source.patch：90274B / `f847e5356c5fff40e5a6b2613f55890e1ccec08edb94beec53ebf66c923925eb`。private-build/private-replay均在本任务local目录，只有private git init/index，无commit；未向项目.git注入属性，也未git add -A。

第一次 `git apply --check --binary` 和第一次实际 `git apply --binary` 均exit0，真实命令/各自cwd/退出码在 replay-commands.json4361B / `35868d86aa79f4488adb2f329cc26407b623dc85be569f6e01484e7acd21fc1e`。未通过后拷贝修复replay。replay的全部593源名称/bytes/SHA与最终清单一致，replay-source manifest身份与source-after完全相同。成功replay、target捕获、基线阶段均缓存于journal，未重复执行。

实际上游机制读源与片段/范围/原件SHA在 [upstream-read.json](iam-stage-4-o2a-b-material-reader/upstream-read.json)：Jackson core2.19.0的constraints/ReaderBasedParser/JsonFactory/field canonicalizer/BufferRecycler/BufferRecyclers/pools/TextBuffer，以及Spring JDBC6.2.7的ConnectionCallback、doBegin/doCommit。来源为本任务目录下载的官方固定sources.jar；没有声称完整上游或完整框架审计，也没有新增依赖。

最终handoff位于任务evidence `handoff.json`，明确列source/target/overlay/patch/replay/raw/XML/诊断/报告和缓存阶段身份。own-inventory最后单次生成，覆盖本任务local/evidence、报告、实际工具/阶段日志以及privateGit真实叶子；唯一自排除为inventory自身。为避免循环，报告/handoff只给inventory路径与末尾seal约定，最终bytes/SHA单独交root记录，不回写已封存内容。

## 8. 自检、限制与待独立审核

1. final01的既有Initial测试迁移仍pending；不能声称其原 intended reader拒绝路径已验证，不能称全回归绿。
2. 单项机械预算覆盖不是全部合法run最大值/实际生产容量。八MiB等精确合法业务字节边界没有由writer逐字节造出；采用真实writer正/拒例+机械SQL/parser的组合，并明确其层级。
3. parser中停用用实际Capture的streaming回调覆盖；SQL边界分别覆盖stop/expiry/generation，真实PG锁等待单独覆盖stop+逻辑cancel。没有把每个解析中expiry/renew或每个PG阻塞时机的全排列都单列为独立测试。root-profile长field-name的exact/+1未单列，metadata-name和root string/depth/token/number有具名机械用例；生产root name约束与string阈值同值且配置在同Factory。
4. 1199B是本地canonical实际观测，common canonical只有保守预留；JVM对象精确堆、全应用GC、生产并发与供应商IAM未验收。没有用测试JVM约8GiB max heap替代1Gi容器约716.8MiB heap。
5. 当前scope/表示账本属于B额外交集拒绝，可能拒绝writer允许的更大历史；不改writer或静默截断。B只提供可区分capacity维度，C以后映射HTTP/reset。
6. B不提供HTTP/SSE、terminal/window/state证明、actual servlet write/flush或gateway跨跳验收。C应共享单owner，并把有限编码、实际stream获取和write/flush全部包在borrow内，继续检查同capture；O2b另做跨跳。阻塞出口的物理生命周期属于后续真实验证。
7. 自检发现v1 Info的只读requiredDataLabels Set可随关闭后的emission长期保留，违背“小型Info/清大引用”意图。root明确授权labels-fix新epoch，已移除该导出并在finish清除私有labels；真实借用寿命RED/GREEN及final02见第9节。原v1证据保留，修复仍待fresh reviewer独立判断。

作者自检确认8路径范围、私有mint、两permit、metadata先行、无网络事务、无历史材料重写、非执行历史grant、精确完整行摘要和缓存清理意图；独立fresh reviewer仍需核spec+quality及新增预算/cache设计，作者完成不等于Accepted。

## 9. 最终 labels-fix epoch（root独立授权）

自检确认v1 Info标签集合可逃逸且关闭后保留；root要求本次修复，并明确保留v1 source c6、final01、patch/replay和target2053。新epoch只再改原8路径中的 RunEventReader.java 与 RunEventReaderPostgresTest.java；实际两路径差异在 labels-fix/epoch-source-delta.json。SQL、原64/8/192MiB预算、common校验、旧测试均未改。

Info移除Set；完整标签只在Emission私有Set中持有，finish与payload同时置null。Borrow.writeRequiredDataLabels只在同线程active/live时可用，内部nonrecycling ROOT writer直接写JSON数组，不导出Set/String/byte[]大副本。读阶段先逐标签累计 '2+sum(6*UTF16length+3)'，保守覆盖UTF-8/JSON转义及引号分隔符，超过既定8MiB拒绝后不进入编码；这不是精确实际输出长度。每次输出前检查live/累计8MiB，返回后再检查。generator不关闭或flush调用者stream；完整framing、实际stream获取和flush继续属于C。

私有Set/TreeSet排序节点与引用已由原model的512/token及8/raw-byte项保守计费；不再Set.copyOf，不增加标签大JSON副本。借用中的generator使用nonrecycling factory固定小字节buffer，包含在既有fixed8MiB临时预留；本次不提高model64MiB/canonical8MiB/total192MiB，两个owner总384MiB。提供方自身输出有8MiB界，**OutputStream类型本身不证明接收方缓冲有限**；C持有的frame/队列/扩容预算须另设并贯穿实际write/flush，B不认领其内存或阻塞出口验收。

RED09：真实生产writer/签名授权/read成功后，合法active borrow被默认拒绝stub抛PlatformException，1方法1error，属于行为RED。GREEN07：同1方法通过，借用内能编码合法initial标签；消费完成后同borrow拒绝且0输出；active close后同borrow也拒绝且0输出。未为DTO移除字段增加镜像测试。

最终仅运行一次：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2a-b-final02 engine-runtime RunEventReaderPostgresTest,RunEventReaderBudgetTest
```

实际Running Budget13+Postgres18=31，31pass/0failure/0error/0skip，exit0/BUILD SUCCESS。未重跑v1六类65或单独旧样本；final02自身必然执行两个B类原有样本，其新run/measurement各自保存，未覆盖v1样本，身份见 labels-fix/samples.json。命令/cwd/exit/raw/XML在 labels-fix/commands.json。final02 raw14646B SHA34e5024f705561667eb359d6958a1eee0c1b16b12e928d539a4192eef7cd5f1d；Postgres XML59611B SHAb7f7e71b73dda6c29529ccc3474eea84b308b491831b5802f7a1dde8cb4bfeaa；Budget XML47845B SHA3f8402de1e6cda4d391edd18c4451ccbea4d8f8fd0070064238707b06bf833ad。仍有既有编译deprecation/unchecked提示，非声称零警告。

最终source593/8：112856B SHA21fc2a4425e5d0767cb3627719ddfce395c97b9ca3718ebb205a18c90e0e9439；最终changed-files1686B SHA3b24ebc2dbceca1cf41b7b11500220095fa108d01136aa8b06b4fc3bf5b770d9。位置为local/evidence各自 labels-fix/ 子目录；Accepted589缓存不复扫。新编译覆盖前，按缓存465路径copy-only归档到local的v1-target-archive，无旧树扫描/重复哈希；记录v1-target-archive-record.json，原target2053逻辑身份仍不变。

最终target：仅实际compiler/resource/JAR/两个Running报告458候选，overlay100781B SHAe5bda5438f448da1b0a656fcfecbc23e67d0cec36ba14b7767bfe10183746604，叠加v1逻辑2053得到2054项，426441B SHA273eedad6e9767d8508abf0cd0639d66dd7429fc2ea24a2983bbcff620187b3f。compiler-inputs32607B SHA1f866533bbc766230b53f5c3556b08527b4c6287719eac59b88db033a1c174b6。新3份dumpstream单独归档，diagnostics.json690B SHA9807456c80e9a7a0108a10f520432fcc389213f5f9fed63b20b17fc3ca293214；未加入正式overlay，旧908历史逻辑仍继承。

新epoch source.patch93433B SHA3988cd175bbdebd2f6a9ee06d13b964b9b2894dd64a239edb56800269fd053a2；replay-commands4565B SHA182ec4a5c9fe57cb4c5899d77395e2cf3aa7494b194730d5c045f31de3b01d56。该epoch第一次check和第一次apply各exit0，全部593源名称/bytes/SHA与最终21fc清单一致；未后拷贝修复、无commit。此为授权新源码epoch的首次成功，不称v1失败重试。新freeze/target/replay成功阶段缓存于 labels-fix/journal.json，后续仅元数据与最终seal，不重新执行成功物理阶段。

handoff.json明确finalEpoch=labels-fix及v1保留关系。own-inventory.json最后单次seal包含报告/handoff和自身目录全部实际叶子（private .git亦含），唯一自排除自身；此处只引用路径避免摘要循环，最终bytes/SHA交root独立记录。作者完成仍非Accepted；旧Initial测试迁移pending、C/O2b/生产heap与出口限制继续有效。
