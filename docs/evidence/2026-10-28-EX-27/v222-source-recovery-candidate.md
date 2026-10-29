# EX-27：V222 自有构建资源恢复候选

2026-10-29，只读核查。目标是解除 EX-06 当前源码打包的迁移缺口；本次仅新增本报告，未写产品 SQL、测试、启动器、主入口或 index，未运行 Java/Maven/PG/HTTP，未读取相邻旧工程。开始核查时 AP HEAD 为 `acad6bdee223b23f331420c6de50045cb5fa8941`；封版前主线程推进为 `73a4ada6de5ed5ee34f623a8a435c949040a2e7d`，两提交间 engine-runtime/contracts 路径无差异，本文末尾所列源与 target V222 指纹复核未变。本次不声称整仓运行上下文冻结。

## 1. 技术判定

**可有条件采用“从本仓既有构建归档恢复已执行迁移原字节”的最小方案。** 已有三份本仓资源互相印证：target/classes、正式 engine JAR、同目录 `.jar.original` 的 V222 完全相同；当前其余 23 个源迁移与这三份构建资源也逐文件同 SHA。恢复的唯一目标是让 clean 后的新包继续包含历史已应用的同名同 checksum 迁移，不是新增审计能力、设计新 SQL 或 repair 数据库。

目前不应直接实施：EX-27 当前主卡主要为 Test-only 基座工作，须由主线程先登记本报告第 5 节的准确恢复白名单，并经独立复核接受下述“历史空列”的边界。EX-06 的既有禁令仍有效；不得在 EX-06 脚本内偷偷复制 SQL 或放宽迁移清单。

**不能证明的事项**：没有找到或声称找到 V222 原作者提交；没有确认源文件为何缺失；没有把构建 JAR 中的 Java 字节认定为当前 Java 源所构建；没有运行 clean、Flyway validate/migrate 或业务回归。相同 SQL 字节仅证明该资源一致，不证明整个旧 JAR 来源与当前源码一致。

## 2. 原件与逐字节核查

先读 `docs/evidence/2026-10-29-EX-06/ap-stage-execution-candidate.md` §4 和 `ap-stage-plan-review.md` §5，沿其已知路径核查，没有进行全仓历史提交搜索。

| 本仓原件 | 原件整体 SHA-256 | V222 entry |
| --- | --- | --- |
| `engine-runtime/target/classes/db/migration/V222__run_context_pack.sql` | `419C753C652B326DBD59DE40409F080A16F26B98787A7F8CF870B6ECF2859A90` | 直接文件 |
| `engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar` | `76AB67C3A72CD1931E2DC64E053CAE8A11A86A850CFA9DA5EB95FEE1132B419C` | `BOOT-INF/classes/db/migration/V222__run_context_pack.sql`，恰好 1 个 |
| `engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar.original` | `0D5FD7D90E4E289120B24404AFA659DA58961D73DEEAA9D7FB4F46E20A2D911F` | `db/migration/V222__run_context_pack.sql`，恰好 1 个 |

三份 V222 均为 **286 字节、UTF-8 无 BOM、LF、末尾 LF**，SHA 同首行；除 SHA 外还对 entry 与 target 做过 byte-array equality，结果相同。用 UTF-8 去 BOM、逐行不含换行的 Flyway CRC32 规则静态计算得到有符号值 **701700386**，与 EX-06 登记的历史期望一致。这不是本次读数据库所得值；运行前仍必须核实际 history。

源码迁移目录有 23 文件：V200–V221、V223；target/classes 与上述两个含 V222 的 JAR 各有 24 文件。当前源与 target、正式 JAR、`.jar.original` 的共有 23 文件逐个 SHA 均相同；唯一多出的资源是 V222。源码版本号无重复；当前全部受 Git 跟踪的 `*/src/main/resources/db/migration/V222__*.sql` 查找结果为空。V221 是 `action_plan_execute_key`，V223 是 `engine_quota_window`，不能复用这些号。

同目录较早命名的 `engine-runtime-0.1.0-SNAPSHOT.jar.current-source-20261023`，整体 SHA `6635EB16E8265FA960DC6A91BF24BB55480BB1F0909A254F5522799FDCF28EAD`，只有 20 个迁移、没有 V222，也缺当前 V220/V221/V223。因此它不能作为 V222 恢复来源，文件名中的“current-source”也不能替代内容证明。

Git 属性实读该待恢复路径 `text=auto, eol=lf`；虽然本机 `core.autocrlf=true`，现有 LF 属性已覆盖，无需为本步修改 `.gitattributes`。恢复前后及精确 staged blob 仍应验证同一 286 字节/SHA，不能仅凭属性推定。

## 3. 精确 SQL 语义与设计边界

原件只有以下内容（展示不作为恢复数据源；必须复制原字节）：

```sql
-- PLAT-13：运行冻结时记录本体上下文包的审计信息（space/releaseId/sha256/fetchedAt/truncated）。
-- 只存审计字段，不存包正文；正文只出现在冻结后的 config.ontologyContext。
alter table engine.run add column if not exists context_pack jsonb;
```

这条 DDL 在既有 `engine.run` 增加一个可空、无默认值、无索引、无回填的 JSONB 列；不改 snapshot、租户键、权限、约束或其他表。对正确的既有 JSONB 列不会再次添加。`IF NOT EXISTS` **不验证同名列类型**，若某库已有其他类型的 `context_pack`，不能因迁移 success 就接受，需独立核列属性。ALTER TABLE 仍有锁需求，不能把迁移称作完全无影响；实际历史库本次应只 validate，不手工重放 DDL。

冻结 v1.2 正文仅把本体上下文包列为预留接口（snapshot 第 2906 行），没有要求这个独立列。后续已登记优化方案 `平台侧优化方案与实施细则-20261027.md` §4.13 要求按已发布 release 拉取、冻结与注入，并把审计写入 run 快照；`平台侧优化-开发计划与阶段编排-20261027.md` §5.4 将 V222 预留给 contextPack，符合用途且不与 V221 冲突。

当前实际代码：

- `RunService.applyOntologyContext`（约 1420–1491 行）在冻结阶段唯一取包；正文写 `config.ontologyContext`，审计写 `snapshot.contextPack` 的 space/releaseId/sha256/fetchedAt/truncated，绑定解析另记来源。1477 行明确说明审计只落 snapshot、独立列写入不在当时卡片范围。
- 当前 `engine-runtime/src` 搜索 `context_pack` 只命中该注释，没有 INSERT/UPDATE 的列写入者。V200 已有 `snapshot jsonb NOT NULL`；既有注入审计依靠该快照持久化。
- 当前 `RunServiceContextPackTest.successfulPackIsFrozenAndAuditedBeforeDispatch` 断言的是上述冻结快照及后续 assemble 不再触网，不是数据库独立列。

还有必须保留的历史差异：`docs/evidence/2026-10-27-PLAT-13-contextpack/README.md` §2 明确说编排者曾删除“原 V221”审计列，理由是没有写入者、已有 snapshot；§5 也写该卡不产生迁移。它描述的是“原 V221”，没有给出今天 V222 的文件身份或 SHA，故不能据此认定 V222 有意删除或意外遗失。与此同时，它足以阻止把独立列恢复描述为“设计要求的审计功能终于实现”。

结论是：**SQL 与当前执行逻辑没有行为冲突，但独立列是未使用的兼容遗留结构。** 只有主审接受“保留已执行迁移历史与 clean 包的兼容性”这一目的，才恢复原字节；不改注释以凑当前描述，因为更改任何原字节会改变资源 SHA，评论文字也参与 Flyway checksum。恢复后在本卡证据解释空列，不借机新增 RunService 写入、回填、索引或 API 字段。

## 4. 先红、构建与真实迁移验证

### 4.1 保护原件与可重复 RED

当前静态观察已证实“src 缺、包有”，但本报告没有执行断言程序，不能记成真实 Maven RED。建议在原 `EngineMigrationsTest` **只追加一个**默认测试，要求 classpath 中这个已执行历史资源存在、完整原字节 SHA 为登记值，并含版本 222；现有 3 方法及断言不改。该历史资源不变量与 max/count 动态测试互补：现有测试的 max 只要求至少 220，缺 V222 而仍有 V223 时会通过，不能承担缺源检测。

RED 前主线程必须先把正式 JAR、`.jar.original` 和 target 的原 V222 复制到本仓忽略的、唯一登记的 `.local/ex27-v222-recovery/<RunId>/`，核完复制前后 SHA，再开放 clean。禁止把唯一恢复来源留在即将清理的 target 内。原 JAR 若被任何本仓服务使用，按登记 PID/exe/完整命令行/启动时间安全停机并保留恢复记录；不能停止未知 Java 进程。

在 **仍未恢复 src SQL** 的冻结源码上，先从当前 admin-common 源构建安装主 JAR 与 test-JAR，然后仅对批准的父 POM/admin-common/engine-runtime 构建闭包 clean。逐一验证实际 Maven build.directory 是本 AP 内的准确目标、无 reparse/外链/未知模块，不把当前保护 V222 的禁止 clean 直接全局撤销。

clean 后先保存资源清单：engine classpath 只能来自此轮输出，只有源中的 23 份 SQL，不能额外引入旧 engine JAR 或旧 target。运行原 `EngineMigrationsTest` 的 3 例加新 1 例，准确 RED 应是新增资源断言因 V222 缺失失败，原 3 例通过，0 ERROR/0 SKIP。若发生依赖编译、验签配置、Flyway连接等失败，不算本缺口的行为红。原生退出码、命令、fresh XML、源码与 classpath 指纹必须保留；`failure.ignore` 不能用于这个定向红绿门。

### 4.2 原字节恢复与 clean GREEN

RED 接受后，唯一源恢复位置是 `engine-runtime/src/main/resources/db/migration/V222__run_context_pack.sql`。从已固定的自有归档读取 entry bytes，用二进制复制写入，文件原来必须不存在；不要从 Markdown、终端文本或手工重写生成。写后要求 286 字节/SHA419C…/CRC701700386，且全部 23 个旧源文件原字节未变。

重新执行相同当前源 clean 构建，保留依赖安装与打包 native 结果，分别核 src、target/classes、新正式 JAR 24 份 SQL 的精确名称、版本与逐文件 SHA，新 V222 必须三个位置相同且无重复 entry。不能拿“非 clean package 恰好还有 target 遗留”当修复证据，也不能拿旧整体 JAR SHA 要求新包相等；新 Java 源对应的新包应记录新的完整 SHA。

同一新测试原断言必须绿色。当前 POM 下最小源码构建闭包是父 `agent-platform`、`admin-common`（含 test-JAR）、`engine-runtime`；版本为 `0.1.0-SNAPSHOT`。可沿本仓 `.tools/m2` 与既有 `-pl engine-runtime -am clean install -DskipTests` 形式，实际命令/参数/Java/Maven 指纹由 collector 冻结后执行；此处不授权运行。六服务 EX-06 打包是后续更大范围，不能借这个三项目准入一次 clean 全 reactor。

### 4.3 PG 必要门，不 repair

从旧证据得到 701700386 不等于当次连接了正确数据库。实际窗先核本仓 PG25432 的 PID/exe/data/owner、准确数据库/schema/history、其他 session 与原迁移逐项指纹；不可沿用 `EngineTestDatabase.url()` 的自动建固定库行为作为本次独占证明，也不能运行 `RunAuthorityDatabasePreflightTest` 来“顺便检查”，它会访问/创建另一固定库并迁移到 215。

兼容性需两个互补事实：

1. **已应用 V222 的准入历史库**：本次明确核 V222 script/checksum701700386/success、其余 history 原件以及 `context_pack` 的 jsonb/可空/无默认属性；使用新包来源执行 Flyway validate，不迁移、repair、clean、改 history 或重放 ALTER。保存前后 history 一致。若 history 或列不符，先报告，不能删除记录来配合源码。
2. **本次登记的新空库**：复用 EX-06 后续唯一 session DB 的正式初始化准入，以新源码包的正常 Flyway 迁移到 24 个版本，精确核 V200–V223、V222 checksum与 success、列类型/可空/无默认，再次 validate/正常重复启动不产生第二条 V222 或额外 DDL。只能操作该已登记空库，不把共享 agent_platform_v12 当空库重置。可与 EX-06 第一次启动共享这一真实证据，不必另造第二套库/迁移框架。

上述历史库 validate 和新库正常 migrate 可以由主线程分阶段串行完成；在两项尚未完成时，只能称资源恢复与打包门通过，不能称迁移兼容性完整验收。新库验证须使用新包，而不是为取得 24 条 history 又回用旧 JAR。

## 5. 最小白名单、回归与独审

实施前需要主入口明确登记的候选白名单：

- 产品资源仅 `engine-runtime/src/main/resources/db/migration/V222__run_context_pack.sql`，恢复上述既有原字节；不改 V200–V221/V223、POM、Java、application.yml、公开契约、其他服务迁移。
- 测试仅 `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineMigrationsTest.java`，建议追加 1 个已执行资源保留断言；不改动态枚举 helper、不增加跳过、不按数据库历史动态生成“期望值”。
- 本卡证据：归档来源与指纹、有限采集脚本/manifest、真实红绿 XML/native、构建来源清单、迁移读回及独立复核。运行档案留本仓忽略目录，不提交依赖、JAR 或数据库内容。若 collector 需要新文件，由主线程先固定其准确名字，不授权任意框架扩展。

静态独审至少核 SQL 完整原字节与两份归档 entry、无版本撞号、未使用列的历史解释、源白名单和 clean 目录、历史库/新库边界。恢复后的提交审查须单独核 staged blob 仍是原 286 字节及 SHA，并保留旧原件。回滚代码不能删除已应用 history 或 DROP COLUMN；如停止恢复方案，应保留兼容包与原归档，不让缺 V222 的新 clean 包连已有库。

定向回归优先复用现有 5 类：`EngineMigrationsTest` 原3、`ContextPackTextTest` 1、`OntologyContextPacksTest` 4、`RunServiceContextPackTest` 9、`OntologyBindingResolutionTest` 6，共原23方法；若按本候选新增1方法则共24，新增必须无失败/错误/跳过。后四类验证当前快照、注入、身份与绑定语义保持，不能当 PG 迁移证据。

engine 模块基线仍采用 `docs/evidence/2026-10-28-EX-04/collector/runs/full-engine-20261029-1124/summary.json`：93 XML、932记录＝817P/35F/80E/0S；930具名＋2原类级事件，14源方法未具名执行。已实读原件，不能回退旧907。若届时仅多本候选1方法，预期记录为933、原932身份/状态/原因保持且新增通过；这是比较目标，不是已取得结果。实际需按届时准确批准增量冻结清单，保存旧非通过原始原因，不能仅看总数或泛化替换迁移错误。若 clean 暴露其他资源/依赖缺口，应单独归因，不能扩大本次恢复 SQL。

EX-06 解阻还需要当前源六服务包的来源、独占 DB/Redis、真实评估/发布及 J1–J9/S1 门；恢复 V222 不能单独批准这些业务写入或把 EX-27/EX-06 整卡关闭。

## 6. 本次只读证据边界

读取和计算仅为文件/ZIP/UTF-8/SHA/CRC 与 Git 只读状态，无 Java/Maven/PG/HTTP；没有调用旧工程或运行任何产品。当前 `RunService.java` SHA 为 `C6AA628A0AE436627B2F202F10A0278DC9D3069141582658CADBF98219607716`，`EngineMigrationsTest.java` 为 `BCAB2DCC8287A9E73A59E87E8F0AF59924A94CB6CA0672C8AFE814F9882A5B65`，PLAT-13 历史 README 为 `BA5AE036C4AE3805397BAA42E2D8F1BF794921EEB8C1EA83CB2004359AE9EA64`。本报告没有恢复 V222 源文件；源码缺口、旧 target/JAR 与原 history 债务均保留等待准入。
