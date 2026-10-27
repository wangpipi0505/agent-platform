# PLAT-21a：算法本地进程执行原语

- 卡片：T21a（PLAT-21 / 方案 §4.21，S1B 第一卡）
- 日期：2026-10-27
- 范围：只交付**执行原语**（契约、进程树、适配器、测试），不接入目录/发布校验、不读取 artifact、不做取消与配额。
- 结论：定向测试 22/22 绿，`ServiceLifecycle` 回归 3/3 绿；Windows 实机已验证 `taskkill /T /F` 能终止整棵进程树。

## 1. 问题

路线二（算法本地进程）在当前代码里**无法执行**：

- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:304` 只允许
  `HTTP/MCP/ALGORITHM`，发布任何“本地进程”类型都会 400 `INVALID_SKILL`。
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java:162-191` 的
  `invoke` 只按 `SYNC/SUBMIT_QUERY/CALLBACK` 与 `HTTP/MCP/ALGORITHM` 分派，没有进程分支。
- 杀进程树的代码原本埋在 `skill-registry/src/main/java/cn/agentplatform/skill/application/ServiceLifecycle.java:702-750`，
  与生命周期强耦合，无法被新的执行器复用。

本卡先补齐可复用的执行原语，让 T21b 只需接线，不重复实现“起进程、限时、截断、杀树、清目录”。

## 2. 交付物

| 文件 | 说明 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/domain/ProcessContract.java` | 不可变进程契约 + `parse(JsonNode)` 结构校验 |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessTree.java` | 启动、等待、整棵进程树终止（从 `ServiceLifecycle` 抽出） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java` | 单次执行：allowed root 下 `callDir`、无 shell argv、限时、双流截断、环境白名单 |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/ServiceLifecycle.java` | **只做替换**：启动/杀树改调 `ProcessTree`，生命周期语义与错误映射不变 |
| `skill-registry/src/test/java/cn/agentplatform/skill/domain/ProcessContractTest.java` | 契约 9 条（默认值、完整解析、逐字段拒绝） |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/FakeProcessWorker.java` | 跨平台假 worker（纯 Java，无 sh/cmd） |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessAdapterTest.java` | 适配器 10 条（ok/exit3/超时/双流洪泛/参数/中文 cwd/开关/环境/越界） |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessTreeTest.java` | 进程树 3 条（正常等待、按 `Process` 杀树、按 `ProcessHandle` 杀树） |

证据日志：`red-phase.log`、`green-targeted.log`、`green-servicelifecycle.log`、`windows-taskkill.txt`（本目录）。

## 3. 契约字段与校验

`ProcessContract.parse(JsonNode)` 只接受 JSON 对象；缺省值在解析期补齐。任何结构/范围错误统一：

```text
PlatformException(400, "PROCESS_CONTRACT_INVALID", 中文消息，消息中点名字段)
```

| 字段 | 类型 | 规则 | 缺省 |
| --- | --- | --- | --- |
| `command` | string[] | 1..64 个参数；每个 ≤4096 字符、不含 NUL/LF/CR；**首元素必须绝对路径** | 必填 |
| `workDirRoot` | string | 可空；非空时必须绝对路径、≤4096 字符；运行期还须落在 `agent.process.work-dir` 内 | 空（用配置根） |
| `timeoutMs` | integer | 整数毫秒，`1..1_800_000`（30min） | `60_000` |
| `stdin` | JSON | 可空；序列化后 ≤1MiB；本卡只校验结构，不读取 artifact | `null` |
| `env` | object<string,string> | ≤32 项；变量名 `[A-Za-z_][A-Za-z0-9_]{0,63}`；值 ≤4096 字符、无控制字符；**禁止覆盖执行器保留变量**（PATH/SystemRoot/JAVA_HOME/CLASSPATH/HTTP(S)_PROXY 等） | `{}` |
| `maxOutputBytes` | integer | `1..1_048_576`；只能下调，不能突破 1MiB 硬上限 | `1_048_576` |

`command` 是 argv 数组而非 shell 字符串，执行器从结构上无法做字符串拼接；首元素绝对路径在发布期还应由 T21b 校验“文件存在且属于允许目录”（本卡只校验“是绝对路径”）。

直接 `new ProcessContract(...)` 也会走同一个紧凑构造器校验，避免绕过 `parse`。

## 4. 五条执行不变量与测试映射

1. **cwd 必须位于允许根之下**
   - 每次调用在配置根（或契约给出的、位于配置根内的子根）下 `Files.createTempDirectory(root, "call-")`。
   - 配置根与契约根都用 `toRealPath()` 解析后做 `startsWith`，阻止 `..`、symlink 逃逸。
   - 越界/未配置/不可访问统一 `403 PROCESS_WORKDIR_DENIED`。
   - `callDir` 在 `finally` 中递归删除；删除失败只记 DEBUG/WARN 日志，不改变已得到的结果。
   - 测试：`passesArgumentsWithoutShell`（断言 cwd 位于 root 下且目录名以 `call-` 开头）、`chineseAndSpaceInWorkingDirectoryArePreserved`（允许根本身含中文/空格时 cwd 原样保留）、`rejectsWorkDirOutsideAllowedRoot`、`@AfterEach assertNoCallDirs()`（所有用例都验证调用后 root 为空）。

2. **不经过 shell，argv 原样到达**
   - `new ProcessBuilder(new ArrayList<>(command))`，不拼 `cmd /c`、不拼引号。
   - 参数含空格、中文、shell 元字符时原样到达；`$HOME;%PATH%` 不会被展开。
   - 测试：`passesArgumentsWithoutShell` 断言 `argc=3`、`arg=a b`、`arg=中文 参数`、`arg=$HOME;%PATH%`。
   - 已知 Windows JDK 限制：`ProcessImpl` 会丢弃参数内部的 ASCII 双引号（`quote"inside` → `quoteinside`），这不是 shell 行为；因此测试用 shell 元字符而不是内嵌双引号证明“无 shell”。

3. **超时必杀整棵树**
   - `ProcessTree.awaitExit(process, timeout)`；超时返回 false 后先 `killTree` 再抛
     `504 PROCESS_TIMEOUT`，`finally` 再检查一次存活并补杀。
   - 测试：`timeoutKillsTheChildProcess` 让 worker 写 PID 后睡 10s，800ms 超时后断言
     `504/PROCESS_TIMEOUT`，并轮询 `ProcessHandle.of(pid)` 确认子进程已消失。

4. **stdout/stderr 各自 1MiB 硬上限，截断不 OOM**
   - 每条流一个定长环形缓冲（`maxOutputBytes`，默认 1MiB），只保留尾部；总量超出置
     `truncStdout/truncStderr`，不会把 2MiB 洪泛全部放进内存。
   - 测试：`floodIsTruncatedAtOneMiB`（stdout 2MiB，断言恰好 1,048,576 字节且 `truncStdout=true`）、
     `stderrFloodIsAlsoTruncatedAtOneMiB`（stderr 2MiB，断言 `truncStderr=true` 且 stdout 为空）。

5. **环境变量白名单，绝不全量透传**
   - `ProcessTree.builder(..., environment != null)` 先 `clear()`，再注入 `minimalEnvironment()`
     （Windows：SystemRoot/SystemDrive/WinDir/PATH/PATHEXT/TEMP/TMP/COMSPEC/处理器与 OS/用户目录等；
     非 Windows：PATH/HOME/TMPDIR/LANG/LC_ALL/OS），最后叠加契约 `env`。
   - 最小集是从父进程**按名单逐个读取**，不是 `System.getenv()` 整体复制；因此 `JAVA_HOME`、
     `CLASSPATH`、`HTTP_PROXY/HTTPS_PROXY` 等不会进入子进程。
   - 测试：`childEnvironmentIsWhitelistOnly` 让 worker 打印 `System.getenv()`，断言契约变量存在、
     父进程独有的非白名单变量不存在、代理变量不存在、PATH 存在；契约层另有
     `rejectsInvalidEnvironmentEntries` 拒绝保留变量。

补充护栏：

- `agent.process.enabled` 默认 `false`；关闭时在解析根、建目录、起进程之前直接
  `403 PROCESS_DISABLED`。测试 `disabledSwitchSkipsProcessAndCallDir` 断言 root 为空。
- 非 0 退出 → `502 PROCESS_EXITED`，消息带 exitCode 与 stderr 尾部 2KiB；启动失败 →
  `502 PROCESS_START_FAILED`。
- 读取线程为虚拟线程，超时杀树后 join，避免管道未排空导致子进程阻塞。

## 5. 红 → 绿

### 5.1 红阶段（先写测试，全部因桩未实现而失败）

命令（在 `代码/agent-platform` 下，先 `source .tools/env-load.sh` 并设置 `JAVA_HOME`）：

```bash
mvn -B -ntp -pl skill-registry test -Dtest='ProcessContractTest,ProcessAdapterTest,ProcessTreeTest' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

原文（详见 `red-phase.log`）：

```text
[ERROR] Tests run: 9, Failures: 7, Errors: 2, Skipped: 0, Time elapsed: 0.736 s <<< FAILURE! -- in cn.agentplatform.skill.domain.ProcessContractTest
[ERROR] Tests run: 8, Failures: 4, Errors: 4, Skipped: 0, Time elapsed: 0.065 s <<< FAILURE! -- in cn.agentplatform.skill.infra.ProcessAdapterTest
[ERROR] Tests run: 3, Failures: 0, Errors: 3, Skipped: 0, Time elapsed: 0.010 s <<< FAILURE! -- in cn.agentplatform.skill.infra.ProcessTreeTest
[ERROR] Tests run: 20, Failures: 11, Errors: 9, Skipped: 0
[INFO] BUILD FAILURE
```

典型失败原因（全部是桩）：

```text
Caused by: java.lang.UnsupportedOperationException: T21a red phase: ProcessContract.parse
Caused by: java.lang.UnsupportedOperationException: T21a red phase: ProcessTree.builder
```

红阶段共 20 条：契约 9、适配器 8、进程树 3。实现后补了 stderr 洪泛
（`stderrFloodIsAlsoTruncatedAtOneMiB`）与中文/空格 cwd（`chineseAndSpaceInWorkingDirectoryArePreserved`）
2 条，因此全绿为 22 条；这是新增覆盖，不是把红用例删掉。

### 5.2 绿阶段

同一命令，原文（详见 `green-targeted.log`）：

```text
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.177 s -- in cn.agentplatform.skill.domain.ProcessContractTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.665 s -- in cn.agentplatform.skill.infra.ProcessAdapterTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.912 s -- in cn.agentplatform.skill.infra.ProcessTreeTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 5.3 `ServiceLifecycle` 回归

`ServiceLifecycle` 只替换启动/杀树调用，未改生命周期语义。回归命令：

```bash
mvn -B -ntp -pl skill-registry test -Dtest='ServiceLifecycle*Test' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

原文（详见 `green-servicelifecycle.log`）：

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.379 s -- in cn.agentplatform.skill.application.ServiceLifecycleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 6. Windows 实机验证（taskkill 版本与结果）

环境：

```text
Microsoft Windows 11 Pro build 26200
taskkill: C:\Windows\system32\taskkill.exe
FileVersion:    10.0.26100.8457 (WinBuild.160101.0800)
ProductVersion: 10.0.26100.8457
```

手工复现“父进程 + 两个子进程”并杀树（脚本与完整输出见本目录 `windows-taskkill.txt`）：

```powershell
$p = Start-Process cmd.exe -ArgumentList '/c','ping -n 30 127.0.0.1 > nul' -PassThru
# 父 PID 66228；子 PID 68160、53456
taskkill /T /F /PID 66228
```

原文输出（exit code 0）：

```text
SUCCESS: The process with PID 68160 (child process of PID 66228) has been terminated.
SUCCESS: The process with PID 53456 (child process of PID 66228) has been terminated.
SUCCESS: The process with PID 66228 (child process of PID 63688) has been terminated.
```

（最后一行括号里的 `63688` 是启动 `cmd.exe` 的 PowerShell 进程；taskkill 对根进程也会标注其父进程，
这不影响终止结果。）

杀后 500ms 检查：`parentAlive=False`，两个子进程均不再存活。说明 `/T` 确实沿父子关系递归，
不会只杀直接子进程而留下孤儿。

自动化侧由 `ProcessTreeTest.killTreeKillsParentAndDescendant` 与
`ProcessTreeTest.killTreeByHandleAlsoKillsDescendants` 覆盖：worker 的 `tree` 模式会再启动一个
`FakeProcessWorker sleep` 子进程，测试读取两个 PID，杀树后 15s 内轮询确认两者都不再存活。

## 7. 未覆盖（明确留给后续卡）

- **真实 `AlgorithmWorker`**：本卡只用同 JVM 的 `FakeProcessWorker` 验证协议与生命周期，未接真实算法 worker。
- **artifact 输入**：`stdin` 只校验 JSON 结构与 1MiB 上限，不读取对象存储、不解析 `artifactId`、不下发文件；SSRF/越权读取属于 T21b。
- **并发与配额**：没有全局/租户并发信号量、队列、速率限制；每次 `execute` 独立执行。
- **取消**：没有 `cancel` API，进程只能等超时或正常退出；`cancellable` 标志未接执行侧。
- **容器化/网络隔离**：只做到“不注入代理”，不是内核级断网；容器、namespace、cgroup、只读根文件系统均未做。
- **输出 schema 映射**：`stdout` 文本到技能 `outputSchema` 的 JSON 解析/校验未做，由 T21b 适配层决定。
- **非 Windows 实机**：`destroy` 子进程路径有代码与单元测试逻辑，但本机只在 Windows 11 上跑过；macOS/Linux 的进程树行为未实机验证。
- **`taskkill` 不可用时的降级**：代码保留 `destroy/destroyForcibly` 兜底，未构造“taskkill 缺失”的故障注入测试。
- **symlink 竞态**：`toRealPath()` 校验后到 `createTempDirectory` 之间理论上存在目录替换竞态；当前部署模型（单机、受控根）接受该风险。
- **最小环境完备性**：Windows 名单按 JDK 21 实测；Python/CUDA/本地 SDK 可能还需要额外变量，届时按需扩充名单，不能改成全量透传。

## 8. 给 T21b 的接口说明（只写建议，本卡未改这些文件）

### 8.1 `ProcessContract.parse` 的入参形态

建议发布契约把进程配置放在 `process` 字段，T21b 在 `SkillCatalog.normalize` 中调用：

```java
ProcessContract process = ProcessContract.parse(Json.tree(body.get("process")));
```

期望的 JSON：

```json
{
  "command": ["C:\\Program Files\\Java\\jdk-21\\bin\\java.exe", "-jar", "algorithm-worker.jar"],
  "workDirRoot": "C:\\agent-platform\\var\\process\\skill-123",
  "timeoutMs": 60000,
  "stdin": {"artifactId": "art_..."},
  "env": {"ALGORITHM_MODE": "batch"},
  "maxOutputBytes": 1048576
}
```

- 除 `command` 外全部可选；`timeoutMs`、`maxOutputBytes` 缺省分别为 60000、1048576。
- `workDirRoot` 是“允许根下的子根”；不填则直接用配置根 `agent.process.work-dir`。
- 解析失败抛 `PlatformException(400, "PROCESS_CONTRACT_INVALID", 中文消息)`，可直接由现有 400 通道返回。
- 本卡**不校验可执行文件存在**；发布期如需“文件存在且属于允许目录”，在 T21b 里补。

### 8.2 `ProcessAdapter` 的方法签名与返回结构

```java
// Spring Bean；构造参数来自配置：
//   agent.process.enabled  (boolean, 默认 false)
//   agent.process.work-dir (String, 允许根；为空则任何调用都 403)
public ProcessAdapter.Result execute(ProcessContract contract)

public record Result(
        int exitCode,
        String stdoutTail,
        String stderrTail,
        long durationMs,
        boolean truncStdout,
        boolean truncStderr) {}
```

行为约定：

- 成功（exitCode=0）返回 `Result`；文本是“尾部窗口”（默认最多 1MiB），不是全量。
- 非 0 → `PlatformException(502, "PROCESS_EXITED", 带 exitCode 与 stderr 尾部 2KiB)`。
- 超时 → `PlatformException(504, "PROCESS_TIMEOUT")`，抛出前已杀树。
- 开关关闭 → `PlatformException(403, "PROCESS_DISABLED")`；无配置根/根不存在/契约根越界 →
  `403 PROCESS_WORKDIR_DENIED`；启动失败 → `502 PROCESS_START_FAILED`。
- 不读 `stdinJson`、不写文件到调用方目录；`callDir` 由适配器创建并清理。

### 8.3 接入 `SkillCatalog` / `SkillAdapters` 的具体行（建议）

先约定新类型名（示例用 `PROCESS`；若产品命名用 `LOCAL`，同样改这四处）：

1. **`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:304`**
   类型集合加入新类型：

   ```java
   if (!Set.of("HTTP", "MCP", "ALGORITHM", "PROCESS").contains(type))
   ```

2. **`SkillCatalog.java:320`**
   进程技能没有 HTTP endpoint，跳过 endpoint 校验：

   ```java
   } else if (!type.equals("ALGORITHM") && !type.equals("PROCESS")) SkillRules.endpoint(endpoint);
   ```

3. **`SkillCatalog.java:346-356`**
   允许 `PROCESS + SYNC`（建议进程只支持同步）：

   ```java
   || type.equals("PROCESS") && kind.equals("SYNC")
   ```

4. **`SkillCatalog.java:306-320`（`result` 创建后、持久化前）**
   校验并落库进程契约：

   ```java
   if (type.equals("PROCESS")) {
       ProcessContract parsed = ProcessContract.parse(Json.tree(body.get("process")));
       result.put("process", /* 规范化后的 Map，例如 command/timeoutMs/env/maxOutputBytes */);
   }
   ```
   注意 `ProcessContract` 是 record，直接用 `Json.write(parsed)` 会把 `Duration` 序列化成对象；
   建议 T21b 在 `ProcessContract` 上补一个 `toJson()`/`asMap()`，或在这里手工组 Map。
   另外进程技能不应有出站凭据：`authRef` 必须为空，`SkillRules.authScheme` 传 `NONE`。

5. **`skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java:69-83`**
   注入 `ProcessAdapter`（字段 + 三参构造器加一个参数）。`bind`（:91-94）与
   `persistentBinding`（:96-98）不需要特殊分支；`observe`（:100-115）对非 ALGORITHM 会自动走
   `invoke`，无需改。

6. **`SkillAdapters.java:162-191` 的 `invoke` switch**
   在 `default` 前加：

   ```java
   case "PROCESS" -> {
       ProcessContract contract = ProcessContract.parse(Json.tree(skillContract.get("process")));
       ProcessAdapter.Result result = processAdapter.execute(contract);
       var structured = Json.mapper().createObjectNode()
               .put("exitCode", result.exitCode())
               .put("stdout", result.stdoutTail())
               .put("stderr", result.stderrTail())
               .put("durationMs", result.durationMs())
               .put("truncStdout", result.truncStdout())
               .put("truncStderr", result.truncStderr());
       yield new Raw(structured, List.of(), result.stdoutTail(), null, null);
   }
   ```
   其中 `skillContract` 是 `invoke` 的契约 Map；`Raw` 的字段见 `SkillAdapters.java:23-36`。
   错误处理建议：`ProcessAdapter` 抛的是 `PlatformException`；如果执行层希望统一成
   `SkillAdapters.Failure`/`PARAM_ERROR` 语义，在 case 内捕获并转换，否则 `502/504` 会直接透出到 API。

7. **`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:289`**
   同步主路径走 `adapters.observe(...)`，第 6 步完成后无需改；`:841` 是 HTTP 对账专用
   `invoke`，与进程无关。

8. **配置（本卡无权改 `application.yml`，留给 T21b/编排者）**：

   ```yaml
   agent:
     process:
       enabled: false          # 默认关闭；打开前必须确认 work-dir 权限与部署边界
       work-dir: ${AGENT_PROCESS_WORK_DIR:}
   ```

   打开后目录必须存在且可写；不要在 `work-dir` 放任何凭据/源码。

## 9. 复现命令

```bash
cd 代码/agent-platform
source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"

# 定向（22 条）
mvn -B -ntp -pl skill-registry test -Dtest='ProcessContractTest,ProcessAdapterTest,ProcessTreeTest' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2

# 回归（3 条；需要 .tools/env-load.sh 提供数据库连接）
mvn -B -ntp -pl skill-registry test -Dtest='ServiceLifecycle*Test' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

注意：不要跑 `skill-registry` 模块全量（存在与本卡无关的既有失败），只跑上述定向用例。
