# R-22 E2 执行方证据

[30 分钟采样](rv05-nmt.csv) 包含四个 JVM 各七个时点的 native committed 与 RSS；[分配类别](rv05-nmt-categories.txt) 单列 0、15、30 分钟。每个 JVM 在本次观测期内 PID 一致。authority RSS 从 511968 上升到 562304 KiB，native committed 从 438951 上升到 447522 KiB。model RSS 从 1007460 到 802644 KiB，中途回落。authority 尾段仍有增长，保留观察，未据此签认两小时长稳。

[隔离启动负向](release-gate-negative.txt) 中门禁关闭后进程退出 78。真实门户、模型和抓取已验证。交接单要求的正式门禁置位及 HTTP 403、双角色走查、四卡完成后的 PR-A/B1/C 综合复跑仍待执行。
