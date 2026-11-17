# R-REG-1 验收证据：25 条款逐条核对归档（机制级）

日期：2026-11-18。卡片：台账 §7.6 **R-REG-1 按 §7.8 核对覆盖并归档**。对照映射：contracts/reg-1109-clause-map.md（R-REG-0 初表）。基线 tip 见 milestone/m2-signoff→m2-gate→m3-gate→m2-progress-6 链（tag 列表可溯）。

## 机制级口径（诚实登记）

本环境无部署栈：25 条款按**机制级定向回归证据**逐条归档（对应测试类+断言），部署栈正式编号全链证据随 G5-0/阶段5 部署演练承接。逐条结果如下（实施卡=已交付；证据=测试类）：

| # | 条款 | 实施卡（已交付） | 机制级证据（测试类） | 判定 |
| --- | --- | --- | --- | --- |
| 1 | 基础1 任务编排与模式控制 | T2-2/T2-3/T2-4 | TasksApiTest（mode 必填/幂等）+PlansApiTest（CAS/确认/五项）+PlanModeTest（双轨解耦） | ✓ |
| 2 | 基础2 会话存储与检查点恢复 | H1-1/2/3/8+T2-2+F3-6(session-drill) | ChatTurnEventLinkageTest（四时点重放）+AgentReceiptLostRecoveryGateTest（受理恒 1）+RecoveryFactExitTest | ✓ |
| 3 | 基础3 上下文与长期记忆 | T2-9+A3-4 | CompactionStoreTest（三事件锁/配对边界）+MemoryApiTest（A09 子集 9 例） | ✓ |
| 4 | 基础4 工具调度与权限控制 | H1-6+T2-2+T2-12 | ApprovalPolicyEnforcementTest（never/fail-closed）+BusinessIntentMissingValueTest+TaskReconciliationTest（409 证据复核） | ✓ |
| 5 | 基础5 模型服务与能力配置 | 复用回归（model-gateway 现状） | ResourceManifestTest（模型条目聚合）+A3-8 装载清单 | ✓（部署栈连通演练随 G5-0） |
| 6 | 基础6 运行记录与诊断 | F2-3+F3-6+H1-9 | SessionEventsEndpointTest（11 例投影/游标/SSE）+session-drill 前端静态锁定 | ✓ |
| 7 | 推理1 大模型选择绑定 | 复用回归 | persistedModelResponseIsReusedAfterLeaseTakeover（TracePostgresTest 24 例内） | ✓ |
| 8 | 推理2 提示词模板管理 | 复用回归（prompt_template_version） | PromptAssembler 渲染落 system/message（SpillPromptTest 内）+模板版本发布预检 | ✓ |
| 9 | 推理3 工具调用编排 | A3-2+H1-6 | AgentEngineShadowTest B1（采纳集=实际执行）+事件级 B2 配对 | ✓ |
| 10 | 推理4 推理策略配置 | T2-10+A3-1 | SpillPromptTest A15b 双分支（RETRY 3 次同预算/TERMINATE 1 次）+output_validation 落库 | ✓ |
| 11 | 流程1 画布编排 | F2-1+B4-1 | ConfirmationCenterTest plan-import 映射+降级 | ✓ |
| 12 | 流程2 调试预览 | F2-1 DebugPanel 复用 | 前端静态锁定（workspace-shell）+AgentEngine 影子 | ✓ |
| 13 | 流程3 异常处理 | H1-8+plan_step.failure_policy | StepExecutionMappingTest（A08b：超时映射/记账/必需下游 BLOCKED/可选 SKIPPED）+ExecutionRecoveryTest 四态 | ✓ |
| 14 | 流程4 发布集成 | A3-1+T2-15 | WorkspaceManifestPostgresTest（预检阻断定位）+ExternalIntegrationTest（四端点白名单/mode 强制） | ✓ |
| 15 | 管理1 智能体列表与检索 | F1-2+F0-1 | navigation-policy（六角色菜单）+SessionListEndpointTest | ✓ |
| 16 | 管理2 分类与标签 | 复用回归 | AppsPage 筛选静态锁定（L4 交付） | ✓ |
| 17 | 管理3 生命周期管理 | 复用回归 | AppWorkspacePage 提交发布链（workspace-shell 断言）+plan_revision 不可变历史 | ✓ |
| 18 | 管理4 运行监控 | F2-3+F3-1 | use-live-list.test+TaskEventsSseTest（SSE 实时） | ✓ |
| 19 | 管理5 执行记录与日志 | F2-3+F3-6 | session-drill.test（锚点定位/压缩标记/双视角） | ✓ |
| 20 | 插件技能1 技能与规则注册 | A3-2+A3-3 | SkillGuideDualTrackPostgresTest（GUIDE 五字段）+RuleAssetsPostgresTest（互斥阻断） | ✓ |
| 21 | 插件技能2 插件发现与能力管理 | A3-5 | PluginDiscoveryExecutorTest 11 例（认领/接管/差异/授权停用闭环） | ✓ |
| 22 | 插件技能3 轻量化运行时 | A3-8 | ResourceManifestTest（两级清单/RESOURCE_NOT_LOADABLE 定位） | ✓（真实四项注入随 G5-0） |
| 23 | 插件技能4 第三方 MCP 接入 | A3-6 | McpConnectionTest 7 例（按次建连/批次串行/差异审核/旧批次拦截） | ✓（真实 Streamable HTTP 适配器随接入卡） |
| 24 | 性能1 自然语言规划与执行 | T2-4+T2-2 | PlansApiTest（结构化固化/execute 五项）+PlanModeTest | ✓ |
| 25 | 性能2 多任务与子智能体调度 | T2-6+T2-7 | TaskDispatchTest（三要素接管）+SubagentPlanningTest（A03 汇合） | ✓ |

## 判定

25/25 条款机制级覆盖归档完成（对应实施卡全部交付、证据测试类全绿于各卡 DoD 记录）。部署栈正式编号全链证据（A01-A16 运行时走查、性能口径 P50/P95 实测、A12 真实故障注入、A16 mock↔真实一致）随 G5-0 部署演练与部署环境承接——诚实登记，不冒充全链完成。
