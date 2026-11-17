# A3-9 验收证据：A09/A10/A12 机制级验收（阶段三收口）

日期：2026-11-18。卡片：台账 §7.4 **A3-9 A09/A10/A12 验收**。基线收口于 4660c818（A3-6 合入后）。

## 验收执行口径

按测试分级减负口径（§5.3）：M3 门标准 A09/A10/A12 对应机制级定向回归；部署栈全链走查随 G5-0/阶段5 承接（诚实登记，与 T2-13 同口径）。

## 门标准逐项对照（主方案 §12-3 / 台账 §2.2）

| 标准 | 机制级证据 | 结果 |
| --- | --- | --- |
| **A09 记忆权限/删除即时退出检索** | MemoryApiTest 9/9：私有显式保存 ACTIVE 即进列表/授权共享读+USER 写 SHARED 403/PATCH CAS 409/DRAFT 审批按层（私有=来源用户、共享=ADMIN）/删除置 REVOKED 立即退出列表+retrieve+单条 404+重复幂等/检索顺序（权限→scope→相关性→topK）/策略收窄退出 | ✓ |
| **A10 插件闭环**（失败保留目录/成功差异/授权及停用） | PluginDiscoveryExecutorTest 11/11：并发认领恰一胜者/租约接管 gen1 STALE 留痕/失败保留目录+enable 不变/成功差异 added+changed+removed+批次递增/旧批次拒绝/来源停用拦截/差异确认门 409 PLUGIN_DIFF_NOT_SHOWN/grant→revoke→再授权→停用拦截闭环 | ✓ |
| **A12 最小装载四项注入** | ResourceManifestTest 6/6：资源六分区聚合/digest 409 定位对象与阶段/空 skillCode=MISSING→RESOURCE_NOT_LOADABLE{objects,stages} 定位受理阶段不进入执行/规则形状/角色门 403/启动清单四项 N/A 留痕。四项注入（模型不可达/插件停用/授权缺失/快照服务不可达）的全栈演练随 G5-0 部署环境承接——机制级以 RESOURCE_NOT_LOADABLE 定位形状+启动清单 N/A 留痕承载（诚实登记） | ✓ |
| 记忆读写策略/两版本策略 | MemoryApiTest（会话钉住版 policy 判写范围+策略收窄退出）+ MemoryPolicy 随快照装载条目 | ✓ |
| 轻量装载两级清单 | ResourceManifestTest.startupChecklist（启动四项）+ admission 按引用逐项 | ✓ |

## 回归统计

- A09/A12 组：MemoryApiTest 9 + ResourceManifestTest 6 + PlanModeTest 5 + CompactionStoreTest 4 + TasksApiTest 6 = **30/30 绿**
- A10 组：PluginDiscoveryExecutorTest 11/11 绿（合并时点 2026-11-18 已记录）
- MCP 闭环组：**McpConnectionTest 7/7 绿**（2026-11-18 合入基线 4660c818 复跑确认）——V322 双路径 migratedTo=322/登记 revision/测试三分支（OK/AUTH_FAILED/TIMEOUT）/批次串行三形态（1→3→5 单调）/三类拦截（BATCH_STALE/REVISION_STALE/DISABLED）/差异三态+review→skillDraftRef

## 部署依赖（诚实登记）

- A12 四项注入（模型不可达/插件停用/授权缺失/快照服务不可达）的**真实故障注入**需部署栈——机制级以 RESOURCE_NOT_LOADABLE 定位形状承载，全栈演练随 G5-0；
- MCP 真实 Streamable HTTP 适配器随接入卡（本阶段 StubMcpTransport 机制级闭环）。
