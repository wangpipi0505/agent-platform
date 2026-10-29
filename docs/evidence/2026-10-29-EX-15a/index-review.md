# EX-15a 精确索引独立审阅

2026-10-29；审阅者 `algorithm_interrupt_readonly`。仅只读Git元数据/index blob、候选清单及当前文件，唯一新增为本报告。未运行产品/测试、Java/Maven、Node、API、PG或服务；未改index、源码、旧证据或三主入口，未使用Superpower。

**结论：接受当前精确293文件暂存候选。** 允许root只追加本报告形成294文件，再复核该唯一增量/报告原始字节与`git diff --cached --check`后本地提交。此许可不包括其它文件，不替代[全链实质独审](final-independent-review.md)，也不把保留的原模块失败门改为true。

## 冻结输入与逐项结果

- 审阅开始、结束HEAD均为`1fe99d67b9f72fad576d5e3a424b24d550dbf0b2`。
- 准确候选：本仓`output/ex15a-staging/candidate.json`，SHA256 `3cbe5bb282575093ca8affd13d4eb7f1522a7122df8f9dbf78934d79b65c3227`，293路径，7源＋286本卡证据/属性。
- 当前`git diff --cached --name-only`与候选逐路径集合完全一致，缺失/意外/重复0；七源恰为实质独审列明的四生产、两个既有Test及一个新Gateway Test。所有其余路径严格在`docs/evidence/2026-10-29-EX-15a/`，没有EX-06/EX-10等并行材料、三主入口、contracts冻结物、迁移、根属性或忽略规则改动。
- 逐个核293项工作区byte数/raw SHA与候选相等，并从实际index stage0读取OID，293项均精确等于候选filteredOid，mode均100644，没有冲突stage。随后逐个以`git cat-file blob <OID>`读取二进制输出重算暂存blob SHA，未通过控制台文本换行推测。
- 291项暂存blob与工作区原始字节完全相同；仅下表两项发生预期CRLF→LF。原始总字节10,825,829、暂存总字节10,824,808，差1,021恰为287＋734个CR。两文件解码后只去CRLF中的CR即与暂存全文Ordinal相同，无其它字符/语义变化。

| 路径 | 工作区raw SHA256 / 字节 | index blob SHA256 / 字节 | 结论 |
| --- | --- | --- | --- |
| 本卡`.gitattributes` | `69ec7427bb94548ae58dd6bec2c63bf8d006e424a70b97e8e029efa3d36f7dee` / 25,366 | `21fb36a32e359fc37e0956567eaa85a6014dd0d5bab79f13188cda87391486e3` / 25,079 | 仅287处CRLF→LF |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java` | `967849596ae6179a2b0858fb39d50a365c65f2370d4e2ad0658dfee44a0569b1` / 35,703 | `9eb61433d3faa6c6a12e9d9b71756405362bb6e876c07927a46f456866bf1116` / 34,969 | 仅734处CRLF→LF |

其它六源也包含在291项完全同字节中，不推测新Gateway Test一定需要归一化。曾被称作“casefilter”的事项经root明确为Git clean/filter换行笔误；没有caseFilter文件或字段需要修改，以上以真实blob字节为准。

## 属性只覆盖准确证据

独立读取暂存的本卡`.gitattributes`：一行说明＋286条无通配符的准确相对路径，每条严格为`<path> -text -whitespace`，无重复/非法行。集合精确等于当前285份安全证据加待追加的`index-review.md`；不包含自身、生产、测试源码、raw日志、全目录glob或仓外路径。

对293路径分批通过**显式argv**执行`git check-attr --cached text whitespace filter -- <paths>`，核879条属性回执：285份已暂存安全证据均text/whitespace=unset；七源与`.gitattributes`自身均text=auto、whitespace=unspecified；全部filter=unspecified。未来本报告的准确`-text -whitespace`路径已在暂存属性中。没有通过给源码关闭空白检查来放宽规范。

`git diff --cached --check`本次独立执行退出0。暂存证据无需Git换行或空白过滤，285份安全证据全部真实字节不变；既有实红、运行源快照、原SHA与proof不会在提交时被重写。

## 84份安全XML与私密原件的边界

293候选明确包含84份已经准入的安全XML：完整skill71、相邻5，以及各定向红/绿8份。这些是当前证据范围内保存的方法/结果原件，必须随本卡提交并按`-text`保留真实字节，不应因为扩展名是XML就一律排除。逐份从暂存blob解析XML成功；未发现原始machine key/JWT形状或非空password/secret/access-key/API-key类property。对全部候选文本的对应原始key/JWT形状和JSON直接secret值检查也未发现命中；该检查与实际路径/输出机制检查共同使用，不能代替对未来新证据的审阅。

全链独审末段“原日志/XML留本地忽略”指**私密raw原件**，不是禁止本段84份安全XML入库。gateway完整模块的私密原XML、完整raw原因/日志、live私密行/签名/HTTP原件、env、key、payload与JAR均不在293候选；公开`private-evidence-fingerprints.json`只登记安全文件摘要/字节数，不能误读为提交了私密内容本身。原私密full gateway XML、原raw原因与摘要门false仍保留，未替换成清洗后的假原件。

初始清单为305，含12份被Git忽略规则拒绝的`.log`；修订候选如实剔除这12份，原初稿保留在`output/ex15a-staging/candidate-initial-with-ignored-logs.json`，没有force-add或通过unstage掩盖。独立逐条用显式argv核12条`git check-ignore -v -- <path>`，均命中现有规则、文件仍存在、均不在候选/index；原始日志没有被删除。本次没有为了补公开清单而复制/伪造一份日志。

## 实质结论与唯一允许增量

暂存`final-independent-review.md`当前raw SHA仍为`9fd18ef5649eeb3168e9d5e2b03fd19faf554b3c3a76d6ba247bf4dfa7ade41d`；`final-acceptance.json`仍为`844d2ed8a406ab48ac6a2f5a3ae36850d98658b3169df7ab5523374bb5620b61`。原gateway summary仍为`c5c5d72ef44451242baacb1717b6c7daca0e101f39ce2a720dc1cf877857e546`，11项有限证明仍为`e6817b0da75e6681886ffad7861ef80ee5ddd4f088f61e4d13326a07b77a4f78`；它们包含在已逐blob核实的候选内。原raw/regression/allTestsPassed=false、独立缺类归因边界及尚待提交关闭条件没有被索引阶段改写。

本报告完成后停止写本卡目录。293候选不含本报告以避免自引用；root可仅`git add -- docs/evidence/2026-10-29-EX-15a/index-review.md`成为294，再确认额外路径只有此一项、报告raw/index相等、其余293 OID及HEAD未变、cached diff-check0后提交。任何其它index/source变化需重新给出准确差异，不在本次准入内。后续主项状态回填另按已核PLAT-09/T09.1映射处理，不夹入本次源码提交。
