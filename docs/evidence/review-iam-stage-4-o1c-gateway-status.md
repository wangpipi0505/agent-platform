# Spec Compliance

- **❌ Issues found：一项 Important。** 固定 STATUS 的 Q→M、私有 proof、原始 UTF-8 完整响应、有限装配和 after-getOutputStream 终检符合冻结需求；但观察 HTTP 在响应状态/响应头阶段拒绝时，会先进入 Apache graceful response close，再到外层 request.cancel，遗漏取消先于排空的边界。`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java:247`、`:264`、`:294`，详见 I1。
- **✅ 范围符合。** full-context package 只有授权的八份生产文件和四份新增测试/fixture，六新增、六修改，无删除或前端改动。该评审基线是 accepted O1b focused fix source579；候选 source585/backend393/frontend192、target2005/delta64 的物理清单与逻辑回放由 root 有限审计负责，不用代码 diff 代替执行证据。
- **⚠️ 执行事实的界限。** 31 个新方法和三个旧回归是不同命令的 latest passing results，不是一套在最终所有源码上重跑的 34 方法 suite。逐命令 pre-source、实际 Running XML、PG 原行保持、资源退出和 replay 身份不由本次源码阅读独立证明；root 的有限审计及独立 executed-source correction 承担这些核对。本评审没有重跑已通过测试，未把不重跑列为缺陷。

# Strengths

- `admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java:221`：STATUS capture 私构造，claimed 后独立检查同 attribute、anchor、线程、路径、dispatcher、生命周期与 source 的 wall/monotonic 期限；旧构造默认 status-disabled。实际 bean 是否存在控制有限安装，见 `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamAssembly.java:32`。
- `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:177`、`:199`、`:230`：固定 Q/M exchange，私有 typed proof，检查 original source digest/origin/subject、不同 grant/operation，分离 Portal 与内部期限并锁存失败；内部 ticket JwtException 映射503，不错误调用 gateway-target authorize。`GatewayStatusAuthorizationTest.java:16` 的真实受控 authority assertions 核两次 exchange 及独立身份。
- `platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunObservationMetadataClient.java:30`、`:39`：Q 只走固定内部路径，按既有 selector 生成 M 资源，严格核七字段、owner/run/root/版本/hash；wire8192 与序列化 data4096 分开。`RunStatusProxy.java:22`：全量严格 UTF-8/duplicate/trailing/runId 校验后保留原字节；实际 output stream 取得后再次检查 capture/M/source/总 deadline，再首次 write。
- `platform-gateway/src/test/java/cn/agentplatform/common/authority/GatewayRunStatusChainPostgresTest.java:105`、`:127`、`:178`：实际 gateway→独立 engine JVM→PG，原始响应字节相等、大整数/decimal/null/中文转义、Q/M 调用序列、同只读 RR、无执行副作用及真实 stream 窗口均有直接 assertions。新增 engine fixture 明确是验证过 DAG 的生产 storage seam，未冒充完整公开 admission。
- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java:276`：已识别并修复读取中超限/失效先 cancel 再关 chunk stream 的问题，且 `RunObservationGetTransportTest.java:76` 有 stalled chunk 定向 oracle。I1 是相同取消原则在更早终止路径上的遗漏，不否定该已覆盖分支。

# Issues

## Critical — 0

- 无 Critical 发现。

## Important — 1

### I1：提前拒绝响应时，graceful close 仍可排空未受字节上界保护的 body

- **位置：** `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java:247`（非200且非403/404）、`:255`（unsafe header）、`:257`（Content-Type）、`:264`（entity 缺失/已声明长度超限），以及 `:294` 的外层取消；相关内层正确处理在 `:276`。
- **触发与影响：** 如 status 的200响应声明 `Content-Length > 2097152`，或 Q 响应声明长度大于8192，代码在读取循环前抛 unavailable。相同早退也发生于401/未知状态、被禁响应头及错误 Content-Type。这些抛出不经过 `:276` 的 cancel-before-close catch。Apache 的 `execute(..., responseHandler)` 会先关闭它拥有的 response，之后才从调用返回到当前方法 `finally`。该正常关闭会关闭实体并排空剩余流。于是已拒绝响应仍可被继续读取；对持续发送的 peer，读取没有应用字节上界，对停滞 peer 则可能等待到两秒 hop timer。timer 限制时间，不能替代 Q/M 实际读取字节上界。这违反冻结 brief 的 bounded observation transport/实际 wire-byte 约束。
- **窄依据：** 已封存 `docs/evidence/iam-stage-5-n4b2-gateway-published-chain/upstream/CloseableHttpClient.java:245` 的 try-with-response 包住 handler，运行时异常也须经过 response.close；官方固定版本 [CloseableHttpResponse 5.4.4](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.4.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/CloseableHttpResponse.java) 第191–215行将无参 close 送到 GRACEFUL 并先 response.close；[ResponseEntityProxy 5.4.4](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.4.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/ResponseEntityProxy.java) 第164–175行先关闭/排空底层 entity，之后才释放/清理连接。这是静态调用顺序证据，本评审未运行新探针，也未把作者原有43ms结果外推到该漏测路径。
- **最小修复范围：** 仅新 observation policy：保留现有内层 read-failure 的取消（它须早于 input try-with-resources 的 close）；再让整个 observation handler 的终止失败在 Apache 自动 response.close **之前**取消实际 handle，覆盖状态/头/类型/长度拒绝、entity/content 获取失败、最终 proof/deadline check 和严格 error-envelope 失败。保持原 IOException/PlatformException，不改 LEGACY GET/POST 语义，不引入重试或额外请求。无需扩大到其它生产文件。
- **定向验证：** 在现有新 raw peer 测试内增加至少一个“已声明超限、body 不结束”的真实响应，证明早于 hop timeout 取消且仅一次请求；另覆盖未知/401状态或 unsafe-header 的同类未读 body，确保新 guard 不是只修 Content-Length。`RunObservationGetTransportTest.java:46` 的现有短而完整错误 body、`:76` 的读取中超限，均不能证明这些提前拒绝路径。无需重跑旧大套件。

## Minor — 1，已有独立更正，非阻断

### M1：latest-method 记录的 source 字段实际是最终源码身份

- **位置：** `docs/evidence/iam-stage-4-o1c-gateway-status/test-verification.json:16`，对应 stage 为 `iam-stage-o1c-common-wire01`，却写最终测试文件5635B/SHA `42ca61df0c2cad4f392bc9868f8bceb0873c34721f7efe067ca9614a783e96ea`。
- **澄清：** root 新冻结的 `docs/evidence/iam-stage-4-o1c-executed-source-correction.json:5` 明确区分 finalSource 和 executedSource；首个实际样例为4873B/SHA `60819cfbc26370a48fbb31407c6b27938eabd05fd74e5dfee4cb35922ebb2a55`。该 correction 的机器统计为26/31不同、5/31相同，root 最初手写27已明示更正。审查者核了该文件身份、汇总/映射结构与首个样例，未重新审计全部31命令。
- **处置：** 原件保持冻结；后续引用执行身份使用 command.sourceBefore 与实际 XML，最终源码身份另列。已有独立 correction 足以解除这一字段歧义；不因此宣布真实测试结果无效、不要求补跑历史测试，也不把它升格为一项源码阻断。

# Assessment

- **Task quality：Needs fixes。C0 / I1；另有 M1 已由 root 独立证据澄清。** 先修复 I1 的全部新 observation handler 提前失败边界，再对实际窄 delta 和新增定向证据复审；当前冻结 head 不标 Approved。
- **范围界限：** 不改变 v1.2、四契约、T01 唯一全局完成状态或 G6E/F/真实外部 IAM 未验收的界限；无计划时间表、无新增全局完成主张。

# Read identity and handoff

- 完整读取到 EOF：task-reviewer-prompt.md 7919B / `2eb9d54373420de25bc0bd00635d3a3123a6c0eb30c881168e6f3348e2387331`；冻结 brief23192B / `9f1201688af6733a17c3a68e00a57141ce7bfdc292f05d4fb75320c942ea88e2`；implementer report18660B / `ce6b0672dcb7b0615ce0d5bd003f1ec85aed092ebb6a905d850f277c08fd90c6`；handoff4600B / `b7cef80924945929fb1a8e1b16b44af7bc26dcdb12fb536f62fa5b48955abce2`。
- 完整 package187191B / `40238fb8c14f7ff0dcbeeb06aae57ad2354418a55f84379ee39c7cf686a32f18`，已核至2401行末空 EOF。主体互不重叠批次为1–400、401–800、801–1200、1201–1450、1451–1750、1751–2050、2051–2250、2251–2401。801–1200首次工具展示有一小段截断，仅补读997–1004，不把截断当完整阅读；未重复读取任何已完整展示的变更文件。
- Named ambiguity：Q data4096 的口径与 raw wire 的区别；完整读取 interface-finalization-notes.md29920B / `5b8aebf8b0396fe28423d4796940b1baef9cea4320172dcc296dd4dff156b8bf` 至 EOF，确认与 O1a 投影一致口径、恶意 peer 超 M wire 安全503、engine 本身过大独立403，未发现该处偏离。
- Named risk：提前 response rejection 的 graceful close 顺序；只读本次 upstream-reading.json、其指定旧 CloseableHttpClient235–266（EOF），及上述官方固定版本两个 response-close 文件。曾用精确 upstream 目录文件名定位发现无本地 CloseableHttpResponse/ResponseEntityProxy 副本，才读取官方固定来源；未查邻接实现/配置/进程，未扩展成全库或全依赖审计。
- root correction28824B / `968a3b7a9a2099578df83b933f9313ff82c8c103bee6ccfb160f225000a447c2`：身份核验及选择性结构/首例阅读，**不声称全文逐行审阅**。原 test-verification 仅读相关首例及字段行号。源/target/PG/回放的 root 大库存审计未重复。
- 仅新写本审查报告；无源码、测试、target、原证据、八索引或原报告修改，无测试/浏览器/probe/编译/进程操作。报告写完后该新文件写权也交还 root，其它冻结写权一直归 root。
