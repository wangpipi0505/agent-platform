# 算法协议阶段开发制品更新

2026-10-20，root 在 10:44:28 单轮七模块 962 项通过、272 份源码/POM 前后完全一致及算法返修独立审查批准后更新独立新工程开发进程。10:48:28 离线 package BUILD SUCCESS，带引号的 `-Dmaven.jar.forceCreation=true` 强制重建所有 JAR，避免嵌入旧 common。package 跳过测试仅用于制品生成，962 来自之前真实 reactor。

操作前检查新制品/归档目录不存在且均位于本工程；复制旧 846 六份 JAR 和日志后，逐进程再次核验 PID、创建时间、精确 `-jar` 参数及 TCP owner，才停止这六个进程。旧归档 `.local/artifacts/prior-846-20261020-1047/` 保留。新不可变制品 `.local/artifacts/algorithm-protocol-20261020-1047/` 固定六份 JAR/SHA，target 与不可变副本逐一相同，均嵌入同一 current common、RE2/J1.8、networknt1.5.6；skill 包含 V311 且无迁移前旧预算类。

六服务重启后再次核验归属及实际健康 UP：gateway52256/app43548/engine31972/skill55376/model57920/monitor4344，端口仍为本工程18080–18085。完整清单、摘要、嵌入验证及检查时间见 `backend-algorithm-deployment.json`。一次身份检查外层误把 PowerShell-only helper 后的空 LASTEXITCODE 当失败，已按异常与实际六条记录复查退出0；未通过忽略真正身份异常处理。

runner 两源码、完整补丁与原 fixture SHA 已核对；root 为本阶段两个不可变 JAR 生成 `.local/algorithm-reviewed-artifact-lock.json`。这只准备交接，不代表独立 runner 审查或五场景真实进程测试完成。真实结果另记，不从 JAR 更新推定 K8s、容器/RBAC、生产 IAM、容量或全功能通过。
