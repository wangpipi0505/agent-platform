# G-9 路由目录漂移校验（`agent-service-authority.json` ↔ Spring handler）

## 1. 问题与动机

`agent-service-authority.json` 靠手改，已两次漂移：Y-1 缺 `POST /internal/runs/{id}/wake`，Y-1b 缺引擎控制台 2 条内部入口。两次都是网关链路夹具启动时才抛 `ROUTE_COVERAGE_MISSING`，且一次只暴露一条，最后靠一次性脚本人工比对才发现。现状：6 服务共 **197 个 handler / 目录 178 行 / 服务票据面 0 缺行**，应把这个比对固化成构建期检查，而不是等夹具炸。

## 2. 判定口径与判权证据

| 结果 | 触发 | 处置 |
| --- | --- | --- |
| MISSING | `/internal/**` 有 handler、目录无行、不在排除表 | **阻断**（退出 1） |
| PORTAL_SURFACE | `/admin/**` 有 handler、目录无行 | 仅告警，逐条列出 |
| STALE | 目录有行、无 handler（代理/下线/框架 handler） | 仅告警 |
| EXCLUDED | 命中排除表 | 不阻断，但 `reason` 为空直接失败 |

三类端点的判权依据：门户面 `/admin/**` 只查会话 + portal scope 且拒绝 `token_use=integration`（`admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java:182-196`）；服务票据面 `/internal/**` 查 `ROLE_SERVICE`（同文件 `:197-202`）；权威签发 `POST /internal/iam/integration-policy/resolve` 凭据是 policy-request JWT（`admin-common/src/main/java/cn/agentplatform/common/authority/PolicyRequestVerifier.java:13`），且网关测试断言服务票据不得命中（`platform-gateway/src/test/java/cn/agentplatform/gateway/domain/IntegrationPolicyTest.java:127`），故进排除表而非补行。

## 3. 当前全量结果

`node scripts/verify-routes.mjs` → 退出 0：

```
合计：handler 197 / 目录行 178 / MISSING 0 / PORTAL_SURFACE 25 / EXCLUDED 1 / STALE 5
```

| 服务 | handler | 目录行 | MISSING | PORTAL | 排除 | STALE |
| --- | --- | --- | --- | --- | --- | --- |
| app | 45 | 42 | 0 | 4 | 1 | 1 |
| engine | 55 | 56 | 0 | 0 | 0 | 1 |
| skill | 44 | 34 | 0 | 12 | 0 | 1 |
| monitor | 20 | 16 | 0 | 5 | 0 | 1 |
| model | 26 | 23 | 0 | 4 | 0 | 1 |
| gateway | 7 | 7 | 0 | 0 | 0 | 0 |

PORTAL_SURFACE 25 条按服务：app 4（publish-waivers 3 + `/admin/system/status`）、skill 12（`/admin/artifacts` + `/admin/services` 10 条 + service-probe）、monitor 5（`/admin/operations/*`）、model 4（`/admin/model-health*` + `/admin/model-entries/{}/test`）；全部落在网关 `Routes.java` 可转发前缀内。STALE 5 条均为 `GET /v3/api-docs`（springdoc 条件注册，文本扫描看不到）。ENGINE 两条历史缺行（Y-1 wake、Y-1b 控制台入口）已补齐，现 0 MISSING，wake 的全限定注解已被解析器识别。

## 4. 接入方式与取舍

`scripts/verify-contracts.mjs` 末尾 `import {verifyRoutes}`：仅 `blocking` 时 throw（打印缺行明细 + 修复指引），告警走 console；原有 v1.2 快照、四契约哈希、pom/import 边界检查与退出码语义不变，`test.ps1` 第一步即可拦住。

- **为什么只 coverage `/internal/**`**：目录是服务票据的能力面。门户面判权不查票据（`SecurityConfiguration:182-196`），若也阻断，团队会"为过检查补 delegated 行"，而 `TrustedJwtDecoder.java:106-118` 按 `mode/caller/target/action` 授权——补行等于开能力面，比漏检更危险。门户面因此降级为告警 + 清单，`--strict-portal` 可一键收紧。
- **为什么排除表必须有 reason**：它是唯一"合法缺口"出口，空 reason 直接失败，防止用白名单掩盖真漂移。
- **为什么现在做**：目录目前真正的消费者是 `RouteCoverageVerifier`（测试上下文启动期，生产未装配），漂移成本是夹具整体失败而非线上 403；等 `ServiceAuthorization.mappedInterceptor()` 真正注册到生产后，本脚本从预防性检查变为硬前置。
- 未用 `--service` 白名单：按新口径 blocking 已为 false，全量接入即可（白名单会放过其他服务的真漂移）；`--service` 仅留给局部排查。

## 5. 未覆盖

解析是文本级而非 AST：常量拼接/静态导入的 `RequestMethod`/自定义组合注解求值不了；类级 `@RequestMapping` 多路径只取第一个；只扫 `**/api/*.java`（api 外 mapping 仅告警）；不校验 `action` 与网关实际签发的一致性；其他服务后续新增漂移只能靠跑脚本发现（`/internal/**` 已由构建期拦截，门户面待补行后开 `--strict-portal`）。
