# D4a I1 日期顺序修复交接

状态：I1修复已实现、定向验证并冻结，源码/target/本次证据所有权交还root，待原D4a reviewer聚焦复审；作者不自签APPROVED。无提交。

依据完整读至EOF的fix brief（5940B，SHA41ceaf92ecb50645cfd9cb4293917cbe3460aa6f9e9dc81f62e023649c5420dc）及原review（12551B，SHAfaf39ec6a08f1fcd8376077e237a870385aa2a60d5e814e5f83ed2fed84c2da6）。按receiving-code-review/systematic-debugging/TDD核实源码后实施。每个shell显式workdir为唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`，未访问相邻工程。

## 根因和最小改动

原普通时间条件通过 `nbf <= now < exp` 隐式保证 `nbf < exp`。会话elapsed分支允许exp早于now后，没有恢复独立的 `exp > nbf`，导致签名正确但不存在合法有效窗口的current或ticket先通过日期验证，再被下游误归可信截止403。这是错误分类，不是成功proof或字节逃逸。

只改两个批准文件：

- `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityResponseVerifier.java`：在解析iat/exp/nbf后加 `if(elapsedSession&&!exp.isAfter(nbf))throw AuthorityProtocol.expired();` 及两行解释旧隐式条件的注释。既有统一catch将该无效日期转503。仅elapsedSession=true经过新检查；原verify、static verifyPinned与原日期predicate不变，其他签名/header/issuer/audience/TTL/nonce/请求绑定与授权语义不变。
- `admin-common/src/test/java/cn/agentplatform/common/authority/PortalSessionAuthorizationTest.java`：新增一个 `signedElapsedCurrentAndTicketRequireExpiryAfterNotBefore`，使用已有SoftAssertions和已有LoopbackAuthority变换，四个真实签名HTTP变体都执行完再聚合断言。没有新fixture、依赖、反射、proof工厂、Clock注入入口或通用异常框架。仍调用原有session测试辅助入口，不改原有测试。

四个变体是current/ticket × nbf==exp/nbf>exp：iat=T、exp=T+1、nbf=T+1或T+2、验签时now=T+3。来源portal期限40秒，存储grant仍有效，未来nbf和来源到期不会掩盖I1。current实际请求为exchange+authorize，ticket为exchange；没有任何成功Session proof，business sends均0。本轮不重复HTTP首写suite，成功字节边界承接原D4a证据。

## 实际失败先行与定向绿

唯一cwd为上述工程根。实际受控runner仍为未改的 `.tools/run-iam-module-tests.mjs`，offline、本工程.tools/m2、Java21及V:/.local/tmp；未改POM、runner或工具链。

RED实际命令：

```text
node .tools/run-iam-module-tests.mjs iam-stage-d4a-time-order-red01 admin-common PortalSessionAuthorizationTest#signedElapsedCurrentAndTicketRequireExpiryAfterNotBefore
```

实际exit1，Running PortalSessionAuthorizationTest，Tests run1 / Failures1 / Errors0 / Skipped0。SoftAssertions的原XML同时包含四个具名差异，四个实际状态都是403而期望503；没有第一断言中断后伪称四个红。stage的variants-51536.json记录4个actualStatus403、完整iat/exp/nbf/now、来源/grant有效和真实请求序列。不是setup或compile-red。

GREEN实际命令：

```text
node .tools/run-iam-module-tests.mjs iam-stage-d4a-time-order-green01 admin-common PortalSessionAuthorizationTest#signedElapsedCurrentAndTicketRequireExpiryAfterNotBefore,PortalSessionAuthorizationTest#signedElapsedExchangeAndAuthorizeDecisionsAre403BeforeAnyRelease,PortalSessionAuthorizationTest#ordinaryVerifierAndPinnedVerifierKeepExpiredSignedEvidenceUnavailable
```

实际exit0，Running同类，3@Test全部pass、0failure/error/skipped。variants-61624.json显示四个actualStatus均503且来源/grant仍有效；两个指定控制证明合法elapsed exchange/authorize仍403，旧普通verify/pinned对signed-expired仍503。

新唯一JUnit case=1、内部变体=4，green实际JUnit cases=3，不将四变体记四个唯一case。只有这两次行为命令，已停止测试；未重跑原24/8HTTP、旧B2/B1/N3/PG/common179/全962或resourceSuite。各stage完整source-before SHA清单、两文件物理overlay、command/cwd/exit、launcher.raw、maven.raw、实际Running XML及四变体原件均保留，索引在stage-ledger.json；没有后补事前捕获。

## 冻结、重建与目标归因

正式before承接原D4a source380/target1931的sealed manifests及composition（原B2＋N4b2 fix＋D4a overlay）。开工实际source380逐条哈希与正式before一致；target-before继承已由root审计的1931 descriptor，不伪称再次扫描历史完整before target。root既有审计 `.local/iam-d4a-root-check.json` 6175B SHA75cdaf666272495705314539e73a0c0685f3f07824658427229ee58dc53cfc3b作为具名继承依据，本轮不重做其2982物理核验或历史123837/16347扫描。

最终source380只修改2、无新增/删除。source-after.json 78337B，SHA `f56b205dcf81af1e9be357e49fae284768916a920cb36343d2aac50f0ee5aed2`。

最终target1933：新增2、修改5、删除0。target-after.json 398551B，SHA `8b52aa85f9eb05ce6572e3e45668dac8566ae661c6b2e2d42a7165ffe3c00a30`。本轮最终当前target完整哈希，7个变化均有实际物理overlay及逐项归因：生产Verifier.class、新test.class、common tests.jar、当前JUnit XML和txt、两份新344B Surefire dumpstream。没有生产fat JAR打包或复制，也没有完整新树复制。

full-context两文件补丁22883B，SHA `426444b1809af8d39a5f8b00cf533a72c032b1150a800d2118808ac520cc1b54`。从两文件pristine before-overlay实际在工程root cwd执行git apply --check和git apply，`-p1 --directory=docs/evidence/iam-stage-4-d4a-time-order-fix/replay-overlay`；准确参数/exit/stdout/stderr见replay-check.json。重放与frozen组合均对完整380逻辑名称/bytes/SHA一致，不只比对两个patch文件。target1933完整逻辑组合与最终manifest一致，7个新/变目标物理SHA均已验；未变部分复用原immutable bases。

## 保护、诊断和资源

本次开工before.json真实捕获12关键锚点（含原D4a报告/seal/handoff/manifest/composition、原source/target、root新brief/review/audit/diagnostic-notes及受控runner）和8最新索引；收尾实际再次哈希均未变。全source差异仅两位置，因此其余378源码/POM/SQL/配置及gateway无变化。没有改原D4a20阶段/24例原件、原594 own seal、旧报告、review、索引或前端。root新brief/诊断是本轮明确新增before锚点，不回填旧before。

M1仅登记：原root诊断notes26122B SHAcaa988bd0a598f497a408e5610bac60343339af3fbc67a76448bcab3b4c9cc65的旧21份344B dumpstream、32编译提示和241 ERROR为root具名审计结果，未在本轮重扫旧raw。本轮两份新344B dumpstream实际读取，均为Windows Surefire absolute Manifest-JAR/different-root诊断；原文保留，不采纳关闭JDK路径检查的hint，不清洗或宣称pristine，不顺手更改泛型/日志/依赖。

两次新fork从实际四变体原件记录：red PID51536/start2026-10-20T19:56:42.886Z；green PID61624/start2026-10-20T19:57:02.191Z。命令结束后仅对这两个精确PID做CIM核验，均已不存在，见resource-check.json。没有资源行为suite、其他进程扫描、睡眠循环或压力轮询。

## 自审与交还

自审确认：问题可真实复现且四变体都红；修复只作用session日期顺序；合法elapsed和旧默认兼容控制都有本轮真实绿；四变体没有成功proof；来源/grant与nbf<=now条件有实际记录；两源范围和7目标归因准确；新/旧证据分离；380全逻辑重放、1933目标组合及不可变锚点均一致。没有已知未处理的I1缺口；M1与原review关于完整实际normal legacy启动、生产IAM/浏览器/整体项目验收的覆盖限制保持，不把有限实例化称完整应用启动。

本轮新own目录仅 `.local/iam-d4a-time-order-fix/`、`docs/evidence/iam-stage-4-d4a-time-order-fix/`及唯一新报告；受控不变runner必然另写的两个精确 `.local/iam-stage-d4a-time-order-*.log` 原件也入新leaf manifest。leaf manifest排除自身/handoff.json/seal.json以免循环，由seal绑定其真实bytes/SHA及handoff；不改原seal。完成后全部source/target/own写权交还root，等待原reviewer聚焦复审，不提前实施D4b。
