# 运行入口输入校验与 START 独立评审

2026-10-20，独立评审者 review_test_inputs。**结论：REQUEST_CHANGES，1 项 P1；暂不放行 engine 部署。** 已亲自复跑27项作者定向测试全部通过，但另一个真实会话路径探针复现了测试遗漏的回归。

## 冻结范围

已阅读本工程 AGENTS、implementation-brief、contracts/README、runtime-input-validation.md 及冻结清单。重算清单内15个文件与1个只读依赖，**16/16 SHA256 全部匹配**：

- RunService：`93b64f4dbd6a78e2b9a65a788a5c6ab6c5911fae73455142aaa28f913c39d24e`。
- FlowDriver：`a0e65396eb52818a091b33cb1606053b0ddc4969381cace0b2b0f9e8b407c563`。
- RunInputValidationPostgresTest：`2fe0285d6f848209327f9c5edeea8ac0fae2a3fc44de3adf5a05e304d78579ab`。
- 已审 helper InputDeclarations 保持 `d7e3ea02fc12fb6574513fabc759fd978c2c42b4c3c340e9bfb46207644b5e89`。

未修改业务源码、契约或原证据，未读取相邻旧工程，未启停或部署服务。未扩大至施工调度／proof。

## P1：新增前置拒绝使会话轮次预约永久占位，合法后续消息无法提交

位置：新增 `RunService.java:200` 校验与现有 `ChatSessions.java:77–147` 的事务／调用顺序交互。`ChatSessions.turn` 在事务内先插入 `chat_turn`、递增 `next_turn`，事务提交后第147行才调用 `createSessionTurn`。新增校验对缺必填、错误类型等返回400时，没有 run 被创建，却留下 `run_public_id=null` 的轮次预约。

此时原 requestId 的输入已经冻结：用户修正输入并重用 requestId 会返回409 REQUEST_CONFLICT；换 requestId 又因最新预约没有 run/status 而返回409 CHAT_TURN_ACTIVE。按原请求重试只会再次400。一次正常的输入错误因此使该会话无法继续，需新建会话才能绕过。

新增测试 `trustedSessionSnapshotUsesTheSameInputGateBeforeModelFreeze` 直接调用 RunService.createSessionTurn，跳过了真实 ChatSessions.turn 的预约事务，故27项绿灯不能覆盖该回归。

建议在新轮次预约持久化前，对会话固定 snapshot 与本次 inputs 完成相同校验，同时保留已接纳／已绑定 run 的幂等读取优先；或设计可证明安全的拒绝预约释放机制，不误释放已受理工作。补充真实 ChatSessions.turn 路径：无效输入400后，无空预约阻塞、修正后可以正常提交、已接纳 requestId 重放不重新执行、同ID改内容仍409。

### 独立真实 PG 复现

使用独立 Java21 JShell 进程，classpath 为 engine target/classes 与 test-input-classpath；连接**专用测试库 `agent_platform_engine_test`、PG25432**。构造唯一 `review-input-<UUID>` 租户和固定 CHAT snapshot，声明 `message: STRING, required=true, minLength=2`；RuntimeClients 仅返回明确 fixture snapshot，任何 POST 均抛 AssertionError。依次调用真实 `ChatSessions.create/turn` 与真实 TraceStore/JDBC：

```text
INVALID_FIRST=400 INPUT_SCHEMA_CHANGED
RESERVATIONS=[{turn_no=1, request_id=invalid-first, run_public_id=null}]
RUN_COUNT=0
CORRECT_SAME_ID=409 REQUEST_CONFLICT
CORRECT_NEW_ID=409 CHAT_TURN_ACTIVE
CLEAN_TURNS=1
CLEAN_SESSION=1
```

探针未调用模型，未发生 POST。结束时仅按该 sessionId 与租户清理自己的1条预约和1条会话；未触及开发运行库或其他租户数据。已立即向 root 反馈部署阻断。

## 其余范围复核

- DEBUG、正式 TASK→CHAT、EVALUATION、受信 session、重执行、受控复现、受控技能测试最终均调用共有 createInternal。普通控制器路径与 ChatSessions 调用链已实际阅读。
- 共有入口先按 tenant/principal/requestId 查旧 run，并执行现有 verifyReplay 冲突判断；已接纳请求在读取新草稿与新校验之前返回。未发现此次改动新增越权返回或重复执行。
- 新校验在 prepareSnapshot 与受控复现守卫之后、freezeModelRefs 及 engine.run/node/attempt 插入之前。prepareSnapshot 存在权限／技能／提示词元数据 GET，但没有付费派发或本地业务写入；不能把此顺序宣称为所有网络请求之前。
- 新普通请求拒绝保持400，RunService层没有新增 run、node_instance、attempt、model_invocation、tool_call；此保证不应扩展成“上游会话预约也无 PG 写入”，正是本次P1缺口。
- START 在持久恢复执行时仍使用完整 snapshot 校验，PlatformException 转换成 RuntimeFault(PARAM_ERROR, inputs, reason)，由既有失败链处理。没有移除恢复防线或新增隐式重试。
- required 判断以字段存在为准；显式 NULL 在 NULL 声明下合法，缺少 NULL 字段与 STRING=null 仍拒绝。false、0、空字符串／数组／对象按其声明保留，不注入默认值，不强制转换数值／容器类型。
- 原 helper 的网络／文件引用阻断仍存在；未修改评分、模型冻结、旧请求冲突和候选契约。未发现此范围内其他确定的P0/P1/P2。

## 亲自执行的验证

载入本工程 `.env` 至进程，不打印秘密；Java21.0.12；`ENGINE_PG_TEST=true`；显式 `AGENT_DB_URL=jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test`。命令：

```text
mvn -o -B -ntp -pl engine-runtime
  -Dmaven.repo.local=<本工程绝对路径>/.tools/m2
  -Djava.io.tmpdir=V:/.local/tmp
  "-DargLine=-Djava.io.tmpdir=V:/.local/tmp -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"
  -Dtest=RunInputValidationPostgresTest test
```

06:29:28 +08:00完成，**27 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**。无-am，未占其他模块；随后已通知 root 释放 engine target。此轮覆盖作者的入口拒绝、typed值、START恢复、旧requestId和有效冻结重执行／复现；上述独立会话探针属于额外失败证据。

作者357项全模块与最终27项补强日志已核读摘要，不冒称由本评审执行。root部署前真实API红证据的五次错误200、五条run和五次fixture模型调用已阅读；部署后的真实API复验尚待P1修复及独立闭环，不在本报告中预先宣称通过。

## 附录：会话预约 P1 修复独立闭环（06:38 +08:00）

**更新结论：APPROVE，本次运行输入校验增量的上述唯一 P1 已关闭；未发现遗留 P0/P1/P2，可进入 root 部署及真实 API 复验。** 前文 REQUEST_CHANGES 和原27项测试记录保留为修复前历史，不改写原冻结清单或证据。

复核新输入：`runtime-input-validation-session-fix.md`、`runtime-input-validation-session-fix-verification.json`。亲自重算8个文件与3个只读依赖，**11/11 SHA256 匹配**：

- ChatSessions.java：`3cd96db53994eb07a0a3f0d34d0a03c5b57e82f2d9a7e846940ac3cda28f12ea`。
- RunInputValidationPostgresTest.java：`67fdaf99e18eacc483a158ce2532cc6259d8de7b5912ca3ec61c642b49a00ff4`。
- RunService、FlowDriver、InputDeclarations 保持前文已审摘要。

代码复核：在本人会话锁内先处理 existing requestId 的幂等与冲突、再处理已有活动轮次，随后对**会话固定 snapshot**进行本地输入声明校验，最后才插入 chat_turn 和递增 next_turn。声明错误抛出后预约事务回滚，不需要事后删除工作。helper 的外部引用加载阻断仍在，此处未引入事务内HTTP。当前应用可用性／历史权限检查仍在事务外针对新requestId执行；已接纳工作读取、同ID改内容冲突和新请求受停用约束的顺序未被放松。

### 独立真实 PG 探针

作者明确释放engine target及测试库后，再使用独立Java21 JShell、真实ChatSessions/RunService/TraceStore/JDBC和专用 `agent_platform_engine_test` 复测。新建唯一 `review-session-fix-<UUID>` 租户；固定CHAT声明和合法冻结模型引用，跨服务使用显式计数fixture，不调用真实模型供应商。

独立输出中逐组观察到以下5组断言通过：缺必填→修正同ID、缺必填→修正新ID、错误类型→修正同ID、错误类型→修正新ID、minLength失败→修正同ID。每组均满足：

```text
无效输入：400 INPUT_SCHEMA_CHANGED
拒绝后：chat_turn=0，next_turn=1，模型/POST计数无增加
修正后：SUCCEEDED，chat_turn=1，next_turn=2，模型调用仅1次
同请求重放：原runId，不新增模型调用
```

探针只按自己的唯一租户定点清理；随后独立SQL核查 `review-session-fix-%` 的 run 与session均为0。没有清理开发运行库或其他租户数据。完整六种纠正组合及额外语义则由下列本人重跑测试覆盖。

### 本人重跑39项回归

保持前文Java21、离线本工程缓存、PG25432独立engine测试库环境，`ENGINE_PG_TEST=true`。作者释放后独占engine target，执行同一基础命令，将选择改为：

```text
-Dtest=RunInputValidationPostgresTest,ChatSessionsTest
```

**06:37:52 +08:00完成：35+4=39 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS。** Surefire文本报告已当场复核；随后通知root释放engine target及测试库。

新增实际session路径覆盖缺失／类型／minLength各自纠正原ID和新ID的六种组合、非法固定Schema不消费轮次、当前发布声明变化仍使用session固定声明、已接纳请求在停用后读原run、同ID改内容409、新ID受停用阻断。原共有入口／START恢复的27项和原4项会话回归一并通过。

修复作者365项完整engine回归及格式／契约检查属于作者证据；本评审亲自执行的是上述PG探针与39项定向回归，不将365项冒记为本人运行。此闭环不预先宣称部署后真实网关API或全部项目验收通过。
