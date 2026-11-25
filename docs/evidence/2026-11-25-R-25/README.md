# R-25 发布态模型链复验

2026-11-25，采用版本化发布重放。由正式 IAM 网关提交 DIRECT 评测、完成预检，再提交发布，将实际模型条目冻结到新版本。旧 V901 快照保持历史记录。

| 阶段 | 本地实测 |
| --- | --- |
| 评测、预检 | 两轮 SUCCEEDED，报告 ACCEPTED，预检通过 |
| 发布提交 | HTTP 200，应用 `r37chatf52be0ad` 发布版本 1 |
| 发布快照读回 | `dependencyVersions.modelRefs` 有一条，`bindingId=8`，摘要 `d1cf7b04b0092637ceb0903ac32bbe0eb3da7477ff1c3553ee857792f30c0ba8` |
| 门户网关 AGENT 任务受理 | HTTP 200，`task-4c3948d31c184fb8ac96` 初态 QUEUED |
| 隔离 PostgreSQL | 任务 SUCCEEDED，分派 DONE，运行 `r_18c244238a5c4f2896e079a68a6982bb` 为 SUCCEEDED |
| 运行产出 | 冻结模型引用仍为一条，返回回环模型文本 `r37-answer-one`，用量 24 输入加 4 输出 token |

本轮先复现发布提交 403 和发布态 AGENT 未接入 IAM 引擎接收链。发布提交复用按应用绑定的发布委派。引擎仅在已授权且钉住发布版本的 AGENT 路径启用首轮，DIRECT 正文逐项复算候选摘要。

规则回归使用 `PromptTemplateSourceTest`，正向覆盖发布态内联正文，负向覆盖正文被改写。应用发布事务回归 13 例。引擎种子及提示词定向复跑 8 例通过，零跳过。联验步骤与原始输出见工作区 `.local/r25-r33-verify.ps1`、`.local/r25-r33-verify.log`、`.local/r25-terminal-http.log`。

E2 原种子应用的版本化发布重放及真实供应商复跑待执行。真实供应商历史证据见台账的 E1 PR-B1 批次。
