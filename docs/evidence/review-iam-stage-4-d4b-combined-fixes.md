### Spec Compliance

- ✅ Spec compliant：本次聚焦复审关闭 I1–I4；未发现新增 Critical/Important。范围为原冻结 D4b 到两波修复后的 9 个修改文件，0 新增/删除；两份修复 brief 与原 D4b brief 共同绑定本次判断。
- ✅ **I1 已关闭。** `web/shared/artifact.ts:31` 保留 HTTP status，不读错误体、不引入全局 auth；`web/shared/Artifacts.vue:17` 先核 scope，`:18` 仅当前 401 调用捕获 generation 的统一 invalidate，`:19` 单独解释 403。对应纯 helper/旧 scope/原五参断言见 `web/tests/portal-auth.test.ts:60`、`:66`、`:72`，实际组件当前/迟到 401 与 403 见 `web/tests/portal-browser.mjs:102`、`:109`、`:115`；生命周期波次又以真实附件 401 核对宿主事件不恢复身份（`:96`）。
- ✅ **I2 已关闭。** `web/debug-panel/events.ts:125`、`:126` 分别尝试 cancel 与 releaseLock，次要清理失败不再覆盖既有授权/中止/解析/网络主要结果。`web/tests/stream-auth.test.ts:7`、`:14`、`:20`、`:24` 使用真实 ReadableStream 检查三种 reset、AbortError、解析失败、EOF/网络错误及 unlocked；`web/tests/run-observer.test.ts:6` 将真实 streamEvents 接入未改的 observeRun，断言 AUTHORITY_DENIED、opens=1、reads=0、facts=0、cancels=1、locked=false，直接覆盖原审查复现路径。
- ✅ **I3 已关闭。** `web/shared/portal-auth.ts:37` 同步观察统一失效状态，退出/401/到期先暂停再退订；`:30` 先递增 subscriptionEpoch 并移除本地 cleanup 引用，再调用宿主清理，`:46` 丢弃未就绪、已暂停/处置、旧 epoch 或非活跃连接状态的回调。`:58` 仅 manual 解除暂停，`:40` 避免重复订阅，`:86` 幂等 dispose 并停止 watcher。退出 unsubscribe 的计划硬要求和 401/到期 manual gate 均满足；后两者本实现也选退订，不把它描述成原计划的同一句硬要求。测试见 `web/tests/portal-auth.test.ts:11`、`:21`、`:28`、`:35`、`:42`、`:126`，真实壳见 `web/tests/portal-browser.mjs:90`、`:96`。
- ✅ **I4 已关闭。** `web/shared/api.ts:35` 安装前确认有限正寿命，`:39` 固定 performance.now 单调上限，`:44` 取 wall-clock 与单调剩余寿命较早者，保留 generation 检查和 60 秒分段。`web/tests/portal-auth.test.ts:48` 断言 wall 回退一小时、单调推进 60 秒后身份全清，`:54` 验证旧 timer 不清新 generation，`:134` 起保留超长/前跳检查；`web/tests/portal-browser.mjs:99` 在真实壳回退时验证工作区消失和手动恢复。这修复的是前端保留期限，不声称改变后端授权。
- ⚠️ 本次不验证真实外部 IAM、D4a 签名后端联合浏览器链或实际 dev-env 新进程启动；后端 380 source/1933 target 与旧证据沿既验谱系继承，未重新物理全扫。单调上限只防安装后的回退延期，不证明初始客户端时间准确，也不提供浏览器冻结/调度延迟时的即时清除 SLA（`web/shared/api.ts:35`、`:44`；`docs/evidence/iam-stage-4-d4b-lifecycle-fix-brief.md:29`）。

### Strengths

- ✅ 修复位于真实责任边界：HTTP 状态保留在纯下载 helper，当前身份失效留在组件（`web/shared/artifact.ts:31`、`web/shared/Artifacts.vue:18`）；没有修改 observer 分类把普通 TypeError/503 伪装成授权错误，实际联接测试仍使用原 observer（`web/tests/run-observer.test.ts:6`、`:22`）。
- ✅ 订阅同步/排队重入有明确顺序：旧 epoch 先失效再外部 cleanup，注册期 ready=false，手动连接随后取得最新 token（`web/shared/portal-auth.ts:30`、`:43`、`:46`、`:50`、`:65`）。同步注册/清理通知与已退出旧回调均有直接断言（`web/tests/portal-auth.test.ts:11`、`:21`）；正常 connecting 宿主切换淘汰旧 provider 的用途保留（`:42`）。
- ✅ 测试检查实际行为而非仅 timer/函数调用形状：清理失败测试核锁和后续读状态计数（`web/tests/run-observer.test.ts:6`），时钟测试核 token/user/roles 清除（`web/tests/portal-auth.test.ts:48`），浏览器核真实入口、旧 HTTP 401、新身份、订阅数量与 POST/cancel/download 不增长（`web/tests/portal-browser.mjs:90`、`:96`、`:99`、`:102`、`:109`）。
- ✅ 新实现保持规模与接口：provider 仍两方法，controller 91 行，变化集中在连接生命周期（`web/shared/portal-auth.ts:3`、`:28`）；下载旧五参与可选 scope 保持（`web/shared/artifact.ts:13`），测试证明无 scope 成功下载及 URL 释放（`web/tests/portal-auth.test.ts:72`），未新增认证框架或状态层。

### Issues

#### Critical (Must Fix)

- 无。

#### Important (Should Fix)

- 无新增；原 I1/I2 与补充 I3/I4 均关闭，依据见上述逐项实现与测试引用。

#### Minor (Nice to Have)

- **M1 保留：admin/webapp 构建仍有超过 500 kB 的 chunk 警告。** 为核实此具名问题，仅检索两波对应四份原始构建输出：`docs/evidence/iam-stage-4-d4b-error-stop-fix/final-build-admin/output.raw:18`、`docs/evidence/iam-stage-4-d4b-error-stop-fix/final-build-webapp/output.raw:11`、`docs/evidence/iam-stage-4-d4b-lifecycle-fix/final-build-admin/output.raw:18`、`docs/evidence/iam-stage-4-d4b-lifecycle-fix/final-build-webapp/output.raw:11`，均存在实际 warning。两波未提高阈值或顺手改打包；本次不能据此断言新增性能回归。后续性能工作可先量测首屏成本再决定拆包，本项不阻断四个行为修复。

### Assessment

**Task quality:** Approved

**Reasoning:** 四项修复均落在实际失效、读取清理、provider 订阅与计时边界，并有覆盖原失败路径的行为断言；未发现本次新增阻断性缺陷，两波 root 有限证据审计均已 PASS。该结论是 I1–I4 聚焦代码门禁，不代替完整 D/E/F、生产 IAM 或全平台验收。

- ✅ 审查输入检查：先读两份 fix brief，再全文读两份作者报告，然后按不相交区间一次读完合并完整上下文包 1150 逻辑行至 EOF，无工具截断；未重读改动文件、未运行 git、未搜索其他业务源码。包为 96,711 bytes/SHA256 `d2a0d8a1d01376b73321bc509d5e6c8c899746cddd8328e1215213cf9134ca39`；error-stop 报告 9,890 bytes/SHA256 `22fc260d0a29c0b86fb6d217e164f3ded9179e7bd948337ab4f972d0e18531e4`；lifecycle 报告 10,126 bytes/SHA256 `076069cc78f2ff216c434d0d617f0046d079d9dc1550d3222840123fdb13e4ce`，均由本 reviewer 核对。
- ✅ I1/I2 独立证据检查：已读 `.local/iam-d4b-error-stop-root-check.json:2` 的 PASS；root 核 392 个不同物理文件、192 当前前端、163 新叶、9 实际命令、17 定向绿与 3 实际浏览器场景、15 dist/精确重放及 12 锚点/8 索引。读测试源码确认断言语义，不把 probe-results 中未独立打印的变量描述当成额外 raw 输出。
- ✅ I3/I4 独立证据检查：已全文读取并核对 `.local/iam-d4b-lifecycle-root-check.json:2` 的 PASS，1,637 bytes/SHA256 `508e05ef56b2c15b3424b5dae21ac246d39d6dd2a1551a1102af3d212f445523`。root 核 366 个不同物理文件、192 当前前端、133 新证据叶（6,047,640 bytes）、15 dist/精确重放、10 实际阶段、5 新失败/2 原已绿→10 定向绿、另 2 门禁绿与 3 实际浏览器绿，以及 16 锚点/8 索引和原设计不变。root 同时回传实际退出 unsubscribed=1/active=0、手动后 active=1 及正常 B 切换，实际附件 401 后旧宿主通知不恢复，wall 回拨 3,600,000 ms 而安装寿命 1,200 ms 仍按单调上限到期的核验；断言语义已由本 reviewer 从合并差异检查（`web/tests/portal-browser.mjs:90`、`:96`、`:99`）。这是分阶段结果，不能合并成单次最终全套绿；该 root 记录末尾的“combined code review separate/pending”表示其生成时的代码审查状态，本报告给出独立代码结论。
- ✅ 本次不重跑作者测试、浏览器或任何 probe：未发现需要另做最小验证的具体新代码疑点。只写本独立复审报告，原两份 review、两波 seal/source/dist/作者报告保持不变。
