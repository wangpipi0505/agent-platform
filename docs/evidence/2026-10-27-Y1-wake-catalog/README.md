# Y-1：R-A6 唤醒端点未登记路由目录（2026-10-27）

## 1. 问题

`POST /internal/runs/{id}/wake` 由提交 `9976e34`（R-A6）加入
`engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java:495-497`，
但未同步登记 `admin-common/src/main/resources/agent-service-authority.json`。

后果两条，都已在基线里实测到：

1. 任何启动 `RouteCoverageVerifier` 的上下文（平台网关/引擎测试夹具）启动失败：
   `ROUTE_COVERAGE_MISSING: engine POST /internal/runs/{id}/wake public java.lang.Object cn.agentplatform.engine.api.RunController.wake(java.lang.String,com.fasterxml.jackson.databind.JsonNode)`
   ——`platform-gateway` 的 `GatewayRunStatusChainPostgresTest` 即因此报"子进程引擎启动失败"。
2. `ServiceAuthorization` 按同一目录判权，目录无行即无能力：R-A6 端点对外**实际不可达**，
   而代码与提交信息看起来"已交付"。

## 2. 处置（可见但零授权）

不猜测调用方：R-A6 的调用方（算法回调、集成系统、还是引擎内部）尚未定论，
登记成 `delegated` 等于凭空授权。因此按目录已有的 `POST /internal/tool-calls/register`
范式登记为 `disabled`：

```json
{
  "method": "POST",
  "template": "/internal/runs/{id}/wake",
  "caller": null,
  "target": "engine",
  "action": "run.wake",
  "mode": "disabled",
  "integrationAllowed": false,
  "source": "engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java:495",
  "callerSource": "",
  "reason": "R-A6 外部事件唤醒：调用方（算法回调/集成系统经哪条通道唤醒）尚未定，先登记为 disabled——覆盖校验通过但不授予任何服务能力；定稿后改为对应 caller 与 delegated"
}
```

`ServiceRoutePolicy` 的构造校验（`ServiceRoutePolicy.java:14-44`）要求 disabled 行
`caller==null`、`integrationAllowed==false`、`reason` 非空、同 handler 不可重复；本条满足。

判定口径：**覆盖校验要的是"目录如实描述控制器"，不是"授予能力"**。disabled 行让
"端点存在"这一事实可见、可审计、可复查，同时把"谁能调用"留成显式待决项（台账 X-10）。

## 3. 测试

新增 `admin-common/src/test/java/cn/agentplatform/common/authority/RunWakeCatalogTest.java`：
锁住"恰好一行 + 必须是 disabled + caller 为空"，防止后续有人把 mode 改成 delegated 而不留评审。

```text
cd 代码/agent-platform
mvn -B -ntp -pl admin-common test \
  -Dtest=RunWakeCatalogTest,RouteCoverageVerifierTest,ServiceAuthorizationTest \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
  -Dmaven.repo.local=.tools/m2

[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.350 s -- in cn.agentplatform.common.authority.RouteCoverageVerifierTest
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in cn.agentplatform.common.authority.RunWakeCatalogTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.967 s -- in cn.agentplatform.common.authority.ServiceAuthorizationTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

node scripts/verify-contracts.mjs
v1.2快照、四契约原文哈希与七模块依赖边界通过
```

## 4. 未覆盖 / 后续

- **端到端未验证**：`PublishedAdmissionHttpPostgresTest` / `GatewayRunStatusChainPostgresTest`
  这类"真起子进程引擎"的夹具本次没跑（与 PLAT-04 子任务共用 engine-runtime 的 target 目录，
  避免并发写）。PLAT-04 收口后必须补跑，并确认 `ROUTE_COVERAGE_MISSING` 不再出现——这是
  "覆盖校验已覆盖新端点"的唯一硬证据，本文件不作替代。
- **R-A6 仍不可用**：在 X-10 定稿调用方与通道之前，该端点对任何 caller 都是 403/无行状态。
  这符合当前事实，但意味着 R-A6 的验收不能算完成。
- `WakeRequestValidator` 等业务规则不在本项范围。
