# EX-09 独立只读评审

- 评审对象：EX-09（原P-2），将既有能力包声明校验器接入`verify-contracts.mjs`真实门禁，并把required缺字段诊断定位到字段。范围仅两个获准脚本和本卡证据，不修改冻结schema/设计/契约/默认声明，不交付EX-10能力包运行层，也不关闭PLAT-22整体能力。
- 作者：`/root/ex09_package_gate`；主执行者`/root`仅做候选导航与暂存收尾。
- 审阅者：独立代理`/root/ex00_collector_review`；未参与本候选实现、fixture或采集器编写。本轮只读核查并新增本文件，未运行Node、Java、Maven、PG、HTTP、前端或服务，未读取真实环境文件/凭据，未改index或其它文件；未使用superpower。
- 候选：AP HEAD `28aa99ea12cb96bbbf781859c017acebd30647a3`；两脚本合计10行增加/5行删除。`verify-contracts.mjs` SHA256 `b93796fa1a6d38e8864495296a6ef71b3e4f31096c62ed7345c3557cf34d2aad`；`verify-capability-package.mjs` SHA256 `c8d86de1f4a9a8f999624899dc39e12b0c29ef59cd86c1b007f881228d66a1a3`。
- 冻结身份：[candidate-files.json](candidate-files.json)列47项，加该清单自身构成当前48项暂存集合，其当前SHA256为`f29793f9b1846f74838955f98d86a2fccbb16be85737adcd65d0b11d4091a5d2`。[index-candidate.json](index-candidate.json) SHA256 `077c772ffa139fa67de3b0756e9c11c9e07f24faf03138c378d162cc79418baa`，记录48项工作SHA及暂存blob；当前树`49f19875bdbc567d951a9f231ae56ac71472cabf`。该索引回执及本review尚待root后附，预期本卡专属提交50文件；当前尚无该提交。
- 证据：[README](README.md)、[before命令](before/commands.json)、[after命令](after/commands.json)、两阶段`snapshot.json`、36份stdout/stderr、三份[fixture](fixtures/invalid-missing-tool-binding.json)及`capture.mjs`。原命令、native退出码、signal、预期码、字段路径和输出摘要均有记录。

## 已核查

1. **真实调用方与参数**：检索本仓当前直接引用，真实入口为`scripts/test.ps1:5–6`。它先切至本仓、调用无参`verify-contracts.mjs`并检查`LASTEXITCODE`，随后才启动数据库和载入环境；本卡不需改该调用方即可让错误阻断后续测试。其它直接引用是文档/冻结登记及本卡采集器，未发现另一个绕过该前置的同名调用方。
2. **子进程失败传播**：`verify-contracts.mjs:38–41`以当前`process.execPath`和参数数组启动既有校验器，使用绝对脚本路径，完整透传`process.argv.slice(2)`，不设置shell或改变cwd。无参仍校验默认全集；可重复`--file`沿用原CLI；相对file仍按继承cwd解析。stdio继承保留字段诊断；启动异常抛出，非零status原样退出，信号导致的null status回退1；这些分支都不能落到末尾成功输出。无新依赖、解释器或第二套校验器。
3. **校验语义最小改动**：`verify-capability-package.mjs:96–98,131–132`只允许fail附带更精确路径，required检查条件未改；其它调用默认保留原路径。原schema和语义校验逻辑完整保留。未知kind的旧“未注册”告警仍在，但冻结schema枚举同时给出阻断错误，不将告警误记作唯一拒绝依据。
4. **三坏例有意义**：独立解析默认声明与fixture，结构差异仅为`tools[0].binding`删除、`identity.secrets`从空数组替成一条合成明文引用、`interfaces[0].kind`替换为未知值。fixture其余结构与默认声明相同。修后stderr分别到达`能力包.tools[0].binding`、`能力包.identity.secrets[0].ref`、`能力包.interfaces[0].kind`的预期schema错误；前两例各1错误，未知kind为1枚举错误＋原1告警，不是JSON语法、文件找不到或启动失败造成的无意义非零。明文仅为明确合成值`EX09_SYNTHETIC_NOT_A_CREDENTIAL`。
5. **原场景→修后证据**：逐条核对18条精确命令、native码/signal/字段路径，并重算36份输出SHA，全部匹配。原独立CLI默认0、三坏例各1；原总门禁默认及三坏例全0，三坏例均没有能力包执行输出，确为漏接。修后两个默认入口均0且有真实能力包明细，两入口各三坏例均1且命中精确路径；两脚本`node --check`各0。原缺绑定已能拒绝，只是路径不够精确，如实保留，未制造原校验器失效。before采集器本身退出0用于归档原缺口，不代表其三个总门禁坏例满足验收。
6. **回归口径与冻结内容**：两阶段Node均v24.16.0、同HEAD/本仓cwd，修前脚本摘要等于HEAD，修后摘要等于当前候选。schema、freeze、设计快照、默认样例及四契约共8个只读文件均满足before=after=current=HEAD；schema SHA256仍为`0456799d04deb57dfb114d169d8a37c7b57a49cad3996fee917a93efa704a634`，与freeze工程增补登记相符。默认真实目录当前1份声明、0错误/0告警；总门禁原门户27条/STALE5条非阻断告警未变。18条是脚本命令数，不是Java方法数或整项目测试通过数；本卡脚本层回归充分，不以此声称后端模块已重跑。
7. **候选精确性**：47列项工作SHA逐项匹配，证据目录没有混入旧未跟踪材料。README最终仅把两个文件名改成实际Markdown导航；将导航逆替换后，字节SHA等于前次已审README。清单对应更新该README摘要并将CRLF归一为LF，逆还原后SHA等于原清单`5a3d7fa7b25df4c0005a6427699f62e43fdbae81efa2d088383547d9596c335f`，没有其它条目语义变化。
8. **窄索引与公开材料**：当前index集合精确为47列项＋清单自身48项，与索引回执完全相同；工作SHA、stagedBlob及Git换行归一后的工作内容身份全部匹配。从完整index条目在内存重建树摘要，等于冻结树，`git diff --cached --check`退出0。原始输出均是本卡本地CLI诊断；公开候选JWT/私钥/已签发本体key格式扫描无发现。snapshot里的其它脏路径仅是当时清单，不表示其内容纳入本卡暂存或审阅。

| 问题ID/级别 | 文件:行号/场景 | 问题与影响 | 处理/理由 | 复核证据/状态 |
| --- | --- | --- | --- | --- |
| R09-01/P3，已关闭 | README末两段导航 | review和候选清单仅代码文字，缺§14.4所需实际链接 | root仅补Markdown导航并更新清单的README摘要 | 逆替换SHA与原README相同；48项冻结树/内容已核 |
| 当前无遗留阻断 | 两脚本及18条命令证据 | 未发现参数、失败传播、冻结语义或候选范围的必要回改 | 保持最小接线与诊断路径改动 | 独立只读核验完成 |

- 未覆盖：审阅者未重跑Node或真实测试。spawn启动异常/信号分支、重复`--file`、异cwd/带空格路径未另做动态注入实验，本次结论依据参数数组、原CLI和失败分支的静态控制流；实际18条覆盖本仓默认与三个指定坏例。未执行`scripts/test.ps1`后续数据库/后端/前端链路，未验证运行期发布READY、真实密钥/身份映射或能力包导入/装载；这些属于其它卡，不能从本卡默认声明通过推出完成。
- 逐卡结论：**EX-09冻结提交候选可接受，无遗留需回改或补跑的本卡阻断。** DoD①总门禁原漏拒绝→接线后同三坏例精确拒绝的关键场景证据充分；②按本卡纯脚本范围，双默认入口、三坏例双入口及语法检查构成适当回归，未新增规则豁免；③README命令/数字/导航齐；④本独立审阅完成；⑤仍待root精确后附索引回执/review、本地提交并回填台账提交/回滚记录，当前不虚构提交或整卡关闭。
- 作者处置：保留原CLI已能拒绝和原总门禁漏接的全部证据，仅接既有校验器/细化required路径；接受导航收尾，没有修改schema或重写原结果。
- 回滚：仅反向本卡两个脚本的接线、注释及诊断路径差异，保留证据；冻结文件无改动。不得整仓reset或回滚同仓其它卡工作。
- 审阅者复核：`/root/ex00_collector_review`，2026-10-29 00:54（Asia/Shanghai）；只适用于上述HEAD、两脚本SHA及冻结48项树。写本文件前后继续核对冻结候选与index不变，后续变更需复核受影响部分。本结论不关闭EX-10或PLAT-22。
