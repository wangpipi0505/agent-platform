# EX-03 独立只读评审（阶段记录，尚未终审）

- 对象：本体 manifest 导入的显式 API_KEY、凭据准入，以及实际保存所需 ONTOLOGY 来源六字段分支；范围仅三个获准 Java 文件与本卡证据。
- 实现作者：`/root/ex03_manifest_auth`；既有测试预审：`/root/ex02_independent_review`；本轮独立审阅者：`/root/ex03_closeout_review`，未参与候选实现或采集器编写。
- 本轮只读源码、Git 差异及已有证据，并新增本文件；未运行 Maven/Java/PG/HTTP/Node/前端或服务，未读真实环境文件/密钥，未改产品、测试、主入口或 index。使用 Python 仅离线解析已有 XML/CSV/JSON、重算文件摘要；没有执行保存的采集脚本。未使用 superpower。
- 审阅日：2026-10-29（Asia/Shanghai）；当前读取 HEAD：`ae2d1860071c9e49d07e80ecace331e41f898c9d`。历史 Target/Full 的实际 HEAD 以各轮环境记录为准，不将当前 HEAD 倒填为当时身份。

**阶段结论：三个源码及本卡定向证据未发现需回改的实质阻断；完整 Skill 新增 GET_BODY FAIL 仍未关闭，最终模块门、冻结候选/index 和提交待 root，本记录不签收整卡完成。**

## 精确源码与调用边界

| 文件（仓内） | 当前 SHA256 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java` | `41450FAAC9141E0E3393D7A9ED45B5E5F67E7D09DA787F448DDBE86565F24CA5` |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java` | `7C98CAE7587DA4F64BF70E93E9540A16F097BFF885DE7EA158E147FE8D491B03` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyManifestImportsTest.java` | `97D0A6B1B48ECD833568C9E3F584303FEEF6D2A094BDAE6979AEC36353432051` |

已读本仓 AGENTS、implementation-brief、contracts/README，三主入口的 EX-03、回归/DoD 规则，既有 [预审](pre-review.md) 与 [最新结果](green-and-module-regression.md)。AGENTS 的设计短路径是历史指向；本轮未修改冻结设计/契约原文或依赖。

- 唯一生产直接调用方为 `OntologyImportController.create`，以既有 2MiB 请求界限解析 Map 后调用 `importManifests`；没有需要同步扩展的 DTO。`toSkillBody` 仅被同导入器及测试直接调用。
- 导入器先限制数组，再解析显式或缺省 API_KEY，沿用共享 `AuthScheme.parse/checkCombination`。NONE、BEARER 与当前消费协议冲突会拒绝，未知/空显式声明也拒绝；没有丢弃调用者声明后强制覆盖。每个输出契约明确保存 API_KEY 与原 opaque authRef，解析后的密钥值不进入契约或返回。
- `Actor.current().requireRole("ADMIN")` 位于 `SecretVault.resolve` 前；所有凭据验证和全批结构转换/重复编码检查位于全部 `catalog.create` 前。主卡没有要求所有结构转换早于凭据解析，不为箭头顺序提出无价值重排。逐个 create 仍使用原目录事务语义，创建期编码冲突可保留此前草稿；本次没有承诺跨整批数据库原子性。
- SecretVault 使用现有当前 Actor 的租户绑定；跨租户 `SECRET_SCOPE_DENIED` 403 原样保留，其余不可用/非法引用归为固定 400 文案，不附异常消息、引用值、路径或密钥。未修改共享 AuthScheme/SecretVault、手工 HTTP 默认或 legacy 开关。
- Catalog 改动仅在 importSource：ONTOLOGY 精确六键 `kind/toolCode/spaceCode/releaseId/version/contentDigest`，值均为非空字符串，releaseId 可解析 UUID，摘要为 64 位小写 hex；未知字段/类型/kind 拒绝。原 OPENAPI 精确五键、digest 和必填 text 条件保留，来源不可变保存逻辑不变。未混入 EX-04 的分页/非 HTTP 映射。

## 失败路径与已有验证的独立复算

审阅者逐一重算最新 Target 的 3 份、Full 的 70 份归档 XML 的长度/SHA256，并解析 testcase，与各自 methods.csv 的具名身份及状态多重集合完全一致。两轮 149 个模块文件 before/after 摘要一致；本卡当前三源码均匹配两轮捕获摘要。当前 EX-27 诊断测试的后来改动不属于这三文件，也不把旧 Full 改称验证了后来的诊断候选。

| 已有执行 | 独立核查结果 | 能支持的结论 |
| --- | --- | --- |
| v1/v2 原产品红 | 两轮各18项 = 9P/7F/2E，native1；两个 E 停在目标 `SKILL_AUTH_SCHEME_REQUIRED` | 缺鉴权声明真实存在；不冒称后续来源/发布/header 已在红轮到达 |
| 最新 Target `target-20261028-234843-846` | 3 XML、30P，native0，无 failure.ignore；导入18、手工注册7、实际出站5 | 本卡关键路径及原 Bearer 兼容范围通过 |
| 原 Full `full-20261028-234917-584` | 70 XML、673项 = 556P/7F/106E/4S；native0 使用 failure.ignore，采集器1 | 报告可核，不是全绿或回归放行 |

从 EX-00 冻结 module baseline CSV 独立以类名＋具名方法重算：原664身份全部存在、新增9身份均 passed、原116非通过状态精确保留，唯一旧 P→F 为 `AlgorithmOffloadInterruptionTest#interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[3]`（GET_BODY）。因此当前新增非通过1、回归门 false；不同调用链、原源码未改或一次额外 PASS 均不能豁免。原4个 JDK8 条件跳过保持，106错误与4跳过不描述成全部方法体执行；两轮空 method 类级事件均为0。

Full 中 OPENAPI 控制器4P、导入11P、真实持久化1P、HTTP映射7P，以及注册7P/旧出站5P均实际有新鲜 XML，支持旧 OPENAPI/Bearer 范围；没有仅凭分支文字未改就声称动态兼容通过。

真实 PG 方法的固定测试源码中，任何迁移前先断言专库存在、owner=agent_v12 与 EX-03 准确 comment；不自动创建/reset/drop。临时 vault/master-key 属性使用测试临时目录，并在 AfterEach 恢复先前值。测试 Actor 是直接置入安全上下文的替身，不声称 HTTP 验签验证。

该方法已实际 passed：真实 Catalog 创建→PG读回 DRAFT/API_KEY/来源→缺连接拒发布→真实 SkillExecution 适配器 HTTP 并落非 mock PG事实→治理 connection 读同摘要事实→缺选型拒发布→确定模型协议替身供给选择、生产治理计算并落库→生产 publish→PG再读 definition/sha 不变→已发布再次实际 HTTP。两次环回接收头均断言精确单值 X-API-Key 且 Authorization 为空，legacy=false。引擎 registration/claim/submitted/fact 和模型端点是明确、按准确 URL 的协议替身，没有通配默认成功；原首次 Target 的漏 submitted ack/NPE 及29P1E保留，新候选仅补准确 ack，未修改生产门禁。

## 阶段问题与剩余门

| 项目 | 处置与当前状态 |
| --- | --- |
| 本轮三个源码的确定缺陷 | 未发现需回改的实质阻断；三文件 `git diff --check` 退出0 |
| SDK GET_BODY 新 FAIL | 必须保留为模块阻断；由 EX-27 准确诊断/技术门承接，本审阅不判断未采集的历史唯一根因，也不批准改预算、跳过、降低断言或改产品白名单 |
| 当前证据入口 | root 最终收口时应将 README 的“独审尚未开始”更新为实际阶段并链接本 review；历史“尚未执行其它定向组”等句子明确属于原阶段，保留原记录 |
| 完整回归与精确候选 | 等主执行者提供 SDK 技术门处理及新的完整 Skill 方法比较；保持原664＋本卡新9及任何另行核准新增项逐身份对照，不凭总数相同签收 |
| index/提交/回滚 | 本轮未核准本卡最终 index 或 commit。root 冻结本卡公开清单/源码/证据及精确暂存树后再做窄终核，随后本地提交和台账回填 |

DoD①本卡关键失败→定向通过、③本卡已有命令/原件/数字可复核；②完整模块新增非通过未关闭，④当前仅阶段独立核查，⑤提交/最终回滚记录待 root，不将这些状态合并成“整卡完成”。后续验证应针对剩余门，不重跑已完成定向凑数字。

回滚范围仅本卡三个获准源码/测试文件的准确差异，保留原红、首次Target错误与Full新失败证据，不整仓 reset、不回滚其它卡，不原地覆盖已发布版本。分页、非HTTP、真实 OR J4、JWT HTTP 入口、真实引擎/外部模型和 EX-07 联合验收不由本阶段关闭。
