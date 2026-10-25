### Spec Compliance

- ❌ 原 D4b 冻结实现另有 2 项 Important：退出未取消 provider 订阅，以及系统时钟回退会延长前端会话保留。本补充仅记录 root 明确请求的这两个具名检查，不扩展审查范围，不评判随后 I1/I2 修复波次。
- ✅ 检查依据：原任务要求 `docs/evidence/iam-stage-4-d4b-portal-frontend-brief.md:32`、`:42`，原计划 §2.7 `docs/evidence/iam-service-authority-implementation-plan.md:280`，以及原封存差异包 `docs/evidence/review-iam-stage-4-d4b-package.diff`（SHA256 `01823d37e36835a81efd37ab0fbb74743641bc5c9d9ebe4e8975a0f9f4c20557`）。以下产品及测试行号均指该原冻结版本，未读取修复中的半成品源码。

### Strengths

- ✅ 原实现的统一失效已清 token/user/roles、推进 generation、abort 旧工作并清 timer（`web/shared/api.ts:24`）；补充问题集中在 provider 订阅及截止时间计算，不否定已实现的代际隔离。
- ✅ 到期 timer 已分段并核 generation（`web/shared/api.ts:39`、`:42`），原测试覆盖超长期限和向前跳时（`web/tests/portal-auth.test.ts:67`、`:70`）；这能避免 timeout 溢出，却不足以证明回退时仍按原寿命清除。

### Issues

#### Critical (Must Fix)

- 无。以下问题不证明后端授权可被绕过。

#### Important (Should Fix)

- **I3 — 退出没有释放 provider 订阅，后续宿主事件能重新登录。** 原计划 `docs/evidence/iam-service-authority-implementation-plan.md:280` 明确要求“卸载/退出时取消timer和provider订阅并清token/roles/user”，brief `docs/evidence/iam-stage-4-d4b-portal-frontend-brief.md:42` 同样明确退出/卸载释放订阅。实际退出按钮 `web/admin/src/App.vue:669` 只调用 `auth.invalidate(...)`；认证壳 `web/admin/src/PortalShell.vue:13` 卸载 App，但壳自身继续存在，`:10` 注册的 dispose 不执行。宿主订阅 `web/shared/portal-auth.ts:55` 仍直接调用 connect，unsubscribe 仅在 `:59` 的 dispose 中执行。退出后宿主再次发出 sessionChanged 即可取票、验 session 并自动恢复工作区，违背退出后的手动恢复语义。最小修复应把退出纳入订阅释放/暂停生命周期，手动重连时再恢复所需订阅，并阻断已排队旧回调；不需要扩大认证框架。
- **I3 解释边界 — 退出 unsubscribe 是硬要求，401/到期的 manual gate 应单独说明。** 计划 `docs/evidence/iam-service-authority-implementation-plan.md:280` 对“退出”明确要求取消订阅，但没有用同一句硬性规定要求每次 401/到期都 unsubscribe。brief `docs/evidence/iam-stage-4-d4b-portal-frontend-brief.md:32` 又要求退出/401/到期“等待用户重新连接”。一致的最小解释是：正常连接或正在连接时允许宿主变更启动新连接；进入手动恢复状态后暂停宿主自动恢复，直到用户显式重连。401/到期可通过状态门控实现，不应把三种情况一概描述成明确的 unsubscribe 硬要求。原测试 `web/tests/portal-auth.test.ts:63` 只断言到期当下未自动取票，`:64` 随即断言宿主 changed 后自动恢复；因此它没有证明手动恢复约束，反而固化了需要澄清/修正的行为。该解释作为 I3 的修复边界，不重复计一个新问题。
- **I4 — 系统时钟回退会延长前端身份与旧数据保留。** `web/shared/api.ts:40` 每次仅计算 `deadline-Date.now()`，`:42` 再按最多 60 秒安排下一次检查。分段限制检查间隔，不限制累计会话寿命：安装时剩余 60 秒，随后系统时钟回退一小时，原 timer 触发后 remaining 又约为一小时，会继续保留 token/user/roles、工作区及已有业务数据。此行为违反 brief `docs/evidence/iam-stage-4-d4b-portal-frontend-brief.md:42` 的“避免……时钟变化后错误保持会话”，也不足以支持作者报告 `docs/evidence/iam-stage-4-d4b-portal-frontend-report.md:11` 所述避免时钟回退问题。最小修复可在安装时固定剩余寿命的单调时钟上限，与 wall-clock deadline 取较早到期；或检测回退后保守失效。原测试 `web/tests/portal-auth.test.ts:70` 仅将时钟向前设置到 deadline 之后，没有覆盖回退。
- **I4 影响边界。** 原计划 `docs/evidence/iam-service-authority-implementation-plan.md:278` 明确 UI 会话不缓存后端许可，每次动作仍由后端授权。因此这里是明确的前端到期清理功能缺口：用户未发新请求时，页面仍可能显示已过期身份和旧数据；不是通过回退本机时钟获得服务端权限，严重性为 Important，不是 Critical。

#### Minor (Nice to Have)

- 本补充无新增 Minor；原报告中的 M1 不在本次两个具名检查范围。

### Assessment

**Task quality:** Needs fixes

**Reasoning:** 退出订阅释放和时钟回退防延期均属于已有明确要求，不是额外功能。I3/I4 应在新的独立修复波次处理；已完成封存的 I1/I2 修复证据保持独立，最终复审再核对全部未闭合项。

- ✅ 检查方法：仅按 root 指定的两个风险，窄读原计划 §2.7 与原封存差异中的 PortalShell、portal-auth、api 和对应测试行；没有重跑测试、浏览器或套件，没有检查修复半成品，也没有再次全局搜错。本补充的结论来自明确代码路径与时间计算，未冒称运行了新回归用例。
- ✅ 原审查封存：`docs/evidence/review-iam-stage-4-d4b.md` 保持 9,217 bytes，SHA256 `d91c154df4fe00cef27348bb7e4dc8a1ca04fc4fded7a16c8cc38d2355807d90`；本轮唯一新增可写交付物为本补充报告，不改原 review、source、dist、I1/I2 seal 或其他证据。
