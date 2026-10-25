# Task3 独立审查 I1 / I2 / I3 修复

最终 common **123 tests / 0 failures / 0 errors / 0 skipped**，原 Task3 113 项保留、新增 10 项。修复 I1 单次响应能力复领、I2 非 LIFO 上下文复活，以及 root 新授权且由具名 addendum 确认的 I3 批准者固定角色门槛。独立复审尚待 reviewer；IAM 启动闭锁继续保持，不签收 Task4/5 或生产 IAM。

**最终交付证据位于 `docs/evidence/iam-stage-3-review-fixes/i3/`**：`full.patch` 是从原 Task3 63 文件基线生成的完整合并修复；`frozen/`、`applied/`、`source-after.json`、`frozen-target/`、最终日志/XML均对应 123 轮。上一级目录的 121 轮与 patch `0360cba9...` 是已冻结的 I1/I2 候选历史，完整保留，不是当前最终结论。其旧报告原字节保存在 `i3/i1-i2-candidate-report.md`。

## 授权和最小改动

root 明确批准 ResponseLease 从 record 改为最窄 final class，保留七参 public 构造、七同名 accessor、值 equals/hashCode 和脱敏 toString；允许内部 opened/renewed 和每次 open 的 Lifecycle。公开构造只产生数据壳，无已验领取能力，值相等不能证明同能力。root 同时批准 AuthorityContext 内部 frame 栈。I3 后续授权仅移除 common 固定 APPROVER 门槛并做最小 fixture/回归；依据 `review-iam-stage-3-approver-addendum.md`、原计划 §2.3/Task5 和 root-decisions 第5条。原 Task3 brief/root-handoffs、四契约均不变。

合并修改 **8 文件，0 新增/删除源文件**（完整路径见 `i3/changed-files.json`）：

- `ResponseLease.java`：一次真实已验 open 分配本地一次性生命周期，状态仅 0 未领取→1 已领取→2 永久终止，无全局表或当前许可缓存。public 数据壳不能领取/check；带同 permit 的复制数据壳共享其生命周期，但不能据此获得已验能力。renewed 只更新已验 checkedUntil，保留原绑定和同生命周期。equals/hashCode 仅比较原七字段，不能作授权证明。
- `ResponseMaterialPermit.java`：保留原签名、用途、固定公钥、期限与 selector 规则，逐次验签前同时拒绝已终止生命周期。
- `HttpCurrentAuthorityClient.java`：check 在网络前要求已验且未终止；签名与原绑定通过后，open 创建能力，check 沿用能力。I3 仅删除 approver 的一行固定 APPROVER 检查；仍返回绑定真实 approvedBy、grant 和批准资源/摘要的在线当前证明，原签名/nonce/current/版本/期限检查保留。没有 ADMIN/EDITOR 豁免或其他角色硬编码。
- `StreamAuthorityGuard.java`：构造先单次 CAS 领取再启动 watchdog；重复领取只回收新 scheduler，不影响合法 owner。首次领取后构造失败永久终止能力并回收 scheduler。close 在回调前终止共享能力且幂等；renew 除原字段外还要求同 lifecycle。cursor/accepted/final 状态留在唯一 holder，不会重置。正常单 holder 显式跨线程和原 accepted integration drain 保留。
- `AuthorityContext.java`：普通 ThreadLocal 保存 scope frame；非 LIFO close 只关闭本 frame，内层继续有效；展开跳过所有 closed 祖先。owner-thread 校验、不继承、幂等、异常清理和缺失默认拒绝保留。同一个 VerifiedAuthority 绑定两层仍是独立 frame。
- `CurrentAuthorityClientTest.java`、`ResponseAuthorityTest.java`：新增有限回归。原同 lease 两 guard 测试改用两个独立合法 open/binding；保留错 access/owner/payload/version 覆盖。
- `LoopbackAuthority.java`：I3 新增仅测试的 approverRoles，默认仍 APPROVER；可真实签发 EDITOR 当前证明。不改变生产角色语义或引入假 PG。

核对当前工程 ResponseLease 调用点及 `Json.java`：生产使用只在 common open/check/guard，HTTP 显式传 ResponseBinding 和 continuation_ref，没有序列化整个 ResponseLease 的实际调用点。未另建序列化框架；public 数据壳不承诺通过反序列化获得能力。record 反射类型形态确已改变，后续调用点应使用真实 open/check 产物。

## 行为红绿与实际命令

工作目录固定为 `G:/207/项目/算法重组工具/代码/agent-platform`，所有轮次 Java21、离线本地依赖，命令前置相同：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
```

### I1/I2 先红后绿（上一级目录，历史候选）

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" "-Dtest=ResponseAuthorityTest#reviewI1*,CurrentAuthorityClientTest#reviewI2*" "-Dsurefire.failIfNoSpecifiedTests=false" *> docs/evidence/iam-stage-3-review-fixes/red.log
```

生产代码未修改时，**7 tests / 6 assertion failures / 0 errors / 0 skipped**，exit 1。`red-selected-xml/` 为当轮两类 XML，`red-source/` 保存回归源，`baseline/` 保存当时全部生产原字节。

| 方法 | 实际原行为红 | 修复后覆盖 |
| --- | --- | --- |
| reviewI1ActiveRenewedAndClosedLeasesCannotClaimAnotherHolder | 活跃 lease 可构造另一个 holder | PORTAL/INTEGRATION 活跃、renewed、close 后拒绝；原 owner 跨线程正常、取消一次 |
| reviewI1ClosedSseLeaseCannotReplayConsumedSequence | 原 holder close 后重建，Clock +21、撤权后同 seq1 再次通过 beforeMaterialWrite | 构造即拒绝 |
| reviewI1ClosedModelLeaseCannotReplayFinal | close 后重建，过期且 authority 不可用后同 final 再次通过 | 构造即拒绝 |
| reviewI1PublicCopiesCannotClaimOrLaunderSamePermit | 同 permit 等值 public 副本可创建 holder | 等值/改 binding、source、continuation、checkedUntil 副本不能领取/check；原能力正常 |
| reviewI1FailedConstructorTerminatesClaimAndShutsScheduler | scheduler 拒绝首次构造后仍能复领 | 失败终止；重复/空参数构造失败 scheduler 均回收 |
| reviewI2NonLifoSameAuthorityNeverRestoresClosedFrame | 同一真实授权对象两层非 LIFO 得 [false,false]：内层被清除、全部关闭后仍有授权 | [true,true]：内层有效，最终无上下文 |

`reviewI2LifoExceptionOwnerAndChildBoundaries` 本来为绿，覆盖 LIFO、异常 finally、子线程不继承、异线程 close 拒绝且 owner 不受影响、最终 current 缺失。各方法首个红断言之后未执行的断言仅算新增绿色覆盖，不虚称独立红。

修复后三类命名命令：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" "-Dtest=ResponseAuthorityTest,CurrentAuthorityClientTest,ServiceTicketProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" *> docs/evidence/iam-stage-3-review-fixes/green-named.log
```

当轮 **38/0/0/0**（17+18+3），`green-named-selected-xml/`。随后仅新增 `ResponseAuthorityTest#reviewI1RenewalPreservesCursorAcceptanceAndFinalReservation` 定向运行 **1/0/0/0**，命令与上式相同但 Dtest 替换为该方法、输出 `green-renewal.log`；XML 在 `green-renewal-xml/`。它是新增绿覆盖：续核不重置已消费 SSE 序号/accepted，原 holder 可继续 seq2、过期后 drain seq3；model final 消费后 renewed 不能新领取或重复 final，close 后 check 拒绝。

I1/I2 候选完整 common 使用下文最终完整命令的同一参数，输出上一级 `final-common.log`，2026-10-20 13:59:51 得 **121/0/0/0**；10 XML 在上一级 `final-common-xml/`，候选原字节均保留。I3 引入新逻辑后才有下文新的最终完整轮次。

初始 `red-xml/`、`green-named-xml/` 通配符归档另含 surefire 继承的未运行 XML，保留原归档但不混算。上一级 `xml-audit.json` 按 selected 精确核对 7、38、1、121。首次编辑辅助脚本读取 AuthorityContext 因默认 GBK/UTF-8 不符停止，该文件尚未写入，随后显式 UTF-8 完成；不是测试红。

### I3 真正签名 EDITOR 证明先红后绿（i3 子目录）

先保存当前 121 候选 63 文件 `i3/baseline/`，仅新增 fixture 可配置角色和两项测试，生产固定门槛仍原样：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" "-Dtest=CurrentAuthorityClientTest#reviewI3*" "-Dsurefire.failIfNoSpecifiedTests=false" *> docs/evidence/iam-stage-3-review-fixes/i3/red.log
```

**2 tests / 1 assertion failure / 0 errors / 0 skipped**，exit 1。`reviewI3CurrentEditorApproverProofDoesNotImposeFrozenBusinessRoles` 的 assertThatCode 实际失败，堆栈精确落在 HttpCurrentAuthorityClient.approver:96 固定角色检查；并非缺类/编译错误。真实 HTTP authority 用临时 RSA 签回 EDITOR，主体仍是记录的 approver，资源与 grant 绑定正确。`i3/red-xml/` 和 `red-source/` 保留。

随后只删除该生产 if，执行三类命名覆盖：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" "-Dtest=CurrentAuthorityClientTest,ResponseAuthorityTest,ServiceTicketProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" *> docs/evidence/iam-stage-3-review-fixes/i3/green-named.log
```

**41/0/0/0**（19+19+3），exit 0；精确三份 XML 在 `i3/green-named-xml/`。EDITOR 正例通过，原默认 APPROVER 与当前撤权用例保留；新增 EDITOR 当前证明的 authority 撤权、错误主体、503、旧版本、current=false、错 nonce、错资源、错误签名、错 grant、过期十个有限负例仍拒绝。未把获取当前证明等同于业务批准。

### 最终同码完整 common

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" *> docs/evidence/iam-stage-3-review-fixes/i3/final-common.log
```

2026-10-20 **14:06:47** 完成，**123/0/0/0**，exit 0；`i3/final-common-xml/` 的 10 份 XML 同样合计 123，`i3/xml-audit.json` 精确列出 red 2、named 41、final 123 的方法/计数/SHA。本轮无新增 unchecked 提示。最终该轮之后未再改任何生产或测试源字节。

M1 旧配置负向测试仍有预期启动堆栈，由 root 跟踪，未改旧测试/隐藏日志。没有旧 reviewer probe、旧 schema/scope 探针、全七 reactor、主服务或真实 IAM 测试。

## 最终冻结、patch 和保护证据

- 原 Task3 基线为上一级 `baseline/`、`source-before.json`，完整 63 源码/POM；I3 前 121 候选基线另存 `i3/baseline/`、`i3/source-before.json`。最终仍 63 文件，合并变更 8，I3 单独变更 3。
- **最终完整合并 patch：`i3/full.patch`**，SHA256 **7c7e3c3f4e51e429c02e8b187ba32255ebb1a1058a8f568f5ba4de5d7e42f2c2**。
- I3-only patch：`i3/i3-only.patch`，SHA256 **19679d6fed23c22ea623bb3660bbf130e7ce67b94fb17e67e70f6cc76e52850e**。
- 最终源码清单 `i3/source-after.json` SHA256 **5193f6b8a1018a4e100dfdd74f527ba8ad4c3c299e88c363e0eb4cfc5aa42910**。
- 对原 Task3 baseline 的独立 `i3/applied/` 真实执行 `git -c core.autocrlf=false apply --check <i3/full.patch>`、`git -c core.autocrlf=false apply <i3/full.patch>`；对 I1/I2 baseline 的独立 `i3/i3-applied/` 同样真实应用 i3-only.patch。四命令均 exit 0、stdout/stderr 空。`i3/apply-proof.json` 保存实际 argv/cwd/输出；两个 applied、frozen、live 的 63 文件路径、SHA、实际 bytes 全部一致。
- 最终 target 245 文件与 `i3/frozen-target/` 逐字节相同；`i3/target-after.json` SHA256 **ae3487a0fc17da07ff94551148a336b0a1fcb56f166ac924e4473f30a22f1bcd**。没有 package；继承旧 jar 没有重建。
- 上一级候选目录 **881 文件** 的原字节由 `i3/candidate-protected.json` 验证保持；原候选报告另存 `i3/i1-i2-candidate-report.md`。本最终报告按 root 指示重写，旧候选 manifest 中报告项的字节现在可从该副本复核，不能把它误当当前报告 SHA。
- 原 Task3/原 review/probe **561 文件**，其他保护集合 **501 文件**，全部 SHA 保持。原 reviewer 全文前缀上一级 `review-original-prefix.md` 保留，未写 reviewer/addendum；后续其合法追加由 root 管理。
- I1/I2 候选测试后曾仅恢复 EOL：测试时源快照上一级 `frozen/`；其格式恢复后源上一级 `frozen-final/`；`line-ending-proof.json` 对七编辑文件核实 CRLF→LF 规范化 bytes 相等，其中六个仅 EOL 变化，ResponseLease 原 CRLF 未变。root 明确批准不因该格式恢复重复 suite。该历史的 pre-eol patch/证明也保留。**I3 的最终 123 轮使用当前原始字节，此轮后没有 EOL 或逻辑改动。**
- 无 HEAD/remote，使用完整 patch，不造提交。执行脚本、原日志/XML、清单和最终保护复核均在自己的修复目录。

## 实际开源对照和跨 Task 门禁

本轮实际读取本地 Eclipse Adoptium JDK 21.0.12.8 `lib/src.zip` 的 ThreadLocal.java（get/remove/getMap）、AtomicInteger.java（compareAndSet/set）、ScheduledThreadPoolExecutor.java（scheduleAtFixedRate/delayedExecute/shutdownNow）。来源/原文件 SHA 在上一级 `open-source-read.json`，读取摘录在 `*-source-excerpts.txt`。采用普通 ThreadLocal frame、CAS 单次领取和失败 scheduler 清理，无未读源码对照声明。I3 未引入新的框架机制。

原 review 跨 Task 边界及 I3 后续条件全部保持：

- **批准角色仍由 E 按持久冻结 confirmRoles 判断。** common 返回当前证明不等于批准通过。E 必须保留 confirmRoles=EDITOR 的合法正例，也必须拒绝 confirmRoles=APPROVER 但当前只有 EDITOR 的负例；ADMIN/EDITOR 无自动豁免。本轮未实现 E/RunAuthority/业务材料批准逻辑。
- 真实 IAM 持久 Grant 不得改写原 ceiling；DelegationRef 自身没有原 ceiling。真实 resume 持久锚点与 Task4/5 接受/保存/恢复/消费链仍需证明。
- 跨 HTTP hop 父响应关联、实际 provider 首发事实、受信 markResponseAccepted 调用点、private manifest 来源、writer/reader 取消、final/finally close 与真实跨连接矩阵留 Task4/5；本地 holder 测试不能替代实际业务循环。
- permit producer/selector 与真实 invocation/run/version、生产者连接身份的完整关系仍需可信 producer/store 接线核验。
- PG resolver、PG material_scope 同事务、完整初始 ceiling 与父材料要求/labels 并集、metadata-first 同快照 payload、原字节摘要和六服务真实 method/template 守卫均未在本补丁落地。Task5 四纯类型仅前置，不代表 Task5 闭环。
- ServiceTokens 静态 dev/test 接缝与 Spring 配置统一迁移、gateway 实际集成入站/生产桥接仍属 Task4。没有读取真实 integration 密钥或 IAM 凭据。
- SecurityConfiguration 的 **IAM_TRUST_CHAIN_UNAVAILABLE 原字节不变；BLOCKED_IAM_SCOPE_ACCEPTANCE 继续保持**。没有生产 IAM 签收。

没有 POM/业务配置/四契约/迁移/PG/前端修改，没有主进程操作、打包、提交、发布或相邻旧工程访问。最终源码与 target 已冻结，归还 root，由同 reviewer 复审 I1/I2/I3。
