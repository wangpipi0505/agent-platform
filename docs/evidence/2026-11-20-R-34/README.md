# R-34 gateway portal ingress /v1 用户面形状 —— 证据

日期：2026-11-20　分支：`r34-gateway-v1`（worktree wt-r34，基于 upgrade/20261103 @ 52efbfd9）
模块：platform-gateway　构建：`mvn -pl platform-gateway -am -DskipTests package` BUILD SUCCESS

## 结论先行

- **三处评审核实的修改点已逐字落地**（见 §1），本地测试 38 例全绿（RoutesTest 21 + GatewayIamPortalHttpTest 17，含 5 个新形状断言）。
- **但 DoD-4 的“USER 票 GET /v1/apps/... 200 翻转”被本地全链路 HTTP 测试实证为不可达**：三处修改落地后，门户票打 `/v1/apps/ops/sessions?mine=true` 仍 **403 SYS-AUTH-403**（钉子测试 `portalTicketOnV1AppsUserPlaneRemainsDeniedByTheBoundaryRoutePendingTheFlipCard` 通过即此实证）。根因是**先于本卡存在的边界形状限制**，R-26 证据 §5-1 已精确记载（见 §4）：`GatewayIngressBoundary.route()`（admin-common）只承认 RUN/SESSION/STATUS/EVENTS 四种受管形状，`GatewayController.proxy` 的 iam 分支对一切 `/v1/**` 先 `claim()`（要求 Purpose.RUN=POST /admin/apps/{code}/runs）。仅扩 ingress 注册形状不改这两处，GET /v1/apps/* 的 403 来源从 claim() 移到 ingress 过滤器的 route()，**可观测结果不变**。
- **E2 部署验证未完成**：工作窗口内 192.168.1.2 网络层不可达（ping 目标主机无法访问 + ssh 22 超时，多轮重试），scp/镜像重建/终判 curl 均无法执行，如实登记（见 §3）。新 gateway jar 已构建完成待部署。

## 1. 三处修改点（文件:行）

| # | 文件:行 | 修改 |
|---|---|---|
| 1 | `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamPortalConfiguration.java:200` | `gatewayPortalTicketSource.addUrlPatterns` → `("/admin/*", "/auth/session", "/v1/apps/*")` |
| 2 | 同文件 `:216` | `gatewayPortalIngress.addUrlPatterns` → `("/admin/*", "/auth/session", "/v1/apps/*")` |
| 3 | `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalTicketSourceFilter.java:41-43` | `portalSurface()` 硬编码增 `path.startsWith("/v1/apps/")`（javadoc 作用域描述同步修正） |

未改动：`admin-common/.../GatewayIngressBoundary.java`（route() #108-122）、`platform-gateway/.../GatewayController.java`（iam 分支 #67-86）、`compose-test.yaml` 全部段落、`Routes.java`（本卡不涉 Routes 登记变化）。

## 2. 测试统计（本地，JDK 21，mvn surefire）

- `RoutesTest`：21 例全绿（新增 `userPlaneV1AppShapesRemainRegisteredUnchangedByIngressShapeCard`：/v1/apps 用户面四族形状会话/任务/记忆/计划期望不变——Routes 本卡零改动；并断言 /v1/deploy、/v1/runs、/v1/sessions 家族登记维持既有边界）。
- `GatewayIamPortalHttpTest`：17 例全绿（新增 5 例）：
  1. `v1AppsUserPlaneIsInsideBothPortalIngressRegistrationsAndNothingElse`——两个 FilterRegistrationBean 的 URL 形状均含 `/v1/apps/*`，且不含 `/v1/deploy/*`、`/v1/runs/*`、`/v1/sessions/*`、`/v1/tasks/*`；
  2. `portalSurfaceShapeCoversTheV1AppsPrefixOnly`——portalSurface 单元判定（含 `/v1/appsx` 拼写近邻不入）；
  3. `queryStringTicketIsRejectedOnTheV1AppsUserPlane`——`/v1/apps/ops/sessions?access_token=` → 400 PORTAL_TICKET_SOURCE_INVALID；
  4. `queryStringTicketIsNotPortalRejectedOutsideTheV1AppsUserPlane`——`/v1/runs/...?access_token=`（无票头）→ 401 SYS-AUTH-401（非 400，证明负向面未被吞进票源判定）；
  5. `portalTicketOnV1AppsUserPlaneRemainsDeniedByTheBoundaryRoutePendingTheFlipCard`——门户票 GET `/v1/apps/ops/sessions?mine=true` → **403**（残余缺口实证钉子，注释指明后续卡落地时翻 200）。
- 汇总：`Tests run: 38, Failures: 0, Errors: 0, Skipped: 0`。

## 3. DoD 逐条实录

| DoD | 判据 | 结果 |
|---|---|---|
| 1 RoutesTest 扩断言 + ingress 形状断言 | 新增 1 + 5 例 | ✅ 38/38 全绿（§2） |
| 2 构建新 gateway jar | `mvn -B -ntp -pl platform-gateway -am -DskipTests package`（本地仓 .tools/m2） | ✅ BUILD SUCCESS，`platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar`（37,887,658 B，2026-11-20 17:43） |
| 3 E2 部署（scp→docker build→tag/push→compose up -d --force-recreate platform-gateway） | 192.168.1.2 | ❌ **受阻**：`ping 192.168.1.2`＝“无法访问目标主机”、`ssh root@192.168.1.2` 22 端口超时（工作窗口内 2026-11-20 17:38 起多轮重试含 60s 间隔循环）。jar 与部署命令已备好，服务器恢复即可执行 |
| 4 终判（USER 200 / ADMIN 200 / 无票 401） | 部署级 | ❌ 未执行（依赖 DoD-3）；**且本地全链路实证表明三处修改不产生该翻转**：门户票 GET /v1/apps/.../sessions?mine=true = 403（§2 钉子测试⑤）。R-26 基线 403 的成因（route()/claim()）未变，预判 E2 部署后同为 403 |
| 5 SEC-01 抽查（USER 票打 /admin/* 仍 403） | 部署级 | ❌ 未执行（依赖 DoD-3）；本地层面 `/admin/*` 形状与链序零改动（本卡只增 /v1/apps/*），无放大机制 |
| 6 证据归档 | 本 README + 服务器 `/home/agent-platform/evidence/R-34/` | ⚠️ 本 README 完成；服务器侧留痕因 DoD-3 受阻未落，恢复后补 |

## 4. 核心发现（评审三处修改点 vs DoD-4 翻转的缺口）

1. R-26 证据（`docs/evidence/2026-11-20-R-26/README.md` §5-1）已精确记载：E2 gateway（iam 模式）下 **GET /v1/** 必然 403 与票据有效性无关**，成因＝`GatewayIngressBoundary.route()`（admin-common，#108-122）只承认 RUN/SESSION/STATUS/EVENTS 四形状 + `GatewayController.proxy` iam 分支（#67-86）对一切 `/v1/**` 先 `claim()`（要求 Purpose.RUN）。
2. 本卡三处修改只改“票源判定 + ingress 过滤器注册形状”（均在 platform-gateway/infra），**未触及上述两处**。落地后请求链：票源过滤器（400 判定）→ 安全链 2（验签通过）→ ingress 过滤器（route() 对 GET /v1/apps/* `throw denied()`）→ 403 SYS-AUTH-403。与改前（无票源判定 → 安全链通过 → GatewayController.claim() 无 Proof → 403）可观测结果一致。
3. 翻转 200 需要（超出本卡三处修改点与“只动 platform-gateway”红线的部分如实上报待评审决断）：
   - `GatewayIngressBoundary.route()`（admin-common）新增用户面 Purpose（GET/POST /v1/apps/{code}/(sessions|tasks|memories)...，需允许 query string 如 `mine=true`——既有 RUN Purpose 禁 query string）；
   - `GatewayController.proxy` iam 分支为该 Purpose 提供 engine 转发与用户票→服务票换发（authority exchange），engine 侧归属核验已有（R-26 记载 mine 语义）。
4. 本卡按“逐字照做”纪律完成三处修改点并留实证钉子测试，未越权扩大改动面。

## 5. 放行面清单（本卡后 portal ingress 形状）

**放行（进入票源判定 + ingress 边界注册形状）**：
- `/admin/*`（既有）
- `/auth/session`（既有）
- `/v1/apps/*`（本卡新增——用户面：/v1/apps/{code}/sessions…（会话）、/v1/apps/{code}/tasks（任务列表）、/v1/apps/{code}/memories…（记忆）、/v1/apps/{code}/input-schema、/v1/apps/{code}/experience）

**明确不放行（评审范围纪律，均已在断言中钉住）**：
- `/v1/deploy/*`（非用户面；Routes 仅登记 resource-manifest 单入口）
- `/v1/runs/*`（非用户面）
- `/v1/sessions/*`（Routes.java:36 明确该入口不存在——放它是无效扩大面）
- `/v1/tasks/*`（任务详情/事件/计划/执行前缀——评审字面形状仅 `/v1/apps/*`，servlet 通配不含它；如实登记为形状边界）

**如实登记的选择**：
- `/v1/sessions/{id}/subagents` 精确白名单**未加**（测试无需：该入口是服务面形状，Routes 已登记而 portal ingress 形状不含 /v1/sessions 家族；加它属无效扩大面）。
- 修订说明：三处修改点落地后 `/v1/apps/*` 上的可观测行为（403）与改前一致，本卡实际产生行为差异的只有两点——①`/v1/apps/*` 上 `?access_token=` 由“进安全链 401”变为“票源判定 400”（测试③）；②该前缀纳入 portal 票源/边界形状登记，为后续翻转卡提供形状基座。

## 6. 工件与命令

- 分支 commit：见 `git log r34-gateway-v1`（本卡不推送）。
- 构建：`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot`；`mvn -B -ntp -pl platform-gateway -am -DskipTests -Dmaven.repo.local=<repo>/.tools/m2 package`。
- 测试：`mvn -B -ntp -pl platform-gateway -Dtest='RoutesTest,GatewayIamPortalHttpTest' -Dsurefire.failIfNoSpecifiedTests=false test`。
- 部署命令（已备好，待服务器恢复）：
  1. `scp platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar root@192.168.1.2:/home/agent-platform/build/platform-gateway.jar`
  2. 服务器 `cd /home/agent-platform && docker build -f deploy/Dockerfile.backend --build-arg SERVICE=platform-gateway --build-arg JAVA_BUILD_IMAGE=eclipse-temurin:21-jdk-jammy --build-arg JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy -t agent-platform-v12/platform-gateway:0.1.0 .`
  3. `docker tag agent-platform-v12/platform-gateway:0.1.0 127.0.0.1:5000/agent-platform/platform-gateway:0.1.0 && docker push 127.0.0.1:5000/agent-platform/platform-gateway:0.1.0`
  4. `docker compose --env-file .env.test -f compose-test.yaml up -d --force-recreate platform-gateway`
- 终判命令（已备好）：authority `POST http://127.0.0.1:18086/test/tickets` 铸 USER/ADMIN 票 → `curl http://127.0.0.1:18090/v1/apps/satellite-planning-assistant/sessions?mine=true`（USER/ADMIN/无票）+ USER 票打 `/admin/apps` 对照。
