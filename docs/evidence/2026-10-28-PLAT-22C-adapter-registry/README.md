# PLAT-22 C 段（T22C）：适配器注册表——证据目录

- 日期：2026-10-28；分支 `feat/v1.2-implementation`，基线 HEAD `a439557`（T22A），工作树未提交
- 卡片：优化台账 T22C——AdapterRegistry 分发替代 SkillAdapters 硬编码 switch、kind 白名单单一来源、测试 kind 零改代码接入验证、五种既有 kind 零回归
- 依据：《平台侧优化方案与实施细则-20261027》§11.4（新软件接入八步：现有 kind 都不匹配才新增 adapter）；契约增补 `contracts/skill-invocation.md` §15（freeze.json 已登记）

## 1. 半成品承接说明与核查结论

本卡前一执行者因配额超限中断，遗留半成品状态：**编译绿、AdapterRegistryZeroChangeTest 8/8 绿**（`green1-registry-onboarding.log`）；派发单认为红阶段证据缺失，实际前任已留**注册期拒绝红例**（`red1-registration-rejected.log`：回退态下 1 run / 1 Error `适配器类型无效`）与一次模块全量运行（`full-module-run1.log`：664 例 2F+48E）；缺契约增补、freeze.json 登记、证据 README、干净基线对照与 SkillRules 收敛核查。本执行者承接后逐项收尾，并对前任已留的绿/全量各独立复跑一次，未重做已绿部分。

**对半成品的核查结论（通读全部新增/修改文件 + 与 HEAD 版语义逐臂对照）**：

- 五个内置单元与原 switch 各臂一一对应，执行语义零搬运：
  - HTTP 同步 → `host.http(...)`（原 `case "HTTP"` 三目 else 臂）；HTTP 异步 → `host.httpAsync(...)`（原三目 async 臂，`asyncHttp.invoke` 参数序与原一致）；两形态用 `asyncForm`（原 `asynchronous()` 的定义提为 static，public 方法委托它，`SkillExecution` 的旁路询问读同一份）互斥分流；
  - MCP → `new McpAdapter().invoke(...)` 与原 `mcp()` 逐字相同；queryOnly → `RESULT_UNKNOWN`（对账保护）随单元迁移，码/unknown 位/正文与原一致；
  - PROCESS → `executeLocalProcess`（原 `localProcess` 改包私有，方法体零改动：ProcessContract 解析、T21d 并额/白名单/失败转储、信封写 stdin 全在原处；杀树在 ProcessAdapter 未动）；queryOnly → `RESULT_UNKNOWN` 同原臂；PROCESS 注册期 SYNC-only 定制诊断文案逐字保留；
  - ALGORITHM → invoke/observe/bind 分别路由原 `algorithm.invoke/observe/bind`，参数序一致；observe 缺省臂（invoke + 空观测源）与原 else 臂等价；
- `bind`/`persistentBinding` 注册表化后与原 if-chain 等价（ALGORITHM→算法绑定、HTTP 异步→幂等键登记、其余无操作；未注册 kind 保持静默无操作），锚点见测试 `persistentBindingAndBindKeepLegacySemanticsThroughRegistry`；
- `SkillCatalog.normalize`：类型白名单（`Set.of("HTTP","MCP","ALGORITHM","PROCESS")` → `registry.isSupportedType`）、组合白名单（大布尔式 → `isSupportedCombination` + 单元定制文案）、kind 缺省（`type==ALGORITHM?SUBMIT_QUERY:SYNC` → `registry.defaultKind`）三处收敛，错误码/触发条件/文案不变；`mcpTools` 门改 `supportsToolDiscovery`；T12 identity 注入调用点（`SkillRules.identity`，仅 HTTP 有出站头发点）**未动**，仍由 SkillAdapters HTTP 通道原处执行；
- 未注册 kind 执行期：原 default 臂 `PARAM_ERROR/unknown=false/"未支持的适配器类型"` → 注册表同码同位，正文升级为列出已注册类型与接入方式（可读性增强，码不变，测试锚定）；
- **核查未发现半成品缺陷**，无需修复性改动；本执行者未改动任何前任产出的 Java 文件。

## 2. 改动文件完整清单

前任产出（本执行者核查确认，未改动）：

| 文件 | 性质 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/AdapterRegistry.java` | 新增（注册表：dispatch/find/isSupportedType/isSupportedCombination/defaultKind/supportsToolDiscovery/combinationMessage） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapter.java` | 新增（注册单元接口） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpSyncExecutionAdapter.java` | 新增（HTTP/SYNC） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncExecutionAdapter.java` | 新增（HTTP/SUBMIT_QUERY+CALLBACK） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/McpExecutionAdapter.java` | 新增（MCP/SYNC + 工具目录门 + 对账保护） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/AlgorithmExecutionAdapter.java` | 新增（ALGORITHM/SUBMIT_QUERY + 独立观测） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessExecutionAdapter.java` | 新增（PROCESS/SYNC + 对账保护 + SYNC-only 定制诊断） |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java` | 修改（+57 行：三处白名单收敛 + mcpTools 门 + 8 参构造兼容 7 参） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | 修改（switch/分支 → 注册表分发；通道改包私有；6 参构造兼容） |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/AdapterRegistryZeroChangeTest.java` | 新增（8 例） |

本执行者补齐：

| 文件 | 内容 |
| --- | --- |
| `contracts/skill-invocation.md` | 追加 §15（注册表机制 / 两层 kind 关系 / 新执行方式接入路径） |
| `contracts/freeze.json` | skill-invocation sha256 重算 + engineeringAddendum 串追加 §15 条目 + engineeringAddenda[] 新增 T22C 登记 |
| `docs/evidence/2026-10-28-PLAT-22C-adapter-registry/` | README.md、red-wiring-reverted.log、baseline-full-module.log、regression-full-module.log、failure-set-diff.txt、baseline-failures.txt、with-card-failures.txt（前任已留：red1-registration-rejected.log、green1-registry-onboarding.log、full-module-run1.log） |

零改代码接入的证明锚点：产品主代码 `grep -rn TEST_ECHO skill-registry/src/main/` 零命中（本 README 同目录会话留痕）；分发 switch `grep "switch (contract"` 主代码零命中。

## 3. 红 → 绿

- **红·形态一（注册期拒绝，前任留证 `red1-registration-rejected.log`）**：回退态（无接线）下运行本测试的接入用例 → 1 run / 1 Error：`newExecutionKindOnboardsWithoutTouchingPlatformDispatch:64 ? Platform 适配器类型无效`——未注册 kind 被注册期目录以既有 400 文案拒绝，TEST_ECHO 无法登记。
- **红·形态二（接线回退编译失败，本执行者补录 `red-wiring-reverted.log`）**：`git stash push` 仅回退 `SkillCatalog.java`/`SkillAdapters.java` 两处接线（保留注册表与测试文件）后，`mvn -pl skill-registry test -Dtest=AdapterRegistryZeroChangeTest` → **BUILD FAILURE（main 编译期）**：注册单元找不到既有通道符号（`找不到符号: 方法 algorithmInvoke(...)/http(...)`，涉及 AlgorithmExecutionAdapter:42/56/63、HttpAsyncExecutionAdapter:29/42/48、HttpSyncExecutionAdapter:29/42、ProcessExecutionAdapter:41）——不接线则注册单元无法接入执行通道，分发不可用。两形态分别覆盖派发单预测的"注册期拒绝"与"编译失败"。
- **绿（`green1-registry-onboarding.log` 为前任留证；`regression-full-module.log` 内 8/8 + 复绿验证为本执行者独立复跑）**：恢复接线后 `AdapterRegistryZeroChangeTest` **Tests run: 8, Failures: 0, Errors: 0**——TEST_ECHO 测试单元"实现 SkillAdapter + 注册"即完成注册期放行与执行分发，产品主代码零字样；本执行者 stash 往返后复跑同命令再次 8/8（EXIT=0，回绿验证）。

## 4. 模块全量对照（硬门禁：新增失败 = 0）

命令（仓库根）：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot" && source .tools/env-load.sh && mvn -B -pl skill-registry test "-DargLine=-Xmx1024m" -Dsurefire.runOrder=reversealphabetical`

| 轮次 | 树状态 | Tests run | Failures | Errors | Skipped | 失败合计 |
| --- | --- | --- | --- | --- | --- | --- |
| 干净基线（`baseline-full-module.log`，本执行者） | HEAD `a439557`（本卡改动全部 stash，含 untracked） | 656 | 3 | 48 | 4 | 51 |
| 本卡后·前任（`full-module-run1.log`） | 工作树（本卡全量） | 664 | 2 | 48 | 4 | 50 |
| 本卡后·本执行者（`regression-full-module.log`） | 工作树（本卡全量） | 664（+8 为本卡新测试） | 2 | 48 | 4 | 50 |

两轮"本卡后"运行的**失败方法集合逐名一致**（50 例，独立复跑可复现），既有失败定性因此跨轮稳固。

**失败方法集合逐一 diff（`failure-set-diff.txt`，两轮同命令同 runOrder）**：

- **新增失败：0**（基线 51 个失败方法与本卡后 50 个逐名对照，无一名新增）；
- 消失 1：`McpAdapterTest.blockedAckBodyCannotOutliveTheWholeSessionDeadline`——该例为 `System.nanoTime`/800ms session-deadline 的时序敏感用例（`McpAdapterTest.java:473`），基线轮红、本卡轮绿，属运行间时序波动，与本卡改动无因果（本卡未触碰 McpAdapter 与该测试）。

既有失败逐类（与本卡后一致，均为环境/历史已知，非本卡引入）：

| 类 | 本卡后 | 基线 | 定性 |
| --- | --- | --- | --- |
| `CallbackHttpTest` | 32 Errors | 32 | `SECURITY_MODE_REQUIRED` 环境缺口（`agent.security.mode` 未配置）；T21d 曾以 `git archive 75d9d8c` 干净基线逐字复现同样 32 错（`docs/evidence/2026-10-28-PLAT-21d-hardening/baseline-callback-preexisting.log`） |
| `McpManagementTest` | 7 Errors | 7 | 同为 `start:124 IllegalState SECURITY_MODE_REQUIRED` 环境缺口（同一部署输入缺失） |
| `ReconciliationProofTest` | 6 Errors + 1 Failure | 7 Errors + 1 Failure | 6 例 `SECURITY_MODE_REQUIRED`（proofHttpContext 同一环境缺口，T06 基线即载）；1 Failure（`ordinaryHttp404And5xxRemainUnknown` expected UNKNOWN but was null）为基线同名失败（T06 证据记载该类 7 失败口径内的既有失败） |
| `SkillCallbackWakeTest` | 3 Errors | 3 | Spring 上下文静态耦合（`SkillClients` UnsatisfiedDependency），T06 证据 `docs/evidence/2026-10-28-PLAT-06-path-params/`（43 既有失败 = CallbackHttpTest 32 + ReconciliationProofTest 7 + SkillCallbackWakeTest 3 + 1）所载同类 |
| `AlgorithmMigrationPostgresTest` | 1 Failure | 1 Failure | `v310UpgradePreserves...` 断言期望行缺 `external_state/last_polled_at/remote_error_code/remote_error_message/submitted_at` 列——共享 PG 测试库 schema 已前进到含异步状态列的版本（v3.1.x 后续迁移），期望快照未随之更新的既有漂移；与本卡无关（本卡零 schema 改动） |

口径说明：台账"T21d 基线 270/270"是 T21d 的 23 类 `-Dtest` 子集口径；本卡按派发要求跑**模块全量**（664 例），故另做同命令干净基线对照作为新增失败的裁决依据（T06/T21d 同法）。

## 5. SkillRules 收敛核查（grep 证据）

`grep -rn --include="*.java" -E '"(HTTP_SYNC|HTTP_ASYNC|MCP|ALGORITHM|PROCESS|HTTP)"' skill-registry/src/main/java`（剔除注册表新文件）后逐处分类：

**已收敛到注册表（本卡目标，全部完成）**：

- `SkillCatalog.normalize` 类型白名单 `Set.of("HTTP","MCP","ALGORITHM","PROCESS")` → `adapterRegistry.isSupportedType`（注册表唯一来源）；
- `SkillCatalog.normalize` 组合白名单大布尔式（HTTP×{SYNC,SUBMIT_QUERY,CALLBACK} / MCP×SYNC / ALGORITHM×SUBMIT_QUERY / PROCESS×SYNC）与 PROCESS 专项分支 → `isSupportedCombination` + `combinationMessage`（PROCESS 定制文案由单元承载，逐字保留）；
- `SkillCatalog.normalize` kind 缺省 `ALGORITHM?SUBMIT_QUERY:SYNC` → `defaultKind`（单元声明）；
- `SkillCatalog.mcpTools` 的 `"MCP".equals(type)` 门 → `supportsToolDiscovery`（单元声明）；
- `SkillAdapters.invoke` 的五臂 switch → `registry.dispatch`（主代码 `switch (contract` 零命中）；
- `SkillAdapters.observe` 的 ALGORITHM 特判、`bind`/`persistentBinding` 的 ALGORITHM/asynchronous 分支 → 注册表单元（等价性有测试锚点）。

**仍含 kind 字面量、经甄别不属本卡收敛范围（行为分支/契约形状规则，非 kind→实现白名单）**：

- `SkillRules.identity:288`（仅 HTTP 有出站 X-User-* 发头点——T12 的能力边界声明，非分发白名单；收敛它需给注册单元新增能力位，超出本卡"kind 白名单单一来源"口径）；
- `SkillCatalog:412/438/506/525/721/772/1081`：PROCESS 子契约解析、endpoint 形状校验、HTTP CALLBACK/SUBMIT_QUERY 声明校验、MCP toolName、两段审批仅 HTTP SYNC（PLAT-15 门）、PROCESS 摘要——均为 per-kind 契约形状/能力规则（测试注释与方案 §11.4 口径：形状规则收敛属 A-02）；
- `SkillExecution`（ALGORITHM fact/证据、MCP 对账端点）、`ReconciliationProofs`、`SkillCallbacks`、`AlgorithmAdapter`、`HttpAsyncAdapter`、`OpenApiHttpMapping`、`OntologyManifestImports`、`OpenApiImports`：执行编排/协议/导入器的行为分支与协议常量，与注册表分发无关。

## 6. 未覆盖风险

- `CallbackHttpTest`/`McpManagementTest`/`ReconciliationProofTest` 的 `SECURITY_MODE_REQUIRED` 依赖环境侧补 `AGENT_SECURITY_MODE` 等部署输入，补齐后应全绿（T21d 已留基线复现证据）；
- `AlgorithmMigrationPostgresTest.v310...` 的期望快照落后于共享测试库 schema（异步状态列已存在），归算法/迁移侧后续卡修正期望或隔离库；本卡未触碰 schema；
- `McpAdapterTest.blockedAck...` 时序敏感用例存在运行间波动（本次基线红/本卡绿），建议后续给该例去 flake（非本卡）；
- 运行层注册表允许注册能力包 schema（声明层）未声明的 kind——§15.2 已定死"运行层先行注册不构成声明层上线"，但生产入口是否放开注册 API 由 T22D 控制面收口决策，本卡仅提供机制；
- Spring 路径的容器装配（注册表 + 五单元 @Component 自动发现）由注解存在性测试锚定，未起全容器冒烟（既有 3 个 Spring 上下文类在本环境本就因静态耦合/环境缺口不可用）。

## 7. 回滚方式

本卡全部改动未提交（工作树）。回滚 = 放弃工作树改动：

```
git checkout -- skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java \
                skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java \
                contracts/skill-invocation.md contracts/freeze.json
rm skill-registry/src/main/java/cn/agentplatform/skill/infra/AdapterRegistry.java \
   skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapter.java \
   skill-registry/src/main/java/cn/agentplatform/skill/infra/{HttpSync,HttpAsync}ExecutionAdapter.java \
   skill-registry/src/main/java/cn/agentplatform/skill/infra/McpExecutionAdapter.java \
   skill-registry/src/main/java/cn/agentplatform/skill/infra/AlgorithmExecutionAdapter.java \
   skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessExecutionAdapter.java \
   skill-registry/src/test/java/cn/agentplatform/skill/infra/AdapterRegistryZeroChangeTest.java
```

即回到 HEAD `a439557`（T22A）状态；`contracts/skill-invocation.md` 的 §15 与 freeze.json 登记随 checkout 一并撤销，哈希恢复一致（verify-contracts 可复核）。

## 8. 偏差

- 派发单"红阶段"原判缺失；承接盘点发现前任已留注册期拒绝红例（`red1-registration-rejected.log`），本执行者另补**接线回退编译失败**形态（`red-wiring-reverted.log`）构成第二对照，两形态分别命中派发单预测的"编译失败或注册期拒绝"；
- 派发单要求"`git stash` 半成品后跑 AdapterRegistryZeroChangeTest"：实际分两步——先 stash 两处 tracked 接线跑红（如上），再整体 stash（含 untracked）跑**干净基线全量**作新增失败裁决，两轮 stash 均已 pop 恢复并复绿 8/8；
- 台账"T21d 基线 270/270"为 23 类子集口径，本卡按"模块全量"字面执行（664 例）并另建同命令基线对照，已在上文口径说明；
- 无其他偏差；未触碰白名单外文件（engine-runtime/admin-common/app-management/monitor/web/capability-* 均只读未动）。
