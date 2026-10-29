# EX-05 契约与生成类型门禁

日期：2026-10-29。执行主线程：root；OR 契约作者：ex04_test_gate；AP 静态独立复核：algorithm_interrupt_readonly。基点 AP 67d1d41d / OR da3345a。当前进行中，尚未提交或关闭。

本卡只修改 AP contracts/skill-invocation.md、scripts/verify-contracts.mjs，OR contracts/openapi.json、web/packages/api-client/src/generated.ts、deploy/Verify-ApiContract.mjs，以及本卡证据和三份主入口。并行 EX-15a 的 Java 改动独立归属，不能混入本卡提交。未修改冻结 freeze.json、原文快照、Java、迁移、服务权限或数据库产品逻辑。

## AP 实红与消费映射

1. 旧 skill-invocation.md 63,602 字节 SHA-256 为 399f6248e3b24bf10c1489b670238b1a8b7cc67ee5d5ab0e1eb4c6d1a43d66a5。新增门禁在旧契约上实际退出 1，诊断 EX-05 消费映射增补缺失；原件见 red-ap.log。
2. 只追加 §16；固定长度历史前缀仍以原 freeze SHA 校验，另外三契约与原文快照仍全文件校验。新增 --skill-contract 是临时变体输入，原 --file 能力包参数仍原样转发。
3. node scripts/verify-contracts.mjs 退出 0；原 G-9 门户面27/STALE5告警保留，未降低原门槛。见 green-ap.log。
4. node docs/evidence/2026-10-29-EX-05/check-consumption.mjs 共20检查通过（1有效、19拒绝）：原正文漂移、增补缺失/重复/边界/游离尾文、7键缺失、4种语义替换，以及原 EX-09 缺绑定/明文身份/未知 kind 三负例。每次调用真实仓内总门禁，拒绝要求 native=1 且诊断一致；见 consumption-checks.json。
5. 试写检查器曾错误地从整个历史文档首个 JSON 代码块提取到末尾，导致本检查器 JSON.parse 失败、尚未执行变体。已仅把测试提取范围限定为追加的63,602字节之后；产品验证器本来就限定该范围。没有改断言或产品规则来消除该准备错误。

独立静审记录（algorithm_interrupt_readonly）：冻结前缀、七键映射与真实 Importer/Catalog/Rules/SecretVault 一致；未把 OFFSET 元数据声明当自动翻页或行数强制执行；原正文与20项实际结果相符，无阻断。审阅者未作者实现，也未冒称运行了命令。OR 全范围与最终 index 审阅仍待。

## OR 现状与实际红

证据位于 OR 同名目录。注册本仓 API PID 49716 的 /onto/auth/me 实际401，临时管理会话只读导出 v3/api-docs 后注销；凭据未输出或落盘。实时129路径，旧提交109路径；只取本卡7路径10操作，其余20差异不顺带覆盖。旧契约在新增 Verify-ApiContract 上实际 native=1、10操作逐项 operation missing；red-or.log/json 保留源码前后指纹。旧生成物 typecheck-before.log 实际退出0。

OR最终补齐仅7路径10操作，原109路径/126模型/142操作不变；149项门禁及变体通过，最终生成两次8AF1A20…一致、typecheck/build通过。全前端首轮268P1原5000ms超时保留，定向整文件8P后同条件最终全33文件269P；格式前后同41个原失败文件，新差异0。独立终审、精确本地提交与三入口快照待收口。新门禁不替代 EX-07 真实联合调用；API存在不等于本卡证明其业务闭环。

## 回滚

仅反向本卡五个实现/契约/生成文件的授权差异，契约和生成类型同步恢复；保留原红、回归和审阅证据。不得回滚并行 EX-15a，不推送或部署外部环境。

空白校验：旧冻结契约按既有-text保持CRLF，默认diff-check把81追加行CR报尾空白；单命令启用cr-at-eol并保留其余真实空白检查后通过，详whitespace-check.json。未给源文件新增-whitespace属性，未修改冻结正文。
