# 执行增量关闭复核与 OpenAPI 导入审查

日期：2026-10-20。本轮结论：**原 I1 / I2 / I3 可以关闭；新增 OpenAPI 子集仍需修改，发现两项 P2。** 未发现新的确定 P1。root 已接收两项新发现并开始定向修复；本报告先记录发现时源码与关闭依据，不将修复计划计作通过。

## 范围与方法

- 沿用 code-review 工作流及本工程 AGENTS、implementation-brief、contracts/README、冻结 v1.2；只访问本独立新工程，未访问旧工程、运行 dev 库或服务进程。
- 复核 docs/evidence/review-execution-increment.md 的 I1/I2/I3；阅读 engine-review-i1-i2.md、model-quota-nosend-i3.md、skill-openapi-report.md 与对应源码、迁移、测试断言。
- 新增审查限定 OpenApiImports、OpenApiHttpMapping、OpenApiImportController，以及 Catalog.save/normalize、Adapters.http 的小范围接入。没有扩大成全平台功能补齐。
- 本审查者未重跑完整测试、未运行数据库/网络故障复现、未改业务/测试/配置，只新增本报告。新发现为静态路径证据；另使用当前 JDK21 的 javap 只读检查 HttpURLConnection 与项目本地 Spring Web 6.2.7 StringHttpMessageConverter 的实际字节码，确认框架行为，不冒认已跑 HTTP 复现。
- 直接读取现存 Surefire XML：ReplayBoundaryTest 7、SkillExecutionTest 25、OpenApiHttpMappingTest 5、ModelCallsPostgresTest 33、ModelQuotaPostgresTest 5，均 failures/errors/skipped=0。上述由其他工作者执行，不是本审查者的测试运行。root 正进行 Redis 7.2.16 回归及实际跨服务附件复制，这两项在本报告初稿时尚未作为完成证据。

## 原发现关闭结论

### I1：当前授权范围与历史结果复用 — CLOSE

技能位置：skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:196、:314–348、:352–407。recordedMock 现在接收已由 SkillRules.guard 求交后的 secured，独立读取源 PG 事实的 request_snapshot.guardedArguments，规范比较一致才复用。原始 argsDigest 相同但当前范围从 [A,B] 缩成 [A] 的旧漏洞被阻断；403 被映射为 NO_PERMISSION，不退回真实技能执行。源事实仍要求同 tenant/principal、成功、非 Mock、精确版本/合同/源 run、结果摘要与权威快照一致。

引擎位置：engine-runtime/src/main/java/cn/agentplatform/engine/application/ReplayScope.java:17–65、ControlledReplay.java:33–58、:158–208；RunService.java:34。ReplayScope 实施冻结守卫的角色、属性交集、数组参数收窄和默认拒绝；空属性声明使用当前已验 Actor 的非空范围。复现逐调用核对源 guardTrace.argsDigest 与当前 secured 摘要，缺证据拒绝；在创建 run 前还对全部源技能事实做 preflight，避免缓存终答直接复用而绕过已变窄的工具数据范围。

证据核对：SkillExecutionTest.lastRealCannotReuseBroaderGuardedArgumentsAfterPermissionNarrows；ReplayBoundaryTest 的 narrowedCurrentScopeCannotReuseWiderRecordedResult、missingGuardEvidenceCannotBeReused、narrowedScopeRejectsBeforeStorageAndBeforeSavedModelAnswers 及空属性声明正例。已阅读断言，覆盖的是原明确触发路径及零新增业务外调，不只是返回一个新错误字段。

关闭边界：目前实际数据范围由合同声明属性对显式参数求交形成；本轮关闭基于这条真实管道。没有另外声称已完成外部 IAM 任意隐藏数据范围的授权语义。跨租户/主体和合同摘要检查仍保留。

### I2：投影结果验收与新 trace / 工件归属 — CLOSE

位置：ControlledReplay.java:193–250、:255–367、:369–396；SkillExecution.reproduceArtifacts:352–407；ToolCalls.view 的 arguments/source pointer 返回。

- 不再把经过 returnSpec 的 structured 当原始 HTTP 输出重新按 outputSchema 验收。读取的是同版本/参数/合同的权威成功/失败事实，并验证源 Envelope owner 的 tenant/principal/run/call/node/version/code/status；已通过原始 Schema 的标量投影或 null structured 附件能够正确进入复现。
- 新 trace 重建当前 run/node/attempt/call 的 owner，写 isMock 和 sourceProvenance，源记录保持原样。Agent 后续模型工具消息使用源下一轮 source_request 中对应 tool_call_id 的精确文本，避免新 owner 使历史模型材料比较失败。普通驱动仍重算条件与表达式，未变成复制 source.output。
- 附件复制前，当前工具 claim 与 run→node→call 锁内 source pointer 持久化；skill 的 reproduce-artifacts 再以 claim 重查活租约、DEBUG、来源指针、版本/原始参数摘要、源结果摘要以及当前 secured 参数。body 不决定 fileRefs；真正文件列表来自 skill PG 事实。
- ArtifactStore.copyToRun 独立校验源 tenant/principal/run/有效期，读取并重新登记当前 run 的对象；引擎拒绝旧 artifactId、重复 ID、缺失数量及非当前 run 的下载路径。旧源 owner 与存储键不被当作新 run 授权。

已读 ReplayBoundaryTest 的投影、空 structured、篡改/旧附件路径拒绝和当前 owner 断言；普通 ControlledReplayTest 继续保留 source.output=999 而重算为43的证据。SkillExecutionTest 的存储端点绑定反例及既有真实 MinIO 复制测试提供服务内证据；实际 engine→skill HTTP 组合复制由 root 单独验收，不以单模块用例代替。

**边界澄清**：本轮允许基础设施存储复制的内部 HTTP，禁止真实模型与技能业务外调。旧“零 HTTP”笼统描述已由 engine-review-i1-i2.md 明确修正；本报告仅确认该明示边界，不能继续向用户承诺包含存储通信在内的零 HTTP。

### I3：发送前失败的配额预留恢复 — CLOSE

位置：model-gateway/src/main/resources/db/migration/V305__durable_unsent_quota_reservations.sql；ModelCalls.java:267–293、:348–349、:373–374、:494–509；ModelStore.java:399–459；ModelQuota 的 ACQUIRE/RECONCILE 与带 beforeAcquire 的 acquire。

- timeout 与凭据校验前移，非法请求先于配额预留拒绝。Redis 预留之前，PG reserveQuota 保存不可变 receipt 和 RESERVING 意图；PG 写失败不调用扣额 Lua。Redis 准入在同一 Lua 内写 provider generation/窗口预留证据，丢失客户端返回时仍可判定是否实际扣额。
- 同步与流式真实 wire 校验继续在网络前执行；本地发送日志成功后以同一 PG 行 CAS 从 RESERVING 转 SENT，并要求租约仍有效，之后才交给 transport。发送者与恢复者竞争同一行，不能同时取得 SENT 和 NO_SEND。
- 已证实未越过发送边界的失败转 NO_SEND/PENDING，按实际 token=0 退款两个日额及原窗口 TPM/RPM。退款失败可用 PG receipt 恢复；NO_SEND 更新本身失败时，RESERVING 到期仍有恢复入口。SENT/未知 usage 保守保留预留，不能当零用量冲正。
- 15 分钟 admissionUntil 同时约束 PG lease 与 Redis 准入：找不到预留证据且窗口未结束时不能提前终结退款，迟到的准入在截止后也不能扣额。沿用 F6 的 24 小时截止、marker 迟于截止到期、provider generation/PTTL 与 UTC 日窗口，PG 账务终态为 PENDING CAS。

已读定向断言：同步/流式 wire 拒绝、发送日志失败、PG 意图写失败、Redis 退款失败后换实例恢复、NO_SEND 写失败后 lease 恢复、迟到 sender 被 fence、缺证据且准入未截止维持待恢复，以及已发送未知 usage 保留预留。现存 ModelCallsPostgresTest 33 与 ModelQuotaPostgresTest 5 全绿；关闭针对原 I3 的确定漏洞，仍不声称 Redis 全库回滚/人工删除/持久数据损坏下的 exactly-once。

## OpenAPI 新发现

### O1 / P2：允许导入 PATCH，但真实 HTTP 传输不支持 PATCH

发现时位置：skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java:124–127、:136–142；OpenApiHttpMapping.java:56；OpenApiImports.java:301–302。

解析/映射明确将 PATCH 当支持方法；真实 http() 却固定使用 SimpleClientHttpRequestFactory。该工厂依赖 JDK HttpURLConnection，其 setRequestMethod 的允许集合仅为 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，PATCH 会抛 ProtocolException，不能发送。因而可创建“supported=true”的 PATCH 草稿，但真实连接门禁永远无法成功；写技能还可能被包装为 RESULT_UNKNOWN，误导后续对账。

证据：只读 javap 已确认当前 JDK21 HttpURLConnection 的方法白名单及非法方法异常分支。OpenApiHttpMappingTest 的实际 HTTP 用例只发送 POST，不能覆盖 PATCH。未声称本审查者已运行网络复现。

建议：要么在当前安全子集预览明确拒绝 PATCH，要么使用支持 PATCH 的受控传输，并保留连接/读取超时、禁跳转策略及现有响应流硬限制；新增真实 HTTP PATCH 方法、路径、请求体和响应验收断言。只让映射校验通过不构成修复。

### O2 / P2：application/json 字符串请求体被发送为普通文本

发现时位置：OpenApiImports.java:359–365、:446 的 JSON Schema 类型支持；OpenApiHttpMapping.java:103–108 的 body 返回；SkillAdapters.java:142 的 request.body(requestBody)。

导入允许 required application/json body 的 Schema 为 string。映射后 body 是 Java String，例如 `hello`；RestClient 默认 StringHttpMessageConverter 会直接复制字符串字节，缺少明确 Content-Type 时选择普通文本，因此实际发送 `hello` / text/plain，而不是 JSON 字符串 `"hello"` / application/json。严格业务服务会 400/415，宽松服务也收到与导入合同不同的数据。

证据：只读 javap 已确认项目 Spring Web 6.2.7 StringHttpMessageConverter.writeInternal 直接 StreamUtils.copy(String, charset, body)。新实际 HTTP 用例只使用 Map body，Jackson 正常发送对象，并不能证明标量 JSON 被正确编码。

建议：映射 JSON body 统一交由 JSON 节点或显式 JSON 编码后的字节发送，并设置 application/json。仅对 Java String 增加 contentType 不足以避免 String converter 原样发送。新增真实 HTTP 字符串（含引号、反斜杠与非 ASCII）测试，同时保留对象/数组/数值/布尔的 JSON 类型；旧非映射分支不应被无意改变。

root 已确认 O1/O2 并安排修复。报告初稿时尚未复核修后源码，故新导入子集的结论仍为 Request changes。

## OpenAPI 其余限定检查

- **解析无外部权威**：先做 1 MiB 文档、32 深度/20,000 节点扫描，拒绝外部/文件/相对 $ref，之后 swagger-parser 仅 readContents 内存 JSON，resolve 系列开关关闭。局部引用链/Schema 展开有上限并检测循环；YAML alias/anchor、重复键、多文档拒绝。HTTP 请求体另有 2 MiB 含无 Content-Length 流的上限。
- **导入凭据/角色独立来源**：preview/create 要求 EDITOR；服务器仅将明确 manual 覆盖的六维字段放入合同，文档扩展字段不授予角色或凭据。服务地址必须人工 baseUrl；非空 authRef 要求 ADMIN 且 SecretVault 当前租户解析成功；Bearer operation 没有显式认证引用时拒绝。文档服务器 URL 不被执行。
- **路径与参数**：path/query/body 名称隔离，required path/body、标量类型和未映射字段有校验；参数按 UTF-8 编码，URI 分支避免二次编码；路径值点段/空值拒绝，模板不能进入 scheme/authority。此结论针对直接请求构造，不等价于外部系统对任意编码路径的内部业务语义保证。
- **持久与门禁**：create 外层事务包围 Catalog 创建，落真实 DRAFT，不制造连接/选型报告或发布指针；publish 原门禁继续依精确版本/合同摘要验收。Catalog 查询保留 tenant，save 在主行锁内补回省略的 importSource/httpMapping，来源替换拒绝，改 Schema 与映射不一致拒绝。来源摘要是追踪信息，不是签名或授权。
- **公共返回**：新 Controller 沿既有公共 API 约定遮蔽 authRef 并返回 authConfigured；本轮未发现将 SecretVault 明文或实际配置引用通过导入 drafts 回包直接公开的路径。没有把任意用户文档中名为凭据的文本误判成真实服务凭据。
- **受支持子集真实边界**：复杂 Schema/组合认证/非 JSON body/多成功 Schema 等明确 diagnostics，未伪造全 OpenAPI 支持。O1/O2 是已宣称支持子集的可执行性错误，不是要求扩展更多协议。

本轮不改变原报告对容量、外部 IAM/模型、完整 UI/向导和生产验收等台账边界的结论。

## 追加：O1 / O2 修复关闭复核（同日 04:22 后）

**本报告最新结论：I1 / I2 / I3 / O1 / O2 均可关闭。** 上文 Request changes 保留为发现时结论；本追加仅关闭已列具体缺陷，不把未实现台账或外部生产验收标为完成。

本次只复核 SkillAdapters.http 的 PATCH transport、JSON body 编码及资源清理，读取新增测试与实际红绿日志，没有重跑完整套件、访问运行数据库或修改业务实现。

### O1 — CLOSE

SkillAdapters.http 现在仅对 PATCH 创建单次 Java21 HttpClient，使用 JdkClientHttpRequestFactory；其他方法仍用原 SimpleClientHttpRequestFactory。已核对：connectTimeout=3s、合同 timeoutMs 传入 Jdk factory、Redirect.NEVER、映射 URI 仍按已编码 URI 传入、不改变 authority 校验及凭据来源。响应继续进入原 read() 的 max+1 字节读取、转存和原始 Schema 验收，没有换成无界聚合响应。

HttpClient 创建后的请求工厂构造、请求构造、发送、响应验收均在 try/finally 内，finally 调用 patchClient.close()；RestClient 的 exchange 管理响应关闭，读取失败也不会跳过 finally。另只读检查项目 Spring Web 6.2.7 JdkClientHttpRequest 字节码：配置 timeout 时实际建立 TimeoutHandler 并包裹响应 InputStream，而非仅设置一个未使用参数。没有把无压力正常 fixture 的通过扩大为资源/慢响应容量验收。

### O2 — CLOSE

所有非 GET 且非 null 的请求体显式设置 application/json，并先变为 JsonNode 再交给 RestClient，避免 StringHttpMessageConverter 对 Java String 的原样发送。映射的 path/query/body 分离保持不变；原非映射分支的输入本来是参数对象，转成 JsonNode 后仍是 JSON 对象，不会变成 JSON 字符串套壳。GET 不新增 body，映射无 body 时仍不发送。

### 红绿证据与 I2 实际组合链补充

- 已直接读取 `.local/skill-openapi-wire-red.log`：新增两个参数化实际 HTTP 用例中，POST 期望 `POST|application/json|"hello"`，实际为 `POST|text/plain;charset=ISO-8859-1|hello`；PATCH 为传输失败。总计 2 项，1 failure、1 error，非伪造红测。
- 已直接读取 `.local/skill-openapi-wire-green.log`：修后 skill 70 项，0 failures/errors/skipped，BUILD SUCCESS，2026-10-20 04:22:11 完成。现存 OpenApiHttpMappingTest XML 为 7 项全绿；新参数化用例分别证明 POST/PATCH 实际到达测试 HTTP server，且服务端收到对应 method、application/json 与 JSON 字符串 `"hello"`。本审查者只读日志和断言，未冒认自己执行这些测试。
- 已读 `tests/smoke-reproduce-artifact.ps1`、`.local/reproduce-artifact-smoke-result.json`，确认 root 的实际组合链证据：源 run `r_36c8e3b74a764621be4175a63dc2a762` → 新 run `r_dd4e99b3161249b1a520690123f6ace9` 为 SUCCEEDED；新附件 `art_6f41736bb741458abd1a823e1a07b561`、当前 run 的下载路径、5,242,926 字节；下载 SHA-256 与源/新引用一致，`businessCallsAdded=0`。脚本实际通过网关发起复现、轮询、下载并计算哈希，且比较外部 fixture 调用计数，不是只拼装结果 JSON。
- 该 smoke 明确 `fixtureOnly=true`，补上的是 engine→skill→MinIO 的真实基础设施组合链；不等同真实业务服务或生产容量验收。存储内部 HTTP 允许、模型/业务外调禁止的 I2 边界保持明确。

本轮限定复核未发现需要继续阻断 O1/O2 关闭的新问题，审查到此结束。
