# R-12 证据补档对照清单（索引第四节逐行打勾）

- 执行日期：2026-11-19
- 执行卡：R-12 证据补档批量执行（台账 §7.6b；X-1 名下）
- 依据底稿：`文档/优化方案/04-核查与过程记录/证据索引-X-1-20261118.md` 第四节（本文件为**副本打勾登记**，原索引文件在文档库不动，由主线代录）
- 打勾口径：`[x]`=已有独立证据目录（含补档新建）；目录名 `docs/evidence/<日期>-<卡号>/`，README 含验收口径/复现命令/关键输出摘要/报告 mtime。
- 诚实声明：所有"实跑复验"数字来自 2026-11-19 本机实际执行（PG 127.0.0.1:25432，JDK 21，ENGINE_PG_TEST=true）；未复跑的卡在各自 README 内注明并附复现命令；target/ 与 .local/.tools 不入 Git，surefire 报告 mtime 已摘录进各 README。

## 一、第四节清单逐行打勾

### 阶段 0（7 张）

- [x] C0-1 → `docs/evidence/2026-11-13-C0-1/`（文档/挂起标注卡，未复跑登记）
- [x] C0-2 → `docs/evidence/2026-11-13-C0-2/`（四契约冻结在册核查）
- [x] C0-3 → `docs/evidence/2026-11-13-C0-3/`（设计稿→M5-1 实测承接）
- [x] C0-4 → `docs/evidence/2026-11-13-C0-4/`（oracle 骨架 `--help` 实跑可执行）
- [x] C0-5 → `docs/evidence/2026-11-13-C0-5/`（纯登记卡，指向台账 §9，无提交锚点——诚实登记）
- [x] R-REG-0 → `docs/evidence/2026-11-13-R-REG-0/`（reg-1109-clause-map.md 25 条款在册）
- [x] F0-1 → `docs/evidence/2026-11-13-F0-1/`（文案卡，未复跑登记）

### 阶段 1 引擎链 H1（8 张）

- [x] H1-3 → `docs/evidence/2026-11-14-H1-3/`（实跑 InboxContractTest 5/5 绿）
- [x] H1-4 → `docs/evidence/2026-11-14-H1-4/`（实跑 AgentEngineShadowTest 3/3 绿）
- [x] H1-5 → `docs/evidence/2026-11-14-H1-5/`（实跑 AgentD2RequestPersistTest 2/2 绿）
- [x] H1-6 → `docs/evidence/2026-11-14-H1-6/`（实跑 BusinessIntentMissingValueTest 2/2 绿；十类回归未整链复跑——README 登记）
- [x] H1-7 → `docs/evidence/2026-11-14-H1-7/`（实跑 StepSnapshotBindingTest 3/3 绿）
- [x] H1-8 → `docs/evidence/2026-11-14-H1-8/`（实跑 RecoveryFactExitTest 2/2 绿）
- [x] H1-9 → `docs/evidence/2026-11-14-H1-9/`（实跑 SessionEventsEndpointTest 11/11 绿；21/21 中 4 例未复跑——README 登记）
- [x] H1-10 → `docs/evidence/2026-11-13-H1-10/`（门内 9 类代表实跑 35/35 绿；15 类 78/78 为交付时点口径——README 登记）

### 任务域 T2/S2（13 张）

- [x] T2-2 → `docs/evidence/2026-11-14-T2-2/`（实跑 TasksApiTest 7/7 绿；6→7 演进对账在案）
- [x] T2-3 → `docs/evidence/2026-11-14-T2-3/`（实跑 PlansApiTest 6/6 绿；4→6 演进对账在案）
- [x] T2-4 → `docs/evidence/2026-11-14-T2-4/`（实跑 PlanModeTest 5/5 绿）
- [x] T2-5 → `docs/evidence/2026-11-14-T2-5/`（实跑 StepExecutionMappingTest 5/5 绿）
- [x] T2-6 → `docs/evidence/2026-11-14-T2-6/`（实跑 TaskDispatchTest 7/7 绿）
- [x] T2-7 → `docs/evidence/2026-11-14-T2-7/`（实跑 SubagentPlanningTest 9/9 绿；8→9 演进对账在案）
- [x] T2-9 → `docs/evidence/2026-11-14-T2-9/`（实跑 CompactionStoreTest 4/4 绿）
- [x] T2-10 → `docs/evidence/2026-11-14-T2-10/`（实跑 SpillPromptTest 9/9 绿）
- [x] T2-11 → `docs/evidence/2026-11-14-T2-11/`（实跑 TaskEventsSseTest 5/5 绿）
- [x] T2-12 → `docs/evidence/2026-11-14-T2-12/`（实跑 TaskReconciliationTest 5/5 绿）
- [x] T2-14 → `docs/evidence/2026-11-14-T2-14/`（前端 mock 契约，未复跑登记）
- [x] T2-15 → `docs/evidence/2026-11-14-T2-15/`（实跑 ExternalIntegrationTest 5/5 绿）
- [x] S2-1 → `docs/evidence/2026-11-14-S2-1/`（实跑 SessionListEndpointTest 6/6 绿）

### 资产域 A3（清单 7 张中 6 张缺——A3-9 已有 `2026-11-18-A3-09/`）

- [x] A3-1 → `docs/evidence/2026-11-14-A3-1/`（实跑 WorkspaceManifestPostgresTest **4/5 绿+1 例链尾断言过期**（==123 vs 实际 901，M5-1×A3-1 互作，R-7 同类未覆盖）——诚实登记，待主线处置）
- [x] A3-2 → `docs/evidence/2026-11-14-A3-2/`（实跑 SkillV321 4/4+SkillGuide 8/8=12/12 绿；24→12 类演进对账在案）
- [x] A3-4 → `docs/evidence/2026-11-14-A3-4/`（实跑 MemoryApiTest 9/9 绿）
- [x] A3-5 → `docs/evidence/2026-11-18-A3-5/`（实跑 PluginDiscoveryExecutorTest 11/11 绿）
- [x] A3-7 → `docs/evidence/2026-11-14-A3-7/`（实跑 TodoJobsTest 4/4+SessionStoreContractTest 5/5=9/9 绿）
- [x] A3-8 → `docs/evidence/2026-11-18-A3-8/`（实跑 ResourceManifestTest 6/6 绿）
- [x] （A3-9 已有目录 `2026-11-18-A3-09/`，不重复建）

### 前端（16 张）

- [x] F1-1 → `docs/evidence/2026-11-14-F1-1/`
- [x] F1-2 → `docs/evidence/2026-11-14-F1-2/`
- [x] F1-3 → `docs/evidence/2026-11-14-F1-3/`
- [x] F2-1 → `docs/evidence/2026-11-14-F2-1/`
- [x] F2-2 → `docs/evidence/2026-11-14-F2-2/`
- [x] F2-3 → `docs/evidence/2026-11-14-F2-3/`
- [x] F3-1 → `docs/evidence/2026-11-14-F3-1/`
- [x] F3-2 → `docs/evidence/2026-11-14-F3-2/`
- [x] F3-3 → `docs/evidence/2026-11-14-F3-3/`
- [x] F3-4 → `docs/evidence/2026-11-14-F3-4/`
- [x] F3-5 → `docs/evidence/2026-11-14-F3-5/`
- [x] F3-6 → `docs/evidence/2026-11-14-F3-6/`
- [x] F4-1 → `docs/evidence/2026-11-14-F4-1/`
- [x] F4-2 → `docs/evidence/2026-11-14-F4-2/`
- [x] F4-3 → `docs/evidence/2026-11-14-F4-3/`
- [x] F4-4 → `docs/evidence/2026-11-14-F4-4/`

（前端 16 张全部为"未复跑"登记——本工作树未装 web/node_modules；每卡 README 附 vitest 复现命令与测试文件指针。）

### 其他（4 张）

- [x] B4-1 → `docs/evidence/2026-11-18-B4-1/`（ConfirmationCenterTest 未复跑登记+调用点 grep 实测；RoutesTest 19/19 由 R-4 归档承接）
- [x] M5-1 → `docs/evidence/2026-11-18-M5-1/`（实跑三域 DemoSeedV901 3+3+3=9/9 绿——补齐卡面"无 surefire 留痕"欠账）
- [x] R-3 → `docs/evidence/2026-11-18-R-3/`（四码契约 grep 实测命中；前端锁定测试未复跑登记）
- [x] R-7 → `docs/evidence/2026-11-18-R-7/`（实跑 McpConnectionTest 7/7 绿）

## 二、第四节清单之外顺带补齐（索引生成后的新增通过卡）

- [x] R-10 → `docs/evidence/2026-11-19-R-10/`（实跑 Metadata 16/16+Status 19/19=35/35 绿，与卡面一致）
- [x] R-11 → `docs/evidence/2026-11-19-R-11/`（两步复现路径完整复跑：工件重建 116 条 0 违规+PublishedAdmissionHttpPostgresTest 20/20 绿；首跑缺工件 fail-fast 亦如实登记）
- [x] R-8 → `docs/evidence/2026-11-18-R-8/`（实跑 EngineContextPostgresTest 2/2 绿；本卡在索引 §3 标"未开始"系索引时点早于其交付，台账已通过 c0776c0e）

## 三、覆盖率核算（口径：81=台账 §7 实体 76+M4 单列 A11/A13+R-9/R-10/R-11）

- 补档前（索引 §二口径）：**22/81**
  = 13 有目录通过卡（H1-0/1/2、T2-1、T2-13、A3-9、M5-4、R-REG-1、R-1、R-2、R-5、R-6）+3 单文件归档（A3-3/T2-8/R-4）+A11-A13 目录+索引生成后新增 4（M5-2/M5-3/G5-0/R-9）
- 本卡新建目录：**57 个**（54 张清单卡+R-10/R-11+R-8；M5-4/R-REG-1 已有目录不重复建）
- 补档后：**79/81**（≥70 达标）
  - 未建目录余 2：X-1（证据=本索引底稿+本清单，进行中）、X-2（证据=台账 §7.9 五项裁决落文，文档库）——均为过程卡，代码仓无可归档测试产物
- 达标判定：**79/81 ≥ 70/81，通过**。

## 四、抽查指引（验收方任意抽 5 个目录）

每个目录 README 均含：卡号+合入提交 hash、验收口径摘录、可直接执行的复现命令、关键输出摘要（实跑/未复跑显式标注）、surefire 报告指针+mtime。建议抽查：`2026-11-14-T2-6`（实跑）、`2026-11-19-R-11`（两步复现）、`2026-11-14-A3-1`（诚实登记失败样例）、`2026-11-13-C0-4`（骨架实跑）、`2026-11-14-F4-2`（前端未复跑样例）。
