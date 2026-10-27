# X-10 唤醒端点接线（派发卡片 13）证据

日期：2026-10-27 ｜ 分支 `feat/v1.2-implementation` ｜ 起点 HEAD `c3dfa62` ｜ 未提交（由编排者按泳道提交）

## 1. 目的

算法回调把外部事实交给引擎后，引擎的派发队列仍要等满既有退避才继续。X-10 让 skill 在
"事实已被引擎采纳"之后主动调用已存在但此前无人可调的
`POST /internal/runs/{id}/wake`：

- 唤醒只是调度提示（提前一次派发时间），事实判定仍由引擎 `resumeExternalFacts` 完成；
- skill 是唯一被授予该入口的服务（路由目录从 `disabled` 改为 `delegated`/`caller=skill`），
  但该入口仍不进入对外集成面（`integrationAllowed=false`）；
- 调用是 best-effort：失败只记日志，不改变回调结论、不回滚任何已持久化事实。

## 2. 改动文件

| 文件 | 行 | 改动 |
| --- | --- | --- |
| `admin-common/src/main/resources/agent-service-authority.json` | 2128、2131、2134–2135 | `caller: null→"skill"`；`mode: "disabled"→"delegated"`；`callerSource` 指向 `SkillCallbacks.java:355`；`reason` 换成定稿理由。`integrationAllowed` 保持 `false`，其余行未动 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/RunWakeCatalogTest.java` | 8–13、18、28–30 | 类注释改写为"只授予 skill 服务"；用例更名 `wakeEndpointIsDelegatedToSkillOnly`；断言 `mode=delegated`、`caller=skill`、`integrationAllowed=false`、`action=run.wake` |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCallbacks.java` | 11–12、29、186–188、305、313、344、348–366 | 加 slf4j 日志；`store(...)` 由 `void` 改为返回验收结论（`tx.execute`）；采纳路径在事实落库后调用 `wakeEngine(...)`；新增 14 行 `wakeEngine`（只对 `STORED` 唤醒，异常吞掉只 warn） |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillCallbackWakeTest.java` | 新增 447 行 | 真实 PG + 真实服务票据 + 桩 HTTP 引擎：断言唤醒路径/方法/正文两键/票据；500 时回调仍成功；重投与 SUPERSEDED 不重复唤醒 |

未改：`RunWakeService`、`RunController`、任何 `engine-runtime` 文件、`SkillExecution`/`SkillAdapters`/
`SkillCatalog`/`SkillRules`、`Process*`、`pom.xml`、迁移、`web/**`、`contracts/**`。未执行任何 `git add`。

## 3. 实现要点

时序（`SkillCallbacks.receive` 成功路径）：

1. 验签、契约/绑定比对、guard、`current(call,bound)`、`claim(...)` 均与既有实现一致；
2. 读取外部结果并构造 envelope；
3. `store(...)`：在一个事务里做"租约校验→结果分类（STORED/LATE/SUPERSEDED）→写
   `skill.execution_fact`→`finishOwned`"，现在把分类结论返回给调用方；
4. 事务提交**之后**才 `wakeEngine(event, bound, outcome)`：只有 `STORED`（本次投递刚刚把事实写进
   `execution_fact`）才发 `POST {engineUrl}/internal/runs/{run_id}/wake`，正文恰为
   `{"requestId":"<回调 eventId>","source":"SKILL_CALLBACK"}`；`LATE`/`SUPERSEDED` 不发；
5. 最后 `response(call,event)` 照旧构造回调响应。

编排者复核补丁（2026-10-27 23:59）：`V307` 把 `external_job_binding.run_id` 加成**可空**列，
`String.valueOf(bound.get("run_id"))` 在旧绑定上会产出字面量 `"null"` 并 POST 到
`/internal/runs/null/wake`。已在 `wakeEngine` 开头加三行：`run_id` 为 null 或空白直接返回，
注释说明这是"链接前创建的绑定"而非异常路径。补丁后 `SkillCallbackWakeTest` 仍 3/3。

失败处理：

- `SkillClients.post` 本身自带服务票据（`ServiceTokens.issue(Actor.current())`）并把一切 IO/非 2xx
  统一包成 `PlatformException`；`wakeEngine` 捕获所有 `RuntimeException` 只打 `log.warn`，
  因此唤醒失败既不会把已成功的回执改成失败，也不会触发任何回滚（此时事务早已提交）。
- 请求体只有两键，没有 run 快照或结果正文，避免唤醒通道成为第二事实来源。
- 幂等：重复投递命中 receipt 早返回、终态后的新事件落 `SUPERSEDED`，两者都不会再次唤醒。

## 4. 测试命令与原始结果

先红（改 JSON 前，`RunWakeCatalogTest` 新断言）：

```
mvn -B -ntp -pl admin-common test -Dtest=RunWakeCatalogTest \
  -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.913 s <<< FAILURE! -- in cn.agentplatform.common.authority.RunWakeCatalogTest
[ERROR]   RunWakeCatalogTest.wakeEndpointIsDelegatedToSkillOnly:28
```

后绿：

```
mvn -B -ntp -pl admin-common test -Dtest='RunWakeCatalogTest,ServiceAuthorizationTest' \
  -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.204 s -- in cn.agentplatform.common.authority.RunWakeCatalogTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.619 s -- in cn.agentplatform.common.authority.ServiceAuthorizationTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（跑 skill 侧前按约束执行过一次共享模块安装：`mvn -pl admin-common install -DskipTests` → BUILD SUCCESS，
使 skill-registry 的上下文读到新目录行。）

```
source .tools/env-load.sh
mvn -B -ntp -pl skill-registry test -Dtest='SkillCallbackWakeTest' \
  -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.008 s -- in cn.agentplatform.skill.application.SkillCallbackWakeTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

三个用例分别覆盖：`adoptedFactSendsOneSignedWake`（桩引擎收到一次 `POST
/internal/runs/r_callback/wake`，正文键集合恰为 `{requestId,source}`、`requestId==eventId`、
`source==SKILL_CALLBACK`，`Authorization: Bearer` 票据解出的 claims 带 `token_use=service`/
`sub=owner`/`tenant_id`/`SERVICE` 角色）；`wakeFailureKeepsCallbackSuccessful`（桩返回 503 时
`outcome=STORED`、`execution_fact.status=SUCCEEDED`、receipt 仍 `STORED`）；`replayAndSupersededDeliveryDoNotWakeAgain`
（同 eventId 重投与终态后新事件都不再唤醒）。

目录/边界校验：

```
node scripts/verify-contracts.mjs
G-9 路由目录：服务票据面 0 MISSING；门户面缺行告警 26 条、STALE 5 条（均不阻断，清单见 docs/evidence/2026-10-27-G9-route-drift/README.md）
v1.2快照、四契约原文哈希与七模块依赖边界通过
```

## 5. 未覆盖 / 风险

- **skill 侧"先红"未单独留档**：卡片要求的红点是目录断言（已留原始输出）。新增的
  `wakeEngine` 是纯增量调用，改前代码不存在唤醒路径，`SkillCallbackWakeTest` 三个用例在改前必然
  失败（`wakes.size()==0`）；本轮出于时间考虑直接用最终实现跑绿，未回滚一次取红输出。
- **既有真实库 HTTP 用例在本机当前环境是红的**（与本次改动无关）：按卡片模板直接跑
  `mvn -pl skill-registry test -Dtest=CallbackHttpTest#callbackContractRetainsFrozenSecretReference`
  会以 `IllegalStateException: SECURITY_MODE_REQUIRED`（`SecurityConfiguration.bindDeployment`）
  失败，因为这些用例的测试上下文只注入了 `agent.security.jwt-secret`，而仓储测试用的 `.env`
  没有 `agent.security.mode`。新测试在属性源里显式补了
  `agent.security.mode=dev-hmac`/`agent.dev-auth=true`/`server.address=127.0.0.1` 才可启动。
- 唤醒不校验 `RunWakeService` 的 `woken` 返回值（例如只读重放、终态 run 会返回 `woken=false`）；
  按 best-effort 契约这属于"提示未被采纳"，无需重试或降级，故不解析响应体。
- 未测：引擎返回成功但实际未提前派发（需要 engine-runtime 侧联调）；唤醒与调度的竞态；多实例
  skill 并发投递时的唤醒风暴（`STORED` 只可能由唯一一次 `claim` 产生，未做压测）。
- 未跑模块全量测试（同模块有既有失败与 fork 内存坑，按约束只跑定向用例）。
