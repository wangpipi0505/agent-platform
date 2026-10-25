# 算法 runner：外部原代与本地恢复尝试的限定验收

2026-10-20，依据 root 唯一入口 `algorithm-process-runner-attempt-scope-brief.md` 与独立 memo `algorithm-live-attempt-scope-review.md` 实施。本轮仅修改 runner 的只读PG采集/断言及新增两个纯Node文件；原31项检查的源码与语义保持，没有改生产、962固定JAR、原4秒/仅offload15秒期限、HTTP/初始提交/对账/重检请求、fixture、环境、健康、异步PS上界/UTF8/归属或收尾。没有接真实凭据、运行JVM、新库、PG/MinIO、Maven/package或第五轮入口。

v4及前三轮失败、完整原始log/PG/process、全部旧patch/manifest/SHA、独立memo和brief均保留原字节。v4仍是退出1，complete通过而ack-loss未走完原runner，后3场景未执行；本次有限通过不改写该实际结果。

## 依据与字段澄清

已完整读取独立memo及brief，并定点读设计快照245—251、1003—1006、1750—1757，复查RunService/TraceStore/AgentDriver/ToolCalls相关源码以及AlgorithmAdapter终态字段。节点认领即记attempt，tool_call登记身份独立；`resumeExternalFacts`将已确定原call的等待node重新置READY，新的本地claim消费原存储结果。v4旧报告将方法误称 `recoverExternalReady`，实际为 `RunService.resumeExternalFacts`，本报告澄清，原报告不改。

节点key是sha256("node:"+nodeId)，外部call key是sha256(runId+":"+nodeId+":"+seq+":"+versionId)，新oracle分别验证，不相互代替。terminalEvidence按实际externalJobId/externalJobUid/namespace/apiSha256/identitySha256/condition核验；Running UNKNOWN不要求终态证据。节点output与原structured比较只要求ack-loss成功恢复，不强加于Failed/Running/structured=null的转存。

## memo六项要求与实现

| 要求 | 对应采集及门禁 |
|---|---|
| 1. 逐行快照与明确集合 | capture新增calls/facts/bindings数组、完整node、attemptRows排序行、accepted终态terminalNotes行、完整带PG时间events；保留整数计数。单数别名仅从数组派生，长度必须各1，开始对账前先检查集合唯一。另存tenantRuns身份集合，拒绝重检另起业务run。 |
| 2. ACK精确本地恢复 | 新私有测试oracle `tests/algorithm-process-attempt-scope.mjs` 只对ack-loss的before UNKNOWN/WAITING、原no1 UNKNOWN/零提交条件接受恰两行。原行整体不变，新no2成功、同node/key、且为committed_attempt_id。NODE_STARTED逐行映射；终态note时间≤EXTERNAL_READY≤新NODE_STARTED≤唯一逻辑提交，事件seq严格有序。其他四模式仍恰1行，拒绝第三行或错提交。 |
| 3. 原外部身份固定 | 逐字段钉住call/fact/binding及两个完整request_snapshot，核snapshot内部attempt/leaseOwner/run/call/node/seq/version/key/args；结果owner、终态note/payload/result.owner均保持原外部代与owner。继续DEBUG/nonMock、readonly_retry_count=0。node当前owner允许合法本地重新认领，不覆盖外部owner。 |
| 4. UID正确首采纳 | fixtureBefore非空原UID/Job为基准，ACK前PG UID可NULL，首次后必须精确采纳该UID；重检后仍同UID。绑定API/namespace/Job/identity/contract及实际终态证据六字段全部关联。 |
| 5. 一次业务和终态/逻辑采纳 | run内call/fact/binding各1，accepted终态note恰1（Running为0），UNKNOWN note不计终态，SKILL逻辑提交恰1（Failed/Running为0），fixture各观察POST1且原Job路线、无认证或拒绝计数。 |
| 6. 重检后重新采集 | 原有四个controlledCallId重检请求之后立即保存afterRecheck并重新执行全部oracle；另对本地attempt行、终态note、fact结果及node提交/输出字段与after比较。Running原流程无此HTTP重检，本轮不增加请求，仍验证原取消后的after集合与UNKNOWN事实。全部原输出、UID、MinIO、关闭检查继续执行，最终才标passed。 |

采集仍走原显式begin read only，不SQL seed业务事实。修改没有替换实际SDK、接口、恢复或fixture路径，也没有因等待恢复扩大任何预算。

## 实际行为红与有限负例

先在原257e源码上提取实际 `assert.equal(scenario.after.attempts,1)`，用原v4 after真实attempts=2运行，合法本地恢复预期可接受却明确 `2 !== 1` 失败；`red.log` 实际1项失败/退出1，原源码/测试红快照保存。不是缺导出、语法或假异常的红。

测试存档输入从v4真实ack-loss/fixture最终记录和专用库只读行复制，原始字节不改。真实v4未采node-before及afterRecheck；测试对缺失node-before字段做明确合成补全，afterRecheck是after的合成副本，其他四模式正例也明确标为合成。它们不回写原v4，不声称是第五轮或真实重检观察。实现初期 `green-first.log` 曾因测试补全未填新tenantRuns字段出现TypeError，随后补齐；该日志保留，不作为行为红或通过证据。

新增93项纯Node检查覆盖：合法两行ACK及合成重检、其他四模式仍单行；第三attempt、原UNKNOWN行被改写、节点key误用外部key、错committed attempt/事件顺序/PG时间/逻辑归属/output；call/fact/binding身份逐字段、两snapshot逐字段及任意额外字段漂移；终态实际六字段、错误首次UID、新call/fact/binding/run、POST2、重复终态note/逻辑提交、note/result owner换代，以及仅afterRecheck出现的本地行/外部owner/UID/绑定降级/提交引用/输出摘要等漂移。均必须拒绝。

实际全覆盖命令：`node --test tests/algorithm-process-runner.test.mjs tests/algorithm-process-runner-fixes.test.mjs tests/algorithm-process-runner-redis.test.mjs tests/algorithm-process-runner-async.test.mjs tests/algorithm-process-runner-async-cleanup.test.mjs tests/algorithm-process-attempt-scope.test.mjs`，最终124/124通过、0 fail/skip/cancel、退出0，原始green-final.log。原31项源码SHA保持上轮值，测试没有读取真实.env或联系服务。

## 冻结、重放与限制

baseline保存上轮十一交付文件，SHA逐项匹配原manifest；baseline-absence.json记录新增oracle/测试/本报告不存在。frozen保存最终十四文件；新 `algorithm-process-runner-attempt-scope.patch` 为完整真实baseline→frozen差异，仅改runner的采集/断言和新增这三文件。旧patch/manifest/报告不覆盖。

freeze.mjs实际复核上轮冻结/重放/证据/patch与历史manifest、完整v4摘要中的全部证据、独立memo/brief及设计/相关源码SHA；新manifest固定本次红绿和输入。新replay复制baseline，实际git apply --check与apply退出0，十四文件逐字SHA与frozen一致；只读复制原fixture及本轮存档测试输入后，实际语法检查九文件并执行完整124项覆盖。具体命令和输出在replay.log，SHA在 `algorithm-process-runner-attempt-scope-sha256.json`。

本轮只建立本次单次有限恢复的严格oracle，不宣称全系统attempt永远最多2，也不证明实际集群/容器/RBAC/IAM、容量或全部G2。PG采集SQL未接真实数据库执行；后续必须经原runner审查者核代码，再由root单独授权第五轮取真实五场景/MinIO/进程与事后只读证据。本次有限通过不表述成17功能、34任务、30验收或生产环境签收。
