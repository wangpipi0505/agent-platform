# G2 HTTP CALLBACK 实现与验证证据

日期：2026-10-20。原实现阶段本模块 **132/132 通过，0 failures/errors/skipped，05:58:09 +08:00**；后续 `deliveryAccepted` 字段更名及最新验证见文末。本报告是作者实现证据；不据此关闭全部 T11。

## 范围与冻结输入

唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。先只读完成 AGENTS、implementation-brief、contracts/README、callback-plan、async-fix、独立 HTTP 复核报告、冻结技能调用原文及 SkillExecution/SkillAdapters/HttpAsyncAdapter/SecretVault/SkillClients、engine ToolCalls/RunService 权威边界核对；收到 root 完整后端套件释放后开始写入。采用 test-driven-development、java-spring-boot、verification-before-completion。没有读取/复用相邻旧工程，没有改 engine/common/web、原契约正文、原 V308 或原 AsyncHttpExecutionTest；没有启停部署服务、发布或 Git 提交。测试中的临时 HTTP server 是本测试夹具。

冻结详设实测 SHA256：`3C72B2062BEB2E8B377F2384843EA935A0074B50C0063E5106566BBD8DCD0C4F`。原 V308 SHA=`4A2229C9C0DF0BC95C6E782306ABF01FC60A7B7BCB3F9A825BE6B0EE15390DAF`；原 AsyncHttpExecutionTest SHA=`464736B6A2D287A165A211F92BC249389C687E2CA5377FFAC5318DD3B6C17B26`，仍与上一阶段冻结清单一致。回调迁移使用 **V309**。

本阶段精确 12 个业务/测试文件的最终摘要在 `skill-callback-sha256-final.txt`：9 个 Java 主文件、V309、CallbackHttpTest 与 SignedCallbackTest。先前 `skill-callback-sha256.txt` 是最后一次测试格式整理前的中间清单，不作为最终冻结清单。其余写入只在本报告与同前缀日志/摘要文件。

## 协议与信任边界

入口 `POST /internal/skill-jobs/{callId}/callback`，仍走公共 Spring Security 已验 JWT 链；入口另要求真实 SERVICE 角色、`token_use=service`、`aud=agent-platform-internal`。已签用户票据即使含 SERVICE 也被拒绝。tenant/principal 只来自 JWT，先按 PG 原绑定查找，任意其他 SERVICE 主体、仅凭 HMAC、自报 body 身份均不构成权限。

契约配置 `invocationKind=CALLBACK`、`callback.secretRef`；注册时须 ADMIN 且 SecretVault 能证明当前租户可使用引用，只冻结引用。管理回包将该引用替换为 `secretConfigured`。提交沿既有 POST/幂等键和发送栅栏；ACK 只证明 job/key，不能按结果 Schema 当最终响应。可选 reconciliation 仍须 GET 和原 job/key 查询规则；没有查询配置则保持 UNKNOWN，绝不重新提交业务。

请求头 `X-Callback-Timestamp` 为 Unix 秒，`X-Callback-Signature` 为 64 位十六进制 HMAC-SHA256。签名输入是 timestamp 原字符串、换行、**原始请求字节**，时间窗 ±300 秒。流式计数、HMAC 和 SHA256 同步完成，64 MiB 总正文硬限，8 KiB 接收缓冲；验签前只写临时文件，不解析或物化结果。固定 32 字节摘要用 MessageDigest.isEqual 比较。随后复用 AsyncHttpResponse 的严格重复键、尾随 JSON、token/深度/字符串/metadata 限制及 result 临时流；正常与异常退出清理临时文件。

body 必须包含 `eventId/jobId/runId/callId/attemptId/skillVersionId/idempotencyKey/status`。数值归属字段要求 JSON 整数，逐项核对 PG 原绑定；PG metadata 合同摘要与当前精确版本一致。`status` 仅 SUCCEEDED/FAILED。SUCCEEDED 必须提供 result，走既有 SkillAdapters.read → 原始 outputSchema → returnSpec/受控 fileRef、大结果 MinIO 路径，再封装原归属信封。FAILED 必须给出 `errorSemantic` 为 PARAM_ERROR、NO_PERMISSION、QUOTA 或 RETRYABLE_FAILURE；按冻结管道业务失败分支和同步 HTTP Failure 语义生成 raw=null 的失败信封（structured=null、fileRefs=[]），不要求成功 outputSchema 的 result。FAILED 即使携带 result，也只进行整个请求的字节/JSON/结构边界校验，不物化或公开其中数据。RESULT_UNKNOWN、缺失或未知 semantic 均 422 拒收并保持原 UNKNOWN，不能冒充确定失败。

当前授权必须覆盖冻结 guardedArguments：重跑 guard/inputSchema，对守卫属性比较集合覆盖，对其他参数严格比较；缺证据/权限收窄在物化前拒绝，receipt 留 AUTHORIZATION_DENIED，不改变原 UNKNOWN。重复 STORED 回调也先重查当前授权，只返回收件状态，不返回原结果正文。

## 早到、并发与交付

V309 唯一键 `(tenant_id,call_id,event_id)`，保存 raw payload digest、原绑定、首次签名时间/验签时间和处理结论；同 ID 不同正文返回 409，同正文重投允许恢复。只有 `send_started=true` 且非 NOT_SENT 的原绑定可进入；PREPARING/NOT_SENT 发送栅栏未削弱。

**早于 ACK 的保守规则**：已验签、原主体及 call/run/attempt/key/version、当前授权/权威核对通过后，若原 external_job_id 仍空，保存 WAITING_BINDING 元数据与摘要，返回 409 `CALLBACK_BINDING_PENDING`，要求提供方使用相同 eventId 和逐字节相同正文重新签名重投。回调不设置 external_job_id、不保存结果正文、不创建产物。ACK 或独立原 key 查询证明 job 之后，相同 receipt 可继续验收。ACK 永久丢失且没有查询证明时保持 UNKNOWN；这里**没有无重投交付保证**。

处理认领用短 PG 事务：锁当前 fact、清理该调用过期的 PROCESSING 租约、一次只允许一个 receipt 处理者；唯一部分索引进一步仲裁。5 分钟租约结束后，必须重投并重新执行验签/归属/授权/权威校验；旧 owner 不能提交。所有引擎查询、HTTP 和 MinIO 操作都在事务外，没有行锁跨网络。进程崩溃测试用持久 PROCESSING 图像和过期时间重建，未声称执行 OS kill。

验收前、输出处理后都通过 SkillClients 原主体短票据读取 tool-call 与原 `/admin/runs/{runId}`。tool-call 不暴露 run 状态，且 cancel 可保留 SUBMITTED，所以还核对 run 与原 node 的 RUNNING/WAITING_EXTERNAL。原 attempt 已被替换、run/node 取消或终态，receipt 记 LATE，不落新的执行事实。跨服务预检不能消除 TOCTOU，取消/租约竞态最终仍由 engine.fact 的原子条件裁决。

有效结果以同一个短事务更新 execution_fact 和 receipt=STORED，并置 PENDING/立即到期，使用既有 SkillOutbox。receipt STORED 仅代表事实持久化。原132阶段回包字段为 `deliveryState` 和 `logicalAccepted`，后者实际仅反映该投递 DELIVERED；引擎恢复器可能先行采纳，不能由旧投递 LATE 推断全局未采纳。该布尔字段的后续更名见文末补充。收件成功后没有同步业务重发，也不新增另一套 logical committer。

必要交付修复：SkillExecution.deliver 对 accepted/late/error 回写都增加原 `status + result JSON` CAS。旧 UNKNOWN 的在途 ACK 或错误不能把新 SUCCEEDED/PENDING 吞成 DELIVERED/LATE，或污染新事实的 callback_error。既有 execute 完成更新仍要求 status=UNKNOWN，不能覆盖先到的回调终态。

## 实际验证

先从工程 `.env` 读入进程环境、不打印密钥，使用 Java 21 与工程 own m2；所有 Maven 命令仅本 module、**没有 -am**：

```text
mvn -B -ntp -pl skill-registry test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

定向用 `-Dtest=CallbackHttpTest`，组合原回归用 `-Dtest=CallbackHttpTest,AsyncHttpExecutionTest`，最终格式整理后定向用 `-Dtest=CallbackHttpTest,SignedCallbackTest`。

| 原始日志 | 实际结果与证据身份 |
| --- | --- |
| skill-callback-contract-red.log | 1 项、1 failure、0 errors/skipped，05:43:07；注册拒绝 CALLBACK，assertDoesNotThrow 失败。 |
| skill-callback-contract-green.log | 1/1，05:43:31。 |
| skill-callback-http-red.log | 16 项、15 failures、0 errors/skipped，05:44:37；主要在原同步路径把 ACK 判为 FAILED，早到入口尚无实现。并非每条身份断言都有独立红运行。 |
| skill-callback-http-first.log | 16 项、1 failure、0 errors/skipped，05:46:57；屏障下 UNKNOWN ACK 将新终态改为 DELIVERED，精确复现。 |
| skill-callback-http-green.log | Callback16 + 原 Async21 = 37/37，05:47:49。 |
| skill-callback-protocol-red.log | 27 项，1 failure（可选查询允许 DELETE）、1 error（尾随 JSON 的 IOException 未归一）、0 skipped，05:49:08。 |
| skill-callback-protocol-assert-red.log | 上述两条定向改为断言 HTTP 正常返回后重跑，2 项、2 failures、0 errors/skipped，05:49:43；没有隐去先前 error。 |
| skill-callback-protocol-green.log | 27/27，05:50:30。 |
| skill-callback-full.log | 中间版128项，0 failures、3 errors、0 skipped；原 OpenAPI 测试因新增 Set.contains(null) 失败，05:51:50；已恢复缺省 invocationKind 的同步兼容。不是通过日志。 |
| skill-callback-lease-red.log | 1 项、1 failure、0 errors/skipped，05:51:58；独立恢复已终态、过期 receipt 仍 PROCESSING，应 SUPERSEDED。 |
| skill-callback-full-final.log | 失败报文纠正前全模块129/129，0 failures/errors/skipped，BUILD SUCCESS，05:53:32 +08:00。既有98 + CallbackHttpTest28 + SignedCallbackTest3。 |
| skill-callback-formatted-final.log | 失败报文纠正前、测试最后一次格式整理后定向31/31，0 failures/errors/skipped，05:54:51。 |
| skill-callback-failure-red.log | root只读核对指出原失败信封语义后，实际3项、2 failures、0 errors/skipped，05:56:43：无result的合法FAILED、带非成功结构error result的合法FAILED均返回422而非200；RESULT_UNKNOWN/缺失/未知语义反例已通过。 |
| skill-callback-failure-full-final.log | **最终全模块132/132，0 failures/errors/skipped，BUILD SUCCESS，05:58:09 +08:00。既有98 + CallbackHttpTest31 + SignedCallbackTest3。** 包含格式整理后最终源码和FAILED语义修复。 |

原实现阶段 11 个 Java 文件经 google-java-format 1.25.2 `--aosp --dry-run --set-exit-if-changed` 实际验证 exit 0。该阶段完整测试与 `skill-callback-sha256-final.txt` 绑定，冻结后先交独立评审；后续字段更名单独记证，不覆盖该清单或原132日志。

关键覆盖：真实 PG job 绑定、错误 key/job/run/call/attempt/version、同 event 摘要冲突、6 路并发同事件、早到 ACK 屏障、ACK 丢失仅原 key 查询、未发送栅栏、当前 AB→A 拒绝、HTTP 匿名/坏 JWT/用户票据/其他 principal/其他 tenant、坏 HMAC/正文改一字节/±301 秒、重复键/尾随 JSON、有效失败语义（包括无result/非成功Schema的error result）与不确定语义拒收、原始 Schema 与 returnSpec 投影、2 MiB 真实 MinIO 受控引用、取消/旧 attempt、处理租约恢复、receipt PostgreSQL trigger 注入失败导致 fact 同事务回滚、回应忽略后重投、引擎 HTTP503 后 outbox 重投、UNKNOWN 在途 accepted/late/error 三种响应不覆盖新事实。SignedCallbackTest3 是补充边界验证，验证逐字节 UTF-8/换行保留、64 MiB 流读取停止及无效时间在读体前拒绝，不伪称三条均在 primitive 实现前逐一红过。

回调入口使用真实 Spring MVC + 公共 SecurityFilterChain/Nimbus 验签的 MockMvc HTTP 请求，**不是单纯直接设置 Actor 然后跳过过滤链**；外部 submit 与引擎权威通过真实 SkillClients/HTTP 夹具。引擎夹具未运行真实 engine 进程，不冒称验证了真实引擎逻辑提交/取消实现。回调响应丢失场景为忽略已成功返回的响应再重投，不是 TCP 故障注入。外部 ACK 丢失是 HTTP 连接关闭。MinIO 大结果验证受控 fileRef 和 fact，并未逐字节下载全部对象。

新回调 PG 仅用 127.0.0.1:25432 的 `agent_platform_skill_callback_test`，按不存在才 CREATE DATABASE，不清空任何库。全模块既有测试还使用其固定 `agent_platform_skill_async_test`、`agent_platform_v12`、`agent_platform_statistics_test`；没有连接/迁移/清理运行开发库 `agent_platform_dev_v12`。日志保留已有 unchecked 和 Mockito 动态代理提示，不声称零警告。

## 已读开源机制与限制

实际读取本机 Temurin/OpenJDK `jdk-21.0.12.8-hotspot/lib/src.zip` 中 `java.base/java/security/MessageDigest.java` 的 isEqual 实现与源码许可证头（GPL-2.0-only + Classpath exception），以及 `java.base/javax/crypto/Mac.java` 的 update(byte[],offset,len)。本实现用固定 32 字节期望 HMAC 调用 isEqual，并逐块 Mac.update；没有复制上游实现或声称做了时间侧信道实测。PG 短事务/租约/唯一索引使用本工程已有可验证机制；没有把未读的第三方 webhook 实现写成对照证据。

真实外部环境仍缺可信 relay、专用可达回调 URL、供应方共享 secret_ref 和短服务票据换发集成；不开放匿名入口，不把 HMAC 当身份授权。NOT_APPLIED/新代重试授权、外部取消三态、UNKNOWN 人工产品闭环、真实 CALLBACK engine 纵切与供应方验收仍未完成。处理中崩溃可能留下既有 ArtifactStore 登记的对象，沿原 TTL/清理机制回收；这里没有声称证明了所有进程崩溃后的即时对象回收。

## 后续小修：回包仅说明本次投递是否被接受

原132阶段之后，独立评审者已完成34/34定向检查，root随后执行真实 engine+skill 四场景纵切。依据见 `review-skill-callback.md` 与 `signed-callback-live.md`；这些是对应执行者的证据，不是本轮作者重复运行。原文所列“真实 CALLBACK engine 纵切未完成”仅描述原实现阶段，当下应合并 root 后续证据判断；真实供应商/可信relay等外部边界仍保留。

实际纵切出现引擎 ExecutionRecovery/W3 先按同 factId 独立采纳、run SUCCEEDED 且技能节点仅提交一次，旧 outbox 随后 LATE 的合法顺序。原字段 `logicalAccepted=false` 容易被误读为全局未采纳。因此本轮只将新回调 API 布尔字段改名为 **`deliveryAccepted`**，原布尔条件完全不变：对应 receipt=STORED 且当前事实 callback_state=DELIVERED 时为 true，STORED+LATE 时为 false。该字段只反映这份 outbox 投递 ACK，最终逻辑结果须查 engine run 状态/事件；没有新增跨域查询推断，也没有增加冗余 authority 字段。

生产变更只有 SkillCallbacks.response 的字段名和解释注释，测试变更只有 CallbackHttpTest：更新原 LATE 断言、增加 DELIVERED 正例，两者都要求 deliveryAccepted 为实际 boolean、取值正确且不再有 logicalAccepted。不改状态机、事务、权限/签名/Schema门禁、迁移、其他模块、原冻结契约或 root 的 smoke 脚本。本轮未部署或启停服务。

| 本轮日志 | 实际结果 |
| --- | --- |
| skill-callback-delivery-field-red.log | 两条定向红测：2项、2 failures、0 errors/skipped，06:08:21 +08:00；LATE与DELIVERED回包均缺 deliveryAccepted。 |
| skill-callback-delivery-field-full.log | **最终全模块133/133，0 failures/errors/skipped，BUILD SUCCESS，06:09:46 +08:00。** 既有98 + CallbackHttpTest32 + SignedCallbackTest3。 |

仍使用上文 Java21/own m2/V tmp 环境和单 skill module、无 -am 的命令；定向选择 `CallbackHttpTest#receiptReportsDeliveryAcceptanceWithoutClaimingGlobalLogicalAcceptance+deliveredReceiptReportsAcceptedDeliveryWithoutGlobalLogicalAcceptanceField`。两个变更 Java 文件格式化后 dry-run exit0，完整133套件使用格式化后的源文件。

本轮最终12文件摘要独立保存为 **`skill-callback-delivery-field-sha256.txt`**，实测12/12匹配；对比原 `skill-callback-sha256-final.txt` 仅 SkillCallbacks.java 与 CallbackHttpTest.java 两项改变，其余10项不变。保留原132阶段所有日志和SHA清单，不将原评审结论冒充本轮已定向复核。
