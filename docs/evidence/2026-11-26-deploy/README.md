# E2 冻结候选部署

E2 在 2026-11-26 18:02 切换八个业务镜像，标签为 r23-20261126，提交为 42e7dbb8f3b1223edc91c4bb404171e5ec99f0e2。本机编译前端三个产物，使用 E2 已验证的 nginx 运行时封装；七个后端使用同一冻结提交的 Java 21 Maven 产物。导入 E2 时对照八个 config SHA，切换后再次核对实际容器的 image ID。原始清单、备份摘要、八个探针、迁移记录与六个抓取目标见 [切换自检](e2-selfcheck.txt) 与 [最终自检](e2-final-health.txt)。

PostgreSQL、Redis 沿用原实例。E2 回滚素材位于 /home/agent-platform/r22-candidates/r23-20261126，包括 before.dump、before.toc 和 before 目录。备份目录已由 pg_restore 读取。app-management 将新签发的集成凭据写入专属加密存储目录。真实门户会话依赖工作机 user-mgmt 和到 E2 的回环转发。正式 app 迁移最后一条为 V127，交接单 V128+ 仍待核对侧校准。
