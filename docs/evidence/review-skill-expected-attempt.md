# 原 attempt 前置增量独立审查

审查时间：2026-10-20；范围仅为本新工程的 `skill-expected-attempt-brief.md` 增量。按 code-review 与 ponytail 技能核查真实调用链、最小实现和并发不变量。没有修改作者源码、测试或原始证据，没有提交、打包、部署、启停主服务、访问相邻旧工程或派发子 agent。

## 结论

**需求符合性：通过本增量审查。代码质量：未发现需要修复后才能继续的缺陷。** 独立执行 115 项相关测试，全部零失败、零错误、零跳过。结论限定于原 attempt 参数传播和 HTTP 异步持久发送 CAS；不能据此关闭完整 G2 或声称所有适配器具备跨代原子发送保证。

## 源码与并发核查

- `SkillExecution.java:52` 通过既有正整数解析取得请求原 attempt；`:153` 新增 claim attempt 一致性检查。首次执行 `:269` 向适配器传原 attempt，对账 `:676`、`:703` 传 recorded attempt。未发现以当前 binding attempt 补齐身份的业务入口。全新工程七个 Java 模块搜索显示真实调用方均在 skill-registry。
- `SkillAdapters.java:98`、`HttpAsyncAdapter.java:93`、`McpAdapter.java:29`、`AlgorithmAdapter.java:27` 均仅有一个带显式 expectedAttempt 的业务 invoke；前置拒绝非正数。HTTP 同步私有路径同样收到并检查原 attempt。MCP discover 保持独立入口，内部发现用的 callId=0 没有变成业务 invoke。
- `HttpAsyncAdapter.java:40` bind 对冲突行核实主体/tenant、逻辑调用、run、attempt、版本、key、完整 request_snapshot（含原参数和 guarded 参数）及合同 metadata。外主体不能取得该行；错代或快照变化不能复用。
- `HttpAsyncAdapter.java:102` 在尝试 CAS 前比较显式原 attempt、行身份、冻结快照及合同；非 queryOnly 还要求业务参数等于冻结 guarded 参数。`:132` 的单条 PG UPDATE 同时约束 REGISTERED、未发送和全部原身份，只有影响一行才 POST。CAS 失败之后重新读取会执行 `sameGeneration`，不会借新行身份再次尝试发送。
- 两类迟到窗口均独立执行：旧 submitted ACK 暂停后由测试事务建立新 REGISTERED 代，再释放旧 ACK，POST=0/GET=0，新行保持 REGISTERED；首次读取后、发送 CAS 前替换 attempt 或六种身份，均 POST=0/GET=0。测试通过真实 PG 更新制造竞争，HTTP 为 loopback 有限夹具，不把 mock UPDATE 当作数据库仲裁证据。
- ACK `:171`、原键查询补绑定 `:233`、DONE `:288` 均以原 expectedAttempt、主体/run/版本/key/快照/metadata 约束写回；`:193`、`:251`、`:312` 阻止读取后身份变化被采纳。queryOnly 不进入发送 CAS，错代在 GET 前拒绝，未发送行也不能伪造查询。对账授权范围扩大仍允许覆盖原历史范围；未要求新 guarded 参数字面等于旧范围，相关既有测试通过。
- 合法只读 RETRYABLE_FAILURE 新代路径、审批、Mock、计量及事实回调由独立 SkillExecutionTest 覆盖；另独立执行 proof 34 项、CALLBACK 4 项和 MCP PG 4 项。MCP 改动仅是参数和前置校验，既有总 deadline、分页/解码预算、会话信号量、禁止业务重放逻辑保持原实现；按审查约束未重跑资源耗尽探针。

## 独立执行命令及结果

从工程根执行；进程内从本工程 `.env` 装入变量而未打印凭据，JAVA_HOME 指向 `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`。公共命令：

```powershell
mvn.cmd -o -B -ntp -pl skill-registry test `
  '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' `
  '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' `
  '-Dtest=<下表选择器>'
```

| 选择器 | 独立结果 | 日志 |
|---|---|---|
| `HttpAsyncAttemptFenceTest,AsyncHttpExecutionTest,SkillExecutionTest,SkillAttemptPropagationTest` | 73/73；0 failures/errors/skipped；BUILD SUCCESS；08:59:43 +08:00 | `review-skill-expected-attempt-directed.log` |
| `ReconciliationProofTest,CallbackHttpTest#validCallbackIsDurableAndDuplicateCannotRepeatBusiness+cancelledRunAndOldAttemptAreOnlyAudited+notSentFenceRejectsCallbackWithoutResultMaterialization+independentKeyLookupCanProveBindingAfterLostAck,McpExecutionPostgresTest#repeatedReadUsesOnePersistedFactAndOneRealToolsCall+sentWriteDisconnectPersistsUnknownAndDuplicateEngineRequestNeverResends+unknownMcpWithoutQueryContractCannotResendDuringRepeatedReconcile+explicitHttpEvidenceContractStillReconcilesOriginalMcpCall` | 42/42；0 failures/errors/skipped；BUILD SUCCESS；09:00:56 +08:00 | `review-skill-expected-attempt-guards.log` |

独立日志实际数据库为 PG16.15 `127.0.0.1:25432` 的 `agent_platform_v12`、`agent_platform_skill_async_test`、`agent_platform_skill_callback_test`、`agent_platform_skill_proof_test`、`agent_platform_skill_mcp_test`，均非运行库 `agent_platform_dev_v12`。夹具只用临时 loopback HTTP/MCP；没有实际模型、本体、IAM、K8s 或生产端点验收。

HttpAsyncAttemptFenceTest 独立观测：19 项合计 POST=1/GET=3；同原代并发合计 POST=1/GET=2，合法 queryOnly GET=1；换代后无新增查询和写回。旧 submitted ACK、缺 claim attempt、错 claim attempt 独立观测均 POST=0/GET=0。其他 AsyncHttpExecutionTest 有主动清零计数的既有范围测试，因此不把各行计数汇总宣称完整流量。

## SHA、真实补丁与红绿证据

独立重新计算 baseline 全部61份 SHA、changes.json 的11 modified/2 added前后SHA，以及最终63份 src/POM 与 frozen、final-source-before/after 的对应 SHA，无不一致。审查测试后又核对当前文件，仍等于冻结。

补丁实际 SHA256 为 `90500a461d1369d293956524303665099985344523cef4a33f624f6327d1ce5b`。在新建 `review-skill-expected-attempt-replay/` 目录复制完整 baseline，初始化仅用于重放的 Git 仓库，实际执行：

```powershell
git -C <review-replay> -c core.autocrlf=false apply --check --whitespace=nowarn <absolute-patch-path>
git -C <review-replay> -c core.autocrlf=false apply --whitespace=nowarn <absolute-patch-path>
```

两次退出均为0，重放63份文件 SHA 均与 frozen 相等。没有以无 HEAD 的空 diff 证明范围。结构化审计记录见 `review-skill-expected-attempt-audit.json`。

已核查作者四份行为红日志：13项/8失败、旧ACK 1项/1失败、六种CAS身份6项/6失败、claim2项/2失败，均进入 JUnit 后产生行为断言失败，非编译失败；缺/错claim的红日志实测各POST=1/GET=1。独立绿运行复现相应修复结果。本次没有恢复源码去重跑行为红，因此红阶段是原始证据核实，绿阶段为本审查独立执行。

另从作者冻结 `final-surefire/TEST-*.xml` 实际汇总24个suite=297项、0失败/错误/跳过，与最终完整日志一致；这属于核实作者最终完整轮，不能冒称审查者独立重跑297或完整846。作者中间295轮不作为最终冻结验收。

## 限制与后续边界

HTTP同步、MCP、ALGORITHM本次仅完成原代参数传播和入口校验，没有新增持久发送栅栏。ALGORITHM仍缺完整PREPARING/UID/Pod归属及有界I/O协议；其既有DONE更新也不能误读成HTTP异步同等级的原代写回保证。此限制已由作者明确披露，符合本增量边界，不在本次擅自扩实现。

HTTP异步CAS保证的是原身份尚有效时唯一取得发送权；本次没有实现UNKNOWN reset、新代许可或自动重发。未来若允许新代切换，仍须审查CAS成功至外部实际发送之间的许可协议，不能仅靠此增量推导任意新代重置安全。全部既有 `retryBusinessAllowed=false` 语义保留。

未执行完整846、资源/慢正则/耗尽探针、实际生产端点或主服务操作。skill Maven target 已结束独立使用并交还 root；报告和审查独立证据是本审查唯一新增内容。
