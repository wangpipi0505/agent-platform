# G1 输出策略独立评审

评审结论：**暂不认可 G1 局部关闭，需修复 2 项 P2 后定向复核**。未发现已证实的 P1。本结论只针对本次 G1 源快照，不扩展为全工程验收。

## 评审对象与边界

- 唯一工程根：`G:/207/项目/算法重组工具/代码/agent-platform`。
- 工程无 HEAD；评审对象是 `.local/reviews/g1-output.json` 的 current-source-snapshot-no-HEAD，capturedAt=`2026-10-19T20:44:09.177Z`，不是 Git diff。评审时逐文件 Get-FileHash 验证，10/10 文件 SHA256 与包内清单一致。
- 已读根 AGENTS.md、docs/implementation-brief.md、contracts/README.md、冻结详设第二部分 §5、原 31 的请求冻结/恢复/预算规则及 docs/evidence/engine-output-policy.md。
- 已核对 OutputPolicy、ModelExchange、AgentDriver、FlowDriver、ControlledReplay、V213、对应测试断言，并仅沿本工程的 ContextAssembler / TraceStore / RuntimeFault 接口追查上下文。
- 使用 code-review、requesting-code-review、verification-before-completion 技能执行独立评审；无 HEAD 时以完整内容与 SHA 绑定对象。未读取相邻工程、旧数据或配置，未修改业务源码，未启动停止服务，未提交 Git，未重跑 Maven 或既有测试套件。本次唯一写入为本报告。

## 必须修复

### [P2] G1-OP-01：引用过滤器把合法业务属性名和字面量当作 schema 引用

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/domain/OutputPolicy.java:57-65`，尤其递归遍历所有对象的第 64 行。

最小合法 schema：

```json
{"type":"object","properties":{"$ref":{"type":"string"}},"required":["$ref"]}
```

期望实例 `{"$ref":"value"}` 合法。当前遍历到 `properties` 对象时将业务属性名 `$ref` 当作 schema 关键字，又因其值是 schema 对象而非以 # 开头的字符串，直接抛 `OUTPUT_POLICY_INVALID`。直接使用当前同一 networknt 1.5.6 验证上述 schema/实例返回 0 错误，而 OutputPolicy 构造失败。因而合法输出合同无法运行；`const`、`enum`、`examples` 中的同名字面量也有同类误判。

应只在真正的 schema 节点识别引用，按 schema 关键字语义区分 schema-map 与实例/注解值，同时继续拒绝真实的远程引用。需补合法 $ref 业务属性和字面量的回归断言。

### [P2] G1-OP-02：本地引用延迟解析异常绕过 dispatch 前配置拒绝、诊断及用量记账

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/domain/OutputPolicy.java:47-53,98-99`；调用后果位于 `ModelExchange.java:29-43`。

最小 output 配置：

```json
{"format":{"type":"json","schema":{"$ref":"#/missing"}},"onInvalid":"PASS_WITH_FLAG"}
```

构造 OutputPolicy 成功；对 `{"text":"{}"}` 调用 validate，实际抛出 `com.networknt.schema.InvalidSchemaRefException: : Reference /missing cannot be resolved`。getSchema 尚未完整初始化本地引用，构造处的 catch 无法拦截。

ModelExchange 在第 33 行完成模型请求与响应持久化后，第 40 行调用此 validate；异常使第 42 行 recordValidation 和第 43 行 accountMainUsage 都不执行。因此非法冻结配置仍会先产生一次付费模型请求（modelCalls 已扣），响应虽保留，output_validation 为空且 usage_accounted 仍为 false；PASS_WITH_FLAG/RETRY/TERMINATE 均不能进入正常验收分支，恢复时会再次在同一响应上抛相同异常。该后果由实际领域探针与明确代码顺序共同证明，本次未额外发送 HTTP 或运行 PG 场景。

应在首次真实模型调用前完整初始化并验证 schema 引用，以 `OUTPUT_POLICY_INVALID` 拒绝坏配置；同时防止执行期校验器异常跳过已发生模型调用的可靠记账和错误证据。需新增未解析本地引用的 dispatch 前拒绝断言。

## 实际复现命令与输出

以下在唯一工程根运行，Java 21 路径由主任务确认。stdin 领域探针不创建源码文件、不调用 PG、不请求模型 HTTP，也不是重跑既有套件。classpath 使用本工程已编译类及本工程 .tools/m2 依赖。

```powershell
$probeJars = @((Get-ChildItem -LiteralPath '.tools/m2/com/networknt','.tools/m2/com/fasterxml/jackson/core','.tools/m2/com/fasterxml/jackson/dataformat/jackson-dataformat-yaml','.tools/m2/com/fasterxml/jackson/datatype/jackson-datatype-jsr310','.tools/m2/org/slf4j/slf4j-api/2.0.17' -Filter '*.jar' -Recurse | ForEach-Object FullName))
$probeClasspath = (@((Join-Path (Get-Location) 'engine-runtime/target/classes'),(Join-Path (Get-Location) 'admin-common/target/classes')) + $probeJars) -join ';'
@'
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.common.*;
import com.networknt.schema.*;
String special = "{\"type\":\"object\",\"properties\":{\"$ref\":{\"type\":\"string\"}},\"required\":[\"$ref\"]}";
try { var direct = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(Json.read(special)); System.out.println("DIRECT_SPECIAL=" + direct.validate(Json.read("{\"$ref\":\"value\"}")).size()); } catch(Throwable t) {System.out.println(t);}
try { new OutputPolicy(Json.read("{\"format\":{\"type\":\"json\",\"schema\":" + special + "}}")); System.out.println("WRAPPED_SPECIAL_OK"); } catch(Throwable t) {System.out.println("WRAPPED_SPECIAL=" + t);}
try { var p = new OutputPolicy(Json.read("{\"format\":{\"type\":\"json\",\"schema\":{\"$ref\":\"#/missing\"}}}")); System.out.println("MISSING_REF_CONSTRUCTED"); System.out.println(p.validate(Json.read("{\"text\":\"{}\"}"))); } catch(Throwable t) {System.out.println("MISSING_REF=" + t);}
/exit
'@ | & 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/jshell.exe' --class-path $probeClasspath --feedback concise -
```

实际退出码 0，关键输出：

```text
DIRECT_SPECIAL=0
WRAPPED_SPECIAL=cn.agentplatform.engine.domain.RuntimeFault: Schema[中文终端编码乱码]
MISSING_REF_CONSTRUCTED
MISSING_REF=com.networknt.schema.InvalidSchemaRefException: : Reference /missing cannot be resolved
```

第二行异常消息由当前源码第 63 行确认是“Schema仅允许本地引用”；不将终端乱码推断为新的业务错误。

## 已核实的局部实现与现有证据

| 要求 | 源码/断言核对结果 |
| --- | --- |
| PG 为权威、先持久化最终材料再发送 | prepare 保存 source_request；freeze 在事务内计 modelCalls、保存 final_request 及 attempt 快照，提交后才 chatStream；每输出 attempt 独立行、独立 invocationRef。 |
| 输出格式及三种 onInvalid | 严格 JSON 拒绝空白、重复字段、尾随文档；常规有效 schema 的 TERMINATE / PASS_WITH_FLAG / RETRY 路径均有实际 PG 断言。原文与诊断分开持久化，返回值修改使用 deepCopy。上述两个 schema 边界是例外。 |
| 有界重试和预算 | 总 MAIN 次数上限为 1+maxRetries；每次调用经过 ContextProjection/冻结/预算，修正消息含前次原答及诊断。modelCalls/token/duration 的既有 PG 断言已读；token 超限前验证诊断先落库。 |
| 工具中间轮、SUMMARY 隔离 | 非空 toolCalls 绕过终答验收；SUMMARY 删除 params.output，独立测量/冻结/记账；对应断言已读。 |
| FLOW 覆盖与映射 | effectiveConfig 先合并 nodeOverrides，再合并 inline modelOverride，未覆盖嵌套项继承，schema 整体替换；显式 outputContract 接入公共策略。失败 outputValidation 在业务输出映射之后重新附加，保留原始响应。nodeOverrides/PASS flag、legacy contract/retry、映射保留标记有 PG 断言。 |
| 恢复和并发发送栅栏 | 同一 node/round/kind/outputAttempt 唯一键；事务内 run/node/调用行锁；sdk_attempts>0 且无响应时拒绝隐式重发；已保存响应重用、usage_accounted 防重复扣；实际测试涵盖完成后重入及校验后下一 attempt 前中断。 |
| controlled replay | 精确 source node/round/outputAttempt 匹配，每 attempt 单独 mock/source 链接，sdk_attempts=0，重走验收；三 attempt 测试将 post/chatStream 都设为 AssertionError 并核对 3 行对应关系。 |

现有真实日志已重新读取（只读审计，不声称本评审重新执行测试）：

- `engine-runtime/target/output-policy-full-green.log`：第 214 行为 **100 tests / 0 failures / 0 errors / 0 skipped**，第 226 行完成时间 **2026-10-20 04:41:01 +08:00**；可见 Flyway/PG 16.15 与 engine schema 213。第 106–107 行领域 OutputPolicyTest=4，第 179–184 行 OutputPolicyPostgresTest=18，合计输出策略 22。
- 同一全量日志包含 RunStatisticsPostgresTest=2（86–98 行）和 ChatSessionsTest=4（110–115 行），因此没有因新增策略而漏列 H1 四项或统计两项。
- `engine-runtime/target/output-policy-statistics-green.log`：**2/0/0/0**，完成 **04:41:22 +08:00**；是同一统计两项的顺序复验，不累加为 102 个独立测试。
- 已执行的 22 项未包含本报告两个反例，因此全绿日志与本报告发现并不矛盾。部分测试仅断言异常存在，不能替代 schema 合法性全边界或所有故障窗口验收。

## 尚未验收边界与关闭条件

1. 修复 G1-OP-01/02 后需重新绑定源快照、保留失败到通过的针对性断言及实际日志，再作局部复核；当前不予关闭。
2. 本次代码阅读核对了事务锁顺序和唯一键，但未新增多副本同时执行/租约接管压力场景，未执行真实进程崩溃注入；不将串行恢复测试扩称为完整并发故障验收。
3. 显式 inline modelOverride 与 nodeOverrides 的冲突优先级、部分字段继承及 schema 替换在代码上可见，现有新增 PG 测试只直接覆盖 nodeOverrides 的 onInvalid；未宣称这些组合全部已有独立运行断言。
4. 本次不做全 JSON Schema 元模式、格式词汇或复杂度/资源上限验收；不重新覆盖整个引擎。
5. 真实供应商质量与外部环境未在本局部评审验收，也不据此记为本增量缺陷。前端如何展示重试流式片段及最终权威结果由主任务另行处理，不纳入此 G1 engine 快照关闭结论。


## 修复后定向复核（2026-10-20）

**最新结论：G1-OP-01、G1-OP-02 均已关闭，认可 G1 engine 输出策略局部关闭。** 本节取代前文针对旧快照“暂不认可关闭”的当前状态；前文保留为原始发现与复现记录。定向复核未发现新的 G1 P1/P2 阻断，不扩大为全工程验收。

### 对象绑定与方法

本次只读 `docs/evidence/engine-output-policy-fix.md`、两项发现对应的 OutputPolicy / ModelExchange 生产修改、两个原测试文件的新增断言及实际红绿日志。仍未重跑 Maven/原套件、未启动停止服务、未读相邻工程或凭据，唯一写入是本报告追加部分。

重新执行 SHA256 读取，四个当前 Java 文件 4/4 匹配修复报告清单：

| 文件 | 定向复核 SHA256 |
| --- | --- |
| engine-runtime/src/main/java/cn/agentplatform/engine/domain/OutputPolicy.java | cebcbaf813d4da4f77e453dbfb2e3d247ead8a31dca522d6f2ac4145696f53de |
| engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelExchange.java | e5f548510993ad64a12a34ce3d0d2701eb48082c88913480e8472c4a99e08b4a |
| engine-runtime/src/test/java/cn/agentplatform/engine/domain/OutputPolicyTest.java | db4e02546dd1b70fdc7b6cd9dc2c42c24af3a7142fb3a12f7270de6f30849e25 |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/OutputPolicyPostgresTest.java | b93b2fd182eab3084ce80485e224e8f510ffc205f50f249c4d00ce841e644e1d |

这四个哈希与原 G1 快照共同绑定本轮修复对象；没有将其冒称为 HEAD/Git diff。

### 两项发现的关闭依据

- **G1-OP-01 已关闭**：OutputPolicy 的 schemaLocations 只在 schema 节点检查引用；properties / patternProperties 等 map 的业务键不作为关键字，const / enum / examples / default 保持数据语义。新增断言直接用同版本 networknt 验证包含三类引用名称的业务属性，包装后同样合法，并保留 required 的失败断言。四类字面量均有验收断言。实际 schema 中 HTTPS/file/relative 引用仍被拒绝；额外 loader 拒绝兜底防止本地引用将数据节点转换为 schema 后绕过 I/O 限制。
- **G1-OP-02 已关闭**：OutputPolicy 在构造阶段对根及识别到的子 schema 显式 initializeValidators，并在异常时统一抛 OUTPUT_POLICY_INVALID；这发生在 ModelExchange.exchange 第 29 行创建策略、调用 exchangeAttempt 之前。新增真实 PG 断言对三种 onInvalid 都检查 post/chatStream 零调用、invocation 零行及预算 JSON 不变。缺引用也覆盖 unused defs、optional properties、未触发 anyOf；递归本地 schema 与命名锚点保留正反例断言。
- **付费后异常保护已核对**：ModelExchange 第 41–67 行捕获执行期 RuntimeException，先 recordValidation，再 accountMainUsage，最后抛 OUTPUT_POLICY_INVALID，不进入输出修正重试。新增 PG 测试通过 Mockito construction mock 注入 validator 异常，实际走持久化与记账；两次进入/恢复只发生一次模型调用，response、output_validation、usage_accounted 同行存在，modelCalls=1、tokens=7。该 mock 只证明异常窗口保护；正常引用解析由上述真实 networknt 测试证明。

### 重新读取的实际红绿证据

| 日志 | 读到的结果 |
| --- | --- |
| engine-runtime/target/output-policy-fix-red.log | 04:49:48，10 项 / 5 failures / 0 errors / 0 skipped。五个失败名称分别对应业务属性、字面量、缺引用初始化、零 HTTP 前置拒绝、异常记账保护。 |
| engine-runtime/target/output-policy-fix-targeted-green.log | 第 67 行领域 10 项，第 79 行 PG 20 项；第 83 行汇总 30/0/0/0，BUILD SUCCESS，04:52:44 完成。 |
| engine-runtime/target/output-policy-fix-full-green.log | 第 222 行 engine 汇总 111/0/0/0，第 231 行 BUILD SUCCESS，第 234 行 04:54:04 完成；按日志每个 engine 测试类的计数重新相加亦为 111。 |

完整日志中 OutputPolicyTest=10（第 115 行）、OutputPolicyPostgresTest=20（第 192 行）、RunStatisticsPostgresTest=5（第 106 行）、ChatSessionsTest=4（第 123 行）。111 的口径为原 100 + 本次输出策略新增 8 + 统计边界新增 3；定向 30 不重复累计。admin-common 的独立 5 项也没有混入 engine 的 111。

这里确认的是重新读取、交叉核对的已有真实运行证据，没有声称独立评审重新执行过套件。

### 仍保留的验收边界

原报告关于多副本并发/真实进程崩溃、覆盖优先级的全部组合、完整元模式/格式词汇/复杂度资源上限和真实供应商的验收边界继续有效，本轮不扩展覆盖。主任务另有前端终态快照替换流式片段及 PASS warning 的修复/浏览器证据；本次定向 engine 复核没有独立评审该前端修改，不将其表述为本报告验收内容。以上边界不构成本轮两个已证实 P2 的遗留阻断。


