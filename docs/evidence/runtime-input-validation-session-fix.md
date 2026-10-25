# 运行输入校验评审 P1：会话预约阻塞修复附录

本附录对应 `review-runtime-input-validation.md` 的 REQUEST_CHANGES。原评审、`runtime-input-validation.md`、原冻结清单及其日志均保留，不能将原 27 项通过记录当作已经覆盖真实 ChatSessions.turn 路径。

本轮仅改 `ChatSessions.java` 和原作者测试 `RunInputValidationPostgresTest.java`，不修改已冻结的 RunService、FlowDriver、InputDeclarations、ChatHistory、迁移或契约；不启停、部署、提交代码，不访问相邻旧工程。

## 根因与最小修复

原 ChatSessions.turn 先在短事务插入 chat_turn 并递增 next_turn，事务提交后才调用 RunService.createSessionTurn。新共有输入 gate 返回400时没有 run，但已经留下 run_public_id=null 预约：相同 requestId 修正输入会409 REQUEST_CONFLICT，更换 requestId 则409 CHAT_TURN_ACTIVE。

现在在预约事务内锁定本人会话后，先保留原 existing requestId 幂等返回及冲突判断，再保留原 active-turn 判断，之后用会话固定 snapshot 和本次 inputs 执行同一个 InputDeclarations.validate，最后才允许 insert 和 next_turn+1。失败使事务回滚，不消费轮次、不创建空预约；不采用事后释放预约的新协议。schema helper 只允许本地引用、阻断外部加载，事务内无跨服务 HTTP。

current app 的可用性和历史权限检查仍在事务外，仅针对新 requestId；验证使用固定 session snapshot，不能拿当前发布声明替换会话固定版本。已经接纳且绑定 run 的同 requestId 仍优先回读，不因当前应用变更或停用重复执行；同 ID 不同输入仍冲突，新 ID 仍受当前应用停用约束。RunService 的第二层共有 gate 与 START 恢复 gate 保留。

本轮只阻止输入声明拒绝产生新的空预约，不改写或回收其他既有预约。非法 Schema 若已固定于 session，单纯改 inputs 不能修复 Schema；测试验证它反复返回真实400且不消费轮次，不伪造更换 requestId 就能修好固定声明。

## 新增真实会话测试

在专用 PG25432 / `agent_platform_engine_test` 中执行真实 ChatSessions.create/turn、TraceStore/JDBC 和 RunService；跨服务仅为明确计数的 RuntimeClients 协议 fixture，不代表真实供应商或 IAM。新增八项，总作者类由27项增加为35项：

- 必填缺失、错误类型、minLength 三类错误，各自覆盖纠正后复用原 requestId 与改用新 requestId，共六项。拒绝时 chat_turn=0、next_turn=1，run/node/attempt/model_invocation/tool_call 均无新增，冻结/POST/模型调用均为0；纠正后首轮正常 SUCCEEDED、turn=1、next_turn=2，模型只调用一次。
- 固定 Schema 非法：连续两个 requestId 均返回400 INVALID_INPUT_DECLARATION，且保持无预约、轮次不递增、无 run 和付费发送。
- 当前发布声明改变后仍按固定会话声明执行；已有请求在当前应用停用后回读原 run 且不增加模型调用或元数据读取；同 ID 改内容409，新 ID 受停用阻断，轮次不递增。

每个用例等待已创建 run 终态；红阶段暴露的预约按本测试创建的 sessionId 清理，不删除其他会话或开发运行库数据。原四项 ChatSessionsTest 一起运行，保留先前历史权限、幂等及停用语义检查。

## 实际验证

Java21.0.12、Maven3.9.16、本工程 `.tools/m2`；`.env` 只读入进程且不打印。显式 `ENGINE_PG_TEST=true` 与 `AGENT_DB_URL=jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test`。使用 `mvn.cmd -o -B -ntp -pl engine-runtime test`，附本工程 `-Dmaven.repo.local=.../.tools/m2`、`-Djdk.net.unixdomain.tmpdir=V:/.local/tmp`，不加-am。

| 日志 | 结果 | 完成时间（+08:00） |
| --- | --- | --- |
| `runtime-input-validation-session-red.log` | `-Dtest=RunInputValidationPostgresTest`：35 项，7 failures，0 errors/skipped；六类纠正场景和非法 Schema 均留下1条 null-run 预约，正确红 | 06:32:32 |
| `runtime-input-validation-session-green.log` | `-Dtest=RunInputValidationPostgresTest,ChatSessionsTest`：35+4=39/0/0/0，成功 | 06:33:32 |
| `runtime-input-validation-session-full.log` | 最终冻结源码 engine 全模块365/0/0/0，成功 | 06:35:33 |
| `runtime-input-validation-session-format.log` | google-java-format1.25.2 对两个变动 Java 文件执行 AOSP dry-run，exit0 | 本轮 |
| `runtime-input-validation-session-contracts.log` | v1.2快照、四契约原文摘要、七模块依赖边界通过，exit0 | 本轮 |

本轮39项定向及365项完整 engine 回归、AOSP 和契约检查均通过。原始日志同时保存在 `.local` 及本附录同目录，既有 JDK/Mockito/Lettuce 警告保留。本附录不提前替代独立评审结论或真实服务部署后的 API 复验；原 REQUEST_CHANGES 历史保留，待同评审者复核闭环。

## 摘要边界

本轮新清单为 `runtime-input-validation-session-fix-verification.json`。RunInputValidationPostgresTest 原已审历史 SHA 为 `2fe0285d6f848209327f9c5edeea8ac0fae2a3fc44de3adf5a05e304d78579ab`，新测试 SHA 由新清单记录；原清单不回写。

三个只读依赖保持原 SHA：RunService=`93b64f4dbd6a78e2b9a65a788a5c6ab6c5911fae73455142aaa28f913c39d24e`，FlowDriver=`a0e65396eb52818a091b33cb1606053b0ddc4969381cace0b2b0f9e8b407c563`，InputDeclarations=`d7e3ea02fc12fb6574513fabc759fd978c2c42b4c3c340e9bfb46207644b5e89`。所有四契约原文和 v1.2 快照不变。
