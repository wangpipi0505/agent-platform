### Spec Compliance

- ✅ Spec compliant：本轮可由冻结四文件及下述具名 provider 核查验证的 F1 要求满足。只认可严格 wire decoder 与本地有限预算，返回 UntrustedPacket；没有把结构、摘要或 COMMIT 当作来源、授权或完整业务验材。没有发现缺失/额外的阻断实现项。
- ⚠️ 完整 source612/delta4、有限 target、source epoch 与实际 class 对应、两次不同 patch 的历史/恢复事实、owned inventory 与八索引不变，仍由 root 的独立有限审计闭合。本审查没有重跑测试、PG、git patch/replay，也没有遍历 target、private .git 或全部 own 根。
- **Task quality：Approved。C0 / I0 / M1**。M1 是已存在且非阻断的运行诊断噪声，见下文；不将测试输出称为 pristine。

### 审查输入与范围

- 按 `.local/iam-o2b-f1-review-instructions.md` 和 SDD `subagent-driven-development/task-reviewer-prompt.md` 执行独立 task-scoped spec + quality gate。
- 唯一 shell cwd：`G:/207/项目/算法重组工具/代码/agent-platform`。唯一写入是本报告；无源码、target、作者 evidence、helper、索引写入，无相邻旧工程访问。
- Base：Accepted E+fixes source608。Head：作者冻结 source612，manifest 117016B / SHA256 `b391ee9fe99f69b51066006bdb468bcf4750cf2f6bd16a2dc22ae458fae301f4`；此 head 身份为审查绑定，完整 manifest 一致性交 root。
- 完整读正式 brief（15809B / `5689eb9405e574b1c7966177105f795393994c6612d8960296e2c185b9ec9a1a`）、boundary clarification（1077B / `d866cbaf9b1025b4d0412f55d3d117a705d9600fcb3e00ee07c97a39da8be42d`）、byte replay recovery（2871B / `d355760e725da552003f3f83ad401cfc9b21c7409388cb92ffa4a7306e0fa1f7`）与作者报告（17810B / `763b36e97c1e1342990d393dc0df7647bad8637c37123c94f2d652052e48c25d`），并核这些文件身份。报告和设计解释均作为待核 claim。
- 完整 diff：`docs/evidence/review-iam-stage-4-o2b-f1-gateway-decoder-package.diff`，52972B / SHA256 `3096b44f738ea971ef65c6fc25411e607f8bea40c09eaa46ff0be1fe77ee0c00`，读取前后 hash 一致。按 package 1–180、181–360、361–EOF 读取；最后一段工具输出在 509–519 附近截断，只补读 509–520 缺失上下文。ReadAllLines 为535物理行，按换行 split 含末尾空段为536；已到 EOF。没有另读 changed source，也没有重取 git diff。
- 以下 head 行号均来自该完整上下文。路径简称 D=`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketDecoder.java`（223行），B=`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudget.java`（55行），DT=`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsPacketDecoderTest.java`（133行），BT=`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudgetTest.java`（94行）。

### Strengths

- **长度、顺序与摘要按实际 wire 拒绝**：D:52–60 在 u32 无符号转 long、逐段限额和 checked total 后才 cast/new；D:64–73 有界读完整剩余段并核 COMMIT magic/ordinal/SHA、精确 metadata/payload SHA，最后才发布 packet。D:46、52、76–80 实现 HELLO 一次、连续 ordinal、MAX_VALUE 耗尽与 CAPACITY 后追加拒绝。D:88–90 每次至多8192B且不超当前段余量，D:80–85 失败 terminal/实际 finally 释放。
- **严格 JSON 路径确实遍历所有 token**：D:100–142 用 REPORT UTF8 Reader、禁三类跨阶段缓存、strict duplicate detection；VALUE_STRING 显式 finishToken/getTextLength/getTextCharacters；本地计数包含 FIELD_NAME 与 END；未使用 skipChildren 或 DOM。D:143–148 检查 name/string 的 UTF16 surrogate 配对，D:149–175 严格 header scalar、固定根键、直接类型与 event 空值一致性。动态 nested 字段不套根白名单，业务规范化和授权没有偷渡入 F1。
- **关闭和账本不以取消标志替代退出**：B:11–16、26–45 在 Owner→budget 顺序下收费/回滚/归还；D:29–37 绑定一个 input，一生一个 decoder 由 B:26–28 保证；D:177–180 读/parse active 时拒绝 close。D:200–221 在 Owner 锁下访问 backing、持有 borrow 计数并独立收费；packet close 拒绝 live borrow，Segment close 清 packet 引用，close 后无法继续访问旧 array。没有额外 mutable array/DOM/大 String 出口；Header 的小 immutable facts 与其存活由基础费用覆盖。
- **测试对真实行为有约束**：DT:48–62 校冻结输入 file/bytes/SHA、7B短读、25包、三种 kind 与至少65 parents；receipt 明确未认证。DT:24–27 机械畸形包重算外层段 SHA/COMMIT，DT:83–115 有严格 JSON、可达 depth/token/string/name/number 正例及 +1；DT:117–131 覆盖最大 wire、unsigned length、4096 eventType 与 Unicode/long 边界。BT:39–45 用真正阻塞 read 与 interrupt 证明不提前退账；BT:47–60 观察真实 wire backing、预收费、不预取及大长度只读取24B。
- **范围与责任清楚**：D:12–16、181–185 明确 UntrustedPacket 不代表来源、关联、row digest 或发布权限；四新增文件没有 Spring 注册、HTTP/TLS/SSE/main/common/pom 改动，也没有新业务依赖。此项只判断包内所见 scope；全 source 写入集合仍由 root 核对。

### 固定 provider / 64MiB 的独立窄核

- 核查风险：Reader/byte bootstrap 分支、recycler/symbol 缓存、lazy string/聚合、duplicate set 和临时 scalar 是否有未计费的大图。输入是 `provider-model.md`（7474B / `d2a7f7eeb4540b4fb0f8930d8334b715922882befcbe8cd1fb95601d284de502`）与 `provider-evidence.json`（8707B / `801d6eae881f467bda81f07a00aea25efa5c4706fe498628e57faf9f8ca0ecfc`），均完整读并核身份；另核实际 Jackson 2.19.0 JAR SHA `da8e859bac94874528116a25f20c68560e4287acbf27628711b8a4f96b028430` 与下列18个具名 primary 源文件的保存 bytes/SHA。
- `.local/iam-o2b-f1-gateway-decoder/primary/` 内实际所读范围：JsonFactory.java:1291–1298、1900–1912、2158–2219；json/ReaderBasedJsonParser.java:140–235、1538–1680、1809–1900、2142–2240；base/ParserBase.java:265–286；base/ParserMinimalBase.java:853–865；io/IOContext.java:286–305、387–409；util/BufferRecycler.java:90–132；util/JsonRecyclerPools.java:130–170；util/ReadConstrainedTextBuffer.java:1–29；util/TextBuffer.java:155–210、430–530、1005–1045、1146–1245；sym/CharsToNameCanonicalizer.java:250–310、387–413、490–525；json/DupDetector.java:20–111；json/JsonReadContext.java:85–208。JsonFactory 的 fetch 元数据指向 FasterXML `jackson-core-2.19.0` tag；没有声称完整审阅这些文件之外的 provider 实现。
- 同根 `jdk21/java.base/` 实际所读范围：sun/nio/cs/StreamDecoder.java:300–340、UTF_8.java:258–290；java/lang/String.java:270–315、4748–4774、StringUTF16.java:150–175；java/util/HashMap.java:281–300、683–754、1960–1985、LinkedHashMap.java:190–213。与 final XML 的 `java.home` Adoptium jdk-21.0.12.8-hotspot、java.version 21.0.12、vendor Eclipse Adoptium 对齐。
- 分支结论：createParser(Reader) 实际构造 ReaderBasedJsonParser；关 USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING 返回 NonRecyclingPool，并创建局部 BufferRecycler；关 canonicalize 的 findSymbol 直接验证名称长度再 new String，不向共享符号表积累输入名。TextBuffer 当前完整 segments 在每次 finishCurrentSegment 时通过 constrained subclass 检查，单末段至多65536；getTextCharacters 聚合另有 char[]，该副本已列成本。DupDetector 的前两名和 HashSet 以及 context child reuse 的残留名称都算在本段全部已读 name 总数中，不能仅按当前深度计算。
- 费用复核：T=262144，允许 parser 预解析及拒绝前少量前进，N=131074；全部已解析属性至少各占 name/value token，故名称数量受该 N 约束。全段 decoded UTF16 name 内容不超过 wire M=8388608 字节，按非压缩字符串计2M。256N 已覆盖 String/array 对齐头、最坏 TreeNode 和 resize 新旧 table；JDK resize 复用现有节点，节点继承字段已计入。S=1048576+65536，10S 覆盖 segment、聚合、当前 String/Latin1尝试及临时副本；另4MiB覆盖固定 parser/context/reader/hash/诊断等图。合计65,667,584B，低于预收费67,108,864B，余1,441,280B。D:42 先收该阶段费用，D:57–63 对 prefix/header/wire 的临时并存另外收费，D:83–85 在实际扫描返回后才退阶段费用。
- D:137–141 抛新的常量 IOException，不将 Jackson exception/cause 所持 parser/content graph 带出 scan；factory/parser/Reader 不进入 packet，避免把 close 当作图已经消失。小 header facts 留在 Owner 基础65536B内，raw 按实际 length+32B持续收费，借用每个256+128B。此固定 provider、固定代码路径的保守费用模型足以支持 F1 本地预算结论。
- ⚠️ `ThreadMXBean threadAllocatedBytes=5412560` 只证明这一次实际分配观测；`inputChars=4000 / aggregatedChars=1048576` 只证明实际 provider backing。它们不是 worst-case live heap 测量，也不是64MiB上界的单独证据。上述结论依赖静态模型；F2 增加 DOM、normalization、frame、transport 或更换 provider 时须重新证明并从同一 Owner 取预算。

### 证据核对与无法由 diff 证明项

- 只读唯一 final command/raw 与它引用的两份归档 XML：实际 Running 仅 BudgetTest/DecoderTest，9+11=20，F/E/S均0；raw.log:54、63 是16 production/13 test javac，raw.log:73–83 与 XML逐方法一致。final raw的 `F1_PROVIDER_BACKING`、`F1_REAL_SCAN_ALLOCATION`、`F1_WIRE_ALLOCATION` 对应 BT:77–91 与 BT:47–53 的真实断言。没有另跑 suite 或 probe。
- ⚠️ 其余阶段按已读 test-results.json/作者报告作为记录：RED2F；首次green compile失败0Running；compiled17/2F；focused5green。没有把编译失败或 selector 预启动拒绝算功能RED，没有声称20方法各自有历史RED。全部 stage raw/XML/epoch、compiler lst和字节归属仍由 root 有限审计核验。
- ⚠️ header depth8/token2048 在固定标量 schema 下孤立最大值不可达；payload 256KiB先限制1MiB字符串和262144 tokens。遵循 boundary clarification，核配置与交集，不伪造这些不可达正例。DT:109–115 的 metadata可达限额及payload depth64/65为实际边界；这不意味着覆盖 F2 的 scope、records、parents、nested schema 或业务关系。
- ⚠️ 未重做两份 patch/replay。原文本规范化 patch 虽check/apply=0，完整612字节失败；另一个binary patch在独立baseline恢复的claim不替代代码判断，也不抹去原失败。该历史与恢复完整性由 root 单独审计。
- ⚠️ F1 只借用 InputStream。BT:33–37 证明 decoder不关闭上游；BT:39–45 的阻塞输入验证本地实际退出边界，不覆盖真实HTTP/TLS abort、Servlet complete、来源/receipt association、G current、完整材料或公开SSE。EVENTS默认关闭的全局状态与既有STATUS/POST不变，需 root 通过零existing写入审计继承，未在此扩读入口。
- ⚠️ 只读核查中一次 PowerShell `ConvertTo-Json -Depth 3` 显示 serialization depth warning，仅截断审查输出中嵌套 suite 展示。随后直接解析两份归档 final XML核实20方法/运行时，未写或改变证据；该工具输出警告不构成产品问题。

### Issues

#### Critical (Must Fix)

- 无（C0）。

#### Important (Should Fix)

- 无（I0）。

#### Minor (Nice to Have)

- **M1：运行证据仍有诊断噪声，不能宣称 pristine。** `.local/iam-o2b-f1-gateway-decoder/iam-stage-o2b-f1-final/raw.log:55`、`:64`（各自及下一行）记录既有 IntegrationPolicy 与 GatewayPublishedChainPostgresTest 的 deprecated API 提示，且中文路径/信息存在编码乱码；`docs/evidence/iam-stage-4-o2b-f1-gateway-decoder/diagnostics/platform-gateway/2026-10-21T16-20-08_431.dumpstream:2` 记录 Manifest-JAR absolute classpath / different root，另外16-05-03、16-09-34、16-11-06三个已归档 dumpstream 内容同类。后果是日志可读性与启动环境诊断不干净；本次实际两类20测试均运行成功，未发现它影响四新增源码行为，因此非阻断、不是新业务缺陷。最小处置：保留原始诊断并在root验收中明确；未来允许修改运行器/既有代码时统一日志编码、核跨盘classpath配置及弃用调用。不要为消噪重跑本轮成功suite、修改冻结证据，或直接照hint关闭JDK检查。本项没有可归责的新增head源码行；它是按模板要求必须列明的实际证据发现，位置以上述原始artifact行定位。

### Assessment

**Task quality：Approved；Spec Compliance：✅（可验证部分）；C0 / I0 / M1。** 四文件在协议边界、预收费及实际关闭所有权方面形成完整的 F1 实现，测试包含独立 encoder 输入、重算摘要的恶意结构和真实阻塞读取。root 的 artifact有限审计仍是 Accepted 前置；审查结论仅为“严格wire decoder及本地预算通过，输出未授权材料；完整来源/授权/验材/公开输出仍待F2”，不授予全F、全IAM、全后端、生产容量或部署结论。
