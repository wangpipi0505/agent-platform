# EX-15b PhaseA Adjacent88 实际非作者复核

2026-10-29，`algorithm_interrupt_readonly`。结论：接受已结束的 `phase-a/runs/phase-a-adjacent-20261029-02`，原83项与新增5项完整身份均通过，10 XML / 88P / 0F / 0E / 0S，native0、collector0；未发现阻断本阶段验收的问题。这里只接受 PhaseA 的相邻回归，不关闭完整文件 I/O 或 EX-15b，不引用仍在运行的 Full706 结果。

审阅者未编写本次88采集器和两处产品修复；此前编写过依赖 GREEN30 的 `4CA466…` 采集器，故不把自己对该入口的检查冒充 GREEN 独立审查。GREEN30 的非作者实际复核由 `phase-a-green-review.md` 提供，SHA `D7F720EE4EE39D9F7E35CBF9E760FC74E4F8012257BE7C3B8CB0D85EE5DCFE02`；本报告核其被本轮精确绑定的原件与来源。本文仅做文件/XML/源码差异和摘要核验，没有运行 Java/Maven/PG/HTTP/WSL/Node、连接服务、导入环境文件或修改 source/index/旧证据。

## 精确实际输入与结果

| 原件（均位于上述 actual run02） | SHA-256 |
| --- | --- |
| `approved-manifest.json` | `6E42B9B0CD8BD3F417531F0F2EB6D03B10112EABD3762AC3114D8EE0BC33EC24` |
| `collector.ps1` | `8A93C1255DBA3623609ED11EFC860B27462458AE58FA81E2F2A8378627904BC1` |
| `summary.json` | `CB3FBF6F19915C09FCE38BAC5C6938C02F05A3DB9B8580DFB7797A7CEC6CC21A` |
| `methods.csv` | `509750515EEAA815CA9692BE4764C71E2C409D2814A11FC47342904578260FED` |
| `xml-manifest.json` | `C6D3EB374DE34C1E54C18F80E8735DB39B5958CAAB80006DB097FFB47D07476B` |
| `source-before.json` 与 `source-after.json` | `520C209CF8F427C60F99DB547760133AFC77F164C6576EBD15017E82BC1932AB` |
| `context-before.json`（与 after 字节相同） | `127225B6C4638A2F190409D6F4A239255BE0FE6DE5EFC7E1A5A59DAEB05D029E` |
| `before-storage.json` | `05E78DA7AECDE99F9FC972AE28F26A78F344DED8E8EE9B9C6646B85F7CD05C12` |
| `after-storage.json` | `68A6F3A9D871F3FA1EC1D293E93B5396AC3A541A6A17E7396812C37187BEC608` |

当前公开 `phase-a/regression-manifest-v2.json` 已为后续 Full 封版为 `BB6DDEA557F0FEFD56A9249C594478D3914B5F7133D2D20FC1D9FCAE969CA12F`。它不是本次88的实际输入；不将它倒填为 run02 的 `6E42…`。本次实际 HEAD 为 `6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946`。

独立重读实际10份 XML（`XmlDocument`、`XmlResolver=null`），每份 bytes/SHA 与实际 `xml-manifest.json` 相同，mtime 均不早于本次 Maven 起点；原83基线的10份 XML 同样逐份核摘要。用已冻结 reader 读取原83与实际88，再按 classname/method/display_name/suite_path 比较：原83加 manifest 明列新增5，恰88个唯一身份，缺失0、意外新增0、全部P；XML 对 CSV 的 record_kind/status/type/cause/项目首帧/message/detail 字段差异0，解析问题0、类级事件0、skip0。这里没有用总数相同替代身份比较。

83项基线为 `docs/evidence/2026-10-28-EX-27/response-ownership-full/runs/adjacent-20261029-01`。新增5项分别为非布尔 files 开关拒绝、false/legacy stdin 保留、规范化序列化读回，以及两个真实自有 worker 的缺失/绝对输出拒绝；该两个路由负例仍要求502/PROCESS_OUTPUT_INVALID且 ArtifactStore 零交互。它们不等于真实 PG artifact 文件链验收。

原25项及其余原相邻方法身份、断言未删除或放宽。当前三测试较已提交原件仅增加本次五方法及其有界夹具分支；旧 `execute(mode)` 仍走 false 分支，原 empty/escape 场景保持。ProcessFilesTest 原 `@Timeout(60)`、process timeoutMs=20000、future.get(25秒)、caller awaitTermination(10秒)保持。实际 Maven 命令仅显式选这10类，failure.ignore=false、1024MiB/900秒、默认 fork/reuse/order；无 clean/install/-am、skip 或诊断开关。native记录在解析报告前保存，实际区间为 `2026-10-29T12:52:35.5351003Z` 至 `12:52:54.4750034Z`，raw.log 的现字节 SHA 与归档摘要相符。

## 源码、依赖和存储边界

当前 `skill-registry/src` 恰152文件，与本次 before/after/approved manifest 全部路径和 SHA 一致，零差异；相对原 Full701 恰批准的两生产＋三测试五路径变化。生产为 ProcessContract `8D8030F53F24488C3880FEED71BBB6E7E3A42ED19FC416B63366505C41205B88`、ProcessAdapter `00D30C03B2F63079908BA98AC89992DBD6304D8025E7B5DA756F5C9D20BD134C`。三测试仍为94A0E0…、65ED5A…、6E4A4A…；未夹入 engine 源。因此 engine SSE 测试后续变更不构成本次152 skill来源漂移，也不能据此宣称整个 engine 与较早 GREEN 同源。

两产品修复分别为顶层 stdin.files 严格布尔校验、仅启用时拒绝绝对/非法/非 output 相对路径及不存在普通文件。未改变旧关闭分支断言；路径位置与存在性门不证明祖先 reparse 防护、稳定句柄、防替换、字节复制、SHA/配额、持久化或回放，后续完整 I/O 验收仍需实现与实跑。

逐份 XML 直接拆实际 `java.class.path`：10份均恰一个 common候选，完整绝对路径精确等于本 AP `.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar`，实文件 SHA `0FBB83F6E6ADCF9E36BA27345602AA93AA4769D863CF2635FE0357EC7DA29A93`；其它 common及 tests JAR均0。全部 CP 值 SHA 为 `C4A9BC77B5786D2084E37124AFE408B048C6CB13371282804C7813FE33B04406`。10个 context 文件 pin 与 actual manifest相符，before/after context原字节一致。历史 Full701 使用的旧1B主 JAR不倒写为0FBB。

前后两份存储收据均为本仓 PG 25432/PID15204、已核数据目录，MinIO29000健康。五个固定库均存在、owner/schema/history owner均agent_v12：agent_platform_v12、agent_platform_skill_algorithm_test、agent_platform_skill_algorithm_migration_test、agent_platform_skill_digest_test、agent_platform_ontology_import_test。逐库核1条合法rank0/SCHEMA/"skill"/null version和checksum，加16条300–315 SQL；每条script/version/checksum/rank/installedBy/success符合已冻结表，前后五组history全字段相同。collector源码仅SELECT，PGOPTIONS为read-only及5秒statement_timeout；缺失/未知归属拒绝，未建库、reset、DDL或清旧队列。收据的 originalReplay 是原Full允许库分类；本次 Adjacent 的 selected replay列表为空，不称本次执行过两迁移重放测试。这里是对既有真实收据和采集代码的审查，没有重新查询PG/HTTP。

## 保留失败与验收限制

首轮 `phase-a-adjacent-20261029-01` 原件保留：collector2/native null，ENVIRONMENT_AND_READONLY_STORAGE 阶段 APPROVED_MAVEN_MISSING，未进入 Maven，不能计为产品RED或用run02覆盖。旧2632/3916候选及本轮8A93/6E42原件不改。

本次 Adjacent 不包含 SDK类；summary 的 sdkDefaultPassed/ownershipWitnessPassed 是本模式的条件门，不能解释成实际跑过 SDK6或四outcome。独立 XML读取显示本组SDK outcome0、diagnostic0。原 Full701 的116非通过、4旧skip及raw=false保持其历史含义；本报告不接受或修改任何Full结果，也不关闭完整EX-15b。后续Full706须按自己的实际manifest/源码/原件另作相对回归与原因独审。
