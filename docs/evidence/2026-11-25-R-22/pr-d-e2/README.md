# E2 两小时业务长稳

2026-11-25 22:20:36 至 2026-11-26 00:20:45（UTC+08:00）。使用 E2 正式入口、真实 user-mgmt 测试会话及 DeepSeek 已发布合成 CHAT 应用。每五分钟提交一条 AGENT 任务并重放，首尾各采一次。25 条任务均为 `SUCCEEDED`，重放均返回原任务。八个容器及六个 JVM 每分钟各有 121 个样本，采样失败数 0。

| 关键序列 | 开始 MiB | 结束 MiB | 变化 |
| --- | ---: | ---: | ---: |
| authority 容器使用量 | 513.10 | 899.60 | +75.33% |
| model 容器使用量 | 718.70 | 856.20 | +19.13% |
| model JVM RSS | 737.58 | 878.12 | +19.05% |

**RV-05 内存判据未通过。** authority 容器使用量在测试后段出现阶梯上升，超过细则 20% 上限。model 首末值低于上限，整段 JVM RSS 曲线仍有上升趋势。authority 没有纳入既有六个 JVM 抓取目标，其曲线取自 `docker stats`。Docker 容器内存和 JVM RSS 分别记录。

长稳开始、中段和后段的 model 已提交 Java 堆均为 216 MiB，直接缓冲均约 4.1 MiB。结束后的只读 JVM 快照显示 authority 已提交堆 160 MiB、已用堆约 123.8 MiB，元空间已提交约 72.9 MiB。两个容器 `HostConfig.Memory=0`。采样期间 `/proc/1/status` 观测到匿名 RSS 持续偏高。分配类别待内存诊断确认。

本次两小时窗口采用切换前镜像。已开发的门户 PLAN、用户检索与控制台修复在采样结束后上线，版本切换证据在上级目录。正式 PR-D 的故障注入分项仍按各自用例登记。

- [采样汇总](stability-summary.json)
- [业务任务原始记录](stability-tasks.csv)
- [采样运行日志](pr-d-stability.log)
- [Docker 数据](stability-docker.csv) 与 [Docker 图](docker-memory.png)
- [JVM RSS 数据](stability-jvm.csv) 与 [JVM RSS 图](jvm-rss.png)
