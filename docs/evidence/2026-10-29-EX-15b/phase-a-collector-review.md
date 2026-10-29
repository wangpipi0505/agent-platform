# EX-15b PhaseA：RED 采集器、实际 RED 与最小生产修复复核

日期：2026-10-29。审阅者：`artifact_boundary_review`。

结论：接受 `phase-a-red-20261029-01` 为真实的三边界业务 RED：30 项完整身份，27P/3F/0E/0S，原 25 项和新增两个控制项均通过，三个失败均为指定的 `PlatformException` 未抛出。原生测试退出码为 1，采集器退出码为 0 仅表示预定 RED 门通过。当前两生产文件的 31 行增量贴合这三个缺口，静审无本子步阻断；可另行冻结 GREEN 采集器后运行同 30 项。本文没有运行 GREEN，也不判定 EX-15b 全卡完成。

独立性说明：本审阅者不是本轮采集器、实际运行或当前两生产文件修复的作者；此前参与过 PhaseA v2 候选中绝对路径夹具的修订，因此本文不冒称全部测试均由完全未参与作者的人员审阅。测试候选另有 `process-files-contract-review.md` 的非作者审查，SHA-256 为 `F04CD34F4FF80B8A34715221B3E4643DF9AACC5E037D417F660BE95E4DDB60E5`。本文独立读取当前源码、Git 差异、原始 XML、依赖 JAR 和运行记录；仅新增本文，不改运行原件、产品、测试、入口文档或 index，不运行 Java/Maven/PG/HTTP/Node 或操作产品进程。

## 1. 固定对象与运行准入

运行 HEAD 为 `6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946`。本次检查时 HEAD 相同，index 文件数为 0。工作树并非仅有本卡变更，故准入依靠精确源清单，不以全工作树干净为前提。

| 对象 | SHA-256 |
| --- | --- |
| `phase-a/run-target.ps1`，与 run 内 `collector.ps1` 相同 | `7252B91377B863711EF1537574A1B9FE9D46D802C8ECB14E154E58BE013720EE` |
| `phase-a/manifest.json`，与 run 内 `approved-manifest.json` 相同 | `74C2A9E18FD31EE93A1042842A9FE65B76C994BCE8920C72ED98CEE4003B05C3` |
| `ProcessContractTest.java` | `94A0E0A7682BE4203CFC3A76A4F646FC74489D3E182268AFAF7D727F257A20CD` |
| `SkillProcessRegistrationTest.java` | `65ED5ACA7101A4C8198E0E26BB83609B90F4A2E3BD48F32B73C655D33FB5D705` |
| `ProcessFilesTest.java` | `6E4A4AAAB135BD01CA2B830A0E11A3741B060551A98EA2F0D9FA111ECF2A544C` |
| RED 时 `ProcessAdapter.java` | `5A0C87FD88EECE89C2B5A2F85C95B599C59124468830E5A500C734A935118E80` |
| RED 时及当前 `ArtifactStore.java` | `75F89B938A054BE6AC385C7152F49A9525D25E696BCE3634C42C7BD3201AE907` |

采集器只执行 `skill-registry` 内上述三个测试类，不执行 clean/install、`-am`、PG/MinIO 或业务 HTTP；`.env` 仅核 SHA，不导入。Maven 参数保留 `-Dmaven.test.failure.ignore=false`、原 1024 MiB Surefire JVM 堆和 900 秒预算，原生退出码先落盘，再解析 XML，不以采集器 0 替换 Maven 的 1。新 run 目录禁止覆盖，保存实际命令、脚本与 manifest、原始日志/XML及各自指纹，无自动修复或重跑。

152 个 skill 源文件及上下文 before/after 均纳入门；HEAD、JDK 21、Maven 3.9.16、POM、自有 `.tools/m2` common 主/test JAR 与 POM 等按固定值检查。原 Full701 的 152 源只允许三个获准测试文件差异，不能任意重学旧基线。采集器拒绝继承非空 `MAVEN_OPTS`、`JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS`。审阅时额外只读检查相关 Maven/JVM 注入变量为空，项目 `.mvn` 配置及用户 `.m2/settings.xml`、`toolchains.xml` 均不存在；这些额外现场事实不能扩大为脚本对所有未来环境的通用保证。Maven 自身可能进行正常依赖解析，本文不以“未调用业务 HTTP”声称网络抓包证明。

Windows PowerShell `5.1.26100.9711` 的 Parser 检查为 0 错误。本次独立 XML 检查同样在显式 PS5 内以 `XmlResolver=null` 读本地文件；没有运行采集器或测试。

## 2. 测试范围、旧断言与资源边界

对 HEAD 旧测试的完整 `@Test` 方法体逐块比对，除 CRLF/LF 的文本换行表示外，`ProcessContractTest` 原 17 个、`SkillProcessRegistrationTest` 原 6 个、`ProcessFilesTest` 原 2 个方法体均保留，合计 25 个。另审 helper 增量及实际旧 25P，未见降低断言或预算。新增方法恰为五个：

| 方法 | 预期 RED / 实际 RED | 实际覆盖界限 |
| --- | --- | --- |
| `rejectsNonBooleanFilesOptIn` | F / F | 预期 400/`PROCESS_CONTRACT_INVALID`；旧实现对首个非布尔值未拒绝，循环随该失败结束，不能声称 RED 已执行完其余四值 |
| `falseFilesOptInAndLegacyStdinRemainUnchanged` | P / P | 比较 false 和旧 stdin 内容，不等同业务 `arguments.files` 的真实信封保持验收 |
| `normalizedFilesOptInSurvivesSerializedContractReadback` | P / P | 实际 normalize → JSON 写读保持 boolean true；不冒充 PG 持久化或 JWT 验签 |
| `routedFilesOptInRejectsMissingOutputBeforeArtifactPersistence` | F / F | `output/missing.bin` 缺失，应在任何 artifact 交互前 502/`PROCESS_OUTPUT_INVALID` |
| `routedFilesOptInRejectsAbsoluteOutputBeforeArtifactPersistence` | F / F | 本次 `callDir/output/declared-only.bin` 已真实存在，bytes/SHA 等声明合法，只以绝对路径作为负例 |

两条路由测试通过真实 `SkillAdapters` → PROCESS adapter → 真实 Java 子进程。ArtifactStore 为 mock，最终 `verifyNoInteractions` 明确要求不进入存储；认证上下文由测试注入，不能称为真正入口 FilterChain 或验签测试。测试只使用自有 `@TempDir`、登记的子进程 PID/startInstant/父 PID/可执行路径和本次 callDir；绝对路径夹具也在身份核验后的本次 output 子目录创建，没有触碰不明文件。worker 仅返回描述符，不读声明目标。

原 60 秒测试上限、20 秒执行 timeout、15 秒 worker 身份等待、25 秒 future 等待及 10 秒线程池收敛保持。finally 清认证上下文、取消本次 requestId，并在 PID 与 startInstant 仍匹配时收敛自有进程树、取消 future、关闭 executor，检查活跃数/进程/目录回收。stdin 有 1 MiB + 1 的有界读取，worker 堆 64 MiB。本子步没有引入 PG 写、对象上传、全 tenant 清理或服务停机。

## 3. 当前 common 依赖来源与精确运行 classpath

Full701 运行实际使用的主 JAR 为 `1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87`，其历史记录保持。它在本子步作为 25 个旧方法的身份、状态及原源码基线，不能倒写为曾使用当前 common。

当前依赖来自已实际完成的 `v222-resource-green-20261029-01` 当前源码 clean 构建，receipt SHA 为 `F4D04843CB224F86F1CB5CE7E093E9BD23F8E02C9F3917A025F2355A63770025`，installed-after 清单 SHA 为 `11236879CBFB1DB4FF05B5FF4986A454AC55F3C9F1A04B5B8A36657B3784CCAE`。PhaseA 对新依赖显式重新冻结，旧未执行 manifest `E9F31683…61F874C` 与相同 7252 runner 已另存，未改任何旧 run。

本次用 .NET ZipArchive 逐项重算旧自有备份和当前 JAR 的 entry 名、长度、解压字节 SHA：主 JAR 126 项、test JAR 110 项全部相同且无重复 entry。旧 → 新原始 JAR SHA 分别为：

| 依赖 | 旧 SHA | 当前 SHA |
| --- | --- | --- |
| common 主 JAR | `1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87` | `0FBB83F6E6ADCF9E36BA27345602AA93AA4769D863CF2635FE0357EC7DA29A93` |
| common test JAR | `1C91223A6FC2A97B0B5EF43370440BFA9C4761EF4EAC280748DE470E6F30859E` | `0CA864F064A758ED9EA72BCD8EEEDE532C771F5F7BD51F0A2E9B42818E7AB732` |

该独立检查支持内容相同，不将原始 ZIP 字节不同隐去，也没有把任意后续 JAR 视为等价。

采集器原 `classpathPassed` 只用文件名包含检查，单独不足以证明精确路径。本次直接从三份 fresh XML 的 `java.class.path` 属性按分号拆分：每份恰一个 common 主 JAR，其绝对路径与本 AP `.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar` 完整相等，实文件 SHA 均为上述 `0FBB…A93`；common test JAR 条目均为 0。三份完整 CP 字符串 SHA 均为 `C4A9BC77B5786D2084E37124AFE408B048C6CB13371282804C7813FE33B04406`。因此本实际轮的精确依赖已另行证实；后续 GREEN 应继续保留精确路径核验，不能仅复用文件名包含布尔值。

## 4. 实际 RED 的原始证据复算

原件目录：`phase-a/runs/phase-a-red-20261029-01`。原生执行时段为 `2026-10-29T12:04:08.9151583Z` 至 `2026-10-29T12:04:24.8196539Z`。独立逐 XML 解析并对 suite 汇总字段、全部 testcase 身份、旧 25 + 新 5 状态、CSV 的异常类型/message/detail/首项目栈帧逐项比对，均相符；无缺项、额外项或重复身份。原 Full701 三份 XML 与 run 内 baseline 副本均逐 SHA 对上固定 pins。

| fresh XML | SHA-256 |
| --- | --- |
| `TEST-cn.agentplatform.skill.application.SkillProcessRegistrationTest.xml` | `8D1E442B6B46F96DD18D5FA7378FCA4A7A2B114DE9139A8CA22A67C7E2A92EE5` |
| `TEST-cn.agentplatform.skill.domain.ProcessContractTest.xml` | `5FA44F1F18BEB3E04F62849A7692DF962C1A256842DB01A5BC65B852CE7A1442` |
| `TEST-cn.agentplatform.skill.infra.ProcessFilesTest.xml` | `14F2F803FEFD9AAB9E31C9F88509FEB22FDBC85BD3E52362B7B88F0F6B226B0E` |

三条失败均为 `org.opentest4j.AssertionFailedError`，message 精确为预期 `PlatformException` 未抛出，SHA `8BF71AC60D238C445FED002B529C735D9F91229CB81AF43117A752CB48195664`；不是编译、依赖、环境、子进程身份或清理失败。detail 仅沿现有读器的 CRLF→LF 约定取 SHA，没有泛化替换：

| 方法 | detail SHA-256 |
| --- | --- |
| `rejectsNonBooleanFilesOptIn` | `A985851B0A7F05D3D8A03CFAFF818588B4A89C1680976B4814552BD93D12B900` |
| `routedFilesOptInRejectsMissingOutputBeforeArtifactPersistence` | `F72E810DB004C583C848187BE76EEB301CDAE491495D59DAEF7891F6E3062037` |
| `routedFilesOptInRejectsAbsoluteOutputBeforeArtifactPersistence` | `0B50F609FBC915A48BF83B513A8E227EE709D66627C20157C6DE4010EE7F29BB` |

首项目栈帧分别落在 `ProcessContractTest.assertInvalid` 和对应两条 `ProcessFilesTest` 方法。独立结果是 27P/3F/0E/0S；native 1/failureIgnore=false/collector 0 与此一致。两份 source 清单均为 152，逐 path/SHA 相同；context before/after 字节相同。

| run 原件 | SHA-256 |
| --- | --- |
| `summary.json` | `C77B84E8EA6F200BE27CFA5577462086AD170A100896BE7DCB2DE8601463E22F` |
| `source-before.json` / `source-after.json`（相同） | `F7045ADD519549EC390B1C2BE2545457FD6C43AC3967C484370142C5A16257F3` |
| `context-before.json` / `context-after.json`（相同） | `FEB345BB404BFB36CF0F64913B3940A75B81D9371D9AAF41F12AB784552416AB` |
| `native-exit.json` | `3ED32EF39C27A37071900DBB8B5658D1B2F08B6900E80A23503CC2C588C89E8A` |
| `collector-exit.json` | `96C73584FF298E1AF55597E5902971F33EA2EFDD8D68852DA28F406C3DC5EAA2` |
| `methods.csv` | `E18F278CEB9102EF63C962C9283D2A625E4A4380F54F362516ADF6B6CA033F3E` |
| `xml-manifest.json` | `5C22AE8E8B37823882201C95641531367451C3FCB18478715CE4C3FDDDA98C38` |

## 5. RED 后两生产文件的非作者静审

RED 完成后主线程才实施修复。当前 152 文件与 RED source-after 逐 SHA 比较，差异仅以下两个生产文件，三个测试指纹未改：

| 当前生产文件 | SHA-256 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/domain/ProcessContract.java` | `8D8030F53F24488C3880FEED71BBB6E7E3A42ED19FC416B63366505C41205B88` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java` | `00D30C03B2F63079908BA98AC89992DBD6304D8025E7B5DA756F5C9D20BD134C` |

该两文件方案就是本子步所需的最小修改范围，不需要动 SkillAdapters、ArtifactStore、POM、契约冻结前缀或数据库：

1. `ProcessContract.validStdin` 在原字节预算与 deepCopy 前，仅对 object 顶层存在的保留键 `files` 要求 boolean，错误沿既有 400/`PROCESS_CONTRACT_INVALID`。新增 `filesEnabled()` 基于已校验保存的 stdin 返回开关。false、缺省、旧 scalar/array/null stdin 与其它字段继续原处理。旧 object.stdin.files 非 boolean（含 null）现在有意拒绝，已在 `contracts/process-files-io.md` 明示，不能描述为完全兼容旧任意对象。
2. `ProcessAdapter.success` 已有本次 `callDir`，在返回成功且 finally 回收前，仅 `filesEnabled()` 为 true 时检查每个声明路径：非空文本、非绝对/UNC/盘符、无空段/`.`/`..`、首段精确 `output` 且至少再有一段，并且本次路径为 `NOFOLLOW_LINKS` 普通文件。`InvalidPathException` 沿既有 502/`PROCESS_OUTPUT_INVALID`。原所有分支的完整父目录段拒绝仍保留，原未启用的宽松描述符处理不被顺带收紧。

diff 为 31 行新增；原 result/schema 校验、退出码、requestId/protocol、进程取消与回收、并发额度、输出/时间预算均没有修改。missing 和 absolute 两例分别在新存在门和绝对路径门命中，契约非布尔例在构造解析时命中；这是静态推断，仍须实际 GREEN 验证。

这里的 `NOFOLLOW_LINKS` 只约束末项，不证明父目录没有 symlink/junction/reparse，也不能排除 stat 后替换、后代继续写入或提供稳定快照。该门当前不打开输出内容、不调用存储，且原 `SkillAdapters.executeLocalProcess` 仍返回空 fileRefs；因此这些限制不阻断已明确分离的三边界子步，却是后续启用真实文件 I/O 前必须解决的事项，不能把本修复当成完整的 output 安全校验。

## 6. 本次接受与后续门

接受本轮真实 RED 和上述两文件静态修复范围。下一步保持三测试及原预算不变，以重新冻结的两生产 SHA 跑同 30 项，须实际 30P/0F/0E/0S、原生 0、完整身份、精确当前 classpath 和 source/context 稳定；之后按已登记 Adjacent88 与 Full706 范围取得回归，继续保留原 Full701 历史失败、原生语义及相对门，本文不代替这些运行。

完整 PROCESS 文件链仍包括真实输入 schema/guard 优先、false/缺省的业务 files 保持、owner/READY/过期隔离、输入物化与额度、输出集合校验及稳定句柄/快照、流式持久化与精确补偿、二进制回放归属、新 JVM 读回。三条 RED 转绿与两条 mock 存储零交互测试均不能替代这些事实；EX-15b 继续进行中。
