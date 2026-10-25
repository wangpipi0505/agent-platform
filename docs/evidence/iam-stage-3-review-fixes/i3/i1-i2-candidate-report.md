# Task3 独立审查 I1 / I2 最小修复

本轮仅修复 `review-iam-stage-3.md` 的 I1（同一响应能力重新领取）和 I2（非 LIFO 上下文复活）。完整 common 最终 **121 tests / 0 failures / 0 errors / 0 skipped**，其中原 Task3 113 项保留、新增 8 项。独立复审尚待 reviewer；不据此开放 IAM 或签收 Task4/5。

所有本轮证据在 `docs/evidence/iam-stage-3-review-fixes/`。原 Task3 报告、113 轮日志/XML、完整 patch、549 原证据清单，以及原 review/probe 未覆盖；本轮记录的原证据保护集合 561 文件全字节保持一致。其他保护集合 501 文件也未变化。

## 授权与改动

root 已明确批准 ResponseLease 从 record 改为最窄 final class，保留七参 public 构造、七同名 accessor、值 equals/hashCode 和脱敏 toString；允许内部 opened/renewed 工厂与每次 open 生命周期。公开构造仅为数据壳，不获得已验领取能力；值相等不能证明同能力。带同一 completionPermit 的复制对象共享该 permit 生命周期，但仍因未验标记而不可领取或续核。四契约、Task3 brief/root-handoffs 不变。root 同时批准 AuthorityContext 内部 frame 栈。没有新增公共方法或扩大业务接线范围。

共修改 7 文件（完整路径见 `changed-files.json`）：

- `ResponseLease.java`：只有真实已验 open 工厂分配一次性生命周期，状态仅 0 未领取→1 已领取→2 永久终止；无全局表/授权结果缓存。公开数据壳不可领取，也不能借 check 洗成已验能力。renewed 仅携带原字段和同一个 lifecycle，更新已验 checkedUntil。equals/hashCode 仍只比较原七字段，不能用于权限判定。
- `ResponseMaterialPermit.java`：持有同一个生命周期，逐次验签前拒绝已终止能力。保留原签名、用途、绑定、期限、selector 规则。
- `HttpCurrentAuthorityClient.java`：check 在网络请求前要求已验且未终止；响应验签/原绑定校验通过后，open 创建新能力，check 沿用原能力。没有第二套 HTTP、额外可信端点或缓存。
- `StreamAuthorityGuard.java`：构造时先单次 CAS 领取再启动 watchdog；重复构造拒绝只回收新 scheduler，不终止合法 owner。首次领取后构造失败会永久终止能力并回收 scheduler。close 在回调前终止共享能力，保持幂等；renew 除字段不可变外还要求同 lifecycle。cursor/accepted/final 继续保留在唯一 holder，不会被新 holder 重置。正常单 holder 跨线程及 accepted integration drain 保留。
- `AuthorityContext.java`：ThreadLocal 保存 frame 而不是授权值；非 LIFO close 只关闭本 frame，内层继续有效；展开时跳过所有已关闭祖先。owner-thread 校验、无继承、重复 close 和默认缺失拒绝保留。同一个 VerifiedAuthority 绑定两层也不能混同 frame。
- `ResponseAuthorityTest.java`、`CurrentAuthorityClientTest.java`：新增有限回归；原同 lease 两 guard 测试改为两个独立合法 open/binding。原 wrong-access/owner/payload/version 测试保留，没有删除跨 holder 拒绝断言。

核对当前工程 ResponseLease 调用点及 `Json.java`：生产使用只在 common 的 open/check/guard；HTTP 请求显式传 ResponseBinding 和 continuation_ref，没有序列化/反序列化整个 ResponseLease 的实际调用点。未另造序列化框架；public 数据壳不承诺通过反序列化获得能力。record 反射类型形态确已改变，后续新增调用点应使用真实 open/check 返回值。

## 行为红绿与实际命令

统一工作目录为 `G:/207/项目/算法重组工具/代码/agent-platform`，Java21，离线本地依赖。实际命令前置：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
```

1. **先写回归、未改生产代码时实际红**：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" "-Dtest=ResponseAuthorityTest#reviewI1*,CurrentAuthorityClientTest#reviewI2*" "-Dsurefire.failIfNoSpecifiedTests=false" *> docs/evidence/iam-stage-3-review-fixes/red.log
```

结果 **7 / 6 assertion failures / 0 errors / 0 skipped**，exit 1。原日志 `red.log`、两类 XML `red-selected-xml/`、测试源 `red-source/`，生产原字节在完整 `baseline/`。失败来自以下真实行为：

| 回归方法 | 原行为红 | 修复结果 |
| --- | --- | --- |
| reviewI1ActiveRenewedAndClosedLeasesCannotClaimAnotherHolder | 活跃 lease 构造另一个 holder 未抛异常 | PORTAL/INTEGRATION 活跃、renewed、close 后均拒绝；跨线程原 owner 正常、取消一次 |
| reviewI1ClosedSseLeaseCannotReplayConsumedSequence | close 后重建，Clock +21、撤权后同 seq=1 再次通过 beforeMaterialWrite | 构造即拒绝，无第二次释放 |
| reviewI1ClosedModelLeaseCannotReplayFinal | close 后重建，Clock +21、authority 不可用后原 final 再通过 | 构造即拒绝 |
| reviewI1PublicCopiesCannotClaimOrLaunderSamePermit | 同 permit 的等值 public 副本可以创建 holder | 等值及改 binding/source/continuation/checkedUntil 副本不可领取/check；原能力正常 |
| reviewI1FailedConstructorTerminatesClaimAndShutsScheduler | scheduler 拒绝首次构造后仍可重新领取 | 构造失败终止；重复/空参数失败 scheduler 均 shutdown |
| reviewI2NonLifoSameAuthorityNeverRestoresClosedFrame | 同对象两层非 LIFO 得 [false,false]：内层被清除、全部关闭后仍有授权 | [true,true]：内层继续有效、最终无上下文 |

红中 `reviewI2LifoExceptionOwnerAndChildBoundaries` 本来通过；它覆盖 LIFO、异常 finally、子线程不继承、异线程 close 拒绝且 owner 不受影响、最终 current 缺失。上表各方法中首个失败之后未执行的断言只算新增绿色覆盖，未虚称为独立红。未重跑旧 reviewer probe、旧 schema/scope 探针或其他业务 reactor。

2. **修复后三类命名覆盖**：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" "-Dtest=ResponseAuthorityTest,CurrentAuthorityClientTest,ServiceTicketProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" *> docs/evidence/iam-stage-3-review-fixes/green-named.log
```

结果 CurrentAuthorityClientTest 17、ResponseAuthorityTest 18、ServiceTicketProviderTest 3，**38/0/0/0**，exit 0。`green-named-selected-xml/` 保留当轮三类 XML。无新增 unchecked 编译提示。

3. **明确补续核状态覆盖**（仅新增绿色覆盖，不冒充红）：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" "-Dtest=ResponseAuthorityTest#reviewI1RenewalPreservesCursorAcceptanceAndFinalReservation" "-Dsurefire.failIfNoSpecifiedTests=false" *> docs/evidence/iam-stage-3-review-fixes/green-renewal.log
```

**1/0/0/0**，exit 0；XML `green-renewal-xml/`。SSE 写 seq1 后续核，seq1 拒绝、seq2 通过，过期撤权后原 holder drain seq3 通过且 seq2 拒绝；model final 消费后即使 check 取得 renewed，也不能重新领取或重复 final，close 后 check 拒绝。

4. **最后且本轮唯一完整 common**：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp" *> docs/evidence/iam-stage-3-review-fixes/final-common.log
```

2026-10-20 13:59:51 完成，**121/0/0/0**，exit 0；`final-common-xml/` 的 10 份 XML 合计相同。当前三类为 17+19+3=39。M1 既有配置负向测试仍输出预期启动错误堆栈，由 root 跟踪；没有改旧配置测试或掩盖原日志。

初始 `red-xml/`、`green-named-xml/` 是通配符归档，另含 surefire 中继承的未运行类 XML；保留原归档但不合计。精确当轮 XML 位于上述 selected 目录，`xml-audit.json` 按指定类/方法核对 7、38、1、121 四轮数值，没有把继承 XML 拼入当轮结果。

编辑辅助脚本首次读取 AuthorityContext 时因 Python 默认 GBK 与 UTF-8 不符停止；该文件当时尚未写入，随后以显式 UTF-8 完成。它不是测试红，没有计入行为证据。

## 冻结、最小 patch 与逐字节复核

- `source-before.json`/`baseline/`：完整 63 源码/POM，与原 Task3 source-after 一致。`source-after.json`/`frozen-final/`：最终 63，7 修改，0 新增/删除。
- `full.patch` 是完整最小修复 patch（不依赖空 git diff），SHA256 **0360cba91691676bbaefc37fd450c21765e41b31cb0287dceae92228655c41d2**。
- 最终 `source-after.json` SHA256 **a3d4b847dae2d7a3f8e98a92795ba681b73fd9ca7472746077c30746ad80257d**。
- 在独立 `applied-final/` 复制完整 baseline，真实运行 `git -c core.autocrlf=false apply --check <full.patch>` 和 `git -c core.autocrlf=false apply <full.patch>`，两命令均 exit 0、stdout/stderr 空。`apply-proof.json` 保存 argv/结果；applied-final、frozen-final、live 63 文件路径集合、SHA 和实际 bytes 全部相等。
- 测试后仅恢复原 EOL：编辑工具把原 LF 文件写成 CRLF，第一次冻结保留在 `frozen/`，对应 `pre-eol-*` 清单/patch/proof。最终恢复原格式后重新生成最小 patch。`line-ending-proof.json` 逐文件记录测试时 SHA、最终 SHA、规范化 CRLF→LF 后 bytes 相等；7 个编辑文件中 6 个仅 EOL 改变，ResponseLease 原本 CRLF 且字节未再变。没有逻辑/断言改动。root 明确批准仅 EOL 无需重跑，因此没有追加完整 suite。不能将测试后 EOL 变化描述为原始源码字节完全未变。
- `target-after.json`/`frozen-target-final/`：242 文件逐字节一致；SHA 清单 **6c766c88f593a86cb6139079a70874c17acaf03ef0b500fb2e14655ca1d7da7f**。未 package，target 中继承的旧 jar 没有重建。
- `protected-before/after.json` 501 文件未变；`original-evidence-before/after.json` 561 文件未变；`review-original-prefix.md` 保存旧 reviewer 全文，最终验证旧前缀原字节相同。后续 reviewer 合法追加由 root 管理，本轮未写其文件。
- `freeze.py`、`restore-line-endings.py`、`freeze-final.py` 记录可复核步骤；首次 CRLF patch 真实 apply 也通过但有换行噪声，保留为 pre-eol 证据；最终 patch apply 无该噪声。无 HEAD/remote、未造提交。

## 实际开源对照与未完成边界

本轮实际读取本地 Eclipse Adoptium JDK 21.0.12.8 的 `lib/src.zip` 中 ThreadLocal.java（get/remove/getMap，普通 threadLocals）、AtomicInteger.java（compareAndSet/set）、ScheduledThreadPoolExecutor.java（scheduleAtFixedRate/delayedExecute/shutdownNow）。逐条源码 SHA 与来源在 `open-source-read.json`，实际摘录在 `*-source-excerpts.txt`。据此采用普通 ThreadLocal frame、CAS 单次领取和拒绝构造时 scheduler 清理；没有声称阅读未打开的项目源码。

本轮仅证明 common 内单次 open 的本地对象生命周期。以下原 review 跨 Task 边界完整保留：

- 真实 IAM 持久 Grant 不得改写原 ceiling；DelegationRef 自身不携带原 ceiling，真实 resume 持久锚点及 Task4/5 接受/保存/恢复/材料消费链仍需证明。
- 跨 HTTP hop 父响应关联、实际 provider 首发事实、可信 markResponseAccepted 调用点、private manifest 来源、实际 writer/reader 取消及 final/finally close 留 Task4/5。不能用本地 holder 测试代替真实业务循环或跨连接矩阵。
- permit producer/selector 与真实 invocation/run/version、生产者连接身份的完整业务关系仍需可信 producer/store 接线核验。
- PG resolver、PG material_scope 同事务、完整初始 ceiling 与父材料要求/labels 并集、metadata-first 同快照 payload、原字节摘要及六服务真实 method/template 守卫均未落地于本补丁。Task5 四纯类型只是已获许可的前置，不代表 Task5 完成。
- ServiceTokens 静态 dev/test 接缝与 Spring 配置统一迁移、gateway 实际集成入站和生产桥接仍属 Task4。没有读取真实 integration 密钥或真实 IAM 凭据。
- `SecurityConfiguration` 的 IAM_TRUST_CHAIN_UNAVAILABLE 闭锁原字节保持；**BLOCKED_IAM_SCOPE_ACCEPTANCE 继续保持**。不作生产 IAM 签收。

没有 POM/业务配置/四契约/迁移/PG/前端修改，没有主进程操作、打包、提交、发布或相邻旧工程访问。源码与 target 冻结后归还 root；等待同 reviewer 独立复审 I1/I2。
