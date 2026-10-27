# AP-S10 产品化运行中心

> 日期：2026-10-27
> 环境：本地 dev 18080（platform-gateway）、18082（engine-runtime）、18085（monitor）、PostgreSQL 25432 `agent_platform_dev_v12`；管理台 15173，dev 身份 `developer-v12`。
> 结论：运行中心已提供租户级运行指标、服务健康、traceId、告警评估/确认、保留期清理和管理台页面；不把外部 Prometheus/Grafana 冒充为产品能力。

## 一、范围与边界

AP-S10 解决“对外交付需要可运维”的产品化缺口，形态是平台自己的“运行中心”，不是给客户暴露 Grafana/Loki。

- 运行指标从 `engine.run`、`engine.attempt`、`engine.tool_call` 权威表读取；窗口按运行 `started_at`、模型/工具调用时间过滤。
- 服务健康是页面/评估时实时 GET 各服务 `/actuator/health`，不落历史健康快照。
- 告警只在 ADMIN 点击“评估告警”时写入；恢复后置为已恢复，不自动发送邮件/Webhook。
- 保留期清理真实删除过期的 `monitor.statistics_snapshot` 和已恢复的 `monitor.ops_alert`；不删除审计和运行证据。
- traceId 从入口 `X-Trace-Id` 接收（仅接受 1–128 位安全字符），写入 MDC、响应头，并继续传给 engine 内部调用；服务日志可按该号关联。

## 二、实现文件

| 文件 | 内容 |
| --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunOperations.java` | 运行总览只读投影：完成/成功/失败/取消、活跃、平均/P50/P95、模型/工具失败、失败语义 Top 5、最近失败 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java` | `GET /internal/operations/overview?windowMinutes=` |
| `monitor/src/main/resources/db/migration/V503__operations_alerts.sql` | `monitor.ops_alert`，同租户/规则/主体只允许一个未恢复告警 |
| `monitor/src/main/java/cn/agentplatform/monitor/domain/OperationsRules.java` | 窗口校验、服务不可达、运行失败率 20%/50% 阈值与最小样本 20 |
| `monitor/src/main/java/cn/agentplatform/monitor/application/OperationsService.java` | 实时健康、engine 投影、告警 upsert/resolve/ack、保留期清理、定时清理 |
| `monitor/src/main/java/cn/agentplatform/monitor/api/OperationsController.java` | `/admin/operations/*` 管理面 |
| `admin-common/src/main/java/cn/agentplatform/common/TraceContextFilter.java` | 入口 traceId 接收/校验、MDC、响应头 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java` | `/admin/operations` 转发 monitor |
| `web/admin/src/pages/OperationsPage.vue` | 运行中心页面 |
| `web/admin/src/pages/operations-admin.ts` | 百分比/耗时/窗口/告警标签/时间显示纯函数 |

## 三、验证

### 3.1 定向测试

```bash
set -a && . ./.env && set +a
export JAVA_HOME='/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
mvn -q -pl engine-runtime,monitor -am test \
  -Dtest=RunOperationsTest,OperationsRulesTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine='-Xmx512m -XX:MaxMetaspaceSize=256m'
```

结果：`RunOperationsTest` 1/1、`OperationsRulesTest` 7/7，errors=0、failures=0。

覆盖：

- 窗口小于 5 或大于 1440 分钟拒绝；服务健康失败产生 `SERVICE_UNREACHABLE` CRITICAL；最小样本不足不产生失败率告警；20%/50% 分档；`runs.runs` 嵌套与紧凑摘要两种输入；不把缺失指标当 0。
- 前端 `operations.test.ts` 覆盖缺失指标、比例/耗时/窗口、告警标签、时间格式。

前端：

```bash
cd web
npm test
npm run build
```

结果：34 文件/248 测试通过；`vue-tsc --noEmit` 与 admin、webapp、assistant 三套生产构建通过。

### 3.2 真实 API

通过 `http://127.0.0.1:18080` 网关取 dev token 后访问 monitor：

| 请求 | 结果 |
| --- | --- |
| `POST /auth/dev-token` | 200，`data.accessToken` |
| `GET /admin/operations/overview?windowMinutes=1440` | 200；5 个服务健康均正常；`runs.completed=69`、`succeeded=22`、`failed=47`、`failureRate=0.6812`；模型 131/45，工具 55/41；`traceId` 为 36 位 UUID |
| 同上，入口带 `X-Trace-Id: ap-s10-client-trace-001` | 响应头 `X-Trace-Id: ap-s10-client-trace-001`，body `traceId` 相同 |
| `POST /admin/operations/alerts/evaluate?windowMinutes=1440` | 200；生成 `RUN_FAILURE_RATE` CRITICAL，详情“完成 69 次，失败 47 次，失败率 68.1%” |
| `POST /admin/operations/alerts/{id}/ack` | 200；状态 `ACK`；overview `openAlerts=0` |
| `POST .../evaluate?windowMinutes=60` | 200；无失败率告警，原活动告警自动 `RESOLVED` |
| `POST .../evaluate?windowMinutes=1440` | 200；重新产生活动告警；说明恢复判定按当前窗口真实计算 |
| `POST /admin/operations/retention/purge?confirm=nope` | 400；不删除任何数据 |
| 直接向 `monitor.ops_alert` 插入一条 `RESOLVED`、`resolved_at=now()-100 days` 的测试行，再 `POST ...?confirm=PURGE` | 200；`alertsDeleted=1`；随后按 id 查询该行已为 0 |

### 3.3 管理台

- 开发服务器 `http://127.0.0.1:15173`，dev 身份登录后进入 `#/operations`；无 pageerror。
- 页面显示服务健康、运行概况（69/22/47、失败率 68.1%、P95 12.5 s）、模型/工具调用、失败语义 Top 5、最近失败运行、告警历史和保留/清理。
- 默认窗口为最近 24 小时；时间显示明确标注 UTC；告警列表注明是历史记录、不受统计窗口过滤。
- 截图：`.local/tmp/ap-s02-s07/ap-s10-operations-real.png`。

### 3.4 实现中修复的真实问题

- `OperationsRulesTest` 首次断言失败：页面整体 `runs` 是 engine 投影，失败率在 `runs.runs.failureRate`；已修正前端取值，避免真实指标显示为“—”。
- 引擎模型/工具统计最初误写 `a.run_id` / `t.run_id`；按 schema 改为 `node_instance` join `run` 和 `tool_call.run_id`，真实 API 才返回 131/45、55/41。
- `openAlerts` 原统计 `resolved_at is null`，把已确认 ACK 也算待处理；改为只统计 `status='OPEN'`，确认后归零。
- 保留期清理原按“有统计快照的租户”循环，导致没有快照的租户告警永不过期；改为按时间全局删除。
- 网关响应 traceId 与 body traceId 不一致；`GatewayController` 写回 filter 的 trace ID；已验证入口/响应一致。

## 四、已知限制

- 告警是产品内记录与确认，不接邮件、短信、Webhook、PagerDuty；通知渠道需要单独配置与凭据治理。
- 服务健康是实时探测，不保存历史健康曲线；历史趋势仍以运行/调用指标为主。
- 不直接暴露 Prometheus/Grafana/Loki/Tempo；这些是部署侧基础设施，产品只给出租户级视图。
- 当前服务日志仍为各进程 stdout/stderr 文件；运行中心给 traceId 关联入口，但没有日志全文检索。
- 保留期只清理统计快照和已恢复告警；运行、模型调用、工具调用和审计按各自权威表保留，不在本项删除。
- 全量 `monitor`/`engine-runtime` 测试仍受本地测试库历史数据和 JWT 夹具影响；本项以定向测试和真实 API 为证据，不声称模块全绿。

## 五、证据位置

- 截图：`.local/tmp/ap-s02-s07/ap-s10-operations-real.png`（本地证据，不提交二进制）。
- 运行日志：`.local/logs/monitor.out.log`、`.local/logs/engine-runtime.out.log`、`.local/logs/platform-gateway.out.log`。
- Flyway：monitor 启动日志确认 schema `monitor` 迁移到 V503。
