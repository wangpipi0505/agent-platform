# G-8b —— skill 内部调用保留下游错误语义，票据失败不折叠

- 卡片：`文档/优化方案/派发卡片-B1-20261027.md` 卡片 19（G-8b）
- 仓库：`代码/agent-platform`，分支 `feat/v1.2-implementation`，基线 HEAD `492e5f2`
- 日期：2026-10-28
- 环境：`JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`；每条命令前 `source .tools/env-load.sh`；先 `mvn -B -ntp -o -pl admin-common install -DskipTests`（BUILD SUCCESS）。本用例只用 JDK `com.sun.net.httpserver` 桩，不连数据库。

## 1. 改动清单

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java` | 132 行（+81 / -8） | `request(...)` 拆出传输层与解码层：`RestClientResponseException` → `downstreamError(...)`（4xx 解析正文，能解出 `{code,message}` 就抛 `PlatformException(statusOf(code), code, message)`，否则 502）；其余异常（5xx、连接失败、超时）→ `unavailable()` 502；2xx 正文交给 `unwrap(...)`，`code != OK` 保留原码按 `statusOf` 映射状态，`data` 解包与返回结构不变；`ServiceTokens.issue` 移到传输 try 之外，票据失败不再进入 `catch (Exception)`；新增 `parseOrNull`/`messageOf`/`statusOf`/`unavailable` 私有方法 |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillClientsErrorTest.java` | 新增 279 行 | 6 个用例：404 `SKILL_NOT_FOUND`、403 `SCOPE_DENIED`（附 409/400 两个 `statusOf` 分支）、500→502、非 JSON 400→502、票据失败不折叠（子 JVM）、成功信封形状不变；桩为 JDK `HttpServer`，记录 `METHOD path Authorization` |
| `docs/evidence/2026-10-28-G8b-skill-clients-errors/` | 新增 | 本 README 与 `red.log`、`green.log`、`red-attempt1-env-setup.log`（`*.log` 被仓库 `.gitignore` 的 `**/*.log` 忽略，未提交） |

未改动：`SkillExecution`、适配器、`contracts/**`、迁移、`pom.xml`、yml、其它模块、既有测试；未新增依赖；未 `git add` / `git commit`。

## 2. 行为与实现要点

1. **错误分层**：`RestClientResponseException` 单独捕获（Spring `RestClient` 对 4xx/5xx 都会抛该异常，5xx 是其子类）；先按 4xx 判断，4xx 才尝试把正文解析成业务信封，5xx 一律 502。其它 `Exception`（`ResourceAccessException` 连接失败、读超时等）仍是 502，与改动前一致。
2. **4xx 保留原码**：正文能解析出非空、非 `OK` 的 `code` 时，抛 `PlatformException(statusOf(code), code, message)`；正文不是 JSON、没有 `code`、`code` 为空或 `OK` 时回到 `502 DEPENDENCY_UNAVAILABLE`（卡片行为 2 的"解析不出"分支）。
3. **2xx 信封**：`{code,message,data?}` 且 `code != OK` 时同样保留原码；`code` 为空/非文本视为协议破坏，返回 502（与改动前此类畸形信封被折成 502 的结果一致）；`OK` 或没有 `code` 时按 `data` 解包，`data` 缺省返回整个正文，形状不变。
4. **statusOf 映射**（大小写不敏感、子串匹配）：`*NOT_FOUND*` → 404；`*AUTH*`、`*SCOPE*`、`*PERMISSION*` → 403；`*CONFLICT*`、`*CHANGED*`、`*EXPIRED*` → 409；其余 → 400。匹配顺序固定为 404 → 403 → 409 → 400，避免 `SKILL_AUTH_NOT_FOUND` 这类码落到错误分支。
5. **票据失败不折叠**：`ServiceTokens.issue(actor)` 在传输 `try` 之前调用。`401/403` 授权上下文缺失（Y-3/Y-8 根因）与票据配置失败不再被 `catch (Exception)` 吃掉；`ServiceTokens.issue` 抛出的 `PlatformException` 原样到调用方。
6. **协议面不变**：URL、HTTP 方法、`Authorization: Bearer <ticket>` 头、`data` 解包行为都不变；用例 1 断言桩只收到一次 `POST /g8b Bearer ...`，用例 6 断言成功路径返回值不变。

## 3. 先红后绿证据

两轮都只跑卡片验收命令，未跑模块全量。原始日志见本目录 `red.log`、`green.log`。

```
cd "G:/207/项目/算法重组工具/代码/agent-platform"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
source .tools/env-load.sh
mvn -B -ntp -o -pl skill-registry test -Dtest='SkillClientsErrorTest' -DargLine="-Xmx1024m"
```

### 3.1 红（实现前，仅新增测试类）

```
[ERROR] Failures: 
[ERROR]   SkillClientsErrorTest.downstreamCodesKeepTheirOwnStatusInsteadOf502:128 
expected: "SCOPE_DENIED"
 but was: "DEPENDENCY_UNAVAILABLE"
[ERROR]   SkillClientsErrorTest.downstreamNotFoundKeepsCodeAndStatus:116 
expected: "SKILL_NOT_FOUND"
 but was: "DEPENDENCY_UNAVAILABLE"
[ERROR]   SkillClientsErrorTest.ticketIssuanceFailureIsNotFoldedIntoDependencyUnavailable:183 [Exception in thread "main" java.lang.AssertionError: ticket failure folded: status=502 code=DEPENDENCY_UNAVAILABLE
	at cn.agentplatform.skill.infra.SkillClientsErrorTest.main(SkillClientsErrorTest.java:241)
] 
expected: 0
 but was: 1
[INFO] 
[ERROR] Tests run: 6, Failures: 3, Errors: 0, Skipped: 0
```

失败点与卡片用例对应：① 404 `SKILL_NOT_FOUND`（第 116 行）、② 403 `SCOPE_DENIED`（第 128 行）、⑤ 票据失败（子进程打印 `status=502 code=DEPENDENCY_UNAVAILABLE`，退出码 1）。用例 ③（500→502）、④（非 JSON 400→502）、⑥（成功信封形状）在红轮即通过，属"行为不变"守卫，不因新实现才成立。

第一次红轮曾整体失败于子进程 `IllegalStateException: IAM_DEV_AUTH_FORBIDDEN`：`.tools/env-load.sh` 导出的 `AGENT_DEV_AUTH` 经 Spring 松散绑定成为 `agent.dev-auth=true`，IAM 校验先于票据签发拒绝。日志留在 `red-attempt1-env-setup.log`；测试在子进程属性里显式写 `agent.dev-auth=false` 后得到上面的红轮，该调整只影响测试夹具，不改变被测行为。

### 3.2 绿（实现后）

```
G8B_TICKET_FAILURE=403/AUTHORITY_CONTEXT_MISSING
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.110 s -- in cn.agentplatform.skill.infra.SkillClientsErrorTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 4. 第 5 条（票据失败不折叠）的实际做法

卡片允许"同 JVM 不可行时改为反射/桩注入并说明"。这里采用**子 JVM**，没有用反射改静态状态：

- 原因一：`ServiceTokens.registerDeployment(...)` 是 `cn.agentplatform.common` 包内可见，skill-registry 测试只能经 `SecurityConfiguration.bindDeployment(...)` 注册；IAM 一旦注册不可回退（`iam -> 非 iam` 直接 403），会污染同 JVM 后续用例与其它测试类。
- 原因二：反射改 `deploymentMode` 同样污染静态状态，且测不到真实 `issue(...)` 分支；子进程跑的正是生产代码路径。

子进程 `main("ticket-failure")` 用 `SecurityConfiguration.bindDeployment` 注册 IAM（10 个 `agent.security.authority.*` 必填项 + `agent.dev-auth=false`），然后调用真实 `new SkillClients().post("http://127.0.0.1:9/g8b-ticket-only", ...)`：

- 端口 9 不可达，正常传输必然失败；因此只要拿到的异常不是 `PlatformException(403, AUTHORITY_CONTEXT_MISSING)`，就说明票据失败被传输层/兜底折叠了。
- 子进程断言 `status == 403 && code == AUTHORITY_CONTEXT_MISSING`，打印 `G8B_TICKET_FAILURE=403/AUTHORITY_CONTEXT_MISSING` 后退出 0。
- 父进程断言：子进程在 45s 内结束、退出码 0、输出包含该 marker 且**不包含** `DEPENDENCY_UNAVAILABLE`。

红轮时子进程打印的正是 `ticket failure folded: status=502 code=DEPENDENCY_UNAVAILABLE`，证明该用例确实能区分折叠与不折叠。

## 5. 偏差（相对卡片）

1. **用例 5 用子 JVM 而非同 JVM**：理由见第 4 节；比卡片提到的"反射/桩注入"更接近生产路径，也不会污染静态部署状态。
2. **多一个用例 6（成功信封形状不变）**：卡片行为 5 要求"不改返回结构"，用 200 的 `{code:OK,data}` 与无 `data` 正文各断言一次；不计入卡片 5 条。
3. **2xx 的 `code` 为空/非文本 → 502**：卡片未规定畸形信封；取与改动前一致的保守解释（旧实现对此抛 `IllegalArgumentException` 后被折成 502），不抛空 code 的 400。
4. **4xx 的 `code` 为 `OK` → 502**：`OK` 不是业务错误码，按"解析不出业务错误"处理，避免把成功码当 4xx 业务失败回显。
5. **`message` 缺失/空白 → `"依赖返回失败"`**：卡片要求把 `message` 带出；缺失时沿用改动前的兜底文案，避免空消息。
6. **票据签发的非 `PlatformException` 失败（如密钥不足 32 字符的 `IllegalStateException`）也原样上抛**：`issue` 在传输 try 之外，这类失败同属"票据签发失败"，折成 502 会掩盖部署配置错误；卡片只点名 `PlatformException`，此处范围略宽，与"不得折叠"的目标一致。

## 6. 未覆盖项

- 未跑模块全量（卡片明确不跑）：只跑 `-Dtest='SkillClientsErrorTest'`，未回归其它测试类；仓库里有依赖真实 PG 的用例（`ENGINE_PG_TEST=true`），本环境未执行。
- 连接失败/读超时 → 502 没有单独新增用例：该路径是既有 `catch (Exception)` 分支；用例 3（500）覆盖了 5xx 走 `RestClientResponseException` 后的 502，连接类异常没有走到桩，未覆盖 60s 读超时。
- 4xx 正文可解析但 `Content-Type` 非 JSON 的响应：实现只按正文解析、不看 Content-Type，未单独造该组合的用例。
- `data` 显式为 `null` 的解包（`result.get("data")` 得 `NullNode`）未断言；与改动前同一行代码，行为不变。
- 未做真实 engine/app 下游的端到端错误映射，只按 `{code,message}` 信封契约用桩验证。

## 7. 回滚方式

改动未提交，回滚只需丢弃工作区改动：

```
git checkout -- skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java
rm skill-registry/src/test/java/cn/agentplatform/skill/infra/SkillClientsErrorTest.java
rm -rf docs/evidence/2026-10-28-G8b-skill-clients-errors
```

无迁移、无配置项、无新依赖，无需数据库回滚。
