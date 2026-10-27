# PLAT-08b 技能契约 Schema 方言（skill-registry 接入 SchemaDialect）

- 日期：2026-10-27
- 分支：`feat/v1.2-implementation`（未提交）
- 台账：T08b；前置 PLAT-08a/08d（`admin-common` 的 `SchemaDialect` 与 App 侧接入）
- 结论：技能契约 `inputSchema`/`outputSchema` 与 App 侧使用同一套方言；注册期归一后**落库的就是规范形**。

## 1. 红 → 绿

命令（在 `代码/agent-platform` 下，`.tools/env-load.sh` 已 source，显式 JAVA_HOME 为 JDK 21）：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl skill-registry test \
  -Dtest='SkillRulesTest,OpenApiImportsTest,SkillSchemaDialectTest' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
  -Dmaven.repo.local=.tools/m2
```

| 阶段 | 结果 |
| --- | --- |
| 红（`red.log`） | `Tests run: 23, Failures: 5, Errors: 0, Skipped: 0` + `BUILD FAILURE` |
| 绿（`green.log` 第一段） | `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` |
| 绿（`green.log` 第二段，相邻 schema 边界回归） | `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（`BoundedSchemaValidationTest` 11、`HttpSchemaBoundaryTest` 8、`McpSchemaBoundaryTest` 22） |

红阶段 5 个失败（改动前）：

1. `OpenApiImportsTest.convertsOpenApiNullableIntoTheCanonicalUnionInsteadOfRejectingIt:249 expected: <true> but was: <false>`（nullable 仍被 `OPENAPI_SCHEMA` 拒绝）
2. `SkillSchemaDialectTest.multipleConcreteTypesAreRejectedWithJsonPointer:105 Expected PlatformException to be thrown, but nothing was thrown.`（networknt 接受 `["string","object"]`，没有方言判定）
3. `SkillSchemaDialectTest.nullIsAlwaysMovedToTheEndOfTheUnion:99 expected: <[string, null]> but was: <[null, string]>`
4. `SkillSchemaDialectTest.nullableFlagIsAcceptedAndStoredInTheSingleCanonicalForm:92 expected: <[string, null]> but was: <string>`
5. `SkillSchemaDialectTest.remoteReferenceIsRejectedWithJsonPointer:123 expected PlatformException but was: IllegalArgumentException`

说明：`unionWithNullIsRegisteredAndReadBackAsArrayType` 与 `legacyObjectSchemaKeepsWorkingUnchanged` 在红阶段即通过（前者原本就被 networknt 原样接受、后者是回归护栏），红例以其余 4 条为准。
只跑定向测试，未跑模块全量：本模块另有 9 例 `ServiceTokens`/`deploymentMode` 相关既存失败（Y-3），不属于本卡。

## 2. 改动文件

生产代码（均在 `skill-registry/src/main`，未触碰其它模块/`admin-common`）：

- `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java`（仅 schema 段 501-517）
- `skill-registry/src/main/java/cn/agentplatform/skill/application/OpenApiImports.java`（schema 转换器 + convert 末尾；路径与卡片写的 `infra/` 不同，以仓库实际布局为准）

测试：

- 新增 `skill-registry/src/test/java/cn/agentplatform/skill/domain/SkillSchemaDialectTest.java`（6 例）
- 修改 `skill-registry/src/test/java/cn/agentplatform/skill/application/OpenApiImportsTest.java`（+1 例）

## 3. 关键实现点

### 3.1 校验归属：skill-registry 不再有第二份 type 规则

- `SkillRules.normalizedSchema(Object)` 成为注册期唯一入口：`SchemaDialect.normalize` 改写 → `SchemaDialect.validate` 一次性收集全部方言错误 → 再 `build` 交给 networknt 编译并做 `SchemaWorkBudget`/正则预算。
- `schema` / `mcpSchema` 都委托它：`mcp` 布尔参数原本从未参与判定（死参数），已随 `prepare(raw)` 删除；MCP 与 HTTP/ALGORITHM/PROCESS 不再有两套标准。
- `SkillCatalog.normalize` 删除 `if (type.equals("MCP")) mcpSchema else schema` 分支，所有适配器同一入口，并把返回的规范形写回 `result.put("inputSchema"/"outputSchema")`。
- `OpenApiImports` 不再自己拒绝 `nullable`：转换器只保留原始布尔，`convert` 末尾统一 `SkillRules.normalizedSchema(...)`，导入器不复制可空语义，也不引入 `$ref` 解析（远程/文件/相对 `$ref` 仍在 `bound()` 入口拒绝，`ParseOptions` 仍全关）。

### 3.2 落库前后差异

`SkillCatalog.create/save` 的落库值就是 `normalize` 的返回值（`Json.write(contract)` → `skill_version.contract` jsonb，摘要 `Digest.sha256(Json.canonical(contract))`），因此：

| 输入写法 | 改动前落库 | 改动后落库 |
| --- | --- | --- |
| `{"type":["string","null"]}` | 原样（能存） | `{"type":["string","null"]}`（不变） |
| `{"type":"string","nullable":true}` | 原样存（技能侧未判定） | `{"type":["string","null"]}`，`nullable` 消失 |
| `{"type":["null","string"]}` | 原样存 | `{"type":["string","null"]}`（null 恒在最后） |
| `{"type":["string","object"]}` | 能存（networknt 接受） | 400 拒绝，带 `/<pointer>` |
| `{"$ref":"https://..."}` | 能存（networknt loader 报错被吞成通用消息） | 400 拒绝，带 `/$ref` 与 URL |

`OpenAPI` 导入的 `nullable: true` 同样在 preview 阶段就变成 `[T,"null"]`：preview 看到的就是 `catalog.create` 落库的形状。

### 3.3 错误码与前缀

- schema 注册失败沿用既有 **400 `INVALID_INPUT`**（改动前 `SkillRules.schema` 抛 `IllegalArgumentException`，由 `PlatformErrors` 统一映射为 400 `INVALID_INPUT`）。唯一变化：以前 message 被吞成固定文案「请检查必填参数及数据格式」，现在经 `PlatformException(400,"INVALID_INPUT", ...)` 原样返回，pointer 才能到达前端。
- 消息保留 `Schema` 关键字（前端依赖），方言错误聚合后追加在尾部，例如：
  `Schema方言无效（不接受多具体类型联合与远程引用）: /type: 联合类型只允许单一类型加null（如["string","null"]），实际为["string","object"]`
  `Schema方言无效（不接受多具体类型联合与远程引用）: /$ref: 只允许本地引用（#/...），远程引用已拒绝: https://evil.example/x.json`
- 其它既有错误码（`INVALID_SKILL`/`INVALID_SECRET_REF`/`INVALID_GUARD` 等）未改；`SkillRules.validate` 仍抛 `IllegalArgumentException`，未改异常类型。

### 3.4 与卡片措辞的一处偏差（需编排者知悉）

卡片红例 2 写「`nullable:true` … 读回 `nullable:true`」；但 PLAT-08a 已定的规范形是「nullable 只是输入写法，归一后必然消失」，`SchemaDialect.normalize` 会把 `nullable:true` 改写成 `type:[T,"null"]`。本轮 `admin-common` 禁改、也不允许在技能侧再写一份可空语义，因此按规范形实现：**接受 `nullable:true` 写法，落库/读回为 `{"type":["string","null"]}` 且无 `nullable` 键**。语义等价，但 JSON 形状与卡片字面不同，测试按规范形断言。

## 4. 未覆盖 / 风险

1. **MCP 远端工具目录准入变严（未改，需拍板）**：`skill-registry/src/main/java/cn/agentplatform/skill/infra/BoundedMcpSseTransport.java:474` 也调用 `SkillRules.mcpSchema`，现在同样走方言。远端 MCP 工具若声明 `type:["string","number"]` 这类多具体类型联合，会在 discovery 阶段被整台拒绝（外层固定文案「MCP工具Schema不可安全解析」），而不是只拒绝该工具。若要保留远端发现的旧宽容度，需要给远端发现单独一个准入方法（本轮按"只做这一件事"未改 transport）。
2. **运行期 `SkillRules.validate` 只编译已存 schema、不重跑方言**：这是有意的（执行期不该每次深拷贝 + 重新判定）。已落库契约都是规范形；但若有人绕过 API 直接写库塞入 `type:["string","object"]`，执行期不会报方言错，只按 JSON Schema 语义执行。属"不顺手改"。
3. **OpenAPI 3.1 的 `type` 数组仍不支持**：导入仍只支持 3.0.x（既有 `OPENAPI_VERSION` 限制），转换器要求单一 `type`；`nullable`（3.0 写法）已支持，3.1 的 `type:["string","null"]` 不在本轮范围。
4. `SkillCatalog.processPrecheck` 对 PROCESS 的 `outputSchema` 仍调 `SkillRules.schema`（现含方言判定）。因为落库已是规范形，行为不变；但若历史 PROCESS 契约含 union，重新发布时会失败——API 侧历史上只在 MCP 分支校验，理论上可能存在此类存量行（未迁移，未查库验证）。
5. 红例 1（`["string","null"]`）改动前就能存，属回归护栏而非真红例；真正的行为变化是例 2/3/4。
6. 未跑模块全量（9 例 Y-3 既存失败）；额外跑了 3 个相邻 schema 边界测试类（41 例全绿）作为回归证据。

## 5. 未提交

本轮**未 commit、未 `git add`**。工作树中另有其他协作者的 `web/**` 改动与证据文件，与本卡无关。
