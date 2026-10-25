# 引擎与技能共享 Schema 资源防护

本增量只修改独立新工程，未部署。MCP 作者在 2026-10-20 08:04:44 完成 270/270 回归并释放源码/target 后，根任务明确发送“释放并开始”。基线为 `mcp-resource-budget-source-sha256.json`（SHA256 `7de1dd0bf037adb95a1de37f06bf086212f97c2b007a3414528215efb7edbbdd`）及 `.local/mcp-resource-full-skill-final.log`（SHA256 `612b4d87fe27dd01b88a48a0cc83be1b74103c75d17d82511820ce4a01259fb8`）；本轮已逐文件核对。历史报告、23/396、25/412、MCP242及270清单均未覆盖。

## 实现与顺序

三个无业务状态的组件迁至 `admin-common/src/main/java/cn/agentplatform/common/schema/`；skill 删除原实现并明确导入共享类。common 增加固定 networknt 1.5.6 / RE2/J 1.8 直接依赖；skill 和 engine 保留直接使用的 networknt，skill 移除不再直接使用的 RE2/J 依赖。没有技能与引擎业务 JAR 依赖。

既有图准入、正则编译及匹配、实例复杂度算法和硬上限沿用 MCP 最终实现。共享错误文案去掉“技能”限定词；仅新增有限原始 JSON 结构检查入口，以及只能降低固定硬上限的显式方法重载。低限额必须在 `[1,默认值]` 范围内，没有 HTTP 配置、测试运行模式或放宽硬闸的 scope。默认实例仍为 250000 节点、64Mi 字符、深度100；Schema 原始结构仍为 65536 节点、1Mi 字符、深度64，展开工作量8192。

InputDeclarations：有限原文结构检查先于声明 deepCopy/数组转换；Schema对象图预算也先于 deepCopy。声明数组先按原规则转换为完整对象，再执行图预算。两个并存配置继续各自完整验证，FLOW definition.inputs 选择顺序、typed/null/false/0/空容器和默认值不注入语义不改。实例超工作量映射400/INVALID_INPUT，Schema配置拒绝保留400/INVALID_INPUT_DECLARATION，普通原生判分失败保留 INPUT_SCHEMA_CHANGED。

InputDeclarations 和 OutputPolicy 的原递归 walker 删除，均按共享 Plan.locations 初始化全部实际子Schema，包含未使用defs。图预检和受限正则预编译先于 SchemaValidatorsConfig 和原生 getSchema；原生配置关闭跨调用缓存，preload深度64、failFast、闭合外取loader和同一RE2工厂。const/default/enum/examples 内的引用仍是实例literal，properties 内业务名称不解释为Schema关键字。

输入完整验证、输出策略构建和每次输出实例验证各有最多2秒本地单调deadline；输入内部嵌套scope取已有deadline的更早值，两个输入配置共用外层总时限。输出解析前先限定文本64Mi字符，仍使用原严格JSON规则。输出实例预算或deadline失败返回无效 Verdict，诊断限制1024字符，沿用原 TERMINATE / RETRY / PASS_WITH_FLAG 和重试计数。它仍由networknt原生判分，没有自写Schema验证器、校验线程池或子进程服务。

Deadline为有限工作量上的协作检查，不宣称可硬实时抢占任意同步JVM操作。MCP transport、会话许可清理、UNKNOWN不重发、HTTP异步/回调/对账及18MiB产物逻辑均未改变。

## 明确的兼容性收窄：循环引用

原 OutputPolicyTest.recursiveLocalSchemasAndNamedAnchorsRemainValid 曾接受循环本地Schema。本增量按brief确认的 fail-closed 子集明确拒绝循环引用；这是一项兼容性收窄，不能声称旧成功断言完全保留。未修改原设计或四契约，也未增加另一套循环求解器。

原测试完整文件及原始SHA保存在 `.local/shared-schema-before/engine-runtime/src/test/java/cn/agentplatform/engine/domain/OutputPolicyTest.java` 和改前清单；原positive测试的完整原始字节另存 `shared-schema-budget-original-recursive-test.java.txt`（SHA256 `c7fceb0542b153e6ac1109cbed7073e13377560a9532830fa0ccc4a4f3895a7c`）；完整原文件SHA256 `db4e02546dd1b70fdc7b6cd9dc2c42c24af3a7142fb3a12f7270de6f30849e25`。新精确拒绝测试检查 OUTPUT_POLICY_INVALID 和“循环引用”原因；非循环本地anchor仍使用原有效/无效数据的两条判分断言。没有跳过测试或删掉覆盖来换取绿色。

## 有限安全证据

- `.local/shared-schema-engine-red.log`：8项全部为真实断言失败，0错误/0跳过，非编译缺入口。仅小型Schema、小实例、短lookaround语法和已过期deadline；原版没有运行循环/展开耗尽/长CPU探针。
- `.local/shared-schema-engine-targeted.log`：205/205通过，含183项Schema位置矩阵及8项新预算边界。低图限额通过真实纯预算重载执行；拒绝时监测到 SchemaValidatorsConfig 构造0次，源码顺序确认 getSchema 位于其后。此处没有声称直接插桩统计了 getSchema。
- `.local/shared-schema-engine-pg-targeted.log`：67/67通过，包含原35项输入真实PG回归，以及输出低预算下零model_invocation/零模型调用、三种输出策略的调用次数、持久化无效诊断及usage核算。本定向轮之后新增的2项输入低限额PG断言归入最终engine完整轮，不倒填到67项中。
- common 新增5项回归：低图/实例限额及不能放宽上限，声明数组原文检查，deadline嵌套恢复，以及普通anchor/literal与循环拒绝。它们是共享方法回归，不冒充新行为红。

没有捕获OOME，没有先建高成本原生validator再期望超时，也没有构造/重放资源耗尽载荷。原版无法安全执行的风险只提供安全拒绝断言与源码顺序证据，不声称已行为复现。

## 完整模块验证和冻结

本轮按模块顺序完整执行，不能拼称根任务计划的单轮全七模块回归：

| 模块 | 完成时间 | 测试 | 失败/错误/跳过 | 源码及POM前后 |
|---|---|---:|---|---|
| admin-common | 2026-10-20T08:12:57+08:00 | 10 | 0/0/0 | 19文件一致 |
| engine-runtime | 2026-10-20T08:15:59+08:00 | 427 | 0/0/0 | 103文件一致 |
| skill-registry | 2026-10-20T08:18:56+08:00 | 270 | 0/0/0 | 61文件一致 |

engine 为原412加新增15项，原183位置矩阵保留，输入PG原35加2项；原循环成功例按上文明确收窄。skill保留270项，包括原18MiB异步产物、真实HTTP/SSE、回调和对账。本轮没有改前端127项冻结。

common/engine完整轮后，skill测试的一处旧全限定包名引用导致testCompile失败，首次修正又遗漏显式import；最终补齐import后才重新固定skill源码快照并完整跑270项。这些编译准备错误均不计为行为红；原日志保存在 `.local/shared-schema-skill-reference-compile-error.log`、`.local/shared-schema-skill-reference-import-error.log`、`.local/shared-schema-skill-reference-import-error-2.log`。修正前后common/engine全部src/POM摘要一致，因此保留其有效独立模块证据，没有倒称一次全模块统一快照。

本轮源码/测试/POM增删改共21个路径，补丁以改前完整字节快照生成，已在隔离快照运行git apply --check及实际应用，并逐文件核对应用后SHA。没有依赖HEAD。

精确路径：

- `modified` `admin-common/pom.xml`
- `added` `admin-common/src/main/java/cn/agentplatform/common/schema/BoundedRegexFactory.java`
- `added` `admin-common/src/main/java/cn/agentplatform/common/schema/SchemaDeadline.java`
- `added` `admin-common/src/main/java/cn/agentplatform/common/schema/SchemaWorkBudget.java`
- `added` `admin-common/src/test/java/cn/agentplatform/common/schema/SharedSchemaAdmissionTest.java`
- `modified` `engine-runtime/src/main/java/cn/agentplatform/engine/domain/InputDeclarations.java`
- `modified` `engine-runtime/src/main/java/cn/agentplatform/engine/domain/OutputPolicy.java`
- `modified` `engine-runtime/src/test/java/cn/agentplatform/engine/domain/OutputPolicyTest.java`
- `added` `engine-runtime/src/test/java/cn/agentplatform/engine/domain/SharedSchemaBudgetTest.java`
- `modified` `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OutputPolicyPostgresTest.java`
- `modified` `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunInputValidationPostgresTest.java`
- `modified` `skill-registry/pom.xml`
- `deleted` `skill-registry/src/main/java/cn/agentplatform/skill/domain/BoundedRegexFactory.java`
- `deleted` `skill-registry/src/main/java/cn/agentplatform/skill/domain/SchemaDeadline.java`
- `deleted` `skill-registry/src/main/java/cn/agentplatform/skill/domain/SchemaWorkBudget.java`
- `modified` `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java`
- `modified` `skill-registry/src/main/java/cn/agentplatform/skill/infra/BoundedMcpSseTransport.java`
- `modified` `skill-registry/src/main/java/cn/agentplatform/skill/infra/McpAdapter.java`
- `modified` `skill-registry/src/test/java/cn/agentplatform/skill/domain/BoundedSchemaValidationTest.java`
- `modified` `skill-registry/src/test/java/cn/agentplatform/skill/domain/SchemaWorkBudgetTest.java`
- `modified` `skill-registry/src/test/java/cn/agentplatform/skill/infra/McpSchemaBoundaryTest.java`

执行只向测试进程注入 `.env`，日志不记录明文凭据。JDK21，Maven共享target串行使用；common `install -DskipTests`仅为准备依赖，未计作测试。初次离线clean准备因未缓存clean插件而在测试前退出，随后使用现有test命令；只删除迁走三类的7个精确陈旧class文件，避免旧字节码假绿，没有递归删除其他target。

格式检查使用固定 google-java-format 1.25.2 AOSP；四契约、原文快照、七模块依赖边界检查通过。G5四生产文件和FlowDriver SHA与改前保持一致。

最终精确新增/修改/删除清单、前后SHA、各模块完整日志/套件、原positive测试SHA和可应用补丁见本轮 `shared-schema-budget-source-sha256.json`、`shared-schema-budget-verification.json`、`shared-schema-budget.patch`。改前190个文件完整保存于 `.local/shared-schema-before/`；补丁以明确改前快照生成，不用无HEAD空diff证明范围。

补丁保留本轮实际文件字节（包括行尾），在 `.local/shared-schema-patch-check-v2/` 改前隔离副本验证通过。精确重现需使用 `git -c core.autocrlf=false apply --check` 和同配置的 `git apply`；本仓库全局autocrlf=true，首次生成时发现行尾归一化导致应用后SHA不同，已修正生成/应用参数并逐个核对最终21路径的新增、修改或删除结果。补丁仅含源码/测试/POM，报告和证据清单单独交付。

## 其他入口的静态范围

经授权只读扫描新工程七模块 `JsonSchemaFactory/getSchema/initializeValidators` 实际用点，生产只有 SkillRules、InputDeclarations、OutputPolicy 三处，均由本次共享算法覆盖；未发现另一处未接入的本地networknt构建入口。此结论是所查API用点的静态结论，不是对任意JSON序列化或远端供应商校验器的全量安全证明。

SkillRules原有Object/Map到JsonNode转换和各传输接收边界沿用MCP最终实现；本增量没有顺手改写其他JSON、模型或应用Schema流。开源算法/许可审阅继承 `mcp-resource-budget.md`，本轮只核对现有公开扩展API和本地接线，不冒充重新审阅全部上游源码。RE2/J BSD-3-Clause许可继续保留原文件。

本轮是作者验证后的候选，仍需根任务安排独立安全复核、单轮全后端回归及固定制品的两JVM/真实MCP/前端异步纵切。没有授生产发布许可，没有关闭完整G5/G2/T11。
