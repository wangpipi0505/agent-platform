# EX-15b 文件 I/O 工程增补与 PhaseA v2 独立复核

2026-10-29，审阅者 `/root/algorithm_interrupt_readonly`。只读新契约、原平台方案、当前调用链及修正候选，仅新增本报告；未改文档/源/补丁/主入口/index，未应用补丁，未运行 Java/Maven/PG/HTTP、测试或服务，不使用 Superpower，未触及 OR/engine 独占范围。

**结论：新契约的已冻结语义无阻断；修正后的 PhaseA v2 可进入 root 的三测试源应用/重新指纹冻结及单次30方法实红流程。** 这不表示已编译、已实红、五生产文件均可一次实现或完整 I/O 已验收。其它输入/存储错误映射仍按后续阶段实证冻结。

## 1. 审阅版本

当前 `contracts/process-files-io.md` SHA256：

`194DEAF939B0A97EEEC66869E742AD56A1220F4A227974AB1674EB60A9E0548B`

最初484E版本将既有artifact403/404描述为保留端到端语义，实读发现SkillExecution只透传PROCESS_/ASYNC_，无法让原ARTIFACT_NOT_FOUND直接透过。root已在194D版本准确改为已列SkillAdapters内的窄404新码映射；本结论针对修正版本。原freeze、skill-invocation及EX05/schema不因此改写。

当前HEAD为 `6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946`。当前ProcessAdapter5A0C、ProcessFilesTest7B363及ArtifactStore75F89B未被本轮修改。

## 2. schema与兼容

SkillExecution:126/135、530/534先校验完整arguments及guard后参数。新契约要求arguments.files也走这些原门，additionalProperties=false未允许时正确拒绝；adapter只在通过后复制并移控制键，不能修改schema或原请求。这与现调用顺序一致，无需为绕过schema增加第六生产文件。

ProcessContract.validStdin:304–314当前仅序列化限长/deepCopy，scalar/array及任意对象字段均可保存。新文档准确限定只解释顶层object.files，false/缺省不触业务arguments.files，保留其它legacy形状；同时明确非bool/null的旧同名键是有意收紧，以400/PROCESS_CONTRACT_INVALID拒绝，不再笼统承诺任意旧stdin全部兼容。历史发布记录不就地改写、由所有者修新草稿的边界也清楚。

stdin.files=true与输入映射是否存在分开；没有输入files的调用仍可走输出文件能力。尚未启用时保留旧无文件I/O流程和既有父目录段拒绝。后续真实信封测试仍须证明false/缺省的业务files值、数组顺序、原请求不变，以及schema前置拒绝；PhaseA只比stdin树、直接调用adapter，不能代替这些入口证据。

## 3. 已知错误链的窄边界

当前ArtifactStore.owned:448–463按tenant/principal/READY/expiry查询，不可见、跨owner、过期、DELETED或不存在统一404/ARTIFACT_NOT_FOUND；不暴露其它owner是否存在。SkillExecution:346–354对PROCESS_码才原样透传。新契约的 `(status=404且code=ARTIFACT_NOT_FOUND)` 在PROCESS输入边界窄映射为404/PROCESS_INPUT_ARTIFACT_NOT_FOUND可使用现通路，保持HTTP状态及owner不可区分，无需改SkillExecution。

不能把任何404、任意403或其它异常都套该映射。download→Actor.current确有workload/delegated主体拒绝的403/SYS-AUTH-403，但它是身份上下文错误，不是artifact专属权限码，不应凭空增加PROCESS_INPUT_FORBIDDEN分支。download中SDK/流取得错误当前映成503/ARTIFACT_STORAGE_UNAVAILABLE，也不能伪装404；其后续PROCESS_资源码与原status须按实际输入阶段单独冻结。原schema/guard门保持原行为。

必要的真实输入路由验收至少包括：跨tenant、同tenant跨principal、过期、真实已删除四组，均404新code、worker启动0、本次输出artifact行0，源输入/无关哨兵不变；不存在ID应保持相同不可区分响应。同owner前次r_upload输入作为新run来源须有正例。metadata合法而存储故障另测，不能借它模拟owner拒绝。这里列验收，不执行PG、存储或进程操作。

输出路径错误明确502/PROCESS_OUTPUT_INVALID，能沿既有PROCESS_路径透出；不拿此码吞输入授权或真实资源/持久化故障。PhaseA两负例不读取输入artifact，故本窄404映射尚不是它的实现或实测结果。

## 4. 文件额度、归属与生命周期

输入16个/单个16MiB/合计64MiB，输出16个/单个与合计64MiB，均与平台方案§4.4/4.5一致；输入输出独立，实际读取计数/长度/SHA及关闭流、不信任声明值，未放宽原1MiB信封/stdout和timeout。

input使用平台生成名，logicalName不拼路径；output只在本次output子目录，拒绝绝对/父段/UNC/盘符/链接/reparse/缺失/目录/重复/错bytes或SHA。集合先验、真实写入者退出、稳定句柄或平台独占快照、防替换实证与Windows fail closed均正确保留。不能把两次路径检查、一次stat或CAS当退出/安全证明；也不将事后接纳额度说成worker整个写盘期的OS硬配额。

V306已有tenant/principal/run/call、固定key、状态、size/SHA与不可变归属，足够二进制产物沿现生命周期实现，不需要预先迁移。它**没有attempt列**；契约中的真实attempt指可信调用权上下文及关联核验，不能虚称artifact已新增attempt持久列。后续测试应核真实执行归属与过时代际处理。

不能用upload的r_upload/call1伪装执行输出；只补偿本次精确创建集合，保留主失败/补偿失败，不删输入、哨兵或旧队列。fileRefs仅由持久归属和安全元数据构造，不泄露本机路径。二进制回放须再次校验owner/sourceRun并生成新run/call引用；“二进制”按附件原始字节理解，**包括application/json/visibility_detail.json文件**，不能因MIME为JSON又走旧offload(result outputSchema)。旧非PROCESS JSON offload路径保持；JSON附件与非JSON附件都须有字节/SHA回放正例。

上述仍是后续目标，当前生产代码没有完整materialize/persist/replay链。五文件必要范围有前轮源码依据；本契约没有要求新执行器、迁移或通用上传框架，未泛扩其它文件。

## 5. PhaseA v2准入附核

在root授权“等新SHA继续只读复核”下，本轮另读作者新文件，原90D补丁、7C630说明和FC802首轮报告不改：

| 文件 | SHA256 |
| --- | --- |
| phase-a-tests-candidate-v2.patch | `D66FF6AD7E53E11236A92444783099336682B1B244B57D551F355EB0B8CCA81E` |
| phase-a-tests-candidate-v2.md | `6ED4AD5D8D1E8DDF45EE6114B1031902CFA22CEE7CFC85D56B220267138BD2D1` |

重新执行的仅 `git apply --check`，退出0。再从当前三源与v2 hunks在内存重建：原17/6/2共25方法块全部逐字保留，候选19/7/4共30；候选LF摘要分别94A0E0A7…、65ED5ACA…、6E4A4AAA…，与作者完整值一致。未把候选写到源文件，未用编译失败冒充产品红。

A-01已闭：父测试确认自有PID/startInstant/parent/executable/callDir后，使用createDirectories兼容新旧output目录，创建output/declared-only.bin真实空文件；worker只返回此文件绝对字符串，bytes/SHA及目录位置均合法，绝对性为唯一故意违反项。原身份/finally/许可/目录回收及断言预算未弱化。

A-02已闭：说明采用152源/Full706(73XML)/Adjacent88(10XML)，target仍30/3XML；27P3F仅预测，首个非bool失败不能声称其余循环项已执行。旧三源raw SHA仍4C329/74581/7B363。当前不启动OR/engine正在占用的实际窗口。

root按本契约与新候选冻结后，可只应用这三测试源、重新记录真实source/HEAD/依赖/env摘要，再在唯一窗口单次运行三整类；noignore/no skip，精确三行为红方可进入最小修复。真实输入/权限、链接/替换、补偿、二进制回放、正式新JVM和X-1仍各自待验，不能借30例关闭整卡。报告至此停写。
