# EX-15b PhaseA 精确子批提交准备

本子批只交付对象顶层 `stdin.files` 的布尔校验，以及显式启用后的输出相对路径、output 目录位置和普通文件存在性检查。两生产文件共31行增量；原三个测试源的25个方法与断言保留，增加5个方法。绝对路径负例先在已核验的本次 callDir/output 创建真实空文件，其余声明合法，绝对性是唯一故意违反项。

本材料是提交准备，不是已经 stage 或 commit 的回执。精确集合为29文件：两生产、三测试和一份工程增补共6源；本目录原10份证据加5份新公开材料；父目录7份既有精确证据和父 `.gitattributes`。ROOT 已完成 MCP 独立提交 `b4918591a8d899d260ab365106ca798554d2b81d`（parent `73dc4cf37a2894f5c20fe806908011091888383b`，tree `5cc6977aa584becd1e22a5daa591daeb131316e9`），本候选 preparedParentHead 已固定为该实际提交，sealed=true；仍须非作者 exact29 index 审查。MCP 最终manifest SHA 为 `0A42D127DDA4497DBE1B301C250067BA9E3AFB082C60347FEC48586E75DA9F8A`，最终独审报告已在该父提交中逐字核对。不会将较后的提交号倒填到历史运行。

## 实际验证与历史保留

| 实际运行 | 结果及含义 |
| --- | --- |
| phase-a-red-20261029-01 | 30=27P/3F/0E/0S，native1/collector0；原25和两个新控制项P，三条指定应抛异常而未抛的业务红 |
| phase-a-green-20261029-01 | 同30身份全P，native0/collector0；原三红恢复，三测试及预算未改 |
| phase-a-adjacent-20261029-01 | APPROVED_MAVEN_MISSING，native=null/collector2，未进入Maven；保留原失败，不计产品红 |
| phase-a-adjacent-20261029-02 | 10XML/88P，native0/collector0；原83加新5无丢失 |
| phase-a-full-20261029-01 | 73XML/706=589P/7F/106E/4旧S，native0(ignore)/collector1；新增旧P→F为MCP blocked ACK方法，原raw及回归门false保留 |
| mcp-terminal-full707-20261029-01 | 73XML/707=591P/6F/106E/4旧S，无新增skip或状态变化；native0(ignore)/collector1，原rawReasonComparisonPassed/regressionGatePassed/allGreen仍false |

RED30、GREEN30及Adjacent88成功轮的 HEAD 为 `6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946`。Full707运行 HEAD 为 `73dc4cf37a2894f5c20fe806908011091888383b`，使用当时包含PhaseA两生产/三测试和MCP两源的联合工作树。五份PhaseA Java字节在Full707中保持，不能把该运行写成只有本子批六文件的隔离构建。

[MCP与PhaseA最终非作者复核](../../2026-10-28-EX-27/mcp-terminal-error-review.md) 已封版，SHA `D2E3C3FC1B230AFC685958ACCFCD81F62F54980877925CBD1CFD0AEEE2CC965A`。该报告按完整身份核实原PhaseA30/30和Adjacent88/88在Full707均P，同时MCP39、原SDK5和ownership1均P；152源、189上下文pin、精确classpath与五库前后守卫通过。

相对接受与原false并列：原116个非通过中115项原始message/detail完全相同；剩余固定迁移方法只在本次明确的原件对中出现 `tenant_id":"algorithm_upgrade_` 两处UUID及 `updated_at":"` 两处时间的变化，类型、cause、首项目栈帧和其余字符、重复值关系均保留。最终独审接受这一个原件对的表示变化，不接受通用归一化或未来豁免；该旧方法仍为F。`full707-bounded-reason-proof.json` 为实际Full707的C050原字节副本，原始门和原XML均未改。

RED/GREEN、Adjacent的非作者证据分别引用父目录 `phase-a-collector-review.md`、`phase-a-green-review.md`、`phase-a-adjacent-review.md`。本提交清单准备者参与过测试候选和部分采集器，不将本清单冒称独立index审查；ROOT仍须安排非作者逐路径、逐blob核验。

## 契约与实现边界

`contracts/process-files-io.md` 当前SHA `465646D3C46F79191E2E8C4C8E109F046D7B004EF6E3A7626FE4FB0F29C4D637`。原194D文件保存在本仓私密归档；逐字反向替换首段状态说明即可得到原件，只有第3行状态段落变化，未改后续契约语义。既有契约非作者报告审的是194D原件，本次状态更新不是声称重新实现了完整I/O。

旧 scalar/array stdin及其它字段保持；对象顶层files缺省/false不启用受控I/O。旧同名键的非布尔值（含null）过去允许、现在有意拒绝，不能表述为所有legacy输入完全不变。完整arguments必须先经过原schema/guard；false/缺省时业务arguments.files与真实worker信封的完整保持，以及启用后的参数复制移键，仍需后续专门链路验收，当前树比较/路由测试不代替它。

当前不提供输入物化、输出字节/SHA/配额、稳定句柄或防替换、完整链接/reparse防护、artifact持久化/补偿、二进制回放或正式新JVM读回。原stdout/超时/身份及进程收敛预算未放宽；路由测试的ArtifactStore零交互不是真实PG文件链证据。完整PROCESS I/O、PhaseB及EX-15b仍进行中。

## 安全公开边界

公开集合只含上述源码、工程增补、静态采集器/manifest、说明、独审报告和安全元数据。所有runs目录、原始XML/CSV/log、环境文件、凭据、JAR、进程记录、依赖、其它卡及未来PhaseB均排除。引用原件路径与SHA用于追溯，不等于将原件纳入提交。

公开Git集合不是独立完整回放包：采集器及有界原因证明需要本仓私密原件、既有历史run和准确现场环境；无法仅凭本公开子集重新复算原始原因对。新增属性仅精确枚举本叶15文件和父目录7文件，保留既有父属性规则，不对生产源或未来目录使用通配放宽。最终manifest自身SHA须由ROOT外部回执固定，避免循环自哈希。
