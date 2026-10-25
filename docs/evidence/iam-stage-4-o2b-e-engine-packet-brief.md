# O2b E正式任务：真实引擎child与同RR完整材料私有输出

## 目标和前置

唯一项目及每条shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。不访问/引用/依赖相邻旧工程、旧配置、缓存、数据或进程。持续按已批准v1.2开发，四契约不动、时间不安排。Java21/Boot3.5/Security6.5/Nimbus9.37.3、原固定依赖、PostgreSQL唯一业务权威；api/application/domain/infra和跨业务HTTP边界保持。无commit/deploy/真实外部凭据要求。

用户已明确无真实模型/本体/IAM/K8s环境，继续实现并用隔离端点验证。D common与selector-only已Accepted，详见`.local/iam-o2b-d-acceptance.json`及两个独立review；原87=86pass/1F/0E/0S保留，后续独立3green不改写历史。D M2 early原3035B patch独立apply记录缺口、M3可恢复JSON-Buffer归档缺陷及旧C M1/M2继续保留，本任务不修旧证据。

基线source603：`.local/iam-o2b-d-selector-migration/source-after.json`，115043B/SHA `ab0310201aff0a59e87f73b0692bf6e89b050591d7f33c2b4f099da3a97283c4`；target历史逻辑2118：同目录target-after.json，441041B/SHA `f04cd892a8842083b1d546795a5ce146aa3b4edec794f90f3fa0f2e58035a893`；完整私有基线`.local/iam-o2b-d-selector-migration/replay/`。8索引checkpoint `.local/checkpoint-iam-o2b-d-docs-before/manifest.json`5411B/SHA `2b125131305a08bbdf6f3c734a744a3296d258da666cc4d25b5c9c724ba87dab`。历史target是逻辑lineage而非全classpath。

完整读本brief和`docs/evidence/iam-stage-4-o2b-e-packet-decisions.md`，它们是本任务requirements。准备稿已由root完整读并正式提炼，不需再全读累计旧计划。源代码按具名实现接缝读取，真实API不可猜。SDD新implementer、TDD、verification、ponytail、java-spring-boot流程；用户已授权按当前设计持续实施，无需再请求设计/实现许可。

## 唯一源码scope和所有权

当前唯一source/target writer，只能改以下8文件，预期603→608（新增5）、delta8：

- 修改`engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java`：同三参events handler增加固定private MIME produces与非IAM私有请求拒绝；原action/debug/v1限制不变。
- 修改`engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java`：沿C owner/有限executor/slot/async，按固定Accept严格分支standalone或child，不fallback，新增私有编码路径。
- 修改`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java`：同一RR完整材料私有视图及guarded元数据编码，原B读者和C普通SSE验证/生命周期不变。
- 新增`engine-runtime/src/main/java/cn/agentplatform/engine/infra/PortalEventsPacketWriter.java`：固定v1两遍受限编码器。
- 新增`engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalEventsPacketCodecTest.java`。
- 新增同目录`PortalChildEventsPostgresTest.java`。
- 新增`engine-runtime/src/test/java/cn/agentplatform/engine/application/PortalChildEventsHttpTest.java`。
- 新增唯一test support `engine-runtime/src/test/java/cn/agentplatform/common/authority/PortalChildEventsAuthorityFixture.java`：复用正常签名LoopbackAuthority、真实G/E client、trusted decoder/frame和有限撤权/阻塞hook，不公开key/任意claims签名；真实HTTP正例必须由正常ServiceAuthorization filter/frame建立，不由bridge bind冒充。

common生产/fixture、AuthorityContext、MaterialScopeStore、ChunkBus、catalog/ingress、POM/runner、旧test/fixture、其他business/frontend均0写。不因实现方便增public DTO造cap、reflection sibling target或业务JAR。若确有不足先给root具体文件/接缝/最小变更证据，不自行扩scope。

## 必须达到的行为

采用正式decisions中的唯一MIME/schema/24B prefix/44B COMMIT/10键header/完整14键WINDOW。child receipt必须从正常production export导出；HELLO和首WINDOW在同一次fresh consume内，首RR失败时零HELLO/payload。实际child和standalone的双向表示互斥、非IAM私有不落legacy。私有packet永不混入公开SSE/JSON错误。

完整initial/provenance/3root refs/state及selected event refs/revision/全部scope/完整parents/直接父floor/11键state/5非payload事件字段/window来自唯一原RR；不额外SQL/深复制成无预算JSON模型。只有私有mint borrow受guarded同步出口可编码，不导出mutable数组/Map/JsonNode。历史opaque grant/operation ID完整保留，可用票/continuation不出wire。eventType沿C非空/CONTROL_BYTES，wire header/string容量超限须零packet容量失败，不能采新64字符正则当授权规则。

原16MiB池/270437B frame/2permit、单原runtime和192MiB ledger不扩大。正式decisions的header/scratch/descriptor/copy逐项在分配前收费；对Jackson等真实backing给出保守可核模型和边界测试，不能只报名义reserve。两遍同不可变材料count/hash，第二遍live相等才COMMIT。原B readNext/consume不变；C-fixed `consume(action,beforeRelease)` entry/action/final guard与actual complete-before-release保持，真实阻塞get/write/flush/complete未退出不能归还B/frame/slot。任何partial packet不advance，有限superseded重读只在prefix前。

这是E encoder+engine接收端；F decoder/TLS/gateway current/material校验/公开SSE尚未实现。不要为测未知wire输入建立一套生产网关decoder或拿test-only parser宣称F闭环。E产出可复用准确wire向量及本侧fail-closed证据；F完整验证义务保留。

## 验收和测试顺序

先编写真实可失败的测试并保留RED；编译失败/环境错误须如实标记，不充功能RED。新增机械codec边界测试无需PG；实际child/完整材料正例使用正常G→E签名链和真实writer/PG，不从DTO/VA/currentOnly/手签正例造权。PG至少65父、完整parents/provenance和同RR材料，核row/scope摘要/当前收窄/错父/篡改/追加/终态/retention及实际预算。HTTP走实际filter/frame，验证四组合、非IAM/无child/别run、正常receipt、payload0拒绝和阻塞退出所有权。只运行与当次变更有关的focused RED/GREEN，不重复已成功阶段为补记录。

由于本轮实际改了C Controller/Service/Reader，新的E最终epoch明确授权对受影响C生命周期做一次新回归。它不是重跑历史C stage来补造证据。最终仅一次如下完整命令（新3类＋受影响C四类/具名2方法，C部分实际24项）：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2b-e-final engine-runtime PortalEventsPacketCodecTest,PortalChildEventsPostgresTest,PortalChildEventsHttpTest,PortalRunEventsLifecycleTest,PortalRunEventsCapacityTest,PortalRunEventsHttpTest,RunEventReaderBudgetTest#emissionAndBorrowCannotBeMintedPubliclyOrExportLargePayloadReferences,RunEventReaderBudgetTest#getterClosureExpiryAndGenerationFailureAreLocalAndSticky
```

新最终期望7个actual Running classes，目标全0F/0E/0S，实际计数以XML testcase为准。不得重跑历史C29/Cfix24命令、D87/selector3/旧B65、common A/Service或全后端。若新变更/新失败确需新focused验证，记录理由/epoch/真实结果，不能改旧raw或反复跑同green。根reviewer不重跑作者已验证测试。

## 证据与交还

自有`.local/iam-o2b-e-engine-packet/`、`docs/evidence/iam-stage-4-o2b-e-engine-packet/`、`.tools/iam-o2b-e-*`，报告`docs/evidence/iam-stage-4-o2b-e-engine-packet-report.md`。不改旧D/C/迁移报告或inventory/journal/private replay；8索引root在新review/audit Approved后收口，作者不能写。

任务前保存source/target before；每个实际测试前完整source epoch，结束立即command/cwd/exit/stdout/stderr/raw及actual Running对应XML/txt；记录真实case而非编译input。compiler `.lst`/raw直接copy或Buffer原字节写，不JSON.stringify Buffer。新dumpstream/dump独立列出，不混入官方target。有限target只实际admin-common/engine-runtime两compiler phases的created/input、实际资源/common fixture JAR/actual Running报告；不全树扫旧target。保存源delta/before/after、实际量测/向量、所有异常过程，数组只输出count/schema/有限sample，勿打印manifest或数字Buffer数组。

建立一个完整603私有Git基线，首次真实raw patch check/apply可按RED/后续不同patch顺序即时留证；最终608需从真实基线+patch达到且名称/bytes/SHA全等，无apply后source copy/no commit。依赖mutation前必须等真实子进程session退出；每条命令在任何断言/后继操作前持久化，并缓存/journal每个成功步骤。中途metadata错误只修未完成元数据，不重复成功hash/测试/PG/replay。不可复演D early index竞争或补造缺失独立记录。

最终handoff明确file/bytes/sha256条目：sourceBefore/After（count）、targetBefore/After（logical count）、finiteTargetOverlay、finalEpoch、testResults、patch/replayCommands/replayRoot/replayComplete、journal、ownInventory、report、sourceWrites/ownedRoots/writeAuthority。own-inventory覆盖全部自有local/evidence/helpers/report与私有.git，仅自身排除。封存交还所有source/target/local/evidence/helper/report权，作者完成不是Accepted，等fresh独立review与root有限审计。没有真实IAM/生产issuer/TLS/网关EVENTS/容量/全功能覆盖承诺。
