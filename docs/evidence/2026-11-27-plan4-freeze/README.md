# 本人 PLAN 候选部署核对

冻结标签 `r22-20261127-plan4`，代码 `e122a508d412d2674ac5dc7de54cf4e97fa1e181`。 核对时间 `2026-11-27T04:03:18+0800`。八个容器的镜像 ID 与冻结包相符，八个健康入口返回 200，六份静态资源摘要相符。app 的 V128 迁移成功，共十条角色能力。收尾恢复常态 authority 配置、撤销临时目录凭据与平台角色授予，并停止本轮 Java 宿主。

[远端主干与标签](remote.txt) · [运行镜像](image-ids.txt) · [迁移](plan-migration.txt) · [静态文件](assets.sha256) · [切换前备份核对](prebackup.txt) · [最终健康](final-health.txt) · [监控目标](prometheus.txt) · [临时资源清理](cleanup.txt) · [回归测试](targeted-tests.txt)
