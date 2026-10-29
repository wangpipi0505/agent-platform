# EX-27 V222 实际 RED 与原字节恢复独立复核

审阅者：`docs_reader_check`；2026-10-29。只读核验 AP 本仓源、Git、JSON/XML/ZIP、命令/native/资源原件；新增仅本报告。未运行 Java、Maven、Node、PG、HTTP 或服务，未改产品、测试、collector/manifest、原 run、HEAD 或 index；未读取/输出凭据值。OR EX-19 旧报告未动。

## 结论与边界

**实际资源缺失 RED 成立，随后恢复准确归档原 SQL 的资格满足，未见阻断。** `v222-resource-red-20261029-01` 为 clean 构建 native0、定向测试 native1、collector0；4 个精确方法为原3P、新资源缺失1F，0E/0S。失败到达真实 ClassLoader 资源为 null 的原断言，没有使用编译错误、缺 API 或虚假 stub 充当红。

root 随后恢复的唯一源码为 `engine-runtime/src/main/resources/db/migration/V222__run_context_pack.sql`，与保护归档及两份旧包中的唯一 entry 逐字相同：**286 字节、SHA419C、Flyway signed CRC32=701700386**。RED 的其余305源、Test66EF、helper和旧23迁移均未变。本结论只接受资源恢复子阶段；**未签收 Green、历史库 Flyway validate、新独占空库 migrate、相邻/模块回归或 EX-27/EX-06 整卡完成**。

## 三份历史证据不能混用

| 原件 | SHA-256 | 实际范围与结论 |
| --- | --- | --- |
| fresh `history-preflight-engine-test.json` | `527e40116db52b10204b99f5062888c7bc0c29dc8f12a53ca6f45d2b5d5a8425` | 新的精确 engine_test-only 只读授权；target=`agent_platform_engine_test`、passed=true，24成功SQL+1原SCHEMA，总25。 |
| 旧 EX04 `full-pg-admission.json` | `bbadcb36e055c15f8cf053a2177bcbb6639314fe88c7df7047ce72d290f3376e` | 旧历史来源与目标定位依据；包含多库记录，本次只静态比较其中 engine_test/engine 一项，不据此访问其它库。 |
| dev `history-preflight.json` | `4ad65d712e5017ee540f258d1bd5153b387052c43688b310ebe97fd50c917533` | target=`agent_platform_dev_v12`，21SQL+1SCHEMA，总22；passed=false、`EXACT_24_SQL_HISTORY_REQUIRED`，未查列。原失败不倒写，不把该库迁移到24来凑前提。 |

三者均在本仓既定位置。fresh527E 的 engine_test 24 SQL与唯一SCHEMA，按原 rank 顺序逐字段 **完全等于** 旧 BBAD 该库 history；不是只比较最大版本或 V222 一行。版本集合 V200–V223，V222 rank22 / script=`V222__run_context_pack.sql` / checksum701700386 / installedBy=`agent_v12` / success=true。原 V221 在 V223 之后的历史 rank 顺序保留，没有重新排序成版本顺序来掩盖差异。

fresh527E 另有精确 owner/session/column 事实：PG25432/PID15204/原创建时间与 `V:/.local/pgdata`；库、schema、history owner及role均 `agent_v12`，otherSessions0；`engine.run.context_pack` 为 pg_catalog/jsonb、nullable YES、default null。该证明3条只读元数据SELECT均native0；dev原件仅2条SELECT/native0，失败时 column=null，不能从中推断列不存在或类型。

fresh527E 明确区分 credentialEnvironmentDatabase=`agent_platform_dev_v12` 与实际授权 target engine_test；run collector 的 psql 连接库为固定 engine_test，不按环境 URL 动态选库。所有24份固定 catalog 的本地 SQL SHA与逐行 UTF-8 Flyway CRC32 经本审阅独立重算，逐 script/version/checksum 与 fresh history 相符；唯一SCHEMA rank0/null version+checksum/`"engine"` 与24SQL分开。没有接触业务值或重新查询数据库。

## 实际 RED：断言、classpath 与 clean 包

实际窗口为 root 的 PS5 执行，私密原件目录 `.local/ex27-v222-recovery/v222-resource-red-20261029-01`：

| 原件 | SHA-256 |
| --- | --- |
| 实际 collector E8，副本与 `run-resource-gate-v2.ps1` 相同 | `e8eadc50369836ee264e0875803396dcb600133da0ead1f377b7169d3181cd0e` |
| 实际 approved manifest E315，副本与 `manifest-v2.json` 相同 | `e315200b67871a97b0bb5fa64f87de7f4a3903b969b0a888ff225ac23218d1b6` |
| `summary.json` | `eb33af3095a6f14c658d9e0bb01ea28eae62ff833746827a3e2994f2195fc756` |
| `methods.xml` | `db953e10f74d9deb6780568f438c6bb33d6f58e60f7aec87023843c73c2ff4c8` |
| `methods.json` | `5aca414c5202b1d7400353caeb0dd26dd293c492f3b175cc31d2b01efe800fd5` |
| `build-native.json` / build0 | `93fbefd77f047779d55c133b31ba001f353ed9f0598a2d34d5dd36ba186114e5` |
| `test-native.json` / test1 | `6569e81539092a39ad8c0b291721a3d1bd47b4d2ef4c9a2221a2bf9719c2d588` |
| `collector-exit.json` / collector0 | `eeac0814f0437f61efdfcbe9bc0471585885c1e55b45e629309912a3aebdbeaf` |

逐 XML testcase 的唯一失败为 `cn.agentplatform.engine.infra.EngineMigrationsTest#preservesExecutedV222MigrationResourceBytes`，type=`org.opentest4j.AssertionFailedError`，message精确 `V222_RESOURCE_REQUIRED ==> expected: not <null>`，自身栈落 `EngineMigrationsTest.java:50`。因此红停在资源存在断言，尚未运行286字节/SHA/contains222的后续断言。其余 `versionsComeFromClasspathInOrderWithoutDuplicate`、`countIsTheCensusOfVersionsInTheInclusiveRange`、`everyVersionIsExactlyWhatTheClasspathFileNameSays` 均 PASS；suite恰4/1F/0E/0S，与methods.json及批准方法集合一致。

原 test 源 SHA `66ef434ab8abb818599c69c9e0bf1d80c02087cfb7cd05cfd8ab577f2ff6109f`，仅新增15行。移除该新方法及其插入空行后，原3方法完整原字节重构 SHA **`bcab2dcc8287a9e73a59e87e8f0af59924a94cb6ca0672c8afe814f9882a5b65`**，并与 HEAD 原文件经既有换行过滤后一致；没有放宽任何旧断言。`EngineMigrations` helper 仍为 `5b682b496cc084fca57b8cd5dbefb6b70b2e86e09899c0b884a833d11a42c688`。

XML 实际 `java.class.path` 的 engine 根恰为本轮 `engine-runtime/target/test-classes` 与 `target/classes`；没有旧 engine JAR/archive 注入。Java21.0.12、`maven.test.failure.ignore=false`；实际 test-command 只运行 EngineMigrationsTest，缺类也不能忽略。build-command 是 `-pl engine-runtime -am clean install -DskipTests`，另有独立 test；原 build.raw 中恰三个 reactor success 总结和 BUILD SUCCESS。构建成功后才出现该断言红，不是依赖/编译/PG前置失败。

红时 before/after built-resource 文件字节相同（SHA `9a52538edad637a13b4fb902e0e367dd079175be210e7c0b60366ebfd52c72f3`）。在 Green 尚未运行、target仍为红产物时，本审阅实读其 JAR，整体 SHA **`ef26e32520bf731822c72e3815924dc2a5f92488c5f58d921c7cade49f09d564`** 恰等红记录；独立 ZIP 中央目录/entry核得23份无重复迁移，**没有V222**，逐entry SHA等于红资源清单的23个当前源。没有用恢复后的源假定红包内容，或只信summary。

## 清理、原件与历史未变

三个实际 Maven `project.build.directory` 均经各自 POM 的 help-plugin/output-file 解析，原输出文件是严格 UTF-8、一个绝对路径、无换行/NUL/U+FFFD；本审阅逐字核得仅本 AP `target`、`admin-common/target`、`engine-runtime/target`，各resolve native0。resolved-targets保留独立POM/no-reparse门。三POM无自定义build.directory或clean filesets；collector还检查目标递归reparse/越界，未准入全reactor或其它模块target。

清理前受保护三原件均在 `.local/ex27-v222-recovery/v222-source-20261029-01`，位于三个target之外：

| 保护原件 | SHA-256 | 独立 ZIP/字节核验 |
| --- | --- | --- |
| `engine-before.jar` | `76ab67c3a72cd1931e2dc64e053cae8a11a86a850cfa9da5eb95fee1132b419c` | 24个SQL，V222唯一，BOOT-INF/classes entry；全部当前24源逐字相同。 |
| `engine-before.jar.original` | `0d5fd7d90e4e289120b24404afa659da58961d73deeaa9d7fb4f46e20a2d911f` | 24个SQL，V222唯一，普通db/migration entry；全部当前24源逐字相同。 |
| `V222__run_context_pack.sql` | `419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90` | 286字节，UTF-8无BOM/LF/末尾LF，与两entry及恢复源逐字相同。 |

run下 `installed-common-before` 的旧 admin-common 主JAR、test-JAR、POM均独立匹配 manifest.commonBefore 的原SHA；未因当前源重建改变依赖而丢失前态。新 parent/common/engine 六份 installed artifact 的 before-test/after-test inventory 字节相同，SHA `2e03debd043e7a5acf3b70b87b0996b92cac20067ac8d8ba60e8322b8a816207`；本次审阅时当前六份文件也匹配。新旧common可以因真实构建而不同，不能强行要求新依赖等于旧包或自动还原。

RED source-before/source-after逐字相同，SHA `1b0efb918689c50b7ab35b39f61d0fad70fc9c2e2484fa0a9c1742ae5331bd16`，完整305路径与批准源集合相同且当时不含V222源。before-clean/after-test history字节相同，SHA `5565b3e58007f2502a28477ecc7dddd764888e413328cad7507d5c74bccdf49f`，字段逐项等于527E的原 facts/SQL/SCHEMA/column/PG身份；不是只比较行数。service before-resolve/before-clean/after-test字节相同（SHA `fb7133f520b1a2e0800c4a013a4a77f195170ca550f8581f7d5bd39ba362c5a8`），既有登记PID均不存在、本仓正式JAR匹配0、不枚举其它工程、不启停进程。原RED记录contextStable=true；本审阅额外重hash冻结collector/工具/config等pins仍相符，仅读.env哈希而非其值。恢复SQL是下节明确登记的后续源集合变化，不把当前306源冒作原RED的305源或重跑旧RED准入通过。

## RED 后准确原字节恢复

`docs/evidence/2026-10-28-EX-27/v222-source-recovery/sql-restore-after-red.json` SHA **`2f2eaed734ba036618a243d422b0775feda88c7315c489b37e79601b99a5060f`** 精确绑定本次redRun与DB953 XML，记录从受保护独立SQL复制到唯一src路径、ddlExecuted=false/otherProductCodeChanged=false/testSourceUnchanged=true/greenExecuted=false。

本审阅独立重读恢复src、归档SQL及两个原JAR的V222字节，全部等同286/SHA419C；按UTF-8去BOM、逐行无换行CRC32独立计算 **701700386**。逐一重hash RED原305源，全部原SHA仍相同；当前父POM+admin-common/src+engine-runtime/src完整文件集合，较RED恰多这一个SQL，合计306，没有其它新增源或修改源。Test66EF和helper原SHA保持。

恢复的精确DDL为既有 `engine.run` 增加 `context_pack jsonb` 的历史兼容资源；本次只恢复同名同checksum原字节，不改成新编号、不改SQL注释/语义、不增加RunService写入、回填或清理历史值。PLAT-13曾有意只写snapshot、删除独立列迁移的历史说明仍保留，不能称此次是“误删纠正”或新增审计功能。当前历史库已有该列，与未来新空库首次正常migrate的DDL影响必须分开。

本次复核满足“实红后仅复制已保护原SQL”的前提。后续必须以另行批准的Green collector保留相同Test66EF断言与精确clean/资源边界，真实取得4P及src/target/新包24份一致；再另核历史validate/独占新库migrate和相邻/模块相对回归。此处未提前记这些阶段通过，亦未改原RED为Green。
