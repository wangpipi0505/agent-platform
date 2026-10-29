# EX-04 执行规格窄技术复核

2026-10-29 01:10（Asia/Shanghai），独立审阅者`/root/ex00_collector_review`。本次未编写两份准备稿或三主入口规格，只读对照相关源码；唯一新增本文件。未使用Superpower，未实现产品、运行CLI验证或产品测试，未读取真实环境文件/凭据，未改index或签收任何产品提交。

**结论：当前最小设计方案可采纳，无遗留需扩大生产白名单的设计阻断。EX-03硬门仍未通过，EX-04仍为待启动；本结论不授权提前实施，不是DoD实施终审、运行通过或commit签收。**

## 对象及准确时点

已读统一台账v2.9的EX-04准备状态、执行卡片v1.9的EX-04（69–86行）、手册v1.9新§2.4、[preflight-mapping.md](preflight-mapping.md)及[mapping-decision-draft.md](mapping-decision-draft.md)。两稿保留各自历史HEAD/未核准说明；当前选择以三主入口为准，不将历史准备稿冒充已交付的新契约。

本次最终复核时的SHA256：

| 文件 | SHA256 |
| --- | --- |
| 统一台账 | `4780082fbeb151af66ca808b35cf3e93c2e5965739a782f52df103c5aff752ee` |
| 执行卡片 | `fcbe030226a9ba4830d636855bd9fdac681ac2cf17d3f47c3a6995d59b34403f` |
| 联合验收手册 | `abe50c5fad338a5d7864ee6ccf152ee222d5ac328e0f5c079d4b35b19ca903a0` |
| preflight-mapping.md | `1bcd7e808539bd979034e6ab0f161cfed4d2f041113011f5494b2fdfe489a93a` |
| mapping-decision-draft.md | `dbd3d63692d4c293114699d30f53531d89089d8ab2af653107c5cb9a2674ea07` |

这些是规格时点身份，不是产品候选/索引冻结；后续状态回填可改变文档摘要。

## 源码对照结论

- **传输边界准确。** OR `ContractValidator.java:32–43`区分HTTP/MCP/ALGORITHM，非HTTP不需要HTTP入口；`ContractGenerator.java:38–55,80–93`按绑定生成并深拷贝正式化。AP当前Importer仍在`OntologyManifestImports.java:90–94`只接HTTP/SYNC/POST。新方案明确HTTP/SYNC实际消费、HTTP非SYNC缺异步协议时字段化400；非HTTP先查类型/组合、无名义绑定目录则`binding_ref`明确拒绝，不伪装HTTP。这与`AdapterRegistry.java:84–100`只提供类型/组合而没有名义绑定目录的实际能力相符。注册表已认识MCP/ALGORITHM不等于已存在可解析的绑定正例。
- **分页载体正确且足够小。** 当前`ToolOutputPolicy.java:109–140`只读`contract.pagination.request.page/size`；OR `ContractValidator.java:86–92`声明的是offset/limit及正32位上限。新形状`mode=OFFSET,request.offset/request.size,maxPageSize,maxRows`保留原参数名，不做offset/page换算；exposure仅保留kind，避免两份可编辑的分页权威。OR没有response路径，故不猜路径，observed为空，不编造nextOffset、自动续拉或总行数限额。maxRows只是声明元数据；没有源码依据加maxRows≥maxLimit限制。
- **兼容与防御读取边界明确。** 旧PAGE缺mode仍按原形保存，显式PAGE可接受；原response点路径、可选上限及无分页缺键保持。新增模式严格校验未知键/模式、类型、范围和混用字段；运行读取仍对畸形声明防御性视为未声明，不因旧存量形状新增运行异常。阈值内返回同一节点，超限只替换模型可见面，原始调用事实不截断。这是应测试的不变量，尚非运行结论。
- **读写规则没有冒称既有实现。** OR `ContractValidator.java:81–95`原来只明确拒绝MUTATION＋readonly；新QUERY要求readonly=true且sideEffect=false已写明为新增消费约束。MUTATION要求非只读，但sideEffect保留显式原值，不强制true；不自动改执行旗标或合成exposure。未知/矛盾声明须在首create前拒绝。
- **风险与审批兼容可落地。** OR `ContractValidator.java:56–58`已有风险降级拒绝；AP `SkillCatalog.java:674–684`已有布尔互斥及非只读→CONFIRM_REQUIRED，并允许只读显式上调风险。Importer当前未映射risk_level，新方案保留上调、不降低派生风险是必要增补。最终规格明确来源risk_level/新投影冲突的拒绝范围，不扩大旧手工API的拒绝条件。Catalog `normalizeApproval:782–810`缺省SINGLE_STEP，PREPARE_EXECUTE另要求有副作用；`publish:868–879`仍要求confirmRoles及查询guard。MUTATION DRAFT接受不等于发布或两段审批通过，角色/prepare协议不得补造。
- **保存、摘要与冻结落点合理。** 当前Catalog规范结果未保留exposure/pagination，仅改Importer会丢字段；Importer和Catalog共用最小纯规则入口有必要。`SkillDigest.java:62–79`递归保留非展示键，`RunService.java:1315–1329`把skill.definition原样置入tool.contract，均无新增生产修改依据。`RunToolProfileTest.java:115–120`已有调用实际prepareSnapshot的夹具，扩逐字段相等断言可证明真实冻结函数；手工拼tool不能替代。该测试仍不等于真实OR业务分页或平台J4运行。
- **OR保持只读有依据。** Generator生成/正式化深拷贝exposure；`onto-release/.../PublicationService.java:568–593,728–739,794–809`只给HTTP建端点、保存/回显exposure并从冻结preview正式化。当前未见需改OR产品才能保字段的事实。复验已有ToolBindingExposureTest只证明生成/正式化，不证明业务处理器读取offset/limit；真正OR操作样例和J4准确留给EX-07。

## 本轮反馈与最终规格复核

| 项目 | 原风险及源码依据 | 主执行者处置 | 本次结论 |
| --- | --- | --- | --- |
| R04-01/P2，验收规格已补 | 单个坏项零create不足以证明整批预检。Importer:65–79先转换再逐个create，Catalog:293–309在单次事务内部才normalize；只把新检查加在Catalog，会让有效前项先落库 | 卡片85行及手册318行明确有效首项＋第二/后项分别含坏分页、QUERY、risk_level、类型组合；要求catalog.create零调用及真实PG本批draft前后不变，不靠新增事务替代预检 | 补充准确，可进入未来红→绿矩阵；当前没有运行这些测试，不能记为已通过 |
| R04-02/P2，兼容范围已澄清 | 风险拒绝若无条件扩大到所有旧API，会改变原Catalog安全上调而非拒绝的兼容行为 | 卡片81行/手册317行明确限本体来源risk_level或新投影冲突，旧手工风险推导/无exposure原门保持 | 与原风险代码相符，不必扩UI或其它产品范围 |

## 开工与验收边界

准确生产白名单为AP现有`OntologyManifestImports`、`SkillRules`、`SkillCatalog`、`engine/application/ToolOutputPolicy`；测试限`OntologyManifestImportsTest`、拟新增`OntologyProjectionImportTest`、`ToolOutputPolicyTest`、既有`RunToolProfileTest`。OR仅复验既有ToolBindingExposureTest，其Generator/Validator/PublicationService先只读。当前没有修改AdapterRegistry、SDK、RunService、SkillDigest、控制器、POM、冻结schema/契约或新增迁移的依据；新事实需先回填范围。

EX-03关闭后，仍须保留实际字段丢失/旧OFFSET不提示的红；验证真实PG创建、读回、保存/发布、精确API_KEY头链、只改分页导致摘要变化；逐项覆盖混合批预检、MUTATION原sideEffect、只读显式高风险、旧无exposure、旧PAGE和小响应不变。跨模块仅用来源可追溯的脱敏JSON衔接，不导入其它业务JAR。定向后完整skill/engine按准确方法/类基线比较，保留存量红、类级事件、跳过及未执行；新增差异必须为0。

本次只核规格与相关源码，没有产品改动、红绿/模块/OR运行证据或提交验收。上述设计门可采纳不改变EX-03进行中、EX-04待启动及其它能力状态；未来实现必须另经独立候选审阅。
