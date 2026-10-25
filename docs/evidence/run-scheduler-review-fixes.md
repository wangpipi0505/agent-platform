# G5 独立审查三项问题修复

日期：2026-10-20，Asia/Shanghai。作者验证完成，待原审查者独立复验；本报告不覆盖原 REQUEST_CHANGES 记录，也不代替部署验收。

本轮仅修改 ChatSessions、RunScheduler、RunService、RunScheduleRepository 四个生产 Java 文件，新增 RunSchedulerReviewPostgresTest 与 RunSchedulerTimingTest 两个测试文件。V215、原有测试、网关源码及受保护的 InputDeclarations/FlowDriver/OutputPolicy 均未改动。

## 修复与失败测试

先运行新增测试，9 项中 7 项失败、0 error：`.local/run-scheduler-review-red.log`，07:41:50；修复后同一批 9/9 通过：`.local/run-scheduler-review-green-first.log`，07:42:45。

| 原发现 | 真实失败与修复 |
|---|---|
| P1 会话 drain 两窗口 | reserve 后立即 drain，实际 ChatSessions.turn 返回 503 却写入 1 个 turn；预约成功后 drain，则实际创建 run 被二次 gate 拒绝。首次短预约事务现纳入同一 ticket.persist；未受理前 drain 胜出，0 turn、next_turn=1、0 run。首次写入成功后，原 ticket 仅允许一次 completeReservation 完成，不再检查 drain；写入失败、未受理或已消耗的 ticket 无权调用该完成步骤。受理后 drain 得到原 PENDING run，原 requestId 回读同一 run、冲突输入仍 409。快照/HTTP 准备仍在预约事务之外。 |
| P2 一个行锁阻塞心跳 | 真实 PG 两个 active worker，锁住心跳遍历第一项 run，未锁另一项 dispatch 失效，node-renew 回调为 0；真实 node 和 tool_call 行锁也分别使原 renewLeases 超时。dispatch/node/tool 续租现用短语句或逐节点短事务的 FOR UPDATE SKIP LOCKED，单项异常隔离；一项失败不阻止其余项及 node/tool 续租。未修改线程数量，没有无限并发续租。owner/generation、未过期、节点归属及 claimed_by 条件保留，锁争用时不延长该项，不复活过期租约。 |
| P2 大 lease 的心跳周期 | 合法 300000ms lease 的实际 scheduleWithFixedDelay 参数原为首次/间隔 100000ms，超过固定 node 90000ms。首次延迟与间隔现均为 max(10,min(30000,lease/3))；100/90000/300000 三个配置分别验证 33/30000/30000ms。保持 node/tool 原 90 秒规则；这是实际任务参数测试，没有声称执行 90 秒过期实跑。 |

新增 PG 测试最终 13 项，覆盖上述两窗口/幂等、无首次成功受理的 ticket 拒绝完成、准备事务边界、真实 run/node/call 行锁、dispatch 单项异常、node 事务故障仅回滚本项，以及 expired/foreign node/call 四种不续租条件。node/tool 用例通过真实 TraceStore.claim 与 ToolCalls.register/claim/submitted 建立租约，测试用反射登记 activeNodes 和读取实际 owner；不声称这些用例另外跑过完整外部技能链。run 行锁用例使用实际两个调度 worker。定时参数测试仅拦截 JDK timer 工厂记录实际安排的参数。

扩大矩阵 `.local/run-scheduler-review-matrix.log`：92/92，07:45:35。原 24 项调度（含代际 CAS、锁跳过、公平领取、池隔离、取消、异常回收和 drain）、35 项输入校验、会话/恢复/HTTP 准入和工具取消等保留。随后仅整理格式、缩小断言的租户范围及将 call 锁用例覆盖到 SUBMITTED，再执行以下完整验证。

## 最终完整验证

Java21，真实专用 agent_platform_engine_test；仅从本工程 .env 读入进程变量且不打印。分别运行模块，未使用 -am、package、部署、服务启停或提交。

| 本轮执行 | 结果 | 完成时间 +08:00 | 实际日志 |
|---|---|---|---|
| engine-runtime 全模块 | 412，0 failures/errors/skipped | 07:49:48 | `.local/run-scheduler-review-engine-final.log` |
| platform-gateway 全模块 | 16，0 failures/errors/skipped | 07:47:05 | `.local/run-scheduler-review-gateway-final.log` |
| AOSP formatter 1.25.2 dry-run | 23 Java 文件，exit 0 | 本轮 | `.local/run-scheduler-review-format.log` |
| verify-contracts.mjs | v1.2 四契约、冻结快照、七模块边界通过 | 本轮 | `.local/run-scheduler-review-contracts.log` |

```text
mvn.cmd -o -B -ntp -pl engine-runtime test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
mvn.cmd -o -B -ntp -pl platform-gateway test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

engine 日志 SHA256：`a15e2533cb29d7adc6f06f2715b0bf7593cb755352e36e22213d8f331082d600`；gateway：`77e6aba1ccba5340b4cca92e8cdc84b3864df5f7cf585ca2e6cd64270a9c0b0a`。两个模块分别整轮通过，不合并为全后端验收。原 396 轮的 34 个既有测试类计数全部保持，新增 16；本轮原有测试文件逐字节未变。故障注入用例中的 SQL/IllegalStateException 警告为预期测试输出。

## 冻结、补丁与交接

- [本轮六文件修复清单](run-scheduler-review-fixes-sha256.json)，含四文件 previousSha256 和两新增文件；清单 SHA256=`702a2106feedd81a79c1c95a6eca77fadcbc02a22d56469dc12996ad400fe524`。
- [完整候选25文件清单](run-scheduler-review-candidate-sha256.json)，与 `.local/run-scheduler-review-source-before-full.json` **25/25 相同**。候选清单 SHA256=`d05ce9e2f919fdb3bf261c33a5a5947cc86beac1e0e8d39f1c0c556787edc1fd`。
- [单一六文件补丁](run-scheduler-review-fixes.patch)，SHA256=`04f2b81413d2db372fc72d900b6b0e4efc6ec350891a5140baa79e8ff450c30b`。修改前全内容位于 `.local/run-scheduler-review-baseline/`，23/23 与原冻结清单匹配。补丁从真实文件 no-index diff 生成；在 `.local/run-scheduler-review-patch-check/` 隔离副本执行 apply --check、apply 均为 0，结果 **6/6 SHA 与候选相同**。详见 `.local/run-scheduler-review-patch-verification.json`。不依赖项目 HEAD 或空 git diff。
- `.local/run-scheduler-review-verification.json` 保存本轮源前后摘要、完整日志摘要、34 类计数对照、保护文件及补丁校验。

原 `run-scheduler-sha256.json` 的23文件清单 SHA256 仍为 `2644637f158394d69826382a999583a4d1bf05303364babb3748d5f47b246e36`；原396日志、原报告及 [REQUEST_CHANGES](review-run-scheduler.md) 保留。此次审查前快照完整，并不补齐675阶段另外五份旧测试缺失的可靠全文；原历史限制仍成立。

engine/gateway target 和源码释放给根任务与原审查者。两实际 JVM、新库迁移/故障接管及部署仍由根任务在审核后执行；本轮未运行 `tests/smoke-run-scheduler-replicas.mjs`。三类分布式许可、其余内存硬闸、总体容量和生产 IAM 验收仍未完成，不因此次修复关闭完整 G5。
