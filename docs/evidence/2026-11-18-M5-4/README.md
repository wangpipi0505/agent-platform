# M5-4 验收收口证据：A14/A15/A15b/A16 机制级汇总（阶段五机制级收口）

日期：2026-11-18。卡片：台账 §7.6 **M5-4 验收收口**。基线 tip `d7e2c87d`（M5-1 合入后）。

## 机制级口径（诚实登记，与 T2-13/A3-9/A11-A13 同口径）

本环境无部署栈：M5-4 的 A14/A15/A15b/A16 以**机制级定向回归证据**收口（各证据已在对应卡 DoD 全绿）；A01-A16 正式编号全链走查、性能口径 P50/P95 实测、G5-0 部署演练、M5-2 切真实 API、M5-3 E2E 随部署环境承接——不冒充全链完成。

## A14/A15/A15b/A16 逐项对照

| 用例 | 机制级证据 | 结果 |
| --- | --- | --- |
| **A14 长会话压缩** | CompactionStoreTest 4/4：三事件锁协议（start→summary→user/message replace→end）/配对边界硬性拒绝（409 COMPACTION_PAIRING_BROKEN）/压缩后引用早期事实（摘要入投影+遮蔽区摘除+重放一致）/孤儿锁检测与恢复补写 | ✓ |
| **A15 会话长度与预算控制** | TasksApiTest（task budget JSON 在位）+ AgentLoopBudgetPostgresTest（既有预算链回归绿）+ manifest budget.maxTurns/maxModelCalls 字段装载（SpillPromptTest/ResourceManifestTest） | ✓ |
| **A15b 输出校验策略** | SpillPromptTest A15b 双分支 5 例：RETRY=retry.max+1 次同一预算/TERMINATE=首次终止/output_validation 落库含 OUTPUT_SCHEMA_INVALID 原因码与策略动作 | ✓ |
| **A16 mock↔真实一致** | M5-1 种子幂等复跑两轮 9/9 绿（行数+内容 sha256 前后一致 38/32/2）+ 激活指针 active() 可读断言 + demo:true 明示纪律（M5-2 切真实 API 随部署环境承接——机制级以种子幂等承载一致性前提） | ✓（机制级） |

## M5 门组成卡状态

| 卡 | 状态 | 证据 |
| --- | --- | --- |
| M5-1 种子迁移 | ✓ 交付（c3dd483a） | 两轮幂等 9/9+migratedTo=901 三模块+active() 断言 |
| M5-2 切真实 API | 部署依赖（诚实登记） | mock 分支保留+demo:true 明示；切换需部署栈 |
| M5-3 场景转 E2E | 部署依赖（诚实登记） | 场景数据已资产化（种子/映射表），E2E 脚本需部署栈 |
| G5-0 部署演练 | 部署依赖（诚实登记） | V901 三域种子+启动清单四项+跳过开关已备；演练需部署环境 |
| R-REG-1 | ✓ 归档（eba3f963） | 25 条款机制级逐条对照 R-REG-0 映射 |
| 挂起线冒烟 | ✓（M1-M3 门已含） | 挂起路由保留/读门不变/菜单排除（navigation-policy 锁定） |

## 判定

机制级全量收口完成：A14/A15/A15b/A16 证据齐备（部署栈项诚实登记），R-REG-1 25 条款归档，全部门（M0/M1/M2/M3/M4）tag 在链。M5 门判定=机制级通过；正式编号全链证据随部署环境承接。
