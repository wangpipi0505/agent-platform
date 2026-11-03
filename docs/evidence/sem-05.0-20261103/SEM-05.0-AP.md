# SEM-05.0 AP 侧：本体元数据投影按 return_spec 声明提取

- 卡片：SEM-05「证据回执、时效与跨仓元数据投影」子步 05.0（双线方案 §4.1 席位 O 首项）
- 分支：`upgrade/20261103`，AP 基线提交 `5da6bd99`；OR 侧本子步只读核对，起点 `91935bb`
- 日期：2026-11-03
- 结论：**子步功能完成待统一验收**（按总入口 §7 状态机；本子步不授权独立验收）

## 1. 基线与缺陷链

OR 契约生成器对每个本体操作声明 `ontologyMetaPath: "$.meta"`，正式执行入口返回
`{code,data,meta}`，且 `meta` 是真实版本四元组（`spaceCode/releaseId/version/contentDigest`，
正式发布另带 `preview:false`）。AP 导入时保留了整份 `return_spec`，但读取入口把路径写死成
`ontologyMeta`，于是声明从未生效、正式四元组全部丢失，且无法区分"未采集"与"采集到别的版本"。

静态定位（基线 `5da6bd99`）：

| 位置 | 事实 |
| --- | --- |
| `代码/_worktrees/upgrade-20261103/ontorepo/onto-contract/src/main/java/cn/ontorepo/contract/ContractGenerator.java:70` | `returns.put("ontologyMetaPath", "$.meta");` |
| `代码/_worktrees/upgrade-20261103/ontorepo/onto-contract/src/main/java/cn/ontorepo/contract/ContractValidator.java:69` | 只接受 `structuredPath=$.data`、`ontologyMetaPath=$.meta`，否则 `INVALID_RETURN_PATH` |
| `代码/_worktrees/upgrade-20261103/ontorepo/onto-trace/src/main/java/cn/ontorepo/trace/application/CandidateService.java:964-999` | 正式 `meta`：`spaceCode`、`releaseId`、`"v"+versionNo`、`bundleSha256`、`preview:false` |
| `代码/_worktrees/upgrade-20261103/agent-platform/skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java:157` | `validateReturnSpec` 校验 modelSummary/artifactSources/cardTemplate，未校验 ontologyMetaPath |
| `代码/_worktrees/upgrade-20261103/agent-platform/skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java:1159`（基线） | `JsonNode ontology = output.path("ontologyMeta");` 硬编码，声明被忽略 |

## 2. 修复范围

只改认领文件（卡片白名单）：

| 文件 | 改动 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `read(...)` 计算 `returnSpec` 后调用新增私有 `ontologyMeta(output, spec)`；删除硬编码 `output.path("ontologyMeta")` |
| `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java` | `validateReturnSpec` 补 `ontologyMetaPath` 受限路径校验（非空字符串、单对象点路径/数组下标；拒绝 `[*]`、`$` 根和坏类型） |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/OntologyMetaProjectionTest.java`（新增，9 例） | 投影提取、旧字段兼容、root meta 不识别、路径错/非对象/四元组不全/preview、转存不伪称、注册期路径校验 |

未改 `OntologyManifestImports.java`（导入已保留 `return_spec`）、`SkillExecution.java`（既有四元组
非空门槛仍作为第二道闸，不伪造字段）、`ReadOnlyToolCache.java`（深拷贝既有 `Raw.ontologyMeta` 语义不变）。

## 3. 运行期不变量

- **声明优先**：`returnSpec.ontologyMetaPath` 存在时，只按该受限路径取对象，禁止回退到根 `meta`、
  `importSource`、模型字段或旧 `ontologyMeta`；因此同响应里同时存在同名旧字段时，声明路径胜出。
- **失败即拒绝**：路径未命中（含 `select` 抛"结果路径不存在"）、命中非对象、四元组任一字段空白、
  `preview` 为布尔真，统一 `OUTPUT_INVALID`，不透出半截元数据。
- **无声明保留存量行为**：仍读顶层 `ontologyMeta`；普通 HTTP 响应的根 `meta` 不会被自动识别，
  避免把任意业务字段当版本证据。
- **转存大响应**：走既有受控文件引用分支，`Raw.ontologyMeta` 为空（NullNode），不宣称已采集四元组。
- 输出 Schema 校验、摘要/卡片/产物映射、错误语义、PG 事实形状与字段顺序均未改；无 DB 迁移、
  无新依赖、无公开 API 变化。

## 4. 测试（两轮，均在同一份断言上）

第一轮——先跑实红（实现前，基线 `5da6bd99` + 新增测试）：

```
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
mvn -q -pl skill-registry -am -Dtest=OntologyMetaProjectionTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 9, Failures: 6, Errors: 0, Skipped: 0
  6 条按预期红：声明路径提取取到旧 ontologyMeta（fake）、路径缺失/非对象/缺字段/preview 未拒绝、
  注册期坏路径未拒绝；3 条基线偶然通过（旧字段兼容、root meta 不识别、转存不加元数据）。
```

第二轮——实现后复跑新增用例并带相邻回归：

```
mvn -q -pl skill-registry -am \
    -Dtest=OntologyMetaProjectionTest,ReturnDeclarationContractTest,OntologyManifestImportsTest,SkillRulesTest,SchemaDegradedValidationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
→ OntologyMetaProjectionTest 9、ReturnDeclarationContractTest 5、OntologyManifestImportsTest 20（3 跳过，既有环境条件）、
  SkillRulesTest 6、SchemaDegradedValidationTest 14；合计 54，Failures 0，Errors 0
```

测试为纯 JUnit5 + Mockito（`SkillAdapters` 短构造），不启动 Spring、不连 PG/MinIO/外部对端。
两轮之间未放宽任何断言。

## 5. OR 端只读核对（本子步不改 OR）

- `ContractGenerator.java:70` 固定声明 `$.meta`；`ContractValidator.java:69` 对非 `$.data`/`$.meta`
  报 `INVALID_RETURN_PATH`——与 AP 新提取逻辑的受限路径一致。
- `CandidateService.java:964-999` 正式成功响应携带完整四元组与 `preview:false`；候选预览响应
  `releaseId/version=null`、`preview:true`，因此 AP 对 `preview=true` 直接拒绝，不会把候选当实际版本。
- `onto-contract/src/test/resources/sample-2.json:19`、`sample-4.json:20` 与
  `onto-designer-api/src/test/java/cn/ontorepo/P2SupplyTest.java:82,145` 均以 `$.meta` 为声明，
  无需改样例。
- 未运行 OR 构建/测试；本子步不改 OR 文件，真实发布链路联调留给 SEM-05.1 与统一验收。

## 6. 未覆盖 / 有意限制

- 只覆盖 HTTP 成功响应的元数据提取；错误信封不携带 `meta`（OR 设计如此），错误路径按既有
  错误语义处理，不产生 `ontologyMeta`。
- `SkillExecution` 落库门槛（四字段非空后加 `callId`）未改，新增的读取期拒绝发生在更早位置；
  二者对"字段不全不得成为实际版本"同向，未做冗余移除以免影响既有运行记录。
- 异步结果端点、PROCESS 复制既有 `Raw` 元数据的路径不在本子步；后者不经过 HTTP `read`。
- 未做真实 OR→AP 联合回放（需要启动 OR/PG 与发布环境），留给 SEM-05.1 和统一验收。

## 7. 提交与回退

- 单次本地提交（AP 仓 `upgrade/20261103`），文件清单：`SkillAdapters.java`、`SkillRules.java`、
  `OntologyMetaProjectionTest.java`（新增）、本证据文件。未使用 `git add -A`，未推送、未打标签、未切分支。
- 回退：`git revert <commit>` 即整体复原；新增测试文件随回退移除，无数据迁移需回滚。
