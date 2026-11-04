# SEM-07.1 AP 分发前条件门禁证据

日期：2026-11-04　席位：O（OR/语义）　卡片：SEM-07.1 实际分发前强制条件检查（M1b 核心）

## 1. 范围与白名单

- 新增 `skill-registry/src/main/java/cn/agentplatform/skill/application/SemanticExecutionGate.java`。
- 新增 `skill-registry/src/main/java/cn/agentplatform/skill/infra/SemanticDecisionClient.java`。
- 修改 `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java`：仅在 `gateApproved` 之后、适配器分发之前增加一次条件复核；既有构造签名不变，用 `@Autowired(required = false)` setter 注入，直构测试保持旧行为。
- 新增两个测试类；未改 `SkillAdapters`、未改 engine、未改 `ToolCalls`、未新增迁移。

## 2. 条件声明与版本来源（关键取舍）

engine 的 `ToolCalls` 登记视图与 `AgentDriver.skill(...)` 请求都没有 `configReleaseId`/`semanticBindings`，且本卡不允许改 engine 传参。因此门禁把“调用归属”落在**冻结技能契约**上：

- 声明：`execution.semanticConditions = {opCode?, bindingId?, releaseId?, conditions[]}`，随技能发布固化，调用请求里的同名字段一律不采信。
- 版本：优先 `importSource.releaseId`；声明内 `releaseId` 仅作为补充，两者不一致直接 409。
- 缺少冻结版本 → 503 `SEMANTIC_CONDITION_UNAVAILABLE`；声明了强制检查却未装配门禁 → 同样 503，不降级为裸调用。
- 该取舍在证据中显式登记：未把 app 配置快照里的 `semanticBindings` 冒充为已传入 engine 的输入。

## 3. 错误映射

| 场景 | 结果 |
| --- | --- |
| 未声明条件 | 零调用，行为与旧路径一致 |
| 全部 HOLDS | 放行到适配器 |
| NOT_HOLDS | 409 `SEMANTIC_CONDITION_BLOCKED`，不进入业务 I/O |
| UNKNOWN | 409 `SEMANTIC_CONDITION_UNKNOWN`，带条件类型与原因 |
| OR 不可达（502 DEPENDENCY_UNAVAILABLE） | 503 `SEMANTIC_SOURCE_UNAVAILABLE` |
| 声明结构非法/版本不一致/绑定非 ACTIVE | 409 `SEMANTIC_CONDITION_INVALID` / OR 原样 409 |

门禁不生成授权 token、不改 actionPlan/幂等键/UNKNOWN 状态机。

## 4. 两轮功能检查

第一轮：

```
mvn -o -pl skill-registry -am \
  -Dtest=SemanticExecutionGateTest,SemanticDecisionClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 首跑 14 例 1 error：测试对“未声明条件”直接调用 `check`，而实现要求 `check` 仅在声明时调用。把 `check` 改为声明缺失即 no-op（更防御），重跑全绿。
- 结果：`SemanticExecutionGateTest` 8/8、`SemanticDecisionClientTest` 6/6。

第二轮（回归）：

```
mvn -o -pl skill-registry -am \
  -Dtest=SemanticExecutionGateTest,SemanticDecisionClientTest,SkillExecutionTest,ApprovedConnectionGateTest,DefinitionLookupSkillsTest,SemanticFactsClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 8+6+9+8+9 = 64 例，0 失败 0 错误；`SkillExecutionTest` 24 例因缺少真实 PG 环境整类跳过；BUILD SUCCESS。

## 5. 未覆盖与边界

- 未运行“真实 PG 零写计数/审批后状态变化/正常动作读回”的端到端链路：需要 AP + engine + OR 同时在线，且 engine 的 tool-call 登记需要真实 run；本窗口未启动三端。
- 门禁顺序由 `SkillExecution.execute` 的调用点保证（`gateApproved` → `gateSemantic` → 适配器分发）；单测覆盖门禁语义，未覆盖真实适配器副作用计数。
- 声明结构中的 `conditions` 只校验形状，条件真实性由 OR 侧求值。

## 6. 回退

移除门禁 setter 调用与两个新类即可恢复旧分发路径；已声明条件的历史技能若回退将失去强制检查，需按卡片要求停用其新流量而不是裸调用。
