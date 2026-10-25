# 算法真实进程 runner 独立任务审查

## Spec compliance

**结论：准备交付符合本轮范围；真实纵切验收待执行。** 本结论来自完整新增 patch、原始有限检查日志和下列定点合同核对，不以作者报告中的“已修正/已通过”作为判断依据。没有发现必须阻止 root 移交固定制品的规格缺陷。

- 本轮 baseline 为三份文件不存在，patch 完整新增两源码与准备报告。独立只读 SHA 检查核对 manifest 的 sources/frozen/replay/inputs/evidence/patch/历史报告共 31 项；从 patch 的新增行重新拼接三文件，SHA 与当前源码及 manifest 全部一致。
- 实际执行入口只接受显式 `--execute-reviewed`；lock 校验先于读取凭据和创建资源。逐模块检查 root handoff 标记、绝对固定目录、完整小写 SHA、realpath 后目录边界、JAR 不逃逸目录及内容 SHA；每次 spawn 再核 SHA（`tests/smoke-algorithm-processes.mjs:128`、`:171`、`:271`、`:428`）。标记本身是人工移交约定，不能代替 root 对制品的审查。当前 `.local/artifacts` 实际是普通 Directory，无 LinkType/Target；根目录本身保持本工程内是此边界检查的前提。
- 真实草稿注册、版本 1 connection-test 和内部对账路径与实际 controller/governance 相符；初次创建只调用一次，未知响应不重试；重检仅传原 `controlledCallId`（runner `:315`、`:328`、`:365`、`:388`；`skill-registry/src/main/java/cn/agentplatform/skill/api/SkillController.java:50`、`:89`、`:169`；`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillGovernance.java:57`）。实际 `RunService.status` 提供 `controlledCallId`（`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:1335`）。
- 每场景独立 namespace/fixture，检查首次原 Job GET 先于创建、唯一认证 POST、原 call/attempt/key/namespace/Job/identity 摘要和已捕获 UID 保持；核查真实非 Mock DEBUG fact、原 claim owner、一次 tool call、一次 attempt、一次终态事实接受及成功逻辑提交（runner `:298`、`:340`、`:350`、`:380`、`:404`）。计数、UID 和完整观察均保留，不把有限 HTTP 计数称为容器执行。
- Failed 同时检查 call/run/fact FAILED、不可重试语义、绑定 DONE/Failed、重检 `ok=false`，不把 Failed 当作 NOT_APPLIED（runner `:64`、`:374`；实际 `SkillExecution.reconcile` 对已有终态调用 deliver，`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:598`）。Running 对账只查原 call；本地取消后明确要求 run CANCELLED、call/fact UNKNOWN、绑定原 UID/SUBMITTED、零逻辑提交（runner `:57`、`:362`）。
- 输出内联核对实际 envelope 的 `structured`；约 1.2 KiB 正例使用公开下限 `constraints.outputMaxBytes=1024`，转存后要求 `structured=null`、单 fileRef、真实 PG READY 归属、受控下载逐字节及 SHA/长度一致（runner `:76`、`:303`、`:322`、`:393`；`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:419`；`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:525`；`skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java:324`；`skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java:463`）。这里只确认方案与实际接口相符；未确认真实 MinIO 结果。
- 每次业务 HTTP 前检查 child 尚存活、记录 PID、创建时间、精确 `-jar` 参数和全部 listener PID；启动前拒绝既有监听，停止前 JS 与 PowerShell 都再次核验（runner `:42`、`:97`、`:229`、`:238`、`:251`、`:271`）。进程服务 URL/凭据只注入显式环境；唯一新库由 createdb 创建、事实 SQL 均 `begin read only`，未 SQL seed、未 drop（runner `:174`、`:190`、`:198`、`:289`）。
- 逐个子进程、逐个 fixture、最终记录均独立尝试；原错误、多个清理错误及最终写入错误保留，fixture 关闭有上限（runner `:21`、`:205`、`:414`；`tests/algorithm-process-runner.test.mjs:9`、`:28`、`:93`）。原 fixture close 幂等且调用 closeAllConnections（`tests/smoke-algorithm-protocol.mjs:82`），适合场景结束及外层 finally 再清理。

**待验边界：** root 同轮源码回归、固定不可变 JAR/SHA 及五个实际 engine/skill 场景尚不属于本轮通过结论。真实迁移、PG 查询、JVM 启动/归属/停止、deadline 客户端上传与回读只能在交接后从原始日志及只读 PG 事实核验。真实 Kubernetes/容器/RBAC、生产 IAM、容量和全部 G2 不在该 runner 的证明范围。

## Quality

**结论：Approved（准备阶段；一项非阻塞断言改进）。** 资源创建与清理路径集中、默认入口安全、失败不转业务重试，有限测试确实检查了错误分支。没有 Critical 或 Important 问题。

### Minor：最终 fact 原代归属可再直接断言

- 位置：`tests/smoke-algorithm-processes.mjs:352`、`:382`、`:385`。
- 当前 before 将 fact.attempt_id 与原 call/绑定比较，after 固定 call 与绑定的 attempt，但 after.fact 只明确断言 is_mock/状态/结果；未直接比较 after.fact.attempt_id、execution_owner、call_kind 与 before.fact。若将来对账错误地换代 fact，原始 JSON 会留下证据，但 runner 对“所有三方最终仍是原代”的门禁表达不完整。
- 影响：这是最终自动断言的局部缺口，当前实际执行合同和已检查的回调路径没有发现可据此造成错误通过的具体缺陷，因此不列为阻塞。建议 after 增加这些原 fact 字段等值检查；真实验收的独立 PG 审查必须继续比较它们。

## 证据与检查范围

- 阅读原 `resume-green-all.log` 与 `replay.log`：均明确列 12 pass、0 fail/skip/cancel；replay 原日志保留 git apply --check/apply 与 Node 检查退出 0。它们是作者已运行的有限检查证据，本审查没有重复运行整套测试，也没有用它们推定 JVM/PG/MinIO 通过。
- 本审查唯一执行的脚本验证是只读 SHA/patch 重建，命令为 PowerShell here-string 管道到 `node`，内部只调用 `readFileSync`、SHA256 和字符串解析；退出 0。原始结果如下（没有执行 runner 入口或导入 runner）：

```json
{
  "command": "node read-only manifest SHA and new-file patch reconstruction",
  "checks": 31,
  "sourceSha": {
    "tests/smoke-algorithm-processes.mjs": "a29c1e0d0a0e6c68bed1238e9655ea3843de1eb590599468ab205f8e8fe891c4",
    "tests/algorithm-process-runner.test.mjs": "0f7e90723af9628f2b4132be20f8bc816d865e8785a55e8cf9cb9d7495aacf6a",
    "docs/evidence/algorithm-process-runner-ready.md": "d7723588ba930bd495111f558c483ff8b25b132f779b4aac70a8ad381a74b968"
  },
  "patchReconstructed": {
    "docs/evidence/algorithm-process-runner-ready.md": "d7723588ba930bd495111f558c483ff8b25b132f779b4aac70a8ad381a74b968",
    "tests/algorithm-process-runner.test.mjs": "0f7e90723af9628f2b4132be20f8bc816d865e8785a55e8cf9cb9d7495aacf6a",
    "tests/smoke-algorithm-processes.mjs": "a29c1e0d0a0e6c68bed1238e9655ea3843de1eb590599468ab205f8e8fe891c4"
  },
  "failures": []
}
```

- 定点读 diff 外源码的具体风险：①connection-test/reconcile 返回值与角色/受控 call 定位不符；②structured/fileRefs/outputMaxBytes/下载路由不符；③run_event/attempt_note 名称或 payload 与 SQL 计数不符；④SecretVault 字节格式或子进程环境键不符；⑤重复 close 遗留连接。只查看相应 controller、方法、配置、migration 和原 fixture；没有扩大为生产算法重复审查。
- 未访问 target、未 package、未启动/停止 JVM 或任何真实服务、未连接真实 PG/MinIO、未改源码/原证据/总台账、未提交、未读相邻旧工程。新增文件仅本报告。
- 写报告后再次 `Get-FileHash -Algorithm SHA256 -LiteralPath` 核对上述三文件（退出 0），三个 SHA 与前述只读检查相同；审查期间源码及准备报告未变化。

## 追加复核：首跑失败后的窄修复

2026-10-20。本节针对 `algorithm-process-runner-fixes.patch` 的完整增量作独立复核；上文原始准备结论及其 SHA 仍保留为历史，不能作为当前源码 SHA 或实际进程通过结论。

**Spec compliance：通过本次窄修复范围。** `tests/smoke-algorithm-processes.mjs:92` 在唯一 PowerShell wrapper 的 `-Command` 前设置无 BOM UTF8 输出，与既有 `spawnSync encoding:'utf8'` 一致；没有修改 PID、创建时间、精确 JAR、TCP owner、停止前复核或清理逻辑。`tests/smoke-algorithm-processes.mjs:55` 对所有场景直接比较最终 fact 的 attempt_id、execution_owner、call_kind 与 before 原值，关闭上轮 Minor。`tests/algorithm-process-runner.test.mjs:65`、`:68` 只补实际观察已有的三字段及 before.fact 副本，没有削弱原12项断言。

**Quality：Approved，无新 Critical/Important/Minor。** 新增 `tests/algorithm-process-runner-fixes.test.mjs:7` 从实际源码提取 helper，真实调用 PowerShell 回显无秘密中文路径并严格比较，验证的是曾失败的编码行为；`:23` 三项均先确认完整有效观察通过，再逐个替换单一 fact 字段并要求明确拒绝。没有以放宽精确路径比较、重新定义身份或跳过断言消除首跑错误。

独立读取原始 `algorithm-process-runner-fixes/red.log`：四项确实全红，其中 Unicode 实际得到替换字符，另外三项确实为 Missing expected exception。读取 `green.log` 与 `replay.log`：均为16 pass、0 fail/skip/cancel；replay 保留实际 apply --check/apply 和各语法/测试命令退出0。`post-failure-ownership-and-encoding.json` 与首跑 summary 明确保留 default exactPath=false、显式UTF8=true、首跑退出1、原 finally 清理失败和稍后 pid=null/listenerPids=[]。本轮没有重复16项测试，也未执行真实 runner。

本审查另执行只读 SHA 与内存中 patch 应用检查，退出0：新 manifest 的 baseline/sources/frozen/replay/evidence/patch/原manifest共27项一致；原 manifest 中 frozen/replay/inputs/evidence/patch/暂停报告共28项历史 SHA 保持；从本次 baseline 在内存应用完整 patch 后四文件均匹配新 manifest。原始输出如下：

```json
{
  "command": "node read-only new manifest, historical 28 SHA and in-memory patch application",
  "checks": 27,
  "historicalChecks": 28,
  "reconstructed": {
    "docs/evidence/algorithm-process-runner-fixes.md": "0d5506da403f42f79ecd35cf6c2bcbef3300c9fb5c8365e8e93275fffc28554f",
    "tests/algorithm-process-runner-fixes.test.mjs": "497b62697389a9acc7fe98dffaba53ae29081fe92e85ff18dbf9093199f7218d",
    "tests/algorithm-process-runner.test.mjs": "9329f7550c1e455029c2ff6ff6130366efb1dbab9ff6dff7fa05e0231c8a030d",
    "tests/smoke-algorithm-processes.mjs": "9edbf0c02e17f8e792146ed065339c3877a737e17d2802633d1cab3c5647e8e8"
  },
  "failures": []
}
```

**实际验收状态仍为首跑失败、五场景未通过。** PID52436 是记录的 skill child，54504 是其端口；engine 未启动、业务 HTTP/fixture POST 为0，没有 run/call/attempt/UID 或 MinIO READY/下载可验证。稍后无进程/监听不能改写当时 finally 的失败，也不能证明该进程由清理逻辑停止。本修复解除重新执行前的代码审查门禁；root 后续执行必须产生新一轮独立原始进程、HTTP、fixture、PG、MinIO和清理证据，再作真实纵切审查。

本追加仅修改本审查报告，未改 runner/测试/生产/原证据，未触及 target，未启动/停止 JVM或真实服务，未连接 PG/MinIO，未提交。

追加报告后再次只读核对四个当前 source SHA，退出0、unchanged=true，与本节上述值完全一致。

## 追加复核：v2 NOAUTH 后的显式 Redis 配置修复

2026-10-20。本节依据完整 `algorithm-process-runner-fixes-v2.patch`，独立核对本次新增 helper、调用顺序、三个 child 环境键、脱敏表及五项假配置检查。上轮 source SHA 仅为历史；当前 runner SHA 为 `e30638e7f0642570f8a5b5740678afff6153fd64aab7b4107696390059389c93`。

**Spec compliance：通过本次窄修复范围。** `tests/smoke-algorithm-processes.mjs:149` 要求三个 Redis 配置均为显式非空字符串，只允许本工程 `127.0.0.1:26379`，密码原样返回；`:178` 在 mkdir/createdb/fixture/spawn 前调用。`:322` 仅将已验证三键展开到原显式 common，原 system 白名单与最终 child 的 `env: {...system,...common,...}` 顺序保持。`:203` 把 Redis 密码加入既有脱敏表。没有禁用健康检查、放宽超时、继承父 AGENT/Spring/Java 全环境或修改生产配置/fixture/制品。

**Quality：Approved，无新增 Critical/Important/Minor。** `tests/algorithm-process-runner-redis.test.mjs:12`、`:18`、`:56` 执行实际 system/common/secrets/redact 构造表达式；五项测试用假配置验证显式透传、父环境毒化隔离、缺失/空白拒绝、错误端点拒绝及 Redis/PG 密码同时脱敏。测试不读取真实 .env，不连接 Redis。资源前验证的调用位置另由 diff 检查，未把测试 helper 的拒绝误称为已经运行真实启动流程。

**本次启动配置定点核对：未发现其他具体必需配置遗漏。** 检查 `engine-runtime/src/main/resources/application.yml:11`—`:14`，Redis host/port/password 正是此次新增三键；`engine-runtime/src/main/java/cn/agentplatform/engine/infra/ChunkBus.java:50`—`:54` 从相同 Spring 配置读取连接与认证，没有另一套必填 Redis 密码来源。两服务 application.yml 的 SERVER_PORT/BIND、PG URL/user/password、JWT、service URLs 和 scheduler 参数均有原显式注入，stream/CORS/embed 为代码已有默认值。进一步检查 `admin-common/src/main/java/cn/agentplatform/common/SecretVault.java:25`、`:32`，`skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java:264`—`:267`，`skill-registry/src/main/java/cn/agentplatform/skill/infra/AlgorithmAdapter.java:60`—`:92`：vault、四项 MinIO、K8s API/namespace/account/允许镜像/loopback开关也均有显式 child 值。这是对这两个私有子进程启动及本轮算法路径的静态检查，不能证明真实服务凭据有效或后续业务执行成功。

**v2 真实失败及清理边界保持。** 原 engine stdout `algorithm-process-runner/live-49498fe27b72/engine-runtime-1.out.log:53`、`:79` 明确是 RedisReactiveHealthIndicator/NOAUTH；应用已启动而健康未通过。读取原 `processes.json` 和 root 的 `.local/algorithm-live-v2-cleanup-root.json`，两个记录 child PID59292/36984 均 stopped=true，最后各自 PID不存在、端口65282/65281无 listener。读取原只读 PG JSON：engine200—215、skill300—311成功，skills/facts/bindings/runs/calls均0，新库保留。这些是已保存原始记录的审查，本审查没有再次查询进程或数据库。

原 `red.log` 确实5项全红：三字段 undefined、缺配置/错端点未拒绝、Redis假密码未脱敏。原 green/replay 均21 pass、0 fail/skip/cancel；replay 包含实际 apply --check/apply、四份源码语法检查与测试退出0。本审查未重复21项检查或运行真实入口。

另执行只读 SHA 与内存 patch 应用，退出0：本次 manifest（含保留 v1/v2 live 证据）60项一致、上轮历史21项一致；完整 baseline 应用本次 patch 后六文件全部匹配当前 manifest。原始输出为：

```json
{
  "command": "node read-only v2 manifest, preserved live, previous historical SHA and in-memory patch application",
  "checks": 60,
  "historicalChecks": 21,
  "reconstructed": {
    "tests/smoke-algorithm-processes.mjs": "e30638e7f0642570f8a5b5740678afff6153fd64aab7b4107696390059389c93",
    "tests/algorithm-process-runner.test.mjs": "9329f7550c1e455029c2ff6ff6130366efb1dbab9ff6dff7fa05e0231c8a030d",
    "tests/algorithm-process-runner-fixes.test.mjs": "497b62697389a9acc7fe98dffaba53ae29081fe92e85ff18dbf9093199f7218d",
    "docs/evidence/algorithm-process-runner-fixes.md": "0d5506da403f42f79ecd35cf6c2bcbef3300c9fb5c8365e8e93275fffc28554f",
    "tests/algorithm-process-runner-redis.test.mjs": "f939eff96c4467cfa7dc9784ccc3a752e50435fb947d509130dd9c576b81888c",
    "docs/evidence/algorithm-process-runner-fixes-v2.md": "b8acadc19e6133eb11b6f10a641ae3d33098987d77df987c7a9801d21c5bad3d"
  },
  "failures": []
}
```

**当前真实纵切状态仍是 v1/v2 失败、五业务场景0完成。** v2 的完整迁移和成功清理不能代替算法调用、原代对账、非重POST、Failed/UNKNOWN 或 MinIO 转存结果。本修复解除下一次入口执行前的代码审查门禁；新实际执行仍需独立保存事实并另作审查。

本追加只修改本审查报告；未改源码或旧证据，未访问 target，未执行 JVM/PG/Redis/MinIO/真实runner或任何服务启停，未提交。

报告追加后六个当前 source SHA 再次只读检查退出0；原输出：`{"postReviewCurrentSourceFiles":6,"unchanged":true,"runnerSha256":"e30638e7f0642570f8a5b5740678afff6153fd64aab7b4107696390059389c93"}`。

## 追加复核：v3 同线程阻塞后的有界异步 PowerShell

2026-10-20。当前 runner SHA：`257e8545c4818d1d40a235a32a3c10d6912997e10d9a0aacf876bd56d3b75688`。本次完整增量为 runner 的异步 helper/await 链、Unicode 测试适配、新增 async 测试及报告。

**Spec compliance：runner 的异步改造符合本轮范围；新增有限测试的异常收尾存在缺陷。** 未发现漏 await、身份拒绝后继续 HTTP、未确认 JVM 被停止或放宽业务期限的问题；下述测试清理缺陷修复前，本次交付不能整体通过。

**Quality：Needs fixes，一项 Important/P2。** 原生 execFileAsync 比同步 PowerShell 更适合与同 Node 线程 fixture 共存；helper 不包装失败，UTF8/独立环境/精确归属与原4秒业务期限均保留。新增测试覆盖了响应性、绝对超时、双流溢出及原始退出错误，但测试自身的失败路径会遗留真实监听，必须修复。

### 已核查的 runner 行为

- `tests/smoke-algorithm-processes.mjs:93`—`:104`：使用内置 promisify(execFile)，默认 timeout=10000ms、maxBuffer=65536（分别限制 stdout/stderr），参数仅允许缩小上界；保留 UTF8、windowsHide、独立白名单环境和 killSignal。没有捕获/替换 execFileAsync 原生错误；非零退出、timeout、maxBuffer 拒绝将向上抛出。超时终止的是本 helper 创建的 PowerShell child，不能作为停止任意 JVM 的授权。
- `:117`、`:130`、`:222`、`:239`、`:247`、`:250`、`:268`、`:283`、`:291` 全部必要异步结果已 await。`request` 在 `:256` await owned 完成后才 fetch；health `:298` 同样先 await owned。`stop` 先 await inspect 再执行完整归属断言，之后才 await 内含二次核验的 PowerShell stop；拒绝会进入原逐项清理聚合错误路径，不继续该次 HTTP/停止。
- 原 green/replay 的26项全部通过（0 fail/skip/cancel），原 red 五项全部失败且原因对应同步阻塞/缺界限/丢原code；这证明已运行的正例和已选失败例，不能排除新测试未覆盖的清理分支。本审查未重复26项。

### Important / P2：pending 拒绝会跳过 HTTP 测试的全部清理

- 位置：`tests/algorithm-process-runner-async.test.mjs:30`—`:31`。
- 问题：finally 中先 `await pending`，之后才 closeAllConnections/close。真实 PowerShell 启动失败、超时或其他 execFile 拒绝时，await 立即抛出，两个关闭调用都不会执行。该测试的 server 是有引用的真实监听，测试没有自身总超时，因此失败检查可能留下监听并使 Node 测试命令一直不退出。如果先有 HTTP 错误而 pending 随后也拒绝，finally 还会用后者覆盖前者。快速拒绝还可能先于 pending 被 await，造成未处理拒绝噪声。
- 独立证据：只提取实际第一项测试的回调，注入假 HTTP server/fetch 和40ms后拒绝的假 powershell；不创建任何 socket/PowerShell/JVM。两种情形（HTTP成功+PS拒绝、HTTP失败+PS拒绝）均实际得到 `closeCalls: []`；第二种原 HTTP 错误也被覆盖。probe 退出0表示已成功复现缺陷，不表示该清理路径通过。
- 修复建议：在创建 pending 后立即附加结算处理，保证不会先产生未处理拒绝；将 server 的关闭放在不受 pending 结算影响的嵌套 finally/独立清理尝试中，保留原测试错误及 pending/清理错误。补一项有限故障检查，证明 pending 拒绝及 HTTP+pending 同时失败时关闭仍执行，且检查进程有界退出。无需修改 runner 生产调用链或增加真实服务验证。
- 新增独立审查证据目录：`docs/evidence/review-algorithm-process-runner-v3/`。`async-cleanup-probe.mjs` 为完整 probe 源码；`async-cleanup-probe-command.json` 保存命令/退出0/空stderr；`async-cleanup-probe.log` 保存原 stdout；`source-before.json`、`source-after.json` 保存八份 source 的前后 SHA，全部相同且匹配当前 manifest。

### 包与真实执行边界

独立只读 SHA/内存 patch 应用退出0：当前 baseline/sources/frozen/replay/evidence/v3原始证据/root清理/patch/上轮manifest共61项一致；上一轮 frozen/replay/evidence/保留live/patch/manifest共50项历史一致；应用完整 patch 后八份源码/报告 SHA 与当前 manifest 一致。

```json
{"checks":61,"historicalChecks":50,"reconstructedFiles":8,"failures":[],"runnerSha256":"257e8545c4818d1d40a235a32a3c10d6912997e10d9a0aacf876bd56d3b75688"}
```

读取原 v3 summary、blocking diagnostic 及 root cleanup JSON：已保存的同步检查耗1971.955ms，同时线程25ms timer及有限HTTP延后至检查返回；这支持修复结构阻塞，仍未精确证明原SDK超时的全部因素。v3原Job只收一次POST，fixture有UID而PG binding仍SENDING/UID=null、fact/call UNKNOWN、run WAITING_EXTERNAL、逻辑提交0；两记录child62900/46180已 stopped且端口55459/55458无监听。原未知事实、失败报告和之前历史保持。没有把本次异步有限检查或本次清理成功推定为五场景真实通过。

本追加只新增本报告和独立审查probe证据；未改源码/原证据/target，未运行真实入口或JVM/PG/Redis/MinIO，未启动/停止真实服务，未提交。第四轮入口应继续等待本项测试清理缺陷返修后的独立复核。

## 追加复核：异步测试收尾 P2 返修

2026-10-20。依据完整 `algorithm-process-runner-test-cleanup-fix.patch`、原始五故障红/绿JSON及定向/完整/replay日志独立复核；原 Needs fixes 与旧probe保留，本节为返修结论。

**Spec compliance：通过此次仅测试返修范围；原 P2 已关闭。** 完整 diff 只修改第一项 async 测试并新增回调故障probe、有限子进程测试及报告。实际 runner SHA仍为 `257e8545c4818d1d40a235a32a3c10d6912997e10d9a0aacf876bd56d3b75688`，没有修改原4秒期限、环境、业务断言、真实入口、fixture或进程清理预算。

**Quality：Approved，无新增 Critical/Important/Minor。** 返修直接修正原 pending 拒绝跳过关闭与覆盖原错的问题，并补可证伪的五故障检查；没有通过吞掉错误或跳过原响应性断言实现绿色。

- `tests/algorithm-process-runner-async.test.mjs:25`—`:26`：先附双路径结算handler再启动 PowerShell；快速拒绝转成结果而不产生暂时未处理拒绝。HTTP/timer先响应和最终PS值断言仍保留。
- `:34`—`:42`：主体错误先入 errors；finally读取pending只补缺少的原PS对象，单错抛原对象、多错AggregateError保留全部errors且原首错为cause。HTTP先失败、PS后拒绝时不再覆盖HTTP。
- `:43`—`:51`：嵌套finally内独立尝试 closeAllConnections 与 close，前项抛错不会跳过后项；close callback失败保留原对象，永不结算由测试专用1000ms超时报告ETIMEDOUT，timer必清除。listen位于try内，失败也会进入同一关闭路径；ERR_SERVER_NOT_RUNNING按已停止处理。
- `tests/algorithm-process-runner-async-cleanup-probe.mjs:16`—`:24`：提取实际回调并只注入假HTTP/PS，1000ms关闭等待在probe中缩为30ms，真实测试源码预算不变。原对象身份、主cause、关闭顺序及未处理拒绝均有输出；不是复制一个理想清理实现。
- `tests/algorithm-process-runner-async-cleanup.test.mjs:9`—`:19`：五种故障各在独立Node子进程中执行，2000ms timeout、16KiB输出上界；要求stderr空、unhandled=[]、两个关闭调用顺序正确，并检查双失败原错、三错误对象及永不结算close的ETIMEDOUT。

独立读取 `red-baseline-probes.json` 与 `green-current-probes.json`：旧回调PS拒绝跳过关闭、双失败丢HTTP、快速拒绝有unhandled/warning、never-close退出13，均在原JSON保留；新五case均exit0/stderr空/unhandled=[]且closeCalls依次为closeAllConnections/close。新双失败保留HTTP+PS且HTTP为主因，新三错误保留PS+destroy+close原对象，never-close有ETIMEDOUT。这些原始结果与新增测试断言、实际改动一致。

定向原日志10/10通过，完整和实际apply replay原日志均31/31、0 fail/skip/cancel；原红日志5/5失败且原因命中本P2。本审查未重复31项、未重跑真实PowerShell/服务，也未覆盖上轮独立probe。

另执行只读SHA及内存patch应用，退出0：63项当前包SHA、47项上轮历史SHA一致；baseline应用完整差异后11文件匹配新manifest。原输出：

```json
{
  "command": "node read-only cleanup-fix manifest, previous historical SHA and in-memory patch application",
  "checks": 63,
  "historicalChecks": 47,
  "reconstructedFiles": 11,
  "runnerSha256": "257e8545c4818d1d40a235a32a3c10d6912997e10d9a0aacf876bd56d3b75688",
  "asyncTestSha256": "638d5d438aedc7b0cbae185a987dde61cbe7ffeafa7f2e7aada5fb5aa1d787b7",
  "failures": []
}
```

原三轮actual失败及五场景未通过状态保持；本结论仅关闭本次代码审查门禁，不将31项有限检查解释为真实算法纵切成功。root可在重新移交固定锁后执行第四轮，随后仍需审查新原始进程/HTTP/fixture/PG/MinIO和收尾证据。本次只追加审查报告，未改源码、旧证据或target，未运行真实入口或JVM/PG/Redis/MinIO，未提交。

追加后11份当前source SHA再次只读检查退出0、unchanged=true；runner SHA仍为本节上述257e8545值。

## 追加复核：外部原代与本地恢复 attempt 范围

2026-10-20。完整阅读唯一 brief、独立 memo 六条、实施报告和完整 patch。本次 runner SHA为 `e2878c7ae3275eaa26e6a7e24a178fae79c9bac38c91d9ecdfbe55d7c348a1e4`，新oracle SHA为 `f6a78f20554d75994abfc16a4e49ac72def2ab86bd28ff254a35eddc0b4fe9cb`。

**Spec compliance：Needs fixes，一项精度缺陷未满足 memo 第2条。** 本地恢复与原外部身份已分别严格核验，没有仅删除总数1或放宽成任意次数；但跨终态note/事件的PG时间顺序在同毫秒内可能错误通过，见下方P2。

**Quality：Needs fixes，一项 Important/P2；未发现其他阻塞问题。** 集合采集、专用纯Node oracle和真实runner调用分离清楚；93项新检查覆盖较多身份漂移，旧31项源码保持。成功合成补全、afterRecheck副本与其他四模式合成样本明确标注，没有改写v4失败。尚需修正PG时间精度并增加同毫秒逆序负例。

### memo六条的独立核对

1. **逐行采集：符合。** runner新增capture保存完整node、排序attemptRows、calls/facts/bindings数组、带时间events与accepted终态note行及原整数计数；单数别名从集合派生，并在对账前检查各集合唯一。oracle `tests/algorithm-process-attempt-scope.mjs:20`—`:26` 明确检查集合长度、整数计数、别名以及tenantRuns稳定，避免仅看scalar掩盖新call/绑定/业务run。
2. **精确ACK恢复：除时间精度外符合。** oracle `:17`—`:19`、`:56`—`:100` 固定原no1、独立节点key，原row整体不变；仅ACK在before WAITING_EXTERNAL/UNKNOWN/零提交前提下允许恰no2/SUCCEEDED，并要求node.committed_attempt_id及唯一逻辑提交指向no2。NODE_STARTED逐行映射，EXTERNAL_READY恰1且seq在no2开始/提交之前。其他模式继续恰1本地row；本轮检查的Running取消源码仅取消node/run，不改UNKNOWN attempt原行，未发现新整行不变断言与该路径冲突。
3. **完整外部身份：符合。** oracle `:27`—`:55` 比较原call/fact/binding字段、两个完整snapshot及其内部身份/arguments，明确外部key与节点key各自计算；固定原owner/attempt、DEBUG/nonMock/readonly_retry_count=0及result.owner。node当前重新认领不被错误用于重绑外部owner。
4. **首次UID：符合。** `:104`—`:121` 以fixtureBefore非空原UID/Job为基准，before允许NULL、after及重检后固定原UID；终态证据实际六字段与绑定关联。Running不要求terminalEvidence；路由与POST计数继续指向原Job。
5. **原call/fact/binding、终态note、逻辑提交各一次：符合。** `:20`—`:23`、`:62`—`:86`、`:107`—`:114` 同时检查集合/计数、原外部attempt与owner、完整终态结果、节点限定唯一逻辑提交和POST1。UNKNOWN note没有被算为终态。ACK恢复node.output与原structured相同，未把这一成功输出要求强加给Failed/Running/offload。
6. **重检后重新capture：符合。** runner `tests/smoke-algorithm-processes.mjs:416`—`:420` 在after验证之后执行原controlledCallId重检，紧接afterRecheck重新capture并调用全oracle；oracle `:123`—`:129` 再固定attemptRows/terminalNotes/fact结果和node提交引用/摘要/output。Running保持原无重检HTTP的流程；原最终输出/MinIO/fixture/关闭检查继续在passed之前执行。

### Important / P2：Date.parse丢失PG微秒，逆序可能被验为相等

- 位置：`tests/algorithm-process-attempt-scope.mjs:5`、`:97`—`:98`。
- 问题：PG created_at保存微秒，Date.parse返回整数毫秒。终态note必须不晚于EXTERNAL_READY，当前比较却会将同毫秒内的逆序当成相等。memo特别允许同一时刻、不能要求相差正毫秒；这要求保留PG已有精度，而不是把微秒先截去。
- 独立纯Node反例：使用实际测试的合法sample构造和当前oracle，先确认合法ACK通过，仅把唯一终态note时间改为 `2026-10-20T11:23:56.064001+08:00`，EXTERNAL_READY仍为 `2026-10-20T11:23:56.064+08:00`。note晚1微秒，当前oracle仍接受；完整字段/seq/UID/原代均未更改。probe输出 `sameDateParseMilliseconds=true, invalidSequenceAccepted=true`。
- 影响：这个oracle可能对违反本轮明确顺序要求的证据报绿；现有2099年时间负例只能检出较大倒置，124项绿不能覆盖此缺口。
- 修复建议：将PG时间规范为保留小数秒/时区精度的可比较值（例如整数微秒），保留真正相等时间通过；增加同毫秒内终态note晚于ready的负例，并覆盖ready/start/commit同类倒置。无须改生产或重跑真实服务。

### 授权真实只读capture验证

因本次新增SQL此前未真实执行，按root的窄授权，从本工程.env只读取得PG凭据，仅通过PGPASSWORD环境传给本工程psql；固定连接 `127.0.0.1:25432/agent_platform_algorithm_live_f7fecdf481a3`，选择已结束v4的call2，不连接其他库。执行的是从当前runner实际模板提取并只替换tenant/run/call的完整capture SELECT；显式BEGIN READ ONLY、SET LOCAL statement_timeout=5000，PGOPTIONS另固定read_only/5秒，进程timeout10000ms，未执行seed/DDL/更新/新建库。

命令退出0、stderr空。实际返回run `r_2700d27889274aea890a7042b9e0f994`、call2，call/fact/binding各1、attempt id5/6、no1/2、node.committed_attempt_id=6、accepted终态note1、toolCommits1、events14。由此确认当前SELECT字段、集合与保留v4最终行可实际读取；没有执行新场景、重检、JVM或MinIO，也不能称新五场景通过。

独立证据目录：`docs/evidence/review-algorithm-process-runner-attempt-scope/`，包含完整 `verify.mjs`、命令/退出记录 `verify-command.json`、原stdout `verify.stdout.log`、微秒反例JSON、实际SQL及其命令/原stdout/空stderr。`source-before.json`、`source-after.json` 保存14份source SHA，前后相同且匹配当前manifest。该probe退出0表示成功复现精度缺陷并完成授权只读查询。

另作只读SHA/内存patch应用：95项当前包/依据SHA、62项上轮历史文件SHA、v4旧19项证据SHA全部一致，完整patch重建14文件匹配；另核原v1/v2/v3的37项实际证据SHA亦保持。原最终green/replay日志均124 pass、0 fail/skip/cancel，实际apply记录退出0，本审查没有重复124项。

原v4仍整体退出1，ACK重检及后3场景当时未执行；本报告保留memo限定恢复语义判断及所有旧失败。此次只追加报告/独立review证据，未改源码、原证据、生产、target或固定制品，未启动任何真实服务或发业务HTTP。第五轮应继续等待本P2返修与独立关闭。

## 追加复核：PG微秒时间精度P2返修

2026-10-20。本次只审完整 `algorithm-process-runner-time-precision-fix.patch` 中的时间换算与11项新测试；原独立memo六条和本报告上节其他门禁结论保持。

**Spec compliance：通过本次窄修复范围，时间精度P2已关闭。** `tests/algorithm-process-attempt-scope.mjs:5`—`:12` 只将整秒及其时区交给Date.parse，独立把PG的0—6位小数补齐为整数微秒，最后用BigInt合并；原相邻 `>=` 门禁未改。原反例 `.064001` 晚于 `.064000` 现在不再被截成相等；不同小数位表示的真实等时仍合法，正负时区及半小时时区按绝对时间比较。

**Quality：Approved，无新增 Critical/Important/Minor。** 修复使用标准库整秒解析和整数运算，未引入依赖或修改邻近身份/事件seq门禁。runner完整SHA仍为 `e2878c7ae3275eaa26e6a7e24a178fae79c9bac38c91d9ecdfbe55d7c348a1e4`，SQL、原93项scope和31项旧测试均保持原字节；当前oracle SHA为 `4c63d3c66764250211866f36ea511668639c95834acfa07357ec5cd5b4e69abe`。

- `tests/algorithm-process-attempt-time.test.mjs:26`—`:34`：三种相邻阶段的同毫秒微秒逆序均要求原时间门禁拒绝，且明确证明Date.parse会把这些值折成同一毫秒。
- `:36`—`:43`：相邻微秒正序、全等、不同PG小数位、跨毫秒边界正例继续通过。
- `:45`—`:63`：覆盖跨时区等时/正序/逆序以及整秒与零小数等时，直接调用完整实际oracle；不是只测试一份复制的比较函数。
- 独立读取原 red.log：11项中7通过、4失败，四个失败均为Missing expected exception，准确复现上节P2。原green-time为11/11；green-all与实际apply replay均135/135、0 fail/skip/cancel，commands.json保存对应命令与退出码。本审查没有重复135项，也未重跑服务/PG验证。

另执行只读SHA和内存patch应用，退出0；87项本次包SHA一致、preserved-history.json列出的305个唯一历史文件逐个一致；完整baseline应用patch后16文件匹配当前manifest。作者历史检查375是其执行计数，本审查独立报告实际遍历的305个历史路径。原始输出为：

```json
{
  "command": "node read-only time-precision manifest/preserved-history SHA and in-memory full patch application",
  "checks": 87,
  "historicalChecks": 305,
  "reconstructedFiles": 16,
  "runnerSha256": "e2878c7ae3275eaa26e6a7e24a178fae79c9bac38c91d9ecdfbe55d7c348a1e4",
  "oracleSha256": "4c63d3c66764250211866f36ea511668639c95834acfa07357ec5cd5b4e69abe",
  "failures": []
}
```

本结论关闭上轮时间精度P2与本次代码审查门禁，不代表第五轮或真实五场景通过。四轮原实际失败/局部结果及旧review/probe均保持；上节已授权执行的capture SELECT验证不在本轮重复。本次只追加此报告，未改source/原证据/target，未读凭据、连PG/其他服务、启动JVM或执行V5；后续真实执行仍由root重新移交锁并授权。

追加后16份当前source再次只读SHA检查退出0、unchanged=true，oracle仍为本节4c63d3c6值。

## V5 实际五场景独立事后复核

2026-10-20。任务依据 `algorithm-process-live-v5-review-brief.md` 的限定只读授权。本节复核实际结果，不重跑135项或任何业务入口。原V1—V4失败和之前Needs fixes/P2记录保留。

**Spec compliance：APPROVE，V5五个有限场景闭合。** 独立读取五份raw逐行观察、四份afterRecheck和fixture最终请求账，再从唯一V5库取得新的完整只读行并逐行比对。原外部call/fact/binding/attempt/owner/两个snapshot/UID保持，ACK仅新增合法本地no2；非重POST、终态note、逻辑提交、重检后采集、Failed/Running与真实offload要求均得到本轮实际证据支持。

**Quality：Approved，无阻塞问题；保留两条非阻塞启动WARN说明。** 实际结果与冻结runner/oracle和事后PG一致；GET唯一READY对象及10个记录PID/端口的独立只读检查均完成。报告正确限定为有限K8s HTTP fixture配真实JVM/PG/Redis/MinIO链路，没有把第五轮通过覆盖前三/四轮失败或扩大为生产验收。

### 五场景实际核对

| 场景 | call / 原外部attempt | 本地attempt / 提交attempt | run / fact / binding | POST / 终态note / SKILL逻辑提交 |
|---|---|---|---|---|
| complete | 1 / 2 | no1=id2 / 2 | SUCCEEDED / SUCCEEDED / DONE | 1 / 1 / 1 |
| ack-loss | 2 / 5 | no1=id5 UNKNOWN；no2=id6 SUCCEEDED / 6 | SUCCEEDED / SUCCEEDED / DONE | 1 / 1 / 1 |
| failed | 4 / 9 | no1=id9 FAILED / 无 | FAILED / FAILED / DONE | 1 / 1 / 0 |
| running | 5 / 11 | no1=id11 UNKNOWN / 无 | CANCELLED / UNKNOWN / SUBMITTED | 1 / 0 / 0 |
| offload | 6 / 13 | no1=id13 SUCCEEDED / 13 | SUCCEEDED / SUCCEEDED / DONE | 1 / 1 / 1 |

- **逐行身份与重检：** 原 `live-ec8285eb1f44/{complete,ack-loss,failed,running,offload}.json` 中before/after、四个afterRecheck的集合、原完整tuple、两request_snapshot、结果owner、Job UID及终态证据均满足已独立审过的实际oracle；独立新PG行与最终call/fact/binding/node/attemptRows/events/accepted终态note分别完整深比较一致。库内恰5 runs / 5 calls / 5 facts / 5 bindings，没有新业务run/call或第三本地attempt；readonly_retry_count=0。Running没有新增原流程不存在的重检。
- **Complete：** 首条原Job GET在唯一POST前，fixture从无Job返回404，之后Complete、Pod/controller/algorithm日志路径各一次，完整structured为 `{"value":"ok-complete"}`。这是冻结有限fixture的HTTP接收及实际SDK/引擎消费证据，不是真实容器执行证据。
- **ACK恢复：** before为WAITING_EXTERNAL/UNKNOWN，binding UID未采纳；fixtureBefore已固定原UID `bf00e73e-1f87-4682-a7ad-1fad4b54f0cb`，对账后精确采纳，原call/fact/binding/snapshot与终态result.owner仍是attempt5/原owner。原UNKNOWN行不变，本地no2=id6仅消费原事实。终态note的PG时间 `12:00:25.512508+08:00` → EXTERNAL_READY seq7 `.592658` → NODE_STARTED seq8/attempt6 `.600626` → 唯一LOGIC_RESULT_COMMITTED seq9/attempt6 `.609384`，原微秒顺序成立。重检后再次capture无漂移，Job GET总2、POST仍1。
- **Failed：** 原UID保留，可信Failed证据/非重试错误 `NON_RETRYABLE_FAILURE` 与原call/fact FAILED一致；reconcile仍FAILED，connection重检ok=false，未解释成NOT_APPLIED。终态note恰1、逻辑提交0，PG节点无committed_attempt_id。
- **Running：** before及afterReconcile均UNKNOWN/WAITING_EXTERNAL，原UID及SUBMITTED绑定保持；随后只取消本地run/node，最终call/fact仍UNKNOWN，原attempt11也UNKNOWN。终态note与逻辑提交均0，fixture仅原Job查询（本轮jobGets199）和一次POST，无DELETE路径；没有把本地取消解释成Job停止/删除/撤销效果。
- **Offload：** PG唯一artifact `art_378961d017934f0789bfc77f50d40df6`，同tenant/principal/run/call6、state READY、size_bytes=1212。原受控下载已记录完整SHA；本审查另只GET该PG记录指向的唯一自有MinIO对象一次，HTTP200、精确1212字节，逐字为 `{"value":"` + 1200个a + `"}`，SHA `d0150502d84a1181a40bb6d26b8332e63c62f54283f056e0d1891fa866fc1d63`，与PG/原下载一致。该GET有10秒/8192字节界限，不list/写/删；实际fact structured=null、唯一fileRef原artifact，未以READY状态名代替对象内容核验。

### 独立PG、对象与进程证据

独立目录 `docs/evidence/review-algorithm-process-live-v5/` 保存完整 `verify.mjs`、`verify-command.json`、原stdout `verify.stdout.log` 与 `verification-result.json`。命令 `node docs/evidence/review-algorithm-process-live-v5/verify.mjs` 实际退出0、无stderr；没有重放业务请求或启动fixture/JVM。

- PG：只连接 `agent_platform_algorithm_live_ec8285eb1f44`，项目.env凭据仅到PGPASSWORD环境；SQL显式BEGIN READ ONLY、SET LOCAL statement_timeout=5000，PGOPTIONS另固定default_transaction_read_only和5秒，客户端10秒/2MiB界限。`readonly.sql`、`readonly-command.json`、原stdout与空stderr已保留。迁移全部success；当前最终行与五case最终capture相符。
- 对象：`object-get-result.json`记录GET200/1212字节/原SHA/归属和界限，`offload-readback.json`保存实际回读字节；没有输出签名或凭据。
- 原10个进程记录的精确JAR/SHA/创建时间/ownershipVerified.listenerPids与各自PID一致，全部stopped=true且原final无PID/监听。独立只读CIM只筛这10PID，TCP只筛所记端口；当前10PID全部不存在、端口无监听。保存 `process-inspection.ps1`、原stdout/空stderr及 `recorded-identities-and-ack-time.json`；未停止/接管任何进程。记录端口61107顺序复用于两个case，原两child均已分别停止，独立最终端口检查仍为空。
- 独立SHA：V5 summary列出的41项原证据全部匹配；16份冻结source、2份固定JAR和305份历史证据逐项匹配，source-before/after JSON相同。固定runner `e2878c7a…348a1e4`、oracle `4c63d3c6…4e69abe`，制品仍为原已审engine/skill SHA。本轮未改source/target/原raw。

### 非阻塞观察与证明边界

Minor日志噪声：`live-ec8285eb1f44/skill-registry-0.out.log:26` 与 `engine-runtime-1.out.log:29` 各有Flyway `schema already exists, skipping` WARN（42P06）；原日志保持，不称输出完全无WARN。对应全部迁移success、空stderr和业务事实一致，没有发现业务错误或清理错误被隐藏；该提示不影响本轮五场景结论。

本次通过只证明单轮、单次有限场景下的真实私有JVM/PG/Redis/MinIO及有限K8s API路径，不能签生产K8s/实际容器/RBAC/IAM、多副本或多次恢复普适性、容量、完整G2、17功能/34任务/30验收。此前原失败不作改写。本次仅新增独立review证据并追加本报告，没有业务POST/重检/重试、真实K8s请求、新库/DDL/写SQL、JVM启停、Maven/package或源码更改。

本节追加后再次核16份source SHA未变，退出0；独立证据目录的 `review-evidence-sha256.json` 固定本次review脚本、原始输出和查询/对象/进程证据的SHA。
