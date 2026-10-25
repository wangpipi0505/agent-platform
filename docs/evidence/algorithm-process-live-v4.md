# 算法真实进程第四轮：complete 通过，丢 ACK 在节点 attempt 总数断言失败

2026-10-20，root 移交相同962固定制品及同一artifact-lock，使用不变的已审runner SHA `257e8545c4818d1d40a235a32a3c10d6912997e10d9a0aacf876bd56d3b75688` 实际运行，最终退出1。完整输出 `algorithm-process-live-v4-execution.log`，本轮原始目录 `algorithm-process-runner/live-f7fecdf481a3/`；原三actual和全部返修字节保持。没有自动重提业务初始请求或擅自修改源码、fixture、原4秒/仅offload15秒期限。

固定engine SHA `d19504f307dd5ac956ce5cc9556cef2119b038a43913f9206288f7069440fcb7`、skill SHA `2543a6c9c3aa0bd8ec6f0d893093e3b9fe5f4664a0634ea6dd509f07b0835196`。唯一新库 `agent_platform_algorithm_live_f7fecdf481a3` 保留；独立只读PG补核engine215、skill311迁移成功。

## 实际场景与失败位置

complete 已通过runner全部该场景断言：run `r_d4ba885d57be4dccad0af8ed3e922c6c`、call1、外部原attempt2，非Mock DEBUG fact/工具调用/运行SUCCEEDED，绑定原UID/DONE，首次GET404→认证POST1，完整structured JSON，Pod controller/container日志路径，原controlledCallId重检不再POST，工具逻辑提交1、accepted终态事实1。完整证据complete.json与fixture-1-final.json。

ack-loss 实际run `r_2700d27889274aea890a7042b9e0f994`、call2、原外部attempt5。初始fixture已收一次POST后丢ACK，原事实/call UNKNOWN、run WAITING_EXTERNAL。受限内部对账仅GET原Job、Pod/log，随后原fact/call/运行SUCCEEDED、binding DONE且采纳原Job UID `f5c374eb-0bed-48da-884a-42fc1a2ad114`；最终fixture仍POST1，无重发。工具逻辑提交1，accepted终态事实1，run只有一条call、一条非Mock fact。

runner第400行 `assert.equal(scenario.after.attempts,1)` 实际得到2而退出。该字段查询同一SKILL node全部 `engine.attempt`，不是外部调用代次数。该失败原样保留；尚未完成本场景后续连接重检/全部自动断言，所以不能将ack-loss列为runner完整通过。failed/running/offload均未执行，无MinIO READY/下载SHA结果；本轮整体仍失败。

## 原代与节点恢复逐行分类

`algorithm-process-live-v4-readonly.json` 在服务停止后仅对本轮新库 begin read only，退出0，保存全部run/call/fact/binding/node/attempt/event/note；`algorithm-process-live-v4-attempt-classification.json`保存定点分类、四份相关冻结源码SHA与关键片段。

实际外部原代完全固定：before/after call.attempt_id=5、fact.attempt_id=5、binding.attempt_id=5，fact/binding request_snapshot.attemptId也为5；call.claimed_by、fact.execution_owner和两个snapshot.leaseOwner均保持 `c7c0989b-cfaa-44d3-8755-60c7efd6f6db_733c3e0f`，两个request_snapshot整体字节结构未变。原call/key/Job/UID、DEBUG/nonMock均有原始证据。readonly_retry_count=0，没有新call或newPOST。

同一node5的两行node attempt分别为：attempt5/no1 UNKNOWN，attempt6/no2 SUCCEEDED；两行使用相同node幂等key，node.committed_attempt_id=6。终态 `TOOL_EXECUTION_FACT` note仍绑定原attempt5且只接受一次；其后事件EXTERNAL_READY→NODE_STARTED attempt6→唯一SKILL LOGIC_RESULT_COMMITTED attempt6。START与END节点各自逻辑提交不混入技能逻辑提交计数。

实际源码说明该本地恢复路径：RunService.recoverExternalReady在所有原toolcall已确定后将WAITING_EXTERNAL node置READY并发EXTERNAL_READY；TraceStore.claim每次节点本地驱动创建下一node attempt；ToolCalls.register按同原幂等key `on conflict do nothing` 返回已存call；AgentDriver.skill第287—288行在registration.status=SUCCEEDED时立即返回已存result，位于新的skill execute POST之前。因此额外node尝试消费原已存结果，不能只凭node总数2就认定外部业务换代。root也定点核对了v1.2快照中节点committed_attempt_id/attempt_no与对账attempt留痕的语义；本报告只分类现有事实，不改生产语义。

已向root提出最小新门禁，尚未获实施授权：非恢复场景继续总数1；仅ack-loss的before UNKNOWN/WAITING且外部三方及各snapshot原attempt/owner全部固定时，允许恰一条额外node本地attempt（no2、SUCCEEDED、同node幂等key），并要求它是committed_attempt_id。保存逐行attempt，验证原外部attempt绑定唯一、终态accepted note仍是原attempt且一次、终态note先于EXTERNAL_READY/额外NODE_STARTED/唯一逻辑提交；继续call/fact各1、readonly_retry_count0、POST1/原UID。任何新外部代、owner变化、新call/newPOST、第三条node尝试或事件不符均拒绝。不会直接删除总数断言或改成任意上限。

## 收尾

本轮顺序共创建四个私有JVM，最多同时一engine/skill：64824/51095、47740/51094、58636/63423、59072/63422，均记录精确JAR/创建时间/listener归属且stopped=true；原finally各final.pid=null/listenerPids=[]，无清理错误。有限fixture均关闭。独立再次只读查询这些记录PID/端口也全空，见 `algorithm-process-live-v4-cleanup-readonly.json`。没有接管主六服务或drop专用库。

目前不修改runner、不重跑、不将部分协议成功改写为本轮整体通过。等待root阅读分类证据后定窄修复与独立复核，后续真实执行必须另行授权并保存新一轮证据。
