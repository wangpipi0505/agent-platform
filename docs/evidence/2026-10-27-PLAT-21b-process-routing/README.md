# PLAT-21b 证据：把 PROCESS 执行方式接进技能目录与适配器

- 日期：2026-10-27
- 范围：`skill-registry` 单模块（允许白名单内 3 个主代码/配置文件 + 2 个新测试类 + 本目录）
- 状态：测试全绿；**未** `git add` / `commit` / `push`
- 前置：`4be5715` 已交付 `ProcessContract.parse(JsonNode)`、`ProcessTree`、`ProcessAdapter.execute(ProcessContract) -> Result`（`@Component`，读 `agent.process.enabled` / `agent.process.work-dir`）

## 1. 问题

`4be5715` 之后本地进程执行原语已经能跑，但技能目录仍只接受 `HTTP/MCP/ALGORITHM` 三种类型，`SkillAdapters` 也没有 `PROCESS` 分支。结果是路线二停在"能写 `ProcessContract` 但既注册不进去、也执行不了"的半截状态：

1. `SkillCatalog.normalize` 的 `Set.of("HTTP","MCP","ALGORITHM")` 直接 400 `INVALID_SKILL：适配器类型无效`，PROCESS 契约根本走不到校验；
2. 目录层没有任何"PROCESS 不得带 endpoint/httpMapping、只能是 SYNC"的约束，即使放开类型集合，也会把一台本地进程登记成带 HTTP 端点、带 OpenAPI 参数映射的"出站技能"，调用方会按 HTTP 语义去理解它；
3. `process` 子契约如果原样落库（前端传什么存什么），缺省值要到执行期才补，草稿页显示的超时和 `ProcessAdapter` 实际使用的超时可能是两个数（编辑页 30s、执行器 60s）；
4. `SkillAdapters.invoke` 的 `switch` 落到 `default -> Failure("PARAM_ERROR", "未支持的适配器类型")`，PROCESS 技能即使注册成功也无法执行；
5. Y-3 的教训是"把 403/502/504 包成依赖不可用"会让"配置错误"和"上游抖动"混为一谈、诱导调用方重试（对本地进程就是第二次副作用）。PROCESS 分支必须让 `ProcessAdapter` 的 `PlatformException` 原样冒泡。

本卡只解决"注册 + 执行路由"，不碰发布预检、也不把 stdout 强解成 `outputSchema`（T21c）。

## 2. 改动

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java` | `:305` | 类型集合加入 `PROCESS` |
| 同上 | `:310`、`:314-336` | PROCESS 与 `endpoint` / `httpMapping` 互斥，报错点名冲突字段；跳过 `SkillRules.endpoint` |
| 同上 | `:362-378` | PROCESS 仅允许 `kind=SYNC`，组合错误点名原因 |
| 同上 | `:379-386` | `process` 必填，经 `ProcessContract.parse` 校验后把**规范化结果**写回契约 |
| 同上 | `:480-494` | PROCESS 的顶层 `timeoutMs` 由 `process.timeoutMs` 派生（跳过 100..600000 的 HTTP 上界） |
| 同上 | `:571-587` | 新增 `normalizedProcess(Map)`：`ProcessContract.parse(Json.tree(body.get("process")))` + 固定键序输出 |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `:70-99` | 注入 `ProcessAdapter`：新增 4 参 `@Autowired` 构造；2/3 参短构造保留给既有单测 |
| 同上 | `:196-203` | `case "PROCESS"`：`queryOnly` 按"结果未知"拒绝（没有结果查询通道，重跑=第二次副作用），否则 `localProcess` |
| 同上 | `:226-252` | `localProcess`：契约 → `ProcessAdapter.execute` → `Raw`，映射 6 个结构化字段，`fileRefs` 为空，不 catch 任何异常 |
| `skill-registry/src/main/resources/application.yml` | `:30-39` | 新增 `agent.process.enabled` / `agent.process.work-dir` 及语义注释 |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillProcessRegistrationTest.java` | 新增 | 6 例，无 Spring / 无 DB（`new SkillCatalog(null,null,null,"http://unused",true)` + `JwtAuthenticationToken`） |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillProcessRoutingTest.java` | 新增 | 4 例，`@TempDir` 允许根 + 真实 `ProcessAdapter` + 真实 `FakeProcessWorker` 子进程 |

未改：`ProcessContract` / `ProcessTree` / `ProcessAdapter`（行为一字未动）、`SkillExecution` / `SkillClients` / `HttpAsyncAdapter` / `SkillRules` / `AuthScheme`、`pom.xml`、数据库迁移、其它模块。

### 2.1 目录侧行为

- **类型集合**：`PROCESS` 是第四种适配器类型，`type` 仍按 `body.type → invocation.adapterType` 解析。
- **互斥**：PROCESS 带非空 `endpoint` → 400 `INVALID_SKILL`，消息含 `endpoint`；body 含 `httpMapping` 键 → 400 `INVALID_SKILL`，消息含 `httpMapping`。两条都在 `authRef`/`authScheme` 校验之前，避免被后续错误掩盖。
- **调用形态**：`kind` 解析逻辑不变（`invocation.kind → body.invocationKind → 默认 ALGORITHM?SUBMIT_QUERY:SYNC`），PROCESS 的非 SYNC 组合在通用守卫之前单独报 400 `UNSUPPORTED_INVOCATION_KIND`，消息形如 `PROCESS 仅支持 SYNC 调用形态（本地进程没有异步提交或回调通道），收到: SUBMIT_QUERY`。通用守卫追加 `|| process && kind.equals("SYNC")`，HTTP/MCP/ALGORITHM 的既有分支一字未改。
- **子契约**：`process` 必填；缺失/非对象/命令首元素非绝对路径等一律由 `ProcessContract.parse` 抛 400 `PROCESS_CONTRACT_INVALID`（**错误码归属**：PROCESS 子契约的语法错误统一是 `PROCESS_CONTRACT_INVALID`，由 `ProcessContract` 单点决定；目录层不重复映射成 `SKILL_CONTRACT_INVALID`，以免同一错误有两个码）。`ProcessContract.parse` 只做**语法**校验，不检查可执行文件是否存在（发布预检是 T21c）。
- **规范化落库**：写回的是 `ProcessContract` 的取值，不是前端原文——缺省 `timeoutMs=60000`、缺省 `maxOutputBytes=1048576` 在注册期就落库；未声明的 `workDirRoot/stdin/env` 不写空值。
- **顶层 `timeoutMs`**：PROCESS 派生为 `process.timeoutMs`，因此不会出现"编辑页 30s、执行器 60s"；PROCESS 的合法上界因此是 `ProcessContract.MAX_TIMEOUT=30min`，而不是通用 HTTP 的 600000ms。
- **`authScheme`**：未改动 `SkillRules/AuthScheme`（目录层只允许经 `SkillRules.authScheme` 这一个入口裁决，见其 Javadoc）。PROCESS 与 MCP/ALGORITHM 一样继承 `BEARER` 默认，执行期 PROCESS 从不读该字段（不走 `AuthScheme.headers`）。

### 2.2 适配器映射

`SkillAdapters.invoke` 的 `PROCESS` 分支：

1. `queryOnly=true`（对账路径）→ `Failure("RESULT_UNKNOWN", true, "本地进程没有可查询的原调用结果，禁止以对账名义重新执行")`；与 MCP 同一裁决，避免把重跑当对账。
2. `queryOnly=false` → `localProcess(contract)`：
   - 未装配 `ProcessAdapter`（短构造）→ `Failure("PARAM_ERROR", false, "当前进程未装配本地进程执行器，拒绝执行PROCESS技能")`，不静默回退；
   - `ProcessContract.parse(Json.tree(contract.get("process")))` 再解析一次（目录已校验，执行侧仍以契约为准，防止绕过目录的历史数据）；
   - `ProcessAdapter.execute(...)`，**无 try/catch**：403 `PROCESS_DISABLED` / `PROCESS_WORKDIR_DENIED`、502 `PROCESS_START_FAILED` / `PROCESS_EXITED`、504 `PROCESS_TIMEOUT` 原样冒泡，不包成 `DEPENDENCY_UNAVAILABLE`；
   - `Raw.structured` = `{exitCode, stdout, stderr, truncStdout, truncStderr, durationMs}`，`fileRefs = []`，`summary = "本地进程退出码 X，耗时 Yms"`，`ontologyMeta = null`；
   - 不做 `outputSchema` 校验、不把 stdout 强解成对象（T21c/P4）。

`arguments`（调用入参）本卡不注入：`arguments → stdin/argv` 的绑定属 T21c，已在 `localProcess` Javadoc 中如实标注为未覆盖，而不是塞一个不完整的映射假装支持。

### 2.3 配置键

```yaml
  # PLAT-21b 本地进程执行（PROCESS 适配器），由 skill/infra/ProcessAdapter 读取。
  # enabled 默认关闭：注册/编辑 PROCESS 技能不受影响，但执行前即返回 403 PROCESS_DISABLED，
  # 不创建进程、也不创建调用目录（开关只在执行期生效，目录层永远可以做契约校验）。
  # work-dir 是唯一的允许根：执行期对它做 realpath 归一化后比较，契约里的 workDirRoot 必须落在
  # 其下（防 symlink/.. 逃逸），并且该目录必须预先存在——执行器不会替你创建允许根。
  # 默认值放在 java.io.tmpdir 只适合开发/CI；生产必须指向独立数据盘上的专用目录
  # （例如 /data/agent-process 或 D:/agent-process），不要指向系统临时目录或代码仓库。
  process:
    enabled: ${AGENT_PROCESS_ENABLED:false}
    work-dir: ${AGENT_PROCESS_WORK_DIR:${java.io.tmpdir}/agent-process}
```

`application.yml` 只加了这两个键（与 `ProcessAdapter` 已有的 `@Value` 名称一致），没有改动其它键。

### 2.4 `process` 字段的最终 JSON 形状（给前端与文档）

注册（`POST /skills`）请求里的最小 `process`：

```json
{ "process": { "command": ["/abs/path/recombine", "--input", "in.json"] } }
```

注册读回的完整契约（本机实测，`ProcessContract` 补默认值后的结果）：

```json
{"type":"PROCESS","description":"本地执行重组算法二进制","endpoint":"","authRef":"","authScheme":"BEARER","httpMethod":"POST","process":{"command":["C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.8-hotspot\\bin\\java.exe","--version"],"timeoutMs":60000,"maxOutputBytes":1048576},"invocationKind":"SYNC","timeoutMs":60000,"inputSchema":{"type":"object"},"outputSchema":{"type":"object"},"riskLevel":"READ_ONLY","execution":{"sideEffect":false,"idempotent":true,"readonly":true,"cancellable":false},"guard":{"attributes":{},"roles":[]},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}
```

`process` 子对象（唯一有内容差异的部分，键序即输出顺序，JSON 对象本身无序）：

| 键 | 必有 | 类型 | 说明 |
| --- | --- | --- | --- |
| `command` | 是 | string[] | 1..64 项，每项 ≤4096 字符、不含 NUL/换行；首项必须是可执行文件**绝对路径**（`Path.of(..).isAbsolute`） |
| `timeoutMs` | 是 | int | 缺省 60000，范围 `(0, 1800000]`，单位毫秒 |
| `maxOutputBytes` | 是 | int | 缺省 1048576，范围 `1..1048576`；stdout/stderr 各自截断到该上限 |
| `workDirRoot` | 否 | string | 绝对路径；必须落在 `agent.process.work-dir` 的 realpath 之下，否则执行期 403 `PROCESS_WORKDIR_DENIED` |
| `stdin` | 否 | any JSON | ≤1MiB，进程启动后写入 stdin 并关闭 |
| `env` | 否 | object<string,string> | ≤32 项，不得覆盖保留变量名（PATH/COMSPEC/SYSTEMROOT 等） |

## 3. 红 → 绿原文

测试命令（`skill-registry` 单模块；不跑模块全量，模块既有 8F/112E 与本卡无关）：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
source .tools/env-load.sh
mvn -B -ntp -pl skill-registry test -Dtest='SkillProcessRegistrationTest,SkillProcessRoutingTest' \
  -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8" \
  -Dmaven.repo.local=.tools/m2
```

（`-Dstdout.encoding`/`-Dstderr.encoding` 是 Windows 控制台的中文显示需要，不影响断言。）

### 3.1 红：注册侧（`SkillCatalog` 还是 `HEAD` 版本）

```
[ERROR] Tests run: 6, Failures: 5, Errors: 1, Skipped: 0, Time elapsed: 0.085 s <<< FAILURE! -- in cn.agentplatform.skill.application.SkillProcessRegistrationTest
[ERROR] SkillProcessRegistrationTest.processRejectsEndpointBecauseALocalProcessNeverAddressesAnHttpEndpoint -- Time elapsed: 0.060 s <<< FAILURE!
org.opentest4j.AssertionFailedError: 报错必须点名冲突字段: 适配器类型无效 ==> expected: <true> but was: <false>
[ERROR] SkillProcessRegistrationTest.processCommandMustStartWithAnAbsolutePath -- Time elapsed: 0.003 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <PROCESS_CONTRACT_INVALID> but was: <INVALID_SKILL>
[ERROR] SkillProcessRegistrationTest.processContractIsAcceptedAndNormalizedBeforePersistence -- Time elapsed: 0.001 s <<< ERROR!
cn.agentplatform.common.PlatformException: 适配器类型无效
	at cn.agentplatform.skill.application.SkillCatalog.error(SkillCatalog.java:64)
	at cn.agentplatform.skill.application.SkillCatalog.normalize(SkillCatalog.java:305)
[ERROR] SkillProcessRegistrationTest.processObjectIsMandatoryForProcessSkills -- Time elapsed: 0.001 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <PROCESS_CONTRACT_INVALID> but was: <INVALID_SKILL>
[ERROR] SkillProcessRegistrationTest.processOnlySupportsSyncInvocationKind -- Time elapsed: 0.001 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <UNSUPPORTED_INVOCATION_KIND> but was: <INVALID_SKILL>
[ERROR] SkillProcessRegistrationTest.processRejectsHttpMappingBecauseThereIsNoHttpRequestToMap -- Time elapsed: 0.001 s <<< FAILURE!
org.opentest4j.AssertionFailedError: 报错必须点名冲突字段: 适配器类型无效 ==> expected: <true> but was: <false>
[ERROR] Failures:
[ERROR]   SkillProcessRegistrationTest.processCommandMustStartWithAnAbsolutePath:170 expected: <PROCESS_CONTRACT_INVALID> but was: <INVALID_SKILL>
[ERROR]   SkillProcessRegistrationTest.processObjectIsMandatoryForProcessSkills:158 expected: <PROCESS_CONTRACT_INVALID> but was: <INVALID_SKILL>
[ERROR]   SkillProcessRegistrationTest.processOnlySupportsSyncInvocationKind:142 expected: <UNSUPPORTED_INVOCATION_KIND> but was: <INVALID_SKILL>
[ERROR]   SkillProcessRegistrationTest.processRejectsEndpointBecauseALocalProcessNeverAddressesAnHttpEndpoint:116 报错必须点名冲突字段: 适配器类型无效 ==> expected: <true> but was: <false>
[ERROR]   SkillProcessRegistrationTest.processRejectsHttpMappingBecauseThereIsNoHttpRequestToMap:128 报错必须点名冲突字段: 适配器类型无效 ==> expected: <true> but was: <false>
[ERROR] Errors:
[ERROR]   SkillProcessRegistrationTest.processContractIsAcceptedAndNormalizedBeforePersistence:85 » Platform 适配器类型无效
[ERROR] Tests run: 6, Failures: 5, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

### 3.2 红：执行侧（`SkillAdapters` 的 `PROCESS` 分支尚未接通）

```
[ERROR] Tests run: 4, Failures: 3, Errors: 1, Skipped: 0, Time elapsed: 0.079 s <<< FAILURE! -- in cn.agentplatform.skill.infra.SkillProcessRoutingTest
[ERROR] SkillProcessRoutingTest.disabledSwitchRejectsBeforeCreatingAnyCallDirectory -- Time elapsed: 0.055 s <<< FAILURE!
org.opentest4j.AssertionFailedError: Unexpected exception type thrown, expected: <cn.agentplatform.common.PlatformException> but was: <cn.agentplatform.skill.infra.SkillAdapters.Failure>
Caused by: cn.agentplatform.skill.infra.SkillAdapters$Failure: 未支持的适配器类型
[ERROR] SkillProcessRoutingTest.nonZeroExitPropagatesProcessExitedInsteadOfDependencyUnavailable -- Time elapsed: 0.004 s <<< FAILURE!
org.opentest4j.AssertionFailedError: Unexpected exception type thrown, expected: <cn.agentplatform.common.PlatformException> but was: <cn.agentplatform.skill.infra.SkillAdapters.Failure>
Caused by: cn.agentplatform.skill.infra.SkillAdapters$Failure: 未支持的适配器类型
[ERROR] SkillProcessRoutingTest.reconciliationQueryMustNotRerunTheProcess -- Time elapsed: 0.003 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <RESULT_UNKNOWN> but was: <PARAM_ERROR>
[ERROR] SkillProcessRoutingTest.processSkillReturnsExitCodeAndStdoutTailAsStructuredOutput -- Time elapsed: 0.002 s <<< ERROR!
cn.agentplatform.skill.infra.SkillAdapters$Failure: 未支持的适配器类型
[ERROR]   SkillProcessRoutingTest.disabledSwitchRejectsBeforeCreatingAnyCallDirectory:115 Unexpected exception type thrown, expected: <cn.agentplatform.common.PlatformException> but was: <cn.agentplatform.skill.infra.SkillAdapters.Failure>
[ERROR]   SkillProcessRoutingTest.nonZeroExitPropagatesProcessExitedInsteadOfDependencyUnavailable:92 Unexpected exception type thrown, expected: <cn.agentplatform.common.PlatformException> but was: <cn.agentplatform.skill.infra.SkillAdapters.Failure>
[ERROR]   SkillProcessRoutingTest.reconciliationQueryMustNotRerunTheProcess:137 expected: <RESULT_UNKNOWN> but was: <PARAM_ERROR>
[ERROR] Tests run: 4, Failures: 3, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

> 复现方式（先红后绿留档说明）：两段红确实是"先写测试、跑红、再实现"时观察到的（第一次红：6 例 5F/1E；执行侧红：4 例 3F/1E，其中 2 例是 `SkillAdapters$Failure: 未支持的适配器类型`）。实现转绿后 `target/surefire-reports` 被覆盖，这里按同一方法重新留档：3.1 把 `SkillCatalog.java`/`SkillAdapters.java`/`application.yml` 临时回退到 `HEAD` 后只跑注册测试（`SkillProcessRoutingTest` 因缺 4 参构造会编译失败，故暂时移开），3.2 把 `case "PROCESS"` 临时改名让分支落到 `default` 后跑执行测试；两次都在跑完后立即还原，`git diff --stat` 复核回到 `3 files changed, 130 insertions(+), 12 deletions(-)`。日志见本目录 `red-registration.log` / `red-routing.log`（`*.log` 被仓库 `.gitignore` 忽略，故正文完整引用）。

### 3.3 绿

```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.249 s -- in cn.agentplatform.skill.application.SkillProcessRegistrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.175 s -- in cn.agentplatform.skill.infra.SkillProcessRoutingTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

对应用例：

| 测试 | 断言要点 |
| --- | --- |
| `SkillProcessRegistrationTest.processContractIsAcceptedAndNormalizedBeforePersistence` | 注册成功；`type=PROCESS`、`invocationKind=SYNC`、`endpoint=""`、`timeoutMs=60000L`；`process.command[0]` 为绝对路径、`process.timeoutMs=60000`、`process.maxOutputBytes=1048576`，且不写空 `workDirRoot/stdin/env` |
| `...processRejectsEndpointBecauseALocalProcessNeverAddressesAnHttpEndpoint` | 400 且消息含 `endpoint` |
| `...processRejectsHttpMappingBecauseThereIsNoHttpRequestToMap` | 400 且消息含 `httpMapping` |
| `...processOnlySupportsSyncInvocationKind` | `SUBMIT_QUERY`、`CALLBACK` 各 400 `UNSUPPORTED_INVOCATION_KIND`，消息同时含 `PROCESS` 与具体 kind |
| `...processObjectIsMandatoryForProcessSkills` | 缺 `process` → 400 `PROCESS_CONTRACT_INVALID` |
| `...processCommandMustStartWithAnAbsolutePath` | `command=["bin/recombine"]` → 400 `PROCESS_CONTRACT_INVALID`，消息含"绝对路径" |
| `SkillProcessRoutingTest.processSkillReturnsExitCodeAndStdoutTailAsStructuredOutput` | 真实 `FakeProcessWorker ok`：`structured.exitCode=0`、`stdout` 含 `ok-stdout` 与 `中文-正常退出`、`stderr=""`、两个 trunc 标志 `false`、`durationMs` 为整数、`fileRefs` 为空、`summary` 非空 |
| `...nonZeroExitPropagatesProcessExitedInsteadOfDependencyUnavailable` | `FakeProcessWorker exit3` → `PlatformException` 502 `PROCESS_EXITED`，消息含 `boom-stderr` 与 `3`（未被包成 `DEPENDENCY_UNAVAILABLE`） |
| `...disabledSwitchRejectsBeforeCreatingAnyCallDirectory` | `ProcessAdapter(false, root)` → 403 `PROCESS_DISABLED`，且允许根下 `Files.list(root)` 为空（没建 `call-*`，也就没起进程） |
| `...reconciliationQueryMustNotRerunTheProcess` | `queryOnly=true` → `Failure`，`semantic=RESULT_UNKNOWN`、`unknown=true`（不重跑） |

### 3.4 回归（HTTP/MCP/ALGORITHM 与既有校验）

```bash
mvn -B -ntp -pl skill-registry test -Dtest='SkillAuthSchemeRegistrationTest,ProcessAdapterTest,McpAdapterTest,HttpSchemaBoundaryTest,OpenApiHttpMappingTest,ArtifactReferenceTest,SkillAttemptPropagationTest' \
  -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8" \
  -Dmaven.repo.local=.tools/m2
```

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.251 s -- in cn.agentplatform.skill.application.SkillAuthSchemeRegistrationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.184 s -- in cn.agentplatform.skill.infra.ArtifactReferenceTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.923 s -- in cn.agentplatform.skill.infra.HttpSchemaBoundaryTest
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 22.36 s -- in cn.agentplatform.skill.infra.McpAdapterTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.056 s -- in cn.agentplatform.skill.infra.OpenApiHttpMappingTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.812 s -- in cn.agentplatform.skill.infra.ProcessAdapterTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.100 s -- in cn.agentplatform.skill.infra.SkillAttemptPropagationTest
[INFO] Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

T11 的 `authScheme` 注册矩阵（`SkillAuthSchemeRegistrationTest` 7 例）与 `ProcessAdapterTest` 10 例全部保持通过；`SkillAdapters` 的短构造仍被既有测试使用（`SkillAttemptPropagationTest` 用 3 参、`AlgorithmProtocolTest` 用匿名子类），编译与行为未变。

`git diff --stat`：

```
 .../skill/application/SkillCatalog.java            | 76 +++++++++++++++++++---
 .../agentplatform/skill/infra/SkillAdapters.java   | 56 +++++++++++++++-
 skill-registry/src/main/resources/application.yml  | 10 +++
 3 files changed, 130 insertions(+), 12 deletions(-)
```

## 4. 开关与回滚

**开关**

- `agent.process.enabled`（env `AGENT_PROCESS_ENABLED`，默认 `false`）：**只在执行期**由 `ProcessAdapter.execute` 读取。注册/编辑/发布预检不受影响——目录层永远可以做契约校验，这是"注册可以、执行不行"的有意分工。
- 关闭时执行路径：`ProcessAdapter.execute` 在解析允许根、创建 `call-*` 目录之前就抛 403 `PROCESS_DISABLED`，因此不创建进程、不创建调用目录（`disabledSwitchRejectsBeforeCreatingAnyCallDirectory` 用例断言了允许根为空）。
- `agent.process.work-dir`（env `AGENT_PROCESS_WORK_DIR`，默认 `${java.io.tmpdir}/agent-process`）：唯一允许根。执行期对它 `toRealPath()` 后比较契约的 `workDirRoot`；目录必须预先存在且是目录，否则 403 `PROCESS_WORKDIR_DENIED`。默认 tmpdir 仅适合开发/CI，生产应指向独立数据盘（避免临时目录被清理、也避免可写仓库）。
- 打开开关的最小步骤：`mkdir -p <work-dir>` → `AGENT_PROCESS_ENABLED=true`、`AGENT_PROCESS_WORK_DIR=<work-dir>` → 重启 `skill-registry`。运行账号只需对该目录可读写执行，不需要 shell/管理员权限。

**回滚**

- 代码回滚 = 还原上表 3 个文件（+ 删除 2 个新测试类）。没有数据库迁移、没有新增依赖、没有改 `pom.xml`，`agent_platform_v12` 的表结构/数据不受影响。
- 已落库的 PROCESS 契约在回滚后仍能读回（目录的写入期校验不影响读路径），只是不能再执行，且再次保存会被 `HEAD` 的类型集合拒绝——回滚不产生"半迁移"数据，也不需要数据修复脚本。
- 开关回滚 = `AGENT_PROCESS_ENABLED=false`，不需要改契约；执行端点恢复 403 `PROCESS_DISABLED`。

## 5. 与 HTTP 契约的差异

| 维度 | HTTP（含异步） | PROCESS |
| --- | --- | --- |
| `endpoint` | 必填（`SkillRules.endpoint` 校验 http/https、host、无 userinfo/fragment）；`CALLBACK` 还要结果端点占位符 | 必须为空（非空即 400），读回 `endpoint=""` |
| `httpMapping` | 可选，走 `OpenApiHttpMapping.validate` | 禁止（含该键即 400） |
| `httpMethod` | 默认 `POST`，参与 CALLBACK 校验 | 仍写 `POST`（形状字段，执行期不读） |
| `invocationKind` | `SYNC`/`SUBMIT_QUERY`/`CALLBACK` | 仅 `SYNC` |
| `authRef`/`authScheme` | `authScheme` 决定执行期请求头（`AuthScheme.headers`）；`API_KEY` 必须带 `authRef` | `authRef` 可为空；`authScheme` 仍缺省写 `BEARER`（沿用 MCP/ALGORITHM 的默认），但执行期从不使用 |
| 超时 | 顶层 `timeoutMs`，100..600000，驱动 HTTP 客户端 | 顶层 `timeoutMs` 由 `process.timeoutMs` 派生（默认 60000、上限 1800000），驱动进程树 `awaitExit` |
| 输出 | `outputSchema` 强校验；`returnSpec` 决定结构化/摘要/文件引用；超出上限转存 `ArtifactStore` 得 `fileRefs` | 不校验 `outputSchema`；`structured={exitCode,stdout,stderr,truncStdout,truncStderr,durationMs}`；`fileRefs=[]`；超限在 `ProcessAdapter` 内截断并置 trunc 标志（不转存） |
| 对账/幂等 | `reconciliation` 契约 + 异步查询 | 无结果查询通道；`queryOnly=true` 一律 `RESULT_UNKNOWN`；`idempotencyKey` 不参与执行 |
| 失败语义 | 适配器按 HTTP 状态映射 `Failure`（`unknown` 决定 UNKNOWN/FAILED） | `ProcessAdapter` 的 `PlatformException`（403/502/504）原样抛出，不经过 `Failure` 包装 |

**发现的契约不一致 / 需要下游注意的点**

1. **双 `timeoutMs`**：顶层 `timeoutMs` 与 `process.timeoutMs` 是同一份数据的派生关系（不是两个可独立声明的字段）。顶层允许 100..600000，而 PROCESS 的合法上界是 30min（1800000），即 PROCESS 技能会突破前端"通用超时"滑块的常规范围——前端对 PROCESS 必须以 `process.timeoutMs` 为准，不能按 600000 截断。另外执行期真正生效的只有 `process.timeoutMs`，顶层字段目前仅供展示（本模块内没有别的消费方）。
2. **`authScheme` 对 PROCESS 无语义但仍写 `BEARER`**：这是沿用 MCP/ALGORITHM 的默认（目录层只允许经 `SkillRules.authScheme` 裁决，见其 Javadoc）。若前端按 `authScheme` 渲染"凭据输入框"，PROCESS 会多出一个无意义控件。后续可在 `SkillRules` 里对非出站类型强制 `NONE`，但那是独立改动，本卡不动。
3. **执行端点的异常语义不一致（Y-3 家族）**：`SkillAdapters.invoke` 这一层已经原样冒泡，但 `SkillExecution:311-315` 的 `catch (Exception)` 会把 `PlatformException`（403/502/504）转成 `FAILED/UNKNOWN + OUTPUT_INVALID/RESULT_UNKNOWN`，并写"外部输出接收、转存或Schema验收失败"的原因。也就是说 `POST /skills/{code}/call` 的调用方看到的仍不是 `PROCESS_EXITED`/`PROCESS_TIMEOUT` 本身。修复点在 `SkillExecution`（本卡禁止改动），已列为后续卡。
4. **空 `httpMapping` 视为未携带**：`normalize` 开头会剔除空 `httpMapping`（既有"清空即缺省"语义），所以 PROCESS 传 `"httpMapping": {}` 不会触发互斥错误；只有非空对象/非空内容才会 400。
5. **`save()` 不回填上一次的 `process`**：`save` 只为 `importSource/httpMapping/authScheme` 做"省略即沿用"，`process` 不做。所以 PROCESS 技能的编辑保存必须带完整 `process`（与 `type` 必填一致）；只提交部分字段的"补丁式保存"会 400 `PROCESS_CONTRACT_INVALID`，而不是静默沿用旧命令。
6. **`arguments` 未绑定**：带 `inputSchema` 的 PROCESS 技能目前会忽略调用入参执行（见 2.2/6）。

## 6. 未覆盖

1. **发布预检**（T21c）：`publish` 目前不检查 `command[0]` 是否存在/可执行、`workDirRoot` 是否可写、`allowedEnv` 引用的变量是否可用。`ProcessContract.parse` 只做语法校验（`4be5715` 的既有边界），本卡未加"注册时探活"，避免在编辑草稿时真的起进程。
2. **stdout → `outputSchema`/`returnSpec` 映射**（T21c/P4）：PROCESS 只回原始证据，`returnSpec` 不参与投影，`outputSchema` 不参与校验；因此 PROCESS 技能暂不能声明"输出必须是某结构"。
3. **调用参数注入**（T21c）：`arguments → stdin` / `argv` 未实现。PROCESS 技能会忽略入参执行；本卡在 `localProcess` Javadoc 与 2.2 中明确标注，没有伪造映射。
4. **执行端点的错误透传**：`SkillExecution:311-315` 的 `catch (Exception)` 仍会吞掉 `PROCESS_*`（见 5.3）。`SkillAdapters`/`ProcessAdapter` 两层的行为已经正确，缺口在 `SkillExecution`，属"Y-3 的 403→502 吞错"同族，需单独一张卡修（本卡禁止改该文件）。
5. **前端表单**：技能编辑器还没有 PROCESS 的 `command/timeoutMs/workDirRoot/stdin/env` 输入与校验回显（不在本卡白名单）。后端错误消息已点名字段，前端可直接定位。
6. **对账**：PROCESS 没有结果查询通道，`queryOnly` 一律返回 `RESULT_UNKNOWN`；未实现"用 `call-*/stdout.txt` 落盘证据对账"的机制（`ProcessAdapter` 的 `call-*` 目录在 `finally` 里删除，本卡也不改其清理策略）。
7. **允许根与多租户**：`agent.process.work-dir` 是进程级单根，不按 tenant 分区；契约里的 `workDirRoot` 只做 realpath 前缀校验。多租户隔离/配额留待后续（属运维与 `ProcessAdapter` 的边界）。
8. **模块全量测试**：按要求未跑 `mvn -pl skill-registry test` 全量；该模块既有 8F/112E 是 Y-3 的 403→502 吞错遗留，与本卡无关，未修。
