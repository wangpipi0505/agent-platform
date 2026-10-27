# PLAT-13 T13 contextPack 注入 — 证据

日期：2026-10-27　模块：`engine-runtime`（未提交，由编排者统一提交）
日志：`red.log`、`green.log`、`regression.log`（同目录）

## 一、改了什么

新增：

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/OntologyContextPacks.java` | 184 | JDK `HttpClient` 取包；`X-API-Key`；5s 连接/15s 总超时；≤256 KiB；非 200 → 503；4xx 消息带状态码；按 `tenant\|space\|releaseId` 缓存（5min/64，失败不缓存） |
| `engine-runtime/src/main/java/cn/agentplatform/engine/domain/ContextPackText.java` | 170 | 纯函数渲染四段固定顺序文本；≤8000 字符；截断追加 `…（共 N 项，已截断）` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OntologyContextPacksTest.java` | 160 | 3 条：请求头/路径、500 不缓存、缓存按租户/空间/版本隔离 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/domain/ContextPackTextTest.java` | 69 | 1 条：固定段序/空包/稳定渲染 + 超 8000 截断 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunServiceContextPackTest.java` | 209 | 4 条：冻结写包+审计、未声明零 HTTP、未装配 503、非法配置 400 |

修改：

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/ContextAssembler.java` | +4 | 在 `${` 守卫之后追加 `\n\n[本体上下文]\n` + 文本；既有段落/消息顺序不变 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | +50 | `contextPacks` setter 注入；冻结段末尾 `applyOntologyContext(...)`：400 配置校验、503 未装配、写 `config.ontologyContext` 与 `snapshot.contextPack` 审计四字段 |
| `engine-runtime/src/main/resources/application.yml` | +5 | `agent.ontology.base-url` / `agent.ontology.api-key-ref`（空默认，未配置不阻断启动） |

未触碰：`ModelInputMaterials`、`ModelInputMaterialStore`、`AgentDriver`、`RuntimeClients`、其它模块、其它迁移、`pom.xml`、`contracts/**`、`web/**`。

## 二、为什么

- **取包只在冻结阶段**：`ModelInputMaterials.assemble` 会用 `new ContextAssembler().assemble(config,...)` 重算请求哈希（`ModelInputMaterials.java:279`），`ModelInputMaterialStore` 再按 `Json.canonical` 比对（`ModelInputMaterialStore.java:216`）。若 `assemble` 触网，回放/重算会因网络抖动产生不同请求 → 因此包文本必须在 `prepareSnapshot` 冻结时写进 config，`assemble` 只做确定性字符串拼接。
- **渲染必须逐字节稳定**：`ContextPackText` 只按固定 key 顺序读取、沿用包内数组顺序，不使用 map 迭代顺序、时钟或随机数；测试对同一包做两次渲染（含序列化往返）断言相等。
- **失败不缓存**：本体 5xx/4xx/超时只返回 503，不写缓存，避免一次抖动把整轮运行钉死；成功缓存 5 分钟、容量 64 按访问序 LRU，键含租户，防止跨租户遮蔽。
- **错误语义**：`space`/`releaseId` 缺失或非 UUID → 400 `CONTEXT_PACK_CONFIG_INVALID`（配置错误，运行不可创建）；未装配/未配地址/本体不可用 → 503 `CONTEXT_PACK_UNAVAILABLE`（可恢复）；不做"静默跳过"，否则声明了本体却被悄悄忽略。
- **注入点位置**：本体文本是数据，示例问答可能含 `${...}`。放在 `Values.resolve` 与 `${` 守卫之后，避免被当成模板变量替换或触发 `PROMPT_VARIABLE_MISSING`；对外格式仍是卡片要求的 `\n\n[本体上下文]\n` + 文本，且不改变既有段落与消息顺序。
- **审计与正文分离**：snapshot 只落 `space/releaseId/sha256/fetchedAt/truncated`，正文仅在冻结后的 `config.ontologyContext`；`engine.run.snapshot` 是 NOT NULL jsonb 且建 run 时即写入，所以审计不需要新列（查询用 `snapshot->'contextPack'`）。编排者复核时**删掉了原 `V221` 迁移**：没有写入者的列等于假 schema，等真正需要按 release 反查运行再一并迁。

## 三、测试命令与原文

环境：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`，在仓库根执行。

红（实现前，测试先落盘 + 生产骨架仅有 API 签名）：

```
mvn -pl engine-runtime test -Dtest='OntologyContextPacksTest,ContextPackTextTest,RunServiceContextPackTest' -DargLine="-Xmx1024m"
[ERROR] Tests run: 8, Failures: 5, Errors: 2, Skipped: 0
```

逐类：`RunServiceContextPackTest` 4/3F、`ContextPackTextTest` 1/1F、`OntologyContextPacksTest` 3/1F/2E。
场景 3（未声明 ontology → 零 HTTP）在红态即绿：它是"既有行为不变"的守护用例，实现前后都必须绿。

绿（同一命令，实现后）：

```
mvn -pl engine-runtime test -Dtest='OntologyContextPacksTest,ContextPackTextTest,RunServiceContextPackTest' -DargLine="-Xmx1024m"
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

定向回归（存在的类：`ContextAssemblerTest`、`RunServiceContextPackTest`；未跑模块全量）：

```
mvn -pl engine-runtime test -Dtest='ContextAssembler*Test,RunService*Test' -DargLine="-Xmx1024m"
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

8 条场景 → 用例映射：

| # | 场景 | 用例 |
| --- | --- | --- |
| 1 | 请求只有 `X-API-Key`、无 `Authorization`、路径含 releaseId | `OntologyContextPacksTest.requestCarriesOnlyApiKeyAndReleasePath` |
| 2 | 200 → `config.ontologyContext` 含概念卡/术语，`snapshot.contextPack.sha256` = 响应体 sha | `RunServiceContextPackTest.successfulPackIsFrozenAndAuditedBeforeDispatch` |
| 3 | 未声明 ontology → 零 HTTP、无 `ontologyContext` | `RunServiceContextPackTest.runWithoutOntologyDeclarationNeverCallsOntology` |
| 4 | 声明但未装配 → 503 `CONTEXT_PACK_UNAVAILABLE` | `RunServiceContextPackTest.declaredOntologyWithoutClientIsUnavailable` |
| 5 | 桩 500 → 503 且失败不缓存（第二次仍请求） | `OntologyContextPacksTest.serverFailureIsUnavailableAndNeverCached` |
| 6 | 超 8000 → 含"已截断"且长度 ≤8000 | `ContextPackTextTest.rendersFixedSectionsAndTruncatesOversizePackWithinBudget` |
| 7 | space 缺失 / releaseId 非 UUID → 400，且不触网 | `RunServiceContextPackTest.invalidOntologyConfigIsRejectedWithoutHttp` |
| 8 | 同 key 二次取包只发 1 次；不同 tenant 同 space/release 不共享缓存 | `OntologyContextPacksTest.cacheIsScopedByTenantSpaceAndRelease` |

## 四、未覆盖

- **没有独立的审计列（有意）**：`engine.run` 的 INSERT 在 `createInternal` 内联（`RunService.java` 约 655 行），不在本卡白名单内；因此不做"建列不写"的半成品，审计四字段只落 `snapshot.contextPack`。若后续要按 release 反查运行，再补迁移 + 写入者。
- 未与真实 ontorepo 联调：桩模拟 `Envelope{code,message,data}`；真实 release context-pack 正文没有 `candidateSha256`（见"偏差"），仅在测试桩里构造该字段。
- 未跑 Spring Boot 上下文启动/任何 Postgres 测试：新 `@Component` 的注入、`application.yml` 占位符解析、`snapshot` 落库校验（`TraceStore`/`RunAuthorityStore` 的拒绝清单）都只做了静态核对，未在真库验证。
- `firstChat`/`recordedReplay` 路径绕过 `prepareSnapshot`（既有语义）：这两类运行不会取包，也不会写 `contextPack` 审计。
- 同一 key 并发首次取包可能发两次请求（无 single-flight）；只保证"成功后缓存"，未做请求合并。
- 4xx 带状态码的消息分支只有代码路径保证，未由桩覆盖（桩覆盖 200/500）；`vault:` 密钥解析未在单测覆盖（测试用 `forApiKey` 工厂）。
- 8000 预算按 Java `char` 计，未按 Unicode 码点/显示宽度折算。

## 五、回滚

1. 配置层（最快）：`AGENT_ONTOLOGY_URL` 置空，并在应用定义/发布版本里删除 `config.ontology` → 冻结不再取包，行为回到注入前（无迁移需要回退）。
2. 代码层：删除 `OntologyContextPacks.java`、`ContextPackText.java` 与三个测试文件；恢复 `ContextAssembler.java`（4 行）、`RunService.java`（50 行）、`application.yml`（5 行）即可。
3. 数据层：本卡不产生任何迁移，无需回退。

## 六、偏差与需编排者拍板

1. **`candidateSha256` 缺失容忍**：真实 release context-pack 正文（`FrozenKnowledge.context`）没有该字段。实现为"存在则必须 64 hex，缺失不阻塞"，sha 审计用响应体 sha256。严格按卡片会令真实联调恒 503；若要严格，需 ontorepo 在包正文补 `candidateSha256`。
2. **注入点在 `Values.resolve` 与 `${` 守卫之后**（卡片字面为之前）：理由见"为什么"；格式与顺序不变，仅位置后移 2 行。
3. **`ontology: {}` 空对象视为未声明**（返回而非 400），避免既有应用占位配置被误判；`space`/`releaseId` 缺失或非 UUID 仍为 400。
4. **不建审计列**（见"未覆盖"），审计先进 snapshot；落列需改 INSERT，超出本卡白名单。
5. **`api-key-ref` 走 `SecretVault`**，因此兼容 `vault:` 与 `env:`；生产按卡片用 `vault:`。若要求强制 `vault:` 前缀，需要再加一道启动期校验（当前未加，避免阻断本地 `env:` 联调）。
6. **不新增迁移**（卡片原写 `V222`、后改 `V221`；复核后整条迁移删除，理由见上）。
7. **`ContextPackText` 只渲染约定字段**（name/code/title、description/definition、properties、term/aliases、cardinality、question/answer/sourceRef 等）；本体若新增展示字段需同步，否则会被静默忽略。
8. **响应体 sha 按原始字节字符串计算**（含空白/字段顺序）：本体序列化变化会改变审计 sha，这是预期行为；不是规范化 JSON 摘要。
