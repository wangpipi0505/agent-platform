# 独立部署交付与运维附录

本目录交付六个 Java 服务、admin/WebApp/assistant.js 三种前端产物，以及 OTel Collector、Prometheus、Grafana、Loki、Tempo。当前为**经过静态验证的部署候选配置**；没有镜像构建、容器运行或 K8s 联调验收。静态校验未启动容器或连接集群；本工程自己的本地开发基础设施见根README。

生产 IAM 接入与内部服务调用 scope 细分尚未完成。`AGENT_RELEASE_GATE` 默认 `BLOCKED_IAM_SCOPE_ACCEPTANCE`，后端入口脚本在此值下直接退出；没有公开生产入口、默认账号或自动解锁步骤。发布负责人只能在 IAM 验签、租户/主体权威来源、服务来源/受众/scope、吊销与越权负向验收完成并留证后，单独作出 `IAM_SCOPE_ACCEPTED` 的发布决策。该变量只是部署门禁，不能补齐 IAM 功能。Grafana 必须显式注入运维用户名和至少 32 字符密码，否则拒绝启动；不提供默认口令。

## 构建产物

- `Dockerfile.backend` 接收独立工程已打包的可执行 Spring Boot JAR；Java 构建镜像使用 Temurin 21 JDK、运行镜像使用 Temurin 21 JRE（含 `/bin/sh`、`sha256sum`），由运维注入真实 `@sha256:` 引用。Boot 仍为工程锁定的 3.5.0。
- `Dockerfile.frontend` 用 Node 22（Debian 版）执行 lockfile `npm ci`；Nginx 官方 Alpine 镜像需包含 BusyBox `wget`。构建与运行基镜像同样要求真实 digest。门户 base 为 `/console/`，WebApp 为 `/w/`；`/admin/*` 保留管理API，页面刷新不会与API或另一端的assets冲突。WebApp入口为 `/w/:appCode`。
- 助手产物为 `/assistant.js`，另提供 `/embed/assistant.<SHA256>.js` 与 `/embed/manifest.txt`；嵌入时使用带摘要的路径。动态 `/embed/ticket` 经 gateway，不能由静态站点生成权限票据。
- `build-images.ps1` 的四个必填基镜像参数校验 digest 格式，仅执行本地构建、不推送。调用前需在专用构建环境完成 Maven 打包，并执行 `download-tools.ps1`。本轮未运行构建脚本。

## 本地独立容器验收候选

`compose.yaml` 使用独立项目名，业务、telemetry、ops 内部网络分离。后端的外部依赖网络、vault/观测持久卷均必须由运维预先创建成**全新的专用资源**并显式填名；不寻找或连接任何已有项目资源，也不把本机 bootstrap 的 loopback 实例直接当成容器依赖。不得配置 `host.docker.internal` 复用旧实例。

复制 `release.env.example` 到不跟踪的专用文件并填写实际镜像 digest、专用依赖地址、卷名与两个环境文件绝对路径。运行时环境文件只由部署方创建，需包含 `AGENT_DB_USER`、`AGENT_DB_PASSWORD`、`AGENT_JWT_SECRET`、`AGENT_MASTER_KEY`、`AGENT_REDIS_HOST/PORT/PASSWORD`、`AGENT_MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET`；所有账号、密钥、桶、数据库只属于本工程。JDBC base 不含查询串，服务会追加各自 `currentSchema`。Grafana 环境文件必须包含 `GF_SECURITY_ADMIN_USER`、`GF_SECURITY_ADMIN_PASSWORD`。不得提交这些文件，不在命令行或报告打印其值。

以下只执行本地解析与门禁检查，不启动容器：

```powershell
python deploy/validate.py
docker compose -f deploy/compose.yaml config --no-interpolate --no-env-resolution -q
./deploy/check-release.ps1 -EnvFile <专用release环境文件>
```

实际运行必须同时启用 `acceptance` 与 `ops` profile。前端仅绑定 `127.0.0.1:18090`，Grafana 仅 `127.0.0.1:13000`；其他端口不发布。Compose loopback 适合受控本机验收，不能替代生产网段防火墙。全部运行容器使用 UID/GID 10001、只读 rootfs、drop ALL capability、no-new-privileges；仅 `/tmp` 和明确持久卷可写。后端镜像有 HTTP readiness 健康检查；ops 的完整健康探针在 K8s 配置中交付，Compose ops 健康状况仍需运维验收。

## Kubernetes 候选

`kustomization.yaml` 将 `k8s/base.yaml`、`k8s/ops.yaml` 与五份 ops 配置组装；本轮已用独立 Kustomize 5.7.1 离线渲染并检查 44 个对象，未执行 kubectl、未读取任何 kubeconfig。资源面向 Kubernetes v1.33.0 API schema。两个命名空间为 `agent-platform-v12` 与 `agent-platform-v12-ops`，启用 restricted Pod Security、禁用 service account token 挂载；没有集群角色、HostPath、NodePort、LoadBalancer 或 Ingress。

镜像默认是 `registry.invalid` 加全零摘要，确保未经替换不能意外拉起。运维 overlay 必须逐项替换七个业务镜像及五个观测镜像为实际 digest，Collector 配置已用 **contrib 0.137.0** 原生校验；其余观测镜像版本/digest需运维补齐并做各组件原生配置验收，不能填 `latest`。在专用集群执行 server-side dry-run 前还必须提供：

| 对象 | 必须由运维注入的内容 |
| --- | --- |
| `agent-runtime` Secret（业务命名空间） | 前述 DB 账号/口令、JWT 签名密钥、专用 Redis/MinIO 参数；不能含可绕过显式 env 的发布开关 |
| `agent-database-urls` Secret | 六个 key 与服务名相同，每个值为专用 PG 对应 schema 的完整 JDBC URL |
| `agent-vault-master-key` Secret | 唯一 key `AGENT_MASTER_KEY`；至少 32 字符、独立生成、独立备份，不能放在 vault PVC 中 |
| `grafana-ops` Secret（ops 命名空间） | `GF_SECURITY_ADMIN_USER` 与 `GF_SECURITY_ADMIN_PASSWORD`，密码至少 32 字符；运维 RBAC 限制 Secret 读取 |
| 独立 PG/Redis/MinIO | PostgreSQL16.15、Redis7.x（交付候选固定7.2.16，容器形式必须实际digest）、项目锁定官方MinIO构建；专用账号与租户/前缀权限、TLS与服务端证书，明确新资源标识；模板不启动、不发现任何旧实例 |
| 存储 | 替换 vault 的 `required-dedicated-rwx-csi`；四个 ops PVC 名为 `prometheus-data`、`grafana-data`、`loki-data`、`tempo-data`，由运维提供专用 RWO 卷及保留策略 |
| 外部入口/依赖出网 | 指定 TLS 业务入口、CNI和专用端点白名单。`dependency-egress.example.yaml` 仅是保留 IP 的文档示例，不被默认安装 |

当前默认拒绝全部 ingress/egress，仅放行命名空间内必要 HTTP、DNS 和业务到 Collector 的 OTLP。业务前端只有被授权为 `agent-platform.access=business-ingress` 的独立入口命名空间可达。Grafana 只有 `agent-platform.access=operations` 命名空间内同时带 `agent-platform.ops-client=true` 的运维端可达；其他观测组件仅 ops 内部互通。需使用确实实现 NetworkPolicy 的 CNI并实测业务 Pod 无法访问 Grafana/Loki/Tempo/Prometheus；命名空间标签只能由集群运维管理。当前 backend HTTP 网络策略粒度为服务组，不能冒充内部 JWT scope 已细分；外部模型/技能目的地需另经批准白名单，默认拒绝。

engine 为两副本，有 PDB、主机级优先分散和滚动更新零不可用配置。`engine-hpa.pending.yaml` **未纳入部署**：并发运行数指标、Prometheus Adapter 与单副本容量标定尚待真实验收，不能将示例 `50` 当实测阈值，也不能用 CPU HPA 代替详设要求。尚未授予 ALGORITHM Job 的 Kubernetes 权限，Job 提交/查询与最小 RBAC需专用集群验收后补齐。

## JVM、探针与关闭

每个后端 limit 1 GiB、request 512 MiB，使用 `-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=70 -XX:MaxDirectMemorySize=128m`；最大堆约 716.8 MiB，容器/堆约 1.43，剩余预算还承担线程、元空间与 agent。该初值未经过容量压测。Spring 显式 graceful shutdown、每阶段 45 秒，Pod/Compose 终止宽限 60 秒；容器脚本使用 `exec java` 保证 SIGTERM 到达 JVM。

K8s startup/liveness 指向 `/actuator/health/liveness`，readiness 指向 `/actuator/health/readiness` 并包含 `readinessState,db`，不会把依赖瞬时故障当作 liveness 失败。业务代理屏蔽 `/actuator`、`/internal` 和 `/auth/dev-token`。调度器停止认领、租约移交、在途外部写操作对账、滚动升级及 SSE 断线续传均需真实多副本测试；仅配置优雅关闭不等于这些行为已验收。

## 密钥共享与灾备边界

SecretVault 当前是文件 AES-GCM，所有需要读写同一 `vault:` 引用的 Pod 必须共享 `agent-vault-rwx` 的 **ReadWriteMany 文件系统卷**，不能用 RWO 或每 Pod emptyDir 代替。目录 owner 10001:10001、权限 0700（不允许 other/group 读），文件在入口 `umask 077` 下创建为 0600；RWX存储导出 ACL/CSI fsGroup 行为必须实测，不能靠模板推定。Pod `fsGroup` 仅为初始化挂载提供兼容，若 CSI 会扩大权限，应由存储方在挂载前设置 UID 与0700并验证最终权限。容器镜像内不含主密钥，主密钥只从独立 Secret 注入。跨 Pod 新建后立即读取、并发创建、防篡改、租户拒绝、重调度后恢复均未执行。

root 的 `scripts/backup-database.ps1`、`scripts/restore-database.ps1` 负责本工程 PostgreSQL dump 与新库恢复，可参见 `docs/evidence/backup-restore.md`；这不是完整灾备。还需要同一恢复点的加密 vault 文件、独立保护的 master key、MinIO 对象与版本、镜像 digest、配置/Secret 轮换记录及观测数据保留策略。恢复时先隔离写流量，再对齐数据库引用与对象/vault，最后以新库、新桶/前缀验证权限和完整性后切流；本轮未做全系统灾备或承诺 RPO/RTO。

## 观测交付边界

Javaagent 2.20.1 向 Collector 发送 OTLP；Collector 将指标暴露给 Prometheus、trace 发送 Tempo、日志通过 Loki 原生 OTLP 写入。避免给 Actuator Prometheus 接口提交静态服务 bearer token。Javaagent 未开启 HTTP 请求/响应头或 GenAI 消息内容采集，JDBC语句净化开启；Collector 进一步白名单化 span/event 属性、清空 status message、替换 span 名与日志正文，避免 Authorization、prompt、模型正文或异常消息出现在持久化日志中。此默认策略有意保留较少排障文本，敏感数据测试和字段白名单变更需运维验收。run/node_instance/attempt 只有应用实际埋点才有，agent不会凭空生成业务关联或并发计量。

配置保留 7 天（Prometheus另限4GB），五个 ops 容器的内存 limit 合计约 3.25 GiB，另需持久磁盘；各服务在此之外合计七个 JVM Pod。Loki/Tempo 自身未开业务用户登录，依赖 ops 网络隔离与运维入口控制；Grafana 禁匿名、禁注册、只用运维账号。没有门户导航、业务菜单或业务用户功能说明的改动。基础自动采集链路、可视化看板、告警、业务运行关联与保留磁盘规模均须在专用环境实际验收。

## 核对的官方机制

- Spring Boot 3.5 Actuator：[endpoints](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)。
- Kubernetes：[探针](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-probes/)、[NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)、[SecurityContext](https://kubernetes.io/docs/tasks/configure-pod-container/security-context/)。
- Docker：[Compose profiles](https://docs.docker.com/compose/how-tos/profiles/)。
- OpenTelemetry：[Collector配置](https://opentelemetry.io/docs/collector/configuration/)；Grafana：[Loki OTLP](https://grafana.com/docs/loki/latest/send-data/otel/)、[Tempo配置](https://grafana.com/docs/tempo/latest/configuration/)。

这些是机制与配置文档核对，不声称阅读了业务参考项目源码或完成了运行验证。
