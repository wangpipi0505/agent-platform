# D4b聚焦修复：附件401与流清理异常

状态：正式修复要求，原D4b代码门禁未通过。依据原D4b brief和独立审查`review-iam-stage-4-d4b.md`（9217字节，SHA256 `d91c154df4fe00cef27348bb7e4dc8a1ca04fc4fded7a16c8cc38d2355807d90`）。root已完整读审查及原报告，接受I1/I2行为证据；不是让作者按结论盲改。按receiving-code-review/TDD确认路径后修复，保留原初审/报告/失败/908叶seal原件。

唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`，每shell显式workdir，遵守AGENTS。不访问相邻旧工程、数据、配置、依赖和进程。原v1.2/四契约/后端380 source与1933 target/SQL/POM/依赖/lockfile/主进程/八索引不改，不排工期、不部署。前端默认portal/显式dev、generation与SDK ticket和下载原五参接口继续按原brief。

## 1. 两项必须闭合的行为

**I1**：当前`web/shared/artifact.ts:31`将HTTP401转为没有status/code的普通Error，`Artifacts.vue:17`仅显示文案，当前token/user/roles、generation和观察继续保留。纯下载helper必须保留可辨HTTP状态（必要的code也可保留，但不为取code无界读取错误体）；仍无全局auth依赖、原五参调用兼容。组件仅在所属捕获scope仍有效且当前HTTP401时调用统一失效，清全部身份并终止旧观察；旧generation迟到401不能清新身份，403/普通错误不冒充401，不自动重连或重放下载/POST。不要只修改错误文字。

**I2**：`web/debug-panel/events.ts:121` finally中的await reader.cancel拒绝会覆盖既定授权/中止原因。reviewer用真实ReadableStream+AUTHORITY_DENIED帧+底层cancel拒绝定向复现：最终抛TypeError cleanup failed，observeRun opens=1、readStatus=1并返回SUCCEEDED。清理必须尝试取消并释放reader lock，同时不得用清理异常替换已经确定的授权reset/401/403/IAM503/AbortError，也不能让授权停止变成普通瞬态断流。禁止在observer里把所有TypeError/普通503都改成授权拒绝来迁就；原普通网络恢复保持。对正常/失败/主动abort如何保留主要结果和回收资源写清，不能承诺底层cancel拒绝时物理socket一定关闭。

原评审M1是admin/webapp大chunk警告，本修复记录保留，后续性能任务量测；不提高warning阈值或顺手重构打包来消噪。全局D/E/F/真实IAM和固定1366工作区等未验边界不因修复关闭。

## 2. 源码范围和必要验证

生产仅修改`web/shared/artifact.ts`、`web/shared/Artifacts.vue`、`web/debug-panel/events.ts`。定向测试可修改既有`web/tests/portal-auth.test.ts`、`web/tests/stream-auth.test.ts`、`web/tests/run-observer.test.ts`、`web/tests/portal-browser.mjs`；不新增认证框架/公用错误层，不修改run-observer生产逻辑掩盖原因，不改其余组件。实施前向root回报精确文件/API/错误处理和selectors；确有额外文件必要时先报具体理由，不自行扩大。

先真实关键行为红，再最小修复、仅具名受影响绿：

1. 真实artifact helper HTTP401保留status，实际Artifacts组件当前401使token/user/roles全清、generation变且工作区/观察失效，provider/业务POST/下载没有自动重试。
2. 延迟旧下载401至新session成功后，旧scope丢弃且不失效新身份、不显示旧错误或click；原无scope五参调用仍不依赖全局auth，成功下载控制如需要只补直接相关断言。
3. 真实ReadableStream发AUTHORITY_DENIED后cancel拒绝，streamEvents仍输出可辨授权停止，后续buffer业务帧零、reader释放有可观察证据；observeRun实际opens=1/readStatus=0，不返回SUCCEEDED或自动重开。不要只mock streamEvents直接抛授权错误。
4. cleanup拒绝与调用者abort组合保留AbortError；原普通网络错误仍按既有恢复语义。仅直接受影响旧selector回归，不重跑原57/127全套或完整browser32。需要实际组件验证时用已授权隔离HTTP/Playwright；不再次问用户权限。

原关键fixture/body/消费者已稳定，不重复修复过的setup。新失败原件和真实source绑定保留，缺类/错脚本/setup不计行为红。共享events/artifact被三个应用消费，最终源码冻结后完整vue-tsc及三个应用build各一次适用；使用原实际命令、SDK输出同步至webapp/dist并核SHA，不把旧构建算本次。只因新改动/新失败才再跑直接受影响项，不为消除warning重跑。UI布局未改无需重复8图；如新401场景自然产生恢复入口图，可保存并实际查看。

## 3. 所有权、谱系与交还

作者是本修复唯一source/对应三个dist writer，backend/target冻结；root不同时改source。独占`.local/iam-d4b-error-stop-fix/`、`docs/evidence/iam-stage-4-d4b-error-stop-fix/`及新报告`docs/evidence/iam-stage-4-d4b-error-stop-fix-report.md`；旧报告/review/seal/908叶目录全部保持原样。root独占八索引、current-work-state、review和未来O1a brief。

基线为原D4b handoff/seal与composition：必要frontend192＋backend380=572（宽622另含50旧日志，口径不混），dist15，backend target1933继承不重扫。原root有限审计`.local/iam-d4b-root-check.json` PASS：5281字节SHA `ea7d15c0609d8a9e1e49bb701980913f0cb9e5cbb4e8e5651e941fa59c19cbc9`，1136distinct物理文件含真实已核1117缓存；root首次工具错误/续核原件保留。不重复旧908叶/后端target/旧10GB物理扫描或全量复制，沿冻结组合复用。每次命令前完整必要source SHA描述加仅变动overlay，原初捕获不可倒改。

最终报告含实际命令/cwd/exit/用例与源码阶段适用、实际浏览器当前/旧401counter、清理异常probe结果和边界。提供精确source/dist delta、最终冻结overlay、真实rootcwd git apply check/apply和572必要逻辑源names/bytes/SHA一致、own精确manifest/总bytes/SHA及自有资源关闭。原App混合CRLF已用临时bare证据repo -text属性的精确binary patch解决，本修复不涉及App，不改工程git/config/.gitattributes；避免为重放复制整棵旧证据。

写完新报告与seal后冻结并交还全部source/dist/own写权，返回小摘要和SHA；root有限审计后交原reviewer聚焦复审I1/I2，同时检查本次新回归风险，初审保持不改。只有两项关闭才可更新八索引并推进O1a；不自行宣称全D4b或整个平台已完成。
