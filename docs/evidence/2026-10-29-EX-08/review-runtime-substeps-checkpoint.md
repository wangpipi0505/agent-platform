# EX-08 六子批检查点独立文档复核

结论：本次时点文档检查点可接受，可补齐本报告的精确指纹、封版manifest后进入13路径独立index审查。未发现需要修改快照原字节、六提交回执或扩大提交范围的阻断。本报告不关闭EX-08整卡，不替代产品/运行验收，也不是尚未发生的index或commit回执。

## 审阅边界与身份

审阅者：artifact_boundary_review。三入口快照、incremental及本次manifest由ROOT/docs_reader_check整理，本人未编写本次检查点。本人参与过PhaseA公开包准备及此前部分测试候选/采集器，不能把本报告称为对本人PhaseA包或全部产品行为的独立验收。这里重新读取双仓Git对象和本次文档；产品结论引用既有非作者报告，最终暂存集合仍须非作者逐blob核验。

本轮只读文件和Git对象，仅新写本报告。未运行Node、Java、Maven、PG、HTTP、CIM或服务；未读取凭据或私密运行原件，未修改源码、原证据、三主入口、HEAD或index，未stage/commit。所需OR Git对象读取限本项目ontorepo仓。

复核输入为[快照manifest](main-entry-snapshot-20261029-runtime-substeps/manifest.json) SHA `78A87DB32945110C9A139B2451FEF2942050174F4D4579A186A8DD634C27B441`，self Git OID `32a2e1be72b9ae5d026f586d756e07ace556aa38`；[incremental](incremental-20261029-runtime-substeps.md) SHA `759AAAB92DA074F8C0345866E5D967D828558F709EE09951F9FCD9887F63A8AA`。该输入sealed=false、review指纹pending是作者等待非作者审阅的准确状态，不是实际运行准入。随后只应回填本报告及必要封版元数据；本报告不把未知最终manifest SHA预写成已核结果。

## 捕获原字节与读者入口

捕获记录为UTC 2026-10-29T15:39:34.880824+00:00至15:39:34.882144+00:00，即Asia/Shanghai 23:39:34。逐文件长度、SHA和版本均与捕获清单一致：

| 快照 | 版本 | 字节数 | SHA256 |
| --- | --- | ---: | --- |
| 优化台账 | v2.21 | 286925 | 9EBF4575DFFE9B48172AB2AE2E3CEEC26EFA1B7AEB202AFE1B783807A4DCDB29 |
| 执行卡片 | v1.21 | 156237 | 71B833DA3422217ECD714DEC2A1B7375E577152C74BEE671B713726898F53951 |
| 联合验收与回归执行手册 | v1.21 | 126817 | B158E88358F4A739D4C6DEAEEF64799E457E34B7997520ADF76F41BAD2CE7B52 |

本复核确认已封捕获清单与现存快照字节一致；没有亲历复制瞬间，不声称重新测得当时sourceStableDuringCopy。live三入口捕获后可继续推进，不以现live必须等于快照为门，也不倒填后来的OR两项RED或Create修复。

副本明确为历史快照、不是新执行入口。原三入口相对链接以manifest所列原路径 `文档/优化方案/` 为基准；incremental以自身目录为基准。去掉围栏代码后，62个实际本地Markdown文件链接全部存在；初步文本匹配的两处PowerShell `[xml](Get-Content...)` 已排除，不误报成链接。无带片段锚点的文件链接；本检查没有尝试执行文档内命令。

读者应先取台账§14的依赖与当前状态，再取对应卡白名单及手册命令，不按EX编号或历史日志顺序直接执行。三入口顶部“OR API/Vite已恢复”保留的是终态子步完成时的历史事实；捕获点的有效最新记录是台账第909行、卡片第582行、手册第766行：301源新RED采集已启动、尚无行为结果，API/Vite暂按记录停止、PG/MinIO运行、结束后待恢复。incremental已显式区分这两个时点，不能把顶部句子当成该捕获时点的现场服务证明。快照原句保留，无需为后来结果改写历史副本。

## 六提交与范围核验

六份local-commit JSON的原字节长度/SHA均与manifest一致。对各自仓的commit直接读取parent、tree、diff路径和commit内manifest；没有用当前工作树替代提交内容。下表全部核对通过：

| 仓/子步 | commit | 文件数 | 非证据文件数 | commit内manifest SHA前缀 / Git OID |
| --- | --- | ---: | ---: | --- |
| AP响应体 | 37577d2806727724f97cdfe8d66be5cd5ef06e3d | 106 | 2 | FCC92CF7 / 41377adfd638d65d62db3218ed995b2461592a3b |
| AP路径 | 6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946 | 70 | 2 | F444526B / e8847dfa6944bc6281947d5a9832be8f5054cbb5 |
| OR终态评论 | b9700862f4515980fb56ed85b606f03eaefdee64 | 38 | 6 | A2E285CD / eb4efe56d7c995a0fe3f3b0bc611c87f238e0783 |
| AP资源/cache/SSE | 73dc4cf37a2894f5c20fe806908011091888383b | 45 | 4 | 37D44D24 / c379205834f122d639ce67400fdf0f9931b92e89 |
| AP MCP终态 | b4918591a8d899d260ab365106ca798554d2b81d | 17 | 2 | 0A42D127 / 63e810fe10d27e12d42cffd5651ca81e65ba584b |
| AP PhaseA | 027fc658ef85e36c546996c7cc308c0377c22794 | 29 | 6 | 6B410966 / 20e7ec9dadf11ba8cbe8a4fbb7f2413be9090654 |

完整parent/tree和manifest SHA均逐值匹配输入manifest。较早三个manifest没有sealed字段，按其实际历史schema和已提交精确blob核验，不捏造sealed=true；后三个具有sealed=true。六组非证据路径也与对应白名单完全一致，未将文件数相加冒充唯一文件数或六次独立Full。

另外核实EX06辅助提交73a4ada6de5ed5ee34f623a8a435c949040a2e7d的parent acad6bdee223b23f331420c6de50045cb5fa8941、tree d653bc84d770910b7c117921fd9ef31efa9a3956及124文件；它不计入六回执。该Ontology/协议可恢复阶段不等于All/AP三应用/J/S1已交付。

## 状态、旧失败与剩余事项

按台账§3实际48行重算：完成10、进行中20、待验收12、待启动5、暂缓1。按当前39个唯一EX表行重算：完成11、进行中5、待启动23、待验收0；进行中恰为EX06、EX15b、EX19、EX27、EX08。不是平台总体完成率，不与45原子项或61历史整改相加。

六子步记录保留了实际范围与非通过事实：Full701为585P/6F/106E/4旧S，响应体与路径共享该轮；Full707为591P/6F/106E/4旧S，MCP和PhaseA共享该轮，原collector1、rawReason/regression=false及allGreen=false不改。D2E3非作者复核只接受唯一固定迁移case的本次精确原件对，两UUID及两updated_at时间表示差异不扩展成通用归一化；115旧原因原文相同，旧116非通过仍存在。原Full700、Full706的新增失败和原门保留。资源Full933亦保留旧817P/36F/80E及后续818P/35F/80E的各自事实，不称全绿。

实际运行归属没有被提交号覆盖：响应体/路径Full701使用acad工作树；PhaseA30/88和旧Full706使用6ca；MCP及Full707使用73dc与当时7份技能源码工作树。PhaseA契约194D运行原件与提交时4656状态文字分开。OR终态API271的270P/1旧F、原gate=false与固定表示差异独审接受并列；旧服务恢复事实不代替新301源窗口的恢复。

完整PROCESS输入输出持久/防替换/二进制回放/正式重启仍待；EX19完整REVIEW材料、主体与元素归属、其它阶段/并发/UI仍待；EX06新独占空库正常迁移恢复、三应用真实评估预检发布、All/J1～J9/S1仍待。X-1～4和X-14只约束对应外部真链路，本地release/key等不能一律推给外部；X-13仍不推送/tag/外部发布。EX08的48能力/45原子证据覆盖和历史完成项独审尚未齐，本报告不提升整卡状态。

## 精确公开包和后续封版

stageCandidate恰13唯一文件：6个回执、3份快照及其manifest、incremental、本报告和父.gitattributes，源码数0。原11个已有非self/非pending文件的raw长度/SHA及实际Git clean OID均通过；self按上述外部SHA/OID单列。本报告写入前AP index为空，没有进行暂存。

父属性raw原前缀1731B/SHA A16C2787DB84DA2A5E04E11A1BC883FD71366E7888C728B8DD01B372170AA9D8逐字保持；仅6个既有CRLF经原过滤得到1725B/SHA C7E4E612DAC79CCE2C1F55AD11AF591EDBB4CCCFD2AE7C3383F02B025D2CDD27，与027父提交blob完全一致。追加恰13精确规则；本属性自身仅-whitespace，其余12路径-text/-whitespace，不新增通配、不改变生产规则、不把旧CRLF带回Git blob。

本公开包不含OR新测试/Create源码、运行日志/XML、数据库内容、依赖/JAR、环境或凭据。对现有公开输入及本报告的JWT/私钥标记扫描零命中；本轮没有读取实际秘密值，不能把该模式扫描冒充新一次凭据值核验。快照和公开摘要引用旧证据，不能单凭这13文件独立重放全部产品验收。

允许ROOT仅回填本报告bytes/SHA/clean OID及相应pending/封版计数，再对最终13路径逐blob独立核对。最终manifest自身SHA仍外部固定，不能循环自哈希。此结论限文档检查点；任何代码、运行、未知路径或扩大完成声明不在授权内。
