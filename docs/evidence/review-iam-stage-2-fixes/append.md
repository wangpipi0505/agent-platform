

---

## 2026-10-20 I1/I2/I3 集中修复独立复审

**修复后 Spec compliance：Approved。修复后 Quality：Approved。I1、I2、I3 全部闭合。** 此结论仅适用于本 Task2 的已接受输入验证 profile 与本次集中修复；上方原 Needs fixes 报告、原77项及原始复现结论保留为历史，不追改为当时已通过。

本次完整阅读 [作者修复报告](iam-stage-2-review-fixes.md) 与六文件 [full.patch](iam-stage-2-review-fixes/full.patch)，补丁 SHA256 `df1aad38462d57a0e9ccbca5cc86f85445861b01372f2950486b79fb98c61abb`。范围仅为 AuthorityProperties/PortalClaimMapper/TrustedJwtDecoder 三个生产类及对应三个覆盖测试；读取最终 decoder 的调用顺序核对 I3。没有重审全工程、提前要求 Task3 能力或重新打开 Task1 已闭合项。

| 原问题 | 修复核对与实际证据 | 结论 |
| --- | --- | --- |
| I1 配置排除合法域角色 | `AuthorityProperties.java:61` 改为明确非null/非空白角色校验，仅禁止 SERVICE/GATEWAY_AUTH，保持原配置集合非空要求。角色精确值不trim、不替换为ADMIN；既有 mapper 仍取已签角色与配置的交集。新增配置测试覆盖 ops/VIEWER/ops+EDITOR 可配置及两保留角色/空字符串/空白拒绝。新增实签映射测试验证配置 ops/VIEWER 时只保留二者，ADMIN、机器角色、未配置角色均不保留；同票据在原 EDITOR 上限下得到空角色。 | **Closed** |
| I2 有界 scope 导致栈溢出 | `PortalClaimMapper.java:36` 起改为一次迭代字符扫描与分隔；每个字符经过固定ASCII允许集判断，首/尾/重复空格拒绝，null/空串与重复scope的既有语义保留。不存在递归正则、扩大栈、catch Error或放宽JWT16384上限。语法测试覆盖合法字符、去重、各种非法空白/字符/类型。实际固定2500个a、4999字符scope经真实RSA签名后解码为空交集，再带platform.access时只保留该允许项，两个token均断言≤16384。 | **Closed** |
| I3 已验范围拒绝误返回401 | `TrustedJwtDecoder.java:62` 的 `requireServiceScope` 仅在 `selected.decode(token)` 成功返回后调用；`:106` 起把scope不覆盖/登记能力拒绝转为403。Nimbus签名、时间、issuer/aud、用途、client/caller/target、action类型、resource结构及两种模式身份限制仍由此前验证完成。新增HTTP测试对未登记动作、scope不足分别断言403；在同一范围不足样本上故意错签/错用途/错client/缺resource仍401，JWKS故障仍503，handler始终0。有效内部票据仍因本阶段链关闭而403。 | **Closed** |

I3 对旧内部矩阵的修改只把 action/scope 已验范围拒绝改为 PlatformException403；其他身份/结构错误仍要求 JwtException。生产 IAM 启动前闭锁、可信 internal 执行闭锁、固定端点/有界HTTP、机器身份不得投影Actor、Task1归属和值上限语义均未在本次六文件差异中放宽。原输入测试仍包含在作者完整common回归中。

### 本次实际核验

读取 `i1-i3-behavior-red.log` 的原始断言与汇总，确认命名I1/I3测试 **3 failures / 0 errors**，包括两个范围拒绝原实际401；读取 `targeted-green.log`，确认规定三类 **58/58、0 failures/errors/skips、BUILD SUCCESS**；读取 `full-common.log` 原尾与对应完整 XML，确认 **82/82、0 failures/errors/skips、BUILD SUCCESS**（Configuration40、Policy14、Mapper6、Trusted12、旧10）。五个新增测试名称均存在于完整XML，未将配置负向启动的预期ERROR日志误算为测试错误。

I2 的行为红仍引用原审查保存的 7510字符JWT/4999字符scope→StackOverflowError；本次复审未重放旧失控probe、未寻找阈值、未运行旧schema探针。修复后绿色结论取自作者实际固定样本及源码检查，没有伪称 reviewer 又执行过该样本。

本次未执行 Maven、Java测试或任何新网络probe；已有证据回答了三项具体问题，没有新的必须运行才能确定的疑点。仅执行只读 diff/源码/日志/XML核对及 [audit-and-append.py](review-iam-stage-2-fixes/audit-and-append.py) 的摘要与报告追加工作。独立证据存于新目录 `review-iam-stage-2-fixes/`，未覆盖原review probe。

该脚本在追加前核对当前34源码/POM、53 target、修复包manifest及591份历史证据；追加后再次核对，历史清单中唯一合法变化为本报告本身。原报告完整字节另存为 [report-prefix-before.md](review-iam-stage-2-fixes/report-prefix-before.md)，并验证追加后文件以原字节为前缀；原报告SHA `bc88528e9d8126718492f500cce191f79c540677b68dc3d75e16d82c04b6b269` 是原前缀/快照的SHA，**不是追加后整个报告SHA**。实际新SHA、各清单数量和校验结果见 [audit.json](review-iam-stage-2-fixes/audit.json)。原77、原失控与环境错误probe、Task1 original55/fix61及其全部原证据均保持原字节。

### ⚠️ 结论边界与归还

本次 Approved 不代表真实供应商 IAM 兼容或生产 scope 签收，不代表 Task3 当前authority、持久grant、nonce/响应绑定、续票，亦不代表 Task4 六服务具体动作/资源守卫完成。没有七模块统一重跑、主服务/JVM启停、真实凭据或生产端点访问、package、发布、提交。生产状态继续 **BLOCKED_IAM_SCOPE_ACCEPTANCE**。

本次集中复审结束，common源码及53个target输出未修改，target交还root。仅追加本报告并新增独立复审证据。
