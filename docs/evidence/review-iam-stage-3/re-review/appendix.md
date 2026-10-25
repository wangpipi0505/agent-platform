

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
