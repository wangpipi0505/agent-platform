# EX-08 第四批文档检查点独立复核

2026-10-29，审阅者 `/root`；文档及快照作者 `/root/docs_reader_check`。本审阅独立于本批文档编辑；EX-05实现的实质、运行及精确索引审阅另由各卡报告承担，不以本报告替代。未使用Superpower。

结论：本次七个文档/快照文件及本报告可精确本地提交；EX-05保持完成，EX-15a与EX-08仍进行中。当前三主入口仍是唯一执行入口，归档副本只用于追溯。

## 实际核对

- 逐行重算主台账：39个EX为10完成/3进行中/26待启动；48能力为9完成/21进行中/12待验收/5待启动/1暂缓。两个分母不混算，EX-06没有因准备稿存在被提前升级。
- AP `824294a451f2a7a8a5986183897358343a56525d`及OR `f19299681a2a54d60cace3672a56cf4bf8fef7e6`是真实本地提交，14/62文件及parent/tree与增量说明相符。原各卡实质/索引审阅可定位；OR最终154门禁、生成/消费类型/完整269前端及构建通过与原41格式红分列，旧失败原件未抹去。
- 三份原文件与新快照逐字节SHA/字节数一致，版本v2.15/v1.15/v1.15；均保留无BOM的UTF-8及CRLF。清单明确snapshotOnly且executionEntry=false，未声称仓外原文件在产品提交中。
- 主线程本轮确实执行了EX-15a Full及Adjacent，原生和采集退出已取回。Full71XML/698=582P6F106E4旧S、方法/原116原因门通过、allGreen=false；Adjacent5XML/32P/noignore/0skip。新增文档如实引用，不把文档作者读取结果写为本人运行。
- 已阅读卡片及手册§2.5/§2.6和台账当前收口：网关精确POST/path/MediaType的65MiB总wire范围、其它2MiB、下游64MiB、实际计数和原auth/error保持；Controller/上游HTTP测试与正式18080入站/真验签/两个JVM和skill新PID验收分层，没有用组件冷重建替代进程重启。
- live仅本轮新空DB，精确artifact归属/对象键清理，不使用cleanTenant；新证据脚本存在与否均不能当实跑通过。V306实际持久链与旧V318/全内存历史标识已纠正。EX-15a后续网关/正式JVM尚待，与进度表一致。
- EX-08 README的已有正文保留，仅顶栏新增本轮和前轮历史索引。属性增补只对本次明确文档/快照路径保真，无source或glob豁免。文档diff-check退出0。

## 精确快照

| 文件 | SHA-256 |
| --- | --- |
| 优化台账-统一-20261028.md | 406ec75eae3244ceb7ce7b28a59137967a31056fef1564a4beb702eee39d669a |
| 执行卡片-统一补差-20261028.md | 180be955fa82036806eec20dd7ac1c551ae2972c049f7896bd0d2aceac41846d |
| 联合验收与回归执行手册-20261028.md | 61c1324dfabec6db9ca979d80a0f28bad89dcfb4bf1acd86be39a097eae59c5a |

本次应提交恰8文件：EX-08 `.gitattributes`、`README.md`、`incremental-20261029-ex05.md`、本报告，以及`main-entry-snapshot-20261029-ex05/`三原样副本和manifest。提交前仍须精确核index及源文件不变，禁止夹带EX-06准备稿、EX-15a进行中源码/证据或其它未跟踪文件。不执行push/tag；后续新状态另增检查点，不改写本快照。
