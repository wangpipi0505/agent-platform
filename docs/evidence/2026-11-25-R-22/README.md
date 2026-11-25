# R-22 E2 入口复核

2026-11-25，按台账登记的 E2 Nginx 地址 `192.168.1.2:18091` 发出只读探测。另发出一次无 ApiKey 的出票请求，核对接入拒绝。

| 请求 | HTTP | 结果 |
| --- | --- | --- |
| GET /healthz | 200 | Nginx 健康 |
| GET /console/ | 200 | 控制台入口可达 |
| GET /auth/session | 401 | SYS-AUTH-401 |
| GET /auth/dev-token | 404 | 生产代理关闭开发票入口 |
| GET /actuator/prometheus | 404 | 公开代理隐藏指标 |
| POST /embed/ticket，无 ApiKey | 401 | API_KEY_REQUIRED |

以项目旧记录使用的 SSH 批处理方式连接 E2，远端返回 Permission denied。E2 真实登录后的页面走查、双小时长稳和新镜像部署复验待执行。此前真实供应商及用量对账结论沿用 2026-11-20 的存档。

## 本轮候选包与部署门禁复验

- 按 Java 21 重新打包 IAM 七服务，确认 authority-service 成品包中的公共授权目录包含当前下载动作。隔离 PostgreSQL 迁移后，网关、应用、引擎、技能、模型、monitor 的 `/actuator/health` 均为 HTTP 200 且状态 UP，authority-service 的 JWKS 返回 HTTP 200。
- `docker compose -f <配置> config --no-interpolate --no-env-resolution -q` 通过，共检查正式、测试和 E1 三份配置，以及测试栈叠加观测配置。
- `python deploy/validate.py` 通过。先从官方版下载缺失的固定版本 Schema 并核对仓库固定 SHA-256，再确认 43 个 Kubernetes 对象、12 个加固工作负载及 3 项拒绝断言。 `python deploy/test-static.py` 通过，探针区分 200 与 503，可变镜像及未签认的 IAM 发布门禁被拒绝。
- E2 复测与上表一致。当前 SSH 仍返回 Permission denied。本机 Docker 仅完成配置解析，Linux Engine 未运行。正式新镜像部署、真实登录后的业务复验、两小时长稳及 E3 仍待执行。
