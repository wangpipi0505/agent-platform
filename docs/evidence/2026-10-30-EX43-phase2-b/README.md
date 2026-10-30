# EX-43 第二阶段 B 组：platform-gateway IAM 装配（gateway 专属）

分支 `feat/v1.2-implementation`，HEAD `0acfa16d`。只改 `platform-gateway/` 与本目录；未提交、未启动服务、未动 admin-common/其它服务/contracts/scripts/pom/迁移/catalog。

## 1. 改动文件清单

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamPortalConfiguration.java` | 224（新增） | 双链装配、portal 域配置约束、入口边界与过滤器注册 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalTicketSourceFilter.java` | 66（新增） | 只收 `Authorization` 头；`?access_token=` 显式 400 |
| `platform-gateway/src/main/resources/application.yml` | +41/−0 | `agent.security.{mode,service-id,portal.*,authority.*}`，值全部走环境变量占位符 |
| `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/GatewayIamPortalHttpTest.java` | 276（新增） | 12 条用例，真实端口启动 `GatewayApplication` |

## 2. 测试证据（原样粘贴首行与 BUILD 行）

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.100 s -- in cn.agentplatform.gateway.infra.GatewayIamPortalHttpTest
[INFO] BUILD SUCCESS
```

命令：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot" && mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl platform-gateway test -Dtest='GatewayIamPortalHttpTest'`

覆盖点：链 1（`ServiceTrustChain`，order −100，只匹配 `/internal/**`）与链 2（通用链）各恰一条且 matcher 不重叠；合法 portal 票过 `/admin/**`（503 `PUBLISHED_RUN_UNAVAILABLE`，即已过边界到控制器）；缺票 401；audience 不符 401；`token_use=user` 但服务 audience 401；`/auth/session` 合法票 200 + `Cache-Control: no-store`、缺票 401；portal 票进 `/internal/**` 401；`/auth/dev-token` 404；`?access_token=`（带票/不带票）均 400；`CoverageGate.missingInternal("gateway",…)` 为空、入口拦截器恰 1 个。

## 3. 红证据（每条变异 → 报错原文 → 已还原）

1. 变异：`PortalTicketSourceFilter` 的 `if (request.getParameterMap().containsKey(QUERY_TICKET))` 改成 `if (false)`（即"忽略"查询参数票）。
   `expected: 400` / `but was: 401`（`queryStringTicketIsRejectedEvenWithoutAnAuthorizationHeader`）。已还原。
2. 变异：`application.yml` 的 `attribute-allowlist` 默认值追加 `bizSystemCode`（但不给取值）→ 键集与 `attribute-value-allowlist` 不等。
   `java.lang.IllegalStateException: PORTAL_CEILING_INVALID`；`GatewayIamPortalHttpTest.boot:64 ? IllegalState PORTAL_CEILING_INVALID`。已还原（= fail-closed：声明即强制）。
3. 变异：删掉 `gatewayInternalServiceChain` 上的 `@Bean`（只留通用链）。
   `[exactly one /internal chain]` / `Expected size: 1 but was: 0 in:`（`internalSurfaceRunsOnTheServiceTrustChainAndPortalSurfaceOnTheGeneralChain:139`）。已还原。

## 4. 偏差与未覆盖

- **配置前缀偏差**：任务写 `agent.authority.portal.*`，基座实际前缀是 `agent.security`（`agent.security.portal.*`、`agent.security.authority.*`）。按基座装配，未改基座。
- **`attribute-allowlist` 取值偏差（重要）**：`AuthorityProperties.validate` 只约束"非空 + 键集与 `attribute-value-allowlist` 逐键相等 + `bizSystemCode`/`userId` 出现时各恰一值"，并不强制两者必含；且 `ClaimCeiling` 是"声明即强制"——白名单声明的属性键必须出现在票里，否则 403。参考 IdP 夹具默认只签发 `region`/`dataLabels`，仓库默认钉死 ownership 两键会让所有 portal 请求 403，故仓库默认值为 `region,dataLabels`，ownership 两键由部署用 `SPRING_APPLICATION_JSON` 追加（`{"agent.security.portal":{"attribute-allowlist":"region,dataLabels,bizSystemCode,userId","attribute-value-allowlist":{"bizSystemCode":["…"],"userId":["…"]}}}`）。不能用环境变量/点号记法：`Map` 键会被宽松绑定成小写，键集不等即启动失败（已用红证据 2 证明是硬失败）。
- **SSE 拒绝方式：选 400**（`PORTAL_TICKET_SOURCE_INVALID`，`Cache-Control: no-store`），由排在安全链**之前**的 `PortalTicketSourceFilter` 判定：这是"请求形态错"，不是"票无效"，401 专留"没有可用票"，运维可从状态码区分；两条测试（带头/不带头）钉住。
- **未覆盖（1）SSE 合法订阅**：带头票的 `POST /admin/runs/{id}/events` 在本装配下返回 403——入口边界的 EVENTS claim 依赖 async 交换装配（`RunEventsProxy` 本轮未接线），故 `503 RUN_EVENTS_UNAVAILABLE` 与流式成功两条路径都未验证；本阶段只闭环"票来源规则 + 拒绝方式"。
- **未覆盖（2）真 IAM 联调**：用 `LoopbackAuthority` 夹具 + 本机真实 HTTP 端口，未连 authority-service 进程。
- **未覆盖（3）全量回归**：按约束只跑本测试类；本模块无既有测试启动 `GatewayApplication`，故未见回归面。
- **目录行一致性**：`CoverageGate.missingInternal("gateway", new ServiceRoutePolicy(), mappings)` 为空；`admin-common/src/main/resources/agent-service-authority.json`（184 行）中 gateway 行 0、`/internal` 行 0，gateway 当前也无 `/internal` handler，故**无缺行可列**，该文件未改。
- **密钥/租户值**：`application.yml` 只有 `${ENV:}` 占位符与 `env:AGENT_SECRET_IAM_GATEWAY_CLIENT` 引用，仓库无任何真实 issuer/audience/属性值；`portal.enabled=false` 时这些空值不参与校验（回滚=关开关）。

## 5. 结论

**仅单测覆盖**。依据：真实端口启动 `GatewayApplication` 的 12 条用例全绿（双链、portal 配置自校验、dev-token 404、portal 面回归、票来源拒绝），但 SSE 事件面仍未闭环（403，需接线 `RunEventsProxy` 与 async 交换），且未对真 IAM 做端到端；`/internal/**` 暂无 handler。因此不能判"iam 可启动"的生产级结论，也不属"仍阻塞"——装配本身可启动、可测，剩余是 SSE 事件面接线。
