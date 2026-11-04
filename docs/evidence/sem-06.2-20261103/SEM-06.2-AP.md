# SEM-06.2 AP 侧：按需定义查询接线（2026-11-04）

## 1. 范围与白名单

- 新增 `skill-registry` 的 `infra/SemanticDefinitionClient.java`、`application/DefinitionLookupSkills.java`、只读技能登记模板 `resources/skills/semantic-definition-lookup.json`。
- engine 的 `infra/OntologyContextPacks.java`、`application/ContextAssembler.java` 属"必要时复用/引导"，本轮**未改动**，理由见 §4。
- 未触 `SkillExecution.java`、`SkillAdapters.java`、`SkillCatalog.java`，也未改 OR 侧任何文件。

## 2. 客户端契约

`SemanticDefinitionClient` 调 OR `POST /onto/releases/{releaseId}/semantic/definitions/query`，请求只发送
`kinds/objectTypes/terms/intent/limit`（未知字段在 AP 侧先拒，避免把越权选择送给 OR）。

响应核对（任一不满足即 502 `DEPENDENCY_UNAVAILABLE`，不把半合法定义带进上下文）：

| 检查 | 要求 |
| --- | --- |
| `schemaVersion` / `profile` | `1.0` / `read-only` |
| `releaseRef.releaseId` | 必须等于请求的固定版本 |
| `releaseRef.contentDigest` | 64 位小写十六进制 |
| 五类定义 | 数组；每项 `ref.kind` 与所属类别一致；`ref.id/name` 非空 |
| 总条目 | ≤200 |
| `counts` | 五数与数组长度一致 |
| `emptyResult` / `truncated` | 布尔；截断时 `truncation{reason,limit,maxBytes}` 自洽 |
| `projectionDigest` | 64 位小写十六进制 |

OR 的 503 `SEMANTIC_SOURCE_UNAVAILABLE` 原样透传；`SkillClients` 对 5xx/连接失败统一折成的
502 `DEPENDENCY_UNAVAILABLE` 在客户端还原为 503 `SEMANTIC_SOURCE_UNAVAILABLE`，让调用方看到
"源暂不可用、可重试"，而不是把它误判成"请求非法"。

客户端**不缓存**投影正文：撤权后的再次查询必须回到 OR，由 OR 的授权修订缓存键决定是否重算。

## 3. 技能适配器

`DefinitionLookupSkills` 注册运行层类型 `SEMANTIC_DEFINITION`（`SYNC`），经既有
`AdapterRegistry` 自动发现，不需要改声明层 schema 枚举。

- **固定版本**：`contract.execution.releaseId`（登记/发布时冻结）优先；调用参数 `releaseId` 只能缺省或与之相同；
  两者都缺省时 409，绝不在运行期"猜"当前版本。
- **同一 run 一致**：进程内按 `runId`（缺省 `call-scope`）锁定首个 releaseId，后续不同即 409；
  只保存版本指针，不保存投影正文。
- **预算**：单 run ≤8 次查询、累计 ≤400 条定义，超限 400 `SEMANTIC_FIELD_OUT_OF_RANGE`；
  返回体中带 `budget{callsUsed,maxCalls,itemsUsed,maxItems}`，便于观测而不是静默截断。
- **未命中**：`emptyResult=true` 原样返回，summary 明确写"未命中"，不生成任何占位定义。
- 错误码：AP 侧参数问题用 `SEMANTIC_FIELD_OUT_OF_RANGE`/`SEMANTIC_REF_INVALID`；源不可用沿用
  `SEMANTIC_SOURCE_UNAVAILABLE`；未引入同义私有码。

## 4. 为什么未改 engine

engine 侧已有冻结链路：`RunSnapshotService` 把 `{space, releaseId?}` 解析成冻结工具定义与
`config.ontologyContext`，`ContextAssembler` 只把已冻结文本追加到 system prompt，运行中不再解析
version。定义查询技能的 `releaseId` 由技能契约冻结并随工具定义下发，执行仍在 skill-registry；
若在 engine 再引一条"当前 releaseId"通道，反而会把版本讨论带进运行快照，增加两处版本源不一致的风险。
故本轮不改 engine；若后续需要模型提示，应由 06.2 的"引导"另行评估（对无该工具的应用必须零影响）。

## 5. 功能检查（两轮）

环境：`JAVA_HOME=C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`，Maven 离线模式。

1. 首轮 RED/GREEN 过程：
   - 先写 `SemanticDefinitionClientTest`（8 例）与 `DefinitionLookupSkillsTest`（8 例），实现后首跑即 16/16 通过；
     随后补模板可登记性断言（`SkillRules.normalizedSchema`/`SkillRules.endpoint`）复跑仍 16/16。
   - 命令：`mvn -o -q -pl skill-registry -am -Dtest=SemanticDefinitionClientTest,DefinitionLookupSkillsTest -Dsurefire.failIfNoSpecifiedTests=false test`
2. 第二轮回归：
   - `mvn -o -q -pl skill-registry -am -Dtest=SemanticDefinitionClientTest,DefinitionLookupSkillsTest,AdapterRegistryZeroChangeTest,SkillRulesTest -Dsurefire.failIfNoSpecifiedTests=false test`
   - 结果：8 + 8 + 8 + 6 = **30/30，0 失败**（`AdapterRegistryZeroChangeTest` 证明新增适配器未改变既有五单元行为）。

测试覆盖：固定版本与缓存隔离（客户端无缓存，OR 键含授权修订）、伪造 releaseId、ref.kind 错配、
counts 不一致、源不可用透传、未知选择先拒、limit 越界、run 内换版本、调用/条目预算、模板可登记。

## 6. 未覆盖与交接

- 未跑真实 OR HTTP 全链（需要 OR 运行与已发布 release）；留 P 统一验收。
- 未做"应用登记模板→发布→模型实际调用"的 E2E：登记是 ADMIN 动作，模板只产草稿，不自动发布。
- 多实例部署时 run 预算与版本锁是进程内状态；跨实例一致性由 OR 的授权修订与 release 固定性保证，
  若验收要求全局预算，需另行设计共享计数。
- 回退：删除新增三文件与两测试即可；未改既有类、未加依赖、未动数据库。

## 7. 结论

AP 侧已能把固定版本的允许定义按需送进上下文，且不缓存、不越权、不伪造空结果；与 SEM-06.1 的
OR 投影契约字段一一对应。移交 P 复核并纳入 SEM-06 整卡验收。
