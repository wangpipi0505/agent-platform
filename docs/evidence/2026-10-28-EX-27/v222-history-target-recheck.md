# EX-27 V222 历史目标重新核对

2026-10-29（Asia/Shanghai），/root/ex04_pg_scope。此报告仅静态读取 AP 自身配置、启动记录、测试 helper 与既有证据，未连接任何数据库、未运行 Java/Maven/HTTP、未执行 helper；只新增本文，不改源、manifest、三入口、index 或旧失败原件。AP HEAD=6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946；测试66EF…仍是未实跑候选。

**结论：不能把 dev 库的21条历史推成“V222从未应用”，也不应仅因选错历史库就撤销兼容问题。存在准确、已登记且有实际旧查询原件支持的目标 agent_platform_engine_test / engine。原 dev 前置必须保持失败；旧 BC333 runner / 1B383 manifest 不能准入 clean 或 SQL恢复。后续只能在主线程另外批准并登记这个精确测试库后进行 fresh只读前置，任何不符仍停。** 本报告自身不是 fresh PG结果或恢复许可。

## 1. 旧 V222 证据究竟指向哪里

| 证据路径（均为本 AP） | 实际内容和边界 |
| --- | --- |
| docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/preflight-array-stop/pg-before-known-history-stop.json | 原严格拒绝：knownDatabase=agent_platform_engine_test，historyRows=25，唯一未知为rank22/version222/typeSQL/脚本V222__run_context_pack.sql，installedBy=agent_v12、success=true。 |
| 同目录上层 legacy-runtime-history.json | 实际只读查询JSON原件，PS5 value/Count封套保留；恰3行V200/V201/V222，各行 database 都是 agent_platform_engine_test；V222 checksum701700386、成功、agent_v12。 |
| 同目录 legacy-runtime-proof.json | 对上述三行与本仓精确 SQL的静态CRC核对，V200/V201为控制，V222 target286字节/SHA419C…；不能改写成dev库证明。 |
| 同目录 full-pg-admission.json:4、27、234 | checkedAt=2026-10-29T11:23:46.8367728+08:00，PG25432/PID15204；agent_platform_engine_test已存在、owner=agent_v12，engine schema/history归属已核；25总行=24成功SQL+1合法SCHEMA rank0。V222 rank22/checksum701700386。 |

本轮对旧 full-pg-admission.json 内 engine_test 的24行逐项与目前已固定23源加保护V222 catalog再次静态比较，script/checksum无差异；版本集合是V200–V223。installed rank 顺序中V221在V223之后，符合 ActionPlanPostgresTest 对既有测试库 outOfOrder(true) 的历史说明，不能把这种顺序误判成新增未知版本。

该旧 admission 另外还有10座 engine schema测试库记录V222，总计11座，**只是已有证据的历史事实，不是允许本轮查询11库**。其 agent_platform_v12 条目只有exist/owner，没有 history；不能由那条记录宣称 v12有24迁移。当前通用 pg-before-error.json 已指向另一次 TestInput前置停止，不能再把它误当V222初次拒绝；V222的准确拒绝原件应引用上表 preflight-array-stop 路径。旧独审描述当时通用路径的历史观察保持原文，本报告补足现在精确归档位置。

## 2. dev、默认库、固定测试库与正式启动覆盖互不等同

- scripts/start-database.ps1:10在缺少.env时生成 AGENT_DB_URL=…/agent_platform_dev_v12；:49分别准备v12/dev_v12，:57将二者说明为集成测试与开发用途。这是启动配置，不能代表每次服务进程实际连接。
- engine-runtime/src/main/resources/application.yml:23的v12只是AGENT_DB_URL缺失时的默认值；:29–33只指定engine schema与classpath迁移。当前.env提供dev URL，因此默认值不能作为正式数据库事实。
- scripts/start-backend.ps1:78–83以父环境和全局.env建立基础，再按ServiceEnvironmentFiles覆盖；:117–124只把覆盖放进本次子进程环境并恢复父进程。当前.local/services.json仅service/pid/port/jar四字段，**没有数据库或环境来源**，不能用该文件猜实际数据库。
- EX01 run-live.ps1:15–17生成本轮唯一库名及AGENT_DB_URL；run-live-engine.ps1:37–43读固定服务环境文件并严格核本轮独立库，:69后通过正式启动器启动engine。公开 engine-continuation-context.json、runs/live-engine-20261028-232216-804/run.json一致登记实际库为agent_platform_ex01_20261028_223728_310，engine PID50360后重启6276，正式JAR SHA76AB67…B419C，服务配置probe通过。全局.env前后仍是AF038…FD4A，这正说明.env不变也不等于实际子服务连接dev。
- 本轮仅读.private对应engine.env的AGENT_DB_URL一项与文件SHA，没有输出其他值。该项准确指向上述EX01库。既有.local/logs/engine-runtime.out.log:24–27也记录PID6276连接该EX01库、validated25 migrations、schema当前223、up to date。日志是历史启动佐证，**不是该库当前24条SQL明细/各checksum的fresh证明**，不能据此自动选它作本轮历史前置。

EX01库与engine_test均不同于本次失败的dev库，也不同于application.yml默认v12。旧正式启动健康和旧包资源清单不能被倒置为“dev已应用V222”。

## 3. 测试 helper 的准确配置链

当前树找到的是 engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineTestDatabase.java 与 EngineMigrations.java，没有名为 EngineMigrationDatabase 的 Java类，不按近似名字假定另一套行为。

EngineTestDatabase.url():12–18读取AGENT_DB_URL（缺省v12），只限制专用25432；:19–31用这个base连接检查是否存在固定agent_platform_engine_test，缺失时实际CREATE DATABASE；:34返回把数据库段精确替换为agent_platform_engine_test的URL。user()/password()沿已有环境。也就是说，即使加载.env后base是dev，最终普通引擎PG测试仍使用engine_test，不能拿base URL当测试目标。

ActionPlanPostgresTest.java:35–48用EngineTestDatabase.url()再按engine/classpath Flyway迁移，且显式outOfOrder(true)；RunEventsPostgresTest.java:38–47也是该固定库后migrate。EngineMigrations.java仅枚举classpath资源版本、去重升序，不选择或连接数据库。部分其它测试夹具确有原自有schema/数据回放行为，原EX04白名单另有记录；这些都不属于当前只读前置。

因此不能执行EngineTestDatabase.url()或某个PG测试来“发现实际库”——它们可能创建库、迁移或清理夹具。本次fresh历史前置应直接用已批准固定连接目标和只读psql，绝不调用这些helper。

## 4. EX06 / EX27 原候选应如何准确理解

EX06 ap-stage-execution-candidate.md:41–49要求未来本session唯一新空库，:42的“23源码/24包、未来读回24份history及V222checksum”来自旧包资源清单，是**新session验收计划**，原文也明确“此值尚非本轮验证”；:49禁止默认dev或共享库。ap-stage-plan-review.md:82核的是ZIP原件与源码来源债务，未证明dev已应用。两份文件不是dev的历史查询证据。

EX27 candidate把既有V222历史兼容作为恢复目的，有EX04 engine_test旧查询支持；但未把兼容目标直接绑定到那座库，后续未封manifest把.env中的dev选作24历史目标，造成了本轮准入拒绝。应修正的是目标绑定和证明层次，不能删除failed4AD原件、把21改作24、迁移dev来凑前提或改查任意库直到通过。

另须保持历史设计边界：PLAT-13 README:101记载当时V222改V221后整条迁移有意删除，没有独立列写入者。本次若继续，只能作为精确已执行历史资源的兼容保留，不称误删恢复或审计新功能。对尚未应用V222的dev/全新库，恢复资源后正常migrate确实会增加这个可空列；不能把兼容方案描述成“所有数据库都没有DDL影响”。本卡不因此授权迁移dev；新库验证须仅在另行登记的EX06独占空库正常启动完成。

## 5. 后续边界和选择

建议保留“V222历史兼容”候选目的，暂缓现有 dev 目标的collector与测试准入；由主线程明确登记 **agent_platform_engine_test / engine / 127.0.0.1:25432 / agent_v12**，以旧BBADCB36完整history作来源，再取得一次fresh只读owner/session/history/column事实。以原23源+286字节固定catalog核24成功SQL，SCHEMA0另列；类型jsonb、可空、无默认；任何不符停。旧证据只能定位目标，不能替代fresh身份及当前结构核验。

若该准确目标通过，可由主线程重新冻结collector/manifest与最小测试/恢复范围，再按实红→原字节恢复→clean绿→历史validate/新库migrate分阶段推进。若不通过，继续保留失败，不换库、不修history、不放宽测试。当前66EF候选测试从未实跑，不能计实红或默认混入后续全量；是否重新准入、撤回或替换由主线程在范围收敛后明确处理，并保护候选原件。

只做“源与clean包均为23项”的验证可以证明打包一致性，却不能消除已有engine_test等已应用V222的兼容债务；缺资源的新包接入这些库仍需独立迁移兼容策略。故现有证据不足以支持把EX27降格为该单项检查后关闭，也不支持推断任何历史库可被重建或忽略未解析迁移。

本报告封版时，主线程已明确另行授权仅engine_test的下一次fresh只读窗口，并在三入口追加目标核正；该后续实际结果必须另存history-preflight-engine-test.json，与dev失败history-preflight.json分开。本报告记录依据，不提前记下一次通过。

## 6. 关键原件 SHA-256

| 路径 | SHA-256 |
| --- | --- |
| docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/full-pg-admission.json | BBADCB36E055C15F8CF053A2177BCBB6639314FE88C7DF7047CE72D290F3376E |
| docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/legacy-runtime-history.json | 6AAE94046A9B34179A7724C1D5C17CD9DF706FB3DFBCEA0F1F45E20CCA893ABF |
| docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/legacy-runtime-proof.json | 3878033FFBE2673B9E641C21396E9A13C6E7A98E072698506BBE10DE08DB3DF8 |
| docs/evidence/2026-10-28-EX-01/engine-continuation-context.json | F92AF804D938CAA63EEC55620EECAB36F9B4A5CC35638DE7BDEF27DAC96A3479 |
| .local/services.json | 79FAE4756C165683FDB4BB18DBA7715DC9AC4FAC21777ABC22EAE9F95F118D2D |
| docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/preflight-array-stop/pg-before-known-history-stop.json | 06E644BE923A258DA16A9938D103A045DD05FB530F2B3422EF15314E4AB87960 |
| docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/read-known-runtime-history.ps1 | D21332C2456D457094C7229CBFDEA0CBC7E82432F65EDDD88C944ECA6DBB1287 |
| docs/evidence/2026-10-28-EX-01/runs/live-engine-20261028-232216-804/run.json | 3D88FC3681E3D0F91FE50181EE987DEE910140FC7288B100029117A9959BBAE3 |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineTestDatabase.java | 770032049F11755A24761279FD5528DA98CD8D63BFE4462DA7E107440CA62780 |
| .local/logs/engine-runtime.out.log | CCDB4EDB29BDF5D961BD76077B5FF4417F49F56FB045D4EF18235ED669ECA704 |
| .local/ex27-v222-recovery/v222-source-20261029-01/history-preflight.json | 4AD65D712E5017EE540F258D1BD5153B387052C43688B310EBE97FD50C917533 |

EX01 engine.env仅作私密配置链核对，SHA=C389CE961A1FCF503C77E37CB6AF047FFB4157319CF697CF953E5AA31BCEA955，不复制其内容到公开证据。dev失败原件4AD65…917533保持passed=false与两条SELECT/native0，未查列；本轮静态复核没有修改该原件。当前源码V222仍缺，新增测试SHA仍66EF434AB8ABB818599C69C9E0BF1D80C02087CFB7CD05CFD8AB577F2FF6109F。
