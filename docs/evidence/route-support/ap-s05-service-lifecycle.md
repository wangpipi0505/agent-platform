# AP-S05 通用服务生命周期

> 日期：2026-10-27
> 环境：本地 dev 18080（platform-gateway）、18083（skill-registry）、PostgreSQL 25432 `agent_platform_dev_v12`、Redis 26379、MinIO 19000；管理台 15173。
> 结论：通用外部服务 manifest、登记、启动/停止/重启、健康检查、日志读取、孤儿判定和审计已实现，并通过定向测试与真实 HTTP 生命周期验证。代码待提交。

## 一、范围与边界

AP-S05 解决“平台如何管理一个本机外部服务”的产品化缺口，不做容器编排，也不接管远端主机。

- manifest 登记在 PostgreSQL `skill.service_manifest`，命令是显式 argv 数组，不经过 shell。
- 生命周期动作只允许 ADMIN；停止、重启、删除必须提交 `confirm=<code>`。
- 平台只终止 `skill.service_runtime` 中记录、且 `ProcessHandle.info().startInstant()` 与记录一致的进程树；不按端口或进程名盲杀。
- 服务健康只发 GET 到登记的 `healthPath`；日志只读取 manifest 对应日志文件尾部，不执行任意命令。
- 不携带凭据，不把 secret 写入命令行；需要认证的服务由服务自身从环境或外部配置读取。
- 孤儿清理在 skill-registry 启动时和心跳任务中执行，只处理记录属于本实例、PID 仍存活且启动时间匹配的条目。

## 二、实现文件

| 文件 | 内容 |
| --- | --- |
| `skill-registry/src/main/resources/db/migration/V313__service_lifecycle.sql` | `skill.service_manifest`、`skill.service_runtime`、`skill.service_audit` 三张表 |
| `skill-registry/src/main/java/cn/agentplatform/skill/domain/ServiceManifest.java` | manifest record、字段校验、行映射、健康 URI、日志文件 |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/ServiceLifecycle.java` | 登记/编辑/删除、启动/停止/重启、健康、日志、心跳、启动清理、审计 |
| `skill-registry/src/main/java/cn/agentplatform/skill/api/ServiceController.java` | `/admin/services` 管理面 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java` | `/admin/services` 转发到 skill |
| `web/admin/src/pages/ServiceManagementPage.vue` | 服务列表、登记/编辑抽屉、动作确认、健康/日志对话框 |
| `web/admin/src/pages/service-admin.ts` | 状态标签、命令文本与 argv 互转、动作路径 |
| `web/admin/src/router.ts`、`web/admin/src/AppShell.vue` | `/services` 路由与“服务管理”菜单 |

### manifest 校验

- `code`：`[a-z][a-z0-9_]{1,63}`。
- `command`：1–64 项；第一项必须是绝对路径且是普通文件；参数不能为空、不能含换行/NUL。
- `workDir`：绝对路径且已存在目录。
- `port`：1–65535；`healthPath` 以 `/` 开头，不能含 `..`、查询串、片段。
- `startupTimeoutMs` 1000–600000；`stopTimeoutMs` 100–60000。
- 端口被占用时启动直接 409，不尝试杀占用进程。

## 三、验证

### 3.1 定向测试

```bash
set -a && . ./.env && set +a
export JAVA_HOME='/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -pl skill-registry -am test \
  -Dtest=ServiceManifestTest,ServiceLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine='-Xmx512m -XX:MaxMetaspaceSize=256m'
```

结果：`ServiceManifestTest` 4/4、`ServiceLifecycleTest` 3/3，errors=0、failures=0。

覆盖：

- 显式 argv 解析；相对可执行文件、相对工作目录、端口 0、非法健康路径、参数换行均拒绝。
- 真实临时 PowerShell 服务：登记 → 启动 READY/pid/healthUrl/healthy → 健康 200 → 日志可用 → 停止后进程退出 → 重启 → 审计至少 5 条。
- `stop` 不带 confirm 返回 409 `SERVICE_CONFIRM_REQUIRED`，进程仍 READY。
- 启动即 `exit 7` 的进程返回 502 `SERVICE_EXITED`，状态 FAILED、`lastError` 含 `exitCode=7`，审计记录 FAILED。

### 3.2 真实 HTTP 生命周期

通过 `http://127.0.0.1:18080` 网关执行，服务为临时 PowerShell `TcpListener`，监听 18090，所有请求返回 200 `ok`。

| 步骤 | 结果 |
| --- | --- |
| `POST /admin/services` | 200；返回 `state=STOPPED`、`managed=false`、`healthUrl=http://127.0.0.1:18090/health` |
| `POST /admin/services/ap_s05_probe/start` | 200；`state=READY`、`pid=31392`、`healthy=true`、HTTP 200、347 ms |
| `GET .../health` | 200；HTTP 200、2 ms、`bodyPreview=ok` |
| `GET .../logs?lines=20` | 200；`available=true`、0 bytes（探针不写 stdout） |
| `POST .../stop`（无 confirm） | 409 `SERVICE_CONFIRM_REQUIRED`；进程 31392 仍存活 |
| `POST .../stop?confirm=ap_s05_probe` | 200；`state=STOPPED`；进程 31392 已退出 |
| `POST .../restart?confirm=ap_s05_probe` | 200；`state=READY`、新 `pid=61688`、healthy=true、308 ms |
| `DELETE ...`（运行中） | 409 `SERVICE_RUNNING`：“服务运行中，请先停止再删除” |
| 停止后 `DELETE ...?confirm=ap_s05_probe` | 200；`{deleted:true,code:"ap_s05_probe"}`；列表恢复为空 |

### 3.3 管理台

- 开发服务器 `http://127.0.0.1:15173`，dev 身份登录后进入 `#/services`。
- 页面显示服务名、code、状态“运行中”、健康 URL、PID、启动/停止超时和操作列；无 pageerror。
- 截图：`.local/tmp/ap-s02-s07/ap-s05-services-real.png`。
- 前端 `npm test`：33 文件/242 测试通过；`npm run build` 通过（admin、webapp、assistant 三套产物）。
- `npx vue-tsc --noEmit` 通过。

## 四、已知限制

- 只管理本机进程，不管理容器、systemd、Kubernetes 或远端主机；这些需要后续独立设计。
- 日志是进程 stdout/stderr 重定向文件；服务自己写轮转文件时，尾部读取可能跨轮转边界。
- `DELETE` 只删 manifest 和运行态记录，不删除已写出的日志文件；日志保留期由后续 AP-S10 处理。
- 真实回归拒绝未在 AP-S05 中涉及；AP-S05 的拒绝路径是 confirm、端口占用、运行中删除和启动退出。
- 本次没有跑全量 `skill-registry` 测试；该模块全量套件仍受 JWT 密钥/测试库历史数据影响（见 AP-S12 证据），不能据此声称模块全绿。定向测试与真实 HTTP 验证是本项证据。

## 五、证据位置

- 截图：`.local/tmp/ap-s02-s07/ap-s05-services-real.png`（本地证据，不提交二进制）。
- 运行日志：`.local/logs/skill-registry.out.log`、`.local/logs/platform-gateway.out.log`。
- Flyway：启动日志确认 schema `skill` 从 v312 迁到 v313。
