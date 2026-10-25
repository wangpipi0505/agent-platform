# 共享 Schema 与 MCP 生命周期防护独立复核

结论：**APPROVE（仅本防护补丁与共享迁移增量）**。未发现需要返修的阻断项。结论基于冻结源码审查、候选与保护文件 SHA 核对，以及下面有限的独立亲跑；不代签生产发布、完整 G5/G2/T11 或全部平台功能。前端及 G5 已有独立批准未重开。

## 范围与候选

唯一工程根目录 `G:/207/项目/算法重组工具/代码/agent-platform`。按 `shared-schema-resource-review-brief.md`、`AGENTS.md` 和共享任务 brief 执行：未读取或执行旧资源耗尽探针，未构造复杂攻击 Schema、慢正则或压力输入，未启停/package 服务、未访问相邻旧工程、未派子 agent，未改生产源码、作者测试或已有报告。

根任务在作者冻结后明确移交 common/engine/skill target，本复核才运行 Maven。最终候选为：

| 文件 | SHA256 |
| --- | --- |
| `shared-schema-budget-source-sha256.json` | `7429a8107bf2f9c44fef3ce23db6cdc014c1bdbc845d04b5fb969708c0e724de` |
| `shared-schema-budget.patch` | `5ba5f1f4e9263a9ac5c9d1a1e818eddfabcd9584511a1591136fdd6b89131a46` |
| `shared-schema-budget-verification.json` | `3064ad7af245c9d2739809858756074fc05b99e51152ac10e613a244352c8b6e` |

亲自核对：21 个增删改路径的当前/删除状态与清单全部匹配，全部改前文件与 `.local/shared-schema-before/` 匹配；历史 MCP 12 文件快照与 `mcp-resource-budget-source-sha256.json` 全部匹配。迁移后的旧包删除属于明确候选，未误报为漂移。补丁 `git apply --numstat` 的21路径一致；隔离实际应用及其逐文件字节核对仅阅作者证据，根任务核对清单/摘要，本复核人与根任务均未重复应用该21路径补丁。复现应用需 `core.autocrlf=false`。

亲自核对作者三模块执行前/后快照、各自日志 SHA 和当前源码/POM：common 19、engine 103、skill 61 个文件全部一致；有限亲跑后再次核对仍一致。清单的5个保护文件（ChatSessions、RunScheduler、RunService、RunScheduleRepository、FlowDriver）全部保持摘要；另核对 ApprovalService 的原冻结摘要亦一致。HTTP 异步、回调、证明 CAS 等其余源码在这些模块快照范围内未改变。

## 源码结论（静态审阅）

以下路径均相对工程根目录，行号为本候选实际行号。

| 定位 | 核对结果 |
| --- | --- |
| `admin-common/.../schema/SchemaWorkBudget.java:49`、`:58`、`:103`、`:199` | 原文结构预算先执行；仅真实 Schema 位置进入引用图，包含 unused defs，重复引用边保留。literal 中引用不作为 Schema；循环、嵌套 id、外部引用/不支持方言明确拒绝。图成本记忆化并饱和累加，预算在 native 构建前拒绝。 |
| `admin-common/.../schema/BoundedRegexFactory.java:20`、`:39`、`:87` | 长度/数量与 counted repeat 估算先于 RE2 编译，实际 programSize 二次限制，只有预审表达式可匹配。短 lookaround/backreference 拒绝，无 JDK fallback，保留 find 搜索语义。固定 networknt 1.5.6 / RE2/J 1.8。 |
| `admin-common/.../schema/SchemaWorkBudget.java:254`、`SchemaDeadline.java:9` | 实例结构及分支、字符、数值、比较/uniqueItems/正则工作量在 validator.validate 前受限。测试重载只允许收紧；固定硬限额不能由参数放宽。嵌套 deadline 取较早值并恢复旧 scope。 |
| `engine-runtime/.../domain/InputDeclarations.java:40`、`:91`、`:102` | 原文结构闸先于 deepCopy/声明数组转换；对象图闸先于 deepCopy；转换后再图准入，之后才配置/构建 native。两个输入配置仍各自完整验证，共用外层有限 deadline。类型、默认值不注入及 INVALID_INPUT/INVALID_INPUT_DECLARATION 分层保留。 |
| `engine-runtime/.../domain/OutputPolicy.java:64`、`:131`、`:147` | 图/正则准入先于 native 构建，有限 preload、关闭跨调用缓存和闭合 loader 保留。按 Plan.locations 初始化 unused defs；严格 JSON、原文长度上界、实例工作量及 deadline 都有入口。失败返回有限 Verdict/1024字符诊断，原 ModelExchange 三种输出策略与计费/事实分支未改。 |
| `skill-registry/.../infra/BoundedMcpSseTransport.java:497`、`:534`、`McpAdapter.java:160` | close 的50ms等待不代表终止；完成信号在 reader join、HttpClient 实际终止及 activeSends 为0之后发生。许可只绑定实际终止，慢清理仍占用原2许可；并发 connect/send/close 入口使用同一 lifecycle 锁，不提前开放会话。 |
| `skill-registry/.../infra/McpAdapter.java:152`、`SkillAdapters.java:121` | 写调用 isError、结果失败及 ACK 丢失保持 UNKNOWN，不自动重发；queryOnly 在建会话前拒绝。精确冻结 toolName、原始 inputSchema/当前 Schema 双校验、元数据 EDITOR/有凭据时 ADMIN 权限保留。迁移在这些 MCP 调用文件仅改 import。 |

共享 regex 实现相对历史仅包迁移；deadline 仅包迁移和去除“技能”文案；budget 增加结构入口、只降限额重载和通用文案。未出现第二套 Schema 验证器或后台校验线程池。

## 兼容性收窄

原递归成功例改为明确拒绝，属于本 brief 已确认的支持子集收窄；不能称作所有旧成功断言不变，也没有据此声称原正例造成资源耗尽。完整改前 `OutputPolicyTest.java` SHA 为 `db4e02546dd1b70fdc7b6cd9dc2c42c24af3a7142fb3a12f7270de6f30849e25`；单独保留的原完整25行方法 `shared-schema-budget-original-recursive-test.java.txt` SHA 为 `c7fceb0542b153e6ac1109cbed7073e13377560a9532830fa0ccc4a4f3895a7c`，两者用途不同。

亲跑的 OutputPolicyTest 已核验循环报 OUTPUT_POLICY_INVALID/“循环引用”，非循环 anchor 的有效/无效实例均保留原生判分；共享 defs、业务字段名及 const/default/enum/examples literal 的兼容性也有小输入验证。RE2 不支持语法、保守编译估算、嵌套 id 与图/实例预算拒绝均为明确子集，不宣称任意 JSON Schema 兼容。

## 独立亲跑

串行使用 JDK21 `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`。三个成功命令共同参数为：

```text
mvn -o -B -ntp -pl <module> test -Dtest=<selector> -Dmaven.repo.local=V:/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

| 模块/selector | 结果 | 完成时间（北京时间） | 日志 |
| --- | --- | --- | --- |
| admin-common / `SharedSchemaAdmissionTest` | 5/5，0失败/错误/跳过 | 08:20:48 | `.local/review-shared-schema-resource-common-jdk21.log` |
| engine-runtime / `SharedSchemaBudgetTest,InputDeclarationsTest,OutputPolicyTest` | 22/22，0失败/错误/跳过 | 08:20:58 | `.local/review-shared-schema-resource-engine.log` |
| skill-registry / 下列13项 | 13/13，0失败/错误/跳过 | 08:21:20 | `.local/review-shared-schema-resource-skill.log` |

skill 精确 selector：

```text
McpResourceLifecycleTest,McpAdapterTest#initializesListsThenCallsWithRealArguments+queryOnlyCannotIssueASecondMcpBusinessCall+rawTopLevelSchemaKeywordsSurviveSdkConversion+remoteInputSchemaIsCheckedBeforeBusinessCall+writeToolErrorNeverAuthorizesAutomaticRetry+droppedBusinessPostAckNeverReplaysPostBody+writeDisconnectIsUnknownAndNeverResent,BoundedSchemaValidationTest#unsupportedLookaroundIsRejectedInsteadOfUsingJdkFallback+unsupportedBackreferenceIsRejectedInsteadOfUsingJdkFallback+expiredScopeRejectsSynchronousValidationAndRestoresCaller+patternPropertiesUseTheSameFactoryAndSearchSemantics+ordinaryValidationErrorsKeepTheExistingBadRequestExceptionType
```

40项仅使用普通小 Schema、注入低限额、短语法/已过期 deadline、正常本地 SSE/HTTP 和受控 reader gate。低图预算验证 native config 构造0次；不把它夸大为直接拦截 getSchema。reader gate 验证 close 有界返回、reader 活动期间 completion 未完成/不退许可，解除 gate 后仅退一次；未用远端心跳传播延迟推断泄漏。原堆耗尽、复杂展开、慢正则、大产物及并发压力用例均未独立重跑。

初次 common 命令被 Maven enforcer 在测试前拒绝：当前 shell 默认 JDK17，尚未执行测试；修正到固定 JDK21 后才得5/5。该准备失败保留在 `.local/review-shared-schema-resource-common.log`，不计为行为红或失败测试。

亲跑日志 SHA256：

| 日志后缀（均 `.local/review-shared-schema-resource-`） | SHA256 |
| --- | --- |
| `common.log`（JDK准备拒绝） | `cfc4d1545cca8d8c031c929d0522a97a43ca54e68193118cb38ddd2d52c57fcb` |
| `common-jdk21.log` | `2e8fe44f21d0cb5b2f259102a7f1fdc94bfa7b65a1da8e95b0beee9c88cb37b7` |
| `engine.log` | `f18a8877cabb607ece89b839fb52ca32dab89a0a6886635debb57ab7f3ec1f4a` |
| `skill.log` | `973f20136c6d8a315d7616397b1c403aa393876999bb7611c92f0d939a0ff9fd` |

## 仅阅作者证据及未验证项

作者最终完整模块日志及其 SHA 已亲自核对：common10、engine427、skill270均零失败/错误/跳过。它们分别完成于08:12:57、08:15:59、08:18:56，拥有各自一致的全 src/POM 前后快照；没有将它们写成同一个全七模块整轮。183项 Schema 位置矩阵、输入PG原35+2项、输出零收费调用和三策略的持久化/usage断言、原18MiB异步产物、HTTP异步/回调/证明CAS均继承这些作者日志，本复核没有亲跑PG或大产物。原18MiB方法 `AsyncHttpExecutionTest.largeAsyncResultUsesControlledArtifactInsteadOfProtocolBuffer` 源码仍保留，完整类21项记录通过。

格式/四契约快照/七模块边界检查为已阅作者日志，未重跑。本轮未涉及UI，不需要截图；生命周期资源证据在亲跑 skill 日志与受控 gate 的完整断言中。

未验证：任意输入硬实时抢占、资源压力/容量、生产供应商、固定制品部署、两JVM/真实远端MCP及前端异步纵切。同步 JVM 操作仅受准入与协作 deadline 约束，不承诺线程硬抢占。其他模块/其他 JSON 序列化入口不在本复核扩大范围内；不据此签发全平台安全证明。

## 移交

2026-10-20 08:21:20之后本复核无运行中的 Maven/校验进程；common/engine/skill target 全部释放。候选源码及作者证据保持冻结，交根任务继续统一七模块回归与后续固定制品验收。本增量无待返修项。
