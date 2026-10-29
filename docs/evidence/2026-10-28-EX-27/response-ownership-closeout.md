# EX-27 响应体交接子步：公开提交候选说明

2026-10-29，准备者 `/root/algorithm_interrupt_readonly`。本次只准备精确公开清单及证据属性，不暂存、不提交，不运行 Java/Maven/PG/HTTP 或产品，不使用 Superpower。提交与索引独审仍由 root 另行处理。

## 两份源码的边界

- `skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java`，工作字节 SHA256 `75F89B938A054BE6AC385C7152F49A9525D25E696BCE3634C42C7BD3201AE907`。仅原 await 成功值交接的局部 once/closeOnce 三路径，原 stage/Call/200ms 生命周期与其余产品逻辑不改。
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadResponseOwnershipTest.java`，SHA256 `40474F829DEBD58D0B1D689D8F139F5BD89E2B2A07B298470D90C870960397F6`。真实 SDK/HTTP/future/response/Call 的原内部 await/close 边界，不冒充完整 offload/PG 业务验证；原五 SDK 测试源码及预算未改。

**不纳入** EX-15b 的 ProcessAdapter 或 ProcessFilesTest，也不纳入 EX06、SSE、V222 或本卡其它未完成范围。Full 的源摘要会如实引用同工作上下文中的 PROCESS 文件；引用摘要不表示它们属于本次公开文件白名单。

## 已完成证据及实际 Git 上下文

独立运行终审为 `response-ownership-runtime-review.md`，SHA256 `1544804F75C758AD4D6EF511AF18905390CD82164CC49AF8EFB005EDC4611D1D`。本准备者读取这些结果，不声称自行运行：

| 阶段 | 实际结果 | 边界 |
| --- | --- | --- |
| 新 Ownership RED | 1F、native1、collector0 | 唯一原终局 q 断言失败，真实原 Call 留存为1；前置未失败 |
| 同测试 GREEN | 1P、native0、collector0 | 同40474F测试，真实原 Call 归零 |
| SDK | 6P / 2 XML、native0、collector0 | 原五身份加新一身份；四 outcome、0诊断 |
| 相邻 PROCESS | 83P / 10 XML、native0、collector0 | 同152源工作上下文；不将其两份 PROCESS 源夹入本提交 |
| Full | 701 / 73 XML；585P、6F、106E、4旧S | 相对回归门=true，allGreen=false；原116非通过保留 |

全部实际窗口的 HEAD 是 `acad6bdee223b23f331420c6de50045cb5fa8941` 加未提交的精确152源工作上下文，包含本子步以及 EX-15b 路径补丁。不能把它写成 acad 提交树已经包含这些代码。原698基线加 ProcessFiles 两身份和 Ownership 一身份形成701，新增非通过/跳过/缺失为0。

随后 EX06 的独立本地提交 `73a4ada6de5ed5ee34f623a8a435c949040a2e7d` 是运行结束后的 Git 事件；它没有重跑本 Full，也不改变原运行 HEAD。当前提交准备以73a4为父上下文，只提交上述两份源码及本子步安全证据，不能宣称两份源码所在提交树已执行了相同701集合。

Full 原 `failure.ignore=true` 仅为完整采集：native0、collector0、方法相对门、raw原因门、allGreen分别记录。原116项结构/原因保持；唯一原 migration 的有界 UUID/timestamp 表示变化保留原 `rawReasonComparisonPassed=false`，解释后原因门=true。**不将 Full 写成全绿，不关闭 EX27 整卡，也不追认本次确定性红为原 Full700 的唯一根因。**

## 精确公开范围与本地原件

精确白名单为 `response-ownership-public-commit.json`，包含两份源码、本子步候选/独审/运行终审、已冻结采集器及 JSON/CSV 摘要、本文和逐路径属性。manifest自身列在精确路径集内，为避免自引用不内嵌自己的摘要；最终摘要另交 root 与独立审阅者。不得用目录级 git add 扩围。

不公开原始 XML、raw.log、pg.raw.log、其它 `.log`、`.env`、私密配置内容、四份重复 product-source/test-source 原字节快照，也不公开旧 EX15b 或其它卡目录。它们留在本地原位置，未覆盖或删除；公开 xml/raw manifests 中只有文件名、摘要、字节数等安全元数据。

公开证据故意不携原始 XML。故从全新 Git checkout 直接重算原 XML 的116原因或执行所有历史 pin 需要另行取得已授权的本地原件；不能将公开清单宣称为独立于本地原件的完整回放包。独立终审已经重算原件，公开摘要/原原因SHA/精确比较结果保留这一证据链。

凭据检查只在内存读取 AP env 与进程环境中的非空凭据值，与候选公开文件作精确匹配，并查 JWT/私钥字面模式；实际值不输出、不写入报告或清单。检查不是凭据脱敏操作：如命中应停止，不能修改已冻结原证据来隐藏问题。

两份 Java 源继续沿根目录既有 text/eol 规则，仅允许 CRLF→LF 的 Git clean 归一；manifest分别记录工作字节SHA和预期blob。证据逐路径 `-text -whitespace` 保持原始字节，不给产品增加该规则。EX27原属性文件只追加本次顶层新文件规则，原前缀原样保留；两新子目录各有精确nested属性，无通配放宽。

本清单交独立审核及 root 精确索引检查；提交前仍须核路径、blob、工作字节和只允许的换行归一。当前不标记本地提交完成或整卡完成。
