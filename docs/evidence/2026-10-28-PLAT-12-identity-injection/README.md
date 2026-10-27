# PLAT-12 / T12.1（技能侧半段）：技能契约 identity 声明 + 适配器出站 X-User-* 注入 — 证据

## 1. 范围与依据

- 派发单元 S-3（T12.1 技能侧半段），方案 §4.12（819–868 行，实现项 2 与验收），台账 §3 T12.1 行、§5 X-5（建表已定）/D-11，计划 §6 规范。
- 基线：分支 `feat/v1.2-implementation`，开工时 HEAD `ca46e55`；skill-registry 基线 `bcd9184`（T06，已收口）。前序事实沿用 `c94f2f1`（T12a）：`Actor.subject()` 不可变快照 + `SubjectSnapshot.mappingKey()`，本卡只消费快照，不动 admin-common/app-management 的 T12a 代码。
- 交付内容：
  1. **契约 `identity` 声明**：`{"mode":"PLATFORM_SUBJECT","headers":{"X-User-Id":"principalId",...}}`；mode 与 headers 映射可声明；未声明 identity 的技能出站行为与信封形状零变化（既有用例原样过 + 专用锚点用例）。
  2. **保存期/注册期校验**（`SkillRules.identity` 唯一解释入口，`SkillCatalog.normalize` 落库）：mode 只允许 `PLATFORM_SUBJECT`（400 `SKILL_IDENTITY_MODE_INVALID` 可读错误带收到的值）；headers 值只允许快照字段白名单 `principalId/tenantId/roles/attributes`（400 `SKILL_IDENTITY_HEADER_INVALID`，防任意 header 注入）；header 名为 HTTP token 子集且拒绝保留头（Authorization/X-API-Key/Idempotency-Key/Accept/Content-Type/Host/Cookie/Set-Cookie）；仅 HTTP 适配器可声明（MCP/ALGORITHM/PROCESS 无出站头通道，400 `SKILL_IDENTITY_UNSUPPORTED`）；声明形态错误 400 `SKILL_IDENTITY_INVALID`。`save()` 对 identity 增加与 authScheme 相同的"旧编辑器省略不等于删除"补回。
  3. **适配器统一注入**：同步发头点（`SkillAdapters.http`）与异步发头点（`HttpAsyncAdapter.request`，提交与查询共用）追加 `SkillAdapters.identityHeaders(contract)`——与 T11 `AuthScheme.headers` 是**叠加关系**（鉴权头照发、身份头追加，`AuthScheme` 语义零改动）。
  4. **序列化定死（写入契约 §13.2）**：roles 字典序排序逗号连接（`EDITOR,USER_ADMIN`）；attributes 为键字典序、值排序字符串数组的紧凑 JSON（`{"dept":["103"],"region":["LOCAL"]}`）；空集合/空白值**不发头**（HTTP 头没有空集合表达，空串会让对端 split 出幽灵角色）。
  5. **无 Actor 上下文语义定死**：无验签上下文（401）或机器 workload 票据（403，机器能力不能映射为用户身份）→ 整体跳过注入、不伪造值，出站请求照常发出；跳过结论随执行事实信封 `result.identity` 落运行详情（`{mode, headers:[{name,from,sent}], injected, skipReason=NO_ACTOR_SUBJECT|SUBJECT_VALUES_EMPTY}`）；**trace 只带头名/来源字段/布尔，不带头值**；未声明 identity 的技能信封无 `identity` 键。
  6. **与映射表的关系（只写文档，不越界实现）**：出站值唯一权威是进程内 `Actor.current().subject()` 快照；V107 `app.identity_mapping` 经 `SubjectSnapshot.mappingKey()` 与快照对齐，是持久登记面；跨服务以表为源的反查属后续卡。
  7. **契约增补**：`contracts/skill-invocation.md` 追加 §13（工程增补，非 v1.2 原文，snapshot 不动），`contracts/freeze.json` 更新 sha256 并在 engineeringAddendum 登记。
- **禁止项遵守**：桥接层无自定义身份；`AuthScheme` 未改；admin-common/app-management/engine-runtime/monitor/web/contracts/snapshot/v1.2 原文未动；无迁移（identity 存契约 JSON，无 DDL）。

## 2. 环境

- JDK21：`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`
- 密钥库：测试内临时目录（`agent.secret.dir` 系统属性，与 AuthSchemeOutboundTest 同法）
- Maven：`mvn -B -pl skill-registry test -Dtest='<类列表>' "-DargLine=-Xmx1024m"`；回归批追加 `-Dsurefire.runOrder=reversealphabetical`（T06 证据记载的纪律：让 SkillClientsErrorTest 先注册进程级 deploymentMode，规避计划 §7.5 已知的 `AsyncHttpExecutionTest` 8 项隔离假红）
- 契约门禁：`node scripts/verify-contracts.mjs`

## 3. 命令原文

```bash
# 前置门禁（见 §5：G-9 阻断为基线既有，与本卡无关）
node scripts/verify-contracts.mjs

# 红阶段（两个新测试类；引用的 SkillRules.identity* 尚不存在 → 编译期红，T12a 先例）
source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -pl skill-registry test -Dtest='SkillIdentityRegistrationTest,IdentityOutboundTest' "-DargLine=-Xmx1024m"

# 绿阶段（新类）
mvn -B -pl skill-registry test -Dtest='SkillIdentityRegistrationTest,IdentityOutboundTest' "-DargLine=-Xmx1024m"

# 回归批（新类 + T05 五类 + T06 PathParamsAsyncMappingTest + T11 AuthScheme 两类 + AsyncHttp/SkillRules/
# SkillExecution/SchemaDegraded/SkillClientsError/ExternalJobState/HttpAsyncAdapter + 三个既有耦合类）
mvn -B -pl skill-registry test -Dsurefire.runOrder=reversealphabetical \
  -Dtest='SkillIdentityRegistrationTest,IdentityOutboundTest,PathParamsAsyncMappingTest,TaskResultControllerTest,AsyncResultEndpointFailureTest,SkillErrorEnvelopeTest,SkillTaskResultTest,ErrorSemanticsTest,AsyncHttpExecutionTest,SkillAuthSchemeRegistrationTest,AuthSchemeOutboundTest,ExternalJobStateTest,HttpAsyncAdapterTest,SkillRulesTest,SkillExecutionTest,SchemaDegradedValidationTest,SkillClientsErrorTest,CallbackHttpTest,ReconciliationProofTest,SkillCallbackWakeTest' \
  "-DargLine=-Xmx1024m"

# 基线对照：同命令、同 runOrder、同类列表（两个新类移出、skill-registry/src/main 的本卡改动
# git stash push 后运行，跑完 stash pop 恢复），输出 baseline-final-batch-same-order.txt；
# 方法级失败集合 diff：failures-baseline-final-batch-same-order.txt vs failures-green-final-batch.txt
```

## 4. 结果原文（红→绿）

**红阶段**（`red-run1.txt`；编译期红，T12a 先例——新测试类引用尚未实现的 `SkillRules.identity/identityTrace/CODE_IDENTITY_*`）：

```
[ERROR] COMPILATION ERROR :
[ERROR] SkillIdentityRegistrationTest.java:[138,32] 找不到符号
[ERROR]   符号:   变量 CODE_IDENTITY_HEADER_INVALID
[ERROR]   位置: 类 cn.agentplatform.skill.domain.SkillRules
[ERROR] IdentityOutboundTest.java:[216,31] 找不到符号
[ERROR]   符号:   方法 identityTrace(java.util.Map<java.lang.Object>,<nulltype>)
[ERROR]   位置: 类 cn.agentplatform.skill.domain.SkillRules
（共 11 处符号缺失，覆盖 5 组红例的全部断言目标）
```

**绿阶段（新类）**（`green-newclasses.txt`）：

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.application.SkillIdentityRegistrationTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.infra.IdentityOutboundTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

17 例对应方案 §4.12 验收：①声明 identity 的技能出站带正确 `X-User-*`（值来自已验快照：`X-User-Id=operator-a`、`X-User-Roles=EDITOR,USER_ADMIN` 字典序逗号连接、`X-User-Attributes={"dept":["103"],"region":["LOCAL"]}` 键排序 JSON 串）；②未声明 identity 出站零变化（既有 AuthSchemeOutboundTest 5/5 原样过 + 专用锚点：声明了 Actor 也不发任何 X-User-*）；③非法 mode（`LOCAL_SUBJECT`/缺失）→ 400 `SKILL_IDENTITY_MODE_INVALID` 带收到的值、白名单外 header 值（`email`）/保留头（Authorization、X-API-Key、Idempotency-Key）/非法头名 → 400 `SKILL_IDENTITY_HEADER_INVALID`、非 HTTP 适配器 → 400 `SKILL_IDENTITY_UNSUPPORTED`；④无 Actor（清空 SecurityContext）→ 请求照发、身份头整体缺省不伪造，workload 机器票据同理，trace 记录 `injected=false` + `skipReason=NO_ACTOR_SUBJECT`；⑤`authScheme=API_KEY` 组合：`X-API-Key` 与 `X-User-*` 并存、不多发 Authorization。异步发头点（`HttpAsyncAdapter.request`）同组身份头由独立用例锁定。

**回归批**（`green-final-batch.txt` 含本卡；`baseline-final-batch-same-order.txt` 为同口径基线）：

```
with-changes: Tests run: 215, Failures: 1, Errors: 41, Skipped: 0   （20 类，含新增 17 例）
baseline:     Tests run: 198, Failures: 1, Errors: 41, Skipped: 0   （18 类；差值 17 = 新增用例）
失败方法集合：两轮逐条 diff 完全一致（42 个，全在 CallbackHttpTest(32)/ReconciliationProofTest(6+1)/
SkillCallbackWakeTest(3) 三个既有 Spring 上下文静态耦合类），新增 0、消失 0
本卡触达面关键类全绿：
  SkillIdentityRegistrationTest 9/9、IdentityOutboundTest 8/8、AsyncHttpExecutionTest 29/29、
  AuthSchemeOutboundTest 5/5、SkillAuthSchemeRegistrationTest 7/7、PathParamsAsyncMappingTest 9/9、
  SkillExecutionTest 25/25、SkillRulesTest 6/6、SkillErrorEnvelopeTest 3/3、SkillTaskResultTest 3/3、
  TaskResultControllerTest 4/4、AsyncResultEndpointFailureTest 1/1、ErrorSemanticsTest 6/6、
  ExternalJobStateTest 8/8、HttpAsyncAdapterTest 2/2、SchemaDegradedValidationTest 14/14、SkillClientsErrorTest 7/7
```

**契约门禁**（`verify-contracts.txt` / `verify-contracts-baseline.txt`）：G-9 之前的全部检查通过（v1.2 快照哈希、四契约原文哈希、七模块依赖边界、graph schema）；**阻断在 G-9 路由目录**：`app GET /health/data`（`DataHealthController.java:24`）缺目录行——stash 全部本卡改动后基线输出逐字相同（`verify-contracts-baseline.txt`），系 T20.1（`ca46e55`，另一泳道）入库时未补 `agent-service-authority.json` 目录行所致，该文件在 app-management（本卡白名单外），**本卡不修、只上报**，由 T20.1 泳道或编排者补行后门禁即恢复。

## 5. 未覆盖与已知失败

- **42 个既有失败**：`CallbackHttpTest`(32 error)、`ReconciliationProofTest`(6 error + 1 failure)、`SkillCallbackWakeTest`(3 error)，基线同口径复跑（stash 后）失败集合逐条 diff 一致，根因为三个类各自的 Spring 上下文/进程级静态态在同一 JVM 内互相干扰（计划 §7.5"运行方式导致的红"同类，T05/T06 证据均有记载），与本卡无关、未修改。
- **G-9 前置门禁阻断（他人改动，已上报）**：`/health/data` 缺路由目录行，基线既有（见 §4），修复文件在白名单外。
- 未覆盖：`save()` 的 identity 补回路径无数据库级用例（需真实 PG；与 authScheme 补回同属 save() 事务内逻辑，normalize 层校验已由本卡 9 例覆盖）；`SkillExecution.envelope` 的 `identity` 键无端到端断言（需要 DB 夹具；trace 纯函数的 injected/skipReason/无值扩散语义已由 IdentityOutboundTest 直接锁定，信封接线为单行条件 put，未声明时返回 null 不落键由 SkillErrorEnvelopeTest/SkillExecutionTest 全绿反证）；网关侧"伪造 X-User-* 被拒绝"属方案 §4.12 验收的平台入口面，不在 skill-registry 白名单内。
- 已知边界（语义取舍，非缺陷）：对账/轮询路径的身份头取当前已验调用者快照——`HttpAsyncAdapter.binding` 本就以 principal_id 约束绑定行归属，跨主体对账在既有代码中已被拒绝，身份头不改变这一边界；identity 变化改变语义摘要（不在展示字段排除集），发布门禁照常要求重测，未新增开关（§6.2 开关要求针对行为分叉，本卡为契约新键 + 默认关闭的注入，未声明即完全旁路，符合 §6.5 前后兼容）。

## 6. 回滚方式

- 代码回滚：revert 本次提交即可——全部改动集中在 `skill-registry`（`SkillRules`/`SkillCatalog`/`SkillAdapters`/`HttpAsyncAdapter`/`SkillExecution` + 两个新测试类）与 `contracts/skill-invocation.md`/`contracts/freeze.json`，无迁移、无表结构、无配置项。未声明 identity 的契约在改动前后出站行为逐字节一致（锚点用例 + AuthSchemeOutboundTest/AsyncHttpExecutionTest 双重锁定），revert 后立即恢复原语义。
- 契约回滚：`contracts/freeze.json` 的 `skill-invocation.sha256` 还原为 `c1b1ba7cfa86e137fdcdbd8079915d801ac017cc044f1b1e1fd295ff9ab257f6`、engineeringAddendum 还原为 §11+§12 两段；`contracts/snapshot/` 全程未动。
- 存量数据：identity 只存在于其后保存的契约 JSON 内。回滚后已声明 identity 的技能回到"声明被忽略"状态——出站不再带 X-User-*（声明仍在契约 JSON 里，不损坏数据），鉴权头由 AuthScheme 继续照发，业务请求不会因回滚失去鉴权。

## 附：实现时定死的决策（台账 D 类记录口径）

| 决策 | 定案 | 理由 |
| --- | --- | --- |
| roles 序列化 | 字典序排序 + 逗号连接 | `Set.copyOf` 迭代序不进协议；同一主体序列化结果恒定 |
| attributes 序列化 | 键字典序、值排序字符串数组的紧凑 JSON | 与快照 `Map<String,Set<String>>` 形状一致且确定性 |
| 空集合/空白值 | 该头不发送 | HTTP 头没有空集合表达；空串会让对端 split 出幽灵角色；跳过比伪造空值诚实 |
| 无 Actor 上下文 | 整体跳过注入、不伪造值；trace 记 `NO_ACTOR_SUBJECT` | 身份头是补充可读身份而非鉴权依据；机器 workload 票据（403）不得投影为用户身份 |
| trace 是否带值 | 不带，只带头名/来源字段/布尔 | 身份明细不向运行详情扩散 |
| 非 HTTP 适配器 | 注册期 400 `SKILL_IDENTITY_UNSUPPORTED` | 与 AuthScheme 对 API_KEY 的适配器限制同一裁决：不接受实现不兑现的声明 |
| 与 AuthScheme 关系 | 叠加不替换；保留头注册期拒绝 | 鉴权语义零改动；防同名双值头 |
| 错误码 | SKILL_IDENTITY_INVALID / _MODE_INVALID / _HEADER_INVALID / _UNSUPPORTED（400） | 与 AuthScheme 家族同风格；注册期可读拒绝，畸形声明不可能流进运行时 |
