# R-32 六服务指标与独立内存曲线

2026-11-25，在本机 Docker Linux 引擎使用 JDK 21 构建的六个应用镜像运行 IAM，观察六服务管理端口。其中网关业务端口 18090，其余为 18081–18085。共同使用独立的回环管理端口 18180–18185。

| 证据 | 结果 |
| --- | --- |
| 改造前请求六个业务端口的 `/actuator/prometheus` | 六个 401 |
| 重建镜像后访问六个管理端口 | 六个 200，返回 `process_uptime_seconds` 和 `process_resident_memory_bytes` |
| Prometheus `/api/v1/targets` | 六个 `platform-services` target 均为 up，`lastError` 为空 |
| Docker `stats --no-stream` | 六个容器各八条，共 48 条 |
| Prometheus `query_range` | 六个实测 JVM RSS 序列 |
| 最终镜像启动检查 | 六个应用容器均为 healthy，分离后的管理端 readiness 探针生效 |

```powershell
docker compose -f deploy/compose-test.yaml -f deploy/observability-test.yaml config --no-interpolate --quiet
docker run --rm --entrypoint /bin/promtool -v "${PWD}/deploy/prometheus-test.yml:/probe/prometheus.yml:ro" prom/prometheus:v2.54.1 check config /probe/prometheus.yml
docker stats --no-stream --format '{{json .}}' codex-r32-gateway codex-r32-app codex-r32-engine codex-r32-skill codex-r32-model codex-r32-monitor
```

图表使用同一段本地空载采样窗口，单位 MiB。Docker Stats 为容器内存口径，Prometheus 为 JVM 进程在 Linux `/proc/self/status` 中的 VmRSS。两个指标各自采样绘制。OTel Collector 未加入本地指标复验，其自监控目标单列于原始返回。

- [Prometheus targets 原始返回](targets.json)
- [Docker Stats 样本](docker-stats.csv)和[独立曲线](docker-working-set.png)
- [JVM RSS 源数据](jvm-rss-range.json)和[独立曲线](jvm-rss.png)

E2 目标环境镜像部署与 PR-D 业务负载复测待执行。
