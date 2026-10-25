# 独立部署候选与验证证据（2026-10-20）

范围仅新工程 `G:/207/项目/算法重组工具/代码/agent-platform`。本任务新增 `deploy/`、`.dockerignore` 与本报告；没有更改业务服务或前端源码，没有提交 Git。未读取、关联、复用相邻工程、已有容器、旧数据库/WSL 或 kubeconfig；没有启动 Docker Desktop/daemon、全局功能、镜像推送或外部部署。

## 已交付

六后端容器构建方案（Java 21，沿用 Boot 3.5.0）、三种前端产物（admin/WebApp/带摘要助手）组合 Nginx 镜像；独立 Compose 项目与应用/ops profiles；Kubernetes 两命名空间、12个 Deployment、ClusterIP Services、默认拒绝 NetworkPolicy、engine两副本/PDB/分散调度、RWX vault PVC；OTel Collector + Prometheus + Grafana + Loki + Tempo 配置；显式发布门禁与 digest 检查脚本。

安全默认：dev-auth=false；IAM/scope 未验收时后端进程拒绝启动；镜像占位值不能拉起；所有容器UID/GID10001、只读rootfs、资源限额和最小capability；Java分代ZGC、70%堆、direct128m；HTTP探针和graceful/SIGTERM；业务代理不转发内部API/Actuator/dev-token；ops不加入业务门户。SecretVault必须RWX与独立主密钥Secret，未生成或提交任何明文Secret。

`deploy/k8s/engine-hpa.pending.yaml` 和 `dependency-egress.example.yaml` 为明确排除出默认组装的候选/示例；没有宣称并发HPA、外部出网或算法Job权限已生效。ops PVC、专用PG/Redis/MinIO与集群均待运维注入。交付依赖版本为PG16.15、Redis7.x（固定候选7.2.16），不把本机历史8.8实例或旧工程资源当部署依赖；本任务未切换本机Redis进程。

## 实际执行与结果

以下均为本工程目录下执行；每条成功命令返回 exit 0。没有通过这些命令访问集群或启动Docker daemon。

| 检查 | 实际结果与边界 |
| --- | --- |
| `python deploy/validate.py` | 源43个Kubernetes对象通过官方v1.33.0 API结构校验，12工作负载安全断言；3项负向变异（允许写rootfs/开启dev-auth/缺memory limit）均被拒绝 |
| `.tools/deployment/kustomize.exe build deploy --output .local/deployment-rendered.yaml` | Kustomize5.7.1离线组装成功，含5份ops配置的ConfigMap；未使用kubectl/kubeconfig |
| `python deploy/validate.py --manifest .local/deployment-rendered.yaml` | 渲染44对象通过同一固定官方schema与安全断言 |
| `docker compose -f deploy/compose.yaml config --no-interpolate --no-env-resolution -q` | Compose5.1.4本地解析成功；没有解析真实密钥环境文件、没有daemon调用 |
| `.tools/deployment/otelcol-contrib.exe validate --config=deploy/ops/otel-collector.yaml` | Collector contrib0.137.0原生配置校验成功，包含privacy transform、OTLP receivers/Tempo/Loki/Prometheus exporters；没有执行collector serve |
| `python deploy/test-static.py` | Java21编译容器HTTP健康探针并对本机随机临时端口测试：200退出0、503退出1；两个Compose release失败路径实际运行，拒绝mutable/latest镜像、拒绝IAM门禁未通过；fixture无真实凭据 |
| `deploy/download-tools.ps1` 与后续官方单项下载 | 初版脚本执行成功；后补schema/Collector/Kustomize分别实际下载并逐项SHA256校验。最终扩展脚本已通过PowerShell语法检查，未整体重复下载。全部只写新工程.tools/deployment，不更改PATH或全局安装 |

API schema来自官方 Kubernetes v1.33.0 `api/openapi-spec/swagger.json`。仅对Swagger `format=int-or-string`按官方语义展开成JSON Schema的string/integer union；未自造Kubernetes字段或绕过类型校验。该校验不能替代APIServer admission、CSI/CNI或实际集群兼容性验收。

健康探针初版使用 Java HttpClient 在本机遇到Windows NIO/UnixDomain socket `Invalid argument: connect`，首次行为测试失败；已改用更小的同步 HttpURLConnection 并重跑200/503测试通过。没有修改全局JDK或系统网络设置。

## 固定下载来源及摘要

| 产物 | 官方来源 | SHA256 |
| --- | --- | --- |
| Kubeconform0.7.0 Windows amd64 | [官方release](https://github.com/yannh/kubeconform/releases/download/v0.7.0/kubeconform-windows-amd64.zip)；与同release CHECKSUMS比对 | `9cb75551d81c909c2241ab383ced2be68363b5bfb15fd989badcc5a63bea5d7e` |
| OTel Javaagent2.20.1 | [官方release](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.20.1/opentelemetry-javaagent.jar)；官方release API digest核对 | `d94e9fc648cab37e540571352937acc06b8014182ddbd2d93a6c08641c84e48e` |
| Kubernetes v1.33.0 OpenAPI | [官方tag源码](https://raw.githubusercontent.com/kubernetes/kubernetes/v1.33.0/api/openapi-spec/swagger.json)；另核对GitHub官方blob SHA1 `65ced1bc61913cefb59ef5f2f368baccb4b99caf` | `2af5abf98554af1599e516f31f7f1698085e6faa12b816401f569c097b9f7ef6` |
| Collector contrib0.137.0 Windows amd64 | [官方release](https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.137.0/otelcol-contrib_0.137.0_windows_amd64.tar.gz)；官方release API digest核对 | `44dfc0da231ad3f99b98aa7cfc77c2a9c287b8c11d8f29960a2880f5e9ed3136` |
| Kustomize5.7.1 Windows amd64 | [官方release](https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize/v5.7.1/kustomize_v5.7.1_windows_amd64.zip)；官方release API digest核对 | `4704f8b47582c1c52bf76a50b37178f7f7de17f892713794a8bf44b01b50e0ce` |

Kubeconform仅下载并核对版本，未把其默认远端schema当作验收依据；本轮实际schema校验使用固定官方OpenAPI与本机已存在的jsonschema4.26.0、PyYAML6.0.3，无新增Python全局依赖。

## 未验收与继续门禁

未构建或运行任何镜像；未验证Nginx原生配置、Prometheus/Loki/Tempo/Grafana原生配置或观测端到端链路；未进行真实K8s/CNI/CSI/RWX权限、多Pod SecretVault共享、滚动关闭/kill-9恢复、SSE5000+、500并发/首token p95、HPA指标/adapter、算法Job/RBAC、真实IAM/内部service scope、外部供应商或数据源调用验收。观测业务关联和业务看板不由Javaagent自动补齐。

默认配置是可审查、可静态检验的部署候选，并非一键生产安装或生产完成证明。发布负责人仍须补齐独立资源标识、实际镜像digest、秘密注入、入口TLS/运维网段、敏感字段流量测试及以上运行证据。root的数据库dump/44表新库恢复证据另见 `docs/evidence/backup-restore.md`，本任务没有重做或冒认该验证；未包含vault/master key/MinIO一致恢复，因此不能计作完整灾备。

## 门户路径调整后复验

主任务将管理门户明确放在`/console/`，WebApp保持`/w/`，管理API保持`/admin/`，避免静态入口与API前缀冲突。`Dockerfile.frontend`、构建参数和Nginx候选同步调整。04:41再次实际执行`python deploy/validate.py`（43对象与3负向安全检查通过）和`python deploy/test-static.py`（HTTP探针200/503及两类发布拒绝通过），exit0。未借此声称验证了Nginx原生配置或镜像运行；该项仍保留在上述未验收清单。
