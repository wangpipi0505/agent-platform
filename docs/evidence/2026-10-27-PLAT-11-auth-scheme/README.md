# PLAT-11 出站鉴权方式声明（T11 / PLAT-11）

> 结论先行：技能契约新增可选字段 `authScheme`（`BEARER` 默认 / `API_KEY` / `NONE`），注册期归一化并显式落库，
> 执行期由**同一个** `AuthScheme.headers(...)` 决定同步与异步两个HTTP出站点的真实请求头；
> 未声明即沿用改造前的 Bearer 行为，`NONE`+`authRef` 与未知取值在注册期以业务错误拒绝，
> 凭据解析不到时在**发请求之前**返回 401 `SKILL_CREDENTIAL_MISSING`（桩服务器实测 0 次调用）。

## 1. 问题与范围

改造前平台对外调用无条件发送 `Authorization: Bearer <vault密钥>`：

- 同步：`skill-registry/.../infra/SkillAdapters.java:227-228`（取值）、`:261`（发头）
- 异步：`skill-registry/.../infra/HttpAsyncAdapter.java:485-486`（取值 + 发头，第二份硬编码）

本体系统（ontorepo）消费路由只接受 `X-API-Key: ontopa_...`，因此路线三此前必须额外挂一个验证桥头做转换。
本卡把"鉴权方式"变成契约里可声明的字段，让同一个技能既能对接 Bearer 体系，也能直接对接 API-Key 体系。

**有意不做**：不建表、不加列。`authScheme` 与 `authRef` 走同一条契约 JSON 通道（`skill.skill_version.contract`），
不需要数据库迁移；`SkillExecution`、`engine-runtime`、`monitor`、`app-management`、`platform-gateway`、
`contracts/`、`pom.xml` 全部未改。

## 2. 契约与语义

| 取值 | 出站请求头 | authRef |
| --- | --- | --- |
| `BEARER`（缺省） | `Authorization: Bearer <SecretVault.resolve(authRef)>` | 可空；为空则**不发任何鉴权头**（改造前的匿名技能形态） |
| `API_KEY` | 只发 `X-API-Key: <SecretVault.resolve(authRef)>`，不得再发 `Authorization` | 必填 |
| `NONE` | 不发任何鉴权头 | 必须为空 |

不变量（实现见 `admin-common/.../auth/AuthScheme.java` 的类注释）：

1. **契约里能存的只有三个枚举名**。注册期归一化后一律显式落库，执行期不再依赖"缺字段=旧行为"。
2. **默认值必须是 `BEARER`**。本字段出现之前所有 HTTP 出站都是 Bearer；默认值只要偏离一次，存量技能就会被
   静默换掉对外凭据形态，远端 401 而平台侧没有任何"我改了什么"的记录。
3. **`NONE` 与 `authRef` 互斥**。声明不鉴权却留一份可解析凭据，只可能是配置遗留或声明写错；注册期拒绝比运行期
   默默忽略凭据更能暴露问题。
4. **凭据缺失必须在发请求前失败**。先发一次无鉴权请求再看远端报错，会把配置错误伪装成业务失败，还可能让
   不幂等的端点先执行一次副作用；因此同步适配器的这段逻辑刻意留在 `try` 之外，不会被 catch 折叠成
   "外部端点连接失败"。
5. **`API_KEY` 只对 HTTP 生效**。MCP（SSE transport 头）与 ALGORITHM（SDK `apiKey`+`Bearer` 前缀）的鉴权在
   各自适配器内实现，不在本字段管辖范围；允许它们声明 `API_KEY` 等于在契约里承诺一个不会兑现的行为，
   故注册期直接拒绝。

兼容窗口 `agent.skill.auth.legacy-bearer`（默认 `true`）：

- `true`：未声明字段的老契约归一化为 `BEARER`（升级瞬间行为不变）。
- `false`：未声明即拒绝注册（400 `SKILL_AUTH_SCHEME_REQUIRED`），逼迫重新保存/发布的契约写清鉴权方式。
- 开关**只作用于注册期**：已发布契约在读取侧永远按存储值执行，运行中改开关不会改变存量技能的出站行为。

错误码（`PlatformException`：HTTP status + code + 中文 userMessage，不是裸 `IllegalArgumentException`）：

| code | status | 触发点 |
| --- | --- | --- |
| `SKILL_AUTH_SCHEME_INVALID` | 400 | 取值不在三个枚举内（大小写必须一致，不接受猜测） |
| `SKILL_AUTH_SCHEME_REQUIRED` | 400 | `legacy-bearer=false` 时未显式声明 |
| `SKILL_AUTH_SCHEME_CONFLICT` | 400 / 409 | `NONE`+`authRef`（注册期 400；已存契约不一致 409）；`API_KEY` 用于非 HTTP 适配器（400，`INVALID`） |
| `SKILL_CREDENTIAL_MISSING` | 401 | `API_KEY` 无 `authRef`，或 `authRef` 指向的密钥不存在/无法解密 |

`SecretVault` 原有的租户越权语义 `403 SECRET_SCOPE_DENIED` 原样保留（不收敛成 401），避免把"越权"降级成"没配"。

## 3. 改动清单

```
 admin-common/.../common/auth/AuthScheme.java        | 181 +++++ (新增)
 admin-common/.../common/auth/AuthSchemeTest.java    | 236 +++++ (新增)
 skill-registry/.../application/SkillCatalog.java    |  40 +++++++++++++++++++++-
 skill-registry/.../domain/SkillRules.java           |  16 +++++++++
 skill-registry/.../infra/HttpAsyncAdapter.java      |  16 +++++++--
 skill-registry/src/main/.../infra/SkillAdapters.java|  12 +++++--
 skill-registry/.../application/SkillAuthSchemeRegistrationTest.java | 新增
 skill-registry/.../infra/AuthSchemeOutboundTest.java                | 新增
```

`git diff --stat`（仅本卡文件；`docs/evidence/2026-10-27-baseline/README.md` 的 4 行改动不是本卡的，未触碰）：

```
 .../skill/application/SkillCatalog.java            | 40 +++++++++++++++++++++-
 .../cn/agentplatform/skill/domain/SkillRules.java  | 16 +++++++++
 .../skill/infra/HttpAsyncAdapter.java              | 16 +++++++--
 .../agentplatform/skill/infra/SkillAdapters.java   | 12 +++++--
 4 files changed, 77 insertions(+), 7 deletions(-)
```

关键接线：

- `SkillCatalog` 新增 `@Autowired` 五参构造（`@Value("${agent.skill.auth.legacy-bearer:true}")`），四参构造委托
  `legacy-bearer=true`，既有 11 处单测构造点无需改动；`normalize()` 在 `authRef` 校验通过后把 scheme **显式写入**
  契约；`save()` 在旧编辑器省略该字段时从上一版本补回，避免一次无关局部保存把 `API_KEY` 静默降级成 `BEARER`。
- `SkillRules.authScheme(...)` 是注册期唯一入口（归一化 + 组合校验），目录层不自行比较字符串。
- `SkillAdapters.http` 与 `HttpAsyncAdapter.request` 都改为 `AuthScheme.headers(contract.get("authScheme"), authRef)`
  返回的头表逐个发送——两个出站点共用同一份映射，同一技能在 `SYNC` 与 `SUBMIT_QUERY` 下不可能发出不同凭据。
- `HttpAsyncAdapter.request` 由 `private` 放宽为包内可见（附注释说明原因）：鉴权头是安全相关行为，而
  `invoke()` 依赖 `skill.external_job_binding` 表，纯单测无法轻量到达；这是本卡在"共享热点"上的最小改动。

## 4. 测试证据

环境：`cd 代码/agent-platform && source .tools/env-load.sh`；`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot`
（注意 PATH 上的 `java` 仍是 17，但 `mvn -version` 显示 21.0.12，编译/测试用 21）；
`-Dmaven.repo.local=.tools/m2`，`-DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"`。

### 4.1 红阶段（先写测试，旧代码 10 条失败）

```bash
mvn -B -ntp -pl skill-registry test \
  -Dtest=SkillAuthSchemeRegistrationTest,AuthSchemeOutboundTest \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

```
[ERROR] Tests run: 12, Failures: 10, Errors: 0, Skipped: 0
```

失败原文（摘要）：`apiKeyIsNormalizedIntoAnExplicitContractValue:73 expected: <API_KEY> but was: <null>`；
`noneWithoutCredentialIsAccepted:81 expected: <NONE> but was: <null>`；
`undeclaredSchemeDefaultsToBearerWhileLegacyWindowIsOpen:110 expected: <BEARER> but was: <null>`；
`unknownSchemeIsRejectedInsteadOfSilentlyTreatedAsBearer:100` 与
`noneWithCredentialIsRejectedWithBusinessError:89`、`undeclaredSchemeIsRejectedOnceLegacyBearerIsTightened:116`、
`apiKeyIsRejectedForNonHttpAdapterBecauseOnlyHttpHonoursTheField:128`
均为 `Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.`；
`apiKeySendsOnlyXApiKey:132 expected: <ontopa_live_secret> but was: <null>`；
`asyncAdapterAppliesTheSameSchemeMapping:170 expected: <async-secret> but was: <null>`；
`unresolvableCredentialFailsWith401BeforeAnyRequest:158 Unexpected exception type thrown, expected:
<PlatformException> but was: <IllegalStateException>`（栈：`SecretVault.resolve(SecretVault.java:111)` ←
`SkillAdapters.http(SkillAdapters.java:228)` ← `NoSuchFileException`）。

红阶段同时有 2 条**兼容性**断言当时即通过（`undeclaredSchemeKeepsLegacyBearerForStoredContracts`、
`noneSendsNoAuthHeader`）——旧代码本来就不发头/本来就用 Bearer，正是本卡必须保住的存量行为。

### 4.2 绿阶段

```bash
mvn -B -ntp -pl skill-registry test \
  -Dtest=SkillAuthSchemeRegistrationTest,AuthSchemeOutboundTest \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ...SkillAuthSchemeRegistrationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ...AuthSchemeOutboundTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

覆盖的失败路径（对应任务书 1–5）：

1. `AuthSchemeOutboundTest.apiKeySendsOnlyXApiKey` —— `com.sun.net.httpserver.HttpServer` 桩收到 `X-API-Key:
   ontopa_live_secret`，且 `Authorization` 为 `null`。
2. `SkillAuthSchemeRegistrationTest.noneWithCredentialIsRejectedWithBusinessError` —— 400 `SKILL_AUTH_SCHEME_CONFLICT`，
   断言 `status()`/`code()`/userMessage 三要素。
3. `...unknownSchemeIsRejectedInsteadOfSilentlyTreatedAsBearer` —— `BASIC` → 400 `SKILL_AUTH_SCHEME_INVALID`。
4. `...undeclaredSchemeDefaultsToBearerWhileLegacyWindowIsOpen`（归一化 `BEARER`）+
   `AuthSchemeOutboundTest.undeclaredSchemeKeepsLegacyBearerForStoredContracts`（真实发 Bearer 头）+
   `...undeclaredSchemeIsRejectedOnceLegacyBearerIsTightened`（`legacy-bearer=false` → 400 `SKILL_AUTH_SCHEME_REQUIRED`）。
5. `AuthSchemeOutboundTest.unresolvableCredentialFailsWith401BeforeAnyRequest` —— 401 `SKILL_CREDENTIAL_MISSING`，
   桩服务器 `calls == 0`。

### 4.3 admin-common 字段语义单测

```bash
mvn -B -ntp -pl admin-common test -Dtest=AuthSchemeTest \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.287 s -- in cn.agentplatform.common.auth.AuthSchemeTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

如实记录：该类在实现完成后补写（红阶段证据以上面的 skill-registry 运行为准），首次运行有 1 条**测试自身**写错——
把 `" bearer "` 期望当作可解析，而实现是"trim 空白但严格大小写"，修正断言为 `" BEARER "` 后 9/9 绿；实现未因此改动。

### 4.4 相邻回归（未跑模块全量，理由见 §5）

```bash
mvn -B -ntp -pl skill-registry test -Dtest=OpenApiHttpMappingTest,SkillExecutionTest \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

```
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0 -- in ...SkillExecutionTest
[INFO] Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- in ...OpenApiHttpMappingTest
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`SkillExecutionTest` 走真实 `SkillCatalog.save()`→`adapters.invoke()` 全链路（含 OpenAPI 映射与凭据路径），
是本卡改动最直接的下游；未出现因"契约多了 authScheme 字段"导致的断言漂移。

运行 `admin-common` 主代码变更后，`skill-registry` 测试前已执行
`mvn -B -ntp -pl admin-common install -DskipTests -Dmaven.repo.local=.tools/m2`（BUILD SUCCESS），
确保 `.tools/m2` 中的 jar 含 `AuthScheme`。

## 5. 未覆盖项与有意限制

1. **执行层不会把 401 原样透传给前端**。`SkillExecution` 的 `catch (Exception ex)` 会把适配器抛出的
   `PlatformException(401)` 折叠为 `OUTPUT_INVALID`/`UNKNOWN`。本卡禁止改该类，因此"适配器层 401 + 零出站"已达成，
   "错误码一路上浮到调用方"未达成，需要后续卡在 `SkillExecution` 增加 `PlatformException` 直通分支。
2. **OpenAPI 导入与本体 manifest 导入不会写入 `authScheme`**（`OpenApiImports`/`OntologyManifestImports` 不在白名单，
   且它们按字段白名单组装契约）。经导入创建的技能一律归一化为 `BEARER`；要声明 `API_KEY` 需直接走技能注册 API
   （`authScheme` 与 `authRef` 同在契约 JSON 里），或在后续卡里给两条导入路径补字段。
3. **MCP/ALGORITHM 仍固定 Bearer**（`McpAdapter`、`AlgorithmAdapter` 不在白名单），且声明 `API_KEY` 会被注册期拒绝
   ——这是有意的"声明与行为一致"，不是遗漏。
4. **未跑模块全量**。`skill-registry` 存在 8F/112E 既存失败（Y-3：纯单测无 Spring → `ServiceTokens` 未登记 →
   403 被吞成 502），与本次改动无关；按纪律既不修也不以模块红断言本卡失败，只跑定向 + 相邻回归。
5. 未覆盖 `env:AGENT_SECRET_*` 引用的成功路径（单测只覆盖 vault 引用与失败路径）；该分支的租户绑定逻辑由
   `SecretVaultTest` 既有覆盖，`AuthScheme.resolve` 只做错误归一，不改变其语义。

## 6. 本体侧端到端还需什么（一句话）

本体侧无需再改代码即可被平台以 `X-API-Key` 调用——把该技能契约的 `authScheme` 显式声明为 `API_KEY`、`authRef`
指向 `ontopa_...` 所在的 `vault:` 引用，即可绕开验证桥头转换；但若该技能是经 OpenAPI/本体 manifest **导入**注册的，
还需要给导入路径补 `authScheme` 透传（见 §5-2），否则导入仍回落 `BEARER`。

## 7. 复现

```bash
cd 代码/agent-platform
source .tools/env-load.sh
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp -pl admin-common install -DskipTests -Dmaven.repo.local=.tools/m2
mvn -B -ntp -pl admin-common test -Dtest=AuthSchemeTest "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
mvn -B -ntp -pl skill-registry test -Dtest=SkillAuthSchemeRegistrationTest,AuthSchemeOutboundTest "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```
