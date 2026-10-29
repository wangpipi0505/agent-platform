# EX-15b PhaseA 实际 GREEN30 非作者复核

2026-10-29，`artifact_boundary_review`。结论：接受 `phase-a/runs/phase-a-green-20261029-01` 为本子步真实 GREEN：同 30 个完整身份全部通过，native0、collector0、0F/0E/0S；原 RED 的指定三个失败恢复，原 25 与新增五方法通过。这里只确认对象顶层布尔开关、缺失输出路径与绝对输出路径三个边界，不认领完整 PROCESS 文件 I/O 或全卡完成。

审阅者未编写两生产修复及本轮 GREEN 采集器；此前参与过测试候选绝对路径夹具修订，测试作者关系沿 `phase-a-collector-review.md` 披露。该已封版旧报告 SHA `AE2FE743A285F974F60F747B050ECB36E107EABB1DEC66DB2E6B40EF2178DE15` 保持。本文只读取原件、XML、文件摘要和源码；没有运行 Maven/Java/PG/HTTP/Node、没有修改代码、旧 run 或 index。正在另行编写的后续回归入口由主线程独审，本文不是对自己回归候选的独立审查。

## 实际原件与独立核验

| 对象 | SHA-256 |
| --- | --- |
| 实际 GREEN runner `collector.ps1` | `4CA466409538EE7754D21760B99D4304788C7412F82DBF3C059C494A7898E76F` |
| 实际 `approved-manifest.json` | `17184F287929512A82EDA335B5267E82DD04C8654628002A3819727E491CC195` |
| `admission.json` | `4DE741F8C269F70BE120479B58AA4D99A301FA05007E7DC85580FCFC1FDAA79C` |
| `summary.json` | `5080A1E987297FAAF80C8985776AE42E58991FC1682C14B5A1ECA8A9C73E507C` |
| `source-after.json`（与 before 字节相同） | `520C209CF8F427C60F99DB547760133AFC77F164C6576EBD15017E82BC1932AB` |
| `context-after.json`（与 before、RED context 字节相同） | `FEB345BB404BFB36CF0F64913B3940A75B81D9371D9AAF41F12AB784552416AB` |
| `methods.csv` | `0266D599C286F5746921FE20D0A6551CB62794B0F1A996E38463D1CB6730E15A` |
| `native-exit.json` | `C8F8B57BD915C23F6743FF44D30A218BCF80FF799500E3012C7C59734DAD7099` |
| `collector-exit.json` | `299478A4A58108227C42F8BD3C456B9FD753EFEA236F0D53923AC7198FD99EF4` |
| `xml-manifest.json` | `C498C0ADF1E2855831176D27AA7A7EE96B8D259E9569EDF94273304EF20EF862` |

显式 PS5 内使用 `XmlDocument`、`XmlResolver=null` 逐份读实际 XML：3 份共 30 个具名 testcase，suite 总数匹配，failure/error/skipped 元素均为 0；每个完整 classname/method/displayName/suite 身份与 CSV 及原 RED 相同。原 RED 的 27P/3F 变为 30P；指定三失败方法没有改名、丢弃或跳过。原生时段 `2026-10-29T12:28:09.7947904Z` 至 `2026-10-29T12:28:20.5992900Z`，native 先保存后解析，failureIgnore=false。

三份 fresh XML 摘要逐一匹配实际 manifest：

| XML | SHA-256 |
| --- | --- |
| `TEST-cn.agentplatform.skill.application.SkillProcessRegistrationTest.xml` | `6C9978E189F976B445A7B31C898607442A23EA44B983B8090613424BEFF8C02D` |
| `TEST-cn.agentplatform.skill.domain.ProcessContractTest.xml` | `947609F59846E094224E56BB63BB5EB0235577FA910949C66C6D7DDDB1A1F905` |
| `TEST-cn.agentplatform.skill.infra.ProcessFilesTest.xml` | `87536FA8A87A74199E8160C3B0A22E051FE8C809B09F39E93A4A6EA25FFE425D` |

RED 与 GREEN 的 Maven 参数数组逐项相同：相同三个类、`failure.ignore=false`、1024 MiB 堆、原 900 秒 Surefire 预算、无 clean/install/-am、无 fork/reuse/order 变更。测试文件仍是 94A0E0…、65ED5A…、6E4A4A…；独立比较 152 源，GREEN before/after 与当前全量相同，较 RED 恰两个生产文件变更：ProcessContract `8D8030F53F24488C3880FEED71BBB6E7E3A42ED19FC416B63366505C41205B88`、ProcessAdapter `00D30C03B2F63079908BA98AC89992DBD6304D8025E7B5DA756F5C9D20BD134C`。HEAD 为 `6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946`。

从每份 XML 的实际 `java.class.path` 按分号拆解，common JAR 候选恰一个，完整绝对路径等于本 AP `.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar`，实文件 SHA `0FBB83F6E6ADCF9E36BA27345602AA93AA4769D863CF2635FE0357EC7DA29A93`；无其它 common/test JAR。三份 CP 字符串相同，运行记录中的精确 CP 门通过。common test JAR `0CA864F0…AB732` 继续保守 pin，但不谎称其在该 classpath 中。历史 Full701 的 1B0D 主 JAR 原件不改。

两处产品差异与原三红的对应关系已在 AE2FE 报告静审：严格 bool 校验及启用时的相对 output 路径/存在性检查。当前路径门不证明祖先无 reparse、不证明稳定句柄或防替换，也未物化输入、读取输出内容、持久化 artifact。路由测试中的 ArtifactStore 零交互、注入身份和真实自有子进程不能替代 PG/MinIO/正式入口认证验收。后续 Adjacent88、Full706 及完整文件链仍需单独实际运行与独审。
