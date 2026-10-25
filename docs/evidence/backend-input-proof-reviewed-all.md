# 输入、回调与对账证明阶段统一回归

2026-10-20由root在独立新工程执行 `scripts/test.ps1`，06:55:24单次Maven reactor BUILD SUCCESS，耗时04:57。七个模块共675项，0 failures/errors/skipped。原始日志 `.local/backend-input-proof-reviewed-all.log`，SHA256 `4dd0be1f7a8874d3df7ee5e94274187873b9359fb3af7a5189d935cae0b6179d`；模块汇总来自该日志每模块Results段，没有将之前分批结果相加。

| 模块 | 本轮测试数 |
| --- | ---: |
| admin-common | 5 |
| platform-gateway | 15 |
| app-management | 35 |
| engine-runtime | 365 |
| skill-registry | 167 |
| model-gateway | 66 |
| monitor | 22 |
| 合计 | 675 |

使用Java21、本工程.tools/m2、PG25432专用测试库及既有独立Redis7；ENGINE_PG_TEST=true。脚本先核对v1.2快照、原契约和七模块依赖边界，再执行完整test，没有测试跳过开关。运行开发库dev_v12与各测试库分开。保留既有JDK/Mockito/Lettuce提示和预期零发送拒绝测试的Reactor异常日志，不宣称日志无警告或无异常文本。

开始和结束各捕获七模块完整src树及根/模块POM，共235文件，逐项path/SHA完全相等。原始两份快照 `.local/backend-input-proof-source-before.json` / `-after.json`；长期清单 `backend-input-proof-source-sha256.json`。测试之后G5调度及MCP增量继续施工，它们不在本轮已验版本内；Git仍无HEAD，使用明确文件摘要固定阶段。

本轮覆盖已经独立关闭的输入Schema字面量P2、共有运行/START校验和会话预约P1、命名输入与不可变数据集、评测等待态、HTTP异步/CALLBACK与新不可变证明及票据P2；也保留原输出/计量/历史权限/确认/复现/发布/统计回归。其中183项是Schema位置边界参数矩阵，任务与功能出口仍按原T01–T34、A01–A30逐项验收。

同阶段另有真实网关/进程/PG纵切：`runtime-input-validation-live.md`、`frontend-test-inputs-live.md`、`skill-reconciliation-proof-live.md`，以及167版本再次执行的异步与签名回调脚本。它们使用明确隔离HTTP协议端点，没有真实模型/本体/IAM/K8s验收宣称。前端当前25文件83项、vue-tsc和三端构建属于独立前端执行证据，手机适配已独立关闭，见frontend-test-inputs-mobile-fix及review-frontend-test-inputs。

06:51的PG一致性备份已恢复到独立新库，5个schema共51表（含Flyway元数据）；证明SHA及命名输入数据、V214/V310/V502均核对，见backup-restore.md。此结果不替代对象/vault/master key一致灾备。

用户已明确暂未提供专用外部环境，继续实现并以隔离端点验证。后续推进G5公平调度/分池、G2引擎许可/取消三态/人工对账、MCP传输、其余管理功能与内存硬闸；生产IAM/scope发布门禁保持默认阻断，不把675项等同最终功能或生产发布签收。
