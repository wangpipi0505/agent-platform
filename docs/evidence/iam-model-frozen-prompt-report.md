# Frozen initial Prompt：共享 Spring AI 原生转换实施交还

状态：NOT Accepted。本项完成窄源码实施与有限验证，独立评审和验收归根。早期 Python 子进程守卫缺口使本轮不能宣称完整执行合规。

实际缺陷是冻结初始请求已包含 frequencyPenalty=0.0/presencePenalty=0.00001，而真实 ModelCalls 的 Spring AI options 丢失这两个值。现在同一 package-private `toPrompt(JsonNode,String,int,boolean)` 负责全部既有消息、tools 和 options 转换，真实 `call` 使用它返回的同一个 OpenAiChatOptions 作为 defaultOptions 与 Prompt options。call 已计算的模型名/有效输出值原样传入；计量、额度、凭据、熔断、拦截器、HTTP 与发送流程没有接入新的 private 入口。

唯一生产修改为 ModelCalls.java；新增三份模型测试 FrozenInitialPromptFixture/Test/HttpTest。完整671→674：1修改、3新增、670相同、0删除。source manifest 133708B SHA256 `ff96b04ef3131a95552d84545c770be8066012057577fcf7fb6a70959b9e196d`；before671与实际 LIMITED Accepted meter基线 SHA256 `6d04b36892d173f8193164da8f1a3c3729d5ccf8677fbb039a0824ae786bb39b` 相同。protected8与 checkpoint-local-meter-docs-before.afterBytes/afterSha256 逐项相同。未编辑 POM/config/migrations/common/engine/契约/web。

## 转换与身份边界

新增 frequencyPenalty/presencePenalty/seed/stop 的实际原生映射。temperature 范围[0,2]、topP[0,1]、penalties[-2,2]；拒绝非数值/非有限值。seed须整型且可表示为 Integer；超出 Spring AI1.0 builder.seed(Integer) 范围时409 MODEL_REQUEST_OPTION_UNSUPPORTED，绝不截断。stop为文本或至多4个文本的数组；无效形状400 MODEL_INPUT_INVALID。这些严格类型/范围校验同时作用于共享 legacy 转换，替代其原 temperature/topP.asDouble 的宽松强转；先前被忽略的 native字段现在会映射或显式失败。未受影响的 JSON object/schema、legacy assistant/tool/function配对、tools以及工具自动执行=false保持现有实现；streamUsage仅流式。

`frozenInitialPrompt`只接受真实 VerifiedFrozenModelBinding及原始捕获bytes。入口/纯工作间/出口均约束 live proof、当前frame/thread/expiry以及2s单调协作预算，中断返回503且保留标志。有效proof下bytes仅克隆一次，范围1..2097152；JDK SHA256检查RAW副本等于proof.requestSha256后才严格UTF8 decode/解析。原始SHA不被框架wire或数值重拼写替代。模型与输出冻结事实仅取proof.frozenMetadata的防御副本；输出优先级精确为 params.output.maxTokens > params.maxOutputTokens > metadata.maxOutputTokens，验证正整数<=冻结上限。topK或非空sampling仅private路径显式409。private当前accepted profile仍只有system/user和空tools。无proof403、原始SHA不符403 MATERIAL_SCOPE_MISSING_OR_CHANGED、bytes边界/UTF8错误400、时间/中断503 SYS-MODEL-503。代码未新增SQL/IAM/SecretVault/tokenizer/ModelCalls实例工作/线程/cache/proof。

返回Prompt/options是可变普通数据，测试也展示改变prepared model后framework wire改变；它不是允许发送的proof。未来真实发送仍须独立重查current/payload/labels/冻结endpoint/实时租户凭据/实际wire计量准入/持久化snapshot。没有新增DISABLED runtime kill、通用1.3校准、provider JSON扩展或新public wiring。

## 真实失败优先与最终方法

先抽取等价旧转换并接回call，private准备骨架先可构造真正正例；第一次可执行RED为3方法、3断言失败、0error/skip，Maven10986ms：同步及流式actual encoder均已触发特定离线sentinel，随后发现frequency_penalty缺失；真实producerHTTP路径同样缺失。有效原bytes的positive先成功，单个ASCII角色字节变更进入旧message converter后抛IllegalArgumentException，而非要求的解析前403，证明当时缺少rawSHA守卫。该RED不是编译/启动/超时失败。初次testCompile的IOException声明编译错误另行保留，未算RED。补齐映射+SHA后同批及其余新9方法全部GREEN（5+4方法，8760/9023ms）。继承proof的stale等保护从首轮即存在，没有伪造它们的RED。

最终行为源码在 final-source 冻结后只执行约定受影响20方法，Running-only XML精确匹配各selector，方法无重复、0failure/error/skip；没有旧73或额外闭合binding suites。

- `group-1`：4个实际方法，exit0，8615ms；selection/Running-only XML/actual 位于 `final-runtime/group-1/`。
- `group-2`：4个实际方法，exit0，12912ms；selection/Running-only XML/actual 位于 `final-runtime/group-2/`。
- `group-3`：8个实际方法，exit0，19682ms；selection/Running-only XML/actual 位于 `final-runtime/group-3/`。
- `group-4`：4个实际方法，exit0，17097ms；selection/Running-only XML/actual 位于 `final-runtime/group-4/`。

新9方法覆盖actual call/stream的model/max_tokens/temperature/top_p/penalties/seed/stop/response_format/CJK+nested schema、stream_options.include_usage与nonstream差异；legacy tools及assistant/tool配对、internalToolExecutionEnabled=false；scientific原始bytes身份、另一真实请求、caller数组修改、普通Prompt可变性、bytes边界、frame切换/retired/closed、跨线程、真实可撤销clock过期、中断及ambient本地事务；真实consumer接受的int64 seed/topK/sampling拒绝，native数值/stop形状拒绝；两种有效output优先级与text stop/JSON object。其余11方法为既有meter8+meterHTTP1+bindingConsumerHTTP2，全部同一final source epoch。

未制造不可达coverage：真实consumer在mint proof前已拒绝无效UTF8及不合格输出，因此匹配proof SHA的无效UTF8、缺失maxOutputTokens后冻结fallback、非正/溢出输出private分支无法通过当前真实fixture独立到达；实现有守卫，未用反射/fakeproof覆盖它们，也未声称实测这些分支。2s为协作检查，不是对JDK解析工作的OS级强杀证明。

## 框架编码与真实来源

Fixture组合未改 FrozenInitialInputMeterFixture，真实ModelStore更新自有entry再freeze，捕获acceptedCanonicalRequestUtf8先于binding.verify退休旧input mapping。选中的LOCAL binding与继承setup binding明确分开，身份留存在final-runtime/model/local-meter-proof-observations.jsonl。HTTP复用未改FrozenModelBindingProducerFixture的真实PG发布/prepareInitial、正常CA/SAN HTTPS、签名接收及binding验证；观测selected bindingId=2，真实producer requestSHA `1224e9d6c147041da42d568d74a71e843d1c9b12acb894950114c7476527c8a9`。它实际产生penalties，未声称它产生额外seed/stop。

offline.invalid仅是测试API目标；同步MockClientHttpRequestFactory在actual RestClient body writer写完后抛唯一Typed EncodedBeforeTransport；流式test-only ClientHttpConnector调用actual request callback及WebClient encoder，DataBufferUtils.join上限2MiB，finally释放buffer，再同sentinel。两个分支均要求sentinel本身/真实cause相同，其他异常失败；没有网络delegate、生产FrozenRequestConnector、createRequest反射、org.springframework桥、手工ChatCompletionRequest JSON或伪provider响应/usage。wire bytes与identity单独保存，原始0.00001与框架wire科学计数属于不同摘要域。

窗口先对自有provider/tokenizer作正常HTTPS204正控并重置计数，随后private preparation+actual offline stream encoder期间SQL=[]、IAM delta0、HTTP0、所有6张model业务表内容摘要相同；见prompt-window.json和对应wire。取业务快照的观测SQL位于转换窗口两侧。该结果不等于证明任意未来代码都不读secret；当前private静态调用链及窄diff补充证明未引用SecretVault/store/ServiceTokens/tokenizer。框架编码不代表网络发送、provider成功、真实token标定或额度预留。

只读JAR签名检查实际用了缓存SpringAI1.0/Spring6.2.7的javap，验证Integer seed、MockClientHttpRequest及builder等API；没有读取开源源码，不作源码对照声明。第一次javap误用@argfile退出1、随后正确bounded调用成功，两份证据保留。两次抽取脚本marker失败都在canonical写入前，未当行为失败。

## 测试产物、独立打包与原生补丁

在final20完成后且任何package前捕获classes/test-classes/resources及12份compiler input/created列表，共634项，manifest 156673B SHA256 `6cf7410851a6981a0da03d7fca569904be5df7673cadaab1746b9d191079f4fb`。compiler绝对源路径保留。fresh own package-workcopy从完整674源实际复制，input manifest保留source/prepackage摘要。所有package只在own workcopy，未canonical package、未构建/移动/读取锁定主engine JAR。

- `common`：exit0，8464ms；`.local\iam-model-frozen-prompt/package-common-command.json`，实际参数见 `.tools/iam-model-frozen-prompt-package-common.args`。
- `engine-complete`：exit0，19950ms；`.local\iam-model-frozen-prompt/package-engine-complete-command.json`，实际参数见 `.tools/iam-model-frozen-prompt-package-engine-complete.args`。
- `model-compile`：exit0，14430ms；`.local\iam-model-frozen-prompt/package-model-compile-command.json`，实际参数见 `.tools/iam-model-frozen-prompt-package-model-compile.args`。
- `model`：exit0，28501ms；`.local\iam-model-frozen-prompt/package-model-command.json`，实际参数见 `.tools/iam-model-frozen-prompt-package-model.args`。

所有实际Maven launch都Java21+offline本工程.tools/m2+显式UTF8+V:/.local/tmp，单个子树cap30包含启动，严格顺序。first combined package在engine testCompile触发30s timer；first splitengine package也在repackage阶段触发30s；两次实际exit-1、注册PID/startTicks、forced/kill决定/无活进程/Dispose与callback完成均原样保留，之后利用已完成的独立编译阶段完成上述成功序列，没有扩上限或改源码。共4个真实JAR（common、common-tests、engine、model），自有class major65；634项package finite与prepackage classes/resources逐字节相同，compiler列表因根路径不同单列。两个业务boot JAR无兄弟业务JAR；仅common跨模块依赖。六POM静态检查没有扩展构建范围。

native在自有diff/replay scratch进行，没有commit/mainGit改动。首次整批671 add超时，保留注册3进程闭合证据；仅在它们已停止后清理此scratch精确index.lock。随后9批<=80paths建索引；完整671条原始blob SHA1由原文件raw bytes加Git blob header计算，与actual ls-files --stage -z一一对应；新3条intent不冒充baseline。最终显式禁用global/systemGit config、系统/全局attributes、外部diff、textconv、autoCRLF；reviewed patch与原patch逐字节相同。native diff --check/apply --check/真实apply通过，完整674 replay文件bytes/SHA256均等于final source。patch 35093B SHA256 `f49bdb78b533384dd77965b1f1708ddf11f445a8c8b1ff930785d5ddb0e70772`。完整窄patch已读，self-review无额外源码修订；独立评审未代签。

## 资源、命令与执行限定

owner iam-model-frozen-prompt，3个fresh epoch、至多1个live，登记+确认缺席+durable preCREATE先于任何角色/DB建立。根目录及各epoch的ACL protected/exit0证据早于private写入；AGENT_DB_PASSWORD仅从本工程.env私下读取，未输出；fixture子环境清理admin/无关service秘密，只注入当次fixture所需modelclient secret。资源helper仅在新owner/新prefix改写旧机制，从未运行旧hardcoded主类。所有DB/roles/data保留；最终复查3库session0、6role NOLOGIN/nonprivileged/member0、两个schema使用/创建权限均无。所有fixture/test/Maven/Java/git进程注册对象稳定身份，filtered-parent发现后代，正常/异常路径清理，无全树未登记kill/global census。fixture readiness<=30、graceful stop<=10；最终已知9端口（含真实engine IAM）无listener。其他未记录inline临时端口仅由fixture close和已退出自有JVM证明，没有追溯性全局监听器普查。

执行不合规限定必须保留：早期直接Python source freeze/copy/测试XML与finite capture/package verification/native准备与verify等未使用外部guard，缺独立起止/PID证据；native bulk copy可能超过30s，不能以现在包装补造时间或抹去。Maven/Java/javap/git的既有cap记录仍有效。实际final test runner、capture、native runner等快照与其精确future delta已保存；future run/native Python调用现改为同一accepted guard，未重跑20/打包/native业务来冒充历史修复。新Python包装真实健康exit0与内部异常exit1控制通过，均资源停止、timer Dispose且callbacks完成；这些控制的真实命令记录独立保留。package中间helper变体未全部当时冻结全文，仅有实际原始argfiles/启动/退出日志，初版及最终版保留，不作不存在的历史字节证明。closure首次因V:/G:路径别名校验过严在资源子调用前拒绝，已保存原版并明确规范同一项目映射；其后实际registry-driven三库复查成功。另增已知IAM端口的定向闭合观测，不追认其他端口枚举。

具体命令：`& .tools/iam-model-frozen-prompt-run.ps1 red compile`；`red all`；`green compile/all`；`final compile/all`；实际Maven选项、JDK执行文件、cwd、argfile、PID/start、开始/结束/退出、cap及有限classpaths保留在各*-command或phase-group-N.json和`.args`。本次已完成且有记录的guard invocation汇总为 docs/evidence/iam-model-frozen-prompt/bounded-commands.json（汇总生成自身的后续guard结果留在owned根目录，不形成自引用）；actual Surefire XML的system-properties保存真实classpath，未重建已删除的Surefire临时文件，未作历史loaded-class证明。未guard的Python命令及缺失时间单独限定而不混进该汇总。前两次guarded report生成均触发30s（stderr仅保留traceback头）；将目录发现限定到实际命令目录并对UTF8 patch/helper显式解码后生成成功。未补造先前内部阶段耗时；其超时记录亦保留，无业务重播。

历史out-of-root枚举、未知主运行时影响、deleted-temp/loaded-class/trace空缺及此前空out-prefix bootstrap目录偏差均仍有限定；本项没有新建out-prefix bootstrap、没有父/邻工程访问、没有主服务/Redis/MinIO操作或外部发布，不宣称历史全部合规。T01仅全局完成，T09/T11/T12仍进行，T33未开始；真实模型/本体/IAM/K8s/容量验收继续待外部输入。本项只准备请求，未发送真实初始CHAT。

完整review package：docs/evidence/iam-model-frozen-prompt/review-package.json，188188B SHA256 `ffe937b96f5ed3f9ee16d8beaf42a4bb902a9df1c5b9e27a19b2a72e700c1455`。本报告封口后inventory/control最后写入，交还全部canonical-target/owned-source/DB/roles/HTTP/IAM/runtime/helpers/workcopies/evidence/report权利，ALL/noBackground，仍NOT Accepted。

封口更正：inventory.json/control.json尚未生成。本次是按根指示中断纯PowerShell封口后的有限ALL交还，exec会话91666经Ctrl-C实际退出1，无子进程；停止结果见 `.local/iam-model-frozen-prompt/seal-stopped.json`。该文件是根据本会话工具返回保存的结构化停止记录，并非完整独立原始进程转录，未单独记录seal的OS PID。前文预期的inventory/control封口步骤尚未完成，以本段为准；缺失物理清单交由根在交还后补齐，非代码阻塞。