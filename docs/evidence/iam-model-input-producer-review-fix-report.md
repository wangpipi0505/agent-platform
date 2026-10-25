# Initial model-input producer — I1/I2/I3 修补报告

状态：DONE / frozen pending independent review。只完成冻结 brief 指定的三个 Important 修补；不签收公开模型链或全平台。

## 身份、范围与冻结

唯一工作目录 `G:/207/项目/算法重组工具/代码/agent-platform`。先完整核对冻结候选的 652 份源，逐文件 bytes/SHA 全部相等，再建立本修补 before。before manifest：125471B / `747463804aeadbbad190cf37a8186dacb4f3f23f05ad4c20dfe0d8cb6d0c24a4`。

最终源仍 652 文件，只有 brief 允许的 Store、Materials、PGTest、Fixture 四份既有文件修改；648 份未改，无新增/删除源路径。公共 Values/ContextAssembler/authority、Manifest、controller、V218/其他 SQL、catalog、POM、HTTP 测试、设计/契约和保护 8 执行文档全部未改。最后两份经本地 Python 编辑的 Java 文件还原为原有 LF，避免无关整文件行尾 diff。

- 最终完整 source manifest：`.local/iam-model-input-producer-review-fix/final/source.json`，125472B / `0e51caf00543e924c52a40b506b3d1509c24b338db9bb02ba7d9d04413c8f992`；全文 blobs 在同级 `source/`，这是全文源包。
- 实际 before652→after652 **完整 native diff**：`.local/iam-model-input-producer-review-fix/final/source-652-to-652.patch`，21561B / `6011f4f2bc2c1cc096a2cce1c3f89225e9aa66a362132dd6f3f29b6598a742cb`。它是 diff，不是全文源包。
- `final/native.json` 保存全部原生 Git 命令/exit；自有 scratch 使用 index baseline，`git diff --binary`、`apply --check`、`apply` 全成功，apply 后 652 文件逐 bytes/SHA 相等。未创建 commit。
- `final/task-delta.json`、`source-eof.json`、`protected-eight.json` 分别绑定四文件变化、全部源 EOF 和保护 8 的冻结 AFTER 身份。

## I1：写者绑定最新成功检查

`ModelInputMaterialStore.localBinding` 在既有 run→authority 行锁内验证持久化 `last_decision_id == retained.current.decisionId`，并核同一锁定 Binding 的 `check_version`。此检查沿用既有 MaterialScopeStore writer 不变量；覆盖 payload 前、重新加锁验证、写入及 replay。原 strict、blocked/retry/expiry、父 closure、precommit 和 rollback-only 保持；PG 事务内没有 IAM 网络调用。GET 的可重复授权用途与独立 operation/grant 语义未收窄。

`latestSuccessfulDecisionRejectsOlderWriterBeforePayload` 使用真实有效 P1、真实较新 P2 current/CAS，然后确定顺序调用 P1。RED 是旧实现没有抛错；GREEN 证明 P1 在受保护 source SQL 之前拒绝，request/model-input scope 均 0。P2 正向写入，scope 的 sourceDecisionId 对应 P2，`initial/new-policy/parent/config` 标签均保留。新增标签由原签名 fixture 决策路径产生，dataLabels 仍是义务；未制造受信 public VerifiedAuthority。既有同 proof 并发幂等用例单独保持绿色。

## I2：放大分配前的本地预算

在调用未改动的 ContextAssembler 前增加有限 prompt preflight。按原 Values 的整值返回、原匹配顺序和全量 String.replace 语义模拟；替换之前逐段计数 JSON 转义后的 UTF-8，跨段处理 supplementary Unicode。超过 2 MiB prompt JSON 预算即拒绝，尚未分配巨大替换结果。随后仍保留完整 canonical request 2 MiB、每 source 1 MiB/总 3 MiB 与 depth 32 限制。

该预算针对每一阶段的序列化 prompt，**不是 Java heap 总量/单个 char[] 物理字节保证**；可以存在多个有界副本，也不提供任意吞吐/CPU 容量证明。没有修改通用 resolver，也没有关闭模板或降低正常 source 上限。

`oversizedExpansionRejectsBeforeLargeAllocationWithMeasuredControl` 用两份均小于 1 MiB 的真实 PG source：128 个引用 × 65,536 个 ASCII 字符，假想展开 8,388,608 字节。主 JVM 没有故意分配 GiB。HotSpot ThreadMXBean 的实际线程分配计数带独立 12 MiB 正控，并先用正常 source 预热；事务先取得真实 source，再度量组装，失败断言两张业务表无 model-input 事实。

- 旧实现行为 RED：虽然最终 403，实际分配 **42,014,464B**，超过测试 8,388,608B 分配观察阈值。
- 中间 GREEN：11,632B；最终同源 GREEN：**11,144B**，正控 **12,583,136B**，正常 PlatformException 拒绝，无 OOM/崩溃。
- 该计数仅证明本机 JDK21/HotSpot 的该实际线程路径，不冒充所有输入/JVM 的总分配上限。

`templateSemanticsAndNearLimitUnicodeRemainSupported` 从实际 PG request 比较重复/缺失/非字符串/嵌套和级联引用，另持久化包含中文、emoji、引号、换行及反斜杠的合法 request；canonical UTF-8 在 2,000,000～2,097,152B。两项用例在旧实现已有正向对照、修补后与最终套件均绿色。

## I3：参数值类型/结构

按现有映射在 request 成事实之前校验：temperature/topP/frequencyPenalty/presencePenalty 必须 number；topK/seed 必须 integral；maxOutputTokens 复用既有正整数/int 上限；stop 仅 string 或 string[]；未知键继续拒绝。不新增 provider 数值区间、不改 sampling/output 的既有覆盖顺序。

`malformedParameterShapesNeverConsumeImmutableSlot` 的行为 RED 是 temperature 字符串被受理。GREEN 逐项拒绝十种错误形状（含 topP/stop object、混合 stop list、非整数、字符串数值、null），每次都查 request 与 model-input scope 为 0。`validTypedParametersSurvivePersistedCanonicalRequest` 从 PG 比较所有合法字段、stop 字符串及列表、同义 sampling/output、完整 canonical digest；maxOutputTokens 保持现有 output/top-level 优先映射，未新增参数优先级。已有 unsupported-config/model-binding 拒绝均在最终套件通过。

## 实际命令、epoch 与验证

每条 shell/子进程明确 cwd 为唯一项目目录；JDK `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`，Maven 原 POM、离线 `.tools/m2`。实际每阶段 args/exit、652 source epoch、javac inputFiles/createdFiles、resources、fixture JAR、Running 与 XML 在 `docs/evidence/iam-model-input-producer-review-fix/<phase>/`，索引为 `final/test-epochs.json`。

| Epoch | 实际结果 | 证明范围 |
|---|---|---|
| i1-red | compile exit1，0 Running | 测试误用 decisionId 字段；不是行为 RED |
| i1-red-behavior | 1 test / 1 failure | 冻结旧 Store 接受陈旧 proof |
| i1-green | 2/2，exit0 | I1 + 同 proof 并发正向 |
| i2-red | helper error 后 Maven exit0、0 Running | 不计 GREEN/RED；原 helper 保留 |
| i2-red-behavior | 2 tests / 1 failure | 分配观察失败，普通/近限正向通过 |
| i2-green | 2/2，exit0 | 预分配拒绝与正常语义 |
| i3-red | 2 tests / 1 failure | 错误参数受理，合法参数正向通过 |
| i3-green | 2/2，exit0 | 类型校验与持久化合法值 |
| **final-green** | **32/32，exit0；PG21 + HTTP11；0 failure/error/skipped** | **与 final/source.json 完全相同的最终 652 字节** |
| package-build | exit0、skipTests | 独立同源自有副本 build-only |

最终命令：`mvn.cmd -o -B -ntp -pl engine-runtime -am test -Dmaven.repo.local=.tools/m2 -Dtest=ModelInputMaterialPostgresTest,ModelInputMaterialHttpTest -Dsurefire.failIfNoSpecifiedTests=false`，另有实际 command.json 中明确记录的 evidence/registry/TLS/Unix tmp 参数。最终有限 canonical target 集合 **558** 项，按实际 compiler/resource/Running 清单保存；不扫描全历史 target/node_modules。全部旧 92 回归/backend/web/browser 均未复跑。

HTTP 测试文件字节不变：真实 canonical catalog/控制器/正常 CA/SAN TLS、签名委托读取、重复 GET、错误签名/用途/selector/parent、authority503、legacy bean/default IAM guard 等在最终同源套件通过。测试 transport 的 redirect/hostname/trust 只证明此 fixture，未来 model client 未实现。最终 endpoint 正控 3，component model/tokenizer/provider canary 0；SecretVault 文件读取正控 2、组件路径 0。文件 JFR 的 0 不独自证明所有 SecretVault 方法未调用；结合未新增 egress 调用链审查，不扩大该证明。

独立 build-only 路径 `.local/iam-model-input-producer-review-fix/package-work/` 复制 652 同源文件；未重试原 target 的已知 Boot repackage rename 失败，也未覆盖旧 historical.original 或终止主服务。`package-build` 实际保存 **562** 有限产物；`final/package-inspection.json` 对两个 JAR 的 **247** 个实际 class/resource entry 逐字节核对，没有 sibling business JAR 或 fixture-tests JAR 混入运行依赖。

所有原失败、源 epoch 和原 helper 均保留。`final/retained-failures.json` 说明 i1 编译笔误与 i2 JS 模板插值错误；初次 i2 helper stderr 只存在当时工具输出，独立文件保存了观察到的精确错误摘要，不冒充原始 stderr 捕获。无业务重跑来粉饰这些失败。compiler deprecated/unchecked 与 Flyway setup 提示分类保留在 `final/warnings.json`。

## 资源与限制

只创建 registry 逐名登记、absence-preflight 后的 **33** 个 `iam_model_input_fix_<32hex>` 新工程 PG25432/agent_v12 数据库。旧 77 库及其他历史 fixture 库从未连接/重置；管理连接只用于新工程系统 catalog/CREATE DATABASE。fixture 的唯一变化为新前缀与创建前显式 registry 记录。33 库只读审计（含实际 migration history）保存在 `database-audit.json`；32 库 V218，一库保留原 preflight V217，均留存给复核。

`runtime-exit.json` 逐名查 registry 的数据库会话为 **0**，观察的 **20** 个自有进程已退出，记录的 **68** 个自有监听端口均不再监听。HTTP redirect 的两个短时测试端口原测试未写端口号，其 finally stop 与整个所属 JVM 退出成立；不把 68 称作所有瞬时端口的完整抓包。所有 Maven/collector/package/audit 命令均已 wait/return，收集器无后台工作。主服务/Redis/MinIO/主端口未操作。

公开 CHAT/modelRefs/LLM/AGENT/driver/default IAM gates 保持关闭；无 model consumer、first-send proof、measure/tokenizer/provider/SecretVault 接线。真实 PG fixture 位于公开受理门禁之下。request canonical digest 不是 provider wire digest；跨 JVM 旧测试仍只复算 request/manifest/wire hash，没有执行跨 JVM public scope require。common 的 Set 规范化无需修补。PG 与 IAM/时间提交的原非原子边界和 immutable DB trigger 信任限制保持，不签默认完整 launcher、真实 IAM/K8s 或容量。

## 完整归还

control/inventory 在本报告与所有实际证据完成后最后封装。状态仅 DONE_FROZEN_PENDING_REVIEW。**ALL 源码、canonical/owned target、33 个 DB、HTTP、helper、owned 工作副本、evidence、report 与 runtime 的写权和执行/运行权全部归还 root；无后台工作，不再继续修改。**
