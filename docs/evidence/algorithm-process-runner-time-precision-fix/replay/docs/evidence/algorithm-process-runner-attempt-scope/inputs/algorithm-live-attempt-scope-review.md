# 算法真实进程 v4：本地 attempt 与外部调用身份的限定复核

2026-10-20。独立复核范围为 `live-f7fecdf481a3` 的 ACK 丢失恢复及 runner 的次数判定。只读新工程设计、源码和已保存的原始证据；没有接入服务或数据库，没有启动 Maven、JVM、PG、fixture，没有修改生产、runner、契约或既有证据。唯一新增文件为本 memo。

**需求符合性：APPROVE（仅本次恢复语义）。** node5 从 attempt5/UNKNOWN 恢复到 attempt6/SUCCEEDED，是节点重新认领和消费已存执行事实的留痕；原外部调用仍为 call2、attempt5。设计与本次落库证据均支持此路径，不能据节点有两条 attempt 判定外部调用换代或重复业务。本结论不批准整轮真实进程验收。

**质量：REQUEST_CHANGES（runner 的 P2 验收口径）。** 当前 `tests/smoke-algorithm-processes.mjs:400` 将同一 SKILL 节点全部 `engine.attempt` 的计数固定为 1，必然拒绝这里正常发生的恢复。应以本文的逐行本地恢复证据和原外部身份不变量替换该场景的错误断言；保留其他场景保护。所读生产路径未发现由这次 attempt=2 现象证明的生产恢复缺陷，不建议为迁就此断言删除生产恢复留痕或重绑原外部 attempt。

## 设计与源码依据

所有源码位置相对新工程根目录，行号以本次只读版本为准。

| 依据 | 本次判断 |
|---|---|
| `contracts/snapshot/detailed-design-v1.2.md:245–249` | `node_instance.committed_attempt_id`、`attempt(node_instance_id,attempt_no)` 与独立的 `tool_call` 登记身份分开；当前有效租约只允许一次逻辑结果提交。逻辑提交的 attempt 不必等于早先发送外部请求的 attempt。 |
| 同文件 `:251`、`:1755–1757` | 已提交未知时对账原确定性 Job/绑定，已有执行事实时补逻辑提交；恢复复用原 call_seq 和幂等键。这里要求单次逻辑业务身份，不要求节点只被认领一次。 |
| 同文件 `:1750`、`:1754` | 明文“认领即计 attempt”，等待态不占执行权，逻辑节点与执行尝试分离。这是判断本地新增行合法的直接依据。 |
| 同文件 `:1003–1006` | 已生效则采纳结果，对账留痕。该条不能单独推导“每次 GET 都新建一个 engine.attempt”，也不授权替换原外部身份。 |
| 同文件 `:1895–1903`、`:2092` | 接管从检查点恢复；旧执行事实不因执行权转移而作废；执行事实可重复消费，逻辑结果归引擎提交。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:1000–1029` | 初次 RESULT_UNKNOWN 将原节点置 WAITING_EXTERNAL、原 attempt 置 UNKNOWN，并记录等待事件；没有把 UNKNOWN 当失败重新发送。 |
| 同文件 `:1564–1602`，方法 `resumeExternalFacts` | 原节点所有 tool_call 已 SUCCEEDED/FAILED 后，WAITING_EXTERNAL → READY，清除原节点租约，唤醒调度并记录 EXTERNAL_READY。v4 报告中称 `recoverExternalReady` 是方法名误记，实际方法为此处的 `resumeExternalFacts`。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:110–145` | 重新生成节点 lease owner；取 `max(attempt_no)+1`，插入新本地 attempt 并发 NODE_STARTED。节点幂等键仍为 `sha256("node:"+nodeId)`；只有 no1 消耗 steps。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/FlowDriver.java:202–211`；`application/AgentDriver.java:262–288` | 同一 SKILL 节点以 call_seq=1 调用；幂等键为 run/node/seq/version，调用 `register` 后若已 SUCCEEDED 立即返回已存 result。新的 `/execute` POST 在 `AgentDriver.java:292`，位于该返回之后，本次恢复不走它。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java:41–68,140–149` | 原 key 冲突时不新建 call，并核参数摘要；只有 REGISTERED 才更新 call.attempt_id，SUCCEEDED 原 call 不改为本地新 attempt。 |
| 同文件 `:205–249`，`claim` | 校验当前节点租约；终态直接返回未取得执行权；仅 REGISTERED→EXECUTING CAS 可改 claimed_by/attempt_id。终态复用不是一次新外部认领。注意本次 AgentDriver 已提前返回，此方法是补充机制证据。 |
| `application/RunService.java:950–965`；`infra/TraceStore.java:170–207` | 本地恢复返回原 result 后，由当前 attempt/owner 提交。CAS 要求 RUNNING、有效租约和 committed_attempt_id 为空；同事务将 attempt 置成功并发唯一 LOGIC_RESULT_COMMITTED/NODE_FINISHED。 |

## 原始证据对应

主要原始材料是 `docs/evidence/algorithm-process-runner/live-f7fecdf481a3/ack-loss.json`、同目录 `fixture-2-final.json`，及 `docs/evidence/algorithm-process-live-v4-readonly.json` 中 `.data` 的行。分类 JSON 和 v4 报告仅作索引，结论回查上述原始行及实际源码。

| 对象 | 实际值与前后关系 |
|---|---|
| run / node / call | run `r_2700d27889274aea890a7042b9e0f994`，数据库 run_id=2，SKILL node_id=5，call_id=2，call_seq=1。before WAITING_EXTERNAL，after SUCCEEDED。 |
| 原外部身份 | before/after 的 `call.attempt_id`、`fact.attempt_id`、`binding.attempt_id` 全为 5；fact/binding 的 `request_snapshot.attemptId` 也为 5。只读归档中 call/fact/binding 各命中一行，仍全部为 5。 |
| 原执行 owner | `call.claimed_by`、`fact.execution_owner`、两个 snapshot.leaseOwner 均为 `c7c0989b-cfaa-44d3-8755-60c7efd6f6db_733c3e0f`，before/after 不变。两个完整 request_snapshot 经解析后序列化比较均相等。 |
| 原请求 key | `7d69fc487dd17919c76c19df3784b0408a74430dfe6939c0630fddd309c1e190`，原 call/binding/snapshot 保持；readonly_retry_count=0。 |
| 绑定 | namespace `alg-live-f7fecdf481a3-ack-loss`，Job `alg-cee2b5d4ca67267e9026d3812b8489563464db68`。原 API/identity/contract 摘要不变。初始因 ACK 丢失 UID=NULL，之后采纳 fixture 已创建的 UID `f5c374eb-0bed-48da-884a-42fc1a2ad114`；这是首次绑定确认，不是 UID 换代。状态 SENDING→DONE。 |
| 本地 attempt5 | `.data.attempts`（readonly JSON `:475` 起）：node5/no1，UNKNOWN，原请求的发送尝试。开始 `11:23:52.299522+08:00`；没有逻辑成功提交。其 UNKNOWN 留痕未被改写成恢复成功。 |
| 本地 attempt6 | 同文件 `:496` 起：node5/no2，SUCCEEDED，开始 `11:23:56.073884+08:00`，结束 `11:23:56.080950+08:00`。与 attempt5 共用节点 key `26ff519f381b17dcd5bdfe0b675c7f00057ae035262355b03a6abd77aaa60667`。 |
| 节点最终提交 | node5 的 committed_attempt_id=6（同文件 `:662–707`）；当前节点 lease owner 后缀 `_d7944601`，与外部原 owner 不同符合重新认领。不能拿当前 node owner 强行覆盖外部原 owner。 |
| 终态事实 | 同文件 `.data.notes`，note3（`:1123` 起）是唯一 accepted=true 的终态 TOOL_EXECUTION_FACT：call2、attempt_id=5、payload.attemptId=5、原 owner、SUCCEEDED、非 Mock。此前 note2 是 accepted UNKNOWN；不能把“所有 accepted notes”为 2 误判为接受两次终态。 |
| 顺序与逻辑次数 | 终态 note3 `11:23:55.965534` → EXTERNAL_READY seq7 `11:23:56.064000` → NODE_STARTED seq8/attempt6/no2 → 唯一 SKILL LOGIC_RESULT_COMMITTED seq9/attempt6 → NODE_FINISHED seq10。START/END 的提交不计入 SKILL 节点计数。 |
| API 接收 | `fixture-2-final.json:2–24`：POST=1，jobGets=2，podLists=1，logGets=1；路径为初始原 Job GET、一次 POST、恢复原 Job GET、Pod、log。终态与原 fixture UID 对应。证明有限 API fixture 接收一次创建，不证明生产集群执行。 |

`ack-loss.json` 中 before 为 toolCommits=0、attempts=1；after 为 toolCommits=1、attempts=2、runCalls=1、runFacts=1、acceptedTerminalFacts=1。after 中 call/fact 已成功、binding DONE，实际 structured 结果为 `{"value":"ok-ack-loss"}`。这些值与停止服务后归档的 PG 行、事件相互吻合。

本地 attempt6 没有改为原 call 的执行 attempt，也没有得到新事实/新绑定。该组合与实际源码的“注册复用已成功结果→本地提交”路径一致。单看两个 attempt 或单看 POST=1 都不足以下结论；这里同时核了身份、行、事件、逻辑提交和 API 记录。

## P2：runner 将不同层次的次数混为一谈

位置：`tests/smoke-algorithm-processes.mjs:355–364,400`。capture 中 `attempts` 查询同 node 的全部 `engine.attempt`，第400行无条件断言 1。

触发：ACK 丢失使节点 WAITING_EXTERNAL，原 Job 对账成功回灌原 call，节点重新认领后消费已有结果。结果：正确生成 no2 本地恢复尝试，断言以 `2 !== 1` 退出，后续重检与其他场景未运行。影响为真实进程验收误报；若反向改生产以压成一条 attempt，会损害设计要求的执行权与恢复留痕。

v4 原始 exit=1 和失败报告必须保留。complete 已走完该场景原 runner；ack-loss 只有上述部分证据成功，尚未通过整段 runner，failed/running/offload 未执行。父任务的 962 回归与 JAR 部署状态不是本 memo 独立执行的验证，也不能消除 v4 失败。

## 最小 runner 修复口径

允许“仅 ack-loss 恰一条额外本地恢复”的建议方向成立；这是该有限单次恢复场景的精确 oracle，不是全系统 attempt 永远最多为 2 的规则。不能只删除总数断言或放宽为 `>=1`。应一次保存并检查以下证据：

1. **保存逐行快照。** before、after、controlledCallId 重检后的 afterRecheck 均保存同 node 的全部 attempt（按 attempt_no/id 排序）、node 当前提交字段、call/fact/binding 的行集合、accepted 终态 note 行和带时间的 events；保留原整数计数便于核对。不要用 scalar 子查询默默掩盖重复行，集合长度必须明确为 1。节点提交计数始终限定原 run+SKILL node。
2. **ACK 前提与本地恢复必须精确。** 只有 mode=ack-loss，before run WAITING_EXTERNAL、call/fact UNKNOWN、原 no1 UNKNOWN、逻辑提交0，才允许 after 恰两行：原 no1/id 不变，新增 no2/SUCCEEDED，node/key 相同；原行无成功提交。新行 id 等于 node.committed_attempt_id，不能等于原外部 attempt。EXTERNAL_READY 恰一次，NODE_STARTED 精确映射这两行；accepted 原终态事实之后才有 EXTERNAL_READY → no2 NODE_STARTED → 唯一 SKILL 逻辑提交，提交 attempt 正是 no2。事件顺序用同 run seq；跨 notes/events 使用保存的 PG created_at 和身份关联，不要求时间必须相差正毫秒。第三条本地 attempt、错序、其他 attempt 提交均拒绝。非恢复场景继续原总数1保护。
3. **外部原代固定。** 从 before 钉住 call id/run/node/seq/version、args digest、contract digest、idempotency key、claimed_by/attempt_id；fact 的 tenant/principal/run/call/version/attempt/execution_owner；binding 的 tenant/principal/run/call/attempt/key、API/namespace/Job/identity/contract。after 及 afterRecheck 均须相同且唯一。两份原 request_snapshot 整体深相等，并核其中 attemptId/leaseOwner/runId/callId/nodeInstanceId/callSeq/version/key/arguments 与原 call/事实对应，拒绝把 snapshot 或三方其中一方悄悄换成 no2。保持 nonMock、DEBUG、readonly_retry_count=0。结果信封 owner 也应仍指原 attempt；原结果被本地提交后的 node.output 与预期 structured 结果相等。
4. **UID 采用正确比较基线。** ack-loss before.binding.external_job_uid 可为 NULL；after 必须等于 fixtureBefore 已记录的原非空 jobUid、原 namespace/name，且 terminalEvidence 的 UID/Job/API/identity 一致。afterRecheck 继续固定该 UID。不能简单要求 before NULL 等于 after UID，也不能只比较两个事后快照而允许首次绑定到另一 Job。其他已绑定场景 UID 前后不变。
5. **一次外部调用、一次终态采纳、一次逻辑提交。** 原 run 下只能一条原 call、一条原 fact及一条原 binding；原 call 的 accepted 终态 TOOL_EXECUTION_FACT 恰一行，note.attempt_id/payload.attemptId/owner/result.owner 均为原身份。允许原 UNKNOWN note 存在；不把 UNKNOWN note 算终态。fixtureBefore、after 和重检后 POST 均为 1；创建与查询路径必须指同原 Job，重检不新建业务 run/call。逻辑提交也始终只有一次，不能仅看最终 run=SUCCEEDED。
6. **重检后重新采集数据库。** 当前第396行采集 after，第403行才重检；第419行只复查 POST。因此只增强现有 after 的断言仍会漏掉重检新建本地 attempt/call、重复采纳或重复逻辑提交但未 POST 的缺陷。完成既有 controlledCallId 重检后须重新 capture 并重验上述身份、集合与次数，结果才可判该场景通过；无需再发一次初始业务请求。

建议给窄改 oracle 增加有限纯 Node 负例：以合法完整证据为基线，分别注入第三 attempt、错 committed_attempt_id/事件顺序、任一外部 attempt/owner/snapshot 漂移、不同 UID/新 call 或绑定、POST=2、重复终态 note/逻辑提交，以及仅 afterRecheck 才出现的漂移，均应拒绝。无需改生产、期限或运行资源探针。本复核未执行这些未来用例，也未授权重跑真实进程。

## 只读验证与边界

实际使用 PowerShell `Get-Content`/`ConvertFrom-Json`、`rg -n` 定点读取与筛选；用 `Get-FileHash -Algorithm SHA256` 独立核验 v4-summary 列出的 **19 个证据文件全部匹配**，classification 中 **4 个源码 SHA 全匹配**。当前 runner SHA 为 `257e8545c4818d1d40a235a32a3c10d6912997e10d9a0aacf876bd56d3b75688`。此外独立读 FlowDriver（SHA `a0e65396eb52818a091b33cb1606053b0ddc4969381cace0b2b0f9e8b407c563`）及设计（SHA `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`）。比较两个完整 request_snapshot 均相等，筛选已归档 PG JSON 证实 call/fact/binding 各一行及上述两个本地 attempt。

本轮新增运行测试 **0**，新增 PG 查询 **0**，Maven/JVM/服务调用 **0**。这是代码与已保存真实进程证据的独立复核，不能称本复核重新亲跑成功。v4 清理记录已归档，本复核未操作任何进程。范围不含生产 K8s/RBAC/IAM、实际容器、全 G2、多副本/多次恢复普适证明或未执行的 MinIO 场景；此前协议复核历史和首轮失败事实不作改写。

移交：限定设计判断完成。生产无需因本问题改动；runner 窄改及其验证需由父任务另行授权。现有 v4 失败证据保持，未占用 target 或运行资源。
