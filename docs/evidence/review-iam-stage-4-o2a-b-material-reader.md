# O2a B 独立任务审核

**Spec compliance: Needs fixes. Code quality: Needs fixes. Critical 0 / Important 1 / Minor 2.**

审核对象为 Accepted589 → labels-fix593 的八文件完整上下文 diff；无 commit。以下结论来自代码和具名证据，未采用作者自评作为通过依据。

## Strengths

- `admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java:166`、`:173` 两个新 getter 均在 holder 锁内先核同 capture，并保留当前证据的粘性失败；没有扩大公开 proof 接口。
- `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:124` 起的独立 bounded loader 先量 control/initial、再 CASE 取文；没有借用原无界 loader。`RunEventReader.java:73`–`:118` 以当前已验签 subject/resource、精确直接父集合、完整 scope、三根 admission 与 floor 完成元数据/coverage 检查后才消费根和完整事件行。
- `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:579` 保留独立公开 payload 256KiB 和完整行 1MiB 检查；`:580` 核完整行摘要，`:577` 保留 UTC 微秒时间投影。根 inputs 不读 payload，见 `RunEventReader.java:99`–`:107`。
- `RunEventReader.java:49`–`:69` 拒绝 ambient transaction，自建只读 RR，并在真正 commit 返回及 mint 前核同 capture；`:195`–`:208` 在参数准备后设置正数 floor 毫秒 timeout，设置失败有限拒绝。没有声称精确物理打断阻塞 JDBC。
- `RunEventReader.java:123`–`:164` pending/active/closing 与原 permit 转移逻辑清楚：active close 不早释，consumer finally 清引用并释放，旧 generation 不能复活。真实 PG 锁等待及 consumer 阻塞测试分别见 `RunEventReaderPostgresTest.java:185`、`:172`。
- `RunEventReader.java:26`–`:45`、`:183`–`:233`、`:264`–`:274` 将 raw、parser、model、canonical 和总预留分开，先 preflight 再 DOM；decimal 膨胀在 normalize 前准入。当前线程 recycler 清理有明确有限槽数和异常保留，测试另核其他线程不受影响（`RunEventReaderPostgresTest.java:251`）。这支持保守表示模型，不构成精确 JVM heap/生产容量证明。

## Issues

### Important I1 — Borrow 将已验证 payload 的可变 backing array 交给调用者

**证据：** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java:161` 的 `output.write(emission.payload)` 直接调用外部可覆写方法，交出内部数组本身。`:136` 的 guard 只核使用状态、线程及授权；同一次 consume 内允许多次 writePayload。`:137` 的 finish 只能清自身引用。`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderPostgresTest.java:222` 已用覆写 `write(byte[])` 接收该实际数组测 backing；`RunEventReaderBudgetTest.java:86`–`:89` 仅检查方法返回类型，检测不到此出口。

**影响：** 一个 sink 在第一次合法输出时改写传入数组，之后同一 active borrow 的第二次输出将使用被改写内容，却仍搭配原已验证行摘要/Info。sink 也能保留 reader 所有的原 backing，finish 后无法撤回该别名。这是内部存储隔离/完整性问题；并非要求阻止合法接收方保存它已收到的数据，也不把接收方自行缓冲的容量算进 B。

**修复方向：** 输出时不要把 emission 自有数组交给外部虚方法；使用分配前计费的有限 scratch/copy，覆盖实际 write 返回，并保持 current/close guards。若以可信 sink 契约代替隔离，须明确缩窄 API 信任边界并重新评估 brief 第64/66行的借用和所有权承诺；现有公开 OutputStream 签名没有该约束。

**建议具名验证（本审核未执行）：** 用现有真实 writer/response 得 emission；同一 consume 首次 write 的 sink 改写收到的 byte[]，第二次 write 到普通 ByteArrayOutputStream，断言第二份仍与原已验证 payload 相同；同时核阻塞输出期间 permit 不释放。现有绿测没有回答这个行为问题。

### Minor M1 — root field-name exact/+1 缺少要求的独立机械边界

**证据：** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderBudgetTest.java:20`–`:25` 覆盖 root depth/string/token/number，却没有 name 长度 exact/+1；metadata 的 name 覆盖位于同文件 `:15`。正式 brief `:79` 要求 parser 所有单项 exact/+1；作者报告 `:163` 也明确承认该缺口。生产 `RunEventReader.java:235` 配置了 root maxNameLength，未见实现绕过，因此按覆盖缺口计 Minor。

**建议：** 增补唯一具名机械用例，分别使用长度1048576和1048577的字段名，并明确它验证 parser 维度，不能冒充同时通过 model/raw 所有相交限制的业务样本；不用重跑整个 PG 套件。

### Minor M2 — 最终阶段编译提示归属不准确

**证据：** 作者报告 `docs/evidence/iam-stage-4-o2a-b-material-reader-report.md:134`、`:187` 将 deprecation/unchecked 提示与最终阶段并列。实际 final02 原始日志 `docs/evidence/iam-stage-4-o2a-b-material-reader/iam-stage-o2a-b-final02/iam-stage-o2a-b-final02.log:28`、`:34`、`:52`、`:58` 的 compile/testCompile 均为 Nothing to compile；对两个 final 原始日志具名搜索未发现 WARNING/deprecated/unchecked。final02 `:70` 起是正常 Flyway INFO，不能当 warning。日志部分中文路径/异常文本存在乱码（final02 `:32`、`:56`，final01 `:233`），属于实际可读性噪声。

**建议：** 将历史编译提示指向其真实发生阶段和原始行；final01/final02 分别陈述实际输出，保留原件。无需为清理报告噪声重编译/重跑。

## 既有失败与范围限制

- **已独立支持 inherited setup 诊断，未据此宣布全回归绿。** `InitialMaterialScopePostgresTest.java:131` 使用 STATUS + app-version ROOT；`:132` 在 exchange 阶段出错，尚未到 `:133` read 断言。`RunObservationSelectors.java:41`–`:49` 已要求 STATUS 与 run-snapshot 双向匹配；`LoopbackAuthority.java:518`–`:523` 在 exchange 先执行该校验。final01 原始日志 `:232`–`:238` 明确栈终止在 exchange/旧测试132行，`:321` 为65 tests/1 error，`:330` BUILD FAILURE。该旧测试需另任务迁移，当前 B 不应通过放宽权限或修改历史原件掩盖。
- final02 原始日志 `:68`、`:142`、`:146`、`:155` 分别支持 Budget13、Postgres18、31/0/0/0、BUILD SUCCESS。该结果不消除 I1，不与 final01 或其他 GREEN 阶段累加。未重跑任何测试、PG 样本、编译、replay。
- ⚠️ 最终完整源码manifest/target overlay/inventory/replay所有条目、上游 sources.jar 来源和全阶段 RED/GREEN产物由 root 独立有限审计；本次没有重新执行这些物理验证。scope/parent容量样本与表示测量是有限样本，不是所有合法 run 最大容量、整个引擎 heap 或生产并发保证。
- ⚠️ parser中 stop 有实际 capture 回调，SQL边界有 expiry/generation/commit撤权，真实锁等待有 stop/cancel；没有将所有解析/阻塞时机的 expiry/renew 组合实测。`RunEventReaderPostgresTest.java:165`–`:170` 的 generation/expiry发生在 SQL调用钩子，不能称其验证了真实 PG 阻塞期间全部排列。
- ⚠️ B 未注册 HTTP/SSE/receiver，没有跨服务实现/JAR新增；C 的实际 stream获取、编码/frame预算、write/flush、唯一reader owner与跨轮进度仍须单独验证。读到的八文件 diff 不证明整个分支其他来源未变动，root 应以冻结manifest完成范围核验。

## 具名 unchanged-source 检查

仅为下列具体风险读取 diff 外源码，没有浏览其他业务或相邻旧工程：

1. **GraphRules是否真正承担 pure FLOW 依赖限制及额外分配风险：** `engine-runtime/src/main/java/cn/agentplatform/engine/domain/GraphRules.java:140`–`:201`（EOF）。requirePublishedFlow 在156–198拒绝不支持依赖/节点类型；此次调用没有进入前方递归 DAG visit，未发现该调用引入额外无界递归。
2. **common scopeDigest/Json.canonical是否会遗漏 dataLabels义务或转换副本：** `admin-common/src/main/java/cn/agentplatform/common/authority/MaterialAuthorization.java:1`–`:50`（EOF）、`admin-common/src/main/java/cn/agentplatform/common/Json.java:1`–`:80`（EOF）。确认 labels 是义务非 permission set，canonical经过 tree/normalize/排序；B 的模型明确计这些副本。没有删改公共算法。
3. **未经长度probe的 metadata scalar是否由schema实际有界：** `engine-runtime/src/main/resources/db/migration/V216__run_authority_initial_materials.sql:1`–`:45`（EOF）、`V217__run_flow_materials.sql:1`–`:18`（EOF）。tenant/locator有varchar界，producer/kind固定，version为64hex或等于bigint revision文本；所以 ObservedRow 的标量读取不会偷偷读任意长version。只以rg --files定位这两个已具名版本。
4. **旧Initial测试是否真的在reader前因既有selector失败：** `InitialMaterialScopePostgresTest.java:116`–`:137`（EOF）、`RunObservationSelectors.java:35`–`:55`、`LoopbackAuthority.java:510`–`:526`，配合上列实际final01栈和 `.local/iam-o2a-b-inherited-test-failure-check.json`。没有把root诊断当作代码证据的替代品。

## 读取范围、身份与操作记录

- 唯一 shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。没有git命令、数据库连接、测试运行、相邻工程访问、线程消息或commit；唯一写入为本审核报告。
- reviewer模板、正式brief、三份补充决定、preparation 1–101 EOF及作者报告已阅读。brief实测19412B/SHA256 `879d6077463aafc5e063a3eb0af58b3600cd4f27d512afbff8d449fa8eb7a340`；作者报告实测29408B/SHA256 `0fca24b8c6551228eb340fd0ebcfcfd7619fea6f0c1c5385cb20b2e394e33f60`。
- 完整diff：`docs/evidence/review-iam-stage-4-o2a-b-material-reader-package.diff`，实测231237B/SHA256 `6bb5b9f9309df592f0d7c4acdf1a2ba94485b97150c057537e8869001fc765c0`，ReadAllLines=3459；最后byte=10，按含尾空项split为3460。
- 主读取范围依次为1–450、451–950、951–1400、1401–1640、1641–2240、2241–2840、2841–3130、3131–3280、3281–3390、3391–3459 EOF。951–1400这次工具输出中间截断，单独补回1115–1204被截/边界内容；没有因审核方便另读任何changed source，也未重读其余完整diff。所有八文件hunk均完整至EOF。
- 额外证据只读为具名final01/final02 raw、labels-fix/commands.json定位raw路径、root inherited检查记录及报告行定位。曾误猜final02位于labels-fix子目录，路径不存在；随后用commands.json解析真实路径，没有递归扫描证据树。

**Assessment：Needs fixes。** 有限SQL、已验签观察权限、PG一致快照与permit实际生命周期实现有充分可见支撑；在修复自有payload数组别名出口前，不接受“受保护只读借用及原表示所有权”已完成的结论。C/生产容量和旧测试迁移继续保持明确未验收。

## Borrow-fixes epoch 独立复核（追加，不改上述历史结论）

**本epoch Spec compliance: Approved。Code quality: Approved。当前 Critical 0 / Important 0 / Minor 1（M3，仅既有编译/日志噪声，非阻断）。原 I1/M1/M2 全部关闭；未发现本delta新增代码缺陷。** 上述 Needs fixes 是 labels-fix593 的历史结论；本节判断 labels-fix593 → borrow-fixes593 三文件修复，不能解释为 C 或整个项目验收。

### 修复核验与设计判断

- **I1关闭：** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java:66` 将原 Work.ledger 与同一 lease 交给私有 emission，`:124`/`:126` 持有该账本；`:173` 先 reserve(payload.length+24L)，`:174` 才 clone、再核 guard、调用 sink、返回后再核 guard。外部虚方法只获得独立副本，不能改写 emission 原 backing。`:175` 的 finally 恢复 outputActive，try-with-resources 覆盖实际输出返回/抛异常并释放临时额度。没有改方法签名、增加permit或把顺序多次输出改为一次输出。
- **重入与线程所有权成立：** `RunEventReader.java:145` 的 beginOutput 先执行同 consumer 线程/live guard，再拒绝已有 outputActive；`:149`/`:166` 两种输出共用它。原 consume 每次只创建一个 Borrow、只有一次合法consume，且 active close 不清 payload，因此异线程误用不能进入输出标志读写，关闭与clone之间不会出现payload被并发置null。四种同借用交叉重入均有限拒绝；内层失败发生在它自己的try/finally之前，不会意外清外层outputActive。
- **账本相交及实际退出成立：** 准备阶段既有 raw/parser/canonical reservation 已退出，随 emission 保留的 model最多64MiB与fixed8MiB是保守占额；新增copy另计最多262168B，不挪用fixed scratch。输出阶段账面上界为75759640B，低于192MiB，仍经过同一 ledger 的硬检查；两个permit仍合计受384MiB模型约束。24B仅为部署标准对齐下的数组头/对齐保守余量。借用重入guard使一个emission同时最多一份输出copy或labels generator；两个并发emission分别持自己的ledger。sink保留的独立接收数据属于接收方；此结论不为C的接收frame/queue内存背书。
- **真实行为测试覆盖原漏洞及新失败路径：** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderPostgresTest.java:103`–`:117` 基于真实writer/签名response，改写首次实际参数后，第二/第三次输出仍匹配原始JSON，并拒绝调用方flush/close；`:118`–`:144` 覆盖四种重入、两个输出方法IOException后的同borrow恢复、未捕获异常释放和结束后拒用；`:145`–`:165` 让两个实际OutputStream.write同时阻塞，逐个放行后只逐个恢复permit，且active close期间第三个仍拒绝。这些不是接口反射或伪造emission测试，足以覆盖本次有限delta。
- **M1关闭：** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderBudgetTest.java:26`–`:32` 走原parse入口，1048576字符字段名可解析且字段存在，1048577拒绝。注释明确这是parser单项机械边界；未放宽业务raw/model预算。生产配置原本正确，所以此补覆盖不需要虚造RED。
- **M2关闭：** 修复报告 `docs/evidence/iam-stage-4-o2a-b-borrow-fixes-report.md:93` 明确撤回旧报告134/187行的阶段归属，精确指出final02的Nothing to compile内容行29/35/53/59，区别于compiler标题28/34/52/58，并保留实际乱码。与本审核上一轮已核原件一致；旧报告不改写是正确的历史保留方式。
- **未扩大原能力：** 完整三文件diff中，生产改动只涉及ledger引用、复制输出及输出重入guard；原当前capture、元数据先于payload、只读RR内不联网、commit返回后检查、私有mint、一次同步consume、pending/active finally释放均保留。`readNext` 的empty仍仅表示本轮无下一事件，不提供terminal/window证明。

### 精确验证口径及剩余Minor

- 实际raw只读核对：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-red01/iam-stage-o2a-b-borrow-fixes-red01.log:73`、`:87`、`:138`、`:147` 为Budget1通过、PG3中2 assertion failures、合计4项2F/0E、BUILD FAILURE；不是编译失败伪RED。
- `docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-green01/iam-stage-o2a-b-borrow-fixes-green01.log:78`、`:92`、`:96`、`:105` 为Budget14+新增PG3=17通过。这次针对+16实现；最终+24修改后，`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-green02/iam-stage-o2a-b-borrow-fixes-green02.log:90`、`:94`、`:103` 仅新增PG3通过。Budget/parser未随+24改变，继承GREEN01覆盖合理；不将三阶段加总，也不声称最终源码重新运行了一套17项或旧PG18/65。
- **Minor M3（非阻断，既有测试工具噪声）：** 新GREEN02 raw `:56`、`:58`、`:67`、`:69` 实际有-Xlint:deprecation/-Xlint:unchecked编译提示及路径乱码，分别指向RunAuthorityStore、ContextProjection、RunEventReaderPostgresTest和FlowMaterialStatusPostgresTest；GREEN01同样有，RED01在`:62`/`:64`有测试编译提示。这些输出不能称“pristine”；M2已通过正确归属修复，本项仅记录仍存在的噪声，不要求为本次有限修复重编译、扩文件范围或修改runner。后续维护若处理，应在所属构建/编码任务中保留原日志并验证。
- ⚠️ 原measurement测试现在接收copy，`RunEventReaderPostgresTest.java:285` 的sink数组长度与`:287`的actualEventArrayBacking字段不能再证明内部数组身份。修复报告`:23`已经明确撤回这种解释，旧用例本次未执行；原eventBackingBytes仍为read/mint时内数组长度，totalReservedPeak仍为读取阶段指标。新输出的上界来自账本/单输出证明，不宣称新测了输出heap峰值；未来引用测量须保持该区别。
- ⚠️ 原Initial迁移与final01的65/1error结论仍未闭环；C真实HTTP/SSE/write/flush、全expiry/renew排列、全应用heap/生产并发不在此次通过范围。新epoch target/replay/inventory与全部raw/XML的物理身份归root有限产物审计；本复核没有重跑测试、PG、编译、replay或扩大源码检查。

### 本轮读取与前缀保持

- 修复brief完整1–44行，6735B/SHA256 `18ede0333390af1c6e177df31c14b8641447bf2863b85e030cdf3ae63453a544`；修复作者报告完整1–113行EOF，16445B/SHA256 `b0efc3968e2a9ad51b0c3ef4877f34d3427130e19e14b72de02b6f0d19ed21b4`，实测身份匹配。
- 三文件完整上下文delta `docs/evidence/review-iam-stage-4-o2a-b-borrow-fixes-package.diff` 实测81021B/SHA256 `64609f26221d945eb5d7f2554365b053d417175c7af213d1eeddc59a3b409462`，按互不重叠区间1–210、211–420、421–590、591–700、701–791一次读至EOF，无截断补读；ReadAllLines=791，末byte10（含尾空项split为792）。未单独读changed source；本轮无新增unchanged-source检查。
- 另只读上述三个具名新raw的测试/编译提示行；没有扫描历史目录或重新验证root已完成物理阶段。唯一shell workdir始终为本工程；唯一写入为本报告追加节，无git、commit、测试、数据库连接或用户chat消息。
- 追加前核原报告恰为11153B/SHA256 `6cf9b988d040ed6f7ad5c9d631b620dd7e5d117e91ad81cb62a9cb9221190b03`；追加后须以相同11153B前缀摘要确认历史字节未变。本轮新文件总bytes/SHA单独回交root，避免将自身摘要写入本文。

**本epoch最终判断：Approved / Approved。** 原I1/M1/M2关闭；C0/I0/M1仅为上述非阻断编译/日志噪声。原受控预算、权限与实际finally所有权在有限copy修复后仍成立，后续跨任务验收边界继续保留。
