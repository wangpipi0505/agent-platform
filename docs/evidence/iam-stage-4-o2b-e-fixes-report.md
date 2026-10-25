# O2b E I1/I2 独立修复报告

状态：修复作者完成待复审，尚未 E Accepted。作者 /root/iam_o2b_e_fixes，日期2026-10-21。唯一工作目录 G:/207/项目/算法重组工具/代码/agent-platform。已完整阅读 fixes brief、原独立review、packet-decisions、wire/header supplements、AGENTS、implementation-brief与contracts README。应用 receiving-code-review、TDD、Java Spring Boot、ponytail、verification 和 systematic-debugging；按 SDD implementer prompt 自核。未commit/deploy，未访问相邻旧工程。

## 修复与范围

仅写 PortalRunEventsService 与 PortalChildEventsHttpTest 两个获授权既有源码。source608，delta2，新增/删除0；没有改Reader/Writer/common/fixture/YAML/POM/runner/旧C tests/四契约/八索引/F。原E report/review/45/patch/replay及其他证据根保持冻结。

I1：private尚未HELLO、当前packet无字节且响应未committed时，复用既有charged固定小JSON ApiResponse路径：413、application/json、EVENT_LIMIT或BUFFER_LIMIT通用白名单envelope，message固定“事件观察不可用”，data/traceId为null。current capture/requireLive实际包围get/write/flush，frame/slot仍由owner持有；complete实际返回后才归还。已开始/部分packet、capacity已发送或已committed而无HELLO时只关闭，不追加SSE/JSON。既有public行为继续沿原分支。

I2：private capacityReset整个尝试处于真正finally，所有export/current/get/write/flush异常也先actual async.complete物理返回，再离开Reader.consume的beforeRelease；Reader的cleanup/lease与Service frame/slot终于只能在其后释放。不改Reader原B/C consume语义，不增加scheduler/预算/重试/COMMIT/游标推进。普通成功packet不进入这个容量finally，idle到terminal正例证明未提前关闭。原32KB实际application.yml、16MiB response pool、270437B frame、2 Reader permit、192MiB workspace保持相同字节/值。

## 测试设计与真实失败

所有正例正常G exchange/签名E ticket → TrustedJwtDecoder/ServiceAuthorization HTTP frame →真实PG/writer。I1把两个真实executor worker暂时占住，使成功opening preflight释放结果后真正排队；随后真实writer追加262144字符payload，fresh RR真实EVENT_LIMIT。阻塞actual complete时核精确已写/flush JSON、frame剩61/slot未回收；两个B permit可用符合失败fresh RR无结果持有，complete退出后frame62和slot恢复；HTTP精确413/MIME/envelope且零APEV/APCM/receipt/state/watermark。

I2沿已有Mockito reader/emission wrapper接缝；所有RR/mint/borrow/consume/guard/cleanup委托实际对象，HELLO由真实encoder提交。只在真实WindowBorrow的WINDOW writePrivatePacket入口、任何WINDOW prefix之前，注入另一次真实超payload read所得CapacityFailure对象；随后真实CAPACITY writer的write或flush分别注入IOException。这是负例故障注入，绝不是实际BUFFER_LIMIT压力证明，也不构造权限DTO/capture。反射只取已有Service executor和替换reader进行时序控制，不制造权限。两个active连接实际complete被latch阻塞：两个B permit仍占用、frame剩60、第三slot及第三B准入均拒绝；complete退出后B2/frame62/slot恢复。每个连接仅HELLO（write失败）或HELLO+单CAPACITY（flush失败），校验prefix/COMMIT/SHA，零WINDOW、无额外COMMIT/重试，afterSeq仍0/eventSeq=null。

有效RED-2实际3F：I1已进入complete但wire集合0而应1；I2 write/flush已证明HELLO后各2次WINDOW prefix前注入及CAPACITY尝试，然而complete阻塞时第三readNext竟成功，明确B提前释放。原生产字节SHA仍为bdebec0399f1ad0b45dabda0786aedd86fb71d788f5c75dc41605a5f68196249。原RED失败因关闭server时latch还被持有各多等约30秒，线程快照保留；随后新测试的finally移入资源scope，失败也及时放latch，无生产超时/关闭参数变动。

## 阶段、源码epoch与命令

每个stage source-epoch均608；command/cwd/exit、stdout/stderr/raw、actual Running对应XML/txt、compiler input/created .lst原字节已立即归档到自有local根。每次run都刷新这两个获授权源文件mtime，raw均实际javac54生产+68测试，证明运行当前字节而非恢复旧mtime/classes。下表tests/F/E/S来自XML testcase/suite，Running来自raw；0用例阶段仅INVALID。

| stage | actual Running类 | tests/F/E/S | Maven exit | source epoch SHA256 |
|---|---:|---|---:|---|
| iam-stage-o2b-e-fixes-red | 0 | 0/0/0/0 | 0 | b89023b56cf78945a739ac953a0c4b8867bb92bc31ff72d46c1f96bdf893aca3 |
| iam-stage-o2b-e-fixes-red-2 | 1 | 3/3/0/0 | 1 | 92568a63bec7d8ca5f58d37fb0404d34fb3bc5e22a16745d394e8bd9a8b52a34 |
| iam-stage-o2b-e-fixes-final | 1 | 4/0/1/0 | 1 | 99d349718c7e7307eed02cbb5f5a0a52673de785e57c0febb13855d832f0dee2 |
| iam-stage-o2b-e-fixes-write-corrected | 1 | 1/0/0/0 | 0 | 303ec0625e9b8491d834cbd0ce27a38c0dcffab77104e333b38f960e102a5adf |

完整物理Maven命令（JSON数组按项精确保存，每条command.json cwd均唯一工程；JDK21、离线repo与隔离测试凭据由自有helper设置，凭据只在子进程env未输出）：

`["mvn.cmd","-o","-B","-ntp","-pl","engine-runtime","-am","test","-Dmaven.repo.local=.tools/m2","-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Dtest=PortalChildEventsHttpTest#privateFreshQueuedCapacityBeforeHelloReturnsBoundedJsonUntilCompleteExits,PortalChildEventsHttpTest#privateCapacityWriteFailureCompletesBeforeRealBorrowRelease,PortalChildEventsHttpTest#privateCapacityFlushFailureCompletesBeforeRealBorrowRelease","-Dsurefire.failIfNoSpecifiedTests=false"]`

`["mvn.cmd","-o","-B","-ntp","-pl","engine-runtime","-am","test","-Dmaven.repo.local=.tools/m2","-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Dtest=PortalChildEventsHttpTest#privateFreshQueuedCapacityBeforeHelloReturnsBoundedJsonUntilCompleteExits,PortalChildEventsHttpTest#privateCapacityWriteFailureCompletesBeforeRealBorrowRelease,PortalChildEventsHttpTest#privateCapacityFlushFailureCompletesBeforeRealBorrowRelease","-Dsurefire.failIfNoSpecifiedTests=false"]`

`["mvn.cmd","-o","-B","-ntp","-pl","engine-runtime","-am","test","-Dmaven.repo.local=.tools/m2","-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Dtest=PortalChildEventsHttpTest#privateFreshQueuedCapacityBeforeHelloReturnsBoundedJsonUntilCompleteExits,PortalChildEventsHttpTest#privateCapacityWriteFailureCompletesBeforeRealBorrowRelease,PortalChildEventsHttpTest#privateCapacityFlushFailureCompletesBeforeRealBorrowRelease,PortalChildEventsHttpTest#privateActiveIdleHasNoPacketAndNewTerminalCarriesProof","-Dsurefire.failIfNoSpecifiedTests=false"]`

`["mvn.cmd","-o","-B","-ntp","-pl","engine-runtime","-am","test","-Dmaven.repo.local=.tools/m2","-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Dtest=PortalChildEventsHttpTest#privateCapacityWriteFailureCompletesBeforeRealBorrowRelease","-Dsurefire.failIfNoSpecifiedTests=false"]`

实际入口为 node .tools/iam-o2b-efix-evidence.mjs run <stage> <engine-runtime逗号/#选择器>，不调用或改写旧runner。首阶段编辑helper CRLF模式不匹配现有LF源码，断言在任何source mutation前失败；同shell误继续启动不存在新方法的selector，0 Running/0cases，永久INVALID，不作功能RED。随后改为编辑与runner独立tool调用，先看exit再后继。此过程保留，不把零用例/编译/环境问题充RED。

唯一初始有限final选择严格为新3用例加原 privateActiveIdleHasNoPacketAndNewTerminalCarriesProof（实际受private容量complete变化影响，验证正常流不提前结束）。结果是3pass/1E，不是4全绿：write用例的核心complete阻塞B2/frame/slot及wire断言通过，但释放后第二个B readNext复用长存standalone probe时撞上其自动renew generation。原stack：PortalEventsResponse.requireLive:35 → RunEventReader.live:134 → MaterialScopeStore.requireObservedLocator:570 → test capacityFailureBeforeRelease:202。此为局部capture变旧，未关闭续核/gate/修改common；完整原stack见初始final XML/txt。

仅把共享测试helper最后的独立post-release恢复检查换成新正常open的holder，避免旧探针续核在两次B读取间交错；核心blocked/wire检查不变。随后只运行原失败write方法，1/0F/0E/0S，未为聚合绿色重跑另3已通过项。正式结论为“3个初始final通过 + 1个纠正focused通过”，有明确跨epoch继承，不伪称一次4green。最新actual Running只有 PortalChildEventsHttpTest 的write方法1项。

生产同字节证明：engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java: 23215B / SHA256 3572534a239272e39ff3b6cb7e38d98284708d3db4278b678157a3aa5df3a427，与初始final epoch中production行精确相等。其余3个testcase本体bytes/SHA也逐一相等，详见inheritance.json；flush调用的共享helper只在全部核心断言/complete退出后做同一新probe恢复检查。initial-final test源码在自有inherited-final下按唯一test-only修改逆变换重建，SHA严格等于已存在初始final epoch；这不是新执行或改写旧epoch。

## 源码、有限target与新回放

.local/iam-o2b-e-fixes/source-before.json: 116139B / SHA256 b89023b56cf78945a739ac953a0c4b8867bb92bc31ff72d46c1f96bdf893aca3；count608。

.local/iam-o2b-e-fixes/source-after.json: 116139B / SHA256 303ec0625e9b8491d834cbd0ce27a38c0dcffab77104e333b38f960e102a5adf；count608，与最新实际source-epoch及replay-source逐项相等。

两个源文件before/after快照在local before/after下，delta：

- engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java: 23215B / SHA256 3572534a239272e39ff3b6cb7e38d98284708d3db4278b678157a3aa5df3a427
- engine-runtime/src/test/java/cn/agentplatform/engine/application/PortalChildEventsHttpTest.java: 28748B / SHA256 8a200ce05d6f4ed82db780568883872bfa2f9c4ef1b1a54c8ef610dad74c936e

.local/iam-o2b-e-fixes/finite-target-overlay.json: 117204B / SHA256 d20251233b9f12f65556d086cb68823016cc1f1958fa32e053ae12d2c9c960fb；count530。只收本轮actual common/engine两compiler phases created/input、真实资源、common fixture JAR与latest actual Running报告；.lst直接copy字节，不扫描旧target树。初始final4及RED各自报告仍独立在stage内，未以latest1覆盖它们。

.local/iam-o2b-e-fixes/target-before.json: 447925B / SHA256 ce1a10735815197b171f17d439063241d186995b63bfe3b31b3ba8f8ac003adf；逻辑count2148。

.local/iam-o2b-e-fixes/target-after.json: 447924B / SHA256 30422b7bcf37c2e31ef6c596ced94543d432bf847bde17b513640d6dba403f51；逻辑count2148。新dump/dumpstream按本轮init时间从明确surefire目录过滤归档到diagnostics，另列不混official target。

从原冻结E replay只复制manifest列出的完整608字节到全新私有baseline，独立git init/add/write-tree，无commit。用该baseline index与当前工作树生成唯一新raw binary/full-index delta2 patch，根本没有把修改后的source复制进replay再回退。docs/evidence/iam-stage-4-o2b-e-fixes/source.patch: 19201B / SHA256 29c81d8fb18b3a13972114970df7de9043a07a711cf1c1625ddd7a28a2df18d2。首次check=0、首次apply=0，各恰一次；apply前后均无source copy；完整608名称/bytes/SHA逐项与sourceAfter精确相等。所有git子进程cwd为唯一工程，命令用-C/--git-dir指定自有replay；每个命令请求/exit/stdout(base64保存)/stderr先持久化再后继，成功phase/hash cache可恢复不重复apply。docs/evidence/iam-stage-4-o2b-e-fixes/replay-commands.json: 32711B / SHA256 71a3c7b6fa9a593009ac25ec172583e1f406d40ebbb408439c1bb18f96dea343。

.local/iam-o2b-e-fixes/replay-complete.json: 778B / SHA256 3eeb1cfdb3da35ad9d0d1a6c944b1fd84ee508dd21012e11f93b1cd963e964ca。docs/evidence/iam-stage-4-o2b-e-fixes/verification.json: 2497B / SHA256 bf165c4c761c877f2d6fdb79c004ac5397abb8c3c783643647dfb7ef2393811c，status PASS。原E source b89023、target ce1a10、90459B patch b49b4d身份保持，未重跑原45/旧C24/common/PGsuite/全后端，未重跑原replay。原E历史stale INVALID/HTML400/PG错误照旧保留，未洗成green。

## 交还与限制

自核完成：I1前HELLO白名单固定JSON与无partial追加；I2异常全finally物理complete先于Reader/frame释放；正常idle→terminal原用例继承通过。全部自有stage/source/target overlay/新private .git与rawpatch/replay/helpers/report纳own-inventory，inventory仅排自身；handoff明列每项file/bytes/SHA/count/schema、lastEpoch/actual Running、test-only correction与resolved verification。文件身份在handoff.json及最终回执中，seal之后作者冻结并交还source/target/local/evidence/helper/report全部写权，不再写。

等待原 /root/review_iam_o2b_e_engine_packet 对新delta2/focused证据复审、root独立有限审计；两者通过且Spec/Quality Approved C0I0才可E Accepted。F仍待实现：生产decoder/TLS来源、receipt association/fresh G current/完整材料验证/公开SSE没有本轮承诺；不宣称真实BUFFER压力、全功能/生产容量、生产IAM/issuer/K8s/ingress部署或强断SLA完成。正常资源32KB与所有既有预算未变。
