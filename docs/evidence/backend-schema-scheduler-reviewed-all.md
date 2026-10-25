# 调度、MCP及共享Schema阶段统一回归

2026-10-20，根任务在独立新工程运行 `scripts/test.ps1`，08:29:57单次Maven reactor **BUILD SUCCESS**，耗时06:39，工具进程退出0。七模块共846项，零失败、错误、跳过。模块结果全部取自同一日志的各模块Results段，没有拼接作者分批结果。

| 模块 | 本轮测试数 |
| --- | ---: |
| admin-common | 10 |
| platform-gateway | 16 |
| app-management | 35 |
| engine-runtime | 427 |
| skill-registry | 270 |
| model-gateway | 66 |
| monitor | 22 |
| 合计 | 846 |

原始日志 `.local/backend-schema-scheduler-reviewed-all.log`，SHA256 `0eee2c9ed8279f00ad6998f16cb1c2eec4eed63aee08a7df3d7e4d483742937f`。结构化汇总为 `backend-schema-scheduler-reviewed-summary.json`。本轮使用Java21、本工程.tools/m2、专用PG测试库/Redis7/MinIO及有限本地HTTP端点；没有测试跳过开关。保留既有JDK/Mockito提示及预期拒绝场景日志，不宣称日志没有警告或异常文本。

开始、结束各捕获七模块完整src树、根/模块POM，共262文件，path/SHA逐项完全一致：`.local/backend-schema-scheduler-source-before.json`、`-after.json`；长期清单 `backend-schema-scheduler-source-sha256.json`。前一675整轮及所有作者阶段原清单/报告保留，不被本报告覆盖。Git尚无HEAD，使用精确文件摘要固定阶段，不以空diff证明范围。

本轮包含已经独立关闭的G5停机准入/PG锁续租/大租约心跳三项，以及MCP有界SSE/原始Schema/精确工具绑定/queryOnly、资源工作预算/RE2/deadline/实际退出后释放许可和共享引擎输入输出防护。对应 `review-run-scheduler.md`、`review-shared-schema-resource.md`；后者只用安全源代码审阅及40项有限定向验证，没有重放原资源耗尽或慢正则探针。原自动安全审核中断仍保留为历史，不计作APPROVE。

循环引用Schema现在明确拒绝；RE2不支持语法、嵌套$id、非支持方言及过预算也明确拒绝。原递归正例全文/SHA及新的精确拒绝、非循环anchor有效/无效断言均保留，见 `shared-schema-budget.md`。这是兼容性收窄，不声称原全部成功断言未变或任意JSON Schema支持。183项位置矩阵及原18MiB异步产物回归继续通过。

前端127项/类型检查/三端构建和MCP页面独立25项及Chrome验证属于单独前端证据，见 `frontend-mcp-registration.md`、`review-frontend-mcp-registration.md`；未把它们计入846。未知创建ACK提示仅在SPA内防误重发，整页重载的服务端幂等和草稿CAS编辑仍需后续。

本报告生成时尚未用本制品更新运行服务，双实际engine JVM、新库故障接管、真实MCP隔离端点及真实异步评测页面纵切仍待根任务执行，须另记报告。500运行/5000SSE/200×5MiB及p95容量没有验收。生产IAM/scope发布门禁仍默认阻断，外部供应商/IAM/本体/K8s环境未提供，继续以明确隔离端点推进。846项不等于T01–T34/A01–A30或17功能项全部联合签收。
