# O2a B：PostgreSQL 有限事件材料读取器实施任务

正式实施 brief。O2a A 与旧 ServiceAuthorizationTest 迁移均 Accepted，当前没有其他 source/target writer。本任务完成有限材料读取器；后续 C 才装配 engine HTTP/SSE 并原子开启 EVENTS，O2b 再完成 gateway 跨跳。不得提前声称事件流、生产容量或真实 IAM 已验收。

## 约束与基线

- 唯一工程及每次 shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。不得读取、引用、复用、链接、修改相邻旧工程代码、配置、依赖、数据、缓存、进程。v1.2 及四份契约不变；不排时间。
- Java21 / Spring Boot3.5.0 / Security6.5 / Nimbus9.37.3；PG 为业务权威；common 与六服务及 api/application/domain/infra 分层不变。跨业务服务只能 HTTP，不引入其他业务服务 JAR、反射 sibling target/classes 或旧实现。无 H2/Map 替代 PG、无假成功或失败回退。
- tenant/principal 来自已验签身份；凭据仅本工程 `.env`/secret_ref，不输出明文。默认拒绝。真实环境暂缺，用户明确授权隔离测试端点。
- 当前 Accepted source 清单 `docs/evidence/iam-stage-4-service-tests-migration/source-after.json`：111999 B，`01121a4739ff5a0299cce9e5ecaa0ab5d433cd0be177ffd5d03f9410c67fe346`，589 项=backend397+frontend192。生产源码与 Accepted A 相同，只有旧14项测试已迁移。
- 当前 target **历史逻辑 lineage** `docs/evidence/iam-stage-4-service-tests-migration/target-after.json`：419609 B，`244535cb02df60585217088e321f2b6ac44aaca20de22821a080d66d41967e20`，2023 项；不是纯编译集合，也不是所有当前物理树。历史908条 dumpstream 继承，不重扫、不删改。
- 八执行文档已闭环 checkpoint `.local/checkpoint-iam-service-migration-docs-before/manifest.json`：4266 B，`77b3a51d09ee1934ac85241f6202fc8cf05eb8a0ee50e2de7191b7602e952e63`。仅 T01 全局完成；不改这些索引，由 root 审核后更新。
- 最新前置核验 `.local/iam-o2a-b-baseline-final-check.json`：4782 B，`c8c252ff14749fcebc737628a83278095b3045a61bd9ef8a1dfcec09622212cc`，14个具名物理 API/fixture 与 Accepted 清单相同。该成功阶段不得重跑。
- 已批准的流程、关系、查询、测试准备见 `docs/evidence/iam-stage-4-o2a-b-material-reader-preparation.md`：14456 B，`93db0efc47403968dbb367b756d186864ce5621827d9dd9c169941b3eaf1b46f`，101行 EOF。先完整读取；本 brief 对入口、common seam、permit、timeout 的下列决策取代其中“候选/待核”描述，其余具体材料关系和测试要求均保留。

## 唯一源文件范围

允许修改4个已有生产文件、新增1个生产文件和3个测试文件，共8个；其余源码、POM、migration、前端、契约不得改。需要扩展先报告 root，不能自己拆类或修改既有 fixtures。

| 文件 | 范围 |
|---|---|
| `admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java` | 仅 Capture 两个本地只读 getter，见下文；既有 mint、取消、调度、更新、生命周期及 receiver 不改。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java` | 新包内有限 observation-binding 读取；不调用旧无界 loader 再统计。原用途和 writer 语义不变。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java` | 新包内 probe/有界 metadata、payload 查询及纯校验复用。旧 writer/status 路径不得改用途。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java` | 仅将已有纯 `requireSnapshotRelations` 改为同包可复用，保持原调用与语义。 |
| 新 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java` | 有限 reader、私有单事件 emission、内部预算/解析及2个 permit；无 stereotype、route、application 空转发。 |
| 新 `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderPostgresTest.java` | 真 PG、writer、签名权限、getter 顺序、事务、生命周期及真实容量样本。 |
| 新 `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderBudgetTest.java` | 有限 SQL、parser、累计预算、workspace/emission 所有权机械边界。 |
| 新 `engine-runtime/src/test/java/cn/agentplatform/common/authority/RunEventReaderAuthorityFixture.java` | test-only 同包签名/frame helper；可调用现有 common test-jar `LoopbackAuthority` 与 trusted package seam。不得新增公开生产 proof 工厂。 |

另可创建本任务专用 `.local/iam-o2a-b-material-reader/`、`.tools/iam-o2a-b-*` 和 `docs/evidence/iam-stage-4-o2a-b-material-reader/` 证据/helper。不得修改公共 runner、既有 fixtures/报告、其他 `.local` 产物或执行文档。现有分支没有 HEAD/remote；不提交、不 add-A、不创建真实项目 Git 属性。独立新工程本身就是用户要求的隔离目录。

## 入口、私有权限与期限

`RunEventReader(DataSource)` 是实际有限资源 owner，入口 `Optional<EventEmission> readNext(PortalEventsResponse response, long afterSeq)`；若实际必须注入时钟，先说明必要性，不能用 wall Clock 重新起算签名的单调期限。C 将仅装配一个 owner，B 不注册它。内部 emission 构造器私有；不允许 Actor、公开 DTO、执行 retained、STATUS/Q 代替 response。

Capture 仅新增 `public long initialCursor()` 与 `public long remainingNanos()`。两个方法都 `synchronized(holder)` 后先 `holder.requireLive(this)`；前者返回 `evidence.binding.effectiveCursor()`，后者返回 `holder.requireCurrent()`，复用失败粘性。不能裸导出 evidence、owner、Actor、VerifiedAuthority 或 Retained，也不新增 Capture.requireLive。现有外层 `response.requireLive(capture)` 用于所有边界。

Reader 在 PG 外取一次 capture，在 permit/SQL 前拒绝负 afterSeq 或 afterSeq 小于 signed initialCursor。初始游标是请求绑定，afterSeq 是本轮进度，允许大于初始；每轮绑定 afterSeq 和所选 seq，跨轮单调由 C 保证。资源必须是本 run 的 accepted snapshot；同 owner 的新登录与历史 execution origin 不同仍可观察；历史 grant/source 只核内部 provenance，不据它恢复执行或要求与新 observer origin 相同。

所有在线 open/refresh 在 reader/事务外；reader 只调用本地 live/material 检查，PG 内禁止网络。拒绝所有 ambient transaction，包括同库 RR。自建同库 readOnly REPEATABLE_READ，有限查询与事务；捕获进入前 `nanoTime` 作保守总预算起点，每条 SQL 前取同 capture 当前剩余与本轮已锚定总剩余的较小值。现有 private evidence 已取 min(原 hard 单调、current 单调、wall hard/current)，不可重置 anchor。PG statement_timeout 毫秒**向下取整**；不足1ms即不发 SQL/不设置 timeout，不得设0（PG 0是禁用）、ceil 或 max(1)延长。Spring/JDBC 秒级 timeout 只能粗后备，不可当精确授权期限。事务内末尾及 tx.execute 真正 commit 返回后再次检查同 generation/capture，失败丢弃，不 mint。不能声称 deadline 瞬时中断被阻塞 JDBC。

## 查询顺序与材料证明

严格实现 preparation 第3节 a–f；不是调用原无界 snapshot/status 然后剪裁。不得 `a.*`/`r.*`、先 getString/getObject 后统计、用 LIMIT 截断仍当完整集合成功、读取大payload后补 scope 检查。

1. owner/publicId、TASK/FULL、unsupported/hasTools 及 control/initial 全部长度/JSON 类型先 probe；有限 CASE 文本及受限 parser 后核 binding 的 root/app/version/accepted/candidate/初始摘要/provenance。未知/他人沿原404，其余沿原拒绝类别。
2. 三根身份/长度/类型/count；下一事件固定 run_id/seq 条件、顺序、LIMIT1，仅 locator/seq/type有限标量，不取 payload。空轮仍校验必要根/binding，无 emission。
3. selected scope probe→受限完整 scope→其完整父 hash 数组；同 run、hash、revision 早于目标查询精确父集合。缺失/重复/未知/超限拒绝，三根去重。不得换 latest 或递归加载整个历史。
4. 全部 metadata 的单份和总量过预算后，限定读取每份完整 scope，核完整 digest/schema/producer/kind/locator/owner/initial/版本/父唯一性、历史与当前资源关系、event floor。父 scope 仍保留完整父数组并计费，不能压成 floor。直接父核 row/locator/hash/先后，不递归祖先 payload。三根按严格 admission 关系；各份 scope 逐一 `capture.requireMaterial`，含 dataLabels 义务。所有 scope 与 cover 完成前，根/event payload getter 必须0。
5. snapshot/refs 的 probe→有限 CASE→精确 JSON/规范化摘要、accepted hash、snapshotRelations/GraphRules、candidate/app/version/refs/pure FLOW；不消费 inputs 就不读取其 payload。最后固定 `id,run_id,seq,type,payload,created_at` 完整行投影，先长度再有限读取，沿原 UTC 微秒、精确数字/对象 key 排序摘要，不能只 hash payload。公开 event payload 256KiB 原口径不变，不能被1MiB完整行预算替换。
6. 每批/解析后、payload 前、commit 内末尾、实际返回后及 emission 使用都检查同 capture。generation 变化、stop、过期、迟到取消均无 emission；新 current 不能复活旧 emission。

代码注释解释这些不变量、事务/资源所有权与限制，引用已经实际阅读的本工程/open-source来源。若需确认 Jackson/Spring/PG API，先窄查本工程现有参考和本工程依赖原件/官方源码；新增上游阅读必须记录版本、路径、范围，不能声称已阅读未读的项目。

## 容量与所有权

所有限额同时适用：scope/initial 单 JSON 1048576 UTF-8 B；metadata 合计8388608 B；总 records4096；每 scope parents4096；control262144 B且计 metadata；root payload 合计8388608 B；event 完整摘要投影1048576 B；event 公开 payload 原262144 B。元数据 parser depth32/token65536/string65536字符/number1000字符；root parser depth64/token262144/string1048576字符/number1000字符。含 field-name token、重复字段/尾随 token/错误类型拒绝，流式 constraints 在 DOM 前生效；long 加总防溢出。

完整 scope 及其父数组的重复数据必须计入，不能只计唯一 hash 或压缩摘要。C=7+4N+4A+E、D=C+U、H=sum(parent counts) 只作模型，实际真 PG 测量才是证据；一层父也可二次增长。4096父至少需4097条记录，被总 records 同时约束，不能声称4096父端到端正例；固定限额不是全部合法 run 的最大值。不得缩 writer/schema/历史或静默截断来适配 reader。

reader 只有2个实际 workspace permit，覆盖准备/事务、commit后 pending emission、同步 active consume 的整个寿命。第三个立即有限容量拒绝。成功 mint 把原 permit **原子转移**给 emission，无释放/重申请、无第二结果池/队列。空轮、异常、commit后失效仅在实际 JDBC/parser finally 退出后释放。取消/Future取消/close 只是逻辑标记，不能提前归还仍被占用的 permit。

`EventEmission implements AutoCloseable`，私有构造、一次性同步消费；返回绑定小型只读信息和同步 guarded callback/borrow，不暴露无保护的大 payload getter/copy。pending close 清除大引用并释放一次；active close 与 begin 原子竞争，active 时标 closing、禁止后续使用，待实际 consumer finally 才释放。live 检查在进入/退出及必要访问边界；消费者抛异常也释放。后续 C 让 borrow 包住编码、actual stream 获取、最后核验、write/flush 真正返回；阻塞写仍占 B permit 和 C frame/slot，不在 B 声称该出口已验证。

实现明确有限表示账本：raw UTF-8/text UTF-16、parser/token/DOM、规范化排序及摘要编码副本、完整 parent arrays、event 消费表示与扩容 backing capacity 的同时存活上界。逐记录释放，转换/排序副本计费；不能用8MiB raw bytes冒充8MiB JVM heap。可采用明示的保守 representation 上界及实测最大 backing capacity，不虚报精确对象堆大小；工作区最多乘2。必要时为各阶段定义有限常数，不新增通用预算框架。

容量错误与拒绝/篡改/撤权区分：B 暴露可区分的 finite capacity 原因，workspace opening 后续 C 映射429/STREAM_CAPACITY，committed metadata/root BUFFER_LIMIT，公开 event EVENT_LIMIT；B 不写 HTTP/stream_reset，不把其他拒绝伪装为容量失败。

## TDD 与隔离验收

先读取 preparation 第1/4节，完整落实所列样本。仅新三测试文件，既有 fixture/readers tests不改。真签名 E exchange/JWKS/JWT/authorizeRetained/trusted frame/openPortalEvents 取得实际 response，禁止手造 Capture/VerifiedAuthority/currentOnly 或重签正例。common test helper 是受信 seam，不能称 EVENTS HTTP 成功。可用已有 common test-jar，不使用任何业务服务 sibling classpath。

- 真 PG 新建本任务独占数据库 `agent_platform_iam_o2a_b_material_v12`；先 current_database 白名单校验、使用本工程用户/密码、正常 Flyway engine migrations。不得操作现有 DB、改迁移或做全 PG 扫描；不删测试 DB/历史资料。测试需要本库初始化 DDL/随机 schema可用，业务 material scope 的原始 SQL 插入/删除/历史重写一律禁止。
- 新15节点 START→13 CODE(top-level expression字符串`1`)→END，14条 from/to 边；真实 GraphRules 与 RunService.createReservedSessionTurn 的持久化前 DAG sentinel，wrong source/target 边对照。在同 PG transaction 调生产 createPublished/15次createNode，之后实际 current/run.advance 与 TraceStore.event。首事件预计67直接父/68所需记录，须实测并保存 N/A/E/U/D/H/各 revision/字节/max record/root及 backing capacity，不能以公式代替。
- 真 reader 读取该事件；正常追加新事件/新 revision 后下一轮可前进；同 owner 新登录不同 origin可读，其他 owner/tenant/资源拒绝；expired historical grant不能独自阻断当前授权读。initial cursor greater-than-afterSeq 在 permit/SQL 前拒绝，等于/小于进度可行。
- 实际 SQL、ResultSet getter 计数和事务隔离/只读/同连接观测：metadata拒绝时payload0；scope完整验证后才payload；真实payload摘要失败时payload>0且无emission。若负例通过受控 JDBC 层篡改返回，明确是对抗 getter 对照，不称已修改 PG immutable历史，不伪造权限正例。
- ambient READ_COMMITTED 与 RR 都拒绝；同 RR 并发 append不混快照，下一轮见新 seq；SQL阻塞/解析期间stop、过期/新generation、commit后撤权无emission；事务内 authority网络调用数0。getter/remaining关闭、过期、generation失效本地拒绝且0HTTP；<1ms不发SQL，不可用0timeout。
- 实际 PG 参数化 VALUES/临时机械数据集复用相同 probe/CASE 形状，构造精确1MiB/+1、metadata8MiB/+1、root8MiB/+1的 UTF-8 实测边界；+1不返回大 text。该层是机械 SQL，不是业务材料授权正例。parser 的所有单项 exact/+1、父4096/+1、records4096/+1、重复hash/类型/trailing/long overflow，必须在受限 parser/账本实际路径测。若某合法结构无法同时命中所有单项上限，报告相交约束，不造正例。
- 用真实生产 writer 生成至少一个容量拒绝样本，记录最先触发维度；不可用裸插材料或修改schema配容量。精确合法字节边界生成不了时写清“机械覆盖+真实样本”的限制。
- 两个 permit 实际持有时第三个拒绝；逻辑取消被阻塞 JDBC 后第三个仍拒绝，实际退出后恢复；pending emission占permit；pending/active close竞争、blocked consumer、consumer异常、重复close、generation改变均不早释/泄漏/复用。

使用不改动的 `.tools/run-iam-module-tests.mjs`，先具名 focused RED（实际行为失败；编译/配置错误不算RED），实现后 focused GREEN。每个stage唯一 `iam-stage-o2a-b-*`，命令与原始 stdout/XML逐阶段保留。最终一次 affected command：

`node .tools/run-iam-module-tests.mjs iam-stage-o2a-b-final01 engine-runtime RunEventReaderPostgresTest,RunEventReaderBudgetTest,FlowMaterialScopePostgresTest,FlowMaterialStatusPostgresTest,InitialMaterialScopePostgresTest,GraphRulesTest`

最终命令运行前冻结 scope；红绿迭代仅跑实际失败的具名方法。不能用`+`连接方法，不在成功后反复跑全类。若300秒上限与真实样本冲突，先报分组方案，不改runner/无端重跑。最终实际 Running 类才是测试计数依据，不把编译文件数/旧XML/多次运行加总称一个suite。root/reviewer不重跑你的绿测、PG样本或replay。

## 冻结、复核和交还

实施前按 Accepted589 manifest **一次**拷贝所列源文件成 `.local/iam-o2a-b-material-reader/baseline/` 并记录 source-before清单；不得扫描旧target或继承巨大目录。记录本任务真实 compiler createdFiles/inputFiles/resource/test-fixture-JAR/actualRunning reports 的路径，正式 target overlay仅取这些有限候选。历史逻辑2023保持继承关系；本次新 `.dumpstream`/诊断单独归档，不进正式 compiler/逻辑overlay；旧908历史项不重扫或“清理”。如果发现具名旧target不符，先报告，不能覆盖、还原或假定它是本次变化。

最终 source-after按 source-before589加本任务4个新增路径计算准确数量（新增生产1+测试3，预计593；**实测为准**）；逐文件 diff仅允许上述8路径。保留 byte-exact patch、独立 private replay目录，第一次 `git apply --check` 与第一次实际apply的真实命令/工作目录/退出码及失败也保留；无commit、不向项目.git注入属性、不靠后拷贝修复。成功后 replay所有源清单名称/bytes/SHA与最终一致。若元数据/helper失败，journal分阶段缓存成功结果，仅恢复未完成阶段，禁止重测/重编译/重新成功replay或再扫描已核物理项。

完整报告写 `docs/evidence/iam-stage-4-o2a-b-material-reader-report.md`：实现与范围、逐条验收证据、原始RED/GREEN/final实际类/方法计数、失败/修复、实际PG样本和容量/表示账本、权限及permit生命周期限制、源码/target lineage/diag/patch/replay/inventory身份、未覆盖/风险。保留自身task evidence目录及local目录的单次own-inventory（含临时privateGit元数据的实际叶子，唯一自排除项为inventory自身）；不得递归扫描历史目录。

提交 `handoff.json`，明确 report/source-before/after/target-after/overlay/inventory/patch/replay/raw/XML/诊断/实际变更身份及成功阶段缓存。root另生成完整上下文 review包，fresh reviewer核 spec+quality，再做有限执行证据/产物核验后决定Accepted；作者完成不等于Accepted。不更新八执行索引、不发布、不打包、不停止已有进程。

返回不超过15行：DONE/DONE_WITH_CONCERNS/NEEDS_CONTEXT/BLOCKED、无commit、测试一行、报告路径和风险。发现真实架构冲突或容量要求不可实现先报具体证据，由root解决；不要静默缩范围或用假实现交差。

EOF
