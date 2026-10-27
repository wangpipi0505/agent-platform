# PLAT-08a：Schema 方言归一（admin-common `SchemaDialect` + app-management 预检复用）

日期：2026-10-27　基线：`1808d3c`　泳道：admin-common + app-management　卡片：`文档/优化方案/派发卡片-B1-20261027.md` 卡片 7
背景：`文档/优化方案/平台侧优化方案与实施细则-20261027.md` §4.8（PLAT-08）

## 1. 动机

同一份「可空字段」意图，在本仓此前必须写成两种互斥写法才能同时通过两侧校验：

- 技能侧（networknt 校验器 + `SkillRules`）要接受 JSON `null`，schema 必须写成 `"type": ["string", "null"]`；
  只写单字符串 `"string"` 时 null 会被判非法。
- 应用侧 `AppService.validateSchema` 只接受单字符串 `type`，遇到 `["string","null"]` 直接抛
  `Schema缺少有效type`；`OpenApiImports` 又明确拒绝 `nullable`（属 T08b 范围，本卡未动）。

结果是「路线一」的状态响应里 `errorMessage` 这类可空字段，两侧无法用同一种写法表达：为了让技能侧接受 null
只能退化响应结构。PLAT-08 的做法是提供一个统一规范化器：把 `type` 数组与 OpenAPI 风格的 `nullable` 布尔
都收敛到同一规范形（`[T,"null"]`，null 恒在末位），应用预检与技能注册都复用同一套规则与同一套错误定位。

本卡只做前半：`admin-common` 的 `SchemaDialect` 与 `app-management` 的 `AppService.validateSchema` 复用；
`SkillRules` / `OpenApiImports` / 任何 skill-registry 文件由后续卡片（T08b）接入，本卡未改。

## 2. 改动文件

| 文件 | 改动 |
| --- | --- |
| `admin-common/src/main/java/cn/agentplatform/common/schema/SchemaDialect.java` | 新增。`normalize(JsonNode)` / `validate(JsonNode)` / `MAX_DEPTH=64` |
| `admin-common/src/test/java/cn/agentplatform/common/schema/SchemaDialectTest.java` | 新增。25 个用例（卡片 8 条 + 边界） |
| `app-management/src/main/java/cn/agentplatform/app/application/AppService.java` | 仅 `validateSchema` 及其可见性/注释/import；业务调用点 `typeChecks`、`referenceChecks` 未改 |
| `app-management/src/test/java/cn/agentplatform/app/application/AppSchemaDialectTest.java` | 新增。10 个用例，不连数据库 |
| `docs/evidence/2026-10-27-PLAT-08a-dialect/` | red/green 日志、契约抽查脚本与结果、本 README |

未改：`SkillRules`、`OpenApiImports`、skill-registry 全部文件、前端、迁移、`pom.xml`、`AppRules`。

## 3. 归一规则（接受 / 拒绝）

规范形只有两种：单类型字符串 `"T"`，或 `[T,"null"]`（`null` 必须在最后）。`nullable` 只是输入写法，
归一后必然消失。

`normalize` 是宽容的纯函数：只改写能无歧义归一部分，非法片段原样深拷贝保留（不抛异常、不静默改写，
以免丢定位信息），并保证幂等、不修改入参。`validate` 是方言的唯一判定源，返回**全部**错误（不是抛首个），
每条以 JSON Pointer 开头。

### 接受

| 输入 | normalize 输出 | 说明 |
| --- | --- | --- |
| `{"type":"string"}` | 原样 | 单类型 |
| `{"type":["string","null"]}` | 原样 | 已是规范形 |
| `{"type":["null","string"]}` | `{"type":["string","null"]}` | 统一把 null 排到最后 |
| `{"type":["string"]}` | `{"type":"string"}` | 单元素数组折叠 |
| `{"type":["null"]}` | `{"type":"null"}` | 仅 null |
| `{"type":"string","nullable":true}` | `{"type":["string","null"]}` | OpenAPI 写法 |
| `{"type":["string","null"],"nullable":true}` | 删 `nullable`，type 不变 | 等价写法叠加 |
| `{"type":"string","nullable":false}` | 删 `nullable`，type 不变 | 空操作 |
| `{"$ref":"#/$defs/body"}`、`{"$dynamicRef":"#/$defs/x"}` | 原样 | 只允许本地引用 |
| 布尔 Schema：根 `true`、`properties.x: false` | 原样 | JSON Schema 2020-12 合法 |
| 未知关键字 `x-vendor` 等 | 原样、不递归、不拒绝 | 兼容厂商扩展；内部非法方言也不报错 |
| 无 `type` 的嵌套节点 | 原样 | draft-07 允许，旧应用实现会拒绝（见 §5 放宽） |
| `dependencies` 的数组值 | 原样、跳过 | draft-07 属性依赖，不是子 Schema |

递归位置与 `SchemaWorkBudget` 的 applicator 集保持一致：`$defs`、`definitions`、`properties`、
`patternProperties`、`dependentSchemas`、`dependencies`（仅对象值）、`allOf`、`anyOf`、`oneOf`、`prefixItems`、
`items`（对象或数组）、`additionalItems`、`additionalProperties`、`contains`、`propertyNames`、`not`、`if`、
`then`、`else`、`unevaluatedItems`、`unevaluatedProperties`、`contentSchema`。

### 拒绝（`validate` 返回错误，消息带 Pointer）

| 输入 | 错误指针示例 | 消息要点 |
| --- | --- | --- |
| `{"type":[]}` | `/type` | `type数组不能为空` |
| `{"type":["string","string"]}` | `/type` | `type数组不能有重复项` |
| `{"type":["string","object"]}` | `/properties/errorMessage/type` | `联合类型只允许单一类型加null（如["string","null"]）` |
| `{"type":"text"}` | `/properties/a/type` | 未知 type，列出 7 个合法值 |
| `{"type":["string",3]}` | `/type/1` | `type取值必须是object/array/string/integer/number/boolean/null` |
| `{"type":3}` | `/type` | `type必须为字符串或字符串数组` |
| `{"nullable":true}`（无 type） | `/nullable` | `nullable:true需要同时声明type（等价于 type:[T,"null"]）` |
| `{"type":["string","null"],"nullable":false}` | `/nullable` | `nullable=false与type包含"null"冲突` |
| `{"nullable":"yes"}` | `/nullable` | `nullable必须为布尔值` |
| `{"$ref":"http://example.com/s.json"}` | `/properties/body/$ref` | `只允许本地引用（#/...），远程引用已拒绝: <url>` |
| `{"$ref":123}` | `/properties/body/$ref` | `引用必须为字符串` |
| `{"properties":{"a":"string"}}` | `/properties/a` | `子Schema必须为对象或布尔值` |
| 根为字符串/数组/null | `/` | `Schema必须为对象或布尔值` |
| 嵌套深度 > 64 | `/...` | `Schema层级超过64`（normalize 该层原样保留） |

指针按 JSON Pointer 转义：属性名里的 `/` → `~1`、`~` → `~0`；根指针显示为 `/`；
数组元素定位到下标，如 `/type/1`、`/allOf/0/type`。

### AppService 复用的分层

`AppService.validateSchema` 的顺序：`Json.tree(raw)` → `SchemaDialect.normalize` →
`SchemaDialect.validate` → 叠加 App 侧既有根约束（根必须是对象且声明 `type`）。

- 错误全部聚合后一次抛出，消息保留旧前缀 `Schema缺少有效type: `，后面拼 `pointer: message`。
  既有调用方按前缀识别，不需要新协议；`check()` 仍把异常记录为预检该组的 ERROR 而不是中断整个 precheck。
- `Json.tree` 失败时用同一前缀包装并**保留 cause**，不吞异常。
- 旧实现对每个 `properties`/`items` 子 Schema 都强制显式 `type`；新实现只强制根，嵌套无 `type` 合法。
  这是有意的放宽（JSON Schema 本来就允许），且仓内契约样例均显式声明 type，实际数据行为不变。
- `validateSchema` 改为包级可见，仅为同包单测直接覆盖；`typeChecks` / `referenceChecks` 两个业务调用点未变。

## 4. 兼容性证据（旧 schema 归一后树相等）

1. `SchemaDialectTest.legacySchemaIsUnchangedByNormalization`：含 `required`、`additionalProperties`、
   `enum`、多层 `array/items/object`、`$defs` 的旧 schema，`normalize` 结果与 `Json.tree` 逐字段相等，
   `validate` 无错误。
2. `AppSchemaDialectTest.legacySchemaNormalizesToTheSameTreeAsJsonTree`：应用侧同样的旧 Map 写法，
   `Json.tree(map)` 与 `normalize(Json.tree(map))` 相等且 `validate` 为空——即应用预检接收的旧配置树
   归一前后完全一致。
3. 真实契约抽查（`compat-check.jsh`）：对 `.local/contracts/*.json` 里 6 份技能契约的 12 个
   `inputSchema`/`outputSchema`（只含单字符串 type、items、properties、additionalProperties，无 nullable）
   做 normalize + validate，结果原文：

   ```
   contracts=6 schemas=12 changed=0 invalid=0
   ```

   即真实存量 schema 归一后零差异、零错误。
4. 旧错误前缀 `Schema缺少有效type` 保留，且全仓除 `AppService` 外无代码/前端依赖该消息文本
   （已 grep 确认），调用方无需改动。
5. 旧实现的接受集是本实现接受集的子集：本卡只新增「联合数组 / nullable」写法与布尔 Schema，
   未删除任何旧接受的写法；唯一放宽是嵌套子 Schema 允许省略 `type`（§3 末）。

## 5. 测试与红绿证据

证据目录同目录下：`red.log`、`red-app-management.log`、`install.log`、`green-admin-common.log`、
`green-app-management.log`、`full-app-management.log`、`compat-legacy-contracts.log`、`compat-check.jsh`。

红灯（实现前）：

- admin-common：`SchemaDialect` 为抛 `UnsupportedOperationException("SchemaDialect.normalize/validate 待实现")`
  的桩，`Tests run: 25, Failures: 0, Errors: 25, Skipped: 0`。
- app-management：只把 `validateSchema` 改可见性、仍用旧实现，`Tests run: 8, Failures: 5, Errors: 0`
  （联合数组与 nullable 被旧逻辑拒绝、错误消息无 pointer）。

绿灯（实现后）：

```
# admin-common（先 install 一次，供 app-management 与并行的 skill-registry 使用）
mvn -B -ntp -pl admin-common install -DskipTests -Dmaven.repo.local=.tools/m2
mvn -B -ntp -pl admin-common test -Dtest=SchemaDialectTest "-DargLine=-Xmx1024m" -Dmaven.repo.local=.tools/m2
# Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS

# app-management（卡片验收命令；AppDraft*Test 目前不存在，surefire 不匹配也不致失败）
mvn -B -ntp -pl app-management test -Dtest='AppSchema*Test,AppDraft*Test' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
# Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS
```

全量回归（与本次改动无关的既有失败，未修）：

- `app-management` 全量：`Tests run: 62, Failures: 4, Errors: 0, Skipped: 37`。4 个失败都在需要独立 PG 的
  测试基座上（`IntegrationPolicyDatabaseTest`、`IntegrationPolicyResolverHttpTest.start:62`、
  `PublishedMetadataHttpTest.start:50`、`PublishedPayloadHttpTest.start:91`），属 Y-4 已知测试基座问题。
- `admin-common` 全量：`Tests run: 356, Failures: 1, Errors: 6, Skipped: 0`；7 个失败全部在
  `ServiceTokensDeploymentModeTest`，原因是 `java.nio.charset.MalformedInputException: Input length = 1`
  （`Files.readString` 读 GBK 文件，Windows 编码类既有问题），与 schema 无关；同轮 `SchemaDialectTest`
  25/25 通过。

红绿口径说明：`red.log`/`red-app-management.log` 是首次红灯时的快照（8 个 app 用例、5 红）。首次绿灯后
又补了两个用例（旧 schema 树相等、非对象根 pointer），它们调用的 `SchemaDialect.normalize` 与新的
`IllegalArgumentException` 语义在旧实现下都不可能通过，因此未重跑红灯，绿色结果以最终一次运行
（10/10）为准，日志已覆盖同名文件。

## 6. 未覆盖 / 风险

- **运行期仍不认联合类型**：`AppRules.validateValue`（`app-management/.../domain/AppRules.java`）按
  单字符串 `type` 做取值校验，预检通过 `["string","null"]` 的 schema，在应用运行期判值时的行为未改、
  也未验证（该文件不在本卡白名单）。这是本卡最大的残留风险，需要单独立卡。
- **技能侧尚未接入**：`SkillRules` / `OpenApiImports` 未改（T08b），因此「路线一状态响应不再被迫退化」
  目前只在应用预检侧成立；两侧共用同一规范形要等 T08b 调用 `SchemaDialect`。
- **OpenAPI `nullable` 转换**：`OpenApiImports` 仍直接拒绝 `nullable`（T08b 范围），本卡只让**已入库**的
  schema 接受 `nullable` 写法。
- **远程 `$ref` 收紧**：旧实现只检查每层 `type`，不识别 `$ref`；现在远程引用会带 pointer 拒绝（卡片要求）。
  存量 6 份契约无远程引用，外部技能契约若含远程引用会在预检失败，属有意收紧。
- **本地 `$ref` 不解析**：只校验 `#` 前缀，不验证目标是否存在（引用解析留给 networknt/技能侧）；
  将来若要放行同源 schema 仓库需另行设计。
- **多具体类型联合按设计拒绝**：JSON Schema 本身允许 `["string","integer"]`，产品口径只允许
  「单一类型 + null」。是否永久拒绝待拍板。
- **`nullable` 无 `type` 被拒绝**：部分 OpenAPI 转换器可能产出这种写法，本卡选择明确报错而非猜测类型。
- **深度上限 64**：normalize 超限层原样保留、validate 报错，与 `SchemaWorkBudget` 准入一致；更深的合法
  schema 会被拒绝（既有约束）。
- **布尔 Schema** 在 admin-common 接受，但 App 根仍要求对象（旧约束保留），两者口径差异已在 §3 注明。
- 未做端到端（web/API）验证：本卡单测覆盖 `validateSchema` 与归一器，未启动完整服务跑真实发布流。

## 7. 待拍板

1. T08b 接入技能侧时，是否同样强制「根必须是对象且声明 type」？当前 `SchemaDialect` 不强制，App 侧自行叠加。
2. `AppRules` 运行期是否支持联合类型/nullable？若不支持，预检放行会与运行期判值不一致，需要排期。
3. `["string","integer"]` 这类多具体类型联合是否永久拒绝（当前按卡片拒绝并给 pointer）。
4. 远程 `$ref` 是否永久拒绝，还是允许同源/白名单地址（影响 OpenAPI 导入与技能注册）。
5. 全量回归里的 4 个 PG 依赖失败与 7 个 `ServiceTokensDeploymentModeTest` 编码失败，是否由 Y-4/环境卡
   统一收口，避免后续卡片重复误判。
