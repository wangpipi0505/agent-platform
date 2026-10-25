# 算法调用协议阶段统一回归

2026-10-20，root 在独立新工程运行 `scripts/test.ps1`。10:44:28 同一 Maven reactor BUILD SUCCESS，耗时 07:17，进程退出 0。七模块共 **962 项**，失败、错误、跳过均为 0。模块结果来自这一轮日志的 Results 段，不拼接作者或审查者分批结果。

| 模块 | 测试数 |
| --- | ---: |
| admin-common | 10 |
| platform-gateway | 16 |
| app-management | 35 |
| engine-runtime | 427 |
| skill-registry | 386 |
| model-gateway | 66 |
| monitor | 22 |
| 合计 | 962 |

原日志 `.local/backend-algorithm-reviewed-all.log`，SHA256 `115308e5e9ddb115ff565a29c4aa049ef2dd7c7bdd8e031754b551437759ea11`；结构化结果见 `backend-algorithm-reviewed-summary.json`。前后各捕获七模块 src 树、根及模块 POM，共 272 文件，path/SHA 完全相同，见 `.local/backend-algorithm-source-before.json`、`-after.json` 和长期清单 `backend-algorithm-source-sha256.json`。四份原契约、v1.2 快照及 HTTP 模块边界检查继续通过。Git 无 HEAD，以实际冻结摘要、完整差异及重放固定范围。

本阶段纳入 expectedAttempt 完整调用身份传播，以及已独立审查返修的算法 Job UID/Pod 归属、单次发送、原调用查询、可信 Failed 不可重试、原始和解码体积边界、全链绝对期限及算法专用 MinIO 生命周期。详见 `skill-expected-attempt.md`、`review-skill-expected-attempt.md`、`algorithm-protocol-review-fixes.md` 和 `review-algorithm-protocol.md`。审查前的失败及证据保留；UID 冲突夹具已查明并修复，未通过放宽生产唯一索引、扩大超时或删除旧事实解决。

中断清理通常等待真实 SDK stage、原生 Call 和自有 executor 退出。若有限清理期限内仍无法确认停止，则保留所有权并记录 STOP_NOT_CONFIRMED/CLEANUP_PENDING，直到实际完成后释放；不声称所有返回均代表资源已退出。多分片大文件、永久不合作源、进程崩溃恢复和分布式容量不由本轮证明。

本报告生成时真实五场景进程纵切和本阶段制品更新尚待另记。Node runner 的 12 项护栏检查不计入 962，也不代表 JVM/PG/MinIO/K8s 已通过。生产 IAM/scope 门禁仍为 BLOCKED_IAM_SCOPE_ACCEPTANCE；17 功能项、34 任务、30 验收项以及容量、镜像和真实集群未整体签收。
