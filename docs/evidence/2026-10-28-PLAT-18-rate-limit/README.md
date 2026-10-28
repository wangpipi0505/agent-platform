# PLAT-18 T18.1 剩余段：业务系统入口按集成凭据限流

## 1. 范围与依据

- 任务：T18.1 三段中 `X-User-*` 注入（T12b `b29b3a8`）与集成审计关联（T12a `c94f2f1` lifecycle_log）已由他卡落地；本卡只补**限流**——`/integration` 业务入口按 ApiKey（credential）维度滑动窗口限流，开关默认关，超限拒绝可读，超限事件写既有日志审计路径。
- 依据：
  - 方案 §4.18（PLAT-18，1042–1076 行）："平台增加按凭据/用户/租户的限流与配额；超限返回 QUOTA，不拖垮主业务"；
  - 计划 §6.2（错误信封 `code+userMessage+traceId`、开关纪律 `agent.<domain>.<feature>-enabled` 默认值入 yml、不打印敏感信息、错误日志带 traceId）、§6.4-3（进程级静态态导致的隔离运行差异必须标注）；
  - 台账 §2 PLAT-18 行、§3 T18.1 行（2026-10-28 派发剩余段）。
- 入口链路探明（实现前提）：仓内没有字面 `/integration/**` 路由；业务系统入口实为三条，全部由集成凭据驱动：
  1. `POST /embed/ticket`（ApiKey 出票，`IntegrationController` → `IntegrationAccess.embedTicket`）；
  2. `/v1/**` 直连 `Authorization: ApiKey <id>:<secret>`（`SecurityConfiguration` 过滤器 → `IntegrationAccess.authenticate()` → `ticket()` 出票）；
  3. `/v1/**` 票据 Bearer 代理（`GatewayController.proxy()` → `IntegrationAccess.check()`，凭据取自已验 JWT 的 `credential_id`）。
- 改动文件（全部在允许白名单内）：
  - `platform-gateway/src/main/java/cn/agentplatform/gateway/domain/IntegrationRateLimit.java`（新增）：进程内按凭据的滑动窗口日志限流器，纯 domain 无 Spring 依赖；参考 engine `RunQuota` 的"窗口内已计量用量≥上限即拒绝"口径，**不跨模块复用代码**（网关无每请求落库计量，独立轻量实现，不引依赖）；
  - `platform-gateway/src/main/java/cn/agentplatform/gateway/application/IntegrationAccess.java`：挂三个计数点——出票成功计数（`ticket(..., CredentialSink)` 回调，被拒流量不消耗配额）、直连 ApiKey 请求打标（`authenticate` 内计数后写请求属性）、票据代理计数（`check()` 凭标记去重）——保证**一次 HTTP 请求恰好计一次**；新增 `@Value` 三参注入与 `rateLimit()` 测试/装配缝；密钥只留脱敏尾 4 位（`tail4`）；
  - `platform-gateway/src/main/java/cn/agentplatform/gateway/api/IntegrationController.java`：`/embed/ticket` 改走 `embedTicket`（出票成功即计数）；
  - `platform-gateway/src/main/resources/application.yml`：`agent.integration.rate-limit-enabled: false`（默认关）、`agent.integration.rate-limit.window-seconds: 60`、`max-requests: 0`（0=不限）；
  - `platform-gateway/src/test/java/cn/agentplatform/gateway/IntegrationRateLimitTest.java`（新增）：6 个失败路径用例（红批先写）。
- 错误码定案：429 `INTEGRATION_RATE_LIMITED`。方案 §4.18 字面写"超限返回 QUOTA"，但 engine `RunQuota` 已占用 `QUOTA` 表达 **token 配额**（运行受理闸门）；按计划 §6.2-2"新增错误须写清触发条件"分码登记：本码=**入口请求速率**超限，与 token 配额的处置路径不同（前者等窗口滑出重试，后者联系管理员调配额）。触发条件已写入类注释。

## 2. 运行环境

- JDK：Eclipse Adoptium jdk-21.0.12.8-hotspot（`JAVA_HOME` 导出，见命令原文）。
- PG 测试库：经 `.tools/env-load.sh` 载入 `.env` 并导出 `ENGINE_PG_TEST=true`（仅 GatewayRunStatusChainPostgresTest / GatewayPublishedChainPostgresTest 两个 PG 类用到；本卡无迁移、无 DDL）。
- 模块：仅 `platform-gateway`（`-pl platform-gateway`，依赖走本地仓 jar）；未跑模块全量。
- 测试上下文显式部署输入：新测试类以 `agent.security.mode=dev-hmac + agent.dev-auth=true + agent.security.service-id=gateway`（@DynamicPropertySource）自含装配——这正是集成入口所在 SecurityConfiguration 旧票链的生产形态；未依赖 shell 环境变量。
- 并行泳道观察：本卡执行期间另一泳道提交了 `4b26625`（T14.1，仅 engine-runtime 与其证据目录）；本卡所有 `git stash` 均**限定 `platform-gateway` 路径**（`git stash push -u -- platform-gateway/...`），未触碰、未暂存、未恢复他人任何文件；工作树中 engine-runtime 的他人改动原样保留。

## 3. 执行的命令原文

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
source .tools/env-load.sh

# 红批：仅保留新测试类、stash 实现后复跑（忠实复现"实现不存在"的编译红）
git stash push -u -- platform-gateway/src/main
mvn -B -pl platform-gateway test -Dtest='IntegrationRateLimitTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-18-rate-limit/red-run1.txt 2>&1
git stash pop

# 干净基线对照（stash 实现+新测试后，与绿批同命令）
git stash push -u -- platform-gateway/src/main platform-gateway/src/test/java/cn/agentplatform/gateway/IntegrationRateLimitTest.java
mvn -B -pl platform-gateway test -Dtest='IntegrationPolicyTest,RoutesTest,IntegrationSecurityTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-18-rate-limit/baseline-adjacent.txt 2>&1
mvn -B -pl platform-gateway test -Dtest='GatewayRunStatusChainPostgresTest,GatewayPublishedChainPostgresTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-18-rate-limit/baseline-pg-pair.txt 2>&1
git stash pop

# 绿批 1：新测试类
mvn -B -pl platform-gateway test -Dtest='IntegrationRateLimitTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-18-rate-limit/green-run1-newclass.txt 2>&1
# 绿批 2：新类 + 既有相邻类同 JVM（旧票链一组）
mvn -B -pl platform-gateway test -Dtest='IntegrationRateLimitTest,IntegrationPolicyTest,RoutesTest,IntegrationSecurityTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-18-rate-limit/green-run2-adjacent.txt 2>&1
# 绿批 3：PG 链两类单独 JVM（原因见 §5：iam 模式静态闩与 dev-hmac 上下文不能同 JVM）
mvn -B -pl platform-gateway test -Dtest='GatewayRunStatusChainPostgresTest,GatewayPublishedChainPostgresTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-18-rate-limit/green-pg-pair.txt 2>&1

# 审计日志摘录
grep -a "INTEGRATION_RATE_LIMITED credential" green-run2-adjacent.txt | head -2 > audit-log-excerpt.txt
```

## 4. 结果原文（红→绿 + Tests run）

红批（red-run1.txt，实现 stash 后，新测试类先行）：

```
[ERROR] .../IntegrationRateLimitTest.java:[8,39] 找不到符号
[ERROR] .../IntegrationRateLimitTest.java:[133,9] 找不到符号
[ERROR] .../IntegrationRateLimitTest.java:[196,15] 找不到符号
[INFO] BUILD FAILURE
```

（首次开发期实跑红批另捕获 3 处符号缺失：`IntegrationRateLimit`、`rateLimit()`、`IntegrationAccess` 导入，与 red-run1.txt 同性质。）

绿批 1（green-run1-newclass.txt）：

```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.gateway.IntegrationRateLimitTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

六个用例对应派发要求的失败路径：
①配额内放行且正常代理到后端（`admitsWithinQuotaAndProxiesToBackend`：断言后端回显 `runId=r_verified`、`tokenUse=service`，链路锚点未破坏）；②同一 key 超限 429 可读（`rejectsOverLimitWithReadable429AndMaskedKey`：`$.code=INTEGRATION_RATE_LIMITED`、`$.traceId` 回显、消息含"凭据 1/尾号 0001/已放行 3/上限 3/重试"、完整密钥不出现）；③不同 key 独立窗口（`distinctCredentialsHaveIndependentWindows`）；④窗口滑出恢复（`windowSlideRestoresAccess`，可注入时钟 +61s）；⑤开关关闭旁路（`disabledSwitchBypassesLimit`，回滚验证）；⑥直连 ApiKey 一次 HTTP 请求只计一次（`directApiKeyRequestCountsOncePerHttpRequest`——若出票+代理重复计数，第 2 个请求即 429，该用例红）。

绿批 2（green-run2-adjacent.txt，与既有相邻类同 JVM）：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.gateway.domain.IntegrationPolicyTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.gateway.domain.RoutesTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.gateway.IntegrationRateLimitTest
[ERROR] Tests run: 6, Failures: 0, Errors: 6, Skipped: 0 -- in cn.agentplatform.gateway.IntegrationSecurityTest
[ERROR] Tests run: 23, Failures: 0, Errors: 6, Skipped: 0
```

绿批 3（green-pg-pair.txt，单独 JVM）：

```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.common.authority.GatewayPublishedChainPostgresTest
[ERROR] Tests run: 14, Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.common.authority.GatewayRunStatusChainPostgresTest
[ERROR] Tests run: 15, Failures: 2, Errors: 0, Skipped: 0
```

超限审计（既有 SLF4J 日志路径，audit-log-excerpt.txt 摘录；带 credential 标识、脱敏尾号、窗口/用量/上限、MDC traceId，无密钥本体）：

```
WARN ... c.a.gateway.domain.IntegrationRateLimit : INTEGRATION_RATE_LIMITED credential=1 keyTail=0001 path=/v1/apps/ops/runs windowSeconds=60 limit=2 used=2 traceId=c79fa2c6-2d6d-4499-bcb5-533c772e2455
```

## 5. 回归对照（新增失败=0）与未覆盖风险

### 干净基线 vs 本卡改动（同命令、同环境）

| 测试集 | 干净基线（stash 后） | 本卡改动后 | 新增失败 |
| --- | --- | --- | --- |
| IntegrationPolicyTest | 5/5 绿 | 5/5 绿 | 0 |
| RoutesTest | 6/6 绿 | 6/6 绿 | 0 |
| IntegrationRateLimitTest（新） | （类不存在=红） | 6/6 绿 | 0 |
| IntegrationSecurityTest | 6 errors | 6 errors（同因同数） | 0 |
| GatewayRunStatusChainPostgresTest | 14 跑 1 失败 | 14 跑 1 失败（同因同数） | 0 |
| GatewayPublishedChainPostgresTest | 1 跑 1 失败 | 1 跑 1 失败（同因同数） | 0 |

既有失败（与本卡无关，基线即红，均未触碰）：
- `IntegrationSecurityTest` 6 errors：上下文加载 `SECURITY_MODE_REQUIRED`——该类自身未设 `agent.security.mode`，本 shell 亦无 `AGENT_SECURITY_MODE` 环境变量（属计划 §6.4-3"运行方式导致的红"类；历史绿记录在 iam-stage-5 证据的 target-final 副本中，为带环境变量方式运行）；
- `GatewayRunStatusChainPostgresTest.realGatewayQueryAndStatusReturnOriginalBytes` 1 失败、`GatewayPublishedChainPostgresTest` 1 失败：基线即失败的既有断言，与本卡无交联。

### 未覆盖与已知限制（有意接受的限制，均写入类注释）

1. **直连 ApiKey 路径的超限响应无 traceId**：该路径 429 在共享 `SecurityConfiguration` 的 ApiKey 过滤器内被 catch 并写信封（`admin-common`，本卡白名单外），信封 traceId 与审计行 traceId 为空（`TraceContextFilter` 在安全链之后才写 MDC）。票据路径（业务系统标准形态）code+userMessage+traceId 齐全且已测试。修复需改 admin-common 共享过滤器，超出本卡白名单，已留待。
2. **iam 模式静态闩**：PG 链两类（iam 模式）与本卡 dev-hmac 上下文不能同 JVM 共跑——`ServiceTokens.deploymentMode` 进程级注册后 "iam" 不可降级（`IAM部署已关闭旧票据签发`）。故绿批 3 单独 JVM 跑；这与既有 `IntegrationSecurityTest`（同链路）面临的是同一运行纪律，非本卡引入。
3. **iam 模式下集成入口未启用**：`GatewayIngressBoundary` 无集成/ApiKey 处理，`proxy()` 在 iam 分支提前返回、不调 `integration.check()`；限流覆盖的是当前实际承载集成流量的 SecurityConfiguration 旧票链（出票+代理全部经过）。iam 模式集成入口若未来开建，需另行挂点。
4. **窗口为进程内存态**：网关单实例部署下有效；重启清零、多实例不共享（方案"不拖垮主业务"口径下可接受，已在类注释声明）。
5. **计数单位口径**：直连 ApiKey 一次业务请求计一次（打标去重）；`/embed/ticket` 出票计一次；票据代理请求各计一次。被拒（401/403/400）流量不消耗配额。票据模式组合（出票+每个业务请求）天然按请求线性计数。
6. **用户级/租户级限流未做**：X 系未裁决（台账 §2 PLAT-18 行），本卡只做 credential 维度（派发范围）。
7. 密钥材料只在出票路径短暂持有并脱敏为尾 4 位；票据代理路径拿不到密钥，超限消息沿用该凭据首次出票时记录的尾号（网关重启后显示"未知"，凭据 id 仍可定位）。

## 6. 回滚方式

- 主回滚：`agent.integration.rate-limit-enabled` 置 `false`（application.yml 默认值即 false，或环境变量覆盖）——限流整体旁路，行为回到 T18.1 前状态，无需回退代码（`disabledSwitchBypassesLimit` 用例即该形态的验证）。
- 辅助调参：`agent.integration.rate-limit.max-requests`（<=0 即不限）、`window-seconds` 可独立调整，均可在不改代码的情况下把限流等效为"不限"。
- 代码回退（如需）：回退本卡四个白名单文件即可；无迁移、无 DDL、无契约变更、无其他模块耦合（`IntegrationAccess` 构造签名未变，既有手动构造该类的测试不受影响，限流器默认关）。
