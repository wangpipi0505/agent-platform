# EX-27 V222 原字节恢复范围独立复核

2026-10-29（Asia/Shanghai），审阅者 /root/ex04_pg_scope。本轮只读文件、ZIP、Git 与本地证据，并仅新增本报告；未参与本轮 SQL 或测试编写，未运行 Java、Maven、PG、HTTP，未修改产品、测试、三主入口、旧证据或 index，未使用 superpower。审阅时 AP HEAD 为 6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946；并行准备过程不是整仓冻结窗口。

**范围结论：接受仅恢复已执行历史迁移原字节的兼容方案，可继续准备采集器。此次并未签收实红、恢复、clean、历史 validate 或新库 migrate。首次 clean 前必须通过一座准确登记自有历史库的只读门；红测通过判定后才可复制 SQL。** 三主入口已经同步这一前置次序及“未使用的历史兼容列（不写入、不清理既有值）”口径，无须扩大产品白名单。

## 1. 原件、保护副本和版本范围

独立解析两份 ZIP 的中央目录、实际 entry 与解压字节，而非仅转述 archive-proof；同时实读 target、23 个源 SQL 及保护副本。

| 原件 | 整体 SHA-256 | 核查结果 |
| --- | --- | --- |
| engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar | 76AB67C3A72CD1931E2DC64E053CAE8A11A86A850CFA9DA5EB95FEE1132B419C | 51930276 字节；24 个迁移、24 个不同版本；V222 entry 恰1；共有23源逐字节同 SHA |
| engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar.original | 0D5FD7D90E4E289120B24404AFA659DA58961D73DEEAA9D7FB4F46E20A2D911F | 547180 字节；24 个迁移、24 个不同版本；V222 entry 恰1；共有23源逐字节同 SHA |
| engine-runtime/target/classes/db/migration/V222__run_context_pack.sql | 419C753C652B326DBD59DE40409F080A16F26B98787A7F8CF870B6ECF2859A90 | 286 字节；UTF-8 无 BOM、无 CR、末尾 LF；与两个 ZIP entry 字节一致 |

正式 JAR 的精确 entry 为 BOOT-INF/classes/db/migration/V222__run_context_pack.sql；原始 JAR 为 db/migration/V222__run_context_pack.sql。按 UTF-8 逐行去换行的 Flyway CRC32 算法独立计算为 **701700386**，不是本轮新读数据库的结果。

当前源迁移23个，版本为 V200–V221、V223；target 与两 archive 各24个，唯一多出 V222。target 的共有23个也逐项同 SHA，无重号。Git 跟踪的 */src/main/resources/db/migration/V222__*.sql 为零，待恢复路径仍不存在，不能覆盖已有文件。V221 的 action_plan_execute_key 和 V223 的 engine_quota_window 保持原样。

.local/ex27-v222-recovery/v222-source-20261029-01/ 中 engine-before.jar、engine-before.jar.original、V222__run_context_pack.sql 与上述三原件分别完全一致；archive-proof.json SHA=384F4A1A51D71B0AC315B64CE680472DA2BBD1B6F1FD8C1E265E1850BCDB6D86，所列24/23/286事实均独立重算成立。该目录被本仓 .gitignore 忽略，位于计划三个 target 之外；实际 clean 的最终路径与 reparse 检查仍须运行前完成。本报告不把旧 JAR 的 Java 字节认定为当前 Java 源构建所得。

## 2. 历史设计与列边界

候选报告 §3 对历史 README 的描述不够完整：docs/evidence/2026-10-27-PLAT-13-contextpack/README.md:101 明确记载“卡片原写 V222、后改 V221；复核后整条迁移删除”。其第80、92、99行也说明当时有意只写 snapshot、没有独立列写入者、不新增迁移。不能把本次称为修复误删、找回作者原提交，或补齐设计要求的审计功能。该历史记录仍未给出今天286字节 V222 的 SHA/完整文件身份，因此也不能据此断言今天这个构建资源具体如何进入历史库。

恢复理由必须限定为：已应用迁移 history 与现有正式包均包含这个精确资源，clean 后当前源包需要保持兼容。只有 fresh 历史库只读事实确认后，这个兼容理由才可进入恢复执行，不能只凭三个文件副本决定恢复已不需要的 DDL。

原 SQL 仅 ALTER TABLE engine.run ADD COLUMN IF NOT EXISTS context_pack jsonb；无默认、无索引、无回填，仍可能取得 DDL 锁。IF NOT EXISTS 不验证现有列类型，必须独立读元数据核 jsonb/nullable/no default；历史库仅 validate，不重放 ALTER。

实读全部七模块 src 的 context_pack 命中，生产仅 RunService.java:1477 的说明；RunService.java:1476–1483 仍把正文放 config.ontologyContext、审计放 snapshot.contextPack。RunAuthorityStore.java:64 的 run INSERT 使用 snapshot，不写该列。**静态无写入者不证明历史数据全 NULL**；本步不扫描业务值、不清理既有值、不补写审计、不改 RunService/API/契约。主线程已将三入口“历史空列”改为准确的历史兼容列口径；候选旧报告作为先前观察保留，以本复核补足边界。

## 3. 准确白名单和次序

产品只准新增 engine-runtime/src/main/resources/db/migration/V222__run_context_pack.sql，且必须从已固定二进制原件复制完整286字节，不能从本报告、Markdown或终端文字重写。测试只准 EngineMigrationsTest.java 新增一个方法；其他23迁移、Java生产源、POM、application.yml、契约和迁移 helper 不改。

三 POM 实读表明 engine-runtime 的本仓依赖只有 admin-common 主 JAR 与 test-JAR；admin-common/pom.xml 的 finite-test-fixtures 在 process-test-classes 生成 test-JAR。父 POM/admin-common/engine-runtime 是当前最小构建闭包。三个 POM 未声明自定义 build.directory/clean filesets；这不替代 Maven 对三个项目逐一实际解析 build.directory 与 no-reparse 门。不得全 reactor clean、不得触其他模块 target、不得使用未知旧 engine JAR 注入测试 classpath。

应依次完成：

1. 保护三原件与 static review；OR 独占窗结束后，冻结准确一座自有历史库、PG25432 PID/exe/data directory、连接身份、DB/schema/history owner 与环境来源。仅读目录、history 和列元数据，不创建、迁移、清理或扫描业务行。
2. 历史门核 **24条成功的 versioned SQL（V200–V223）**，逐项 script、version、type=SQL、checksum；V222 恰1、checksum=701700386。合法原 SCHEMA rank0 记录单列保存，不能混入24，也不能默认允许任意非SQL记录。23个旧源 CRC 与精确原件 CRC 核对，不只看最大版本。列应 jsonb/可空/无默认。任一不符即停，不 repair、删 history、补列或放宽期望。
3. 仅三个实际 build.directory 安全准入后，从当前源 clean 构建；admin-common 主/test JAR 均须当前源产出。V222 源保持缺失，定向4例应精确为旧3P、新1F V222_RESOURCE_REQUIRED，0E/0S；编译、依赖或环境失败不能冒作实红。保存 native、fresh XML、source/classpath/依赖指纹。
4. 真实红经审阅后复制原字节，再按同一构建与测试命令 clean GREEN。新4例全P，src/target/新正式包24份迁移逐名逐SHA一致、无重复 entry；不能用旧 target 残留通过，不能要求新整体 JAR 与旧整体 JAR 哈希相等。
5. 跑相邻24及原932+新增1的模块相对回归，独立核原因与范围，然后精确 staged blob 仍为286字节/SHA419C…；沿根规则 text=auto/eol=lf，无需扩大 .gitattributes。
6. 用新包来源对准入历史库 validate，前后完整 history 不变；另在 EX-06 登记的唯一新空库由同一新包正常 migrate 到24个SQL版本，再 validate/重复正常启动，不出现第二条 V222。两项未齐只能记资源打包子阶段完成，EX-27、EX-06、三应用/All/J1–J9/S1 均不得据此关闭。

旧 EX04 legacy-runtime-proof.json 记录 agent_platform_engine_test 的 V222 成功历史与701700386，可作识别起点，不能代替本轮 fresh 准入，也不能默认把全部14个旧测试库纳入本次前置只读范围。

## 4. 新增测试候选与基线

审阅过程中，另一作者按 root 白名单在 EngineMigrationsTest.java:46 新增 preservesExecutedV222MigrationResourceBytes，当前 SHA=66EF434AB8ABB818599C69C9E0BF1D80C02087CFB7CD05CFD8AB577F2FF6109F。Git diff 恰+15行：原3方法及 import/断言未改，EngineMigrations helper 未改。新增通过现有 ClassLoader API 先 assertNotNull(resource, V222_RESOURCE_REQUIRED)，然后286字节、原 SHA、versions contains222，try-with-resources 关闭流。不会通过访问不存在产品API制造编译红；也没有 Spring/PG连接。

该测试仍是静态候选，未实跑。旧3例对 max/count 为动态约束，缺222仍有223时不会发现缺口；新增专门固定已执行历史资源合理。getResourceAsStream 只能读取首先命中的资源，helper 也会去重；因此 collector 必须证明没有旧 engine archive/target 混入 classpath，并另核打包资源唯一性，不能把单测通过当作所有 classpath 根无重号的证明。

原相邻组为 EngineMigrationsTest3、domain.ContextPackTextTest1、infra.OntologyContextPacksTest4、application.RunServiceContextPackTest9、infra.OntologyBindingResolutionTest6，共23；新增1后24。后四类是快照/渲染/受控 loopback 与绑定行为，不能代验真实 PG迁移。

EX04 full-engine-20261029-1124 原始 summary 为93XML、932记录=817P/35F/80E/0S，930具名+2类级事件，14源方法未具名执行；native0使用 failure.ignore，不能写成全绿。final-review.md:35、45记录了方法及有限表示差异的接受，原严格文本门仍为false，不能抹掉这个事实。新增1后目标933；保留原932身份/状态/原因及2事件/14未具名边界，新方法必须P，不用总数或泛化原因替换冒充回归。

Git 只读比较72ee3428581ff2e25359c9b312a5754de1ac97bc..当前HEAD 的 engine-runtime/src、admin-common/src 没有已提交差异；当前测试新增1是工作树准备差异。原候选报告所列 BCAB… 是新增前旧3例版本，本报告没有把那一指纹冒作当前测试。后续 runner/manifest 仍须冻结实际整套源、工具、依赖、环境与 HEAD，而不是采纳任意当前树。

## 5. 三入口与审阅输入快照

本次最终实读三入口的追加块全文相同，统一 LF 文本 SHA=CF8807458685DE682C0AD860724F1646C24E27E1F370D12BD4C671E524B0741A；均已经在首次clean前加入准确历史只读门，并保留未实红/恢复和阶段验收边界。

| 主入口 | 追加起始行 | 文件 SHA-256 |
| --- | ---: | --- |
| 优化台账-统一-20261028.md | 798 | 76921BC595CBF64AE8BB1025F1095CB285E1B2522DFB8FBF49FE3EEBE1E4176B |
| 执行卡片-统一补差-20261028.md | 471 | 731695F13A945B0724BC5DC4828A084194E87072D26EEDDC3A594639F2D6EF2D |
| 联合验收与回归执行手册-20261028.md | 651 | FC4FBA87ECDCBF83E6CED87BF4B74F32530E97581D3A15B27C25B995F6AC081D |

关键只读输入 SHA：

| 路径 | SHA-256 |
| --- | --- |
| docs/evidence/2026-10-28-EX-27/v222-source-recovery-candidate.md | 1F6131E77DFB234CB87BAF5866A961B6FC8949EC03EA97455A549378D0012C7D |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineMigrations.java | 5B682B496CC084FCA57B8CD5DBEFB6B70B2E86E09899C0B884A833D11A42C688 |
| engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java | C6AA628A0AE436627B2F202F10A0278DC9D3069141582658CADBF98219607716 |
| docs/evidence/2026-10-27-PLAT-13-contextpack/README.md | BA5AE036C4AE3805397BAA42E2D8F1BF794921EEB8C1EA83CB2004359AE9EA64 |
| pom.xml | 836BA70369072496695E167BF3AB937FE48C7150A5E0B13B7B8D13674FFBCB2C |
| admin-common/pom.xml | B5BA5FD5F094DF545C6E18AEEA87166323BF22F7E1FE2E98111CEE5559F8E4B0 |
| engine-runtime/pom.xml | A31CF052CCC6F9E296E4EBE946C337761CFC2823F71EA2AA536A0D241D41B601 |

已核范围可供 root 准备准确的 v222-source-recovery/run-resource-gate.ps1 与 manifest.json；本报告不签收尚未读取的 collector 代码或任何实际 runtime 结果。没有代替外部验收，没有引入新框架、第二套数据库流程或业务修改。
