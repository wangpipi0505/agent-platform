# D3 路线三（HTTP 能力导入）真实规格验收 —— space-ground-visible

日期：2026-10-30。分支 `feat/v1.2-implementation`，HEAD `3280cb69`。本目录只含证据，不改生产代码。

## 结论

1. 用真实业务规格（43 paths / 51 operations，585,649 字节）跑通了 `OpenApiImports.preview → create`：选中 5 个 GET 只读操作，全部产出一个 `type=HTTP` / 归一化后 `SYNC` 的技能草稿，`httpMapping` 键集恒为 `{path,query,body}`，`inputSchema.properties` 与 path/query 参数一一对应。
2. **平台并不从 GET 推导 `readonly`**：`execution` 是 `create` 的运营显式输入（`OpenApiImports.java:124` 原样拷贝），本测试显式声明 `readonly=true` 后用真实 `SkillCatalog.normalize` 断言其归一化为 `riskLevel=READ_ONLY`——这才是"只读契约合法"被平台承认的地方。
3. 真实转换率只有 **16/51**：35 条操作被转换器拒绝（`pattern`/`oneOf`/`anyOf`/`allOf`/非 JSON 成功响应等），这是路线三当前的真实能力边界，已按实现如实断言并留证（下方分类表）。
4. 生成契约对本机已运行的 `space-ground-visible` 发起真实 GET：`/api/v1/space/health` 返回 `200` + `application/json` + `{"status":"UP"}`。"已跑"。

## 输入与可复现性

| 项 | 值 |
| --- | --- |
| 规格文件 | `G:\5-YG\space-ground-visible\docs\openapi\openapi.json` |
| 大小 / 摘要 | 585,649 B / `sha256=d40362dab2c0590015e1d368634e13b70ae1fa2ca62f3b544185122c191bc460` |
| 服务 | `http://127.0.0.1:8080`（`/api/v1/space/health` → `{"status":"UP"}`） |
| 测试类 | `skill-registry/src/test/java/cn/agentplatform/skill/application/SpaceGroundOpenApiImportTest.java` |
| 规格路径覆盖 | `-Dspace.ground.spec=<path>`，文件缺失时整类 `Assumptions.abort`（不伪造输入） |

## 选中的 5 个只读操作

| operationId | 方法 + 路径 | httpMapping `{path, query, body}` | inputSchema 属性 / required |
| --- | --- | --- | --- |
| `operation01` | GET `/api/v1/space/health` | `{[], [], ""}` | `{}` / `[]` |
| `operation03` | GET `/api/v1/space/satellites/page` | `{[], [pageNumber,pageSize,keyword], ""}` | `pageNumber:integer(min 1)`、`pageSize:integer(min 1,max 200)`、`keyword:string` / `[]` |
| `operation05` | GET `/api/v1/space/satellites/{satelliteId}/payload` | `{[satelliteId], [], ""}` | `satelliteId:integer` / `[satelliteId]` |
| `operation17` | GET `/api/v1/space/visibility/tasks/{taskId}` | `{[taskId], [], ""}` | `taskId:string` / `[taskId]` |
| `operation27` | GET `/api/v1/ballistic/files/{fileId}/preview` | `{[fileId], [page,limit], ""}` | `fileId:string`、`page:integer(min 1)`、`limit:integer(min 1)` / `[fileId]` |

形状覆盖：无参、纯 query、纯 path、path+query 四种；path 参数恒为 `required`，query 参数在本规格全部可选（`required=[]`）。

## 生成契约摘要（`create` 实际捕获到的 5 份契约，全文见 `contracts.txt`）

```
drafts=5
warnings=[导入仅生成可编辑DRAFT；真实连接测试与模型选型验收通过后才能发布]
type=HTTP  httpMethod=GET  authRef=''  timeoutMs=30000
execution={readonly=true, idempotent=true, sideEffect=false, cancellable=false}
endpoint=http://127.0.0.1:8080 + 规格 path（模板变量保留，执行期由 OpenApiHttpMapping.request 填充）
importSource={kind=OPENAPI, operationId, method, path, documentSha256}
```

- `outputSchema` **有导入**：取唯一 2xx `application/json` schema 并归一化（nullable 改写成 `[T,"null"]`）。例：health 为 `{type=object, required=[status], properties={status={type=string, enum=[UP]}}}`；satellites/page 的 `records[]` 达 1,590 字符；ballistic/preview 的二维 `rows` 为 `array<array<string>>`。
- 归一化（真实 `SkillCatalog.normalize`，不写库）：`invocationKind=SYNC`、`riskLevel=READ_ONLY`、`authScheme=BEARER`（未声明时的兼容默认，规格本身未声明 security，故 `authRef=""`）。
- 声明式映射可执行：`OpenApiHttpMapping.request(contract, args)` 产出的 URI 无残留 `{}`；无参操作无 `?`。

## 测试与红/绿

测试类共 4 条（`green-run.txt` 为原始输出，`green-surefire-report.txt` 为 surefire 报告）：

1. `previewsRealSpecAndConvertsFiveGetOperationsToMappingConsistentSchemas` —— 51 操作全解析、operationId 唯一、5 个选中操作 `supported=true`、映射/属性/required/标量类型逐项对应，并锁定 `supported=16`（防能力边界悄悄漂移）。
2. `createProducesLegalReadonlyHttpContractsThatCatalogNormalizes` —— 5 份契约的 `type=HTTP`、`httpMethod=GET`、endpoint、code、`authRef=""`、`timeoutMs`、映射；再经真实 `SkillCatalog.normalize` 断言 `SYNC`/`READ_ONLY`/`execution.readonly=true`，并用 `OpenApiHttpMapping.request` 证明可拼出合法 URI。
3. `writeOperationsAreNotSelectedEvenWhenConvertibleAndUnsupportedOnesAreRejected` —— 写操作/不可转换操作的边界（见下节）。
4. `generatedHealthContractReachesTheRunningService` —— 真实网络 GET（3s 超时），服务未起时 abort。

### 绿（可复现命令的原始输出）

```
[INFO] Running cn.agentplatform.skill.application.SpaceGroundOpenApiImportTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.528 s -- in cn.agentplatform.skill.application.SpaceGroundOpenApiImportTest
[INFO] Results:
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`Skipped: 0` + surefire XML 中 `generatedHealthContractReachesTheRunningService` 有耗时无 `<skipped/>`，说明真实 GET 执行了、没有走 abort 分支。

### 红（反证：证明"该操作不可转换"是事实，不是终版测试迁就实现）

初版选型假设 `operation20`（GET `/api/v1/space/visibility/task-results`，纯 query 分页查询）可转换，对真实规格运行后转红。该反证测试作为 scratch 只存在于 `target/red-proof/`（不提交），源码留档 `red-proof-source.java`，实跑输出 `red-surefire-report.txt`：

```
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.463 s <<< FAILURE! -- in cn.agentplatform.skill.application.SpaceGroundOpenApiImportRedProofTest
org.opentest4j.AssertionFailedError: diagnostics=[不支持Schema关键字: pattern] ==> expected: <true> but was: <false>
```

据此终版改用 `operation17`，并把"真实规格的哪些形态会被拒"固化为断言（而非删掉失败用例）：

- `operation11` POST `/api/v1/space/targets/query`：方法名是查询，但请求体 Schema 带 `pattern` → `supported=false`，诊断含 `pattern`；不在只读选择里。
- `operation25` POST `/api/v1/ballistic/files`（multipart 上传）：`supported=true`、映射键集 `{path,query,multipart}`（**不是** `body`）——平台只校验形态、不替运营判断只读性；只读边界由选择过滤守，故断言它绝不出现在 `SELECTED`。
- `operation28` GET `/api/v1/ballistic/files/{fileId}/download`：成功响应不是单一 `application/json` → `supported=false`；`create` 以 `OPENAPI_UNSUPPORTED` 拒绝整批（`catalog.create` 一次都没被调用）。
- 选中不存在的 `operation99` → `OPENAPI_SELECTION`。

## 规格覆盖：16/51 可转换，35 条拒绝（全量分组见 `unsupported-diagnostics.txt`）

| 条数 | diagnostics | operationId |
| --- | --- | --- |
| 10 | 不支持Schema关键字: pattern | operation02, 08, 09, 11, 20, 21, 22, 23, 24, 29 |
| 11 | 不支持Schema关键字: oneOf | catalogHistory, catalogBatch, catalogRows, catalogRevalidate, catalogRollback, catalogItems, catalogDelete, catalogItem, catalogEdit, catalogRestore, catalogChanges |
| 6 | 不支持Schema关键字: anyOf | operation14, 15, 16, 18, 31, 33 |
| 1 | 不支持Schema关键字: allOf | operation13 |
| 5 | 成功响应必须为单一application/json，不能为空或多媒体 | operation28, 30, 32, catalogTemplate, catalogErrorsCsv |
| 2 | 仅支持默认序列化的path/query标量参数 | catalogUpload, catalogCommit |

## 真实 GET 结果（已跑）

用 `preview` 生成的 `operation01` 契约（`httpMapping` + `inputSchema` 原样消费）拼 URL，`HttpClient` 3s 超时：

```
GET http://127.0.0.1:8080/api/v1/space/health
HTTP/1.1 200
Content-Type: application/json
{"status":"UP"}
```

旁证（同类只读、带 query 的操作，人工 curl 复核上游契约与本机数据可用）：`GET /api/v1/space/satellites/page?pageNumber=1&pageSize=1` → `200`，`{"total":5414,"pageNumber":1,"pageSize":1,"records":[{"id":1,...}]}`。响应是上游原样 JSON，不是平台统一信封——路线三直接暴露既有契约，`returnSpec.structuredPath` 需运营按此填。

## 未覆盖项（有意留白，不是遗漏）

- **鉴权头**：本规格未声明 `security`，故 `authRef=""`、`authScheme` 走兼容默认 `BEARER`。未验证 `OPENAPI_AUTH_REQUIRED` 分支（仅当 `op.requiresAuth` 为真才触发，`OpenApiImports.java:149`），也未用假 vault ref 去撞 `SecretVault.resolve`——假 ref 只会证明"解析失败"，掩盖"此规格无需凭据"。
- **写操作**：multipart 上传（operation25）只验证到"可转换、未被选入"；`create` 一个写契约、真实调用、幂等/取消语义均未验证。JSON body 的写操作（operation06/26/34/35）虽 `supported=true`，同样未导入。
- **分页**：`pageNumber/pageSize` 只是透传的 query 参数，平台侧无游标/offset 语义、无翻页编排、无 `total` 提取；`outputSchema` 保留了 `total/pageNumber/pageSize/records` 形状但无人消费。
- **错误码映射**：`errorSemantics` 是手工占位 `[{code: PARAM_ERROR, retryable:false}]`，未从规格 `responses` 推导（导入器不读错误响应），上游 4xx/5xx 到七字段语义的映射未验证。
- **`pattern`/`oneOf` 等关键字**：转换器直接拒绝而不是降级——这是保守选择（宁可少导入也不产出错误校验），未尝试改造。
- **多 2xx 响应**：规格中若有多个不同 2xx schema 的操作会以 `OPENAPI_RESPONSE` 拒绝，本次选中操作均为单 2xx，未专门构造。

## 偏差

1. 任务描述说"`readonly` 为 GET 推导、`authRef` 必填否则 `OPENAPI_AUTH_REQUIRED`"。**实现不是这样**：`execution` 与 `authRef` 都来自 `overrides[id]`；`authRef` 只在 `op.requiresAuth=true` 时必填。测试按实现断言，并在类注释里写明该差异。
2. 任务示例中的 health/orbit/visibility 类操作里，`operation30`（`/api/orbit/health`）因成功响应非单一 JSON 不可转换，故 health 选的是 `operation01`。
3. 指标口径：`create` 只写 DRAFT 且 `warnings` 恒非空（导入不等于可发布），测试未触发发布流程。
4. 没有 git 提交、没有 `git add -A`；`target/red-proof`、`target/probe` 均为 gitignore 覆盖的临时目录。

## 复现

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
cd 代码/agent-platform
mvn -B -ntp -o -Dmaven.repo.local="$PWD/.tools/m2" \
  -pl skill-registry test -Dtest=SpaceGroundOpenApiImportTest
```

`space-ground-visible` 未启动时第 4 条测试 abort（计入 skipped），前 3 条仍应全绿；规格文件缺失时整类 skipped。反证测试需手工 `javac` 到 `target/test-classes` 后 `surefire:test -Dtest=SpaceGroundOpenApiImportRedProofTest`（源码见 `red-proof-source.java`），跑完须删除其 class 以免污染整包测试。
