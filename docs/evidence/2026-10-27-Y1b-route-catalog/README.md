# Y-1b：路由目录漂移补齐（引擎控制台两条端点）

日期：2026-10-27

## 1. 问题

Y-1 修复（把 `POST /internal/runs/{id}/wake` 以 disabled 登记）之后，网关运行状态链路夹具
`GatewayRunStatusChainPostgresTest` 仍然失败，但失败点换了：

```
IllegalStateException: ROUTE_COVERAGE_MISSING: engine GET /admin/runs/page
public java.lang.Object cn.agentplatform.engine.api.RunController.page(java.util.Map)
```

这说明缺行不是一处，而是**目录与控制器之间的系统性漂移**：`RouteCoverageVerifier` 要求服务里每个
handler 都有对应的目录行，但目录靠手改，新增端点时很容易漏。漏行的后果不是"少一条授权"，而是
任何启动校验器的上下文（网关链路夹具、审计脚本、将来的启动自检）整体失败，且错误信息指向
handler 而不是缺失的行，排查成本很高。

## 2. 怎么找全

用手工核对太慢，写了一次性脚本：解析 `engine-runtime/src/main/java/cn/agentplatform/engine/api/*.java`
里的类级 `@RequestMapping` 前缀与 `@Get/Post/Put/Delete/PatchMapping` 路径，把 `{var}` 归一成 `{}`
后与目录做集合差。结果：**引擎 40 个 handler，缺 2 行**（唤醒那行已在 Y-1 补上）。

| 端点 | 事实 | 处置 |
| --- | --- | --- |
| `GET /admin/runs/page` | `web/admin/src/RunList.vue:41` 真实调用，经网关代理到 engine | `caller=gateway`、`mode=delegated`、`action=proxy.engine.get.RunController.page` |
| `GET /internal/operations/overview` | 全仓无服务调用方；运维总览走 monitor 的 `/admin/operations/overview` | `mode=disabled`、`caller=null`、reason 写明"接入方确定后再改 delegated" |

处置口径与 Y-1 一致：**先登记为可见、按实际授权形态给权限**。有真实调用方的按 delegated 登记；
没有调用方的登记为 disabled（覆盖校验通过、零授权），而不是为了"过测试"凭空给一个 caller。

## 3. 测试

新增 `admin-common/src/test/java/cn/agentplatform/common/authority/EngineConsoleCatalogTest.java`，
把两条行的授权形态钉死（`/admin/runs/page` 必须 delegated+gateway，`/internal/operations/overview`
必须 disabled+null+reason 非空），将来改形态必须改测试并留评审记录。

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in EngineConsoleCatalogTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in RunWakeCatalogTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in RouteCoverageVerifierTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- in ServiceAuthorizationTest
```

`node scripts/verify-contracts.mjs` 仍通过（四契约原文哈希、七模块依赖边界、v1.2 快照）。

## 4. 验证失败点已前移

补行后再跑 `GatewayRunStatusChainPostgresTest`，缺行错误消失，失败点变成：

```
PSQLException: ERROR: relation "engine.model_input_snapshot" does not exist
```

`engine.model_input_snapshot` 由 `V218__initial_model_input_material.sql` / `V219__initial_request_utf8.sql`
创建，而该夹具的数据库是早期 schema（夹具自己 `flyway.target("217")` 之类固定目标版本）。
这属于 **Y-6 测试基座债务**（夹具库 schema 版本与源码不一致），不是路由目录问题，也不在本卡范围。
结论：**Y-1/Y-1b 的代码侧缺陷已消除，剩余阻断是测试基座**。

## 5. 未覆盖 / 后续

- 只在 engine 上做了全量比对；app-management / skill-registry / monitor / model-gateway 尚未比对，
  很可能有同类漂移（下次跑各自夹具时暴露）。
- 没有把比对脚本变成构建期校验（`scripts/verify-routes.mjs` 之类），所以漂移还会再发生。
  建议并入 G-8 的"测试基座修复"，作为独立治理项 **G-9：路由目录漂移校验**。
- 网关链路的端到端仍红，原因是夹具库 schema（Y-6），需要单独修夹具的迁移目标与建库脚本。
