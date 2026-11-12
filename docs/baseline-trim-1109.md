# 裁剪清单：1109 基准对齐（C0-1 交付物）

版本：v1.0，2026-11-13。执行卡：台账 **C0-1 裁剪执行**（阶段 0 基准冻结）。
依据：《主方案 v2.0》§2.1《1109 基准对齐与裁剪清单》（1109 Word 定稿全文无"本体"字样，本体线全部挂起）。
裁决顺序：1109 Word > 主方案 > 接口/数据库稿 > 前端稿 > 历史文档。

## 1. 裁剪决定总表（照录主方案 §2.1，处置动作列为本卡实际执行）

| 现有资产 | 处置 | 本卡动作 |
| --- | --- | --- |
| 本体协同线：`SemanticBindingsController`(app V114/V115)、`SemanticCatalogController`(skill V315)、`SemanticResultController`、SEM/M1a/M1b 文档 | **挂起** | 前端入口挂起：`page-access.ts` 中 `integration-semantics`（语义与目标）标 `suspended:true`、移出菜单；后端代码保留不删除，验收主线不含 |
| OR ontorepo 软件联动 | **挂起** | 无代码动作；两软件交互只保留 AP 对外运行接口 |
| 能力包线：`CapabilityPackage*Controller`(app V108-V110)、`capability-package.ts` | **降级保留** | 无代码动作（保持现状）；后续作为 §6.2 插件发现的 `CAPABILITY_PACKAGE` 来源适配器（A3-5 消费），不再作为主干交付物 |
| onboarding 接入向导（V116-V121 + 12 组件） | **简化** | 前端入口挂起：`page-access.ts` 中 `onboarding-templates`（接入模板）标 `suspended:true`、移出菜单；构建流程归入智能体工作区（§6.6），向导组件挂起 |
| L0-L3 分级接入 | **不实现** | 无代码动作；现有 RunController 已满足 1109 §3.4 |
| 评测中心（monitor V504-V506） | **保留为内部质量工具** | 无代码动作；不在验收主线 |
| route2 轨迹 CSV 演示线、R11 PROCESS 完整 Schema 差距 | **随本体线挂起** | 无代码动作（演示数据线随 M5-1 种子资产化时按本清单排除） |
| ActionPlan 双主体确认（V224）+ InlineApproval | **保留** | — |
| 连接档案/环境绑定（V111/V112）、ConfigRelease（V113/V115/V227） | **保留** | — |

## 2. 本卡执行的代码动作

| # | 动作 | 文件 | 说明 |
| --- | --- | --- | --- |
| 1 | `PageAccessEntry` 增加可选 `suspended?: true` 标记 | `web/admin/src/page-access.ts` | 挂起线语义：代码保留不删除、读门不变（深链可打开，满足里程碑挂起线冒烟要求）、不进左菜单、验收主线不含 |
| 2 | `onboarding-templates` 标挂起、移出菜单 | 同上 | 接入向导线挂起 |
| 3 | `integration-semantics` 标挂起、移出菜单 | 同上 | 本体协同线挂起 |
| 4 | `menuEntries()` 过滤 `suspended` 项 | 同上 | 菜单派生单一来源仍然生效（AppShell 经 router navMenus 派生） |
| 5 | 导航测试同步：菜单数 17→15，新增挂起线断言 | `web/tests/navigation-policy.test.ts` | 锁定挂起集恰为两条、读门不变、路由源码保留 |

**主工作树处置说明**：升级工作树 `_worktrees/upgrade-20261103` 是唯一开发现场（台账 §5.1"主工作树不混用"）。主工作树（`代码/agent-platform`，分支 `feat/v1.2-implementation`）当前停在升级分支切出点（merge-base=5da6bd99），无未提交代码改动，其代码内容为升级树的祖先——本清单的裁剪结果随 phase0 线合回基线后自然覆盖，主工作树不再单独提交裁剪改动，避免在停用线上制造分叉提交。

## 3. ontologyVersion 残留核对

核对范围：升级工作树全仓（`*.java`/`*.ts`/`*.vue`/`*.json`/`*.sql`/`*.md`，排除 node_modules、dist、.git）。

- **结论：现行代码无 ontologyVersion 残留。**
- 命中仅出现在 `docs/evidence/**` 下的历史证据快照（`iam-model-input-producer-review-fix` 等回放工作树的源码拷贝），属已归档验收证据，不属现行代码，按证据保全原则不改写。
- 后续对齐：skill V321 迁移（A3-2）将撤销此前 ontologyVersion 契约字段约定（数据库稿 §4.8"不设任何本体专用字段"），本清单作为该撤销的基准依据。

## 4. 验收范围文档更新

《联合验收与回归执行手册-20261028》（`文档/优化方案/`）顶部新增"1109 基准范围更新（2026-11-13，C0-1）"补记：其 DEV 范围中"标准接入向导、语义与目标能力"及 U12/U13 等本体相关用例自 1109 基准起移出验收主线（随挂起线保留可冒烟，不要求新功能）；以 1109 现行套件（`文档/设计/1109现行/`）为唯一验收基准。

## 5. 挂起线冒烟口径（供里程碑检查引用）

每里程碑跑一次挂起线冒烟：`#/onboarding/templates` 与 `#/integration/semantic-bindings` 深链可打开（读门仍为 session），不要求新功能；两入口不出现在任何角色的左菜单中（`menuEntries()` 已过滤）。
