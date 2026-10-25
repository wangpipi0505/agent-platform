# O2b D selector 旧测试独立迁移（作者完成，未 Accepted）

本任务仅修改 `RunObservationSelectorsTest.java` 第一个既有方法体。唯一一次指定完整三方法阶段已真实完成：**3 tests，3 passed，0 failures，0 errors，0 skipped，exit 0**。原 D 六类结果永久保留为 **87 tests，86 passed，1 failure，0 errors，0 skipped，exit 1**；本次没有重跑该 87 项，也不将它改称全绿。

## 依据与范围

按正式 selector-migration brief、AGENTS.md 和独立 D review M1 执行 receiving-code-review、TDD、verification、ponytail 流程。原 87/1F 中实际失败 XML 就是授权的 RED；其 `statusPurposeIsBidirectionalAndOnlyAcceptsFreshPortalSnapshot:15` 错把 engine EVENTS 放进拒绝表。实际公开 `requirePurpose` 方法至文件末尾与 Accepted C 私有基线字节相同，SHA256 `400c8e040524f0f9ef21918e397d528d40dd92a9e62303fb684c2eca851f52f1`。未修生产代码，也未重新制造一次 RED。原 XML、命令、raw 与冲突记录身份见 `iam-stage-4-o2b-d-selector-migration/red-provenance.json`。

将 `EVENTS_ACTION` 迁入正例，与 `STATUS_ACTION` 分别使用真实 `events` / `status` selector 验证 engine/gateway/delegated/PORTAL/PORTAL/run-snapshot 用途配对。两者都继续拒绝错误 resource kind/digest/id、target、caller、mode、source 和 origin；旧 Q action、执行 POST、run.advance 不得携 snapshot 通过。固定私有 action `gateway.run.events.observe` 分别对 gateway 与 engine 两个真实目标经公开 `requirePurpose` 被拒绝，没有放宽 G。

三个方法名称与顺序不变，其他两方法、imports、常量、类定义及第一方法体外所有字节相同。prefix 347B / SHA256 `9e1b6f34ac15061e41405c71d10535cc2695e7c38f592a320dbc3d4f54115c46`；suffix 3604B / SHA256 `18a69ad119942dacdc8576552585519b96b1f9727d2e8c32dc0d578a1a774811`。旧测试 5714B / SHA256 `f5224ed46f0673cecc30f511c8f67e112f4eb8323ea2ec819c40c092aa9d8d78`；新测试 6812B / SHA256 `2559408706e8c2a94add22b0dc03335659e701f10848983ffc28ccbc37c4289e`。详见 `scope.json`。

source 数始终 603，唯一 delta 是上述方法体；production delta 0、fixture delta 0。POM、runner、其他源码、旧 D/C 报告证据、八索引均未写入。未访问相邻工程，未 commit/deploy。八索引由 root 在独立评审通过后统一收口。

## 唯一实际验证

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2b-d-selector-migration-final admin-common RunObservationSelectorsTest
```

执行前保存实际 source epoch；退出后立即保存 command/cwd/exit/stdout/stderr/raw，再归档本次 actual Running 的一类 XML/txt。实际运行 `cn.agentplatform.common.authority.RunObservationSelectorsTest`，包含原三个方法，3/0F/0E/0S，exit 0。该类只验证 selector grammar/purpose，不验证 PG 新鲜度或真实 HTTP/SSE。没有运行 PG、C29/24、D87 或全后端。

raw 4010B / SHA256 `4d229637cde78649e1b5fd2986b784f12e1496413d91d880573d512dba36e3ef`；XML 33791B / SHA256 `f42f59366d25e16f797f30ef50fa9822bf34164230a489e010c11cc8018bfa09`。完整身份见 `test-results.json`。Maven main compile 为 up-to-date；testCompile 实际编译 31 个 test source，重建 fixture test JAR。原有 GatewayRunAuthorizationTest 的 unchecked 编译提示保留在 raw；不声称日志无警告。

本次只收集 common 两 compiler 阶段的四份实际 `.lst`、其 created classes、具名资源、fixture test JAR 和 actual Running 一类 XML/txt。compiler lists 用 `copyFileSync` 保存原字节，原始与归档 bytes/SHA 一致；inputFiles 只记录编译输入，不当作测试运行证据。203 条有限 overlay 合并继承 target 后为 2118 条逻辑清单；未全树扫描旧 target。新增 1 份 dumpstream 独立归档，不混入逻辑 target。见 `compiler-lists.json`、`diagnostics.json` 及 local `finite-target-overlay.json`、`target-after.json`。

## 私有回放与交接

仅创建一个完整 603 文件私有 Git 基线；所有具名文件先逐项核原 bytes/SHA。init、baseline add、write-tree 均等待真实 exit 0，并立即写命令记录与 journal，然后才执行依赖操作。在同一基线生成一个 raw patch，将该唯一测试还原为 before 后，**首次且仅一次** check=0、apply=0。没有 apply 后 source copy，没有 commit。603 个私有回放文件的名称、bytes、SHA 与最终 epoch 完全一致。

patch 5515B / SHA256 `adc78ae984e87721302cbb14ad544960cbd7470a1b0b7ace2e0b44661feb7619`；replay commands 13408B / SHA256 `a37e872a2be4ffc376b83c176d3c495a612400d4c19a0dfd13c13f9956aba705`。基线 tree `2030aa83f8d529b86be671de0b0b27325a32c50c`。见 local `replay-complete.json` 与 evidence `replay-commands.json`。

- source before：`.local/iam-o2b-d-selector-migration/source-before.json`，603 项，继承 SHA256 `ab4a97cab7548de056a5548e129b49c6e19c0c4692ac82fa8f4857e19ec0087c`。
- source after / final epoch / replay source：603 项，115043B / SHA256 `ab0310201aff0a59e87f73b0692bf6e89b050591d7f33c2b4f099da3a97283c4`。
- target before：继承 D 2118 项，SHA256 `5f425586d177676f2a205b5330ef9ffa63ef21ce9fd1cdd2405529aef6ce1191`；本次 target after 与 overlay 的准确身份由 `handoff.json` 给出。

`verification.json` 记录本任务唯一有限封存验证。`handoff.json` 使用 file/bytes/sha256 身份并在数组前注明 count/schema；`own-inventory.json` 仅排除其自身，覆盖全部自有 local/evidence/helper/report，包括私有 `.git`。所有成功物理阶段立即记录 journal/hash-cache。旧 D 首次 patch incident 与 JSON Buffer compiler 归档保持原貌，本任务没有替其修复或补造历史证据。

封存后交还并冻结本任务全部 source/target/local/evidence/helper/report 写权。作者完成不等于 Accepted，等待 fresh independent review 与 root 有限审计；未实现或验收 E/F、真实 HTTP/SSE、PG 新鲜度及外部 issuer 生产能力。
