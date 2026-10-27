# PLAT-21c 证据：PROCESS 技能协议化（信封 / 退出码 / 响应校验 / 发布预检 / 错误透出）

- 日期：2026-10-27
- 泳道：`skill-registry`（T21c，卡片 6）
- 基线：`1808d3c`（T21b）
- 状态：工作树改动，**未 git add / 未 commit**（提交由编排者完成）
- 依据：`文档/优化方案/派发卡片-B1-20261027.md` 卡片 6；`文档/优化方案/算法进程接入-平台侧方案与契约-20261027.md` §4.4 / §4.5 / §4.6
- 环境：`source .tools/env-load.sh`（PG `127.0.0.1:25432`，`AGENT_DB_PASSWORD` 只在该脚本被 source 时导出）；`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`；Maven 固定 `-Dmaven.repo.local=.tools/m2` 与 `"-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"`，带 `-pl skill-registry`、不带 `-am`。

## 1. 问题

T21a/T21b 把 PROCESS 技能接进了执行链，但只是“裸 stdio”：

1. 调用参数**没有进入子进程**：stdin 从不写入，算法拿不到 `arguments`，只能靠 argv 或自己读文件。
2. stdout 被当成自由文本原样返回：`Raw.structured` 变成 `{exitCode, stdout, stderr, ...}`，下游无法用 `outputSchema` 约束算法产物。
3. 退出码只区分“0 / 非 0”：`3`（计算失败）与 `9`（随便什么错）都折叠成 `PROCESS_EXITED`，调用方无法判断是否可重试、是否该改输入。
4. 算法把日志写进 stdout、或回显了别的 `requestId`，平台照单全收，错误会以“成功”形式污染下游。
5. 发布口没有任何 PROCESS 预检：`command[0]` 写成 `"java"` 或指向不存在的文件也能发布成功，失败被推迟到第一次真实调用。
6. `SkillExecution` 的 `catch (Exception)` 把 `PROCESS_DISABLED`(403)、`PROCESS_WORKDIR_DENIED`(403)、`PROCESS_TIMEOUT`(504) 全部折叠成 `FAILED/UNKNOWN + OUTPUT_INVALID/RESULT_UNKNOWN`（Y-7），调用方看到的是“外部输出接收失败”，既误导又可能触发无意义重试。

## 2. 改动

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/domain/ProcessContract.java` | `:41`、`:48`、`:52`、`:63`、`:134`、`:141`、`:294` | record 追加 `protocol`（缺省 `algorithm-process/1`，常量 `PROTOCOL`）与可选 `outputSchema`；`parse` 只接受该 protocol；新增 `MAX_REQUEST_BYTES = 1 MiB`、`hasOutputSchema()`、`parseProtocol`/`parseOutputSchema`/`validProtocol`/`validOutputSchema` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java` | `:84`、`:94`、`:106`、`:114`、`:201`、`:236`、`:281`、`:358`、`:466` | 新增 `Request`/`Output` record 与 `execute(contract, request)`；写请求信封并关 stdin；按 §4.6 映射退出码；按 §4.5 顺序校验响应（字节上限→唯一 JSON 对象→protocol→requestId→ok→result→outputSchema）；`Capture` 支持溢出回调杀树 |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `:202`、`:236` | 仅 `localProcess` 一处：构造 `Request(requestId=callId, operation=技能 code, parameters=arguments)`，Raw 取响应 `result` 节点（**经编排者 m00093 授权，仅此方法体**） |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java` | `:606`、`:671`、`:691`、`:709`、`:735` | `publish` 在 DRAFT 检查后调 `processPrecheck`；`command[0]` 绝对路径/存在/普通文件、`workDirRoot` 绝对或可创建、`timeoutMs` 由 `parse` 强制 1..1800000、`outputSchema` 过 `SkillRules.schema`；失败统一 400 `PROCESS_PRECHECK_FAILED` |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java` | `:314` | `catch (Exception)` 首行：`PlatformException` 且 `code` 以 `PROCESS_` 开头时原样上抛（Y-7） |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/FakeProcessWorker.java` | 全部 | 新增协议模式 `envelope/ok/echoargs/env/tamper/banner/badprotocol/rejected/schemabad/silent/flood/flooderr/exit2/exit3/exit4` 与 `plain`，先读 stdin 再只写一个响应信封 |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessAdapterTest.java` | 全部 | 改为断言 `Output.result`，新增 legacy 入口、退出码语义、stdout 超限杀树、stderr 洪泛不失败等 11 例 |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessProtocolTest.java` | 新增 | 11 例：信封四键、requestId 篡改、banner、protocol 不符、静默、拒绝、exit2/exit4、schema 违反/通过、>1 MiB 请求 |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillProcessRoutingTest.java` | 全部 | 5 例：信封来自 call 参数、Raw=result、计算失败不折叠、关闭开关不建目录、对账禁止重跑 |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessTreeTest.java` | `:25` | `startsAndWaitsForExit` 改用不读 stdin 的 `plain` 模式（协议模式会等 stdin EOF，进程树原语测试不写 stdin） |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/ProcessPublishPrecheckTest.java` | 新增 | 10 例：接受合法契约、相对 command、文件不存在、目录当命令、相对/文件 workDirRoot、可创建 workDirRoot 不落盘、空 outputSchema、timeout 越界、HTTP 跳过 |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillProcessExecutionTest.java` | 新增 | Y-7：经真实 `SkillExecution` 走 PROCESS 分支，开关关闭时 403 `PROCESS_DISABLED`，且不产生 call 目录 |

### 2.1 请求与响应协议（`ProcessAdapter`）

- 请求信封（§4.4）：键序 `protocol`、`requestId`、`operation`、`parameters`、`files`（本卡不传文件，固定 `{}`），UTF-8，写完立刻 close stdin；序列化后 > 1 MiB 在**起进程之前**抛 400 `PROCESS_INPUT_TOO_LARGE`。
- 退出码（§4.6）：`0` → 必须响应 `ok=true`（否则 502 `PROCESS_PROTOCOL_MISMATCH`）；`2` → 422 `PROCESS_INPUT_INVALID`；`3` → 502 `PROCESS_CALCULATION_FAILED`；`4` → 502 `PROCESS_OUTPUT_WRITE_FAILED`；其它非 0 → 若 stdout 是合法 `ok=false` 信封则 502 `PROCESS_REJECTED`（消息取 `error.message`，截 512），否则 502 `PROCESS_EXITED`（消息附 stderr 尾部 2 KiB）。超时/中断仍是 504 `PROCESS_TIMEOUT`。
- 响应校验顺序（§4.5）：stdout 字节上限（超限杀树 → 502 `PROCESS_OUTPUT_TOO_LARGE`）→ 严格 UTF-8 → 恰好一个 JSON 对象（前后多余文本 → 502 `PROCESS_OUTPUT_INVALID`）→ `protocol` 一致 → `ok` 为布尔 → `requestId` 与请求一致（`ok=false` 允许 null）→ `result` 为对象 → `outputSchema` 校验（失败 502 `PROCESS_OUTPUT_SCHEMA_INVALID`，消息保留 `SkillRules.validate` 给出的 JSON Pointer）。
- 返回给调用的 Raw 是 `result` 节点；`files/warnings/metrics` 解析后放进 `Output`，本卡不落工件（见 §5）。

### 2.2 发布预检（`SkillCatalog.processPrecheck`）

- 顺序：先按原始 JSON 校验 `command[0]`（绝对路径、存在、普通文件）与 `workDirRoot`（绝对；存在则必须是目录；不存在则最近已存在祖先可写，用一次性 `.precheck-` 目录探针验证后立即删除，**不创建 workDirRoot**），再交给 `ProcessContract.parse` 强制 `timeoutMs` 与 `protocol`，最后用 `SkillRules.schema` 校验 `outputSchema`。
- 失败统一 `PlatformException(400, "PROCESS_PRECHECK_FAILED", ...)`：发布失败意味着要改契约，重试无意义。
- 预检只做静态可判定项，不真实跑进程（发布不应产生外部副作用）；真实可用性仍由连接/受控调试与选型验收负责。

### 2.3 错误透出（Y-7）

- 只改 `SkillExecution` 主执行路径的 `catch (Exception)`（`:311-315`）。其它 `catch` 位置（`:112`/`:121` 参数与守卫、`:901-917` 对账段）不捕获通用异常，`PROCESS_*` 在那里本来就上抛，故未改。
- 口径：仅 `ex instanceof PlatformException && code.startsWith("PROCESS_")` 原样上抛；HTTP/ALGORITHM 的既有 envelope 语义（折叠为 `OUTPUT_INVALID`/`RESULT_UNKNOWN`）保持不变。

## 3. 红 → 绿

### 3.1 红证据（两份，均保留原始输出）

- `red-baseline.log`：把本卡新增/重写的测试拷到 `git archive 1808d3c` 的干净基线（`/tmp/t21c-base`）上跑验收集合 → **编译失败**（`ProcessAdapter.Output`、`ProcessContract.PROTOCOL`、`SkillCatalog.processPrecheck` 等在基线不存在）。证明这些测试确实是本卡新增、基线不可能满足。
- `red-mutation.log`：在本卡实现上把 5 处行为逐一回退（信封不写参数、退出码一律 `PROCESS_EXITED`、响应不校验、发布不预检、Y-7 不重抛），同一组测试 → **56 tests, 28 failures + 1 error**。用于证明每条断言的“红”对应它要钉住的行为，而不是编译红。
- 说明：本泳道由单人同时写测试与实现，时间顺序上的“第一次运行”没有单独留档；`red-mutation.log` 是行为级回退重放，命令与文件见下，RED-PROBE 标记已在提交前全部移除（`grep -c RED-PROBE` 结果为 0）。

```bash
# 基线红
cd "G:/207/项目/算法重组工具/代码/agent-platform" && source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
cd /tmp/t21c-base && mvn -B -ntp -pl skill-registry test \
  -Dtest='ProcessContractTest,ProcessAdapterTest,ProcessTreeTest,SkillProcessRegistrationTest,SkillProcessRoutingTest,ProcessProtocolTest,ProcessPublishPrecheckTest,SkillProcessExecutionTest' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
  -Dmaven.repo.local="G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2"
```

红日志原文（节选，`red-mutation.log`）：

```
[ERROR] Tests run: 56, Failures: 28, Errors: 1, Skipped: 0
[ERROR]   ProcessProtocolTest.writesEnvelopeWithProtocolRequestIdOperationAndParameters:63 expected: <algorithm-process/1> but was: <>
[ERROR]   ProcessProtocolTest.rejectedRequestIdIsProtocolMismatch:77 Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.
[ERROR]   ProcessProtocolTest.resultViolatingOutputSchemaIsRejected:145 Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.
[ERROR]   ProcessAdapterTest.calculationFailureIsNotReportedAsGenericExit:106 expected: <PROCESS_CALCULATION_FAILED> but was: <PROCESS_EXITED>
[ERROR]   ProcessPublishPrecheckTest.rejectsRelativeCommand:41 Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.
[ERROR]   SkillProcessExecutionTest.disabledProcessFailsWith403InsteadOfGenericOutputFailure:175 Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.
```

### 3.2 绿证据

验收命令（卡片原文，追加 `SkillProcessExecutionTest` 以覆盖 Y-7；`source` 而非 `bash`，否则父 shell 拿不到 DB 密码）：

```bash
cd "G:/207/项目/算法重组工具/代码/agent-platform" && source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl skill-registry test \
  -Dtest='ProcessContractTest,ProcessAdapterTest,ProcessTreeTest,SkillProcessRegistrationTest,SkillProcessRoutingTest,ProcessProtocolTest,ProcessPublishPrecheckTest,SkillProcessExecutionTest' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
  -Dmaven.repo.local=.tools/m2
```

`green.log` 原文（节选）：

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in ...ProcessPublishPrecheckTest
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- in ...SkillProcessExecutionTest
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- in ...SkillProcessRegistrationTest
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- in ...ProcessContractTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in ...ProcessAdapterTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in ...ProcessProtocolTest
[INFO] Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- in ...ProcessTreeTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- in ...SkillProcessRoutingTest
[INFO] Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 3.3 回归

改动触及 `SkillAdapters`/`SkillCatalog.publish`/`SkillExecution`，跑相关集合（`regression.log`）：

```bash
mvn -B -ntp -pl skill-registry test \
  -Dtest='SkillExecutionTest,FixtureSkillsTest,ServiceLifecycleTest,SkillRulesTest,SkillStatisticsPostgresTest' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

```
[INFO] Tests run: 5,  ... FixtureSkillsTest
[INFO] Tests run: 3,  ... ServiceLifecycleTest
[INFO] Tests run: 25, ... SkillExecutionTest
[INFO] Tests run: 7,  ... SkillStatisticsPostgresTest
[INFO] Tests run: 6,  ... SkillRulesTest
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 4. 与卡片/契约的出入

1. **`SkillAdapters` 被改**（卡片禁改清单）：编排者 `m00093` 拍板允许，仅 `localProcess` 方法体 + `case "PROCESS"` 一行调用。不改则信封拿不到 `arguments`、Raw 拿不到 `result`，协议实现是死代码。改动面 35 行。
2. **`ProcessOutput.java` 未新建**（契约 §5.1 提到）：卡片白名单不允许新增该文件，响应信封解析结果放在 `ProcessAdapter.Output`（record）里。
3. **protocol 取值冲突**：契约 §4.2 写 `JSON_STDIO_V1`，§4.4/§4.5 与卡片写 `algorithm-process/1`。按卡片要求“以契约为准”，采用 §4.4 的 `algorithm-process/1`（`ProcessContract.PROTOCOL`）；§4.2 的字段说明需另行更正。
4. **`PROCESS_REJECTED` 的触发面**：§4.6 规定 exit 0 必须 `ok=true`，故 “exit 0 + ok=false” 判 `PROCESS_PROTOCOL_MISMATCH`；`PROCESS_REJECTED` 只出现在“其它非 0 + 合法 ok=false 信封”的路径。这是把退出码当机器契约的必然结果，已在测试 `explicitRejectionOnOtherExitCodeKeepsWorkerMessage` 中钉住。
5. **预检错误码**：卡片只要求 400，未命名 code；实现取 `PROCESS_PRECHECK_FAILED`（与 `PROCESS_CONTRACT_INVALID` 区分：前者是“契约语法没错但指向的文件/目录不可用”）。
6. **预检测试口径**：`publish` 全程在 DB 事务内、且依赖连接/验收记录，无 DB 的定向测试直接调包私有 `processPrecheck(candidate)`（10 例）；`publish` 里的调用点（`:606`）由代码审查保证，未单独做 DB 级断言（见 §5）。

## 5. 未覆盖 / 风险

1. **输出文件（`files`）不落工件**：§4.5 的 `files[{name,path,contentType,sha256,bytes}]` 只被解析进 `Output`，`SkillAdapters` 目前丢弃；文件校验/转存/引用属于后续卡（T21d 或算法接入二阶段）。
2. **输入文件（`files` 请求字段）固定 `{}`**：§4.4 允许传相对路径，本卡不接线（没有工件存储的入参通道）。
3. **`publish` 集成未做 DB 级断言**：预检在事务内、在 DRAFT 检查之后调用；失败时事务回滚。直接测 helper 的取舍已写在上文。
4. **`command[0]` 只校验是普通文件**，不校验可执行位（Windows 上无意义、POSIX 上 `Files.isExecutable` 可作为增强）。
5. **`workDirRoot` 探针的窗口期**：在最近已存在祖先下建/删 `.precheck-*` 目录，与并发发布可能短暂竞争；失败即 400，不会留下目录。
6. **`requestId` 兼容入口**：旧 `execute(contract)` 用随机 UUID 作 requestId、空 operation，仅服务既有测试/内部调用；生产路径都走 `execute(contract, request)`。
7. **stderr 上限**：沿用 1 MiB 环形缓冲，只把尾部 2 KiB 放进错误消息；算法若把关键信息写在更早位置，仍会丢失（§4.6 只要求尾部）。
8. **Y-7 是窄口径**：只上抛 `PROCESS_*` 的 `PlatformException`；如果将来 PROCESS 适配器抛其它 `PlatformException`（如 400 契约错误），仍会被折叠——需要时再扩展白名单。

## 6. 文件清单

改动（9 个，全部在白名单内）：

- `skill-registry/src/main/java/cn/agentplatform/skill/domain/ProcessContract.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java`（`localProcess` 一处，经授权）
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/FakeProcessWorker.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessAdapterTest.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessTreeTest.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillProcessRoutingTest.java`

新增（4 个测试/证据）：

- `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessProtocolTest.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/application/ProcessPublishPrecheckTest.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillProcessExecutionTest.java`
- `docs/evidence/2026-10-27-PLAT-21c-protocol/{README.md,red-baseline.log,red-mutation.log,green.log,regression.log}`

未改动：`SkillRules`、`pom.xml`、V 迁移、`application.yml`、前端、其它模块。
