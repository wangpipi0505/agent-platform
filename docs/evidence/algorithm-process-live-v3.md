# 算法真实进程第三轮：complete 提交后 UNKNOWN，未完成纵切

2026-10-20，root 再次授权相同962固定 engine/skill JAR 与同一 artifact-lock，使用已审 runner SHA `e30638e7f0642570f8a5b5740678afff6153fd64aab7b4107696390059389c93` 实际执行五场景入口，退出1：`Timed out: terminal delivery complete`。原始目录 `algorithm-process-runner/live-5b3418e87817/`，命令输出 `algorithm-process-live-v3-execution.log`。原 v1/v2/两修复证据不覆盖，runner/fixture/超时/生产源码没有修改或重跑。

本轮唯一库 `agent_platform_algorithm_live_5b3418e87817` 保留；engine Flyway215、skill311核对成功。两个私有 JVM 先通过精确 PID/creation/JAR/listener 归属及健康门槛：skill62900/55459，engine46180/55458。固定 engine SHA `d19504f307dd5ac956ce5cc9556cef2119b038a43913f9206288f7069440fcb7`、skill SHA `2543a6c9c3aa0bd8ec6f0d893093e3b9fe5f4664a0634ea6dd509f07b0835196`。

## 真实业务与 PG 事实

complete 场景实际注册草稿并创建受控 run `r_107994f6125f421b88b108b1552f47b2`，call1、技能节点原 attempt2（START节点自身attempt1不计工具attempt）、原 engine claim/skill execution owner `91a23a88-c3ff-4425-98ce-160281637554_26f6248c`。fixture 确认先原 Job GET404、后一次认证 POST；Job名 `alg-404772026b9963271ec5c6f778e45de7e33f19a8`，fixture生成 UID `e6817d8b-2acd-4a16-bcbd-70f0fb548d74`。没有重复POST或未授权请求，Pod/log请求0，拒绝上游计数0。

实际 SDK 在 POST_JOB 阶段报告 `IO_DEADLINE_OR_CANCEL`，原4000ms预算下技能执行累计4182ms。PG 原 fact/call=UNKNOWN、run=WAITING_EXTERNAL，callback_state=DELIVERED，binding=SENDING/send_started=true/UID=null，工具逻辑提交0、accepted terminal facts0、原工具attempt1条、call1条、fact1条、无artifact。完整原 request/key/attempt/owner/contract/Job/identity均在 complete.json 与只读 PG 补核 JSON 保存。fixture 的 UID 尚未被 PG可信采纳，不能把fixture已生成UID说成完整绑定完成。

runner 对 complete 仍要求成功终态，原30秒终态轮询没有等到 SUCCEEDED，因此如实失败；没有把 UNKNOWN 降格为 complete通过，没有重新提交连接测试、业务POST或对账，没有本地取消改写这个实际complete run。后四场景未执行，未取得 Failed/ack-loss/running/offloadREADY/下载SHA结果，不作通过声明。

`algorithm-process-live-v3-readonly.json` 是服务停止后只对本轮专用库显式 begin read only 的独立查询，退出0；保存完整run/call/fact/binding/event/note，并核对最终仍原 attempt2、execution owner和DEBUG call_kind，未换代。run仍WAITING_EXTERNAL，原绑定SENDING，UID未采纳。未知事实保留不能解释成外部效果未发生、允许重发或可撤销。

## 收尾与根因定点证据

本轮 finally 成功：两自有 JVM 都 stopped=true，final pid=null/listenerPids=[]，fixture关闭，没有清理错误。随后又只读查询记录PID62900/46180与端口55459/55458，均不存在，见readonly.json。只停止自己创建且再验证归属的进程，未接管六主服务，未drop库、删旧证据或修改事实。

失败后的结构检查发现：每次status HTTP之前，owned→inspect→powershell 使用spawnSync；有限 K8s fixture 又在同一 Node 线程。因此归属查询期间，fixture 的GET/POST响应不能推进。root要求实证后，运行独立有限 `algorithm-process-live-v3-blocking-diagnostic.mjs`，只调用原e306 helper、只查询已退出的本轮记录PID/端口，并使用独立本地 `/finite-probe` HTTP与25ms timer，不联系任何真实业务或Job：

- 实际原PID/端口检查耗1971.955ms；25ms timer到1972.007ms才回调，独立Node客户端HTTP耗1947.492ms；HTTP handler仅在同步helper返回后才运行。
- 合成PowerShell 350ms延迟总耗545.466ms；timer与HTTP同样全部等到helper返回后运行。

完整测量与helper原SHA在 `algorithm-process-live-v3-blocking-diagnostic.json`。这直接证明 runner 归属探测会阻塞同进程fixture，每轮约2秒的实际阻塞可以侵占SDK的原4秒预算；与本轮GET后POST deadline/UNKNOWN一致。由于原业务请求没有新增精确事件循环时序埋点，不能宣称已排除所有其他网络因素，但这个可重复的runner缺陷必须修复后才能可靠验协议。

已向root提出最小范围：仅将必要PowerShell执行改为有绝对timeout和stdout/stderr上界的异步spawn，并让inspect/freeBeforeStart及start/owned/stop/final records逐级await；保留全部精确归属、原预算/超时、环境白名单、UTF8、逐项清理。当前未获源码修改授权，没有修改或重跑。后续由root决定唯一修复范围并再次独立审查；本轮真实结果保持失败。
