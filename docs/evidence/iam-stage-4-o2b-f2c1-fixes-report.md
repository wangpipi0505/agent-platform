# O2b F2c1 I1/M1 修复报告 — DONE_WITH_CONCERNS

2026-10-21；唯一工作根 `G:/207/项目/算法重组工具/代码/agent-platform`。本轮只修既有 C1 review 的 I1，并在同 Verifier 内处理 M1。原候选仍由 root 独立审计与原 reviewer 复审后决定是否限定 Accepted；本报告不签收完整 IAM 或公开 EVENTS。

只改以下两路径，无新增源码，627→627；其余625条名字与字节逐项相同。实际 main/test compiler 输入各21，不把它写成只编译两文件。

- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsMaterialVerifier.java`
- `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsMaterialAuthorityTest.java`

未写原 C1/B2 snapshots/report/handoff/inventory/review、Http、VerifierTest、固定 fixture、common/TLS/F1/engine/pom/main/controller/assembly/frontend、设计/四契约/八索引。原 C1 source627/target logical2264及9460 owned 的 root 六 phase PASS 按冻结输入继承，未重跑原19项、原审计、PG/provider/旧矩阵。无 commit/deploy/主服务重启，无相邻旧工程访问。

正式本轮 brief/inputs、原 C1 正式 brief、三份冻结完整要求、Accepted API supplement、两份 root 测试澄清、原报告及 fresh review 已读。使用 receiving-code-review/TDD/verification-before-completion/ponytail 与 SDD implementer 规则；sourceBaselineDirectory 的原目录 proof 继承，独立 before 副本与真实 Git blobs 在本轮核对。

## I1 与 M1 的具体修复

原 `writePayload` 的短窗口锁内只有 `activeWriters++`，同一80MiB token 可对应多个同时存活的排序/精确数值转换临时图。本轮在同一锁内、进入任何 `write(generator, payload)` 前检查 `activeWriters!=0`，以 `EVENT_MATERIAL_DENIED / window writer active` 原子拒绝并发与重入。没有逐 writer 排队/加费、generic semaphore、线程亲和或新增公开接口。

准入拒绝发生在 try/finally 之前，不递减首 writer 的计数，不退费；只有成功准入者进入原 finally，恰一次减计数并 notifyAll。`live()`/closing 检查和原等待退出路径保留。锁在 generator 回调前释放，close 的 wait 释放窗口 monitor；首 writer 真正在 writeString 内工作时，consumer 返回、Session close/interrupt 仍须等实际遍历退出，才清 window/verifier 与上下文并释放80MiB。没有改原 t0、Session/G/receipt/P3/current/guard 语义。

源注释明确单份 F canonical 费用只覆盖一个重遍历，含 generator 重入。成本仍是既有80MiB工程收费模型，不增加预算；该模型不等于全 JVM/TLS/native/GC/RSS 硬界。

M1 仅展开14组 SCHEMA 的字段/类型对及 factory 每一项配置。`format-only.json` 记录前后去空白的完整序列逐字符相同及其 SHA256，字段/次序/数值、Unicode surrogate、recycler/canonicalize/intern、duplicate/source-location 与所有 parser constraints 均保持原 token。未拆类、未新增框架。人读差异见 `source-review.diff`，实际补丁见 `source.binary.patch`。

测试文件除两新真实 window 用例及其 helper/main dispatch 外，将 `runs()` 从旧 C1 peers 根改为 `.local/iam-o2b-f2c1-fixes/peers`。root 已明确批准该证据隔离；因此包括本轮调用的既有 retention 方法也只写本轮 peers。未改真实 receiver/issuer/child receipt fixture 链、classpath asset 或旧测试的业务断言。

## 实际 RED 与最终受影响验证

先保持 Verifier 旧生产字节不变，仅加测试并将 peer 输出隔离。`red-single-writer` 实际2项/2 failure/0 error：重入在真实 generator.writeString 回调处明确 Expected PlatformException but nothing was thrown。并发首轮的第二 writer 断言被 Future→IOException→既有 Http unavailable 转换遮蔽，因此保留原 raw，不将那段不可见原因冒充精确缺陷证据。

仅改善并发新测试的 ExecutionException 原因传播后，旧生产码再次单跑并发项 `red-concurrent-observable`：1项/1 failure/0 error，明确 `second writer entered traversal; generatorCalls=1`，cause 是 Expected PlatformException but nothing was thrown。不是编译、selector、环境或早层错误；没有重跑任何成功阶段。

并发用例通过真实 LoopbackAuthority 原 Q→同 Session G/child→TLS 两连接→公开 receiver→retained initial→真实 E receipt→C1 window。首 writer 在另一线程真实 writeString 内 barrier 阻塞；第二 writer 使用另一受控线程，在2秒 future 上界内必须拒绝，其第一个 writeStartObject 计数必须0，不能排队等首 writer。仅两条同时 writer。首 writer 仍未 done、Owner=1与 heldBytes 不变；release 后 first future 实际退出。随后实际 generator IOException 测恰一次清准入，再顺序写成功。

重入用例在外层实际 writeString 内同步调用同一 window.writePayload，内层立即拒绝且 generatorCalls=0，heldBytes 不变，外层完整退出，再次顺序写成功。内层 generator 没有递归 callback，不存在无界 fanout。不是手造计数器、伪 window 或私有反射；沿用此前获准仅观察真实同 G 的原样 delegate Proxy，不更改授权结果。

最终在修复后新627 epoch **只运行一次**以下受影响矩阵，使用逗号分列 class#method selector，无加号：

```text
python -B .tools/iam-o2b-f2c1-fixes-run.py final-candidate "PortalEventsMaterialAuthorityTest#sameWindowConcurrentWriterIsRejected,PortalEventsMaterialAuthorityTest#sameWindowReentrantWriterIsRejected,PortalEventsMaterialAuthorityTest#twoActualBorrowedWritesKeepFeesUntilWorkersExit,PortalEventsMaterialVerifierTest"
```

wrapper 实际命令为 Java21/offline Maven reactor `-pl platform-gateway -am test -Dmaven.repo.local=.tools/m2 -Dtest=<above selectors> -Dsurefire.failIfNoSpecifiedTests=false`；完整 args/cwd/PID 在 stage command.json，raw/Running/XML/source epoch/inputFiles.lst/createdFiles.lst 均已缓存。

结果 Authority3 + 原未改 Verifier5 = **8/8**，failures/errors/skipped均0。Authority 9.453s，Verifier0.295s；BUILD SUCCESS。最终 actual compiler 为 gateway main21/test21；common 为 up-to-date，未伪称重编 common。旧19属于原 candidate epoch，本轮8属于新 epoch，不能合称同场27。

| phase | Maven exit | tests/failures/errors/skipped |
|---|---:|---|
| final-candidate | 0 | 3/0/0/0; 5/0/0/0 |
| red-concurrent-observable | 1 | 1/1/0/0 |
| red-single-writer | 1 | 2/2/0/0 |

本轮共6个 child JVM，前三个为保留的失败阶段 child，最终3个均exit0；每个均记录(run,PID)、actual waitFor、collector join和peer worker join。不是 cancel 即视为退出。`peer-exits.json`/`stage-catalog.json`/`final-observations.json` 给完整映射。最终两个新用例分别是单 Owner heldBytes=88342871，拒绝 generator 调用数均0；retention 的双 Owner 实际 heldBytes=176685742，第三拒绝、close后仍持费、release与实际worker退出后归零，和既有语义一致。final 用例共4个真实两TLS连接 Session；这不是4个额外JUnit测试。

## 新 epoch 的机械证据

source-after `123392B / 6b4a6488efa08b27266ccceff39fd3e79b62b137a3edcc6dfd3b071abd83f68c`；本轮最终 live完整627与 final stage source epoch、source-after副本、primary/replay after完整名字/字节一致。每stage只复制两条写权源码，其余625 manifest行继承原冻结清单；source-name-closure.json 另核实际源码集合没有新增/遗漏。没有重新提取或重hash旧 E binary vector。

主 private Git 先放完整 before627实际名字/字节，在首次add前设置 info attributes `* -text -diff`、禁 autocrlf、隔离system/global配置、空template。before tree `466e6134589f34ed57d4ffdc7edce0bba484b66f`；真实 before627 blobs 的长度/SHA逐项核对，after未改625 blob继承原实核身份、两条修改 blob另实际cat-file核对。after tree `43683b7eb956666c89b2cd5337c4e0c9ce992b33`。没有 commit、remote或辅助index。

独立 replay 在预存 before627 names/bytes 后，首次 plain `git apply --check` 与首次 plain `git apply` 各一次，均exit0；完整after627相等，未建 replay index，apply后未copy补源。patch为两既有文件的实际 binary patch：5107B / `287242afc25fe53e8d4065cdc9cb31b2963ce3bfbe9ec2fd4161c405d44957f2`。check/apply每个成功退出先cache/journal再后继assert，metadata工作没有重跑成功测试/编译/replay。

finite target **378** 条只来自最终实际 compiler input/created lists、对应输出、实际Maven resource copy、common fixture JAR及精确 Running 报告；stage有限target375，后续追加3个具名实际source resource copy。`finite-target-basis.json` 逐条记录来源。由原C1历史logical2264继承overlay→**logical2269**，新增5个实际匿名测试class；不扫描whole-current-target或whole-classpath，不把历史logical宣称全部由本次编译生成。target-after `486941B / 1998bf8be7352c46b2ca2698ca16e90cb092b5a09eb5817401b8bca441caa378`。旧target和全部原C1 proof 保持冻结。

M2 保留：原始 javac raw 的 IntegrationPolicy 与 GatewayPublishedChainPostgresTest 仍有中文编码乱码/deprecation提示。继承的 diagnostics 提取器只匹配 deprecated 导致该摘要为[]；本轮另存 diagnostics-retained.json 的准确4个具名raw行号与原始摘要，未改raw/runner/POM，未关闭检查，也未为消噪重新编译。输出不能称pristine。

## 交还与保留顾虑

自有 report/handoff/freeze/inventory覆盖两 ownedRoot、具名 `.tools/iam-o2b-f2c1-fixes-*` helper与独立报告；inventory仅自排自身，最终写入后本实现者不再改source/target/owned。实际控制三身份由 freeze helper 最后输出，handoff链接所有新epoch proof；原C1 originals保持immutable。source/target/owned写权冻结交回root。

DONE_WITH_CONCERNS 的保留项是已有明确边界：80MiB只是固定类型/输入/次数的工程收费模型，不覆盖全JVM/TLS/native/GC；writePayload仍为数值归整后的重序列化JSON；原F1 ignored.local whole-branch可移植性和M2编译诊断仍待对应阶段处理。原材料边界、真实enginePG latest/membership/root payload与生产issuer验收不在本修复内。公开EVENTS/Servlet/SSE/浏览器字节/每write-flush guard及C2仍未签收。

本轮必要实现及验证已完成；I1/M1是否闭合仍由root独立delta gate与fresh reviewer裁定，之后才允许限定C1 cert/八索引更新及C2。
