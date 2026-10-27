# PLAT-21d 证据：PROCESS 执行加固（并发配额 / 取消 / 失败转储 / 审计 / 白名单 / 容器化评估）

- 日期：2026-10-28
- 泳道：`skill-registry`（T21d，PLAT-21 加固）
- 基线：开工时 HEAD `75d9d8c`（T21c 已入库 `ea6532a`+`29c5db8`）；工作期间另一泳道入库 `c38ea4c`（仅 engine-runtime，与本模块无依赖，回归结论不受影响）
- 状态：工作树改动，**未 git add / 未 commit**（提交由编排者完成）
- 依据：派发卡片 S-4（T21d）；`算法进程接入-平台侧方案与契约-20261027.md` §5.5 / §5.6 / §5.7 / §12；台账 T21d 行（并发隔离、取消、审计与运行详情、临时目录回收、容器化评估）
- 环境：`source .tools/env-load.sh`（PG `127.0.0.1:25432`）；`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`；`-Dsurefire.runOrder=reversealphabetical`；`-Dmaven.repo.local=.tools/m2`，带 `-pl skill-registry`、不带 `-am`

## 1. 问题

T21a–T21c 把 PROCESS 技能接进了执行链，但收口时明确遗留六项，全部归本卡：

1. **无取消通道**：引擎 run/node 取消只落 engine-runtime 的库表状态，没有任何事件到达 skill-registry；运行中的算法进程树只能等超时，用户"取消运行"杀不掉真正的进程。
2. **临时目录只回收成功路径**：`finally` 里删 callDir 对失败路径同样生效（T21a 实现），但没有失败现场保留能力——排查"进程为什么失败"时无现场可看。
3. **无并发配额**：N 个调用并发到达就起 N 个进程，资源挤兑没有任何防线（契约 §2 现状第 4 条）。
4. **无执行审计**：成功事实只有 `modelSummary="本地进程返回成功，耗时 Xms"`，命令模板/退出码/工作目录/截断标志都不可查。
5. **无白名单**：契约字段 `allowed-executables` 标注"未实现，归 T21d"，登记者可指向本机任意可执行文件（仅 ADMIN + 发布预检兜底）。
6. **容器化只欠评估**：Windows 下隔离边界与容器化收益/代价无书面结论。

## 2. 改动

| 文件 | 改动 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessWhitelist.java`（新增） | 白名单唯一实现：逗号/分号/换行分隔解析、`toRealPath` 优先归一、Windows 大小写归一、整路径精确匹配（不做目录前缀/通配符——白名单是"已审核程序清单"不是"信任目录"）；空集=未配置=不限制（兼容窗口） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java` | ①并发：全局 `Semaphore(max-concurrent)` + 每租户 `Semaphore(max-concurrent-per-tenant)`，排队 `queue-wait-ms` 超窗 429 `PROCESS_BUSY`，名额只在执行线程持有；②取消：`active` 注册表（键=requestId=callId）+ `cancel(String)`/`cancel(long)`/`inFlight(String)`，杀树记 `killReason`（TIMEOUT/CANCELLED/OUTPUT_OVERFLOW/ABNORMAL），被取消调用 502 `PROCESS_CANCELLED`；③回收：成功彻底删 callDir，失败/杀树/取消把现场挪成 `failed-*` 转储并按 `keep-failed-dirs`（默认 5）剪枝最旧，挪不动退回直接回收；④审计：成功事实随 `Output.fact()`（command/workDir/exitCode/durationMs/stdoutTruncated/stderrTruncated）交给调用方，失败事实走结构化 WARNING 日志，环境变量键与值一律不落；⑤执行期白名单 403 `PROCESS_EXECUTABLE_FORBIDDEN`（在建目录之前）；⑥配置校验：`enabled=true` 且白名单为空 → 装配期 IllegalStateException（契约 §7.1"未配置白名单不能开启"） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `Raw` 追加第 7 组件 `processFact`（原 4/5/6 参构造保留，存量调用零改动）；`localProcess` 传 tenantId（取自已验签上下文，解析不出跳过每租户层）与 fact；新增 `cancelLocalProcess(callId)` 句柄（接线缺口见 §4） |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java` | `envelope` 追加一行：`raw.processFact() != null` 时落 `result.process`——复用既有 `skill.execution_fact` 写入路径，不新造表/通道，经 `GET /admin/skills/tasks/{callId}/result` 运行详情可查 |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java` | 新增 7 参 @Autowired 构造（`agent.process.allowed-executables`）；`normalize` 注册期白名单校验（已配置时超白名单 400 `PROCESS_EXECUTABLE_FORBIDDEN`，空集保持兼容）；`processPrecheck` 补发布期白名单校验与 `Files.isExecutable` 可执行位校验（契约 §4.3"留 T21d"项） |
| `skill-registry/src/main/resources/application.yml` | `agent.process.*` 新增：`max-concurrent=4`、`max-concurrent-per-tenant=1`、`queue-wait-ms=30000`、`keep-failed-dirs=5`、`allowed-executables=`（全部带 `AGENT_PROCESS_*` 环境变量覆盖与注释） |
| `skill-registry/src/test/java/.../infra/FakeProcessWorker.java` | 新增 `sleepquiet`（不写 stdout 的睡眠）与 `sleepok`（读信封→睡眠→按协议回响应）两个模式：并发用例的名额持有者必须按协议正常收尾，否则 stdout 校验失败掩盖被测行为 |
| `ProcessAdapterTest` / `ProcessProtocolTest` / `SkillProcessRoutingTest`（测试适配） | AfterEach 不变量从"允许根下零条目"收窄为"零 `call-*` 目录 + `failed-*` 转储用后清理"——失败转储是 T21d 的有意保留，其余断言逐字未动 |

### 2.1 定死的口径

- **并发口径**：全局 + 每租户两层（契约 §5.5 原文语义），不是"同技能"口径；排队窗口 `queue-wait-ms` 内拿到名额就执行，超窗 429 `PROCESS_BUSY`（契约命名，替代派发卡片提示的 `PROCESS_CONCURRENCY_LIMIT`）；tenantId 取自已验签 JWT，解析不出（系统级调用）跳过每租户层、仍受全局层约束。
- **取消语义**：`cancel` 置位 + 立即杀树；无论进程是杀死的还是恰好在取消瞬间自然退出，只要取消已登记，结论一律 502 `PROCESS_CANCELLED`——不把"杀到一半的退出码"误读成算法业务失败。
- **回收口径**：清理逻辑在正常/异常/杀树三条路径都执行；失败现场默认保留最近 5 个（`keep-failed-dirs=0` 时失败也彻底回收）。既有测试的"零残留"断言据此收窄为"零 `call-*` 残留"。
- **审计口径**：成功事实落 `execution_fact.result.process`（复用既有路径）；失败事实**不落** execution_fact 行（Y-7 语义下 `PROCESS_*` 原样上抛、无终态事实行），由"错误响应（错误码+exitCode+stderr 尾部）+ 结构化日志 + failed-\* 转储"三件套承载；环境变量的键与值在任何载体中都不出现。
- **白名单匹配**：`toRealPath` 优先归一 + Windows 大小写归一 + 整路径精确匹配；空集=不限制（兼容窗口），但 `enabled=true` 强制要求非空白名单（装配期失败）。

## 3. 红 → 绿

### 3.1 红证据（两份，均保留原始输出）

- `red-baseline.log`：三个新测试类拷到 `git archive 75d9d8c` 干净基线上 → **编译红 54 处**（`ProcessAdapter` 七参构造/`cancel`/`inFlight`/`Output.fact`、`ProcessWhitelist`、`SkillCatalog` 七参构造、`Raw.processFact` 在基线不存在）。证明这些测试确实是本卡新增、基线不可能满足。
- `red-mutation.log`：在本卡实现上把 5 处行为逐一回退（取消不置位不杀树、失败一律直接回收、配额直接放行、执行期+注册期白名单失效、审计事实置 null），同一组测试 → **20 tests, 10 failures + 1 error**，五个变异点全部被钉住：

```
[ERROR] Tests run: 20, Failures: 10, Errors: 1, Skipped: 0
[ERROR]   SkillProcessAuditTest.successfulExecutionLandsAuditFactInExecutionFactAndTaskResult:190 envelope 必须携带 process 审计事实 ... ==> expected: <true> but was: <false>
[ERROR]   SkillProcessWhitelistRegistrationTest.registrationRejectsExecutableOutsideWhitelistWith400:49 ... Expected PlatformException to be thrown, but nothing was thrown.
[ERROR]   ProcessAdapterHardeningTest.cancelKillsTheRunningTreeAndReportsCancelled:79 expected: <PROCESS_CANCELLED> but was: <PROCESS_OUTPUT_INVALID>
[ERROR]   ProcessAdapterHardeningTest.executionRejectsExecutableOutsideWhitelist:298 Expected PlatformException to be thrown, but nothing was thrown.
[ERROR]   ProcessAdapterHardeningTest.factRecordsKillReasonOnTimeout:371 expected: <1> but was: <0>
[ERROR]   ProcessAdapterHardeningTest.failureRetainsDumpAndPrunesOldestBeyondLimit:127 最旧的转储必须被剪枝 ... ==> expected: <true> but was: <false>
[ERROR]   ProcessAdapterHardeningTest.globalLimitRejectsExcessWithReadable429:190 Expected PlatformException to be thrown, but nothing was thrown.
[ERROR]   ProcessAdapterHardeningTest.outputFactCarriesAuditFactsWithoutSensitiveEnvValues:344 成功执行必须携带审计事实 ==> expected: not <null>
[ERROR]   ProcessAdapterHardeningTest.tenantLimitIsIndependentFromOtherTenants:264 Expected PlatformException to be thrown, but nothing was thrown.
[ERROR]   ProcessAdapterHardeningTest.timeoutKillRetainsFailureDump:160 超时杀树路径保留失败现场 ==> expected: <1> but was: <0>
[ERROR]   ProcessAdapterHardeningTest.chineseAndSpacedWorkDirCreateExecuteKillReclaim:391 NullPointer ... Output.fact() is null
```

变异标记（`MUTATION-*` 注释）在重放后全部移除（`grep -c MUTATION` = 0），移除后 20/20 回绿。

### 3.2 绿证据

验收命令（新增类 + T21a-c 全部 PROCESS 测试类 + 相邻回归多类同跑）：

```bash
cd "G:/207/项目/算法重组工具/代码/agent-platform" && source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl skill-registry test \
  -Dtest='ProcessAdapterHardeningTest,SkillProcessWhitelistRegistrationTest,SkillProcessAuditTest,ProcessContractTest,ProcessAdapterTest,ProcessTreeTest,ProcessProtocolTest,ProcessPublishPrecheckTest,SkillProcessRegistrationTest,SkillProcessRoutingTest,SkillProcessExecutionTest,ServiceLifecycleTest,SkillExecutionTest,SkillTaskResultTest,SkillErrorEnvelopeTest,SkillClientsErrorTest,AsyncHttpExecutionTest,McpAdapterTest,AlgorithmProtocolTest,SkillAuthSchemeRegistrationTest,SkillIdentityRegistrationTest,SignedCallbackTest,SkillAttemptPropagationTest' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
  -Dsurefire.runOrder=reversealphabetical \
  -Dmaven.repo.local="G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2"
```

结果：**BUILD SUCCESS，Tests run: 270, Failures: 0, Errors: 0, Skipped: 0**（全文 `green.log`）。

### 3.3 回归对照（新增失败 = 0）

- **T21a–T21c 八类 64 例全部保持绿**：ProcessContractTest 17 + ProcessAdapterTest 11 + ProcessTreeTest 3 + ProcessProtocolTest 11 + ProcessPublishPrecheckTest 10 + SkillProcessRegistrationTest 6 + SkillProcessRoutingTest 5 + SkillProcessExecutionTest 1 = 64（与台账"8 类 64/64"一致）。
- **本卡新增 20 例**：ProcessAdapterHardeningTest 15（取消 2、回收 4、并发 3、白名单 3、审计 2、中文空格路径 1）+ SkillProcessWhitelistRegistrationTest 3 + SkillProcessAuditTest 2（真实 PG）。
- **相邻回归 12 类 186 例全绿**：ServiceLifecycleTest 3（共享 ProcessTree 的杀树原语）、SkillExecutionTest 25、SkillTaskResultTest 3、SkillErrorEnvelopeTest 3、SkillClientsErrorTest 7、AsyncHttpExecutionTest 29、McpAdapterTest 38、AlgorithmProtocolTest 54、SkillAuthSchemeRegistrationTest 7、SkillIdentityRegistrationTest 9、SignedCallbackTest 3、SkillAttemptPropagationTest 5。
- **既有环境失败对照（非本卡引入）**：`CallbackHttpTest` 32 例在 `setup` 处 `IllegalState SECURITY_MODE_REQUIRED`（`agent.security.mode` 未配置）。已在 `git archive 75d9d8c` 干净基线上复现同样 32 错误（`baseline-callback-preexisting.log`），与本卡改动无关，故验收集剔除该类；该环境缺口属 IAM/authority 部署输入，归环境侧。
- **构建环境修正（不改源码）**：`.tools/m2` 里的 admin-common 快照（10-27 23:25）落后于 HEAD 的 PLAT-12 改动（`Actor.subject()`），`-pl skill-registry` 编译在基线上即失败；按 HEAD 执行 `mvn -pl admin-common install -DskipTests` 刷新本地仓库快照后，卡片验收命令原样可跑。admin-common 源码零改动。

### 3.4 Windows 空格中文路径专项证据

`windows-path-live.log`：`ProcessAdapterHardeningTest#chineseAndSpacedWorkDirCreateExecuteKillReclaim+cancelKillsTheRunningTreeAndReportsCancelled+timeoutKillRetainsFailureDump` → **3/3 绿**。覆盖全路径：

1. **创建**：允许根 `...\中文 允许 根 x y\`（含中文与空格）下新建 `call-*` 目录作为 cwd；
2. **执行**：`echoargs` worker 在该 cwd 下原样收到含中文与空格的 argv（`cwd=...\中文 允许 根 x y\call-...` 断言通过）；
3. **杀树**：`sleep` + 700ms 超时 → 504 `PROCESS_TIMEOUT`，`taskkill /T /F` 终止整棵树（取消用例另以 pidFile 实证进程真被终止、execute 以 `PROCESS_CANCELLED` 返回）；
4. **回收**：成功路径 callDir 彻底删除、失败路径挪为 `failed-*` 转储，中文+空格目录下两条路径断言均通过；审计事实 `workDir` 保留完整中文+空格路径。

辅助证据 `executable-bit-probe.md`：jshell 实测 Windows 11 上 `Files.isExecutable` 对存在的普通文件（含 .txt）恒为 true、仅对不存在文件为 false——注册期可执行位校验在 Unix 能真实拦截"未 chmod +x"，在 Windows 上是"存在性再确认"，契约 §4.3 已按实记录。

## 4. 与卡片/契约的出入（偏差单列）

1. **错误码定 `PROCESS_BUSY`（429）**：派发卡片提示 `PROCESS_CONCURRENCY_LIMIT`；契约 §5.5 是更高层权威（"获取不到且等待超过 queue-wait-ms，返回 429 PROCESS_BUSY"），按契约执行。语义为"排队+超窗可读拒绝"。
2. **并发口径**：卡片允许"同技能或全局，实现时定死"；按契约 §5.5 定死为**全局 + 每租户**两层，未做同技能维度（多技能共享同机资源，全局层才是资源防线）。
3. **取消接线缺口（如实记录）**：引擎 run/node 取消只在 engine-runtime 落库，无出站事件到达 skill-registry；本卡交付适配器层取消句柄（`ProcessAdapter.cancel` + `SkillAdapters.cancelLocalProcess`）+ 进程注册表，**无生产调用方**。接通方案（引擎取消事件 → skill-registry 内部端点 → 句柄）与缺口已写入代码注释与 `ProcessAdapter.cancel` Javadoc。真被杀树的行为由测试实证（pidFile + `ProcessHandle.isAlive`）。
4. **失败审计不走 execution_fact**：T21c 的 Y-7 语义（`PROCESS_*` 原样上抛、不落终态事实）保持不动；失败事实由错误响应+结构化日志+失败转储承载。这是与"审计可查"目标的部分降级，见 §5。
5. **"未配置白名单拒绝发布/开启"降级为"开启时强制"**：契约 §7.1"未配置白名单不能开启"落为装配期检查（`enabled=true` 且白名单空 → 启动失败）；但"未配置白名单拒绝发布 PROCESS"未落地（空集=不限制，保持 T21b/c 兼容，避免炸存量登记路径）。白名单已配置时注册/发布/执行三道闸全部强制。
6. **三处既有测试 AfterEach 收窄**（ProcessAdapterTest/ProcessProtocolTest/SkillProcessRoutingTest）："允许根下零条目"→"零 `call-*` 条目 + 转储用后清理"。失败转储保留是本卡有意的行为变更，其余断言逐字未动。
7. **契约文档已同步**（`文档/优化方案/算法进程接入-平台侧方案与契约-20261027.md`，不在 git 仓内）：§4.3"可执行位尚未校验，留 T21d"改为已实现并注明 Windows 语义；§5.5 配置块下"尚未实现…归 T21d"标注改为"T21d 已实现"并列出仍欠的 `max-output-files`/`max-output-bytes`/files 通道。

## 5. 未覆盖 / 风险

1. **取消无生产接线**（偏差 3）：在引擎侧接通之前，"用户取消运行"仍杀不掉运行中的 PROCESS 树，只能等超时杀树。句柄本身已可用且被测试实证。
2. **失败事实不在 PG 可查**：失败审计的三件套（错误响应/日志/转储）中，日志与转储不受租户隔离与保留策略约束地留存；若需要"失败也在运行详情可见"，需要一张后续卡重新裁决 Y-7 与事实写入的关系（可能要求失败也落终态事实行，属语义变更，本卡不擅自做）。
3. **files 通道未实现**（T21c 遗留，契约 §5.5 标注保持）：请求信封 `files` 固定 `{}`，输出 `files` 数组解析后未转 artifact。
4. **`max-output-files`/`max-output-bytes`（输出文件配额）未实现**：契约 §5.5 配置块中的这两键仍未消费，已同步标注归后续卡。
5. **每租户信号量表不淘汰**：按租户有界（来自已验签 JWT）假设不做过期清理；极多租户场景下是常驻内存的少量对象。
6. **同技能并发维度未做**：若需要"单技能限流"，需在目录层或引擎层加维度（当前两层配额只按全局/租户）。
7. **容器化只评估不实现**：评估结论与触发条件见 `CONTAINERIZATION.md`——Windows 宿主容器化代价高、与 ALGORITHM（K8s Job）通道重叠，本轮不做。
8. **CallbackHttpTest 环境缺口**：`agent.security.mode` 未配置导致该类 32 例环境错误，基线逐字复现，非本卡引入；环境侧补 `AGENT_SECURITY_MODE` 等部署输入后应恢复。

## 6. 文件清单

新增：
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessWhitelist.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessAdapterHardeningTest.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillProcessWhitelistRegistrationTest.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillProcessAuditTest.java`
- `docs/evidence/2026-10-28-PLAT-21d-hardening/`（本目录：README.md、CONTAINERIZATION.md、red-baseline.log、red-mutation.log、green.log、windows-path-live.log、baseline-callback-preexisting.log、executable-bit-probe.md）

修改：
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java`
- `skill-registry/src/main/resources/application.yml`
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/FakeProcessWorker.java`
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessAdapterTest.java` / `ProcessProtocolTest.java` / `SkillProcessRoutingTest.java`（仅 AfterEach 适配）
- `文档/优化方案/算法进程接入-平台侧方案与契约-20261027.md`（不在 git 仓内；§4.3、§5.5 标注同步）

## 7. 容器化评估（一页，只评估不实现）

见 `CONTAINERIZATION.md`。结论：**本轮不实现**。v1 隔离组合（独立工作目录/环境白名单/可执行白名单/并发配额/超时杀树）只面向可信算法与可信租户；Windows 宿主容器化代价高（基础镜像数 GB、Hyper-V 隔离要求嵌套虚拟化、bind mount 权限面）、算法交付形态要变（镜像+摘要管理，与 P5 现口径冲突）、且与 ALGORITHM 的 K8s Job 通道职责重叠。触发条件（首个"互不可信但必须用 PROCESS"的真实需求 + 算法侧可交付镜像）满足时应复用 ALGORITHM 链路，而不是在 skill-registry 内嵌容器运行时。

## 8. 回滚方式

- 回滚单位是整卡（模块内无跨卡纠缠）：`git checkout 75d9d8c -- skill-registry/` 恢复全部代码与配置（新增文件按 `git clean` 常规处理）；本卡无迁移、无 DDL、无新表，PG 侧无需回滚——`execution_fact.result.process` 键对旧代码是透明的多余键。
- 配置回滚：五个新键全部有安全默认值（4/1/30000/5/空），删除 `application.yml` 覆盖即回到 T21c 行为；`keep-failed-dirs=0` 单键即可关闭失败转储保留。
- 白名单回滚：`AGENT_PROCESS_ALLOWED_EXECUTABLES` 置空 = 不限制（注册/执行期闸全开）；但 `enabled=true` 时需同时关闭开关，否则装配期失败（该检查本身就是"更安全方向"的回滚阻力，符合契约 §7.1）。
- 契约文档回滚：还原 §4.3/§5.5 两处标注即可（独立文档，不在 git 仓内）。
