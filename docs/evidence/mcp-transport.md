# MCP SSE 客户端与共享 Schema 边界实现

状态：本地实现与完整技能模块回归通过，已冻结待独立评审，尚未部署。本增量属于详设 v1.2 原 20 的 MCP Client“列工具/调用/能力协商”，不将其计作 G2/T11 整体完成。

## 范围与协议

只在独立新工程工作；沿用 `io.modelcontextprotocol.sdk:mcp:0.10.0`、Java 21 与现有 PG/ArtifactStore/OutputGate。SDK 自己管理 JSON-RPC ID、初始化、请求响应匹配、分页参数与工具调用。没有升级依赖、引入另一个 MCP 框架或改变原契约。

固定 SDK 协议版本为 `2024-11-05`。本增量支持该版 HTTP + SSE：GET 事件流，接收同源 endpoint 事件，再 POST SDK 原生 JSON-RPC；没有承诺 Streamable HTTP、新版协议、MCP Server、sampling/roots、可靠外部取消或任意大小 MCP 结果。

初始化必须声明 tools 能力；列工具最多 16 页、100 项，游标不能循环，工具名必须唯一。调用精确绑定冻结的 toolName，完整远端 inputSchema 和原冻结 inputSchema 同时验证实际 arguments，远端声明不会写回冻结合同。SDK 0.10.0 的 JsonSchema record 会丢弃部分顶层关键字，因此从受限 JSON-RPC 响应中保留完整 Schema；`minProperties`、`oneOf`、嵌套 pattern 均有真实端点验证。

客户端没有重新连接或业务重试循环。POST body 只允许订阅一次，阻止底层恢复重放。元数据阶段失败不等于业务已发送；发送 tools/call 后断流、丢失 POST 确认、写工具 isError 或 HTTP 5xx，均保守形成 UNKNOWN。明确 403/429 保留 NO_PERMISSION/QUOTA。只读工具失败可标 RETRYABLE_FAILURE，但适配器自己不重试。

`queryOnly=true` 的 MCP 分支明确拒绝再次调用。MCP 没有配置独立 HTTP 对账证据时，reconcile 在原守卫、输入和历史 scope 检查之后保持 UNKNOWN，记录既有审计并返回 retryBusinessAllowed=false；明确配置的 HTTP 对账沿用旧逻辑。没有把重调同一工具当作查询，也没有伪造 NOT_APPLIED 证明。

## 管理接口

`POST /admin/skills/{code}/drafts/{version}/mcp-tools` 无需请求正文；正文提供的 endpoint/authRef/tools 均不作为输入。服务只读取当前已验租户的既有 MCP 草稿，要求 EDITOR 权限；有 authRef 时额外要求 ADMIN。SecretVault 自身仍验证 vault 记录的 tenantId 或 env 引用的租户绑定；它不能替代草稿访问控制。只列工具，不调用业务。

公共返回采用 ApiResponse：data 包含 protocolVersion、serverInfo、capabilities、tools（name/description/完整 inputSchema）和 limits。不会附带合同 authRef 或解析后的凭据。坏票据 401，无权限 403，跨租户版本 404，非 MCP 400，非草稿 409。网关现有技能前缀路由覆盖该子路径。

## 接收、并发与关闭边界

| 边界 | 当前值或机制 |
| --- | --- |
| 本机统一会话许可 | 2，先取许可再创建客户端/开流，finally 释放 |
| SSE 总原始字节 | 16 MiB，包含注释和其他 SSE 行 |
| 单行 / 单事件 | 4 MiB；按原始字节计数后才能构造 UTF-8 String/JSON |
| 含 data 的 SSE 事件 | 最多 256，逐事件同步交付，无应用事件队列 |
| JSON 解码 | 深度 64、tokens 100000、字段名 1024、数值文本 1000；拒绝重复键、尾随 JSON、坏 UTF-8 |
| 单次 POST 编码 | 128 KiB，Jackson 写出阶段即计数 |
| POST ACK 响应 | 8 KiB，逐批订阅后丢弃，不聚合完整响应 |
| 工具目录 | 100 项 / 16 页 / 目录约束 1 MiB；单 Schema 64 KiB；描述 2048 字符 |
| Schema 子结构 | 深度 64、最多 4096 个实际 Schema 位置 |
| 网络目标 | 禁止重定向；SSE 宣告的发送 endpoint 必须同源，不转发凭据到其他源 |
| 时间预算 | initialize、所有 list 页、tools/call 共用原 timeoutMs 的绝对 deadline；阻塞读和 ACK 也覆盖 |
| 生命周期 | 关闭 SDK、原始 InputStream、pending future、每会话 HttpClient；验证 SSE 连接和 selector 线程退出，许可恢复 |

JDK 21 本机源码 Http1HeaderParser 还具有默认 384 KiB HTTP 头部上限；这不是本增量另造的 HTTP 解析器。原始事件字节、SDK records、解码文本及 OutputGate 处理中可能同时存在多份对象，因此 2 个许可覆盖整个会话处理过程。该许可不是分布式准入，也不等于进程全部堆/RSS 的硬上限。

有界 SSE 事件内的结果若超过合同 outputMaxBytes，仍经现有 OutputGate/ArtifactStore 转存、原始 Schema 校验、登记 tenant/principal/run/call 与摘要。超过 4 MiB 编码事件的结果明确拒绝，不裁剪；未实现任意大 MCP JSON-RPC 事件的流式拆解。ArtifactStore 自有上传/下载超时沿用既有实现，不能把 MCP 传输 deadline 解释为已重写对象存储超时。

## 共享 Schema 修复

原 SkillRules 递归遍历所有 map，只检查 `$ref`，并使用允许默认 loader 的 factory。真实 HTTP 探针确认外部 `$dynamicRef` 与未知 `$schema` 各发生 1 次请求；未引用的缺失本地 `$defs` 被接受；合法 const/default/enum/examples 中的实例 `$ref` 被误拒。

通用 schema/validate 现在使用闭合 loader，任何外部网络、文件或 classpath 加载均拒绝；只枚举 Schema 位置，保留 properties 名及实例数据；逐个位置 initializeValidators，在调用前解析未使用定义。覆盖 definitions/$defs、properties/patternProperties/dependentSchemas、items/prefixItems、all/any/oneOf、not/if/then/else 等。MCP 另明确拒绝当前不支持的 `$recursiveRef`，而不把所有未知 annotation 禁掉。端点与 guard 语义未改。

这是原 MCP/HTTP 共用入口的必要修复：如果只在 MCP transport 新加一个 helper，Schema 在 SkillCatalog 保存和 SkillExecution 第一步仍会被旧 helper 误拒。完整存储→执行测试覆盖了合法字面量引用的这一条链。HTTP、ALGORITHM 仍使用原适配入口；需以整个 skill-registry 回归证明兼容性，不能从 MCP 局部测试推导。

## 实际对照的固定源码与许可

- [MCP Java SDK v0.10.0](https://github.com/modelcontextprotocol/java-sdk/tree/v0.10.0)，MIT。实际读取 HttpClientSseClientTransport、FlowSseClient、McpClientTransport/McpTransport、McpClientSession、McpAsyncClient/McpSyncClient 和 McpSchema 的相关方法及 LICENSE。stock FlowSseClient 先按行聚合且无原始行/事件字节上限，请求无限需求；stock transport 的 closeGracefully 仅取消连接 future，不能证明关闭其 SSE socket 和 HttpClient。因此使用 SDK 正式 McpClientTransport 扩展点，独立编写最小有界传输，保留 SDK 协议/会话。缓存 `.tools/mcp-sdk-0.10.0`；LICENSE SHA256 `077f5c1f533a15ec6c67d61b0c09b5df60bb57a1839a7d1fb27faaf7b746195f`。
- [networknt 1.5.6](https://github.com/networknt/json-schema-validator/tree/1.5.6)，Apache-2.0。实际读取 JsonSchemaFactory、JsonSchema、DefaultSchemaLoader、SchemaLoaders 相关代码及 LICENSE，核对自定义 loader 先于默认 URI/classpath loader、schema 懒加载与 initializeValidators。缓存 `.tools/mcp-schema-source`；LICENSE SHA256 `58d1e17ffe5109a7ae296caafcadfdbe6a7d176f0bc4ab01e12a689b0499d8bd`。
- 本机 Adoptium JDK 21.0.12.8 的 lib/src.zip 中 Http1HeaderParser 及其头部许可，GPL-2.0 + Classpath exception；实际核对头部默认限额。没有复制 JDK 解析实现。
- [WHATWG SSE 解析规则](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)：实际核对 CR/LF/CRLF 行尾、首个 UTF-8 BOM 和可忽略扩展字段；按字节上限实现这些基本分行规则，仍不启用自动重连或跨源凭据转发。

## 红绿与证据历史

| 证据 | 实际观察 |
| --- | --- |
| `.local/mcp-adapter-red.log` | 原接口 6 项断言全红：漏列工具、缺工具仍执行、远端 Schema 未校验、写 isError 错归可重试、403 语义丢失、大事件未得到明确限额错误 |
| `.local/mcp-management-red.log` | 4 项断言红：新接口 404、缺 toolName 保存被接受 |
| `.local/mcp-boundaries-red.log` | 21 项中的 unsupported protocol 分类断言红；修复后 21 项全绿 |
| `.local/mcp-connection-error-red.log` | SDK connect 无 error consumer 的局部全局 error-drop 问题，1 项断言红；通过局部 discovery future 传播错误修复，无全局 Reactor hook |
| `.local/mcp-schema-boundary-red-assertions.log` | 14 项中 9 fail / 0 error / 0 skip，两例实际外部 HTTP 读取；修复后及新增本地 recursiveRef 拒绝共 15 项绿 |
| `.local/http-schema-old-bytecode-red.log` | 从本新工程当时运行 JAR 只读提取原 SkillRules.class，隔离 classpath 执行相同 6 个 HTTP/通用断言：5 个断言失败，2 例真实 Schema HTTP 读取。class SHA256 `c0224c120c92f96e251acb42b68735db16eff205399b258e6a795f2ea4bc855d`。没有改写运行 JAR/源码；当前版本同组 6 项通过 |
| `.local/mcp-query-only-red.log` | queryOnly 调用 MCP 业务的 1 项断言红；已改为 UNKNOWN 且零开流/零业务调用 |
| `.local/mcp-reconcile-pg-red.log` / `mcp-reconcile-pg-green.log` | 同一真实 PG 测试：两次对账原为 HTTP_400，红 1 项；修复后两次 UNKNOWN/retryfalse，原事实身份/合同/参数/代次/key/结果不变，审计 2 行，工具与开流不增加 |
| `.local/mcp-sse-compatibility-red-three.log` / `mcp-sse-compatibility-green.log` | 自审补充标准 SSE 的 CR-only、首部 BOM、可忽略扩展字段，3 项断言红→3 项绿；原始字节仍全计数，未放开事件/总量上限 |

中间整模块 `.local/mcp-full-skill-final.log` 为 239/239、0 fail/error/skip，07:27:10 BUILD SUCCESS，包含原 167 项和当时新增 72 项。这次之后只因上表 3 项真实兼容缺陷修改 transport 与其测试；最终整模块必须再跑，239 不作为当前源码的终版计数。

早期 42/65/70 的定向结果分别对应当时实现，不替代最终整模块回归。一次格式化后测试漏载本地环境产生 13 skips，并出现 800ms 夹具冷启动过短导致分类断言失败，保留在 `.local/mcp-targeted-formatted-final.log`，不计作通过。已明确加载 .env（不打印值），常规协议夹具改为 3000ms，真实 timeout/idle/blocked ACK 专门保持 500/800ms。ACK 迟到错误日志没有被当作稳定功能红证据。编译或夹具问题未作为业务缺陷闭环依据。

PG 测试库为本工程专用 `agent_platform_skill_mcp_test`；业务和引擎权威都使用独立 HTTP 夹具。新管理 API 的权限证据经过真实 Nimbus/JWT Spring Security 过滤链。执行器 PG 测试直接设置测试服务上下文，并以真实 SkillClients 调用引擎 HTTP 夹具；它不是已部署真实 engine 的整链验证。8 KiB MCP 业务结果的真实 MinIO 转存、下载摘要、跨主体/跨 run 拒绝与限定测试租户清理已覆盖。真实 engine/gateway 纵切由根任务在独立评审和部署后另行记录。

## 冻结记录

2026-10-20 07:32:34，终版 `mvn.cmd -B -ntp -pl skill-registry test -Dmaven.repo.local=V:/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`（Java 21，进程内加载本工程 .env，不打印值；无 `-am`）实际 **242 tests，0 failures/errors/skipped，BUILD SUCCESS**。原 167 项与新增 75 项在同一轮执行，新增分别为协议 38、MCP Schema 15、普通 HTTP/共享 Schema 6、真实 PG 执行与产物 9、真实 JWT 管理接口 7。日志 `.local/mcp-full-skill-242-final.log`，SHA256 `88b0227461b6075b5394725b6041770399341e88d2648e2c9f59a9cd5ea81109`。

源码清单 `mcp-transport-source-sha256.json` 含 12 个自有 Java 文件，在该整轮开始前生成，结束后 **12/12 摘要一致**；清单 SHA256 `985031c84e81bcec459d63f2bbf023b50c6b82b6bbefa2865cf9938a86c0dcfe`。相对根任务原 675 阶段源码清单，本次授权范围外的既有 skill-registry 文件变更为 0。只在授权后增加 SkillRules 共同边界与 SkillExecution.reconcile 的小范围 MCP 分支，未改 engine/gateway/common/web、异步 HTTP/回调实现、迁移或原契约。

07:33 已再次执行 12 文件 google-java-format 1.25.2 `--aosp --dry-run --set-exit-if-changed`（exit 0）及 `node scripts/verify-contracts.mjs`（exit 0，v1.2 快照、四契约原文摘要与七模块依赖边界通过）。结构化结果及各测试类计数见 `mcp-transport-verification.json`。未 package 或启停部署服务；全部独立夹具随测试进程关闭，新增测试产物只清理各自专用租户的对象。

自审已核对角色/租户、凭据引用、元数据与业务发送分界、原事实不变、封闭引用加载、接收上限及资源关闭。现释放 skill Maven target，由独立评审者复核；真实 engine/gateway 纵切由根任务随后部署验证。不得将此本地报告作为生产发布许可、真实外部 MCP 供应商验收或全部功能完成证明。
