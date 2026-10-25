# MCP / Skill Schema 资源边界补丁

本轮是独立审查发现后的修复候选，未部署。旧 `mcp-transport.md`、242 项日志及 12 源文件清单保持历史原样；它们不代表本补丁已被独立批准。原审查后续受到自动安全审核中断，不能将其中断写成审查通过。本轮没有重放堆耗尽或原慢正则探针，没有捕获 OOME，也没有使用超时后台校验线程。

## 引用图准入

`skill-registry/.../domain/SchemaWorkBudget.java` 是不依赖 networknt 的纯前置准入。`inspect(JsonNode)` 返回 Plan，必须在 `JsonSchemaFactory.getSchema` 前调用。它沿真实 Schema 位置建立结构边与本地引用边，包括未使用的 `$defs`，不解释 const/default/enum/examples 内的字面量引用。重复引用边保留，不能用去重后的定义数量充当展开量。

单节点展开代价 `C(v)=w(v)+ΣC(child)`，使用记忆化和饱和加法；所有实际 Schema 位置作为初始化入口的 `ΣC(v)` 上限 8192。权重含关键字、字面量节点/字符、路径与根资源 URI 的成本。这样在进入库构造前即拒绝共享图导致的反复展开。不是仅限制原始字节，也不是仅调低 eager 深度。库预加载深度改为有限 64，Schema 外部加载器仍默认拒绝，关闭跨调用 Schema 缓存。

上限：实际位置 4096、引用路径 64、原始 Schema JSON 深度 64、Schema 原始节点 65536/字符 1Mi、字段名 1024、数值文本 1000/绝对 scale 1000。实例独立为 250000 节点/64Mi 字符/深度 100，与既有 ArtifactStore 的接收上限一致；MCP 原接收闸仍先限制到更小的单事件/会话上限。这些是工程准入界限，不能称为完整 JSON Schema 任意输入支持。

支持普通本地 JSON Pointer（含 ~0/~1 与 URI fragment 解码）、单资源唯一 `$anchor` / `$dynamicAnchor`。明确拒绝循环引用、嵌套 `$id` 资源边界、重复 anchor、引用指向 literal 而非子 Schema、非本地引用、未知/非 2020-12 元 Schema、已不属于该规范的 `$recursiveRef`。根 `$id` 仅允许无片段绝对 URI。闭合 loader 是最后外取防线，准入并不代替 loader。未知业务 annotation 不一概禁止。

## 正则与实例工作量

固定新增依赖 `com.google.re2j:re2j:1.8`，只使用 networknt 1.5.6 的 `setRegularExpressionFactory` 扩展点；未替换 JSON Schema 框架。`BoundedRegexFactory` 预编译实际 pattern / patternProperties 中的唯一表达式，保留 matcher.find 搜索语义。最大 32 个表达式、单式 256 字符；在 RE2 编译之前对 counted-repeat 做保守乘积估算，估算上限 4096，之后实际 `programSize≤2048`。不支持的 RE2 模式（例如回溯引用/前后查找）、`\Q` 引用和 `format:regex` 会明确拒绝，无 JDK fallback；不是完整 Java/ECMA-262 正则兼容层。保守估算也可能拒绝某些本可安全运行的模式。

`Plan.admitInstance(instance, regex.programUnits())` 在执行框架校验前，限制结构分支×实例节点到 2,000,000，正则指令×展开权重×字符/节点到 4,000,000；const/enum 比较、字符串长度扫描、数值运算和 uniqueItems 另加保守工作量限制。普通无字符扫描约束的 5MiB 字符串仍可验证，并继续走原 OutputGate / ArtifactStore。开启 failFast 限制错误收集，调用方继续得到既有参数错误语义。

`SchemaDeadline.until(absoluteNanoDeadline)` 是可嵌套/恢复的线程作用域；预检各节点、正则编译/匹配前后、初始化各位置以及校验前后检查同一单调 deadline。McpAdapter 主线程包含输入和 OutputGate 校验，transport reader 的 unmarshal 单独进入同一 deadline。没有每页重置，也没有返回超时而遗留校验任务的执行器。普通同步 JVM 操作无法承诺硬实时抢占；本方案依靠前置有限工作量和作用域检查界定计算，不把网络 timeout 冒充 CPU 中断。

## 生命周期

`BoundedMcpSseTransport.close()` 仅启动一次会话清理，API 最多等待 50ms；它不把等待超时等同资源退出。实际完成信号需要 reader join 完成、`HttpClient.awaitTermination(...) == true`、全部活动 send 退出。正在解析或发送的任务不会在完成信号之前退许可。

`McpAdapter` finally 注册 `whenTerminated(SESSIONS::release)`，然后调用 SDK / transport close。transport 尚未创建的认证或构造失败仍直接退许可。慢清理持有原有 2 个会话许可，每会话最多一个清理 watcher；不会开放新会话叠加残留工作。取消 completion 订阅不会取消实际终止事实。受控真实 SSE reader gate 验证：API 可及时返回，reader 仍活动期间 completion 未完成/许可未退；释放 gate 后完成且只退一次。远端心跳计数的传播延迟与本地实际终止事实分别判断。

## 验证与证据

- `.local/mcp-resource-preflight-red.log`：纯预算入口 5 项，4 个真实断言失败；绝不构建过量 graph 的校验器。对应首次绿 5/5。
- `.local/mcp-resource-regex-red.log`：4 个短普通模式/已过期作用域断言失败；没有慢输入或资源耗尽。
- `.local/mcp-resource-lifecycle-red.log`：受控 reader gate 1 个真实提前完成断言失败。
- `.local/mcp-resource-full-skill-268-red.log`：首轮完整 268 中 267 通过、异步 18MiB 产物 1 项失败；原因是将公共实例闸设为 16Mi 字符，已修为既有 64Mi 产物接收边界，不修改 ArtifactStore、不裁剪数据。新增短普通类型错误兼容检查 `.local/mcp-resource-error-type-check.log` 本身是 1/1 绿，复读框架发现其已捕获 failFast 异常并返回错误集，没有伪造异常类型红证据或新增不必要包装。
- `.local/mcp-resource-budget-targeted.log`：16/16；`.local/mcp-resource-wire-green.log`：30/30，真实 SSE/HTTP，超预算/不支持正则/实例预算在业务调用前拒绝，外取计数 0；已发写调用的输出失败保持 UNKNOWN、仅调用 1 次。
- 最终全模块、异常类型兼容检查、源码前后摘要和格式/契约检查以本轮独立 `mcp-resource-budget-verification.json` 与 `mcp-resource-budget-source-sha256.json` 为准。

最终 `mvn -B -ntp -pl skill-registry test -Dmaven.repo.local=V:/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`：2026-10-20 08:04:44 完成，22 套测试共 **270/270，0 失败、0 错误、0 跳过**（原242+新增28）。包含真实 PG、JWT 管理入口、HTTP/SSE、MinIO 及原 HTTP 异步/回调/对账回归。源码/配置共12文件，执行前后摘要一致；11个Java的AOSP检查和原四契约/七模块边界检查均通过。`.env` 仅向测试进程注入，不记录明文凭据；没有 `-am`、没有 package、没有启停运行服务。

最终全模块日志 SHA256 `612b4d87fe27dd01b88a48a0cc83be1b74103c75d17d82511820ce4a01259fb8`；本轮源配置清单 SHA256 `7de1dd0bf037adb95a1de37f06bf086212f97c2b007a3414528215efb7edbbdd`。本轮候选仍待新的安全方式独立审查；这不是生产供应商或全部功能验收。后续共享组件迁移另立增量，不回写本历史清单。

仍保留有界单事件内结果转存的原运输边界：4MiB 事件/16MiB 会话，不宣称任意大 MCP 流直接转存；固定 SDK 0.10.0/SSE/2024-11-05，不承诺 Streamable HTTP。未修改管理 API 的路径或权限，也未改 HTTP/算法的 query 契约、执行事实键/原合同/原四契约。

## 实读来源和许可

- [networknt 1.5.6 RefValidator](https://github.com/networknt/json-schema-validator/blob/1.5.6/src/main/java/com/networknt/schema/RefValidator.java)、JsonSchema / JsonSchemaFactory / SchemaValidatorsConfig / RegularExpressionFactory / PatternValidator / PatternPropertiesValidator / JDKRegularExpression，Apache-2.0。核实重复 eager 引用展开、regex 扩展、JDK find 语义和惰性初始化；只使用原有扩展，没有移植验证器。
- [RE2/J re2j-1.8](https://github.com/google/re2j/tree/re2j-1.8) Pattern / Parser / Compiler / Machine / RE2 / README / LICENSE。核实 NFA 匹配、公开 programSize、编译器 counted-repeat 展开且无通用长度上限，所以不能只依赖线性匹配保证编译阶段。BSD-3-Clause 原文留存 `docs/licenses/re2j-1.8-BSD-3-Clause.txt`，后续分发需携带。JAR SHA256 `7b52c72156dd7f98b3237a5b35c1d34fba381b21048c89208913ad80a45dfbd7`。
- 固定 SDK 0.10.0 源码已在原报告记录；本轮复读 McpClientSession / McpAsyncClient close/closeGracefully，SDK 会话继续原生使用。

## 后续 engine 复用入口

engine InputDeclarations / OutputPolicy 的 MAX eager 深度属于后续单独任务，本轮没有修改或依赖 engine JAR。可迁移的三个完整路径：

- `skill-registry/src/main/java/cn/agentplatform/skill/domain/SchemaWorkBudget.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/domain/BoundedRegexFactory.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/domain/SchemaDeadline.java`

依赖只需 Jackson / networknt1.5.6 / 固定 RE2/J1.8。当前接入点是 `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java` 的 prepare/validate；MCP 主线程 scope 在 `infra/McpAdapter.java` 的 session，reader scope 在 `infra/BoundedMcpSseTransport.java` 的 unmarshalFrom。

复用顺序应为：已有严格 JSON 接收 → `SchemaWorkBudget.inspect(schemaNode)` → `new BoundedRegexFactory(plan.expressions())` → 原 networknt 配置设置 factory/有限 preload/闭合 loader → 按 Plan.locations 初始化 → `plan.admitInstance(valueNode, regex.programUnits())` → 原框架 validate，外围在现有绝对 deadline 的 SchemaDeadline scope 内执行。不能只复制有限 preload 数字而绕过引用图预算，也不能只复制 RE2 匹配而省掉编译预算。后续迁移须保留本引擎既有输入/输出错误契约并独立回归。
