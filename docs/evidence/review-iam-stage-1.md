# G6 Task1 独立审查

日期：2026-10-20。范围仅 `admin-common` 的 Task1 类型、目录、属性语义、模式装配及测试。审查者未参与这批实现；按 `iam-stage-1-review-brief.md`、完整 `iam-stage-1-implementation-brief.md`（包括追加原计划 §4）和实际差异审查，未把作者报告视为独立批准。

## Spec compliance：Needs fixes

Task1 主要要求已满足，但归属值的缺值边界还需修复一项：`ClaimCeiling.attributes` 接受两侧相等的空串/纯空白 `bizSystemCode`、`userId`，详见 Important I1。该函数尚未接入六服务鉴权，本结论没有推导现网越权；问题位于本任务交付的纯属性语义本身，须避免把无有效归属的材料交给后续调用方当作已通过归属相等检查。

| 要求 | 独立阅读结论与定位 |
| --- | --- |
| 指定六类型、JSON、两测试、仅修改模式装配 | 完整 patch 为 10 文件；既有变化仅 `SecurityConfiguration.java` 的导入与 40–55 行守卫；POM、Actor、ServiceTokens 未在差异中变化。 |
| 精确 caller/target/method/template，未知/重复拒绝 | `ServiceRoutePolicy.java:20–46` 校验目录及重复键；`:52–55` 四元组精确查找并抛 `PlatformException(403)`。无 URL 实例匹配、角色提升或前缀授予。 |
| 实际 handler/caller 库存与禁用项 | 逐字段读完 JSON 166 行。独立集合对比为 158 个库存模板与 JSON 非 springdoc 模板相等；另有 5 个条件 springdoc handler、3 条第二 caller；150 delegated、2 workload、14 disabled。gateway 两个 ANY 通配 dispatch 行均 disabled；8 个无实际调用者/未注册 callback 的 internal handler 均 disabled。 |
| 实际调用边裁决 | 定点核实 `Routes.java:7–37`、`IntegrationPolicy.java:67–82`、`EvaluationService.java:400–414`、`SkillGovernance.java:137–139`：gateway 只选择目标；11 个 integration 模板与允许路径一致；monitor 的动态 projections 为明确三项；skill→model chat 确有实际 caller。未给 engine 普通 chat、未用 statistics GET、reconcile/retry/proof 等补权。 |
| 不虚构未来入口 | material-scopes、iam integration-policy resolve 未进入现有允许目录；callback 没有虚构具名 client。现有未用 handler 禁用与尚未存在的 future handler 明确区分。 |
| 许可交集、标签并集、未知属性不映射 | `ClaimCeiling.java:9–12,20–26` 符合；返回 Map/Set 不可变，region 一侧缺失得到空集合，历史 dataLabels 未被交集删除。 |
| 主体/归属相等与缺项拒绝 | `SubjectKey.java:5–6` 非空白；`ClaimCeiling.java:36–38` 主体相等且缺侧拒绝；`:27–31` 拒绝缺侧、冲突、多值，但未拒绝空白归属单值，I1 待修复。 |
| 具体资源独立绑定 | `ResourceBinding.java:5–7` 三字段非空白；`ServiceRoutePolicy.java:55` 返回 null resource 的目录描述，与注释一致，不能当已授权资源。 |
| 强类型配置、缺/未知 mode 不降级 | `AuthorityProperties.java:25–32`、`SecurityConfiguration.java:43–48` 严格 Binder 与 NoUnboundElementsBindHandler；模式显式配置，未知字段不被忽略。 |
| dev/test 独立限制 | `AuthorityProperties.java:59–69`：dev-hmac 显式 dev-auth=true；test 显式 mode=test 且 active test profile；两者固定 loopback，无 Host header 推断。非gateway portal/integration/旧allow-embed在 `:27–30` 被拒。 |
| IAM 必需配置及阶段闭锁 | `AuthorityProperties.java:33–56` 校验 URI、client/service绑定、secret_ref、算法、audience、数值边界；`SecurityConfiguration.java:52` 字段完整仍拒绝 IAM，没有自报 ready 或 HS256 回退。 |
| 开 listener 之前拒绝 | `authorityModeGuard` 是 static factory processor；固定版本 `AbstractApplicationContext.java:609,621,627` 与 `ServletWebServerApplicationContext.java:164–193` 的执行顺序支持该放置点。`AuthorityConfigurationTest.java:92–98` 对真实 factory 调用计数为 0，不只检查异常文字。 |
| 行为红/绿与完整回归 | 已读原始红/绿/完整日志的对应错误与结果区，见下文；没有重复运行同码45或55项。 |

### ⚠️ 不能由本次差异验证的项

- 外部 IAM 签发、验签、current-authority 在线核实、可信 claims mapper、workload secret resolver、token/JWKS/决定响应预算、重定向控制、撤权与资源摘要校验不在本 Task1 实现中。不能从 URI/record/目录存在推导已具备这些能力。
- 静态 controller 盘点和集合比较不能证明实际 Spring 条件 handler 装配、依赖提供的全部 handler 或运行时歧义检查已经接入；它们属于 Task3/4。5 个 springdoc 行仍是条件框架入口。测试中的源码正则扫描有明确适用范围，不能升级成运行时 handler 注册器。
- 六服务源码、配置与当前固定962制品的兼容迁移、统一后端回归、真实端点及生产 IAM 签收需 root 后续组织。新的 common 要求显式 mode/service-id；本次没有将其嵌入或重启旧制品。
- 发布门禁保持 `BLOCKED_IAM_SCOPE_ACCEPTANCE` 是绑定约束；本次没有生产签收材料或任何允许将其改为 `IAM_SCOPE_ACCEPTED` 的结论。PG业务权威、模块2边界与全部原四契约完整性不以本 common 局部审查重新验收。
- 无 HEAD 的历史基线真实性依赖起步保存与交接过程。本次可独立确认交付字节彼此一致，不能用空 git diff 证明历史未改动，亦未这样作证。

## Code quality

具体优点：

- 保持单一旧文件的最窄模式装配变更，未新造通用 IAM 服务或 ready 开关；完整 IAM 拒绝发生于普通 bean 初始化和 WebServer 构造之前。
- 能力目录、资源描述、主体和属性语义职责分开；`require` 不读取 Actor/ADMIN，没有把角色升级混进 caller 能力。enabled 通配模板与重复/冲突目录默认拒绝。
- 行为测试观测实际 Spring 启动与 factory 调用，并以真实 MAC 签名验证旧显式 dev/test decoder。测试明确是旧装配回归，不冒充新 IAM 信任链通过。
- 原始失败、补丁失败尝试与完整 apply/冻结记录保留；补丁含完整新增代码及 JSON，便于无 HEAD 条件下检查。

### Critical：0

未发现本 Task1 范围内的 Critical 问题。

### Important：1

**I1 [P2] 归属相等检查接受空白归属单值。**

- 位置：`admin-common/src/main/java/cn/agentplatform/common/authority/ClaimCeiling.java:30–31`。
- 触发：两侧都为 `Map.of("userId", Set.of(""))` 时，返回 `{userId=[""]}`；`bizSystemCode` 同样如此，单个空格 `" "` 也被原样保留。检查只要求集合大小为1及相等，没有要求该单值是有效非空白归属。
- 证据：审查者仅增加 `iam-stage-1-review/OwnershipProbe.java`，使用既有 `target/classes` 的纯函数探针；四种组合全部原样接受，退出码0，原始输出在 `iam-stage-1-review/ownership-probe.log`。作者现有 `ownershipMustMatchAndCannotBeCompletedFromOneSide` 覆盖冲突/缺侧/多值，没有覆盖这四种空白值。
- 影响：`SubjectKey` 会拒绝空白身份，但 biz/user 的归属检查会将“没有有效归属标识”的两侧输出为成功结果。后续调用者若使用此基础函数验证历史材料与当前归属，将无法区分真实相等归属与双方都缺有效标识，削弱本任务缺项默认拒绝的边界。这是纯语义层的缺陷；本次无六服务授权接入，未证明当前请求可借此越权。
- 建议：在单值相等检查中明确拒绝 null/空串/纯空白元素，不通过 trim 或其他补值方式把缺项改成身份；对两个 key 的空串、空白分别补负例，并保留合法同值成功。修复范围限该函数及现有属性测试，无需扩展 Task2/业务源码。

### Minor：0

没有另外提交影响不明或仅个人风格偏好的问题。

### 非阻塞观察与界限

- `ServiceCall`、`ResourceBinding`、`SubjectKey` 是纯 record，本身不提供身份防伪；目录仍需后续本地 handler/action/resource 绑定与独立在线授权。此项是已声明阶段界限，不是对本 Task1 的缺陷指控。
- portal/integration 的完整可信链配置、secret resolver、生产网络控制仍待后续任务；IAM 当前始终闭锁，不能因存在基础配置字段而声称它们已接通。
- `AuthorityPolicyTest.java:120–160` 使用源码扫描维护当前模板库存，当前证据可用；后续替换为真实运行时枚举时仍需维持 fail-closed。没有将未来范围提前要求为本 Task1 成果。

**Quality：Needs fixes（I1，P2）。** 不需要重构目录架构或扩大任务；完成上述最小输入校验与针对性负例后，可对该项复审。

## 证据、执行与只读边界

本次独立执行：

```powershell
& 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe' --class-path admin-common/target/classes docs/evidence/iam-stage-1-review/OwnershipProbe.java
& './docs/evidence/iam-stage-1-review/verify-review-inputs.ps1'
```

第一个命令只回答现有运行未覆盖的空白归属疑点，不启动 Spring/HTTP、不连接 PG/IAM。第二个命令只读 manifest 所指向的已知文件并核对摘要；完整输出见 `iam-stage-1-review/verified-inputs.json`：

- 172 个作者制品 SHA 全部一致；当前 common 的 28 文件与 frozen、applied-bytes 均一致。
- target 的41个已登记输出 SHA一致，审查未改动源码/POM/target/原始证据。
- 完整 patch SHA256=`98135a64048ccdf00588fcfb1a74128b30f950f3dd8817642f30b331788f51f4`。
- v1.2快照 SHA256=`3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。
- 158个实际模板与目录对应，JSON166行，150 delegated / 2 workload / 14 disabled。此集合核对验证库存与目录一致性，不替代逐controller运行时枚举。

作者测试证据的独立阅读结果：`behavior-red.log` 为27项、25断言失败、0错误；失败是错误允许/错误启动，并非编译缺类。`legacy-embed-behavior-red.log` 为34项、1断言失败、0错误。`targeted-final-green.log` 是45项全绿；`full-common.log` 原尾与 `full-test-summary.json` 显示37 Configuration + 8 Policy + 4 CommonBoundary + 5 Schema + 1 SecretVault =55项、0失败/错误/跳过、BUILD SUCCESS。本审查未将这批作者运行重新标作审查者运行，也未重复该suite。实际 `git apply` 日志返回0，完整28文件与应用副本字节相等由本次SHA再次核对；失败的早期apply记录未覆盖。

差异外定点阅读仅为：根 `AGENTS.md`、实现交接/契约说明、原设计快照摘要、完整 `SecurityConfiguration` 旧链上下文、`PlatformException`、上列四个可命名caller/路径风险文件，以及交付的固定版本Spring相关执行顺序。未crawl整个业务工程；未读相邻工程、`.env`、vault、masterkey或真实凭据；未启停现有服务、package、提交或发布。

common 源码及 target 现已归还 root，仍保持交接时字节。仅新增本报告与 `docs/evidence/iam-stage-1-review/` 下的独立审查材料。

## I1/P2 最小修复复审（2026-10-20，原审查者追加）

**当前 Spec compliance：Approved（仅 G6 Task1 范围）。当前 Quality：Approved。I1/P2 已闭合。** 原文 Needs fixes 及原探针保留为修复前历史；本节是两文件修复后的复审，不改写原55项或原始失败记录。

本轮完整阅读 `iam-stage-1-ownership-fix.md`、两文件 `full.patch`、实际 apply 记录、红/绿/完整日志的相关结果与失败上下文，并定点核对当前实现。`ClaimCeiling.java:31–32` 在集合恰为单值且两侧精确相等之后，明确拒绝 null 或 `isBlank()`；仍沿用 `PlatformException(403,"SYS-AUTH-403",...)`，在不可变集合复制前拒绝 null，解决原NPE类型问题。不 trim、不补身份、不放宽缺侧/冲突/多值语义。

`AuthorityPolicyTest.java:89–104` 的两个key × 空串/单空格/null共六例真正调用函数并断言异常类型、403及错误码；`Collections.singleton(null)` 让null进入被测边界，未在构造输入时误失败。合法同值、含边缘空格的原样同值成功，以及 `" a "` 与 `"a"` 不相等的断言保留了精确比较语义。该改动直接闭合I1，未引入新抽象或扩大业务范围。本轮 Critical/Important/Minor 新问题均为0。

原始运行证据：旧实现 Policy14项产生6断言失败、0错误/跳过，其中四项未抛异常，两项为NPE而非要求的PlatformException；修复后 Policy14/14通过；完整 common 原日志尾为 Configuration37 + Policy14 + CommonBoundary4 + Schema5 + SecretVault1 =61项，0失败/错误/跳过、BUILD SUCCESS。六例红与绿均为作者运行；本复审未重复14/61 suite，也未运行原接受旧行为的探针来混淆修复后的预期。现有运行及两行实现已经回答该疑点，无需额外启动上下文。

独立只读摘要核对记录在 `iam-stage-1-ownership-review/`：111个新manifest制品一致，当前28文件与frozen/applied-bytes一致，28个修复baseline文件与原Task1的source-after一致；与原Task1相比恰好只改 `ClaimCeiling.java` 和 `AuthorityPolicyTest.java`。41个target输出摘要一致。补丁SHA256=`e2306f59498f92b1c009c7aa6f3b0314bb8d7f216822ca0c38e5d4ebdd9ee250`，作者实际apply为0，allBytesEqual=true，并以独立文件摘要复核全部28文件。

追加前，本审查者再次核验178个旧证据文件与作者冻结时记录一致；其中本报告追加前为11232字节、SHA256=`5816c9bba2712741605097806af89675c79ba2200b78946eb3fa382b11b9bb75`。本节经root明确授权追加，整份报告SHA随之变化，不声称追加后仍等于旧SHA。`verified-after-append.json` 单独核验原11232字节前缀不变，其他177个旧证据与本轮source/target仍一致；原Needs fixes、旧红/55及独立探针原字节均保留。

⚠️ 原报告的不可验证项、非阻塞阶段界限继续有效：本轮仅修复纯属性语义，未接通外部IAM/current-authority、六服务handler守卫或资源授权，也未执行Task2。配置/路由JSON未变，mode=iam仍在WebServer构造前闭锁；**BLOCKED_IAM_SCOPE_ACCEPTANCE** 保持。本Approved不等于生产IAM签收或全工程验收。

本复审仅追加本节并新增 `iam-stage-1-ownership-review/` 独立证据。没有改动common源码、POM、target或作者证据，没有真实服务启停/package/提交/发布；common target现已归还root。
