# O2b D selector-only migration 独立评审

结论：**Spec compliant / Approved**。Critical 0 / Important 0 / Minor 0。本结论只覆盖正式 brief 授权的 selector 测试迁移；作者完成与本评审批准不替代 root 的有限物理审计及最终 Accepted。

## 评审输入、方法及边界

独立 reviewer 完整读 `.local/iam-o2b-d-selector-migration-root-review-instructions.md`、正式 `docs/evidence/iam-stage-4-o2b-d-selector-migration-brief.md`、作者 report 和本项目 `AGENTS.md`，随后一次完整读唯一 full-context package 第 1–80 splitlines 至 EOF，无截断、无重叠重读：

- `docs/evidence/review-iam-stage-4-o2b-d-selector-migration-package.diff`：9339 bytes；SHA256 `96b3bcf4e8bd615880de7d7a9d1e8c0d3ed0e6c56c832ed858c41f65e3c46f78`；80 splitlines。
- package 包含完整 before/after 上下文，仅一个 Java 测试文件。以下源码行号均为 package 中的 after 文件行号。
- 未另读 changed source、未运行 git、测试、PG 或 replay。所有 shell 明确使用唯一 workdir `G:/207/项目/算法重组工具/代码/agent-platform`。
- 采用 code-review-expert 的规格/正确性/安全边界/质量检查及 verification-before-completion 的证据先行原则；任务明确禁止重跑测试，因此独立验证归档证据，并明确区分既有执行与 reviewer 本轮执行。
- 唯一额外生产接缝读取：`admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java:49` 至文件末尾 64，仅一次窄读。具名目的为确认公开 `requirePurpose` 的 snapshot / STATUS / EVENTS 双向约束与 G 私有 action 的拒绝路径；没有扩大生产评审或重新读取其他生产实现。

## Spec review — Spec compliant

1. **修改范围符合。** package 的唯一增删位于 `admin-common/src/test/java/cn/agentplatform/common/authority/RunObservationSelectorsTest.java:11` 至 30，即首个既有方法体内。第 10 行原方法声明保持，第二方法第 32 行 `canonicalQueryBindsVerifiedSubjectPathAndProjection`、第三方法第 42 行 `invalidInputIs400AndPurposeIsBidirectional` 的名称、顺序及完整方法均是未修改上下文；imports、常量、类定义、方法外文本也没有增删。

   `scope.json` 另记录 before 5714B/SHA `f5224ed46f0673cecc30f511c8f67e112f4eb8323ea2ec819c40c092aa9d8d78`、after 6812B/SHA `2559408706e8c2a94add22b0dc03335659e701f10848983ffc28ccbc37c4289e`，以及方法体外 prefix 347B/SHA `9e1b6f34ac15061e41405c71d10535cc2695e7c38f592a320dbc3d4f54115c46`、suffix 3604B/SHA `18a69ad119942dacdc8576552585519b96b1f9727d2e8c32dc0d578a1a774811` 均相同。评审独立确认 package 的修改位置及全部保留上下文；这些物理原字节身份由 root 的有限审计核对，本人未重复读取 changed source 或重哈整棵 source。

2. **两种公开用途都有真实正例。** 第 12–17 行分别使用现有 `STATUS_ACTION` 与 `EVENTS_ACTION`，按 action 选择真实 `status` / `events` selector，并直接调用公开 `requirePurpose`。每次调用均为 engine target、gateway caller、delegated mode、PORTAL source、PORTAL origin，使用合法 run ID 和 snapshot digest。任何异常都会让 JUnit 失败，无吞异常、stub、VA 或 Retained 替代。EVENTS 已从原错误拒绝表迁入正例。

3. **错误维度逐项保持并补全。** 第 18–26 行在两种 action 下分别执行负例：错误 resource kind（app-version、metadata）、不合法大写 digest、非法 run ID、app/gateway target、app/engine caller、DELEGATED/INTEGRATION source、workload mode、INTEGRATION origin，以及旧 Q action、执行 POST、`run.advance` 携带 snapshot。每次仅替换关注维度，其余使用有效正例字段；`assertThatThrownBy(...).isInstanceOf(PlatformException.class)` 实际包围公开 purpose 调用。不是以断言数量代替覆盖判断。

4. **G 私有用途未被公开入口接纳。** 第 28–30 行对固定字面值 `gateway.run.events.observe`，分别构造 gateway 与 engine target，携真实 events snapshot，通过公开 `requirePurpose` 断言拒绝。production 接缝第 52–56 行表明：snapshot 触发专属分支，既非 STATUS 又非 EVENTS 的 action 必须拒绝。engine target 的 G 负例尤其能排除“只是 target 错误才失败”的伪覆盖；gateway target 同时保护真实 G 目标的公开入口边界。

5. **历史 RED 和本轮 GREEN 独立保留。** 原 87 项归档仍为 86 passed / 1 failure / 0 errors / 0 skipped / exit 1；本轮新增的是一次完整三方法执行的 3 passed / 0 failures / 0 errors / 0 skipped / exit 0。没有把两个阶段合写成“87 全绿”。

6. **测试能力描述准确。** 第 11 行注释明确本方法只覆盖严格 snapshot grammar；在线 freshness 在其他位置检查。第 28 行解释 G private purpose 边界。原方法名虽然保留 Fresh 字样，但保持原名是明确要求，新增注释和报告没有将其宣传成 PG 新鲜度、真实 HTTP 或 SSE 验收。

## 独立执行证据核验

实际完整读取新 `command.json`、`raw.log`、归档 txt，并解析实际 XML 的 suite / testcase / failure / error / skipped 节点；同时读取 source epoch 的 schema、603 项总数和目标测试条目。有限重哈下列五个直接结论输入，均与封存身份一致：

| 证据 | bytes | SHA256 |
| --- | ---: | --- |
| 新 command.json | 540 | `14b45a4ae0bbc544bb9c102d3513276726f52158a58f55558e25f695c5366e24` |
| 新 raw.log | 4010 | `4d229637cde78649e1b5fd2986b784f12e1496413d91d880573d512dba36e3ef` |
| 新 source-epoch.json | 115043 | `ab0310201aff0a59e87f73b0692bf6e89b050591d7f33c2b4f099da3a97283c4` |
| 新 selector XML | 33791 | `f42f59366d25e16f797f30ef50fa9822bf34164230a489e010c11cc8018bfa09` |
| 原 D selector RED XML | 34507 | `28e0ee409db0612423b62c8916232b8745d335beb3df1fee899e4b09c56f36f2` |

前四项位于 `.local/iam-o2b-d-selector-migration/iam-stage-o2b-d-selector-migration-final/`，XML 位于其 `reports/TEST-cn.agentplatform.common.authority.RunObservationSelectorsTest.xml`。原 RED XML 位于 `.local/iam-o2b-d-common/iam-stage-o2b-d-final/reports/`。

新 command 的实际 argv 为：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2b-d-selector-migration-final admin-common RunObservationSelectorsTest
```

command 记录正确项目 cwd、exit 0、无超时、stderr 为空。raw 的 actual Running 只有 `cn.agentplatform.common.authority.RunObservationSelectorsTest`，汇总 3/0F/0E/0S、BUILD SUCCESS；main compile up-to-date，testCompile 实际编译 31 个源文件并重建 fixture JAR。编译输入数量没有被用作测试数量。

XML 恰有三条实际 testcase，均属于该类，且没有 failure/error/skipped 子节点：`canonicalQueryBindsVerifiedSubjectPathAndProjection`、`statusPurposeIsBidirectionalAndOnlyAcceptsFreshPortalSnapshot`、`invalidInputIs400AndPurposeIsBidirectional`。XML 执行顺序不等于源码声明顺序；声明顺序由 package 核对。txt 同为 3/0/0/0。

epoch 是 `file/bytes/sha256` 数组，603 项；目标 test 为 6812B/SHA `2559408706e8c2a94add22b0dc03335659e701f10848983ffc28ccbc37c4289e`，与 scope 一致；整个 epoch 的实测 SHA 与 handoff sourceAfter 相同。journal 记录 pre-run `06:02:55.438Z`，command-ended `06:03:02.337Z`、exit 0，archived `06:03:02.675Z`；与 raw 结束时间 `2026-10-21T14:03:02+08:00` 一致。给定封存记录中仅一个本阶段执行结束记录，没有重跑 RED 的记录。不能以作者 verification.json 的 PASS 替代上述独立核验。

原 RED XML 的失败方法确为首方法，异常为 `Expecting code to raise a throwable`，定位 `RunObservationSelectorsTest.java:15`。另窄读原 D raw 的实际 Running/结果和原 command：18+28+22+2+3+14=87，selector 3 中 1F，汇总 87/1F/0E/0S，exit 1。该历史 RED 未由 reviewer 重跑。

## Quality review — Approved

**Strengths**

- 正例与负例共享同一合法调用骨架，STATUS/EVENTS 各自执行，降低只迁移 action 名称而遗漏一条用途的风险。
- G 固定私有 action 的 engine 目标负例，与正常 EVENTS engine 正例形成可区分对照，断言能够捕捉公开入口被意外放宽。
- 只调整获准的方法体，没有引入 helper、参数化框架或 production 改动；注释解释用途与验证能力边界。
- raw、command、XML testcase、txt、epoch、历史 RED 相互支持，测试方法名与原失败定位可追溯。

**Critical：0。Important：0。Minor：0。** 未发现需修复的规格或质量问题，因此没有缺少 file:line / 触发后果 / 最小修法的未展开 finding。

工具诊断独立判断：raw 保留 `GatewayRunAuthorizationTest.java` 的 unchecked 编译提示；归档 344B dumpstream 内容为 Surefire Manifest-JAR 绝对 classpath 和 `'other' has different root` 提示。实际 surefire 已运行完整三个 testcase，exit 0，故这两项不降低本轮 selector 迁移结论，也不宣称日志无警告。没有据此修改 POM、runner 或生产代码。评审首次按错误 local 路径尝试读取 handoff 时得到文件不存在，随后读取实际 evidence 下 handoff；这是 reviewer 路径纠正，没有证据丢失或作者产物缺陷。

## ⚠ 范围外及冻结交接

- 不验证 PG 新鲜度、真实 HTTP/SSE、E/F、外部 issuer 生产能力，也不扩大到 D 全量生产实现。
- source603 全文件物理身份、target2118/overlay203、compiler lists 原字节、own-inventory、private Git/replay identity、原 D/C 全部历史证据及八索引一致性由 root 的有限审计负责。本评审只使用 scope/handoff/journal 作为范围与交接上下文，不把作者布尔声明当成已独立完成这些物理审计。
- 不重跑 C29/24、D87、全后端或私有 replay；未读取相邻工程；没有重哈全 source/target/own，未运行 git。
- reviewer 唯一写入文件为本报告。报告完成后冻结，不修改作者 source、target、local、证据、helper、report 或索引。

最终规格结论 **Spec compliant**；质量结论 **Approved**；**C0 / I0 / M0**。
