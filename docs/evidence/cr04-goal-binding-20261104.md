# CR-04 目标回执归属与动作时序补改

2026-11-04。范围仅目标读回、冻结绑定、登记锚及定向逻辑检查；未启动PG、外部业务服务、全量测试或真实场景验收。

- 每目标冻结`target.objectType/objectId`、只读`readback.skillCode/arguments/objectIdPath/resultObjectIdPath`；可选`action.skillCode/arguments/objectIdPath`声明动作后的核验。新run缺绑定即拒绝，历史缺绑定返UNKNOWN。
- 只选同run、技能、冻结版本/合同摘要、参数摘要与JSON均匹配的回执；响应对象ID也必须匹配。错对象、旧版本、UNKNOWN、替身均不能成为ACHIEVED。
- `ToolCalls.register`仅增加`GoalReadbackAnchor.capture`单点。register/fact已共用run锁；登记时把当时已采纳的成功动作fact ID锚入既有`attempt_note`，重复登记不补锚。核验要求当前最新匹配动作与该锚一致，动作前的读回不能借后来的成功事实倒填。
- 核验只读已有tool_call/attempt_note，处置仍走既有goal_outcome；不发出技能调用、不新增业务写入。未声明action的目标仅核对对象当前读回，不证明某业务动作已执行。
- 原PG用例只补绑定/合同/响应对象测试夹具，未放宽原断言；本轮没有运行其PG部分。

验证（第1轮功能检查）：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
mvn -o -pl engine-runtime -am '-Dtest=GoalVerificationBindingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：exit 0，`GoalVerificationBindingTest` 6 tests / 0 failures / 0 errors，BUILD SUCCESS。覆盖同技能错对象、错响应对象、旧版本、UNKNOWN、替身、读回前动作锚、后续动作使旧读回失效、动作完成后不补填旧锚、重复核验无工具写、缺绑定历史UNKNOWN与新run拒绝。测试替身仅检验逻辑和SQL约束，不代表PG落库/跨进程并发或真实执行成功。

一次先行命令因PowerShell未引用含点的`-Dsurefire.failIfNoSpecifiedTests=false`导致Maven参数解析失败，未编译/执行功能测试；引用后执行上述唯一功能检查轮次。未开展第二轮或扩大测试范围。

CR-06另项继续处理predicate方言与发布Schema；本提交未声称解决前后端判据联通。
