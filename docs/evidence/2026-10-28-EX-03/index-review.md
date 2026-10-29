# EX-03 精确候选窄终核

2026-10-29（Asia/Shanghai），独立审阅者 `/root/ex03_closeout_review`。本轮接续[实质终审](final-review.md)，只核父链、准确文件/字节/index、导航与归档属性；未重做运行实质审、未运行产品/仓库脚本、未读真实.env/凭据，未改source/index或原final-review。唯一新增本文件，未使用Superpower。

**最终精确候选可接受，无本卡遗留阻断。** ROOT可只后附本复核，形成预期102文件的本地提交，再回填真实提交号与回滚。当前没有本卡提交，不虚构DoD⑤已完成，也不预先核准额外内容变化。

| 身份 | 实际核对值 |
| --- | --- |
| 本卡父HEAD / tree | `6eda42cb3201dea2005b5442ac5e5966c31c3300` / `2fdeaf71fde011c13cabe0b777a4c5d3fbe010ea` |
| [index-candidate.json](index-candidate.json) SHA256 | `f5304f9a9c8c973262f276536d394dfe5c9f4eeedc16c911ce13741aee159d4a` |
| 当前完整index tree（本review后附前） | `d0cd36ee544db598b6face3a3bc62831a3386168` |
| 清单/暂存数量 | 100列项＝3获准Java＋97本卡证据；加manifest自身为101；本review后附后预计102 |
| 实质终审原件SHA256 | `a0d221fdfe7dd24a5d4469a7e23ee95fd9b418f73caefbd4c7013b0b414741ed`，未改 |

- 三Java恰为OntologyManifestImports、SkillCatalog和OntologyManifestImportsTest，SHA256仍为已审`41450FAA…65F24CA5`、`7C98CAE7…91B03`、`97D0A6B1…432051`；除此全部限于本卡EX-03证据目录，无EX-27 Test、无其它卡/旧未跟踪资料、无raw/XML、环境、凭据或JAR混入。
- 100列项的长度、工作SHA256、raw blob及expected blob均逐项符合；manifest自身也匹配暂存。审阅者一次批量读取101个实际staged blob，全部逐字节等当前工作内容，并从完整index条目在内存重建树，得到上列d0cd树；没有调用write-tree或修改index。`git diff --cached --check`实际退出0。
- 前置父链确为运行HEAD `83726d50a6cce116251c03c5c193080d8ed5aad2` → outbox `c109171958e6a260089cad123549473d20a5c201` → SDK `6eda42cb3201dea2005b5442ac5e5966c31c3300`。outbox精确112文件，唯一非证据为SkillExecutionTest，tree `614c1ccf53020537f3f449a1b1561b3788e0d02c`；SDK精确250文件，唯一非证据为AlgorithmOffloadInterruptionTest，tree即本卡父tree。没有把两个子步揉入本卡提交或倒填运行HEAD。
- README更新与既有实质结论一致：原673失败/后续674 outbox失败保留，最新共享Full674门/全绿false、30P与协议桩/真实OR边界分列。20个Markdown目标实际存在，包含共享Full和本卡30P的command.json直链、阶段审与final-review导航。历史“待提交”属于记录时点，不冒称本卡已commit。
- [archive-attributes.json](archive-attributes.json)与.gitattributes恰70条独立、前导`/`锚定且无通配符的`-text -whitespace`规则；各项SHA/raw blob/实际暂存均相符并保留CRLF。对暂存集合查有效属性，只有该70项命中；未来子目录同名README/command对照未命中。全局及source规则不被本卡豁免，不为消除diffcheck重写历史数据。
- 共享Full的149文件manifest SHA256 `788fdb7547fac98ad55117470a46bafcf0a833f9bdf896ec011341c6e71ec4b1`与本卡清单一致。149当前工作raw SHA逐项等运行捕获；全部有效属性为text:auto/eol:lf/filter:unspecified，按标准CRLF→LF计算的149 blob逐项等当前完整index。**其中22项工作raw字节与标准LF不同**，不能把Git tree说成与原运行raw字节完全相等；成立的是运行raw指纹未漂移、最终树按既有LF规则表示同一源码内容。

前述实质审已接受限定源码/关键30P/共享Full方法与原因门，Full仍保留116原非通过及4旧跳过，不称全绿；本轮只补精确身份和范围门，不把历史现场补造为全部安全已证。SDK/outbox与EX-03分别审查/提交，EX-27整卡、PLAT-11等主项、EX-04/05/07/22及真实OR/J4不由本卡关闭。

准确回滚仅反向本卡最终提交相对上述父树的三获准Java差异，先核后续编辑，保留本卡证据/独立测试资源；不整仓reset，不撤前置outbox/SDK提交，不原地覆盖已发布版本。ROOT后附本review并提交后，应核实际102文件、父树及index清空，再登记实际提交号；本轮未实际提交或回滚。
