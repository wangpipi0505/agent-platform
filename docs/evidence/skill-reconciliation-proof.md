# G2 技能侧不可变对账证明

2026-10-20。仅在独立新工程实施；依据冻结详设册20 F6、册31 F7/W1–W3及本目录小计划。原设计/契约、引擎逻辑状态机、前端均未修改，不启停开发服务，不生成引擎重试许可，不重新提交外部业务。本报告区分新增证明与相邻旧 APPLIED 代次修复；均须独立评审后才由根任务决定部署。

## 新增接口和持久证据

- `V310__reconciliation_proof.sql` 新增 `skill.reconciliation_proof`，每个 tenant/call/attempt 至多一行。PG UPDATE/DELETE 触发器禁止改写或删除；保留历史代证据，不随当前事实换代覆盖。
- `POST /internal/skill-facts/{callId}/reconcile` 仍仅查询原任务或本地发送栅栏。可靠 NOT_SENT 或明确 NOT_APPLIED 可额外返回 `proofRef`、`proofSha256`，业务状态仍 UNKNOWN，`retryBusinessAllowed=false`。没有提交新业务、创建新代或采纳引擎逻辑结果的分支。
- `GET /internal/skill-facts/{callId}/reconciliation-proofs/{attemptId}` 只读原代证明。真实安全过滤链验证 internal audience/service token；应用层再要求确切 SERVICE 角色，不以 ADMIN 代替。查询始终限定验签 Actor 的 tenant/principal，跨主体/租户返回404。历史证明仍返回 `retryBusinessAllowed=false`。
- 证明正文含 PG `proofId`/`proofRef`/`verifiedAt`、tenant/principal/run/node/call/attempt、精确技能版本/合同 SHA、原始和守卫参数摘要、原幂等键、原 UNKNOWN 状态、发送状态、NOT_SENT/NOT_APPLIED 结论、来源种类及来源摘要。外部原文、业务结果、authRef/secretRef、授权头不写入证明正文。
- `proofSha256 = SHA256(Json.canonical(证明正文))`。GET 响应另附 proofSha256 和 retryBusinessAllowed；消费方重算时去掉这两个附加字段。sourceSha256 是本次实际查询响应的规范 JSON 元数据摘要（异步不包含被单独转存的 result 原文），不是宣称原始 HTTP 字节签名。queryEndpointSha256 仅保存实际来源 URI 摘要，避免暴露 URI 内容。

普通 HTTP 查询验证原 key，异步查询额外核对原 job/key，并在原 ACK 丢失时仅凭原 key 查询来建立可信 job 绑定。只有显式配置且与 doneValue 不同的非 null `notAppliedValue` 可形成未生效证明；404/5xx、超时、未配置、错 key/job、含糊状态均不形成证明。业务 httpMapping/returnSpec 不参与查询协议解释，避免改变 donePath 所描述的实际元数据。

本地 NOT_SENT 仅由同代 REGISTERED/send_started=false 的条件更新形成，或读取已被栅栏阻止的同代状态；任意其他 send_started=false 状态不构成未发送证明。原执行线程迟到也不能越过 NOT_SENT 发送。CAS 同时限定原 attempt/version/run/key/request_snapshot，不允许旧对账器栅栏新代绑定。

## 事务和竞态边界

查询前重验当前守卫覆盖原始 guardedArguments；普通 HTTP 同样执行这一检查。外部 HTTP 和 engine 当前运行/节点读取均在 PG 事务之外。NOT_SENT 栅栏前及证明生成前检查 engine 的原 call 身份与 UNKNOWN、运行/节点活动状态。

证明写入时按 execution_fact→external_job_binding 顺序在短事务取锁，重验原 UNKNOWN、非 Mock、同代、版本/合同、原 request_snapshot、参数摘要与原绑定；回调已写终态或代次/快照已变时409拒绝。完整 engine/run 与本地 PG 不能成为一个跨服务事务；证明只是核实时刻的外部/本地历史事实，后续任何重试授权仍必须由引擎重新核实 run/node/调用代次/审批并做自身原子 CAS。

相同响应并发只保存一个证明并返回同一引用；不同来源元数据/结论冲突409，原证明保留。ACK 丢失后的原 key lookup 可建立 job，后续按 job 查询仍可返回最初证明；实际 URI 摘要保留首次来源，不据受同一不可变合同控制的合法 lookup→job URI 切换误判业务事实冲突。key/job/外部状态/来源元数据摘要仍必须一致。

`reconciliation_note` 记录的是查询观察和独立耗时，先记外部观察不等于已生成 proofRef；最后本地事务失败时不会发布可消费证明。新证明不会将 NOT_APPLIED/NOT_SENT 写成业务 FAILED/SUCCEEDED，不改变原执行耗时统计，也不创建新的逻辑调用。

## 相邻旧 APPLIED 代次修复

实际读取旧代码确认：reconcile 的 APPLIED UPDATE 原只按 tenant/call/status=UNKNOWN 更新；旧查询返回时可覆盖新 attempt 的 UNKNOWN。即使回调已先写终态，原代码也在 UPDATE 0 行后无条件读取并再次投递当前事实。HttpAsyncAdapter 将 binding 置 DONE 同样缺少代次约束。

真实 HTTP latch 挂起原查询，然后由第二条 PG 连接推进新代/已核实回调终态，再释放旧 APPLIED。红测四项中三项失败：新代被写入旧结果、既有终态被旧查询再次交付、新 binding 被置 DONE；正常同代用例原即通过。最小修复后四项均通过。

最终 fact UPDATE 限原 UNKNOWN、tenant/principal/run/call、attempt/version/args/contract 及完整原 request_snapshot。异步还要求原绑定代次、key/快照和已确认 DONE 一致。0行返回409 `RECONCILE_FACT_STALE`，不交付旧信封，不改变当前代。适配器原代绑定仲裁及 ACK/job lookup/DONE 更新均加原代/快照条件；不同代响应保持 UNKNOWN，不写新 binding。相邻已读 SkillCallbacks.store 与 execution_fact 短事务的 owner/attempt/UNKNOWN 裁决用于本工程机制对照，没有另造逻辑提交者。

## 红绿和验证记录

日志均在新工程 `.local/`，未打印本地环境密钥。测试数据库为独立 `agent_platform_skill_proof_test`；原模块回归还使用本工程已有的专用测试库，不连接开发运行库 `agent_platform_dev_v12`。供应方和 engine 是测试目录内的实际本机 HTTP 服务；PG、安全过滤链和 Nimbus 验签是真实组件。

| 记录 | 实际结果 |
| --- | --- |
| skill-reconciliation-proof-red.log | 初始夹具缺 description，3 errors；不计成功红测。 |
| skill-reconciliation-proof-red-assert.log | 3 tests / 3 assertion failures：异步未识别 NOT_APPLIED，普通 HTTP/NOT_SENT 缺证明引用。 |
| skill-reconciliation-proof-first-green.log | 3/3，首次基础绿测。 |
| skill-reconciliation-proof-safety.log | 16/16，权限、归属、普通状态、不可变、历史读取等。 |
| skill-reconciliation-proof-fence-red.log → fence-green.log | 原代误栅栏新 binding 的1条断言失败 →17/17。 |
| skill-reconciliation-proof-concurrency.log | 22/22，真实验签 API 与相同/冲突响应并发。 |
| skill-reconciliation-proof-full-first.log | 155 tests / 3 errors：两项 NOT_SENT 旧夹具缺完整新 authority/run 证据，一项合法 scope 重排被过严逐字快照比较拒绝。不是绿测。补齐夹具证据、按守卫属性集合双向比较绑定 scope，未削弱原断言。 |
| skill-reconciliation-proof-fixture-green.log | AsyncHttpExecutionTest21 + Proof23 =44/44。 |
| skill-reconciliation-proof-lost-ack-red.log / red-assert.log | ACK丢失后切换查询路径导致重复证明冲突，先1error、再明确1failure。 |
| skill-reconciliation-proof-lost-ack-green.log | 第一次修复仍1failure；不是成功。 |
| skill-reconciliation-proof-lost-ack-final-green.log | 1/1，原 key/job 下重复证明稳定、submit=1。 |
| skill-reconciliation-proof-full-final.log | 扩围 APPLIED 修复前阶段157/157，06:33:27；不是最终源码验收。 |
| skill-reconciliation-applied-cas-red.log → green.log | 4 tests / 3 assertion failures →4/4；正常同代 APPLIED 保留。 |
| skill-reconciliation-proof-projection-red.log | 普通 HTTP root RUNNING 被业务 returnSpec 投影成 NOT_APPLIED，1条明确断言失败。 |

最终命令：在新工程根、进程内加载本工程 `.env`，以 JDK21 执行 `mvn.cmd -B -ntp -pl skill-registry test -Dmaven.repo.local=V:/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`（没有 -am）。实际最终日志 `skill-reconciliation-proof-applied-full-final.log`：**162/162，0 failures/errors/skipped，BUILD SUCCESS，2026-10-20 06:37:45 +08:00**。其中新增 ReconciliationProofTest29，既有 AsyncHttpExecutionTest21、CallbackHttpTest32、SignedCallbackTest3 及全部旧模块回归均通过。projection 红测已纳入最终29项并通过，不能据162推称整工程全部模块或G2均完成。

最终源码 AOSP dry-run 六个Java文件，退出0、无待格式化文件；首轮单次 replace 后干跑曾发现3文件仍需整理，随后再格式化并最终重新干跑，不隐去中间非0。`node scripts/verify-contracts.mjs` 通过，冻结详设 SHA 仍为 `3C72B2062BEB2E8B377F2384843EA935A0074B50C0063E5106566BBD8DCD0C4F`。源 SHA 清单 `skill-reconciliation-proof-sha256.txt` 共7文件；新增证明文件与旧APPLIED修复分属同一清单，主要落点分别为 ReconciliationProofs.java 和 SkillExecution/HttpAsyncAdapter，相关独立测试分别记录于本报告红绿表。工程尚无HEAD，本轮按实际源清单冻结，不伪称某次Git diff/提交已审核。

## 实际开源对照

实际读取固定 [Spring Framework v6.2.7 TransactionTemplate.java](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.7/spring-tx/src/main/java/org/springframework/transaction/support/TransactionTemplate.java) 的 execute/rollbackOnException，及 [JdbcTemplate.java](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.7/spring-jdbc/src/main/java/org/springframework/jdbc/core/JdbcTemplate.java) 的 PreparedStatement execute/query 与 DataSourceUtils 连接获取/释放。两文件头部 Apache-2.0 许可已读。对照点是事务回调异常回滚和 JDBC 参与事务；本增量直接使用框架，不复制上游实现，不将普通 HTTP 放入短事务。

## 保留工作

- 该增量不是 G2/T11 完成。引擎单次许可、审批再验证、历史归档、新代计量/合法重试、UNKNOWN 人工操作入口、取消三态均未启用。
- 引擎未来启用新代前，必须为 adapter 调用增加原 expected attempt/引擎许可的显式绑定。现有 invoke 参数只有 call/key/run，当前没有重置 NOT_SENT binding 或新代发送许可，不能把本增量视为未来旧线程迟到后跨新代发送已解决。
- 首次来源中保存的是明确响应观察，不替外部供应方承诺永久不会生效；供应方原 key/job 查询和幂等协议需真实联调。无生产 IAM、真实供应商/MCP/K8s或容量验收宣称。
- APPLIED 结果验收先于最终 CAS；若此时发生取消/换代，已创建但未交付的 artifact 仍按既有登记/TTL清理边界处理，没有新增即时回收保证。
- 不提供证明修改/删除入口；全量生命周期保留/清理策略另属后续运维设计，不以此触发器当作已完成留存策略。

## P2 返修附录：证明必须使用内部用途票据

独立评审对原162版提出 REQUEST_CHANGES 并实际复现：已验签 portal JWT 的 roles 若含 SERVICE，公共 `/internal/**` 角色过滤会放行；原证明读服务也只查角色，于是同 owner 的 `token_use=user/aud=agent-platform` 可以读取证明。原162绿测的 portal 负例未带 SERVICE，不能证明严格票据边界。`.local/review-proof-token-probe-assert.log` 记录实际 Nimbus/MockMvc/PG 探针 expected403/actual200；此前探针的 unixdomain 临时目录环境失败不算该问题红测。以上是评审者的独立探针证据，本作者没有将其称为自己运行。

本作者新增四条实际 HTTP 失败先行用例，`.local/skill-reconciliation-proof-ticket-red.log`：**4 tests / 4 assertion failures / 0 errors**，均 expected403/actual200，06:43:27。分别覆盖 portal+SERVICE 读取、同时包含 portal/internal audience 但 purpose=user 读取、portal+SERVICE 生成 NOT_APPLIED 证明、生成 NOT_SENT 证明/栅栏。

最小修复新增 `ReconciliationProofs.requireProofTicket`，与已实际读取的 SkillCallbackController 边界一致：读取 SecurityContext 中已验签 Jwt，必须同时满足 `token_use=service`、aud 包含 `agent-platform-internal`、原 Actor 具有确切 SERVICE 角色。get/store 必经这一检查；proofAuthority 在 NOT_SENT 栅栏之前及 NOT_APPLIED 最终证明之前也检查，普通用户不能产生 proof 或发送栅栏。

没有修改公共安全过滤链或全平台授权策略。reconcile 的既有普通 SERVICE 角色入口保留，非 proof APPLIED 分支不私加本轮票据策略；新增一条明确限定范围的兼容用例予以验证，不将这一旧行为当作生产 IAM 矩阵已验收。直接服务测试夹具改为具有合法 internal audience/service purpose 的 JwtAuthenticationToken，避免用缺省用途夹具制造生产绕过结论。

定向 `.local/skill-reconciliation-proof-ticket-targeted.log`：**Proof34 + Async21 =55/55，0 failures/errors/skipped，06:44:53**。原29项仍在内；新增5项包括四条修复验证及上述范围兼容用例。混合 audience/user purpose 返回403；仅 internal audience+user purpose、仅 portal audience+service purpose 先被真实 Nimbus 过滤链拒绝401。原内部同 owner 成功、跨 owner/tenant404及坏签名401继续验证。NOT_APPLIED 被拒后 fact 保持 UNKNOWN、proof=0、submit=1；NOT_SENT 被拒后 binding 仍 REGISTERED、proof=0、submit/query均0。

原162版报告、原 `skill-reconciliation-proof-sha256.txt`、原日志及评审发现均保留为历史证据，不改写为通过。返修的新清单另存 `skill-reconciliation-proof-ticket-sha256.txt`。

返修最终命令与上文相同（JDK21、`-pl skill-registry`、无 -am、独立缓存），`.local/skill-reconciliation-proof-ticket-full-final.log` 实际 **167/167，0 failures/errors/skipped，BUILD SUCCESS，2026-10-20 06:46:41 +08:00**。Proof34、原 Async21/Callback32/Signed3 和全部既有技能回归均通过。六个Java文件 AOSP 最终干跑退出0，新7文件清单逐项SHA匹配，verify-contracts再次通过；仅4文件较原162清单改变：ReconciliationProofs、SkillExecution 的证明检查入口、ProofTest、AsyncHTTP直接身份夹具。原V310、Controller、HttpAsyncAdapter保持原SHA。未启停开发服务、未部署、未启用引擎重试。此处只记录返修验证完成，P2最终关闭须由原独立评审者复核。
