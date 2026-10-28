# EX-00 独立只读评审

- 评审对象：EX-00，仅核准两仓当前源的基线采集、方法级归因、环境前提记录及后续比较口径；不核准产品全绿、联合验收或剩余能力完成。
- 作者：`/root/ex00_baseline`（运行与采集证据）、`/root/ex00_source_inventory`（源码盘点与归因）、`/root`（汇总器、协调及候选整理）。
- 审阅者：独立任务 `/root/ex00_collector_review`；未编写本次采集器、汇总器、归因报告或冻结候选，只提出审阅意见并编写两仓本次审阅回执。未使用 superpower。
- 候选：以下两仓均为未提交的新增 EX-00 证据；逐文件工作区 SHA256 及字节数以各自 `candidate-manifest.json` 为准，index 已逐项核对。Git 行尾规范化后 index 与工作区内容一致。

| 仓 | 原 HEAD | 审阅前冻结 index tree | 候选数 | candidate-manifest.json SHA256 |
| --- | --- | --- | ---: | --- |
| AP | `ebbfd62ca1d19efc4d172804efb5ac79453ceb64` | `eee19aaf4f8125eb488000be4c7682ec177d1f93` | 100 | `9cce5d7ff79806130e146eeb55f0d9cab33f400cc9c4d45d62e9df9b1b136d41` |
| OR | `16d96989dfc7fa5b80d31d8b1cb88b21a7a0054e` | `6e0926f711efe62add67953ee56bab9e243ffecc` | 81 | `9f922c450ab894f187a1c1722b4083027358d5dee140c1e3249c9c9ab29557ee` |

manifest 和本审阅回执按候选说明后附，不包含在上述 100/81 文件中。本评审未执行 add、commit、push 或修改 index。

- 证据：[README](README.md)、[冻结候选](candidate-manifest.json)、[源码盘点](source-inventory.md)、[后续比较口径](comparison-baseline.json)、[原主汇总](baseline/20261028-164553-272/method-summary/summary.json)、[两模块独立汇总](baseline/20261028-184530-895/method-summary/summary.json)、[方法集合差](baseline/20261028-184530-895/precondition-method-diff.json)、[XML 指纹](xml-manifest.csv)、[原始日志指纹](raw-log-manifest.json)、[OR 审阅](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/review.md)。命令原文、起止时间和退出码保存在各调用的 run.json/脚本中；raw/XML 本地保留，不直接加入候选。
- 已核查：需求简报、统一台账 §14.4、两仓 AGENTS/POM、相关环境与启停脚本、当前测试源/分库名单；候选 diff、index、工作区指纹、原始归档指纹、方法/覆盖 CSV、归因及恢复记录。未发现产品源码、迁移、工程入口、真实环境配置、旧未跟踪材料或 raw/XML 正文混入候选。
- 未覆盖：审阅者未重跑 Maven、PG、HTTP、前端或产品测试，未重探现时进程/端口，未读取真实 `.env`、`local-env.json` 或秘密。实际测试结果据本轮归档独立解析核对；PS5.1 为静态解析核准，实际运行解释器是 7.6.5。未把有界敏感信息扫描作为所有可能秘密均不存在的证明。主代理通知的后续 EX-01 环境配置示例与 SecretVault 引用语法适配，由 EX-01 另行核准/验证；本卡不表示全部文档已通过。

## 独立核对结果

1. 冻结候选的路径集合与 index 精确一致，文件 SHA256/大小吻合，index 相对冻结 tree 无差异，`git diff --cached --check` 为 0；两仓无候选之外的 tracked/unstaged 产品变更。AP 原有三个未跟踪目录/文件及 OR 原有审计目录未入候选。
2. 纯解析重演 AP 主轮加独立 gateway 补采：2579 testcase＝2206 passed／66 failure／303 error／4 skipped；277/278 源类有 XML，唯一 NOT_REPORTED 为 `GatewayPublishedRunProxyTest`，另有四个类级错误。原 fork 中断状态及原失败命令保留，补采未重跑已报告三类或已知死锁类。
3. 纯解析重演新前提下两个完整模块：app-management 23 XML、engine-runtime 93 XML，共1083 testcase＝950 passed／35 failure／98 error。`scope=SELECTED_MODULES`，不是新的全 reactor 结果。方法集合差逐项准确，共同非通过方法未发现被隐藏的 status 替换；原汇总未覆盖。
4. 三套 AP/OR 主汇总的所有已有字段、methods.csv 和 coverage.csv 均与当前解析所得逐行相等；nonpass.csv 等于 methods 的非通过过滤。AP 三份归因表的 `(classname, 原 name, status)` 多重集合准确覆盖373项（30＋328＋15），无漏项或重复。类级错误和参数化实例保持独立身份。
5. AP396、OR89份 XML 的清单大小/SHA256 全项吻合；新增原始日志清单 AP31、OR21份大小/SHA256 全项吻合。三主入口快照指纹、generation-replay 的解析器及原 summary 指纹已核对。候选通用 JWT、私钥、onto 凭据及 Authorization 字面量扫描未命中；作者已记录的当前秘密精确值/JWT 扫描亦无发现，未输出秘密值或 XML properties/system-out。
6. OR 原496项为495 passed／1 failure，六个无源模块单列不适用；原 P3 失败在 TCP 连通性阶段，未冒称分页实现缺陷。19180前提就绪后精确方法1项通过独立保存。OR 前端原269项含两个5000ms timeout、两文件31项单 worker 复验、完整33文件269项单 worker 复验分别保留；后者无文件过滤或 timeout 覆盖。41个格式问题仍红，未执行格式写入。
7. 两个 OR 旧跟踪证据确有 P2SupplyTest:409/707 的直接写入来源；本轮生成物已先归档且指纹吻合，仅恢复初始干净的这两个文件。恢复后内容经 Git clean 过滤等于 HEAD，原始字节差异仅 CRLF/LF；未恢复其他协作者文件。新建两个 AP 专用库、样例 HTTP 启动和未清理未知资源的边界均已保留。

| 问题ID/级别 | 文件:行号/场景 | 问题与影响 | 处理/理由 | 复核证据/状态 |
| --- | --- | --- | --- | --- |
| REV-01 / P1，历史已关闭 | `collect-baseline.ps1:3`；`collector-parse-check.json` | 无 BOM 的中文路径脚本不能据此声称兼容 PS5.1 | 五个新增脚本均为 EF-BB-BF；最终5.1静态解析回执已刷新当前 SHA | 当前五文件指纹/BOM吻合，回执均0语法错误；关闭 |
| REV-02 / P2，历史已关闭 | `collect-baseline.ps1:114,153,165,194,203` | 首次存储记录缺失、同 RunId 重跑污染、临时环境不恢复 | 存储记录 Test-Path 分支；测试阶段不可重复；PGPASSWORD/测试 URL finally 恢复 | 当前冻结脚本及独立 attempt 归档；关闭 |
| REV-03 / P2，历史已关闭 | `summarize-baseline.py:62,94,132,159,171,185` | 错组/额外类、suite 错计数、无源模块、fork 中断或局部范围可能假绿 | 拒绝不一致类/计数；中断单列；无源组为 null；补采与模块范围显式标记 | 当前代码、22项离线检查记录及实际归档重演；关闭 |
| REV-04 / 证据缺口，已关闭 | `raw-log-manifest.json` | 原始日志本地保留需可追溯指纹 | 两仓新增只含路径/大小/SHA的清单 | AP31/OR21逐项独立校验吻合；关闭 |
| BASE-01 / 保留阻断 | gateway 原 fork 死锁、1坏类/未报告类、4个 JDK8 条件跳过 | 原执行未完整；不能按 Maven exit0 或补采成功放行 | 死锁由 EX-35 承接，跳过及原错误继续逐方法保留 | 主汇总 collection_complete=false、tests_all_passed=false；未关闭产品问题 |
| BASE-02 / 保留失败 | AP 其余失败、前提变化后新观察；OR 原 P3/前端 timeout/格式红 | 采集完成不等于根因均证实或产品修复 | 按 comparison-baseline 逐模块/worker、逐方法比较；未知根因仍标待核实 | 原始/独立重验分目录，不拼装全绿；保留 |

- 逐卡结论：**EX-00 的基线采集、归因与比较口径可转完成；产品回归及发布放行不通过。** Spec：上述有限范围满足；Quality：未发现冻结证据候选的未处理阻断。不得据本结论关闭 EX-35、EX-27、EX-28、真实 IAM/模型或联合 J 组。
- 结论依据：本卡适用的需求/源码盘点、真实采集留痕、逐方法差异、独立评审、证据归属与回滚边界已具备。纯验收未改产品，不虚构“修复后全绿”；原坏类、类级错误、四项跳过及环境/worker差异全部保留。权限、错误信封、冻结候选与已应用迁移未被修改。
- 作者处置：前述审阅问题由作者修复，原红阶段和审阅意见有记录；本回执没有覆盖原失败。最终复核仅追加 README 导航及刷新当前五脚本解析指纹，分别纳入上述最终冻结候选。
- 审阅者复核：2026-10-28（Asia/Shanghai），最终候选以本页两仓 HEAD/tree/manifest SHA 为准。写本回执前后核对冻结文件及 index 不变；本次仅新增两仓 review.md，未改变冻结候选或运行产品测试。
