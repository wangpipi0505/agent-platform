# EX-08 第三批检查点独立复核：EX-04收口

- 审阅者：`/root/ex04_pg_scope`，2026-10-29；未参与 EX-04 产品/测试实现，未编写本次主入口和 EX-08 增量候选。
- 核查方式：只读当前三主入口、EX-04 最终证据/独立终审、本次 EX-08 候选、Git 实际提交对象及源文件；使用 Python/Git 只读校验数量、SHA、字节和路径。未重跑 Java/Maven/PG/HTTP，未读真实凭据、改源码、stage 或提交，不使用 superpower。唯一新增本报告。
- 结论：下述已核检查点内容可提交；EX-04 限定范围可在主台账保持完成，**EX-08 整卡仍进行中**。最终精确 index 集合由 ROOT 暂存后另作只读确认，不以本报告预签未知文件。

## 代码提交对象与来源

实际本地提交 `72ee3428581ff2e25359c9b312a5754de1ac97bc`，parent `d7880a66b345f15aecf37133a702df769e591fa5`，tree `4c660eb5e45312a786f6bf3caba1ff261a591f49`，与本次增量/context 清单一致。`git diff-tree` 独立计得301文件，非证据恰4生产＋4测试，余293均为 EX-04 自有证据；没有 OR 产品改动、迁移、POM 或其它范围。原 `index-review.md` 的八个代码 blob 与实际提交吻合。

独立重读最终 skill/engine source-before/source-after 清单并重算当前 raw SHA：150＋202＝352源全部与实际最终运行一致。对352路径批量使用 Git 标准过滤计算 OID，再以单次 commit tree 和 `cat-file --batch` 核 blob：所有过滤后 OID、blob SHA 都与 `shared-projection-source-context.json` 和实际代码提交相同。52文件的 raw 与 Git blob 字节不同，清单如实逐项标明；没有把 CRLF/LF 差异写成产品漂移，也没有宣称352源 raw 与提交逐字相同。

OR HEAD 仍为 `da3345a123752c3fda06c5a7a8a1dc9d614b8918`，未出现本卡跟踪文件或 index 改动。根三主入口在 AP/OR Git 仓外；本次保存的是精确证据副本，不能说产品提交直接包含原文档。

## 主入口状态、依赖及执行可用性

独立按实际表逐行统计：台账§3有48个唯一能力项，状态9完成/21进行中/12待验收/5待启动/1暂缓；§14.1有39个唯一 EX，状态9完成/2进行中/28待启动。EX-04 完成，EX-27/EX-08 进行中，EX-05 待启动；摘要和逐卡行一致，不把子步或EX重复加到48能力分母。

PLAT-14/O-02/O-03仍进行中，已把“EX-04未闭环”改成限定消费适配完成及明确剩余：EX-10能力包装载、EX-07真实 OR/J4/写工具业务、非HTTP真实登记与绑定正例。没有因组件绿色自动关闭能力主项。

EX-05的 EX-02/03/04硬依赖已全部解除，但本轮未开工；卡片保留准确契约/生成类型白名单、二次生成无差异、typecheck/负例门禁要求和冻结原文边界。EX-06/07后续依赖未跳步。三入口仍分别负责唯一状态、执行白名单/验收、可运行命令与联合场景；证据快照明确不是第四份执行入口。

卡片 EX-04 与手册§2.4已使用实际存在的测试类、当前已实现字段规格及回执链接。红33/47、绿33P/47P/OR9P、首轮Full689新增8E及最小补修、最终689/932、两原class events/14未执行均如实对应最终证据。完整模块只有相对回归门通过，不称全绿；skill唯一迁移2UUID/2timestamp、engine23个对象/连接表示差异保留原始hash/raw门false及有限派生解释，不变成未来泛化规则或测试豁免。

实际PG/HTTP与模型/engine协议替身明确分开；真实OR业务分页与J4仍待 EX-07，没有宣称自动续页/强制累计maxRows或非HTTP登记正例。V222当前target残留、原TestInputHttp9E/未迁移专库、原timeout建库后类级错误都写明；不能将当前环境复验外推clean构建，EX-27仍承接源码/构建/历史一致性。

对三份原主入口及 EX-08 README/本次增量，排除代码块后检查90个本地 Markdown 目标路径，全部存在。此为文件路径检查，不声称执行了命令、浏览器页面或所有历史场景。

## 审查发现及闭环

| 问题 | 影响 | ROOT处理与复核 |
|---|---|---|
| 台账原716行“本次收口…EX-04依赖解除但未开工”紧邻当前完成段 | 同一当前执行小节会出现互相冲突的状态 | 已改“前次收口（历史；以下为当时状态）”及“当时…尚未开工”，保留旧674事实；本审阅者重新读取确认，并验证台账快照与manifest同步。关闭。 |

其余旧EX-03/EX-08首批历史段均有时点或历史标识，未倒改成当前新回归；无需重跑产品来修正文档标签。

## 三份精确快照

独立比较每份原绝对路径、快照、manifest字节数/SHA及版本，均逐字节一致：

| 原主入口/快照文件名 | 版本 | 字节数 | SHA-256 |
|---|---|---:|---|
| 优化台账-统一-20261028.md | v2.14 | 176095 | 800ce2e3fe09e1f96e1e8c2b2ee4004949157efb64d3ac00c6ac413a052bd1bb |
| 执行卡片-统一补差-20261028.md | v1.14 | 79888 | e17365356d721c092cec7f1342790a2ab4a5050a3581458ff0bb7edf21393a3e |
| 联合验收与回归执行手册-20261028.md | v1.14 | 50792 | 740c4620b9cfb07087358f15ff83417cfb089689eb2aa8d22dc5d1da4228c7bb |

manifest 明确 `executionEntry=false`/`snapshotOnly=true`，内部相对链接属于原文档位置；不要求执行者阅读归档副本或逐份证据。

## 本次检查点范围

预期精确9文件，均位于 `docs/evidence/2026-10-29-EX-08/`：`.gitattributes`、`README.md`、`incremental-20261029-ex04.md`、`shared-projection-source-context.json`、本报告，以及 `main-entry-snapshot-20261029-ex04/` 下三快照＋manifest。不得夹带EX-17、EX-00临时库名、user audit/output或源码。

`.gitattributes` 保留旧3条精确快照规则，新增恰8条当前检查点 payload规则，都是 `/具体文件 -text -whitespace`，无glob/src豁免。八个payload的原始字节应保持；attributes文件本身仍按根标准过滤，不把其raw SHA误认成提交blob SHA。README原提交LF、本次按明确保真规则保存当前CRLF会显示整文件字节差异，但独立按文本比较只有顶栏增量/历史标签调整，原首批正文不改。

已核关键原始输入：

| AP repo内本次EX-08路径 | raw SHA-256 |
|---|---|
| shared-projection-source-context.json | fcae52607a75118886f45af0a4dd521831631cc1cd981bb7434867c9adbf1c3e |
| main-entry-snapshot-20261029-ex04/manifest.json | ebc2cf07f700f1475c58e14c84e571e87178ea8c3c0724beb997c853bbf132cb |
| .gitattributes | 4bda50a378068bf91f8b90b5c38021b0f59a15c76f9b15cd6f6b0380ef7781cf |

此检查点只收口本卡进度与证据索引，不代表48能力/45原子项完整复验或EX-08整卡完成；剩余30整卡与真实联合输入仍按主台账继续。未授权push/tag/外部发布。
