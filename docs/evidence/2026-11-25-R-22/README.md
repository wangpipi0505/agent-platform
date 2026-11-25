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

## E2 可传输候选镜像与容器复验

E2 的 Compose 已补齐 authority 的 test profile、真实目录启用开关及独立的测试票开关。默认关闭测试出票，authority 使用回环 JWKS 就绪探针。业务服务在分离的管理端口继续使用原就绪探针。构建脚本支持 `-IncludeAuthority`，E2 可使用 `AUTHORITY_SERVICE_IMAGE` 指定新版本。部署门禁保持原配置。

本机 Docker Linux 引擎使用已打包的 JDK 21 后端和四个固定摘要的基础镜像，构建七个后端镜像及 portal 前端镜像。使用隔离网络命名空间、PG、Redis 和一次性密钥运行八个候选镜像。八个容器均 healthy，authority JWKS 返回 200，默认测试出票返回 404。前端控制台、工作台和嵌入清单返回 200，无票会话返回 401。原始状态见 `e2-container-smoke.txt`。首次容器复验的本地夹具缺少正确的 IAM 算法配置，修正本地夹具后复跑通过。

`deploy/test-static.py` 先复现旧 Compose 缺少 authority profile 的失败，再验证新配置与拒绝用例通过。八个镜像通过 `docker image save --platform linux/amd64` 导出为本地候选包，验过压缩包清单中的八个标签以及整个 tar 的 SHA-256。镜像标识和包摘要见 `e2-candidate-images.txt`。

E2 上 `server609` 解析至 `192.168.1.2`。严格校验既有主机密钥后，项目记载的 SSH 别名与直连两条路径均到达服务器，客户端鉴权被拒绝。E2 现网镜像尚未替换，真实目录和供应商复验、带业务负载的两小时长稳以及 E3 环境验收仍待执行。

## E2 实际升级、目录及供应商联验

SSH 后续恢复。在 E2 校验并导入八个候选镜像，Docker 26 读取的 image config digest 全部与本地导出清单一致。升级前执行 PostgreSQL 备份并以 `pg_restore --list` 读取 714 项。首轮演练共用线上 PostgreSQL 实例，触发连接数上限，移除演练容器后连接恢复。随后用独立 PostgreSQL、Redis 恢复备份，迁移至 app V127、engine V236、skill V322、model V306、monitor V506，五个就绪端点均为 200。

七个后端在 E2 主栈切换成功。初版前端在目标内核启动时出现 `pwrite() "/tmp/nginx.pid" failed (1: Operation not permitted)`，回退原前端恢复服务。复用 E2 上已验证的固定摘要 Nginx 镜像作为运行层，将新静态资源放在独立目录，先在回环 19191 核验再切换 18091。正式控制台文件摘要与原候选资源一致。八个容器均 healthy。

旧 Prometheus 配置改为抓取六个业务模块的独立管理端口，成功 reload。E2 原编排只给了系统 Redis URI，模型配额与深度探针需要独立 host、port 和受控密码引用。补齐后模型探针从 DEGRADED 转 HEALTHY，六个 Prometheus 目标均 up。

使用用户指定的 user-mgmt 源码所运行的本地真实目录。E2 测试通过回环 SSH 隧道访问该目录，网关使用与实际目录签发匹配的测试主体声明配置。真实 `/oauth/token` 和主入口 `/auth/session` 返回 200，平台授予 `demo/admin` 的 ADMIN 权限，`model.manage` 生效。当前该联验配置只绑定测试主体 admin，依赖临时目录通道。

按原验收报告从同项目 E1 的 `r24_check` 定位既有 DeepSeek 供应商和模型配置。读取 E2 已登记的密钥文件引用，重新启动原回环代理并在 E2 主库注册 provider 1 和 entry 1。目标模型为 `deepseek-flash`，窗口 1048576，最大输出 393216。模型深度检查通过配额、供应商、真实对话和 tokenizer 测试。另发出一次受控最小请求，供应商返回 request id `155607f6-8f79-4f09-b3cc-8c4c1e4d61b1`，用量 **34 + 12 = 46 tokens**。

备份、候选覆盖配置及脱敏输出在 E2 的 `/home/agent-platform/r22-candidates/20261125-204157/`。主验证标记为 `E2_PRIMARY_REAL_PROBE_PASS`。证据见 `e2-live-20261125.txt`。A01–A08 业务链在当前版本上的正式重放、PR-D 两小时业务长稳、长期目录接入及 E3 保持待验收。
