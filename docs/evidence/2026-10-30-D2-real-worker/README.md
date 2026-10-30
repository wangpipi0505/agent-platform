# D2：真实算法 worker 接入 PROCESS 执行链（红→绿证据）

任务：把已交付的真实算法 worker（`visibility-trajectory` 的 `algorithm-worker.jar`）接进平台
PROCESS 执行链，用可重复的集成测试证明"平台能按同一份契约真实启动它、喂入输入、收回并验收结果"。

**结论**：真实 jar 在 JDK21 下经 `ProcessContract` + `ProcessAdapter` 成功执行，`Tests run: 5,
Failures: 0, Errors: 0, Skipped: 0`（`BUILD SUCCESS`）。首轮红跑证明"公开三参入口跑不通真实
worker"是一个真实存在的行为差异，第二轮改用生产同款入口后转绿。**未修改任何生产代码。**

## 1. 被测对象

| 项 | 值 |
| --- | --- |
| jar 路径 | `G:/Code/Code-V3/visibility-trajectory/target/algorithm-worker.jar` |
| 字节数 | 54,628,349 |
| mtime | 2026-10-27 19:43:59 +08:00 |
| sha256 | `ed37b290d4c0765b14b3813bede38f4ee80be1ce047695e6096555c9def16a7d` |
| Main-Class | `com.tx.alg.worker.AlgorithmWorker` |
| 运行 JDK | Temurin `21.0.12+8-LTS`（`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot`） |
| 构建工具 | Apache Maven 3.9.16（离线 `-o`，仓库 `.tools/m2`） |

输入夹具（测试内嵌为文本块，并与该文件做字节数与 sha256 双校验）：

| 项 | 值 |
| --- | --- |
| 源文件 | `G:/Code/Code-V3/visibility-trajectory/examples/worker/input/target.csv` |
| 字节数 | 684（3 行 × 20 列，LF） |
| sha256 | `fb7e53bdcd58a7438bb0f608d523714524e605f1424b2caaa773e2fdefe94199` |

调用参数取 worker 自带最小样例：`operation=orbit_visibility_search`，1 条弹道（`file=trajectory`）+
1 颗卫星 + `startOffsetSec=0`、`stepSizeSec=1`、`numCalculations=1`（单采样点，秒级完成）。

## 2. 新增测试

文件：`skill-registry/src/test/java/cn/agentplatform/skill/infra/RealWorkerProcessIT.java`
（477 行，`@Tag("integration")` + `@Timeout(180)`）。

jar 不存在时执行 `Assumptions.abort("真实算法 worker 未构建: …")` —— 记为 skipped，**不伪造通过**。

| 用例 | 路径 | 断言要点 |
| --- | --- | --- |
| `platformEntryRunsRealWorkerAndAcceptsEnvelope` | 平台（生产同款五参 `execute`） | exit 0、`result` 计数 1/1/3、`best.visibleDurationSec=2.0`、`files[0]` 声明、落盘文件 bytes/sha256 与声明一致、明细回显 `requestId`/`protocol`、callDir 已回收 |
| `platformEntryMapsOutOfRangeParameterToInputInvalid` | 平台 | `stepSizeSec=0` → exit 2 → `PlatformException(422, PROCESS_INPUT_INVALID)`，消息含 `exitCode=2` 与 worker 自述原因 |
| `publicEntryWithoutInputFilesIsRejected` | 平台三参公开入口 | 固定 `files={}` → worker exit 2 → 422（钉住"公开入口跑不通真实 worker"的现状，防回归误判） |
| `rawWorkerEnvelopeIsSingleJsonWithEchoedRequestId` | 直接起 jar | exit 0、stdout 有且只有一个 JSON、`requestId` 回显、`result` 非空、stderr 只含 5 行阶段日志 |
| `rawWorkerRejectsOutOfRangeParameterWithFailureEnvelope` | 直接起 jar | exit 2、`ok=false`、`error.code=INPUT_INVALID`、`error.details.field=stepSizeSec` |

## 3. 命令

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp -o -Dmaven.repo.local=$PWD/.tools/m2 -pl skill-registry test -Dtest=RealWorkerProcessIT
```

（去掉 `-o` 即为联网解析；实测离线仓库已含全部依赖。）

## 4. 红跑（首轮，`red-run.txt`）

首轮用 `adapter.execute(contract, request, tenant)` 三参公开入口 —— 该入口固定以 `files={}` 组信封，
而真实 worker 的 `files` 契约要求 1..16 项，于是必然 `exit 2`。原文：

```
[ERROR] Tests run: 5, Failures: 1, Errors: 1, Skipped: 0, Time elapsed: 1.295 s <<< FAILURE! -- in cn.agentplatform.skill.infra.RealWorkerProcessIT
[ERROR] cn.agentplatform.skill.infra.RealWorkerProcessIT.platformEntryRunsRealWorkerAndAcceptsEnvelope -- Time elapsed: 0.190 s <<< ERROR!
[ERROR] cn.agentplatform.skill.infra.RealWorkerProcessIT.platformEntryMapsOutOfRangeParameterToInputInvalid -- Time elapsed: 0.192 s <<< FAILURE!
[ERROR]   RealWorkerProcessIT.platformEntryRunsRealWorkerAndAcceptsEnvelope:133 ? Platform 算法进程判定输入或数据非法（exitCode=2），stderr 尾部: 读取请求
失败：INPUT_INVALID，文件映射数量应为1至16
[ERROR]   RealWorkerProcessIT.platformEntryMapsOutOfRangeParameterToInputInvalid:179 stderr 尾部必须指出非法字段: …（exitCode=2）… 失败：INPUT_INVALID，数值字段超出范围或类型非法
[ERROR] Tests run: 5, Failures: 1, Errors: 1, Skipped: 0
```

诊断与修复（只改测试）：

1. **错误 1 的根因**不是 worker 有问题：三参公开入口 `execute(contract, request, tenant)` 内部把
   `inputFiles` 传 `null`，信封里永远是 `files={}`。生产路径 `SkillAdapters#executeLocalProcess`
   （`SkillAdapters.java:538-607`）走的是包私有五参 `execute(contract, request, tenant, inputFiles,
   outputFiles)`，并在 `filesEnabled()` 时把输入物化到 `callDir/input/`。测试改为同一条五参路径，
   追加 `(callDir, deadline) -> materializeInputs(callDir)` 与 `(callDir, output, deadline) -> …`
   两个回调。
2. **失败 2 的断言本身过强**：真实 worker 只把字段名写进 stdout 失败信封的
   `error.details.field`，stderr 只写「数值字段超出范围或类型非法」；平台按 §4.6 在 exit 2 时只把
   **stderr 尾部**并入错误消息，不透出信封。因此平台侧断言改为 worker 的失败码 + 失败原因，字段级
   定位由直接起 jar 的负例（`error.details.field=stepSizeSec`）承担。

## 5. 绿跑（`green-run.txt`、`surefire-report.txt`）

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.334 s -- in cn.agentplatform.skill.infra.RealWorkerProcessIT
[INFO] Results:
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`surefire-report.txt` 为 `skill-registry/target/surefire-reports/cn.agentplatform.skill.infra.RealWorkerProcessIT.txt`
原始拷贝：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.334 s`。

重复执行一次（`green-run-2.txt`），结果稳定且无残留：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.370 s -- in cn.agentplatform.skill.infra.RealWorkerProcessIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

（运行后 `%TEMP%/junit-*/allowed-root/call-*` 计数为 0，即每次调用目录都被回收。）

## 6. 真实 stdout / stderr 摘要

成功响应（截断 500 字符，完整原文见 `green-run.txt` 的 `[D2-real-worker]` 行）：

```json
{"protocol":"algorithm-process/1","requestId":"real-worker-1","ok":true,"result":{"resultMode":"SUMMARY","referenceUtc":"2026/07/01 10:00:00.000","payloadType":"对地监视","counts":{"targets":1,"satellites":1,"payloads":1,"results":1,"visibleResults":1,"visibleArcs":1,"visiblePoints":3},"best":{"offsetSecond":0,"launchTimeUtc":"2026/07/01 10:00:00.000","visibleDurationSec":2.0,"visibleArcCount":1,"visiblePointCount":3}},"files":[{"name":"visibility_detail","path":"output/visibility_detail.json","cont…
```

stderr（成功时只有日志，逐行核对）：

```
读取请求 | 校验文件 | 开始计算 | 写入明细 | 完成
```

负例（直接起 jar，`stepSizeSec=0`）：

```json
{"protocol":"algorithm-process/1","requestId":"real-worker-1","ok":false,"error":{"code":"INPUT_INVALID","message":"数值字段超出范围或类型非法","details":{"field":"stepSizeSec"}}}
```

平台侧执行事实（`ProcessAdapter` 失败事实日志，节选）：

```
code=PROCESS_INPUT_INVALID, command=[…\bin\java.exe, -jar, G:\Code\Code-V3\visibility-trajectory\target\algorithm-worker.jar],
workDir=…\allowed-root\call-9956304221254907420, killReason=null, dumpDir=…\failed-call-9956304221254907420
```

—— 证明平台确实以 argv 启动真实 jar、在允许根内的 `call-*` 目录执行，并在成功后回收该目录
（`@AfterEach` 断言允许根下无 `call-` 残留，实测通过）。

## 7. 未修改生产代码

本轮没有发现必须修的生产缺陷：§4.5 的"stdout 有且只有一个 JSON / `requestId` 回显 / `ok=true`"
由 `ProcessAdapter` 在校验，§4.6 的退出码映射实测正确（2 → 422 `PROCESS_INPUT_INVALID`）。
红跑暴露的是**测试选错入口**与**断言过强**，均在测试内修正。

`git status` 仅两条新增（未跟踪）：

```
?? docs/evidence/2026-10-30-D2-real-worker/
?? skill-registry/src/test/java/cn/agentplatform/skill/infra/RealWorkerProcessIT.java
```

## 8. 偏差与设计取舍

1. 契约文档写 `ProcessContract.parse(Map)`，实际签名是 `parse(JsonNode)`（无 Map 重载），测试用
   `ProcessContract.parse(Json.tree(Map.of(...)))` 构造，语义一致。
2. 任务书写"用 `execute(contract, request)`"，但三参公开入口无法给 worker 喂输入文件；真实链路必须
   走包私有五参 + `inputFiles`/`outputFiles` 回调（与 `SkillAdapters.java:572` 生产接线同一路径）。
   测试位于同包，可直接调用。
3. 平台在 exit 2/3/4 时**不透出** worker 失败信封的 `error.code`/`error.details`，只拼 stderr 尾部；
   这是 §4.6"退出码优先"的既有取舍，本次不改。若后续需要字段级定位，建议 worker 把字段写进 stderr，
   或平台解析失败信封的 `details` 并附进 `PlatformException` 的 details。
4. `output/visibility_detail.json` 的字节数/摘要随 `requestId` 变化（`result.requestId` 落进明细），
   仓库内 `examples/worker/expected/minimal/response.json` 的 9459 字节 / `af38dd87…` 对应
   `requestId=sample-minimal`，本测试用 `real-worker-1` 得到 9458 字节，二者不同属正常；因此断言用
   "声明值与真实文件一致 + 明细回显 requestId"，而不是写死摘要。

## 9. 未覆盖项

1. 只覆盖 `orbit_visibility_search` 一个操作；未覆盖可选参数 `targetSampleStepSec`、
   `trajectoryPointOutput`、`orbitModel`，也未覆盖多卫星 / 多目标 / 大 `numCalculations` 的耗时与
   超时（`PROCESS_TIMEOUT`）路径。
2. 未覆盖 exit 3（`CALCULATION_FAILED`）与 exit 4（`OUTPUT_WRITE_FAILED`）：jar 未提供注入开关，
   构造真实计算失败或写盘失败会依赖环境（只读目录、磁盘满），测试会变成不可重复的环境测试。
   这两条退出码语义由既有 `ProcessProtocolTest` 的 FakeProcessWorker 用例覆盖。
3. 输入只有一份内嵌 CSV；真实业务会需要多份外部数据文件（`files` 1..16 项），未覆盖多文件映射。
4. jar 路径按任务书硬编码为 `G:/Code/Code-V3/visibility-trajectory/target/algorithm-worker.jar`，
   不等于仓库内构建产物；换机 / 未构建时测试会 skipped 而非失败（有意为之，避免假绿也避免假红）。
5. 平台成功路径不下发 worker stderr（`Output` 无 stderr 字段），因此"stderr 只含日志"只在直接起 jar
   的用例中断言；平台路径只能观察 `fact` 与失败消息中的 stderr 尾部。

## 10. 复现注意

- Maven 在 Windows 控制台把中文按 **GBK** 写出，直接 `tee` 得到的 `*.txt` 是 GBK 字节；本目录两份
  运行日志已用 `iconv -f GBK -t UTF-8//TRANSLIT` 转换（内容未增删，只改编码），`surefire-report.txt`
  是 UTF-8 原始拷贝。复现时若中文乱码，按同样方式转换。
- 证据日志统一用 `.txt`：`.gitignore:7` 忽略 `**/*.log`。
- 离线构建要求 `.tools/m2` 已含 `admin-common`/`skill-registry` 等模块；缺失时去掉 `-o`。
