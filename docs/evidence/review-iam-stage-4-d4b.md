### Spec Compliance

- ❌ Issues found：发现 2 项 Important，当前附件 HTTP 401 未统一清身份（`web/shared/artifact.ts:31`、`web/shared/Artifacts.vue:17`）；SSE reader 清理失败可覆盖授权 reset，导致自动读状态（`web/debug-panel/events.ts:121`、`web/shared/run-observer.ts:70`）。分别违反 brief `docs/evidence/iam-stage-4-d4b-portal-frontend-brief.md:11`、`:32` 与 `:46`。
- ✅ 已核实现的主路径：默认 portal / 显式 dev、宿主方法 this、精确四字段及空 roles、候选先 session 后安装，对应 `web/shared/portal-auth.ts:6`、`:14`、`:18`、`:46`；main 挂载不等待 provider，对应 `web/admin/src/main.ts:10`、`web/admin/src/PortalShell.vue:9`。
- ✅ generation 递增和 AbortController、身份统一清除、分段到期检查及工作区 key 卸载，对应 `web/shared/api.ts:20`、`:24`、`:33`、`web/admin/src/PortalShell.vue:13`。App 导航、共享身份错误、整批 allSettled、busy finally、动态 chart 副作用已有归属保护，对应 `web/admin/src/App.vue:200`、`:208`、`:226`、`:276`、`:343`；组件 useApi 捕获会话与卸载 signal，对应 `web/shared/api.ts:113`。
- ✅ 授权失败与普通 503 分流、两个真实消费者手动恢复、通常情况下 reader 逐帧中止已实现，对应 `web/debug-panel/events.ts:57`、`:63`、`:101`、`:117`，`web/admin/src/RunDetail.vue:47`，`web/debug-panel/DebugPanel.vue:129`；I2 是清理异常组合下的缺口，不能据此声称所有停止路径均闭环。
- ✅ 差异之外的有限证据已由 root 独立审计 PASS（`.local/iam-d4b-root-check.json:2`）：192 当前前端、188 base、16 frozen/replay、15 dist、SDK 副本、908 own 文件、27 实际命令与阶段源码绑定、32 个最后适用浏览器名称（含 1 个仅截图）、12 个实际 Bearer 请求 Cookie omit 与 ambient 正对照、8 索引/9 锚点及原设计/axios 保护；本 reviewer 已读取该核验文件。这里依据 root 审计，不以作者报告 `docs/evidence/iam-stage-4-d4b-portal-frontend-report.md:60`、`:64`、`:145` 的陈述替代证明。
- ⚠️ 无法从本任务确认且不在本次签收范围：前端受控 HTTP 不证明 D4a 真实签名后端联合链路或生产 IAM；实际 dev-env 新进程启动未执行。后端 380 source/1933 target 和旧日志沿既验封存谱系继承，本轮没有再次物理全扫（`.local/iam-d4b-root-check.json:22`、`docs/evidence/iam-stage-4-d4b-portal-frontend-report.md:184`）。

### Strengths

- ✅ 会话边界位于实际请求与消费者两端：`web/shared/api.ts:72`、`:88`、`:94` 在发送/响应/解析后核 scope；`web/admin/src/PortalShell.vue:13` 用 generation key 清理工作区。`web/tests/portal-auth.test.ts:45`、`:53` 对同 token、旧 body、旧 API 工厂、迟到 provider/session 的断言直接检查真实 auth 状态，而非只检查调用次数。
- ✅ 保留小范围实现：`web/shared/portal-auth.ts:28` 集中连接状态，`web/admin/src/PortalShell.vue:1` 负责认证入口，未扩大 App 拆分。`web/shared/artifact.ts:19` 的可选第六参保持旧调用方式，`web/shared/Artifacts.vue:9` 只在组件中接入会话；迟到 blob 的下载副作用保护可对应 `web/tests/portal-auth.test.ts:11` 与 `web/tests/portal-browser.mjs:124`。
- ✅ Cookie 配置走实际 transport：`web/shared/api.ts:158` 使用现有 axios fetch adapter 与 `withCredentials:false`，`web/debug-panel/events.ts:87`、`web/shared/artifact.ts:28` 明确 omit。`web/tests/portal-browser.mjs:102` 使用受控原始 HTTP、同源 Cookie 与实际下载；root 已核实际 Cookie omit 请求及正对照（`.local/iam-d4b-root-check.json:2`）。
- ✅ 测试覆盖实际 App 和两个独立观察消费者：`web/tests/portal-browser.mjs:109`、`:116`、`:119` 触达 allSettled、复制 POST 导航、providerOptions/发布输入；`:135`、`:143`、`:146` 覆盖授权 reset、普通 503 恢复、业务帧后 status 拒绝。`web/tests/run-observer.test.ts:18` 起保留事实游标和最终快照的原有断言。

### Issues

#### Critical (Must Fix)

- 无。

#### Important (Should Fix)

- **I1 — 当前附件 401 未使会话失效。** `web/shared/artifact.ts:31` 把全部非成功状态转成无 status/code 的普通 Error；实际调用者 `web/shared/Artifacts.vue:17` 只写错误文案。用户在 RunDetail 下载附件收到 401 时，token/user/roles、generation 和旧观察仍然保留，不会显示重新连接入口。此路径绕开 `useApi` 的统一 invalidate，与 brief `docs/evidence/iam-stage-4-d4b-portal-frontend-brief.md:11`、`:32` 冲突。修复应让纯 helper 保留 HTTP 状态，由组件在捕获 scope 仍有效且 status 为 401 时调用 `auth.invalidate(session.generation)`；保留原五参 SDK 兼容，不向 helper 注入全局 auth。增加当前 401 清全部身份，以及旧下载 401 不清新 generation 的定向用例。现有 `web/tests/portal-auth.test.ts:11` 只验证迟到 blob，并不能覆盖该错误路径。
- **I2 — reader 清理异常覆盖授权停止原因。** `web/debug-panel/events.ts:117` 抛出 AUTHORITY_* 后，`:121` 的 `await reader.cancel()` 若拒绝，finally 会用清理错误覆盖原授权错误。`web/shared/run-observer.ts:70` 将没有 status/reason 的 TypeError 视为普通断流，继续到 `:77` 的 readStatus；RunDetail/DebugPanel 同样无法将此错误识别为 blocked，可能安排重连。定向内存验证使用真实 ReadableStream、一个 AUTHORITY_DENIED 帧和拒绝的底层 cancel：streamEvents 最终抛 `TypeError: cleanup failed`，随后 observeRun 实际得到 `opens=1, reads=1, result=SUCCEEDED`，证明授权 reset 后仍读了状态。需确保 reader 始终 releaseLock，同时清理失败不覆盖已确定的授权/中止原因；为 cleanup 拒绝组合添加定向断言，验证零 status/reopen/业务帧。现有 `web/tests/stream-auth.test.ts:136` 的 cancel 始终成功，因此其绿不能排除此问题。

#### Minor (Nice to Have)

- **M1 — 构建输出仍有大 chunk 警告。** root 已独立确认实际 warning 位于 `docs/evidence/iam-stage-4-d4b-portal-frontend/final-build-admin/output.raw:18`、`docs/evidence/iam-stage-4-d4b-portal-frontend/final-build-webapp/output.raw:11` 与 `docs/evidence/iam-stage-4-d4b-portal-frontend/final-build-admin-copy/output.raw:18`，与作者报告 `docs/evidence/iam-stage-4-d4b-portal-frontend-report.md:64` 一致；按任务审查模板记录为 Minor，未证明本次引入性能回归。建议在后续性能任务中量测首屏成本再决定拆包；本次不应为消除警告扩大重构。历史 setup/红阶段/pageerror 已在作者报告 `:60`、`:145` 分阶段说明，不能拼为一次全绿，也不能把已解决失败再次判作未解决阻断。

### Assessment

**Task quality:** Needs fixes

**Reasoning:** 主要会话隔离设计和实际消费者覆盖合理，但当前附件 401 和 SSE 清理失败两条错误路径不满足明确的失效/停止要求；修复并补相应定向用例后才能关闭本任务代码门禁。原始证据与保护范围的 root 有限核验 PASS 不改变这两个实现缺陷。

- ✅ 审查范围检查：先读 brief，再读完整作者报告；报告 SHA256 `e9234d439b33e684f07d126489eb901a7a35bf1df468bebdb1b64399f30ed6f2`，差异包 SHA256 `01823d37e36835a81efd37ab0fbb74743641bc5c9d9ebe4e8975a0f9f4c20557`。完整差异按不相交批次读至 EOF；工具在 browser fixture 中截断约 426 token，仅补包第 2786–2800 行（对应 `web/tests/portal-browser.mjs:43` 附近），未另读改动文件，未运行 git。
- ✅ root 独立核验记录已读取：`.local/iam-d4b-root-check.json:2` 为 PASS，文件 5,281 bytes、SHA256 `ea7d15c0609d8a9e1e49bb701980913f0cb9e5cbb4e8e5651e941fa59c19cbc9`（大小/SHA由 root 回传）；1136 个不同物理文件核验包含复用失败前真实缓存的 1117 个，未再跑作者测试或浏览器。初次审计 helper 错误假设 browser-1 应有 raw/end 的失败保留；续查按该阶段实际强制退出、缺 raw/end 的事实处理，没有补造记录。
- ✅ 具名外查：为验证 shared API 生命周期与 SDK ticket/download 调用兼容，限定检索 `web/webapp/src` 与 `web/assistant.js/src`；读取 `web/webapp/src/App.vue:12`、`:28`（登录后才挂载 Chat）和 `web/assistant.js/src/main.ts:324`、`:326`、`:367`（ticket 后调用流/纯下载 helper），未扩大到其他业务实现。一次误指不存在的 `src/index.ts` 返回文件不存在，随后使用真实 `src/main.ts`，不产生行为结论。
- ✅ 定向验证检查：仅从封存差异在内存提取 events/run-observer/artifact 并以本机已有 TypeScript 转译执行，cwd 为项目 root，node exit 0；没有写产品/测试文件，没有重跑任何作者套件或浏览器。I2 实际输出 `{"caught":{"name":"TypeError","message":"cleanup failed"},"opens":1,"reads":1,"result":"SUCCEEDED"}`；I1 helper 实际输出 `{"name":"Error","message":"下载附件失败 (401)"}`，status/code 均缺失，结合 `web/shared/Artifacts.vue:17` 的实际 catch 确认组件不失效。
