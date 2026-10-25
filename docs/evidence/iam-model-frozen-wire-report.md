# Frozen initial stream：实际 SDK 请求体对应校验

状态：NOT Accepted / execution NONCOMPLIANT，等待根独立评审。本项完成限定代码、真实失败优先、最终受影响回归、独立打包及原生补丁验证；没有发送授权、部署或全任务验收。历史 I1（旧任务未守卫 Python、历史时间/PID 缺口）原样保留。本项还实际出现 316ms 双 Maven 重叠和 fixture 生命周期超时，下面逐项记录，不能用后来互斥锁或通过测试修复历史。

范围依据是 `docs/evidence/iam-model-frozen-wire-brief.md`、只读 `.local/after-frozen-prompt-plan.md`，以及根授权的 `.local/iam-model-frozen-wire-execution-scope-addenda.md`。未重规划发送链，未读取相邻工程、主运行时或主 Git；未修改生产调用链、producer、common、POM、配置、迁移、web、四契约或项目进度状态。T01 仍仅全局完成，T09/T11/T12 仍进行，T33 未开始。

源码基线为已接受的 source674：`.local/iam-model-frozen-prompt/final-source/source.json`，133708B，SHA256 `ff96b04ef3131a95552d84545c770be8066012057577fcf7fb6a70959b9e196d`。输入代码证书为 `.local/iam-model-frozen-prompt-code-acceptance.json`，5442B，SHA256 `69b14888d49cacc706016dd8362be6a826fb606ac97f58568c2274d22e1233b2`；它不是本项新签收。首个守卫 Python 将当前674文件逐项与基线核对。最终 `.local/iam-model-frozen-wire/final3-source/source.json` 为134381B，SHA256 `039e79c10827671a6916c72185a844dcff818cc311f0b258512dd5c87a7404c1`，完整 blobs 位于同级 `source/`。实际是3新增、1测试修改、673不变、0删除，674→677：

- 新生产 `FrozenInitialWireGuard.java`，package-private final，单一静态 void require。
- 新测试 `FrozenInitialWireGuardTest.java`、`FrozenInitialWireGuardHttpTest.java`。
- 仅修改 `FrozenInitialPromptFixture.java` 的编码回调重载及异常归一；保留原有重载。

`docs/evidence/iam-model-frozen-wire/source-and-protected.json` 对最终 canonical、测试 workcopy、package workcopy 的677份源码再次逐项核对。protected-eight 的依据仍是 `.local/checkpoint-frozen-prompt-docs-before/manifest.json` 的 afterBytes/afterSha256；本工程内5份逐项匹配。外部3份执行文档按禁止读取约束未读、未写，留给根核对，不冒充8份全部实测。

## 校验与权限边界

入口对两个 byte[] 各限1..2097152并复制。复用 `ModelCalls.frozenInitialPrompt` 对原 accepted RAW SHA、当前真实 binding/frame/thread/expiry 以及共享原生 options 转换作权威校验；不生成第二套映射或第二个 resolver。校验器以公有 `OpenAiChatOptions`、消息 getter 和公有 response-format/schema getter 对实际 encoded body 做逐字段比较，没有预期请求体序列化或重编码。只有 schema DATA Map 转 JsonNode 供结构比较；没有把 schema 内 `model`/`stream` 属性当 envelope 字段。

body 严格 UTF-8/Jackson 解析：拒绝重复键、尾随根、非对象、错误类型；最大嵌套64、数字token128字符、字符串2097152字符，整体仍受2MiB字节上限约束。浮点以 BigDecimal 语义比对共享 converter 的原生值，整数输出/seed须可表示 Integer，缺省与显式零不同。模型、system/user两条文本及顺序、有效 max_tokens、temperature/top_p、两个penalty、seed、归一stop、text/json_object/json_schema wrapper 都对应。额外行为字段及非空tools拒绝；实际 pinned1.0 stream 输出证实 `tools:[]` 是精确允许的默认字段。强制 stream=true 和 stream_options.include_usage=true；实际 call encoder 的非流式体拒绝。

缺失/对应变化403 MATERIAL_SCOPE_MISSING_OR_CHANGED，畸形/边界400 MODEL_INPUT_INVALID；不受支持 options 复用现有409；中断/2s单调协作预算503 SYS-MODEL-503，保留中断标志；入口、转换后及成功出口复查真实binding。诊断通用且不包含payload。2s是协作检查，未声称JDK解析有OS级强杀或真实部署时延证明。

没有保留请求、binding、byte[]或成功结果；私有预配置 mapper 只用于解析及 schema DATA 转换。函数仍未接入任何生产发送路径。普通 Prompt/options 仍可变；返回正常只说明此刻这些传入字节对应，既不证明调用者字节来自SDK，也不允许发送/重放。没有SQL、IAM、HTTP、SecretVault、tokenizer、额度、校准、snapshot或持久化副作用；未加 provider DISABLED runtime kill 或通用1.3规则。

## 真实失败优先与最终16方法

先写真实fixture+回调测试和临时可编译 no-op。真正RED是第三fresh资源epoch的 `red3`：未改动的实际 SpringAI stream encoder 先到达唯一 EncodedBeforeTransport sentinel，记录原始wire；随后修改已准备的原生 model，再经实际SDK编码。空守卫错误地允许sentinel，403断言失败。Running-only XML实际1方法、1failure、0error/skip，Maven exit1/10276ms；`.local/iam-model-frozen-wire/red3-runtime/group-1/actual.json` 与 XML 保留。源码manifest `red3-source/source.json` 为134379B、SHA256 `7d0189b1568305fcf8da78fd5635e50d61710f2bcad35268c9bdc8db2a061646`。这不是缺方法/编译/启动失败。

先前保留的setup失败均不算RED：首个全链testCompile、随后common+model编译触发30s；单model缺common tests classifier；首资源epoch测试lifecycle重新生成common tests JAR导致testCompile后超时、无Running；第二epoch direct Surefire 使用旧本工程m2 common main JAR而 NoClassDefFoundError CurrentAuthorityClient。根授权的runner修复是排除该common依赖并通过公有 `maven.test.additionalClasspath` 明确加入本任务实际编译common main/test classes，无POM改动。cached plugin.xml只读检查验证了此属性。继续未完成编译后才得到上面的行为RED。

首次最终选择实际16方法为15pass/1assertfail/0error：新测试用DoubleNode构造0.00001，原accepted自然拼成1.0E-5，却断言原字面0.00001。改为既有测试采用的DecimalNode，源冻结final2。其local14为13pass/1setup error：继承 `InitialModelInputVerifier.rejectExternal` 在schema DATA里遇到tools属性就先于proof创建拒绝。未扩大生产scope、未伪造proof；把该测试DATA属性改成model/stream，冻结final3。生产守卫自首次实现后未更改。这两个失败及原XML完整保留。

最终仅在 `.local/iam-model-frozen-wire/final-workcopy/` 的实际final3编译类上运行约定四类。Running-only XML精确归集到 `.local/iam-model-frozen-wire/selected-union.json`，实际16、重复0、failure/error/skip全0，不用预设计数代替XML：

- `final2-local-retry1-runtime/group-1/`：FrozenInitialWireGuardTest 6 + FrozenInitialPromptTest 8 =14；Maven exit0/16643ms。
- `final2-http-runtime/group-1/`：两个HttpTest各1 =2；Maven exit0/9496ms。两个group记录的sourceSHA均为上述final3。

新测试实测default text、json_object、嵌套中文json_schema、输出优先级、scalar/array stop、显式零、原始0.00001与wire科学计数、Integer seed两端；真实SDK的model、采样/penalty/seed/stop/输出/schema、usage、n/tool_choice/tools，以及消息文本、角色、顺序、额外消息等变更拒绝。call-mode实际encoder体也拒绝。synthetic parser inputs明确限于null/empty/超2MiB、非法UTF8、重复key、尾随根、嵌套/数字上限、错type、未知/缺字段，不称为SDK捕获。旧Prompt测试中的409不受支持参数及legacy call/tools配对保持通过。

真实proof测试覆盖raw单字节编辑、另一真实accepted输入、错thread/frame、refresh退休映射、action退出后失效、可信签名IAM测试clock过期及中断；无反射/proof constructor或伪frame。controlled-clock实验只证明协议过期检查。输入数组保持不变。继承consumer先拒绝的tools-schema路径不可达，不声称独立覆盖。

纯窗口先用真实HTTPS对自有provider/tokenizer各作204正控，IAM调用数>0、SQL观察非空正控成立，再重置。编码+guard窗口SQL=[]、borrow0、IAM delta0、provider/tokenizer各0；前后6张model业务表内容摘要相同，setup effects单列。见final3 local的 `wire-pure-window.json`、`prompt-window.json`及实际wire/identity文件。没有将新造全零观测当作测试仪器。

HTTP使用未改动的真实engine发布/prepareInitial producer、实际PG、正常CA/SAN HTTPS、签名接收及binding，分别消费first/repeat/newGrant。每次正常stream通过后实际mutated-model流体403，SQL列表不变/borrow0；provider仍停在typed sentinel。原始SDK bytes及accepted/wire独立SHA见两个final3 group的model目录内 `prompt-wire-*.wire.json` 与对应 `.identity.json`。没有provider response/usage、生产connector、Spring桥或私有SDK调用。

## 打包、原生补丁与执行记录

任何package前，`prepackage/manifest.json`捕获实际common/engine/model classes、test-classes、resources、compiler-status，共637项；自身class major65。`package-workcopy/`从完整final3-source677复制，`package-input.json`绑定源码与prepackage摘要。实际6个串行Maven阶段为common test-compile/package、engine test-compile/package、model test-compile/package，均skipTests/exit0/<=30s；全部真实参数、argfiles、PID/startTicks、时钟、退出与cleanup在各 `package-*-command.json`。没有canonical package、没有替换或读取锁定主engine JAR。

`package-verification.json`实测4个JAR：common main/tests、engine boot、model boot；自己业务class major65，boot无兄弟业务JAR。package源677对应，625项classes/resources逐字节等于prepackage，12份compiler-status因绝对源路径不同单列。业务JAR不是模型发送/容量/标定验收。

native仅在自有detached `native/diff`、`native/replay` scratch；没有commit/mainGit操作。674条baseline原始Git blob SHA1与actual index逐条对应，3条new intent不冒充baseline。9批<=80paths加入，显式禁system/global config与attributes、autocrlf、外部diff/textconv；native diff --check、apply --check、实际apply成功，完整677文件的bytes/SHA与final3冻结一致。完整窄patch已逐行读完，自查没有新代码修订；没有代签独立评审。`native/frozen-wire.diff` 25755B，SHA256 `e69c6eac30cfe4b052e79dd36b4c2772521f9de078de61c438775fb864774eb6`；reviewed diff字节相等。native-prepare实际29975ms/exit0/timedOut=false，临近cap但没有改上限。

所有实际Python、Maven/Java/javac/javap、icacls、native Git及resource child都通过只读accepted `Invoke-OwnBoundedProcess` shared<=30s，固定cwd，登记稳定PID/start身份、filtered-parent后代，callback Dispose/cleanup记录保留。UTF8明确，JDK21、现有依赖、V:/.local/tmp；未直接裸跑Python/Node或下载依赖。`.tools/iam-model-frozen-wire-*`的实际helper/argfile版本在 `executed-helpers/` 按SHA保存；以后修改但未执行的最终helper不能代替这些版本。`bounded-commands.json`是有限已完成命令汇总，汇总生成自身、后续ports/seal命令另有原始guard记录，避免自引用。

本项真实偏差：final3 compile Maven started08:15:08.3629764Z、ended08:15:32.2955314Z；local Maven started08:15:31.9797312Z，实际重叠约316ms，违反max1Maven。两个均守卫、随后Surefire运行晚于编译结束，但不宣称因此合规。根要求后续Maven增加本任务Local named mutex，实际6次package成功取得锁并串行；未测试竞争分支，不能追认旧启动。

另有一次helper字节时序缺口：HTTP runner活动时，为后续package/native更改Python helper的source指针；其capture模式未改，但捕获启动前没有把新helper字节版本再次封存。actual capture guard启动08:16:46；保存的 `.432046e5fb88e8320dd51729056a06020290e0d46411be254f06c5ac64b1b0b7` 版本元数据creation为08:16:48、source write为08:16:34。后存全文存在，不能倒推为before-launch字节证据或补造历史时钟。此capture的真实XML/source归集仍保留，独立评审应结合该缺口判断；后续helper在守卫完成前不再改动。本段限定前文helper版本封存说明，不声称每次启动都满足时序要求。

首次final16时两个fixture30s超时且被守卫停止。最终HTTP2全部通过后立即写stop，engine exit0/29625ms；seed启动08:16:16.2474471Z，在30010ms决策触发30s，exit-1/forced、无alive，callbacks完成。`final2-http` coordinator如实FIXTURE_FAILED；不将其改为优雅退出或重跑green来掩盖。资源最终独立safety-close成功。

## 资源关闭与交还

owner始终iam-model-frozen-wire。根额外授权文件明确把原<=3fresh放宽为恰好允许第4fresh；最多1live，未reset/reuse/drop任一已关闭DB/role。每次absence preflight和durable preCREATE registry先于create，schema隔离，登录口令仅私下读取本工程.env；child fixture/test环境先去admin/unrelated AGENT/secret/proxy/JWT/token，测试只加所需模型client secret。初始.NET ACL尝试导致own目录不可写、后续Set-Acl尝试报SeSecurityPrivilege，均在private写入前；shared-guard icacls修复exit0并确认protected后才写资源凭据。root和每个runtime的ACL检查记录保留。初始PS错误的原始工具输出保留在会话，报告不补造其OS PID/独立计时；真正icacls child有原始guard记录。

`databases.jsonl`登记4个新库/8个新角色；`final-resource-closure.json`和每个registry epoch的 `safety-closure.json`实际复查：session0、全部NOLOGIN/nonprivileged、member0、两个schema的USAGE/CREATE均无，保留数据。所有fixture/Maven/Surefire/helper/native/resource注册进程已退出，强停与正常退出分开。`known-port-closure.json`只探测实际ready/config记录的own端口，无listener；不做全局census。其他inline临时端口由fixture.close和已退出JVM证明，未补造端口普查。主端口15173–15175/18080–18085、Redis/MinIO、主runtime与旧资源均未操作。

最终review package为 `docs/evidence/iam-model-frozen-wire/review-package.json`，包括完整677源manifest、完整窄diff上下文、actual16、prepackage/package/native/closure的具体引用和helper版本。报告在该package内以bytes/SHA封存。最后有限inventory按checkpoint分块生成，排除Git对象库/依赖cache和自引用控制文件；`inventory.json`与`control.json`在同一evidence目录，封口守卫本身的原始命令由control另引，不混充历史证据。

交还ALL：canonical targets、源码/编译输出、helpers、evidence/report、workcopies/native、四DB/八roles、IAM/HTTP/runtime所有权。noBackground；NOT Accepted。未做真实模型发送、远程tokenizer/校准准入、额度预留、不可变请求snapshot、raw-wire持久化、恢复或部署验收。本项只实现下一项私有初始stream对应校验依赖；根独立评审后决定代码及有限执行验收。
