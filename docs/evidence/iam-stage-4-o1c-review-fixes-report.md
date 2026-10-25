# O1c I1 提前拒绝响应窄修报告

状态：实现与定向验证完成，待 root 有限 delta 审计及原 reviewer 复审。唯一工程 G:/207/项目/算法重组工具/代码/agent-platform；无 commit、package、deploy、automation 或时间表，未变更原设计、四契约及八索引；T01 仍是唯一全局完成任务。

## 实现与边界

仅修改 PublishedRunHttp.java 与 RunObservationGetTransportTest.java 两份授权源码，无新增/删除源文件。必要源码仍585（backend393/frontend192），delta2。整个 response handler 增加 IOException/RuntimeException catch：只对新 observation policy，在 Apache 拥有的 response 自动 graceful close 前取消实际 installed handle，再原样抛出异常。覆盖状态、unsafe header、Content-Type、entity/content 获取、declared Content-Length、最终本地 proof/deadline 和严格错误解析的失败出口。保留 InputStream try-with-resources 内的 read-loop catch，使读中失败先 cancel 再关闭输入流；两层 catch 均检查已有 cancelled 状态，避免内层取消后再逻辑取消一次。

LEGACY 原处理分支及全局 Cancellation 公共行为没有修改；新增外层 catch 对 LEGACY 仅原样 rethrow。旧 GET/POST 的 status/code 映射及外层 finally 的 request.cancel、active.remove、release 均保留。不重试、不二次请求、不转发不安全响应头或错误正文，无新增 API/抽象/依赖。自审完整 source.patch，仅此两文件；原八方法与 Peer 正文未改，Peer class SHA 因新增方法造成的源行号变化而变化，最终十方法均真实执行。

## TDD 与有限验证

以下每条命令均由原封不动 .tools/run-iam-module-tests.mjs 以 Java21/offline 执行，shell 显式工作目录固定本工程。stage 子目录保存完整585 pre/post manifest、实际 Running XML、原 raw log；own stages 子目录分别保存两变更文件 pre/post 物理文本。executedSource 与 finalSource 分列于 commands.json，未把历史最终文本冒充执行文本。

RED（生产仍为原始16534B文本）：

    node .tools/run-iam-module-tests.mjs iam-stage-o1c-fix-red01 admin-common,platform-gateway RunObservationGetTransportTest#declaredExcessiveLengthAbortsBeforeClosingStalledBody,RunObservationGetTransportTest#preBodyRejectionsAbortBeforeClosingStalledBody

实际 Running transport，exit1，tests2/failures2/errors0/skipped0。独立方法各首行失败：Q unknown599=2042ms、Q declared8193=2003ms，均超过 under-one-second oracle；各一次真实 accept/installed handle，timer cancelledHandles=1，而 cancelled=false。两循环均在首个失败停止，故不声称 RED 已执行其它行。两 raw peer thread 均关闭。原始失败 XML/log 保留，不补造缺失历史。

GREEN（精确 final585，同一命令）：

    node .tools/run-iam-module-tests.mjs iam-stage-o1c-fix-final01 admin-common,platform-gateway RunObservationGetTransportTest,GatewayRunStatusChainPostgresTest#realGatewayQueryAndStatusReturnOriginalBytes,GatewayRunStatusChainPostgresTest#partialOutputNeverAppendsOrRetries

exit0，实际 Running 两类，transport10+chain2=12方法，failures/errors/skipped均0。十个 early rows 覆盖 Q8193/M2097153 declared length，以及每个 Q/M 的599、401、Set-Cookie、text/plain；各0–1ms、一次 accept、一次 installed、一次 cancelled handle、cancelled=true，早于两秒 hop timer。已有 incremental chunk-overflow=31ms、一次请求/取消。58个 raw peer 均有 close/thread-join assertion；完整逐方法结果见 xml-method-results.json。

原真实 chain positive 原始 UTF-8 字节相等867B/SHA d3c17318a13de5d28562399411e0962519b6d120a815d499ef95d3de0d1148a6；Q/M固定顺序、各一次，零dependency/worker。partial 实际只输出128B并抛已有 IOException，证明不追加/不重试；其 WARN 与 Spring suppressed response exception 是预期输出，不能称日志无警告。编译有现有 deprecation 提示，测试无错误。没有重跑 common9/chain14/旧POST/SESSION大套件或其它已绿检查。

## classpath、已知继承 target 偏差与资源

原 chain Child 硬编码读取原 own engine locator；先保存本次 own locator 字节副本，核 exact ordered engine test/classes、common test/classes四输出及130个 dependency-only本工程m2项、212 unchanged sources。只按继承清单读取有限409输出行，无旧 target tree walk。

首次核验发现5个旧 GatewayPublishedEngineFixtureMain unused class 当前 bytes/SHA 与继承行不同；后续有限比较发现另一个旧 RunObservationStatusHttpPostgresTest$2$1.class 已不存在。403行匹配，包含本次实际使用的 status fixture、common及engine业务输出。两个辅助失败分别保存在 own locator-check01/02-failure.json；没有为了记账重复成功的编译/测试/回放。root明确授权使用403匹配输出运行本次两个控制，不恢复旧类、不新编译engine。

独立 inherited-target-correction.json 披露5 modified actual快照+1 deleted absence，发现于本轮final之前的locator核验，属preexisting、具体发生时间未知，不能归属本轮编译。原2005清单保持冻结，只作历史逻辑库存；更正后逻辑2004。再叠加本轮实际common/gateway compiler createdFiles、inputFiles/metadata、已有test-fixture jar与实际Running reports，共245 candidates、7 modified delta、0新增，最终逻辑2004。不是声称重新核验了所有历史target物理文件。5偏差 actual和7 own delta均有own/evidence两份独立快照；缺失项只保存精确路径absence证明，不生成替身。分类见 target-candidates/composition，旧原件未改。

真实 engine child PID45524、端口63381、原PG数据库已存在，正常STOP exit0/alive=false；parent context/http/authority闭合。现有两个控制的断言核 observation前后fixture事实数不变；本次未另作整库原行before/after审计，也没有drop PG或碰无关进程。Child.close硬编码日志路径由root授权临时生成单个fresh-PID日志后，用同一PowerShell Move-Item -LiteralPath移入本次own；已核原inventory无exact路径、目标不存在、resolved两端在指定工程目录、移后原路径不存在、bytes/SHA保持。迁移记录及125714B child log见 child-log-migration/resource-closure。

## 回放、继承证据与封存

私有无commit bare index，以已验证的原585基线（两份before物理原文+583当前未变行）生成两个文件的source.patch。首次实际git apply --check exit0、apply exit0；补丁后逻辑585 names/bytes/SHA全相等，未在apply后把最终bytes复制进replay；真实工程Git无mutation。完整命令、stderr和replay清单均保留。

继承原报告18660B/SHA ce6b0672dcb7b0615ce0d5bd003f1ec85aed092ebb6a905d850f277c08fd90c6、原handoff4600B/SHA b7cef80924945929fb1a8e1b16b44af7bc26dcdb12fb536f62fa5b48955abce2、root PASS2192记录9953B/SHA 7a9868548f23e9d25e21a96555f822fd7795b3e13f5854d8d71ae763c66e30cc；不改原件。原31新+3旧来自15命令，26/31 finalSource误归属由root独立supplement/correction澄清，本次只读correction顶层schema，未重审全部映射或补写历史。

必读全文至EOF并核身份：implementer-prompt.md；review-fixes brief7415B/SHA 77df1fffc85c21fee98ff5031bdeab988219f2b8bfe8f39fb883fedd24c8aff2；review10355B/SHA 780ab201d62ee3339f034893b962ff5029adc97f6d035e59ff226a0829a118e8；supplement3304B/SHA 84bc10c7f334023f397c8826963acfadbb6f086f6a6c24f7ef0587571e14a7c0。适用AGENTS/implementation-brief/contracts README、SDD/TDD/receiving review/systematic debug/Java/ponytail/verification技能已读取使用。未访问相邻旧工程配置/缓存/存储/进程；一次文件名定位误把本工程.tools纳入rg候选，仅显示匹配名字，未读取其内容，后续均用精确范围。

own inventory枚举仅本次.local/iam-o1c-review-fixes、docs/evidence/iam-stage-4-o1c-review-fixes及本报告，另显式包含runner产生的两份.local stage log与其空fresh-secret目录状态；避免自引用，inventory/handoff/seal三文件不纳入inventory自身，分别由seal/移交返回SHA覆盖。必要source清单排除own/evidence/private Git/replay/cache/probe，不新增业务source。所有源/target/backend-class/local/evidence/report写权封存交还root，待有限审计及复审。

边界：这里只证明两个源码窄修及上述真实有限链/停滞peer，不主张总heap上界、即时撤权、blocked-peer物理hard-close、完整Boot、真实外部IAM/G6E/F或整个全局任务验收；原设计及四契约不变。
