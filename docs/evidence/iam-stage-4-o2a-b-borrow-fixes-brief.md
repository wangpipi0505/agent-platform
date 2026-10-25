# O2a B 借用输出修复任务

本任务是 B 独立审核后的有限修复；后续仍要独立复核。先读此文件，它是本任务要求。唯一 workdir 为 `G:/207/项目/算法重组工具/代码/agent-platform`，任何 shell 必须显式设置。不得访问相邻旧工程。v1.2、四份契约、POM、迁移、前端、HTTP receiver 和 C 功能不变；不做 commit、不清理他人文件。

## 输入与冻结边界

- 审核：`docs/evidence/review-iam-stage-4-o2a-b-material-reader.md`，11153B，SHA256 `6cf9b988d040ed6f7ad5c9d631b620dd7e5d117e91ad81cb62a9cb9221190b03`。完整读到 EOF，先以具名代码核实 I1/M1/M2。
- 原正式要求：`docs/evidence/iam-stage-4-o2a-b-material-reader-brief.md`，以及 budget-decision、parser-cache-decision、inherited-test-decision 三份同前缀文档。它们的预算、权限、实际 finally、历史证据边界继续生效。
- 修复前最新源码清单：`.local/iam-o2a-b-material-reader/labels-fix/source-after.json`，593文件，112856B，SHA256 `21fc2a4425e5d0767cb3627719ddfce395c97b9ca3718ebb205a18c90e0e9439`。基线原文件可从同目录 `frozen-overlay/` 取三个允许文件，无需重新扫描旧源码树。
- 原 handoff/inventory/report 及其下所有文件均冻结，只读。root 已验证原自有4895叶子、两次593文件 replay，并将最终458 target 输出复制到 `.local/iam-o2a-b-root-verified-labels-target/`。不要重新运行作者旧 freeze/finalize/seal/test/replay helpers，也不要修改旧归档。root 原审核日志 `.local/iam-o2a-b-root-audit-progress.json` 只读。

## 唯一源码写范围

1. `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java`
2. `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderPostgresTest.java`
3. `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderBudgetTest.java`

不增加生产类，不改已有公开方法签名，不改变行摘要、Capture、MaterialScope、既有权限语义、两个 permit 上限、原192MiB/64MiB/8MiB等相交预算。注释说明所有权和计费依据。先失败测试再实现。

## I1：内部 payload 所有权

`Borrow.writePayload(OutputStream)` 不得把 emission 自有 byte[] 交给外部可覆写方法。外部接收者可以保存它自己收到的数据，但不能得到或改写 reader 的内部 backing。

最小有限 copy/scratch 可以采用，但分配前必须有明确预算，实际占用和临时副本须落在原192MiB总上限内；如使用已预留8MiB固定 scratch，说明所有并存项如何相交，不能拿已经占满的同一项重复许诺。输出返回前保持所有权、current/close guard、原 permit；不要 flush/close 调用方输出。要考虑输出流回调重入同一 Borrow 造成嵌套临时副本无界，使用最小有限 guard 拒绝，或给出同等有限证明。允许同一 active consume 内顺序多次输出，不引入“只能输出一次”来掩盖问题。

新增真实 PG writer/response 的具名回归：第一次 write 的 sink 保留并修改收到的数组，第二次输出仍与原已验证 payload 相同；第一次收到的数组与第二次输出不共享 reader 内部所有权。覆盖重入拒绝、异常后状态清理、阻塞输出期间 close 不提前归还两个 permit、实际 finally 后释放；可以在一个具名用例内分支验证，也可少量独立用例。禁止只靠反射返回类型、假 proof、伪造 emission 构造或 mock 数据源证明。

## M1：单项 parser 机械边界

为 root 字段名新增唯一具名测试：1048576字符 exact、1048577字符 +1。明确只核 parser 单项边界，不冒充同时符合业务 raw/model 等相交上限。使用现有 parse 测试入口，不放宽配置和业务预算。无需为了已有正确边界制造虚假 RED；记录它是补覆盖。

## M2：阶段输出准确

原报告冻结不改。在本任务报告中引用原报告134/187行和实际 final01/final02 原始日志，明确纠正：最终阶段无可归属的 deprecated/unchecked warning；final02 为 Nothing to compile，Flyway 为 INFO，实际乱码如实保留。若不再追溯历史提示，可撤回原阶段归属，无须为文案重编译/重跑。后续 root 文档使用该更正。

## 验证、产物与报告

- 唯一执行入口为未修改的 `.tools/run-iam-module-tests.mjs`，独立 DB `agent_platform_iam_o2a_b_material_v12`。不执行原 final01 的65项，不迁移旧 Initial 测试，不重复已绿 PG18。针对新别名用例先保存真实 RED，再修复；GREEN只跑覆盖本次修复的具名用例。最后一次验证可选 Budget 全类（原13+新1）和新 PG 用例，禁止无依据重跑旧 PG 整类。所有实际命令、exit、raw Running、对应 XML 精确关联，不累加阶段当全套。
- 新 local 独占目录 `.local/iam-o2a-b-borrow-fixes/`；新 evidence 独占目录 `docs/evidence/iam-stage-4-o2a-b-borrow-fixes/`；新辅助脚本仅 `.tools/iam-o2a-b-borrow-fixes-*`。原目录一律只读。
- 只对本任务593清单中三个允许文件更新 SHA 得新源码清单，其他590继承；保存三个修复前/后精确源文件。不要走全树和旧大目录。target 仅 admin-common/engine 实际 createdFiles/inputFiles/resources/test-fixture JAR/actual Running XML+txt 形成有限 overlay，基线为 labels-fix2054，新增任务 dumpstream 单独归档、不进逻辑 target。如任何以前成功项未受改动，不重跑验证。
- 每个成功物理阶段写 journal 与 hash cache；后续 metadata helper失败只恢复未完成项，不重复测试、PG样本、hash、replay。源码新 patch 仅这三个文件，私有隔离目录以修复前593冻结源清单构造，原始首次 check/apply 的命令和exit保留，应用后所有593精确等同。不得在 apply 后复制修复后的文件。没有 Git HEAD，私有Git的所有设置限定每条命令；不做commit、不改项目Git属性。
- 最终报告 `docs/evidence/iam-stage-4-o2a-b-borrow-fixes-report.md`：实现/账本/RED GREEN/命令输出/准确测试列表/文件身份/三项审核处置/噪声与限制。旧报告保持冻结，使用新修复报告作为追加 epoch。
- 同新 evidence 目录交付 handoff.json、own-inventory.json。inventory 仅本任务自有 roots、sole self exclusion；包括私有 Git 元数据和脚本；报告/handoff先固定再一次封存，inventory只引用路径避免循环摘要。归还源码/target写权后不再改任何产物。
- 返回少于15行：DONE等状态、无commit、覆盖测试结果、报告/handoff/inventory的bytes/SHA与担忧。不要把完整清单或旧历史贴回消息。
