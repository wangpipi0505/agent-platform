# G6 Task2 独立审查

日期：2026-10-20。审查对象为 `iam-stage-2/full.patch` 全部 11 文件，SHA256 `54656ea7bfab9b5ef745e9be684586c9b55657bb00a43180db6996a7d0e05051`。唯一工程为 `G:/207/项目/算法重组工具/代码/agent-platform`。

**Spec compliance：Needs fixes。Quality：Needs fixes。** 发现 3 项 Important/P2；没有确认 Critical 问题。三项分别涉及合法域角色兼容、大小上限内输入的栈溢出、已验授权范围拒绝的错误分类。未发现本次样本绕过授权执行 handler；这不构成全输入空间安全证明。

## 依据与范围

先读 `iam-stage-2-review-brief.md`、`iam-stage-2-implementation-brief.md`、作者 `iam-stage-2.md`，随后逐项阅读完整 unified diff，而非空 HEAD diff 或只看报告。使用 code-review 技能的逻辑、安全、测试清单。读取本工程 AGENTS、`docs/implementation-brief.md`、`contracts/README.md`；只读核实 v1.2 快照 SHA 为 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。

本次认可已接受的受限 adapter profile（RS256、at+jwt、签名 client_id、user/service 与 delegated/workload 等），没有将其冒充 v1.2/RFC 要求。没有重审 Task1 已闭合的归属规则，也未把 Task3/4 尚未交付的当前 authority、持久 grant、nonce、具体请求资源比对或逐服务守卫列为本次实现缺陷。`ServiceTokens` 的变更确实只有用途注释。

## Important findings

### I1 / P2：硬编码三角色使合法业务域角色无法进入门户上限配置

位置：`admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:61`（判断及 :64 拒绝）。

`roleAllowlist` 本应是指定 issuer/client 的部署声明上限，新增判断却要求整个配置集合只能包含 ADMIN、EDITOR、APPROVER。v1.2 `contracts/snapshot/detailed-design-v1.2.md:1273` 明确有技能 `access.roles=["ops"]` 的示例；`:2590` 要求按节点声明的角色解析确认人。现有 `engine-runtime/.../application/ApprovalService.java:59`–`:60` 对声明角色与 Actor 做精确匹配，`RunService.java:786`–`:787` 同样读取技能声明角色。因此仅仅将系统角色三个名称视为所有合法域角色，不符合已有的角色配置语义。

独立 probe 对同一有效配置只替换角色：EDITOR 通过；ops 得到 `PORTAL_CEILING_INVALID`；SERVICE 被拒绝。由于 ops 不能配置，mapper 的交集也无法合法保留它。影响为基于该域角色的合法用户无法通过相应技能/审批守卫；审批场景不能靠另授 ADMIN 自动修复。当前生产监听仍闭锁，未声称已经造成线上故障。

建议：继续拒绝 SERVICE/GATEWAY_AUTH，验证配置角色为非空合法字符串；允许部署明确登记的业务域角色进入上限，并继续与已验签声明取交集。补充配置及映射测试：配置 ops、JWT 含 ops 时保留；未配置时删除；保留角色永不映射。若要限制一个更小的角色全集，需要明确的设计变更与兼容性处理，不能只在实现报告中新增限制。

### I2 / P2：scope 正则对上限内已签输入抛 StackOverflowError

位置：`admin-common/src/main/java/cn/agentplatform/common/authority/PortalClaimMapper.java:36`。

`[A-Za-z0-9:._-]+( [A-Za-z0-9:._-]+)*` 的重复分组在固定 Java 21 运行时递归消耗栈。独立 probe 使用 2500 个单字母 scope（4999 个 ASCII 字符），真实 RS256 签名后 JWT 长度只有 **7510**，小于声明的 16384 上限。调用实际冻结 `TrustedJwtDecoder.decode()` 得到 **`java.lang.StackOverflowError`**，而非正常裁剪结果或受控 JWT 拒绝。

输入符合当前空格分隔 scope 字符语法；此样本只执行一次，没有耗尽循环、并发洪泛或慢探针。`TrustedJwtDecoder.java:63`–`:68` 捕获 JwtException/Exception，不能处理该 Error，所以大小上限并未兑现解析过程的稳定性。这里证实的是 decoder 抛出未受控 Error；没有测量 JVM 整体故障，也没有据此宣称验签绕过。

建议：使用线性扫描（或逐 token 的无递归字符校验）保持既有语法与字节上限。不要仅靠扩大线程栈或 catch Error 掩盖输入处理问题。回归应包含此大小以内、重复短 scope 的实签样本；结果应为允许 scope 的交集或明确的受控拒绝。

### I3 / P2：真实签名后的动作/范围拒绝被转换为 401

位置：`admin-common/src/main/java/cn/agentplatform/common/authority/TrustedJwtDecoder.java:96`、`:105`–`:106`，以及 `:79`–`:80` 的统一 invalid_token 转换。

固定 issuer/kid、正确 RSA 签名、正确 client/caller/target、未过期、完整 delegated 结构的票据，若 action/scope 同为未登记的 `business.delete`，执行 route 范围判断后抛 BadJwt；已登记 action 但 scope 为不覆盖它的 `unrelated` 同样抛 BadJwt。随后真实 security HTTP 链返回 401 `SYS-AUTH-401`。原 §4 明确区分无效身份 401 与已验主体的 caller/action/resource/guard 拒绝 403；scope 不覆盖动作属于此处已验范围拒绝。

独立真实 loopback HTTP 结果：

| 样本 | 实际 status/code | handler 累计 |
| --- | --- | --- |
| 结构与范围合法内部票据，本阶段链仍关闭 | 403 / SYS-AUTH-403 | 0 |
| 实签，action/scope 一致但动作未登记 | 401 / SYS-AUTH-401 | 0 |
| 实签，已登记 action 但 scope 不覆盖 | 401 / SYS-AUTH-401 | 0 |
| 错误 RSA 签名对照 | 401 / SYS-AUTH-401 | 0 |

影响：调用方把已认证主体的权限拒绝误判成无效认证，错误代码、审计分类及相应处理分支违反当前绑定协议。拒绝本身有效，没有发现 handler 被调用。

建议：把密码学、用途、必需字段/类型失败保留为 401；在签名及必要身份校验成功后，对当前已实施的登记能力/范围判断返回 403 `SYS-AUTH-403`，并通过现有 filter 保持该状态。必须避免仅凭未验 claim 返回授权结论，也不能将错误用途、错 client 身份绑定或缺 resource 字段一律改成 403。补上这两个真实 HTTP 反例与错误签名对照，断言 handler 为 0；无需引入 Task3 当前授权实现。

## 已核符合项与具体优点

- Nimbus 实际承担 RSA/JWS 验签；固定 RS256 与 JOSE 类型，原始 JSON 类型检查先于 Nimbus/Spring 日期转换；重复字段、额外 aud、时间溢出、未知 issuer/自报网络头均有明确拒绝。
- Clock 注入、零 skew、`exp>now`、内部期限和必需 nbf 已核。JWKS 每次读取固定端点，无默认缓存、旧 key 或 HMAC 回退；最多 8 把 RSA 公钥及字段限制明确。
- 角色交集、region 值交集、未知属性不映射、dataLabels 义务并集及归属精确匹配保持；Task1 已修的 null/blank 归属校验没有在本次差异中回滚。
- workload 与 delegated 的必需/禁止字段、client/caller/sub 对应、已登记机器动作限制清晰，Actor 拒绝 workload，可信 RS256 不授 SERVICE/GATEWAY_AUTH。
- `BoundedIamHttp` 在 BodySubscriber 接收时检查累计 64 KiB，而非完整读入后判断；future deadline 覆盖正文，超时/溢出取消 subscription/future，拒绝重定向，目的 URI 来自固定配置。实读测试包含恰好边界、已知长度和 chunked 超限、正文闩锁期限及重定向目标命中 0。
- 401/403/503 HTTP 基础路径已有实际 loopback 测试及 handler 计数；上游 503 不被错误包装成普通 401。此次 I3 是范围拒绝中具体遗漏的两个分支。
- 生产 `mode=iam` 的静态 BeanFactoryPostProcessor 仍在监听器创建前闭锁；真实可信测试链只由 test 中独立配置反射调用，不存在生产 ready/绕过开关。

## 执行记录与证据

读取完整 patch、配置/mapper/decoder/HTTP/Actor/security 实现与全部新增测试。检查作者完整 common 原尾与汇总：Configuration39、Policy14、Mapper4、Trusted10、旧 Common4/Schema5/Vault1，共 **77/77，0 failures/errors/skips**，BUILD SUCCESS；这是作者本次冻结代码的原运行结果，本审查没有重跑 suite 或选择器。读取真实 apply 原日志与关键行为红、NumericDate 红日志；没有把编译接缝或运行环境失败当行为失败。

差异外只为已点名角色兼容性读取 v1.2 的角色语义、各服务 api 入口角色引用，以及定位到的 Engine ApprovalService/RunService 的角色比较行；为核映射调用读取未改的 ClaimCeiling/ServiceRoutePolicy。最初几个按模块简称猜测的路径不存在，随后按工程文档的实际模块名和 `rg --files` 定位；未扩大为全工程源码遍历，也未读取相邻工程。

额外验证仅运行 [独立 probe](review-iam-stage-2/Stage2ReviewProbe.java)；目的为作者 77 项尚未回答的三个具体疑点。运行入口：

```powershell
python docs/evidence/review-iam-stage-2/run-probe.py
```

[脚本](review-iam-stage-2/run-probe.py) 从已冻结 Surefire XML 只读取 classpath，用固定 Java 21 的 `javac -d docs/evidence/review-iam-stage-2/classes` 编译 probe；`java -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -cp <probe classes; frozen test classpath> cn.agentplatform.common.authority.Stage2ReviewProbe` 运行。未调用 Maven、package 或 git；classes 仅写入本审查证据目录。HTTP 只用有限测试 RSA/随机 loopback 端点，无业务数据和真实 IAM。

第一次 probe 编译成功，但执行因本机 Unix-domain 临时路径产生 `Unable to establish loopback connection`，未进入样本。原日志保留为 [probe.log](review-iam-stage-2/probe.log)，不是业务失败证据。随后补与作者相同的 V: 临时路径参数；[probe-run2.log](review-iam-stage-2/probe-run2.log) 是成功执行的完整输出，exit 0，包含三项复现及四次 HTTP handler=0。

[preservation.json](review-iam-stage-2/preservation.json) 记录 probe 前后重新计算并匹配冻结清单的 **34 源码/POM、53 target 输出、284 manifest 原证据**，全部无变化。manifest 内已包括完整 patch、baseline、frozen、actual apply 和原测试证据；本次没有改写任何原证据。root 已核的 baseline28=Task1 ownership-fix、其他253 backend/POM 未改与真实 apply 字节相等作为交接前置，未重做闭合项。原 Task1 55/fix61 及其 review 原字节保留。

## ⚠️ 无法由本次差异验证的事项

- 真实外部 IAM 对此受限 profile 的兼容、供应商 scope 签收、生产授权撤销与安全运行；测试 profile 通过不能等同生产验收。
- Task3/4 的当前 authority、nonce/response 验证、持久授权与恢复续票、请求级 resource/action 绑定、六服务守卫及流式 permit；当前输入通过不代表这些能力完成。
- 本次没有七模块统一重跑、部署、现网数据或真实服务端点验证。I2 的复现运行时为交接指定的 Java21；未声称所有 JVM 栈大小下阈值一致。

发布状态继续 **BLOCKED_IAM_SCOPE_ACCEPTANCE**。独立审查已结束，common source 与 target 原字节未改，target 交还 root；只新增本报告及 `review-iam-stage-2/` 证据。修复上述问题后应针对变更补定向验证，再按实际改动范围完成回归。
