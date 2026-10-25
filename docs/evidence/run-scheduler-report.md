# G5 持久公平调度与本地分池实现验证

日期：2026-10-20。作者验证结论：本调度增量已完成代码与最终模块回归，**待独立规格/并发审查及根任务两实际 JVM 纵切，不作已部署或完整 G5 关闭声明**。

最终 engine 全模块 **396/396**，0 failures/errors/skipped，BUILD SUCCESS，07:25:41 +08:00；gateway 全模块 **16/16**，同样全0，07:22:50。两模块分别执行，不能合并为一次全后端整轮。23 个冻结源文件在最终回归前后摘要完全一致，21 个 Java 文件 AOSP 格式干跑及原契约/七模块依赖边界检查通过。

## 实现与权限边界

- `RunScheduleRepository` 使用 engine.run 的 `dispatch_next_at/owner/until/generation` 与短事务 `FOR UPDATE SKIP LOCKED` 领取调度机会；按下次时间/id 轮转，不再固定扫描前100行启动线程。仅选择有 READY 或真实过期 RUNNING 节点的运行，PG 领取返回最小列；不在队列 SQL 另写汇合/错误边解释器。
- V215 只增加4个字段、配对/非负约束及交互/批量部分索引，不改变原节点租约、attempt、callId、call_seq、幂等键或技能事实。调度领取本身不创建节点 attempt。首次 PENDING→RUNNING 与原 RUN_STARTED 事件同事务且仅一次，失败完整回滚；旧 RUNNING 接管不补造第二次开始事件。
- `RunScheduler` 对 CHAT/TASK/DEBUG 与 EVALUATION 分别取本地有界槽；取到槽及 PG 机会后才启动虚拟线程。没有无界内存工作队列。同实例同 run 去重；每 tick 的领取预算固定为该池起始可用槽，极快完成和连续新工作也不能让一次 poll 无界循环。
- 调度租约与原 node lease 分开：前者仅允许尝试推进，后者仍是外调、节点 attempt 与逻辑提交的执行权。TraceStore.commit、ToolCalls 的原节点代次/发送栅栏/唯一提交规则保持。旧调度 owner 不能续租或释放新 generation；在途节点事实仍按原检查点与 W3/回调裁决，不因调度换代被丢弃。
- 新交互先按已有请求号读已受理工作，再预约容量；满载429/停止受理503，均有统一 ApiResponse 的 `data.retryAfterSeconds=1` 和 `Retry-After: 1`。拒绝在新 run/chat_turn 写入前。容量拒绝与并发首次创建之间，再用原请求号仲裁短事务核对已受理结果；冲突仍409。
- ChatSessions 保留已审核的固定 Schema 校验。首次轮次写入前预约，RunService 复用同一 ticket；原 requestId 和已受理但 run_public_id 为空的崩溃预约可以恢复，不因当前满载丢失既有工作。ticket 明确区分 OPEN/PERSISTING/CLAIMING/TRANSFERRED/CLOSED，重复移交、关闭后复活或二次持久化均拒绝；close 遇仍在写入/领取时延后释放，移交后由真实 worker finally 归还。
- EVALUATION 持久为 PENDING 后返回原 run 结构；新建和重复请求均不再同步 drive 1000 次。受理后的即时唤醒只领取本池一个机会，不在 POST 中扫描无关外部事实或排完整积压。后台定期推进；monitor 的 PENDING/WAITING_EXTERNAL 分类是根任务此前独立完成的前置，本轮未改 monitor API、评分或数据集规则。
- DEBUG、正式、CHAT turn、评测、受控草稿测试、reexecute/reproduce、STEP续跑、确认与外部事实恢复接入同一调度。确认/超时/事实就绪在原短事务内唤醒；不从观察连接重发业务。
- 网关仅在原响应头白名单增加 Retry-After；实际验签链测试同时断言不透传 X-Internal-Owner。未修改 common/model/skill、InputDeclarations、OutputPolicy、FlowDriver 节点语义或冻结契约。

## 续租与停机

poll 与 heartbeat 各使用一个本地专属的有界单线程 timer，独立于 Spring @Scheduled。活跃调度表为 ConcurrentMap，heartbeat 不获取准入/poll gate；PG 领取和准入写入不持 Java gate。真正开始持久化前通过准入检查，随后是已进入执行的短事务，停止受理不承诺撤销该在途事务；新领取一旦停止，已受理工作可以留在 PG 等后续副本。

本 context 的 ContextClosedEvent 立即停止新准入/领取，子 context 事件不误停父实例。专属 heartbeat 保持到 worker 排空或 drain 上限，之后停止 timer；阻塞 worker 仍持本地槽直到真正退出。超限不强行改节点/调度租约为过期，不把停止线程或进程描述为外部回滚。旧 owner 的 finally 只尝试带 owner/generation 的释放，不能清掉新 owner。

内部启动日志为：`Run scheduler started owner=<uuid> interactive=<n> batch=<n> leaseMs=<ms> drainMs=<ms> pollMs=<ms>`，便于根任务将 PID 与 PG owner 对应；不出现在用户错误体。

## 红绿与中间失败的完整记录

| 记录 | 实际结果与边界 |
| --- | --- |
| `.local/run-scheduler-legacy-red.log` | 旧代码2项明确失败：第150个 READY 永不被扫描；同一阻塞 run 被启动13次。不是编译/环境红。 |
| `.local/run-scheduler-first-green.log` | 同2项通过，07:00:53；engine_test 从214升级215。 |
| `.local/run-scheduler-matrix-first.log` | 中间14项通过，含两repository、SKIP LOCKED队首、代次、分池、票据与无负载drain；不代表后来新增阻塞续租已通过。 |
| `.local/run-scheduler-gateway-red.log` | 真实网关过滤/转发下，上游 Retry-After=3，客户端收到null，1项明确失败。 |
| `.local/run-scheduler-full-first.log` | 中间382项，2失败+3错误：原评测即时终态断言与异步行为冲突，遗留活跃worker同下一测试清理竞争导致FK错误。没有将这次算通过。 |
| `.local/run-scheduler-heartbeat-red.log` | 新增3项均失败：readyFacts或claim/gate阻塞导致活跃PG调度租约过期；关闭子context误停父调度器。随后独立双timer与context归属修复。 |
| `.local/run-scheduler-heartbeat-green.log` | 中间3项中仍1失败：子context测试被旧测试租户积压的合法429影响；不算3/3通过。后续隔离 fixture、直接验证父实例仍可持久受理。 |
| `.local/run-scheduler-matrix-recovery-green.log`、`.local/run-scheduler-stall-threads.txt` | 移出gate后发现极快worker能在同poll反复回收槽，原while失去上界。jcmd确认主线程在真实PG claim中循环；只停止经完整surefire命令核验的本轮测试JVM61548，未停止服务。该轮为中断，不能算通过。 |
| `.local/run-scheduler-matrix-recovery-final.log` | 修复每tick固定预算及测试生命周期后，17调度+24原Trace测试=41/41，07:15:19。 |
| `.local/run-scheduler-ticket-red.log` | 4项明确失败：受理batch同步扫描无关事实、票据重复移交、已关闭票据复活、同预约二次持久化。 |
| `.local/run-scheduler-ticket-green.log` / `-green-final.log` | 首次因其他测试租户遗留 READY 产生3个准备错误，不算通过；明确清理本工程专用测试库 fixture 后，4/4通过。 |
| `.local/run-scheduler-persist-close-red.log` | 写入仍阻塞时close提前退槽，期望0实际1；状态机修复后被最终24项调度矩阵覆盖通过。 |
| `.local/run-scheduler-matrix-http-final.log` | 中间22调度+5真实HTTP+2入口边界=29/29，07:21:11。 |
| `.local/run-scheduler-bounds-atomic.log` | 2项补充通过：快worker保留READY时单tick仍有硬预算；PG触发器令RUN_STARTED插入失败时状态/代次/水位全部回滚。 |
| `.local/run-scheduler-full-final.log` | 最终396/396，0 failures/errors/skipped，07:25:41；包含全部旧365项与新增31项。 |
| `.local/run-scheduler-gateway-final.log` | 最终gateway16/16，0 failures/errors/skipped，07:22:50。 |

新增31项由 RunSchedulerPostgresTest 24、RunAdmissionHttpTest 5、RunAdmissionBoundaryPostgresTest 2组成。真实PG检查两个独立repository并发、队首行锁跳过、101个有效租约不挡尾部、150号轮转、不制造重复worker、两池隔离、取消/异常准备回收、票据生命周期、drain上下限及真实 GenericApplicationContext 关闭期间续租。阻塞readyFacts和claim时，实际PG调度租约在450ms后仍有效，lease配置300ms；不是只检查回调计数。

真实随机端口 Boot HTTP 经 Nimbus/JWT 和 PG，覆盖429头/错误体、满载零新run、新CHAT轮次零预约且next_turn不变、旧请求号满载回读/冲突409、既有空预约满载恢复到PENDING及成功、503与已受理请求继续回读。边界测试验证受控草稿/reexecute/受控reproduce同样在满载时零新增run/call或模型调用。外部模型/技能没有作为本轮真实供应方验收。

## 旧测试适配与审查限制

本轮修改6个既有测试文件：ChatSessionsTest、ControlledDraftSkillTest、ControlledReplayTest、RunInputValidationPostgresTest、ToolCancellationRaceTest、TracePostgresTest。将原四参 new RunService 改为 `SchedulerTestResources.runs`，显式注入真实、有界的 RunScheduler；测试清理前关闭其worker，避免删PG fixture时仍在写入。TracePostgresTest 每例重建调度器；两处原即时终态断言前等待同一原run，仍保留原 SUCCEEDED/WAITING_APPROVAL、输出、幂等、授权与逻辑提交断言。没有增加生产同步评测测试开关。

没有在首次改写前保存这6份原文件的完整备份。`.local/run-scheduler-original-tests/` 是逆向恢复候选，**仅 RunInputValidationPostgresTest.java 与675阶段原SHA逐字节匹配**；另外5份verification.json明确为false，不能充作原文快照或证明逐行未弱化断言。独立审查须结合当前完整源码、原675摘要与既有报告，并可增加独立行为探针。

[测试保留核对](run-scheduler-test-preservation.json) 将原675日志每个既有engine测试类与本轮计数逐一比较，所有旧类用例数保持，新增31项。计数一致不能证明断言完全等价，本报告不以无HEAD的空diff冒充旧全文比对。

## 命令、冻结与后续交接

命令仅从本工程.env加载进程变量且不打印，选择JDK21、ENGINE_PG_TEST=true；没有带-am、打包运行中的Windows JAR、启停业务服务或提交Git。

```text
mvn.cmd -B -ntp -pl engine-runtime test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
mvn.cmd -B -ntp -pl platform-gateway test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
node scripts/verify-contracts.mjs
java -jar .tools/m2/com/google/googlejavaformat/google-java-format/1.25.2/google-java-format-1.25.2-all-deps.jar --aosp --dry-run --set-exit-if-changed <本轮21个Java文件>
```

冻结清单为 [23个源文件SHA256](run-scheduler-sha256.json)，与 `.local/run-scheduler-source-before-full.json` 23/23相同。清单SHA256=`2644637f158394d69826382a999583a4d1bf05303364babb3748d5f47b246e36`。

最终engine日志SHA256=`b6565d52d3184ed949a7582292b12c2be82b2cdda76181404cf638ffb4acd30a`；gateway日志=`5b57de2f560014279516ea7804f67145246fef26f906ac69f2138d3ee123ad00`。保护文件仍为 InputDeclarations `d7e3ea02fc12fb6574513fabc759fd978c2c42b4c3c340e9bfb46207644b5e89`、OutputPolicy `cebcbaf813d4da4f77e453dbfb2e3d247ead8a31dca522d6f2ac4145696f53de`、FlowDriver `a0e65396eb52818a091b33cb1606053b0ddc4969381cace0b2b0f9e8b407c563`。

实际读取JDK21 src.zip中 Semaphore 的tryAcquire/release及ScheduledThreadPoolExecutor的固定延迟/关闭实现，GPL-2.0 Classpath例外；本地spring-context6.2.7（实际读取Apache-2.0许可）通过javap核对ScheduledAnnotationBeanPostProcessor的ContextClosedEvent分支及manualCancellationOnContextClose集合取消任务。后者为字节码检查，不能表述成阅读完整Spring源码，也不声称所有singleton任务均在事件时取消。调度专属timer的正确性以本轮真实context关闭/阻塞试验为证据。[PG16官方锁定子句](https://www.postgresql.org/docs/16/sql-select.html) 对照用于队列领取；SKIP LOCKED不是严格全局FIFO公平承诺。

engine/gateway target与本轮源文件已释放给根任务。独立审查后由根任务在全新专用库、18086/18087同审核JAR上做两JVM取证；使用本测试的纯FLOW START→END快照、有效头部节点租约和PENDING尾部，可验证调度机会真实过期接管与唯一开始/逻辑提交。不能把READY机会接管称为在途写调用节点接管。全新库迁移、两实际进程、本机真实容量与OS优雅停机不在本作者本轮通过声明中。

前端自动观察原评测报告及可读判分仍是根任务安排的必要纵切，不能因API结构兼容省略。三类分布式许可、完整Flow分支并行、全局/租户队列配额、其余内存硬闸与500/5000/200容量试验继续保留；没有开启生产IAM发布门禁，也没有关闭完整G5/T05/T33。
