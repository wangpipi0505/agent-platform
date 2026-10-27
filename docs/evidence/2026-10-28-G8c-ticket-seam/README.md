# G-8c 服务票据签发缝（卡片 21）证据

- 日期：2026-10-28
- 仓：`agent-platform`，分支 `feat/v1.2-implementation`，基线 HEAD `a31bc95`
- 卡片：`文档/优化方案/派发卡片-B1-20261027.md`「卡片 21：G-8c —— 服务票据签发缝」
- 工作树：本卡只产生 3 处改动 + 2 个新文件，未 `git add` / `commit` / `checkout` / `stash`

## 1. 目的

把 skill 服务出站调用的票据从"硬编码调用 `ServiceTokens.issue(actor)`"变成一个可替换的缝，两态且**互不回落**：

| 模式 | 票从哪来 | 何时用 |
| --- | --- | --- |
| `LEGACY`（默认） | `ServiceTokens.issue(actor)`，HS256 自签，逐字沿用今天的行为 | 现在、以及需要回滚时 |
| `AUTHORITY` | 只认"当前请求已绑定的 `VerifiedAuthority` + `ServiceRoutePolicy` 目录登记 + 装配好的 `ServiceTicketProvider`" | IAM 部署 |

动机：IAM 模式下 `ServiceTokens.issue` 本身就会抛 `403 AUTHORITY_CONTEXT_MISSING`（`admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:27-31`），签发方与验签方必须解耦；本条缝也是"用显式失败取代静默回落"的样板。

验收标准（卡片原文要求）：5 条定向用例先红后绿，红绿原文进证据。

## 2. 改动

| 文件 | 类型 | 行数 | 要点 |
| --- | --- | --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java` | 新增 | 246 | 缝本体：模式归一、目标服务由 host:port 判定、模板还原、`AuthorityContext.current()` 取授权、provider 签发 |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ServiceTicketsTest.java` | 新增 | 282 | 6 例：LEGACY 逐字一致 / AUTHORITY 走 provider / 无授权 403 / 未登记 403 / 装配缺失 500 / 模板按段匹配 |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java` | 修改 | +16 / −4 | `String ticket = ServiceTokens.issue(actor);` → `serviceTickets.ticket(actor, "skill", method, url)`；新增默认 LEGACY 字段 + `@Autowired` 设值注入。**构造签名未动** |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillClientsErrorTest.java` | 修改 | +20 / −0 | 只新增 1 例（缝真的接在 `SkillClients` 上：AUTHORITY 无授权时桩服务器零请求）；既有 6 例与断言一字未改 |
| `skill-registry/src/main/resources/application.yml` | 修改 | +5 / −0 | 仅新增 `agent.skill.service-ticket-mode: ${AGENT_SKILL_SERVICE_TICKET_MODE:LEGACY}` 一键 |

未动：`pom.xml`（零新增依赖）、任何迁移脚本、任何白名单、`common/authority/*` 与 `IamServiceTicketProvider`（零调用者，按指令不改）。

### 与卡片 "改动清单" 的对照

| 卡片要求 | 实现 |
| --- | --- |
| 新增 `ServiceTickets`，两态、模式非法启动即失败 | `ServiceTickets.java:91-104`；拼错模式名抛 `IllegalArgumentException("SERVICE_TICKET_MODE_INVALID: ...")` |
| `ticket(actor, caller, method, url)` | `ServiceTickets.java:121-136` |
| LEGACY 逐字沿用 `ServiceTokens.issue(actor)` | `ServiceTickets.java:122-125`，分支内直接 `return`，不读 `AuthorityContext`、不查目录 |
| AUTHORITY 取 `AuthorityContext.current()`，缺失 → `403 AUTHORITY_CONTEXT_MISSING` | `ServiceTickets.java:138-157` |
| AUTHORITY 用 target + method + template 查目录，未登记 → 既有 `403 SYS-AUTH-403` | `ServiceTickets.java:126-130`，直接调用 `ServiceRoutePolicy.require(...)`，不另造错误码 |
| 装配 `ServiceTicketProvider`，取 `issue(authority, call).value()` | `ServiceTickets.java:131-135` |
| `SkillClients` 调用点走缝，构造签名不变 | `SkillClients.java:19-29`（默认字段 + 设值注入）、`SkillClients.java:54`（调用点） |
| 配置 `agent.skill.service-ticket-mode` 默认 LEGACY，不新增其它键 | `application.yml:56-60` |
| 5 条用例 | 见 §3 对照表（6 条，多一条模板形状负例） |

## 3. 用例与卡片验收逐条对应

| 卡片要求的用例 | 位置 |
| --- | --- |
| `LEGACY` 模式与改动前逐字一致 | `ServiceTicketsTest.java:85-103`（`legacyModeUsesServiceTokens`）|
| `AUTHORITY` + 有授权：走 provider | `ServiceTicketsTest.java:105-132`（`authorityModeIssuesThroughProvider`）|
| `AUTHORITY` + 无授权：`403 AUTHORITY_CONTEXT_MISSING`，**不回落** | `ServiceTicketsTest.java:134-153`（`authorityModeWithoutAuthorityFails`；含"bind 后 close"这一"已退休 frame"场景）|
| 目录未登记：既有 `SYS-AUTH-403` | `ServiceTicketsTest.java:155-186`（`unregisteredRouteFails`；方法未登记 + host:port 不在 `agent.services.*` 两种）|
| 缝接上 `SkillClients`：AUTHORITY 无授权时 403 且下游零请求 | `SkillClientsErrorTest.java:207-224`（`authorityModeWithoutBoundContextFailsBeforeTransport`）|
| 额外：装配缺失是 500 而非 403 | `ServiceTicketsTest.java:188-199`（`providerMissingIsAssemblyError`）|
| 额外：模板形状按段匹配，不前缀命中 | `ServiceTicketsTest.java:202-221`（`templateMatchingIsSegmentedNotPrefix`）|

`LEGACY` 用例额外断言：`provider.calls` 为空（没走 provider）、claims 的 `iss/sub/tenant_id/aud/token_use` 与 `roles` 含 `SERVICE` 全部与改动前一致、且**故意不 bind** `AuthorityContext`（证明 LEGACY 不读授权上下文）。

## 4. 红

命令（仓根 `G:\207\项目\算法重组工具\代码\agent-platform`）：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -o -pl skill-registry test \
  -Dtest='ServiceTicketsTest,SkillClientsErrorTest' -DargLine="-Xmx1024m"
```

`docs/evidence/2026-10-28-G8c-ticket-seam/red.log`（原始字节为 cp936，`red-utf8.log` 为仅转码的同内容副本）：

```text
[INFO] --- compiler:3.14.0:compile (default-compile) @ skill-registry ---
[INFO] Nothing to compile - all classes are up to date.
...
[INFO] -------------------------------------------------------------
[ERROR] COMPILATION ERROR :
[INFO] -------------------------------------------------------------
[ERROR] /G:/207/项目/算法重组工具/代码/agent-platform/skill-registry/src/test/java/cn/agentplatform/skill/infra/ServiceTicketsTest.java:[89,21] cannot find symbol
  symbol:   class ServiceTickets
  location: class cn.agentplatform.skill.infra.ServiceTicketsTest
[ERROR] /G:/207/项目/算法重组工具/代码/agent-platform/skill-registry/src/test/java/cn/agentplatform/skill/infra/ServiceTicketsTest.java:[90,25] cannot find symbol
  symbol:   variable ServiceTickets
  location: class cn.agentplatform.skill.infra.ServiceTicketsTest
... （同类 12 条，覆盖 ServiceTicketsTest 的 89/90、109、138、159、191、205 行）
[ERROR] /G:/207/项目/算法重组工具/代码/agent-platform/skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillClientsErrorTest.java:[214,21] cannot find symbol
  symbol:   class ServiceTickets
[ERROR] /G:/207/项目/算法重组工具/代码/agent-platform/skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillClientsErrorTest.java:[215,25] cannot find symbol
  symbol:   variable ServiceTickets
[ERROR] /G:/207/项目/算法重组工具/代码/agent-platform/skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillClientsErrorTest.java:[222,43] cannot find symbol
  symbol:   variable ServiceTickets
[INFO] -------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] -------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.0:testCompile (default-testCompile) on project skill-registry: Compilation failure
[ERROR] Total time:  5.241 s
```

红因：`ServiceTickets` 类及其 `setServiceTickets` 还不存在（16 条 `cannot find symbol`）——即卡片要的"签发缝"此刻确实不在代码里。**没有**出现"断言失败"式的红，因此红本身不包含任何旧行为假设。

编码说明：首跑（`red-attempt1-encoding.log`）未设 `MAVEN_OPTS`，中文正文落进日志是乱码，仅作留档；`red.log` 是同一命令的重跑。

## 5. 绿

同一命令，`docs/evidence/2026-10-28-G8c-ticket-seam/green.log`（`green-utf8.log` 为转码副本）：

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running cn.agentplatform.skill.infra.ServiceTicketsTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.463 s -- in cn.agentplatform.skill.infra.ServiceTicketsTest
[INFO] Running cn.agentplatform.skill.infra.SkillClientsErrorTest
G8B_TICKET_FAILURE=403/AUTHORITY_CONTEXT_MISSING
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.797 s -- in cn.agentplatform.skill.infra.SkillClientsErrorTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  11.154 s
```

`Tests run: 13` = 6（新缝）+ 7（`SkillClients`，含既有 6 例 + 新接缝 1 例）。既有 `G8B_TICKET_FAILURE=403/AUTHORITY_CONTEXT_MISSING` 子进程场景仍原样通过。

补充：`testCompile` 每次都会编译模块内全部 63 个测试源文件，因此本次改动没有破坏任何既有测试的编译面（未跑模块全量测试，按指令只跑定向）。

## 6. 为什么 AUTHORITY 模式绝不回落 LEGACY

四条理由，前两条是正确性，后两条是可运维性：

1. **在 IAM 部署下，回落等于"在明确拒绝签发的地方偷偷签发"。** `ServiceTokens.issue` 的 IAM 门（`ServiceTokens.java:27-31`）不是配置疏漏，是"本部署不再由本服务自签"的唯一表达。`AUTHORITY` 分支里若在拿不到授权时 `return ServiceTokens.issue(actor)`，那么"这个请求没有可信授权"这条事实就被翻译成一张下游会当作合法机器票据接受的票——本地签名一次，绕过整条撤销/审计链。
2. **回落的票缺少 IAM 语义本身。** `ServiceTokens` 的 claims 只有 `iss/sub/tenant_id/aud/iat/exp/jti/token_use/roles/attributes`；IAM 侧的 `authorizationVersion`、`decisionId`、`delegation`、`operation` 根本不在票里（见 `VerifiedAuthority` 的字段）。用 LEGACY 票冒充 AUTHORITY 票，即使侥幸被接受，也是"没有决策依据的机器身份"。
3. **故障可见性。** G-8b 刚把"签发失败"从"下游不可用的 502"里拆出来；如果 AUTHORITY 回落，签发失败会变成一张能用的票，事故从"403 可见"退化成"越权却全绿"。这是本卡最不能接受的回归。
4. **错误分类要能被运维读懂。** 缺授权是 403（查授权/绑定），装配缺失是 500 `SERVICE_TICKET_PROVIDER_MISSING`（查容器），目录未登记是 403 `SYS-AUTH-403`（查目录）。回落会把这三类事故统一伪装成"没事"。

实现上的保证（可审查的形态）：

- `ServiceTickets.ticket` 的 AUTHORITY 分支**只**有一个出口：`provider.issue(authority, call).value()`；全文件 `ServiceTokens.issue` 只出现 2 次——第 124 行 LEGACY 分支的调用，和类注释第 38 行的说明文字，AUTHORITY 分支没有任何自签出口（`grep -n 'ServiceTokens\.issue' ServiceTickets.java` → `38: * ... { @code ServiceTokens.issue} ...` / `124: return ServiceTokens.issue(actor);`）。
- `mode` 只有两个字面量能通过构造：`LEGACY` / `AUTHORITY`；其它（拼错、空串之外的变体同样）在构造期 `IllegalArgumentException`，避免拼写错误静默变成 LEGACY。
- `AuthorityContext.current()` 的 **null** 与 **`403 AUTHORITY_CONTEXT_MISSING`** 两种"没有授权"的形态都被归一成同一个 403（`ServiceTickets.java:138-157`）；其它授权错误（跨线程、协议拒绝、验签失败）原码上抛，不吞、不改写。
- 测试把禁令写成了机器断言：`authorityModeWithoutAuthorityFails` 断言 provider 零调用，`authorityModeWithoutBoundContextFailsBeforeTransport` 断言桩服务器零请求——一旦有人加了回落，这两条会直接变红。

## 7. 模板匹配只用于签发票据、不用于判权

目录的裁决 API 是 exact 键：`ServiceRoutePolicy.require(caller, target, method, template)`。而 `ticket(...)` 拿到的是具体 URL（`.../internal/tool-calls/tc-8c/fact`），必须先还原成目录里的模板串（`/internal/tool-calls/{id}/fact`）才谈得上"登记过没有"。这条缝对模板的使用严格限定在**为 `require` 凑出键**这一件事上：

- `templateOf(caller, target, method, path)`（`ServiceTickets.java:165-181`）只做形状比较：段数相等、字面段相等、`{}` / `{id}` 段通配（`sameShape`，`ServiceTickets.java:182-193`）。它**不读** actor、roles、attributes、`VerifiedAuthority`，也**不返回**任何 allow/deny 结论——它只返回一个字符串。
- 命中 0 条 → 返回原始 path；命中多条（歧义）→ 也返回原始 path。原始 path 里带具体 id，必然不等于任何模板，于是 `require` 抛既有 `403 SYS-AUTH-403`。**歧义宁可失败，不猜一条去签票。**
- 于是"能签出票"当且仅当"`require` 在目录里命中了一行登记"——裁决点始终只有目录一处；模板匹配只是把 URL 摆成 `require` 认得的形状。
- `target` 只由 URL 的 `host:port` 决定（与 `agent.services.*` 同源，`ServiceTickets.java:159-163`），不从路径反推、不拿模板猜服务；判定不出就置空字符串，空名不可能命中目录行，必然 403。卡片里"由 host:port 段对应的服务名传入"正是这条规则的效果，只是把映射表作为第 4 个构造参数注入（见 §8 偏差 1）。

## 8. 偏差与实现取舍

1. **构造参数多了第 4 个（服务清单）**。卡片写的是 `(mode, 可空 provider, 可空 policy)`。仅凭这三者无法把 `http://127.0.0.1:18082` 变成 `engine`，而"target 从 host:port 判定"是卡片与设计同时要求的，所以加 `Map<String,String> serviceBaseUrls`（与 `application.yml` 的 `agent.services.*` 同键），容器入口从 `Environment` 读同一个键。同时保留 `(String)` 与 `(String, provider, policy)` 两个便捷重载，卡片声明的形态仍可用。
2. **接线用设值注入而非构造注入**：卡片明令不改构造签名（多地夹具手工 `new SkillClients()`），因此默认字段 `= new ServiceTickets(LEGACY, null, null, null)` + `@Autowired void setServiceTickets(...)`。未注入时行为与改动前完全一致。
3. **AUTHORITY 下 provider 缺失返回 500 而非 403**，卡片未规定这一格；取"装配错误不同于鉴权失败"（403 会把运维引向授权配置，真因是容器里没有签发器）。
4. **模板匹配的"歧义"处理（命中多行即失败）是一项额外保守选择**，卡片未要求；理由见 §7。
5. **`IamServiceTicketProvider` 未改、未接**：它是零调用者，卡片要求不碰；AUTHORITY 下默认容器会命中 500 分支（显式失败，非遗漏）。

## 9. 未覆盖 / 已知限制

1. **容器装配未做上下文测试**：`@Autowired` setter + `@Value("${agent.skill.service-ticket-mode:LEGACY}")` 的 Spring 分支只有编译期证据，没有 `@SpringBootTest`（`skill-registry` 现无此类基建，引入会拉 DB/Flyway）。风险：容器里注入是否按预期生效未被机器验证。
2. **AUTHORITY 生产链路无 provider 实现**：本卡只证明"取到 `ServiceTicketProvider` 时会用授权签"，真实 IAM 的 `ServiceTicket` 内容/格式/有效期未验证（`IamServiceTicketProvider` 仍零调用者）。
3. **真实 IAM 端到端未做**：`AuthorityContext` 由真实 IAM 决策结果绑定这一环属后续卡片；本卡把 `AuthorityContext.current()` 当作既有契约使用。
4. **目录覆盖只取了 skill→engine 的两行**（`POST /internal/tool-calls/{id}/fact`、`GET /internal/tool-calls/{id}` 的形状），skill→app/model/monitor 的行未逐一取数；同一算法，风险低但未测。
5. **LEGACY 一致性是结构性断言**：覆盖 claims 的 `iss/sub/tenant_id/aud/token_use/roles`，未逐字比较 `exp/iat/jti`（TTL 常量与签发函数均未改动，属间接证据）。
6. **`templateOf` 的复杂度**是 O(目录行数) 每请求（178 行规模可忽略），未做缓存；若目录膨胀需另议。
7. **未跑模块全量**（按指令）；已通过 `testCompile` 全量编译确认无编译面破坏。

## 10. 复现

```bash
cd G:/207/项目/算法重组工具/代码/agent-platform
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -o -pl skill-registry test \
  -Dtest='ServiceTicketsTest,SkillClientsErrorTest' -DargLine="-Xmx1024m"
```

日志：`red.log` / `red-attempt1-encoding.log` / `green.log`（原始 cp936）+ `red-utf8.log` / `green-utf8.log` / `red-attempt1-utf8.log`（仅转码）。
