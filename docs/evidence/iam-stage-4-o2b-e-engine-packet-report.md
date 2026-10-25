# O2b E真实engine child与同RR私有packet作者报告

当前状态：作者完成并冻结，final45通过，私有完整608回放成功；已上报但尚未由新focused验证的首HELLO前容量错误envelope spec gap保留。等待fresh独立review/root有限audit；作者完成不是Accepted。

## 范围与行为

唯一项目G:/207/项目/算法重组工具/代码/agent-platform。正式brief/packet-decisions及wire-clarifications、request-header-scope-decision为依据。原8文件scope经root书面补充扩大为9（仅新增既有engine application.yml的32KB有界请求头及简注）；source603→608，新增5，delta9。common/MaterialScopeStore/ChunkBus/POM/旧tests/旧fixture/catalog/frontend和8索引均无源码写入。未commit、deploy或接触相邻工程。

Controller维持原3参数/events/action和3映射，produces增加唯一private MIME。service沿原有限executor/slot/frame/async，strict Accept在standalone和child正常open间互斥，无fallback。私有非IAM在Actor/SQL前拒绝；private预接管错误以固定JSON ApiResponse封装。完整材料复用Reader原唯一RR中的binding/initial/roots/scopes/state/event，私有mint先由实际authority.portalEventsChildReceipt证明child；无额外材料SQL/DTO造cap/反射造权。HTTP正例由正常G交换→E签名JWT→actual ServiceAuthorization filter/frame→真实PG/writer执行。

private固定v1 encoder生成24B prefix和44B COMMIT，header恰10键、WINDOW恰14键、完整11键state和5键eventIdentity。字典键/集合排序、parents原序保留。UTF8严格surrogate/escaping/数值规范化；有限Json计数包括end token。两遍同一私有不变模型count/hash，第二遍复核与guard后才COMMIT并flush；cursor只在成功返回后推进。无完整8MiB wire buffer，无生产decoder。初始、reset、新终态材料WINDOW输出；后续仅活动idle可跳过，EOF不作终态证明。

HELLO与首WINDOW在同一fresh consume内；owner保留C complete-before-release。新HTTP测试实际阻塞get/write/flush/complete各2连接，stop期间2个Reader permit、frame、slot仍持有，真实complete退出后归还。superseded只在当前packet无字节时允许有限fresh重读；已有HELLO不重发。

## 预算及实际量测

原frame270437B、共享response pool16MiB、Reader permit2和单workspace192MiB ledger不扩大。writer在分配前追加scratch65536B、descriptor65536B、header8192+24B；实际最大同时stream backing16384B，prefix24/commit44，单个隔离sink copy≤8192+24B并单独reserve。无n长排序数组；沿既有map/set做有界扫描，避免Jackson可增长编码backing。Json对象/字段递归深度及所有字符串/数字/token有固定交集；Decimal指数扩张在toBigInteger前检查。

实际borrow返回ledger peak，PG断言≤201326592B通过；精确peak数值未单独log，不能当精确堆量测。原C共享16MiB/exact frame边界5项也包含在本轮受影响回归。final向量12个/25 packet，最大metadata 315800B、scopes 88、单parents 87、总parents条目3862。这是已验样本，不是4096 parents/records全组合或生产容量承诺。BUFFER_LIMIT准确向量是正常child上的encoder reason输入；真实EVENT_LIMIT容量来自正常writer追加超262144B payload。

实际工程application.yml加载到ServerProperties为32768B；正常compact child JWT 8495字符，保守整请求头10543B。默认8KiB失败是Tomcat HTML400；32KB后完整HTTP200。超32KB容器400与IAM业务拒绝分开记录。未扩大JWT16384 cap，未验证生产反向代理/ingress/TLS配置。

## 实际验证与异常记录

采用TDD、Java、ponytail、verification流程。最初将HTTP400误判为功能RED后已主动纠正；先有实现草稿、再取得受控真实baseline RED，不能宣称首次测试先于任何实现。所有初始编译失败、HTML400、stale-target invalid、PG/HTTP断言错误保留原raw/XML/epoch。后续两项wire边界新增测试实际2F→2通过。未修改原87/86pass/1F及其独立selector3green，未重跑历史C stages/D87/selector/common/全后端。

| 新epoch | actual Running类 | tests/F/E/S | 子测试命令exit | 解释 |
|---|---:|---:|---:|---|
| iam-stage-o2b-e-http-red | 0 | 0/0/0/0 | 1 | 编译失败：测试跨包构造ChunkBus；非功能RED。 |
| iam-stage-o2b-e-http-red-2 | 1 | 1/1/0/0 | 1 | 容器默认请求头限制返回HTML400；非功能RED。 |
| iam-stage-o2b-e-focused-1 | 2 | 7/1/0/0 | 1 | codec6通过；HTTP仍为容器HTML400，不作功能RED。 |
| iam-stage-o2b-e-http-diagnosis | 1 | 1/1/0/0 | 1 | HTML400定位证据，非业务授权结果。 |
| iam-stage-o2b-e-functional-red | 1 | 1/0/0/0 | 0 | INVALID：恢复source保留旧mtime，Maven复用了E class；实际通过但不得算基线RED或有效GREEN。 |
| iam-stage-o2b-e-actual-baseline-red | 1 | 1/1/0/0 | 1 | 受控新对照：移出writer/codec、原3生产文件恢复基线并刷新mtime，日志证实际重编译；32KB接收下旧实现403/1F。不是补称历史首次TDD。 |
| iam-stage-o2b-e-http-header-green | 1 | 1/0/0/0 | 0 | 恢复实现并刷新mtime实际重编译；32KB隔离接收、正常签名child200。 |
| iam-stage-o2b-e-pg-focused | 1 | 5/1/0/0 | 1 | 4通过；1F为持久化集合数组顺序与wire集合排序的测试比较错误。 |
| iam-stage-o2b-e-pg-corrected | 1 | 1/0/0/0 | 0 | 仅失败方法，改验scope语义与parents原顺序，1通过。 |
| iam-stage-o2b-e-http-new-focused | 1 | 6/1/1/0 | 1 | 4通过；malformed private Accept错误响应协商导致1F；4097字符eventType被真实PG varchar32拒绝导致1E，不能称wire授权失败。 |
| iam-stage-o2b-e-http-modes-corrected | 1 | 1/0/0/0 | 0 | 只验失败方法；pre-async private错误明确application/json，严格Accept保持400。 |
| iam-stage-o2b-e-http-payload-capacity | 1 | 1/0/0/0 | 0 | 替换无效4097 type设定；正常writer在HELLO+初始WINDOW后追加真实超限payload，EVENT_LIMIT CAPACITY通过。 |
| iam-stage-o2b-e-wire-boundary-red | 2 | 2/2/0/0 | 1 | 新2方法真实2F：结束token计数、HELLO after/initial。 |
| iam-stage-o2b-e-wire-boundary-green | 2 | 2/0/0/0 | 0 | 只验上述2方法，2通过。 |
| iam-stage-o2b-e-capacity-vectors | 1 | 1/0/0/0 | 0 | 正常签名child上编码BUFFER_LIMIT/EVENT_LIMIT准确向量；reason是encoder测试输入，不冒充真实BUFFER_LIMIT压力。 |
| iam-stage-o2b-e-final | 7 | 45/0/0/0 | 0 | 唯一正式最终7类，新3类21项+受影响C24项，45通过。 |

每stage目录包含完整source-epoch、command/cwd/exit、stdout/stderr/raw、actual Running对应XML/txt和compiler .lst原字节copy。测试驱动helper本身正常退出不等于内层Maven成功；结果以command.json/results.json保存的子命令exit和XML为准。初始init调用未保留工具session handle，随后通过具名进程退出与已逐条落盘git-command确认完成；无依赖mutation在该进程仍运行时执行。

唯一最终命令：

`node .tools/run-iam-module-tests.mjs iam-stage-o2b-e-final engine-runtime PortalEventsPacketCodecTest,PortalChildEventsPostgresTest,PortalChildEventsHttpTest,PortalRunEventsLifecycleTest,PortalRunEventsCapacityTest,PortalRunEventsHttpTest,RunEventReaderBudgetTest#emissionAndBorrowCannotBeMintedPubliclyOrExportLargePayloadReferences,RunEventReaderBudgetTest#getterClosureExpiryAndGenerationFailureAreLocalAndSticky`

actual Running7类，XML45/0F/0E/0S（新21+C24）；没有用compiler input数量充当测试数。

## 源码及有限target身份

sourceAfter/finalEpoch/replaySource均608，116139B/SHA b89023b56cf78945a739ac953a0c4b8867bb92bc31ff72d46c1f96bdf893aca3。sourceBefore603仍115043B/SHA ab0310201aff0a59e87f73b0692bf6e89b050591d7f33c2b4f099da3a97283c4。targetBefore历史逻辑2118仍441041B/SHA f04cd892a8842083b1d546795a5ce146aa3b4edec794f90f3fa0f2e58035a893。

本轮finite target overlay542，仅admin-common/engine-runtime实际两compiler phases的created/input、实际resources、common fixture JAR和7 actual Running报告。targetAfter逻辑2148，447925B/SHA ce1a10735815197b171f17d439063241d186995b63bfe3b31b3ba8f8ac003adf。15个新dump/dumpstream另归档，不混入official target。

| 9文件 | bytes | SHA256 |
|---|---:|---|
| `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java` | 29940 | `cd644dedf4416ef968fe6b31ea03cf64dc98b3516ea6a11efc2b5640ddedc22a` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java` | 22708 | `bdebec0399f1ad0b45dabda0786aedd86fb71d788f5c75dc41605a5f68196249` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/PortalEventsPacketWriter.java` | 18747 | `36ab2290b46876ee39ea2ed7bdc813b19e0e2e85298f8fe3c55203de4ff9c093` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java` | 38419 | `e28220170196232b6eae4b2e705c8d0b2115175e39acb8a89299de63b5f7112c` |
| `engine-runtime/src/main/resources/application.yml` | 2037 | `d772c39d4cd144cdd3120fda44199c381d306e0b9471f2c0001e9fc2e1361fa7` |
| `engine-runtime/src/test/java/cn/agentplatform/common/authority/PortalChildEventsAuthorityFixture.java` | 1681 | `dcb2605a56c0cda45d490ee3557e394ddb525724baff612ed60594a330b0803c` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/PortalChildEventsHttpTest.java` | 19240 | `bd350c4a724db1463f15e630d88398f512aa185c59d511c2ed9f3cae9c0eeed4` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalChildEventsPostgresTest.java` | 15806 | `3ce339790c27dca012983460e9a35189849db75ec79f4a80b7def8ccfd15f1a2` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalEventsPacketCodecTest.java` | 3946 | `ecf9d213e512cb0f202c8a046ca51581250d26dc606c91f6f46ccce9014bb02b` |

## 私有raw patch回放

init时完整603复制到自有private Git baseline，无commit。随后最终9文件raw Git binary/full-index diff保存为source.patch，90459B/SHA b49b4de0d973efadfe059292a17be2f0c0fc4d9f8ca10f7e3bdbf6c6875e8f90。恢复真实baseline、清intent-to-add后，第一次git apply --check=0、第一次apply=0；apply后没有source copy。实际完整608文件名称/bytes/SHA全等，replay-complete与replay-commands保存原始命令和逐条exit。private .git也纳own-inventory，inventory仅排自身。

## 已知未验证spec gap及信任边界

封存前自核发现：private fresh queued首次RR容量失败、尚无HELLO时，capacityReset目前仅设置HTTP status/application-json并complete，没有写ApiResponse body。本轮真实EVENT_LIMIT测试覆盖HELLO之后；C既有fresh queued容量测试覆盖public。这是源码审计发现、尚未由新focused验证的spec gap，不计作新运行失败。root明确要求本作者停止source/target写入并交还全部权：由fresh reviewer核全9文件/全部规格，root有限audit；汇总全部finding后由fresh fixer一次修复、真实新focused RED/GREEN、新独立delta patch/epoch，再回原reviewer复审。原final45/原rawpatch第一次apply/私有608身份均不可变，不重跑或改写。

E仅为encoder/engine receiver。F生产decoder、TLS来源、gateway完整材料fresh current校验与公开SSE尚未实现；不是生产issuer/IAM/K8s/真实外部凭据/部署/生产容量或全功能验收。receipt与wire/business SHA不替代TLS，三root大payload未导出也不宣称F可重算它们。
