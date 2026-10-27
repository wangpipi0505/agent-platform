# PLAT-07：Schema 预算降级而非拒绝

- 卡片：`文档/优化方案/派发卡片-B1-20261027.md` 卡片 10（T07）
- 台账：PLAT-07（`文档/优化方案/优化台账-平台侧-PLAT.md:68`）
- 分支：`feat/v1.2-implementation`（**未提交**）
- 日期：2026-10-27
- 范围：`admin-common` 预算判定 + `skill-registry` 输出校验与工具结果 envelope；回调/治理/产物校验点未动，仍为 STRICT。

## 1. 结论：红 → 绿

先红后绿，红阶段全部为真实失败（非编译占位）：

| 阶段 | 命令 | 结果原文 |
| --- | --- | --- |
| 红 1（旧 API，3 例） | `mvn -pl skill-registry test -Dtest=SchemaDegradedValidationTest` | `Tests run: 3, Failures: 2, Errors: 0, Skipped: 0` + `BUILD FAILURE` |
| 红 2（新 API 未实现，编译红） | 同上 | `[ERROR] COMPILATION ERROR :`（新 API/构造器找不到符号）+ `BUILD FAILURE` |
| 绿 1（预算判定） | `mvn -pl admin-common test -Dtest=SharedSchemaAdmissionTest "-DargLine=-Xmx1024m"` | `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` |
| 绿 2（降级校验 + 既有规则） | `mvn -pl skill-registry test -Dtest='SchemaDegradedValidationTest,SkillRulesTest' ...` | `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` |
| 回归（4 个边界测试类） | `mvn -pl skill-registry test -Dtest='HttpSchemaBoundaryTest,McpSchemaBoundaryTest,BoundedSchemaValidationTest,SkillSchemaDialectTest' ...` | `Tests run: 47, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` |

红 1 的两条失败即事故复现：

- `defaultAdapterAcceptsOverBudgetOutputInsteadOfFailing`：默认适配器仍把 173 KB 级响应判成 `SkillAdapters$Failure: 外部响应不符合输出Schema`；
- `strictValidationReportsBudgetNumbers`：STRICT 拒绝消息里没有 `estimatedWork` 数字，无法定位预算量级。

日志：`red.log`（运行时红）、`red-compile.log`（编译红）、`green-admin-common.log`、`green-skill-registry.log`、`green-regression.log`。

红 2 之后有一次“过宽绿”被回归挡住：初版 DEGRADED 对所有预算维度降级，导致 `McpSchemaBoundaryTest.outputWorkRejectionPreservesReadAndWriteSemantics[1][2]` 从 `Expected SkillAdapters.Failure to be thrown` 变成“无异常”（`Tests run: 47, Failures: 2`）。据此把降级面收窄为**只有主维度**（见 §3），既保住资源边界，也保住 MCP 读/写语义的既有契约。

## 2. 改动文件

| 文件 | 关键位置 |
| --- | --- |
| `admin-common/src/main/java/cn/agentplatform/common/schema/SchemaWorkBudget.java` | `MAX_INSTANCE_WORK:14`、`Admission:249`、`degradable():266`、`admitInstance` 委托 `:280`、`admission():287` |
| `admin-common/src/test/java/cn/agentplatform/common/schema/SharedSchemaAdmissionTest.java` | 新增 `:76`、`:97`、`:107`（既有 5 例断言未改） |
| `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java` | `SchemaMode:79`、`Prepared:84`、`build(...,maxExpandedWork):102`、`validate(...,mode):149`、`validate(...,mode,limit):165`、`topLevel:199` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `Raw:25`、`Validation:55`、6 参 `@Autowired` 构造 `:148`、`expansionLimit:176`、`read:464` |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java` | `envelope` 写入 `validation:543` |
| `skill-registry/src/main/resources/application.yml` | `agent.schema.mode:33-34`、`agent.schema.max-expanded-work:35-36` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SchemaDegradedValidationTest.java` | 新增 14 例（`:33`） |

不新增依赖、不改 `pom.xml`、不加迁移、不动 `web/**`、不动 `ProcessContract`/`ProcessAdapter`。

## 3. 关键实现点

### 3.1 `Admission` 判定公式

`Plan.admission(instance, regexPrograms, maxInstanceWork)` 把“是否可校验”变成一个显式决策，而不是直接抛异常：

- `estimatedWork = plan.expandedWork × instance.nodes`（`statistics(instance, 250000, 64MB, 100)` 统计节点数/字符数/最大数字位数）；
- 六维判定，任一超限即 `admitted=false` 并给出 `reason`：
  1. 主维度：`estimatedWork > maxInstanceWork`（默认 `MAX_INSTANCE_WORK = 2_000_000`）
  2. `regexPrograms × expandedWork × (chars + nodes) > 4_000_000`
  3. `comparisons && expandedWork × (chars + nodes) > 4_000_000`
  4. `characterScans && expandedWork × chars > 4_000_000`
  5. `numericOperations && expandedWork × nodes × maxDigits² > 4_000_000`
  6. `uniqueItems && expandedWork × nodes² > 2_000_000`
- `admitInstance(...)` 保持原签名与原消息：`admission(...).require()`，异常仍以 `Schema预算拒绝：本次实例的校验工作量超过上限` 开头，主维度追加 `estimatedWork=N，上限=M`。既有调用方（`BoundedSchemaValidationTest` 等）不需要改断言。
- `Admission.degradable()`：仅当 `!admitted` 且 reason 含 `estimatedWork=`（即主维度）时为真。**只有主维度可降级**；其余五维是“执行校验自身成本”的保护，DEGRADED 下仍必须拒绝，否则等于放弃资源边界（也是 MCP 回归的要求）。

### 3.2 DEGRADED 只保留两条顶层检查

预算拒绝且模式为 `DEGRADED` 时，跳过 networknt 全量递归校验，只做：

1. **根 `type`**：支持字符串和数组（含 `[T,"null"]` 的可空写法），按 `object/array/string/integer/number/boolean/null` 匹配；不符抛 `实例不符合Schema根类型：...`；
2. **根 `required` 存在性**：仅当实例是 object 且根 `required` 是数组时，逐键检查，缺失抛 `实例缺少根必填字段：...`。

其余任何失败（结构上限、schema 展开/编译失败、普通校验错误）都照常抛 `IllegalArgumentException`，由 `SkillAdapters.read` 折成 `OUTPUT_INVALID` —— 降级只发生在“预算主维度拒绝”这一种情形，不会把错误数据放进来。

### 3.3 模式从哪注入到哪一层

`application.yml` → `agent.schema.mode` / `agent.schema.max-expanded-work`
→ `SkillAdapters` 6 参 `@Autowired` 构造（`@Value("${agent.schema.mode:DEGRADED}") String`、`@Value("${agent.schema.max-expanded-work:8192}") long`）
→ 构造期校验：mode 仅 `STRICT|DEGRADED`（否则 `IllegalStateException` 且消息含 `STRICT 或 DEGRADED`）；`max-expanded-work` 必须 `1..8192`（否则 `IllegalStateException` 且消息含 `1..8192`，应用启动失败）
→ `read(...)` 调用 `SkillRules.validate(schema, value, mode, maxExpandedWork)` → `SchemaWorkBudget.inspect(json, maxExpandedWork)`（只可调低共享硬上限 8192，不能抬高）。

`SkillRules.validate(schema, value)` 无 mode 重载恒为 `STRICT`，因此 callback/governance/artifact 三个调用点（以及 mock 固定响应、外部幂等对账采纳）语义不变。

### 3.4 告警落在哪个字段

- 工具结果 envelope（`SkillExecution.envelope` 组装的 `result`）新增：

```json
"validation": { "mode": "DEGRADED", "warning": "SCHEMA_BUDGET_EXCEEDED", "estimatedWork": 5416805 }
```

- 该 map 就是写入 `skill.execution_fact.result` 的 payload，因此运行详情与审计记录里都能看到；`OK` 路径只写 `{"mode":"OK","estimatedWork":N}`，不写 `warning`，调用方不会把降级误读为严格通过。
- 流式转存（超过 `outputMaxBytes` 未做 schema 校验）与 mock/对账路径没有 `validation` 字段，保持原行为。

## 4. 未覆盖 / 风险

1. **`ArtifactStore` 侧未降级**：`offload` 流式转存分支本来就不做 schema 校验（只回文件引用），本次未改；若后续要求“转存也必须给出校验结论”，需要单独设计（可能要在读取时做增量校验）。
2. **前端未展示 `validation`**：本轮未动 `web/**`。`execution_fact.result.validation` 已可用，但运行详情页是否渲染 `DEGRADED` 徽标/告警、是否允许一键“按 STRICT 重跑”属于前端后续工作。
3. **`max-expanded-work` 未做租户级**：当前是进程级配置，所有租户共享 1..8192 的调低范围；若需要租户差异，应在配置中心/租户配置层实现，并复用 `expansionLimit` 的边界校验。
4. **降级面窄于“所有预算维度”**：只有主维度可降级，regex/comparison/scan/numeric/unique 五维在 DEGRADED 下仍拒绝。这是有意的安全取舍（保护校验自身成本、保住 MCP 读写语义），但意味着“超预算即降级”只对节点数×展开工作量成立；若线上再出现其它维度误杀，需要单独评估。
5. **schema 展开超限（`maxExpandedWork`）不降级**：属于 schema 本体问题，注册期就应被拒绝，运行期降级没有意义。
6. **`estimatedWork` 是上界估计**：按“展开工作量 × 节点数”计，未按实际校验路径精确计数；用于告警与定位足够，不作为计费/配额依据。

## 5. 未 commit

本轮**未执行 `git add`、未 commit**；工作树改动见 `git status`，仅涉及上表 6 个文件 + 1 个新测试文件，另有本轮之前既存的 untracked（`docs/evidence/acceptance-audit-20261026/`、`docs/evidence/batch-1-usability-20261026.md`、`output/`）未触碰。
