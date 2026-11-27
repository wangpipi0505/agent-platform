# S-3 专项复跑

候选 `57945918`，E2，2026-11-27。本轮现场专项已执行，下表保留每项证据的覆盖范围。

| 项 | E2 实测与证据 |
| --- | --- |
| R-42、R-46 所有权与第二主体 | 真实 user-mgmt 第二主体 `operatorA` 在授权应用内建会话并三轮成功。跨主体、跨应用各返回 403，审计分别为 `SUBJECT_MISMATCH`、`APP_MISMATCH`。撤销后旧票为 401。见 [S-1](../2026-11-27-scenario-S1/README.md)。S-2 的工作区和规则读取失败独立记录。 |
| R-47 | 草稿 409 `APP_UNAVAILABLE`、不存在 404 `APP_NOT_FOUND`、已发布 200。已发布任务 `r_819f659084d84e80aa38b3faa2b9ffcc` 到 `SUCCEEDED`。见 [接口回执](runtime-results.json)。 |
| R-48 | 按 6 秒间隔发起第 1 轮轮询。详情与结果均 HTTP 200、`FAILED / SYS-MODEL-503 / retryable=false`，同请求号复用同一任务。库内引擎发送尝试数 1、供应商流水和配额预留均 0。临时集成凭据撤销 200，已恢复配置的 DeepSeek 代理。见 [接口回执](runtime-results.json)和[收尾实测](../2026-11-27-scenario-freeze/final-verify.txt)。首次采集因驱动读取字段名有误已单独留档。 |
| R-49 | 以部署镜像 JDK 21 和隔离 PostgreSQL 16 运行 `ModelFailureTaskPostgresTest`，3/3 通过、0 跳过。首次停用夹具时立即检查报清理未完成，随后端点复跑前和收尾时均确认容器已自动移除。见 [JUnit 输出](r49-junit.txt)、[复跑前核对](../2026-11-27-scenario-freeze/r48-retry.txt)和[收尾实测](../2026-11-27-scenario-freeze/final-verify.txt)。 |
| R-50 | 本轮 E2 隔离库定向运行 `RunObservationMetadataHttpPostgresTest` 和 `RunObservationStatusHttpPostgresTest`，35/35 通过。开发侧相同候选的单次 Maven 全套 1,222 项，1,156 通过、63 初始化错误（4 类缺独立进程属性）、3 跳过。独立进程逐例 63/63 通过，按类与方法合并 1,219 通过、3 跳过。见 [E2 原始输出](r50-observation.txt)、[候选对照](r50-source-verification.json)和 [R-50 开发证据](../2026-11-27-R-50/README.md)。 |

S-2 的 ADMIN 读取 403 已经真实门户票直接复现，详见同份接口回执的 `workspaceRetest`。
