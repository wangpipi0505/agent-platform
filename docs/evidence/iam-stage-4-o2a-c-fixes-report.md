# O2a C I1/I2 有限修复作者报告

状态 DONE_WITH_CONCERNS，作者完成，尚非 Accepted。身份 /root/iam_o2a_c_fixes；唯一 workdir G:/207/项目/算法重组工具/代码/agent-platform。仅两份生产文件及既有 Lifecycle/Capacity 测试改变，delta4；Http 仅作回归未改。没有 commit、部署、POM/runner/schema/迁移/catalog/全局安全/common/Controller/ChunkBus/MaterialScopeStore 修改；未访问相邻旧工程，未碰 A/B/Initial 库。原 C report/evidence/review/inventory 与 root audit 原样冻结。

## 输入及修复内容

正式 fixes brief、原 C brief、排队补充与独立 review 全文已读，物理身份见 inputs.json；仅处理 Important I1/I2。采用 receiving-code-review、systematic-debugging、TDD、verification、ponytail。原 C source600 作为冻结但尚未 Accepted 基线；原 finite root audit5/2373 与原29测试均仅继承历史事实，没有重跑或声明已在本次 epoch 验证。

I1：WindowEmission 新增 C 专属 consume(action,beforeRelease)，由原真实 EventEmission 持有同一 lease/ledger。先声明 actual active 所有权，再检查入口 guard、同步 callback、最终 guard；任一正常或异常退出都进入 beforeRelease，再 cleanup、清 active 并 finish 原 B lease。beforeRelease 自身返回/抛出后仍有嵌套 finally 确保一次原资源释放。所有长 write/complete 均不持 holder 或 emission monitor；close 看到 active 仅置 closing，不能提前回收。

C 的终止 complete 从 callback 内移到该真实退出接缝：正常终态/reset、普通失败、失效、耗尽第3次 supersession 均在 B finish 和 C frame finally 前执行 complete。仅明确 Superseded、未终态、仍有2次以内重试额度且 holder 当前仍 live 时跳过 complete；旧 consume 实际退出、close、B/frame 归还后才完整重读。原 getter/flush 真实续核和有界重读成功回归保持通过，未把 healthy retry 变成提前完成。普通 B readNext 和 EventEmission.consume 块在 LF 规范化后逐字相同，具体块 bytes/SHA 见 b-unchanged.json；没有通用 ignoreGuard 开关，也没有把 Servlet 职责加给普通 B borrow。

I2：fresh RUNNING 容量错误在真实 output.isCommitted 分流。尚未提交以 failure.status（本分支413）、既有 EVENT_LIMIT/BUFFER_LIMIT code 和固定“事件观察不可用”通用 JSON 信封响应；不输出内部 dimension/异常文本。已提交时才构造原有限 reset。两个分支都以新 currentCapture、check/requireLive 保护 getter/write/flush 前后，并在仍持有 C frame/slot 时 complete；失效 holder 只关闭。opening 成功预检仍丢弃，既不授权异步输出也不提前提交200。

## 真实失败、GREEN 与测试分工

入口和出口两个 guard 用例均使用真实签名票/JWKS/授权链、生产 Controller/service、真实 PG reader 和同一真实 lease。测试只以 Mockito spy 拦截 readWindow 返回时机；readWindow 本体、真实窗口 consume 的入口/action/最终 guard/finish 都执行真实代码。出口包装仅用反射把真实窗口 consume 的 action 包一层，在 action 实际返回后关闭其真实 holder；所有其它参数/方法委托原真实结果，没有 stub 权限/guard/finish 或返回假窗口，没有公开 proof/Actor/Retained 正例。

两连接均在真实 AsyncContext.complete 被受控 Servlet 包装阻塞时，shutdown/stop 后仍占满原 B2 permits、C2 slots、2 frames（可用60）。首个 complete 实际返回后恢复 B1/slot1/frame1（61），第二个退出才恢复 B2/slot2/frame2（62）。通过真实 readNext/subscribe/frame reservation 检查，不读取或清空 owners，不取消 Future，不用 Servlet 通知冒充实际退出。最初两次 RED 的意外成功 reader probe 只用于暴露提前复用；随后在生产修改前将 probe 改为 try-with-resources，使负例意外获得的真实结果也可关闭，不由测试失误遗留 lease。原 RED raw/XML 未改，最终 GREEN 使用已修正 probe。

未提交413用例以受控 executor 暂占两 worker，真实 opening 预检成功并排队且 write0 后，由真实 writer append 262145B公开编码 event；释放 worker 后 fresh 第一次实际 RR 触发 EVENT_PAYLOAD 容量。complete 阻塞时核 B permits 已由失败读取正常退出、C frame/slot仍保持；实际 HTTP 最初为200形成 RED，修复后413、application/json、EVENT_LIMIT及固定通用 message，无 reset/内部细节。原已提交精确262144/+1 UTF8 event 用例仍通过，保留200有限 EVENT_LIMIT reset 语义。

每次实际命令前将原600条冻结身份继承，仅 fresh hash 批准5路径保存完整 source epoch；命令退出后立即复制 raw、实际 Running 所对应 XML/txt、command/cwd/exit/epoch。所有阶段均为真实功能 RED/GREEN，无 compile/setup failure 冒充 RED；各 XML testcase 由实际报告枚举（含 self-closing），不以源码顺序或编译输入替代运行。

| phase | exit | tests | failures | errors | source epoch SHA256 |
| --- | ---: | ---: | ---: | ---: | --- |
| red-guard-entry | 1 | 1 | 1 | 0 | ea0ff71a0194aac9a904d8b87ca23df77cab275147d097edfa1f8192936fe821 |
| red-final-guard | 1 | 1 | 1 | 0 | ea0ff71a0194aac9a904d8b87ca23df77cab275147d097edfa1f8192936fe821 |
| red-uncommitted | 1 | 1 | 1 | 0 | ec070e52fbaf2962c3a8fbc2186f1738bcffd9991ebc88060d3fb6bdfb51c0f1 |
| focused-fixes | 0 | 3 | 0 | 0 | b10eaecf0c03d79083609efed701f46c78e5670252264fe80406b9957c9c217f |
| final | 0 | 24 | 0 | 0 | 44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54 |

最终精确命令：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2a-c-fixes-final engine-runtime PortalRunEventsLifecycleTest,PortalRunEventsCapacityTest,PortalRunEventsHttpTest,RunEventReaderBudgetTest#emissionAndBorrowCannotBeMintedPubliclyOrExportLargePayloadReferences,RunEventReaderBudgetTest#getterClosureExpiryAndGenerationFailureAreLocalAndSticky
```

最终唯一适用集合实际4类/24 methods/0 failures/0 errors/0 skipped，epoch固定后仅运行一次。Lifecycle11、Capacity5、Http6，以及 RunEventReaderBudgetTest 两个具名无PG方法；旧B Postgres fixture硬绑B库，按本次禁止触碰A/B/Initial库的要求未执行。真实 readNext/permit 检查在独立C库 fixture 上进行。原29/旧B65/旧B整类/全后端未重跑，不把旧成功当本次相同path新epoch缓存。

| actual Running class | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| cn.agentplatform.engine.infra.PortalRunEventsCapacityTest | 5 | 0 | 0 | 0 |
| cn.agentplatform.engine.infra.PortalRunEventsHttpTest | 6 | 0 | 0 | 0 |
| cn.agentplatform.engine.infra.PortalRunEventsLifecycleTest | 11 | 0 | 0 | 0 |
| cn.agentplatform.engine.infra.RunEventReaderBudgetTest | 2 | 0 | 0 | 0 |

最终 raw 与各实际 XML/txt位于 .local/iam-o2a-c-fixes/final/，result.json逐一列出物理身份；test-results.json含每阶段精确方法。唯一未改 runner在固定Java21.0.12+8/Boot3.5.0及原依赖下运行，仅独立PG agent_platform_iam_o2a_c_events_v12、fixture各自新run。真实 timeout/error 的受保护 /error 拒绝栈、既有 deprecation/unchecked 与 Mockito/ByteBuddy/JDK动态agent提示保留；不是零诊断日志。新增 dumpstream 单独归档 diagnostics.json/diagnostics，未为工具噪声改安全/POM或重跑成功阶段。

## Source、有限 target 与首次私有 patch 重放

原 C source600/target逻辑2096直接继承。仅批准4 delta freshhash，对齐本次最终执行 epoch，保存 pre/before/after/source-before/source-after；其余596条继承既有冻结 identity，没有全树重审当前source。最终source仍600，target overlay 494、逻辑 2096，只来自两个模块四个 compiler createdFiles/inputFiles、具名资源、common test-fixture JAR和实际 Running4类 XML/txt。没有扫描旧大 target，不把继承历史逻辑target当干净classpath；新增dump不纳逻辑target。

| changed file | bytes | SHA256 |
| --- | ---: | --- |
| engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java | 35722 | 6149d3b6ab7009117466f7c7153bfb6d5dff41f48865f4c214486da3689a434f |
| engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java | 18557 | 564d5e760da17a0a2e0e2fe53d358ef62659695ca0ad592a1342cd05d1f950d8 |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsLifecycleTest.java | 22051 | 3ffc0280107643ad942557f1eecf5a6df5da760ee9bb5979603deaf973f3fbe8 |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsCapacityTest.java | 8262 | c51724767e76a21f0393016058c9b0fae38a178dc98748f21dd056846569bc02 |

新私有 replay baseline从原 C已物理核过的 private-replay逐600具名复制，未重跑原历史审计。仅私有 Git init/index，无 commit；4文件binary/full-index raw patch生成后恢复before。首次真实 git apply --check exit0、首次真实 git apply exit0；apply后没有源码copy。私有600名称/bytes/SHA逐项与最终 source-after一致。每个成功物理阶段写journal/hash-cache，metadata收尾复用缓存，不重复成功测试、PG或replay。

- .local/iam-o2a-c-fixes/source-after.json: 114375B / SHA256 44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54
- .local/iam-o2a-c-fixes/final/source-epoch.json: 114375B / SHA256 44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54
- docs/evidence/iam-stage-4-o2a-c-fixes/source-delta.json: 2320B / SHA256 e07cb87910328ae40e7881d6178ba61a43089e81dfb4054170778ca69a7b7ee0
- .local/iam-o2a-c-fixes/target-after.json: 435939B / SHA256 65646da310fefd754a22b1284854291de3447551ff801a2b8da9f150473eebc9
- .local/iam-o2a-c-fixes/target-overlay.json: 108880B / SHA256 f5641971be3c0678502aeec1d263b443c28afd706999e550271b7c2b7e2cf542
- docs/evidence/iam-stage-4-o2a-c-fixes/test-results.json: 28299B / SHA256 dba04efb690ca339c70ad0cc64eef2ce45d0003e80416e09c7a72526f85f10ae
- .local/iam-o2a-c-fixes/replay-source.json: 114375B / SHA256 44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54
- docs/evidence/iam-stage-4-o2a-c-fixes/source.patch: 18600B / SHA256 4be741103980702d48e47cd20768a949850d29cddda017b62db898e20e821b39
- docs/evidence/iam-stage-4-o2a-c-fixes/replay-commands.json: 24201B / SHA256 cada24ffb0e86b83ab046b085d2e3b773f7db6ddd1f5a44e206a2ec6697328cd

## 冻结交还及保留限制

handoff.json 与 own-inventory.json将报告、local、evidence、自有两个helper以及私有Git全部纳入，sole self-exclusion只有inventory自身。runner原始日志/可能的自有secret目录以具名映射移入本任务目录，未修改未改runner。封存后交还全部source/有限target写权，随后不再修改；仍待独立review/root有限新审计与Approved后八索引闭环。

M1原工具噪声、M2原早期五阶段缺XML/完整epoch仍是历史事实，没有补造或重跑。原终态DELETE仅证明fixture保留清理后的PG观察，不证明production retention job或active-run清理兼容；本修复未新增DELETE/schema/history/state修改。范围仍限engine一跳PORTAL/pure FLOW，Gateway/O2b、CHAT/chunk/skill/model、生产全IAM和socket物理强断/撤权SLA均不在完成结论内；已进入底层的write/complete不能被逻辑stop物理追回。
