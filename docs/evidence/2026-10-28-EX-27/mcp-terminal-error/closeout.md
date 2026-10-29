# EX27 MCP 终态 ACK 错误：已封版安全公开候选（未stage/commit）

2026-10-29，准备者 docs_reader_check。本批仅封版登记的17条精确路径，未stage/commit，未修改源码、原collector/manifest、raw原件或三主入口，未运行Java/Maven/PG/HTTP/Node/WSL/服务。正式非作者报告mcp-terminal-error-review.md已经root交接并核实：14,810B，SHA D2E3C3FC1B230AFC685958ACCFCD81F62F54980877925CBD1CFD0AEEE2CC965A，Git blob 9c761b57e6f89cd2176ace73bebc05fc1e3792b0。sealed=true仅表示本次公开候选内容已冻结；相对技术接受单列，原始false门及整卡开放状态保持。

## 精确范围及来源

17路径=2源码+叶目录11文件+根目录3候选/审阅文件+父属性1文件。完整白名单见public-commit-manifest.json.files，禁止宽泛add目录。17中已包含真实正式交接且已停写的mcp-terminal-error-review.md；本批补入其准确bytes/SHA/blob，没有另增审阅或索引报告路径。manifest自身不嵌入递归selfhash，最终SHA/Git blob由root外部pin并另做精确索引审核。

prepareParent与actualRuntimeHead均为73dc4cf37a2894f5c20fe806908011091888383b。实际Full707使用该HEAD加7个SKU源码工作树差异：PhaseA的2生产+3测试，以及本批MCP生产/测试。本批只提交MCP两源，另5个PhaseA源和process-files-io.md契约、其它卡及untracked全部排除；不能把完整工作树说成已提交或两源单独复现整套现场。

| 两源 | 当前SHA-256 | 精确改动 |
| --- | --- | --- |
| BoundedMcpSseTransport.java | 83E9568637311DDAC603B4780124F996C68560B5D6E97C5A1C75CC2E005260B7 | sendMessage原catch内3+/1−：先快照closed或deadline终态，再fail(ex)保存失败，仅活动期保留throw |
| McpAdapterTest.java | 068002325D7D224FB685DBB3476AEBD27CEDFF170430E115BDF1008ADC9F282F | 原文件新增203行，仅新增closedSessionDoesNotDeliverLateAckFailureToCancelledRpc及其真实SDK/loopback观察与清理辅助；原38身份/断言保持 |

## 实际结果与原失败

- RED：mcp-terminal-red-20261029-01，1F/0E0S、native1/collector0，精确新方法lateErrors断言成立；准备或编译失败不计行为红。
- Green：mcp-terminal-green-20261029-01，1P/0FES、native0/collector0，仅该新增方法。
- Class：mcp-terminal-class39-20261029-01，39P/0FES、native0/collector0，原38加新1；包括原blockedAckBodyCannotOutliveTheWholeSessionDeadline。
- Full707：73XML/707=591P6F106E4旧S，native0使用failure-ignore、collector1；0漏多、statusChanges0、0新skip。152源与189上下文前后稳定，CP及五PG/MinIO原边界通过；collector不执行DDL/清队列。MCP39在同Full中39P，默认SDK5及ownership1均P，4outcomes/0diag，严格Call0/retainedOriginalCall=false见证齐。
- 原Full707 rawReasonComparison=false、reasonComparison=false、regressionGate=false、allGreen=false永久保留。唯一原迁移case两UUID+两timestamp差异的安全proof已按原字节复制，SHA C05049EC2D7DA11BE2A391C2138B8828A12CE026EF76B0DC049F45B4CE398775；原proof仍是collector未自动接受的候选；正式D2E3独审已另接受本次固定XML/hash对：115旧非通过原始message/detail相同，唯一tenant_id algorithm_upgrade_两UUID槽位和updated_at两timestamp槽位变化，捕获外全部字符相同，message/detail联合重复值双向绑定不变。该接受不改raw collector、不把旧F改P，也不授权未来自动归一化。
- 旧Full706=589P7F106E4旧S、native0(ignore)/collector1和原MCP失败保留；原Full701来源/原116非通过和4旧skip也不倒写。新Full及定向绿不追溯覆盖旧失败现场。

正式D2E3独审还从原XML逐身份确认PhaseA30及Adjacent88在同Full707的精确子集全部P；本次五库前后各17行history、MinIO、SDK5+ownership1及原witness均通过。这是已发生实际run的独立复核，没有重新执行业务。

另有contracts/process-files-io.md状态文字在Full707之后更新为465646D3C46F79191E2E8C4C8E109F046D7B004EF6E3A7626FE4FB0F29C4D637；该契约不属于MCP源码或本批17路径，不把这份较后4656文案倒填为Full707运行输入。

## 属性、raw/clean及安全边界

父.gitattributes保留HEAD73dc原25,978字节前缀（SHA B6696AE7AD98CE15830B8AE237679E51A47A91E4EF3670934D3AD2CD14410E61），只追加三个根文件的精确-text/-whitespace规则。叶目录仅* -text -whitespace以冻结证据字节；这不授权提交未来未知文件，最终仍恰17路径。源码沿原text=auto/eol=lf过滤，不修改源码属性；manifest分别记录rawSHA、cleanSHA、expected Git blob，self及待审文件明确排除未有摘要。

只公开三个现存collector及其三个manifest、候选md/patch、最终待审报告和安全摘要/证明；实际raw日志/XML、PG查询内容、JAR/依赖、.env/凭据/secret原件、重复源快照、其它PhaseA/MCP外候选均留私密目录。localOnlyReferences仅路径/尺寸/SHA元数据，不是提交文件。

秘密扫描按root指定规则从.env仅内存提取PASSWORD/SECRET/TOKEN/KEY键的值，Trim引号、length>=8、排除env:/vault:、unique；其中SECRET_DIR仅为非秘密配置路径的额外字面检查，不输出或持久化实际值。正式report已纳入，本次按同规则对全部17个候选内容重扫，实际7个内存值、literal/JWT/private-key命中均0，未输出或持久化值。结果写manifest的securityScan。最终index集合/blob/自manifest外部pin仍需由非作者另审。

## 限制

新增例确定覆盖already-closed分支，不宣称穷尽deadline竞态，终态快照也不是原子取消协议。保持fail首次失败、原预算、活动期错误、activeSends/reader/HTTP收敛和原SDK来源，不做全局吞错。本子步不关闭完整IO或EX27，也不替代Process/files、EX06或其它实际验收。
