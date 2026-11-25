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


## E2 产品评测、发布和任务

E2 由真实 user-mgmt 签发的门户会话提交合成 CHAT 评测。首份草稿缺分类，模型网关按出域规则拒绝，运行随后失去授权，报告在复核时仍为 `RUNNING`。第二份草稿仅用公开合成数据并在应用配置声明 `requiredLabels: ["public"]`。正式评测达到 `ACCEPTED`，发布预检读回同一报告。测试应用发布版本 1，版本快照保留一个冻结 `modelRefs`。

已发布应用实际受理门户 AGENT 任务 `task-e3a02b8c67a84a1990f8` 并到达 `SUCCEEDED`。同请求重放返回同一任务，终态记录有结果摘要，引擎运行只记录一次模型分派，出话用量为 48/17/65 tokens。上述入口与结果取证见 [E2 业务复测原始摘录](e2-business-20261125.txt)。

独立的门户会话创建 200，后续轮次提交 403。当前 `PortalRuntimeRoutes` 只装配应用级会话、任务、记忆集合，`GatewayIngressBoundary` 未给会话详情、轮次与流式子资源建立入口。管理台直接运行还缺 `PublishedRunProxy` 运行装配，实测 503。补齐会话链需要以应用及本人会话为绑定资源，在网关与引擎逐跳使用原用户授权，辅以跨会话、撤销和断线续传回归。当前评测与已发布 AGENT 任务通过，PR-A 会话链、两小时期间以外的 PR-D 故障注入和 E3 正式目录与外部宿主仍待验。

PR-D 两小时 E2 采样于 2026-11-25 22:20:36 启动。探针每分钟分别记录八个容器的 `docker stats` 和六个 JVM 的 Prometheus RSS，每五分钟用真实用户提交一次公开合成 AGENT 任务，验证重放同 ID 和任务终态。采样进程及三份 CSV 在 E2 本轮候选目录，完成后按 §5.6 计算。


## E2 浏览器和 PLAN 联验修复

主入口控制台由测试宿主向页面注入真实 user-mgmt 会话。1440、1024、768 三个宽度均加载测试应用，三次 `GET /auth/session` 和 `GET /admin/apps` 返回 200。该版本在确认中心菜单未获授权时仍调用 `/admin/confirmation-center/counts`，三个宽度均返回 403。候选前端复用页面权限和引擎角色规则控制角标轮询。对同一 E2 后端、相同三个宽度、亮色执行真实读回，页面没有横向溢出，观测超过一个 15 秒轮询周期，未发出未授权角标请求。Web 82 文件 635 例以及 `vue-tsc --noEmit` 通过。旧计划详情测试改为断言该参数页确实不在菜单，菜单总数由现有导航测试验证。

E2 并发 PLAN 与 AGENT 暴露 `bizSystemCode` 将门户账号误判为外部集成的缺陷。任务受理按现有已验、绑定到应用版本的门户委派识别该请求。测试验证旧集成票、集成来源与应用不匹配仍受 AGENT 限制，并按 R-40 已生效的契约对齐受控中断目录断言。定向共享路由 10 例及独立 PG 引擎 14 例通过。Docker 基线不在两小时采样中切换。

补跑了调度恢复、重放与 SSE 的独立 PostgreSQL 回归 67 例。调度器测试的清理先清空专用测试库的追加式会话事件夹具，再删除会话，消除测试间残留造成的外键错误。E2 的实际重启、短连断和流式网络用例仍按 PR-D 场景单独判定。

### E2 隔离镜像演练

- 压缩包 `r22-e2-runtime-fixes.tar` 216426496 字节，SHA-256 `cb2512112c7c9e3a10f467abdc9e42910bf543533472358cfb6ad27e1e096ac7`。远端校验一致。
- Engine 配置摘要 `6944861286d5c5845e3b09229d22f315bb9811841d124ccd8393b089ada7bb6b`。前端候选配置摘要 `0de3b72f20245bf61fd53fbaff7ddbf91754629b0a30c9d15b9ac81a41f59415`。E2 兼容 Nginx 运行镜像配置摘要 `007f8f07ae40819ed4c80b7008a70ecead544cef935b8f3711f4b7725c1178`。
- 独立 PG、Redis、Engine 与替代网关启动，19182、19180 的 readiness 200。真实目录签发门户票，经 19090 两次提交同参 PLAN 均 200，`task-af2861e362a2477d8fc1` 为 `ACCEPTED`，隔离数据库只生成一行。
- 替代前端只监听回环 19191，`/healthz`、`/console/`、`/w/`、`/embed/manifest.txt` 均 200。E2 阶段日志标记 `R22_E2_RUNTIME_FIXES_REHEARSAL_PASS`。
