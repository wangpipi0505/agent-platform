# G6 Task3 独立审查

## Spec Compliance

**❌ Needs fixes。** Task3 的 common 接口、固定 authority 交互、独立 response permit 和有限 HTTP policy fixture 已实施；但原响应关闭后能力可重新绑定 holder，以及嵌套上下文清理后恢复已关闭授权，违反本任务的响应终止及请求上下文生命周期要求。两项均为 Important，见 I1、I2。

**审查对象：** `docs/evidence/iam-stage-3/full.patch`，SHA256 `93b1771243b0b7c1674099b72fe4be89f3c5fee6570e191f6406d6872bda5d19`。依据完整 Task3 brief、root-handoffs、ceiling-propagation-review-note 和作者报告；完整 patch 按顺序仅读一遍，没有取空 git diff，也未重新读变更源码或爬其他业务模块。以下源码位置均为本次 patch 的新文件行号。

## Strengths

- 固定配置端点、独立 workload 凭据、签名决定的 nonce/完整请求摘要/current 布尔校验形成明确边界；失败没有旧成功结果回退。证据：`admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:138`、`WorkloadCredentialResolver.java:11`；`CurrentAuthorityClientTest.java:36` 实际覆盖 scope/role/version/grant/503/JSON/签名/JWKS 拒绝及显式恢复。
- 初次门户交换同时限制 current Actor 与 initial ceiling；forward 保持完整 operation，resume 必须得到 DELEGATED。证据：`HttpCurrentAuthorityClient.java:41`、`:51`、`:57`、`:130`；`CurrentAuthorityClientTest.java:26`、`:53`、`:75`。fixture 的原 ceiling 存在 Grant 中，resume 继续从同一 Grant 派生 operation（`LoopbackAuthority.java:45`、`:167`、`:218`），不能仅靠当前角色把 ADMIN 补回。
- continuation、普通 service ticket、completion permit 的签名用途分开；permit 固定开流公钥且每次仍校验独立有效期，普通材料 current 到期规则未被放宽。证据：`HttpCurrentAuthorityClient.java:172`、`ResponseMaterialPermit.java:39`、`MaterialAuthorization.java:11`；`ResponseAuthorityTest.java:92`、`:199`。
- 当前 holder 的关闭先设置原子停止位，续核返回后再次检查；原行为红包含真实断言失败，最终测试也覆盖关闭与续核竞争。证据：`StreamAuthorityGuard.java:50`、`:81`；`ResponseAuthorityTest.java:35`；原始 `red-close-race.log:45`。
- 集成 fixture 通过独立签名域的实际 HTTP resolver 查询，验证 DTO、版本和绑定，无 authority 回调；它明确只位于 test 源。证据：`LoopbackAuthority.java:256`、`:279`；`CurrentAuthorityClientTest.java:114`、`:124`、`:137`。本次没有以该 fixture 签收 PG resolver。

## Issues

### Critical

未发现。

### Important

#### I1 — 同一已验 lease 可重新构造 holder，关闭后的 completion permit 和已消费事件因此复活

**位置：** [StreamAuthorityGuard.java:23](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/main/java/cn/agentplatform/common/authority/StreamAuthorityGuard.java:23)、[StreamAuthorityGuard.java:28](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/main/java/cn/agentplatform/common/authority/StreamAuthorityGuard.java:28)、[StreamAuthorityGuard.java:81](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/main/java/cn/agentplatform/common/authority/StreamAuthorityGuard.java:81)；[ResponseMaterialPermit.java:39](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/main/java/cn/agentplatform/common/authority/ResponseMaterialPermit.java:39)。

构造器接受任意仍被调用者保留的同一 ResponseLease，每个新实例重新创建 stopped/accepted/finalMaterialConsumed/cursor；close 只终止当前 guard。permit 本身没有被某个 holder 独占或永久关闭的状态，所以同一已验 permit 可反复装入新 holder。`ResponseMaterialAccess.belongsTo` 仅防止把旧 access 交给另一 guard，不能防止新 guard 自己创建另一个合法 access。

**精确触发与影响：** 一次正常 INTEGRATION open 得到 lease；第一个 guard 合法 accepted、写出 seq=1 后 close；用原 lease 新建第二个 guard，accepted 后取得新 access；推进 Clock 21 秒并撤 grant，第二个 guard 仍进入 drain，`beforeMaterialWrite` 接受已在第一 guard 消费过的同一 seq=1。没有新 open、没有新当前决定。该问题需要内部适配器保留并再次使用合法 lease，不是声称外部请求可构造任意 permit；但它直接破坏 brief §2.4.1 的“关闭后同 permit 重放必拒”和 holder 专属生命周期，是本轮 common 必须保证的边界。

**独立证据：** [Task3ReviewProbe.java:33](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/Task3ReviewProbe.java:33)，[probe.log:2](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/probe.log:2)：`same_permit_event_replayed_after_original_holder_close=true`、`reconstructed_holder_drains_after_expiry_and_revocation=true`。原 `ResponseAuthorityTest.java:110` 甚至已把一个 lease 分配给两个 guard，但只接受其中一个，没有验证第二个 holder 的所有权或重建拒绝。

**最小修复方向：** 把已验证响应能力的 holder 领取与终止状态绑定在单次 open 的不可重复领取能力上，构造第二个 holder 时默认拒绝；close 使该能力终止且不可重新领取。不要通过全局旧授权缓存解决。保持正常单 holder 的显式跨线程使用；加入同 lease 重建、关闭后重建、已消费 SSE seq/model final 不可重放的有限测试。

#### I2 — 同线程非 LIFO close 会恢复已关闭的请求授权

**位置：** [AuthorityContext.java:12](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:12)、[AuthorityContext.java:19](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:19)。

每层仅保存 previous 的授权值，未保存/检查其 scope 是否仍存活。`outer=bind(a); inner=bind(a); outer.close(); inner.close()` 中，outer.close 移除当前值，inner.close 又把已经关闭的 outer 的 previous 值写回 ThreadLocal；两个 scope 均关闭后 `current()` 仍返回授权。owner-thread 和单对象 closed 位不能阻止这一点。

**影响：** 对正常词法 try-with-resources 的 LIFO 用法没有此问题；但公共 API 没有强制该顺序，异常清理/手动关闭时可留下授权供同一复用请求线程的后续无 bind 代码读取。新授权仍需要在线校验，不代表已证明业务越权；本组件“仅本请求上下文、结束清理、无上下文默认拒绝”的契约已经失效，不能依赖以后每个调用点都避开该路径。

**独立证据：** [Task3ReviewProbe.java:20](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/Task3ReviewProbe.java:20)，[probe.log:1](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/probe.log:1)：`context_present_after_both_scopes_closed=true`。probe 使用同一个真实经 HTTP authorize 的对象，不依赖伪造 Actor/VerifiedAuthority；独立一次性 worker 随后退出。现有 `CurrentAuthorityClientTest.java:156` 只覆盖单层 scope 和不跨线程继承。

**最小修复方向：** 使用具备生命周期的 scope frame/栈，禁止恢复已关闭 frame，或明确强制 LIFO 并提供可靠的最终请求清理；顺序错误不能在两个 close 后遗留可读取授权。增加嵌套 LIFO、非 LIFO、重复 close、异常路径和最终 current 缺失的有限测试。

### Minor

#### M1 — 冻结完整测试输出仍含大量预期启动异常堆栈（既有测试噪声）

**位置：** `docs/evidence/iam-stage-3/frozen-full-common.log:44`，同日志 `:59` 指向 `AuthorityConfigurationTest.java:96` 的预期异常断言；`:5648` 确认该类 40 项通过。该项不属于本轮新增功能回归，也不影响 113 项通过事实，但数千行预期 ERROR 堆栈降低真实失败的可辨识性。建议由 root 在所属测试维护时捕获/约束预期日志，保留断言，不要求本轮越界改旧测试。

## 范围与逐项结论

| Task3 约束 | 核验结果和源码证据 |
| --- | --- |
| 仅 common 客户端/上下文/生命周期/fixture | patch 的 29 新增、4 修改均在 common；材料四纯类型和 BoundedIamHttp/parseKeys/Actor 静态兼容属于 root-handoffs 明确许可。没有生产 IAM/PG/store 变更。 |
| Actor 不能在默认/iam 自签 | `ServiceTokens.java:17` 默认拒绝；`ServiceTicketProviderTest.java:9` 覆盖默认、iam、未完整开启的 test/dev。root 许可的 system properties 接缝保留，后续 Spring 接线不在本轮签收。 |
| 当前撤权/错误主体资源/版本/503 默认拒绝 | `HttpCurrentAuthorityClient.java:63`、`:99`、`:138`；`CurrentAuthorityClientTest.java:36`、`:82`、`:93`。没有授权缓存。 |
| 原上限在交换与 forward 中不能扩大 | `HttpCurrentAuthorityClient.java:41`、`:57`、`:130`；本地映射上限、原 initial ceiling 摘要和 operation 保持均有检查。 |
| resume 是显式新 DELEGATED operation | `HttpCurrentAuthorityClient.java:51`；`LoopbackAuthority.java:167` 从原 Grant 新建 operation；`CurrentAuthorityClientTest.java:53` 覆盖原门户过期后可恢复且原 scope 不扩大。 |
| approval / workload 分域 | `HttpCurrentAuthorityClient.java:78`、`:89`；`CurrentAuthorityClientTest.java:105`、`:156`，批准主体及 APPROVER/current 被核验，workload 不生成用户对象。 |
| request-local context / 显式异步对象 | `AuthorityContext.java:8` 普通 ThreadLocal 不继承；存在 I2 生命周期缺口。 |
| response immutable binding / 原期限 / close | `HttpCurrentAuthorityClient.java:152`、`:172`；`StreamAuthorityGuard.java:33`、`:81` 保持本 holder 的停止检查；存在 I1 同 lease 重建缺口。 |
| ordinary 与完成 permit 分用途 | `MaterialAuthorization.java:11`、`ResponseMaterialPermit.java:39`、`StreamAuthorityGuard.java:62`；普通 current 过期拒绝，完成能力只经受限 access。I1 使该能力的终止边界尚不合格。 |
| 集成 policy 走独立 HTTP / 不签 PG 实现 | `LoopbackAuthority.java:256`、`:279`；`CurrentAuthorityClientTest.java:114` 至 `:143` 覆盖次数、无环、签名域/版本/字段/503/404/布尔类型拒绝。 |
| 有效行为红与冻结完整绿 | 原始命名红日志确认断言 failure、0 error；最终独立解析 10 XML 得 113/0/0/0，与 `frozen-full-common.log:5670` 一致。自己的 probe 发现的是已有测试未覆盖路径。 |

## ⚠️ 交 root 跟踪的跨 Task 限制

- 原上限在 resume 的持久锚点是 authority 自有 Grant：接口 `DelegationRef.java:6` 本身没有原 ceiling；客户端 `HttpCurrentAuthorityClient.java:51` 接受 authority 新签 operation/initial ceiling 并校验其内部一致性。当前有限 fixture 确实从同一原 Grant 派生，本轮该路径已核；真实 IAM 持久 grant 不可改写原 ceiling，以及 Task4/5 在接受、保存、恢复、材料消费时保持相同可信上限，仍必须另行证明。不能将本轮有限单测视为真实持久链验收。
- 跨 HTTP hop 的父响应关联、实际 provider 首发事实、private manifest 来源、writer/读连接取消与 final/finally close 均依赖 Task4/5 接线。`StreamAuthorityGuard.java:31` 的 markResponseAccepted 是受信调用点 API，不能由请求体触发；本轮没有检查真实业务循环。
- `ResponseMaterialPermit.java:29` 的 producer 和 `:34` 的材料 selector 在 open 中检查存在性，具体材料按 permit selector 比对（`:55`）；selector 与实际 invocation/run/version、生产者连接身份的完整业务关系还需在后续可信 producer/store 边界裁定和验证。当前 fixture 的有限关系见 `LoopbackAuthority.java:246`、`:248`，不代替真实跨域绑定验收。本轮未就此扩展 probe。
- PG material_scope 同事务、完整初始 ceiling 与父材料要求/labels 并集、metadata-first 同快照读 payload、原字节摘要以及实际六服务守卫均未在该 patch 实现，应继续由 Task4/5 核验。
- 生产启动闭锁保持未被本 patch 触及；root 已提供保护文件核验结果。本审查未因 common 测试通过允许开放 mode=iam，**BLOCKED_IAM_SCOPE_ACCEPTANCE 继续保持**。不替代生产 IAM 签收。

## 检查与独立证据

- 本地 reviewer 模板已读：`D:/00-Work/02-Application/01-CodeIDE/UserData/Codex/DotCodex/skills/subagent-driven-development/task-reviewer-prompt.md`。没有派子 agent、服务启停、package/install、提交、发布、依赖下载；只启动本次独立 fixture 的临时 loopback handler。
- 原日志提取保存在 [original-log-extract.json](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/original-log-extract.json)，独立读取的 10 XML 摘要、测试名称和 SHA 保存在 [frozen-xml-audit.json](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/frozen-xml-audit.json)。没有把旧 112 轮或被覆盖 XML 拼为完整结果。
- 仅执行一次有限 Java probe，真实原文 [Task3ReviewProbe.java](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/Task3ReviewProbe.java)、可复查命令 [run-probe.ps1](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/run-probe.ps1)。classpath 来自冻结 Response XML，并将两个 target class 目录替换为 frozen-target；javac 仅输出本审查目录，Java21，Clock、小样本，没有高次数/耗尽/阈值搜索。
- 首次 runner 在 javac/java 之前因 XML classpath 末尾空项终止，随后仅规范化 classpath 再执行；该准备失败已在 [setup-note.txt](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/setup-note.txt) 披露，没有把它计作行为测试。
- 最终 probe 的 compile/run 均 exit 0；这表示两个缺陷被成功复现，不表示产品通过。输出有 `business_sends=0`。前后逐文件核对 **63 个源码/POM、live target 237 文件、frozen-target 237 文件**，SHA 全部与作者冻结清单相符：[probe-audit.json](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/probe-audit.json)。
- 未重新运行 31 定向/113 完整测试、schema 探针或其他模块，也未读取相邻旧工程、环境秘密/vault/真实凭据。除本审查报告及 `review-iam-stage-3/`，未写工程文件。**common/target 只读核验已结束，现归还 root。**

## Assessment

**Spec：Needs fixes。Quality：Needs fixes。**

固定 authority 协议与用途分离具有有效测试证据，但请求上下文和完成能力均缺少跨 scope/holder 的终止不变量。修复 I1、I2 并做对应有限回归后再复审；Task4/5 与生产验收保持独立门禁。


---

## 合并修复独立复审（2026-10-20，I1 / I2 / I3）

**Spec compliance：✅ Approved。Task quality：Approved。I1、I2、I3 均 Closed。** 此结论针对最终合并修复及原 Task3 范围，替代上方“需修复”的当前状态；上方原报告与独立 I3 addendum 保留为原始发现记录。M1 既有测试日志噪声仍为非阻塞 Minor。

**复审对象与授权：** `docs/evidence/iam-stage-3-review-fixes/i3/full.patch`，SHA256 `7c7e3c3f4e51e429c02e8b187ba32255ebb1a1058a8f568f5ba4de5d7e42f2c2`，从原 Task3 基线合并修改 8 文件。完整补丁按顺序读一次。root 已明确允许 ResponseLease 改为最小 final class、保留七参构造/accessors/值 equals/hashCode/脱敏 toString，public 数据壳无真实能力；允许 per-open 生命周期随续核共享及 context frame 栈。I3 仅去掉固定 APPROVER 条件；冻结 confirmRoles 的业务判定仍属 E。

### 三项发现的关闭依据

| 发现 | 状态 | 实际源码与行为证据 |
| --- | --- | --- |
| I1：原 lease 重建 holder 可复活并重放 | **Closed** | `ResponseLease.java:27` 的已验 open 分配/取得 per-open 生命周期，`:32` 的 renewed 共享该对象，`:36` 拒绝未验 public 壳，`:61` 的 CAS 状态只能未领取→已领取→终止。`StreamAuthorityGuard.java:31` 在调度前领取，`:36` 在领取后构造失败时终止；重复领取失败只清理新 scheduler，不终止合法 owner。`:51` 校验续核同 lifecycle，`:91` 在 close/stop 中终止共享能力。`ResponseMaterialPermit.java:42` 逐次验签前拒绝终止状态。没有全局授权表、缓存续权或通过 equals 恢复能力。 |
| I2：非 LIFO close 恢复已关闭授权 | **Closed** | `AuthorityContext.java:8` 保存独立 frame，`:13` 同一 VerifiedAuthority 也分配不同 frame；`:22` 幂等关闭，`:25` 仅在本 frame 是顶层时展开并跳过所有 closed 祖先。先关闭 outer 不破坏仍存活 inner，随后关闭 inner 不再恢复 outer。普通 ThreadLocal、owner-thread 检查保留。 |
| I3：approver 固定要求 APPROVER | **Closed** | `HttpCurrentAuthorityClient.java:89` 的方法仅删除固定角色 if；原在线 query、authority 映射、真实 approvedBy 与 grant 绑定仍保留，没有增加角色豁免或替代角色门槛。`LoopbackAuthority.java:37` 和 `:185` 仅使有限 fixture 的批准者当前角色可配置，默认仍 APPROVER。 |

I1 的新增测试不是只验证对象字段：`ResponseAuthorityTest.java:11` 实际通过 HTTP open/check 证明续核保持已消费 seq、accepted 和 final reservation；`:33` 覆盖 PORTAL/INTEGRATION 活跃、renewed、关闭后不得再次领取，同时原 owner 显式跨线程正常。`:46`、`:53` 重现并阻断原 SSE/model 重放触发；`:61` 验证值相等 public 副本与篡改数据壳不能领取或用 check 洗成能力，合法原对象仍可使用；`:74` 覆盖调度拒绝后的终止与 scheduler 回收。原同一 lease 同时分配两个 holder 的测试已改为两个独立合法 open，保留原材料错配断言。

I2 的 `CurrentAuthorityClientTest.java:40` 使用相同真实已验授权对象测试非 LIFO、重复 close，断言 inner 保持有效且最终 current 缺失；`:50` 覆盖 LIFO、异常退出、异线程 close 拒绝、子线程不继承及最终清理。没有改成 InheritableThreadLocal 或增加后台隐式授权。

I3 的 `CurrentAuthorityClientTest.java:11` 通过真实 HTTP 签名的 EDITOR 批准者证明，核其主体、资源与 grant；`:22` 对同一 EDITOR 路径继续覆盖撤权、错误主体、503、版本变化、current=false、错 nonce、错资源、坏签名、错 grant、过期。原 APPROVER 当前撤销测试仍在最终完整测试中。**获取 EDITOR 当前证明成功不等于业务批准成功**，本复审没有以此关闭 E/G2 的 confirmRoles 负例要求。

### Spec 与 Quality

- **Spec Approved：** 三项 common 级缺口均得到针对性修复，且根授权的 ResponseLease 形态变更没有增加外部业务协议、生产 IAM、PG/store 或业务批准逻辑。固定七字段值接口仍在，能力身份明确独立于值相等和 public 数据构造。`HttpCurrentAuthorityClient.java:166` 的 check 在网络前拒绝未验/终止 lease，`:203` 只有验签及原绑定通过后才创建 open 能力或共享续核能力。
- **Quality Approved：** 生命周期只由一次 open 的对象持有，CAS 保证单 owner，终止不能逆转；renew 与原 permit 共享同一终止状态。context frame 处理实际非 LIFO 清理，无全局注册表。新增测试覆盖原失败、正常续核、异常构造及角色合法正例，既有关键约束未被删除。
- **Critical / Important：** 本次合并修复没有新增阻塞发现。原 I1/I2 与独立 addendum 的 I3 关闭。
- **Minor M1：** 最终完整日志仍有既有配置负向测试预期启动堆栈，作者未隐藏原输出、未改旧测试；继续交 root 作为日志维护事项。它不改变 `final-common.log:5669` 与 10 份最终 XML 的 123/0/0/0。

### 既有运行证据与本次只读核验

| 实际运行 | 原始证据 | 独立核到的结果 |
| --- | --- | --- |
| I1/I2 命名行为红 | `iam-stage-3-review-fixes/red.log:45`、`:64`；`red-selected-xml/` | 7 tests，6 assertion failures，0 errors，0 skipped；包括原 context 残留与同 lease 重放路径。LIFO 正例本来为绿，不虚称红。 |
| I3 命名行为红 | `i3/red.log:48`、`:54`；`i3/red-xml/` | 2 tests，1 assertion failure，0 errors，0 skipped；实际落在原 `approver:96` 固定角色门槛。 |
| 最终三类命名绿 | `i3/green-named.log:55`；`i3/green-named-xml/` | 41/0/0/0，19 current + 19 response + 3 provider。 |
| 最终同码完整 common | `i3/final-common.log:5669`、`:5677`；`i3/final-common-xml/` | 10 类合计 **123/0/0/0**，BUILD SUCCESS；新增 10 个具名测试均在最终 XML，未混用旧 121 候选。 |

作者实际命令与过滤在修复报告中完整保留：Java21、`mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp"`；命名红分别使用 `ResponseAuthorityTest#reviewI1*,CurrentAuthorityClientTest#reviewI2*` 与 `CurrentAuthorityClientTest#reviewI3*`，命名绿为三个类，最终完整轮无 Dtest 过滤。本复审直接读原日志与原 XML，没有重跑这些命令，也没有新 probe。

本 reviewer 的只读脚本 [verify-inputs.ps1](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/re-review/verify-inputs.ps1) 已实际执行，结果保存在 [verified-inputs.json](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/re-review/verified-inputs.json)：

- live、最终 frozen、完整合并 patch 的 applied、I3-only 的 i3-applied **四份各 63 文件 SHA 相符**；live 与 frozen-target **两份各 245 文件 SHA 相符**。实际 apply 的四个 exit 0 命令记录读取自 `i3/apply-proof.json`，本次没有重新执行 git apply。
- 独立解析 selected 红 XML、I3 红/命名绿/最终完整 XML，结果为 7/6/0/0、2/1/0/0、41/0/0/0、123/0/0/0；逐 XML SHA 与命名用例一并记录。原日志行级提取在 [original-log-extract.json](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/review-iam-stage-3/re-review/original-log-extract.json)。
- 追加前原报告 **15,212 字节**，SHA256 `df842cf0e5b1d987f077419e4f3b9ba2794c7307bdda7eabb35ca605fbc4e9d9`；追加保留此前缀。I3 addendum、原 probe、原 reviewer 证据与作者全部历史候选未改。
- root 另已核对 501 保护文件、561 原证据、881 历史候选及 536 最终证据清单，见 `.local/iam-stage-3-fix-root-handoff-check.json`；本 reviewer 将其标为 root 提供的保护核验，并未声称再次重复遍历全部保护集合。

### ⚠️ 跨 Task 项：本次事实与后续边界

| 项目 | 本次已核事实 | 仍由 root 交后续 Task 落实 |
| --- | --- | --- |
| 原 ceiling 在 resume 的持久锚点 | 合并修复未改原 grant/operation/ceiling 路径，有限 fixture 仍从原 Grant 派生。 | 真实 IAM 不可改写原 Grant ceiling；D/E 的接受、持久化、恢复、材料消费链须保持该可信上限。DelegationRef 自身不携带原 ceiling，不能把 current 当历史上限。 |
| per-open 能力与实际 HTTP 响应 | 当前 JVM 内单次领取/终止、renew 共享、public 数据壳拒绝已修复。 | 各服务须真实 open 本地能力；不能序列化/反序列化整个 lease 取得授权。跨 hop 父响应关联及不递增期限继续由受信 authority 绑定。 |
| 首发事实与输出清理 | guard 保留 `markResponseAccepted`、写前校验、停止位、watchdog 和幂等 close。 | D/E 的真实 connector/writer 只能在合法 SSE 受理或实际首发后标 accepted，final/error/timeout/cancel 路径须 finally close 并取消本地读取；组件测试不证明真实业务循环。 |
| 材料 selector / producer / manifest | 原 permit 签名、selector 比对及本地材料检查未被修复放松。 | 真实 run/invocation/attempt/version、生产者连接身份、同一受理连接上的 private manifest 必须联通验证，不能由请求体或任意 URL 提供。 |
| PG 与材料完整性 | 本补丁没有 PG resolver、material_scope/store 或业务消费点。 | PG 一致快照、事实与 scope 同事务、完整初始 ceiling 和父要求/labels 并集、先元数据判权再读 payload、实际字节摘要及六服务守卫仍是 D/E 工作。 |
| 批准角色 | common 现在可返回合法 EDITOR 当前证明，全部信任绑定检查仍在。 | E/G2 按真实持久 approvedBy 与冻结 confirmRoles 判定；必须同时保留 EDITOR 正例、要求 APPROVER 但当前仅 EDITOR 的负例，且 ADMIN/EDITOR 无自动豁免。 |
| 配置与生产门禁 | 本合并补丁未触及生产启动闭锁或静态 dev/test 模式接缝。 | D 统一 Spring/静态配置与 gateway 集成入口，完整真实链及真实 IAM 未签收前保持 **IAM_TRUST_CHAIN_UNAVAILABLE / BLOCKED_IAM_SCOPE_ACCEPTANCE**。 |

本次无尚未解释的具名疑点需要额外 probe；没有套件重跑、生产/测试源码改写、target 写入、主服务启停、打包、提交或发布。只写本报告的追加和自身复审证据目录。**common source/target 的只读复审已结束，归还 root。**

**最终双结论：Spec compliance Approved；Task quality Approved。I1/I2/I3 Closed；Task4/5 与生产 IAM 验收保持独立门禁。**
