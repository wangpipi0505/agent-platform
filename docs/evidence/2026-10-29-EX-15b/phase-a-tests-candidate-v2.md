# EX-15b Phase A 测试候选 v2

2026-10-29；本次修订者 `/root/artifact_boundary_review`。依据主线程裁定及 `phase-a-independent-review.md`（SHA256 `FC80296C923E4F782B49C34D6A19E12621F90823928611CC16DDFE7B2B442674`），仅另存候选补丁与本文。**未 apply，未写三份测试源/生产/契约/主入口/index，未运行 Java/Maven/PG/HTTP 或产品进程。** 这是作者侧静态修订，不代替非作者复核或真实 RED。

新补丁为 `phase-a-tests-candidate-v2.patch`，11606 B，SHA256 **`D66FF6AD7E53E11236A92444783099336682B1B244B57D551F355EB0B8CCA81E`**。旧 `phase-a-tests-candidate.patch`（90D7B474…9C7CE1B）、旧说明（7C630345…1746）及完整 I/O 候选（A31E630D…3F0D91）均保留，不重写其编写时事实。v2 替代旧说明中绝对路径夹具的描述、当前基数/后续计数，以及尚未明确的 `stdin.files` 兼容边界；不把旧候选当已获准应用。

## 1. 两项复核修正

**A-01 已在新补丁收窄。** 父测试在 `awaitWorker` 已确认本次 PID/startInstant/父进程/可执行/callDir 之后，创建本次自有 `callDir/output/declared-only.bin` 空文件，再放行 worker。使用 `Files.createDirectories(callDir.resolve("output"))`，兼容当前未建输出目录及未来适配器已建目录两种情况；作用域仍限本次已核 callDir。worker 仅构造并返回该文件的绝对路径字符串，不创建/打开声明文件。声明 bytes=0、空文件 SHA、output 子目录位置均合法，唯一故意违反项是路径为绝对路径。missing 例仍仅声明 `output/missing.bin` 而不创建文件。

v1→v2 的实质差异只有上述父夹具目录和 worker 返回字符串；其余变更仅为 unified hunk 行号/计数。未增方法、未改新旧断言、未改错误码/期望。新补丁已通过 `git apply --check`（0），但尚未应用。

**A-02 已修正执行基数。** 当前 skill 源清单为 **152** 份（src 下 Java/SQL/YML/properties；本次只改既有测试，不新增源文件）。真实最新完整基线为 EX-27 `response-ownership-full/runs/full-701-20261029-01`：**701=585P/6F/106E/4旧S，73 XML**，相对回归门已独审接受，allGreen 仍为 false；相邻为 **83P/10 XML**。本批新增 5 方法后目标为 **Full706/73 XML、Adjacent88/10 XML**。旧 Full700/701、151 源与旧 collector 原件不得回改；后续执行须按新源 SHA 与精确方法身份另冻 manifest。

## 2. 仅三份测试、原25＋新增5

| 文件（相对 skill-registry/src/test/java/cn/agentplatform/skill/） | 原方法 → 候选 | 新增方法与准确证明范围 |
| --- | --- | --- |
| domain/ProcessContractTest.java | 17 → 19 | `rejectsNonBooleanFilesOptIn`：字符串、数值、对象、数组、null 经既有 assertInvalid 要求400/PROCESS_CONTRACT_INVALID并指明 stdin.files；`falseFilesOptInAndLegacyStdinRemainUnchanged`：false 与 legacy stdin 树保持。 |
| application/SkillProcessRegistrationTest.java | 6 → 7 | `normalizedFilesOptInSurvivesSerializedContractReadback`：现有 normalize→JSON序列化/回读保留 true 布尔；不是 PG 持久读回。 |
| infra/ProcessFilesTest.java | 2 → 4 | `routedFilesOptInRejectsMissingOutputBeforeArtifactPersistence` 与 `routedFilesOptInRejectsAbsoluteOutputBeforeArtifactPersistence`：经真实 SkillAdapters→注册表→ProcessExecutionAdapter→ProcessAdapter，严格502/PROCESS_OUTPUT_INVALID，ArtifactStore 零交互。 |

定向整类仍 **30 方法/3 XML**。在当前未改产品上 **27P3F/0E0S 仅为预测**：预计 nonBoolean 首值及两个无效输出均未抛目标异常。循环首值失败即停止，不把其余四种非布尔值记为已执行。依赖/编译/Mockito/临时目录/worker 身份/超时等失败不是这三个业务 RED；只有实际新鲜 XML、方法身份及原始 native 码能够确认。

原25方法及原断言保持：v1 已由非作者逐方法核过；本次 v1→v2 差异仅在 helper/worker，未触及任何 `@Test` 方法。又按 unified hunk 在内存重建 v2，逐行核上下文及 hunk 计数，方法总数为19＋7＋4，未把重建文本写回源文件。

| 文件 | 当前源 raw SHA256（未应用） | v2 候选规范 LF 文本 SHA256（非未来应用后 raw 承诺） |
| --- | --- | --- |
| ProcessContractTest.java | `4C32982A92390508913127BDFF7AF4BC1DA002E2320C052D2E4B84967CA8C611` | `94A0E0A7682BE4203CFC3A76A4F646FC74489D3E182268AFAF7D727F257A20CD` |
| SkillProcessRegistrationTest.java | `74581A9ACDF3CE5D26B401F252346C8EBD91BF480ADC996B441D1DF8AC6DA28E` | `65ED5ACA7101A4C8198E0E26BB83609B90F4A2E3BD48F32B73C655D33FB5D705` |
| ProcessFilesTest.java | `7B363256F026266C5A65D4C85E2BEB79D4AF3DADDC497AC1831E30834868A7D7` | `6E4A4AAAB135BD01CA2B830A0E11A3741B060551A98EA2F0D9FA111ECF2A544C` |

## 3. 已裁定的声明、schema 与兼容边界

下列为主线程已裁定、待其准确追加契约后才能实施的规则，不表示当前产品已实现：

1. 仅当 `process.stdin` 是 object 时，保留其**顶层** `files` 键；存在时必须是 bool。true 启用文件信封，false/缺省不启用。旧 stdin scalar/array 及其它字段维持既有行为，不顺手要求 stdin 一律 object，不递归解释同名键。
2. false/缺省时，业务 `arguments.files` 的原值完整保留。true 才解释该键的受控文件引用；不能为了控制键而改写原请求对象。
3. 完整 arguments 先经过现有 inputSchema 和 guard；通过后 adapter 才复制 arguments、在副本中移除启用时的控制键并形成 worker 信封。不绕过 schema，不自动豁免 `additionalProperties:false`。需要文件输入的技能须在自己的 schema 合法声明该键或明确允许；旧 schema 不允许时仍由现有入口拒绝。
4. **兼容例外必须明示：** 当前 `ProcessContract.validStdin` 仅做序列化字节上限与 deepCopy，旧 object 顶层 `stdin.files` 非 bool/null 也会接受；现在有意拒绝这些旧声明。不能笼统宣称任意旧 stdin 全部兼容。

现有源码依据：`ProcessContract.validStdin` 304–314；`SkillExecution` 126/135 与530/534保留完整入参的 schema/guard 校验。Phase A 两路由例直接 invoke 且 arguments 为空；本批的 false/legacy 例只比较 stdin 树，**尚未证明真实业务 arguments.files 的保留，也未证明上述 schema 顺序**。后续输入阶段应分别有 true 路由、false/缺省业务值、schema 拒绝与原请求不变的真实 worker/业务入口证据，不能用本批30例代替。

## 4. 资源边界与实际执行门

本批仍复用已有纯 JDK worker、当前 JDK 精确可执行白名单、1全局/1租户许可、queue=0、keepFailed=0、中文空格唯一根。原20秒契约、25秒 Future、release 握手及 finally 的精确取消/自有子进程退出/executor关闭/目录为空断言保留；不访问声明中的外部路径。旧 `../escape.bin` 也只是返回文本。

路由例注入测试认证上下文并 finally 清除，属于**已认证上下文前提**，不认领正式 JWT 验签。runId/callId 是本次随机测试标识，没有 PG 调用行；ArtifactStore mock 只作零交互探针，不提供假下载/写入结果。不加载 .env，不连 PG/MinIO/HTTP，不操作正式服务/共享对象。

本次静态 HEAD=`6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946`。进入实红前仍须：主线程完成精确契约增补与主卡登记、非作者复核此 v2 接受、主线程分配唯一窗口；然后仅 apply 这三个测试文件并重新记录实际 raw SHA，再冻结新的152源/HEAD/依赖/环境 pin。现在不申请、也不启动该运行。

定向选择保持 `-Dtest=ProcessContractTest,SkillProcessRegistrationTest,ProcessFilesTest`，本仓 JDK21/AP自有 m2、Xmx1024m、现有 unix 临时目录与 surefire timeout900/default fork/order，不加隐藏 JVM flags，`-Dmaven.test.failure.ignore=false`，保留原始 Maven native。先保存新鲜3 XML/30身份与精确原因，只有目标三条差异可认 RED；实现后原30全P，再另冻并运行 Adjacent88、真实PG既有例与 Full706，相对门仍保留6F/106E/4旧S身份和原因，不按总数放行。

这批不实现或证明输入 artifact→worker、成功输出持久化、配额/补偿、链接竞态、二进制回放复制、正式 JVM 重启或 X-1 算法。EX-15b 完整 I/O 仍在进行中。V222 资源恢复与本候选相互独立，未借此修改或运行 V222 collector。
