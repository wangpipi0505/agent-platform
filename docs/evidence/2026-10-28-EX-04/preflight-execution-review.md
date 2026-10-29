# EX-04 实施前独立执行复核

2026-10-29（Asia/Shanghai），独立审阅者 `/root/ex04_preflight_review`。仅阅读当前AP/OR源码、三份主入口及既有三份EX04准备/独审，唯一新增本文件；未参与本轮实现或测试编写，未运行Maven、Java、PG、HTTP、仓库脚本、服务或前端，未读环境/凭据，未改源码、主入口、index或提交。未使用Superpower；沿用最小变更原则，不增加框架或依赖。

**结论：当前卡片/手册的最小方案具备开工条件，EX03硬依赖已解除；无须扩大生产白名单。下面的批次预检、保存省略字段与旧风险兼容必须进入红→绿矩阵，不能留给末次回归碰运气。本结论不是实现验收、运行通过或整卡关闭。**

## 前置与历史边界

当前AP读取HEAD为`d7880a66b345f15aecf37133a702df769e591fa5`，OR为`da3345a123752c3fda06c5a7a8a1dc9d614b8918`。已核EX03提交`cffbd3613273f6c8a810cc25e8cb75508709841b`的Git对象：父6eda42c、树a95e0f158ed3bf9c3606d7eecbb2432d7f012f29、102文件且非证据恰Importer/Catalog/对应Test三源；主台账EX03行已完成。本轮不重算其旧30P/Full674或代替其已有终审。

已读`preflight-mapping.md`、`mapping-decision-draft.md`、`mapping-review.md`。它们当时的EX03未关闭/候选未核准说明是历史事实；现行三主入口v2.12/v1.12/v1.12已明确采纳HTTP/SYNC实际消费、非HTTP未登记拒绝、OFFSET独立载体和QUERY新一致性规则。不得把旧准备稿当作当前硬门仍未满足，也不把其静态盘点当运行结果。

## 当前路径与最小落点

1. `OntologyManifestImports:44–79`在全部`toSkillBody`结束后才逐个`catalog.create`，这是新预检的天然边界；当前`:90–94`仍只接受HTTP/SYNC/POST，`:104–151`未映射exposure/pagination/risk_level。所有本卡新字段的类型、组合、读写与风险拒绝必须在首create之前完成。
2. `SkillCatalog.create:293–320`在单项事务内先插skill、再normalize、再插版本。单项回滚不能撤销前一合法项已提交的草稿。仅在normalize新增拒绝会令混合批出现部分写入；无需新批次事务，先把本卡纯语义校验前移。
3. `SkillRules`现有Schema/auth/identity等纯入口可扩一个最小的投影/分页规范化入口供Importer和Catalog共用；不另建通用投影框架。新增入口应只读输入、构造规范副本，明确字段路径，保留旧无投影/无分页的缺键语义。
4. `AdapterRegistry:84–105`是实际类型/组合权威，但实例藏于Catalog。Importer可通过Catalog中小型无写入委托使用实际实例；不要硬编码另一套MCP/ALGORITHM组合或另建`withPlatformDefaults()`，否则Spring实际注册集合与导入预检可能分叉。无名义binding目录不增造正例。
5. `SkillCatalog.normalize:670–684/730–770`是执行布尔/风险/来源的既有落点；本卡需保留规范exposure与pagination。`save:325–358`目前只补回importSource/httpMapping/authScheme/identity，未给新exposure/pagination任何省略保护。应在同一既有保存路径处理旧编辑器省略新键，避免无关编辑静默清掉投影/分页；显式新值仍走同一规范化和摘要规则，不批改旧发布版本。
6. `ToolOutputPolicy:71–78/109–141`负责模型可见面和分页提示；仅增加OFFSET的防御读取/提示，实际原始结果与错误信封仍保持。`RunService:1328–1329`已将skill.definition透传tool.contract；`SkillDigest:61–90`已递归纳入非展示键。因此两生产类继续只读，实际freeze用既有RunToolProfileTest调用prepareSnapshot验证，不跨业务JAR复用SkillRules。

## 必须先留下的关键红与绿后后置

| 场景 | 必须观测的失败/绿后义务 | 最小测试落点 |
| --- | --- | --- |
| 真实保存链丢字段 | 原PG目录丢pagination/exposure的红；绿后创建→读回→保存→发布逐字段保留OFFSET/QUERY/API_KEY与正式来源 | 新OntologyProjectionImportTest，复用已核归属专库/真实PG，模型与engine替身明确标注 |
| 保存省略及显式修改 | 保存体省略新exposure/pagination时已保存值仍在；显式改参数名或限额后语义摘要改变，旧证据不能冒用 | 同一真实PG保存链；不只比较toSkillBody或mock参数 |
| 全批预检 | 首项合法、第二/后项分别坏分页、QUERY冲突、风险降级、类型/组合错误；每类catalog.create零调用，真实PG本批draft前后数量不变 | mock定位＋真实PG持久化后置，两层都要；每例新UUID命名空间，不清未知数据 |
| 传输组合 | 显式HTTP/SYNC/POST；HTTP非SYNC定位invocation_kind，非HTTP携endpoint/http_method先定位矛盾字段；MCP/SYNC与ALGORITHM/SUBMIT_QUERY无真实binding_ref时400定位binding_ref | 新Projection测试，合法凭据前提齐备后再测对应字段；未知type/kind不得落HTTP兜底 |
| 分页严格边界 | OFFSET四字段精确保留，正32位整型含边界；小数（含1.0）、字符串、溢出、0/负数、空名、null/空对象、未知键/mode与page/offset混用均字段化拒绝 | Projection/规范化测试；不用Number.longValue()截断；不加maxRows≥maxLimit或参数名不同等未授权约束 |
| 旧PAGE及缺省 | 无mode PAGE按原形保存，显式PAGE合法；原可选response点路径/maxPageSize保留；无声明仍无键 | Catalog保存＋ToolOutputPolicyTest，不用新增默认响应路径/限额凑规范 |
| QUERY/MUTATION | QUERY=true/false布尔组合的新增一致性；MUTATION readonly=false且sideEffect原值保留（true/false均测），不得自动改旗标 | Projection测试；readonly=true与sideEffect=true旧互斥仍在 |
| 风险/审批 | 来源READ_ONLY不能降低非只读/副作用推导，来源CONFIRM_REQUIRED在只读时保留上调；旧手工不带投影/本体来源仍按原风险推导，不扩大拒绝 | Projection＋旧手工normalize回归；写DRAFT缺confirmRoles发布继续拒绝，不合成角色/两段协议 |
| 模型可见策略 | 原OFFSET无分页提示的红；绿后mode/原offset和size参数/maxPageSize/maxRows准确，缺响应路径observed为空，无page/nextOffset/总数/自动续拉；阈值内同一节点，原结果不截断 | ToolOutputPolicyTest；不得只按文本包含“分页”弱断言 |
| 冻结及跨模块来源 | 实际prepareSnapshot逐字段保留导入读回definition；PG脱敏JSON与引擎输入应能按内容/SHA互核，不把两份手工同形样例称为真实跨模块衔接 | RunToolProfileTest＋PG读回安全证据；engine不引入skill业务JAR |

这里的整批零写入针对本卡新增声明预检错误；数据库唯一冲突等真正create阶段失败仍按原导入接口的部分创建语义处理，不能据四类测试声称任意失败皆全批原子回滚。

## 旧测试与消费规则的准确边界

- 原`OntologyManifestImportsTest:152–153`固定断言不映射riskLevel，其factory`:110`实际带`risk_level=READ_ONLY`。EX04要求保留显式上调，因此该旧行为断言需要按新规格精确调整；同时保留“缺risk_level沿原推导”“旧手工API未知/降低风险仍按原规则”的兼容锚点，不能只删断言。
- OR `ContractValidator:81–95`只既有MUTATION＋readonly冲突；QUERY只读/无副作用是本轮明确新增消费约束，不冒称OR已有强校验。MUTATION不强制sideEffect=true，不发明批准角色。
- OR `ContractFields:36–40`用isIntegralNumber/canConvertToInt；分页用同等严格界限。Importer原timeout数值helper是另一个存量边界，本卡无需顺手收窄timeout或全局Schema语义。
- OR Generator生成及formalize深拷贝exposure；Validator的分页声明不含响应路径；因此无需改OR产品。既有ToolBindingExposureTest复验只证明生成/正式化透传，真实业务offset/limit消费与J4仍属EX07。
- 当前正确生产范围仍为Importer、SkillRules、SkillCatalog、ToolOutputPolicy；测试仅主卡列明五类。AdapterRegistry、SDK、RunService、SkillDigest、控制器、POM、迁移和冻结契约无修改依据。发现必须的新事实先回填准确范围。

## 后续门

先在原产品保留投影/分页关键红，才写最小实现；拿到ROOT独占实际窗口才执行定向/PG/HTTP。定向全绿后串行完整skill/engine逐方法/原因核对，原4个JDK8跳过、engine两类级事件及14未执行源Test按既有基线如实保留，新非通过/坏类/skip/未执行为0。OR按卡复验透传，不外推真实业务。实际命令、native、collector、方法/原因门与全绿分别记，禁止failure.ignore成功文案替代通过。

本轮仅独立预审，不签收未来实现、索引或提交；最终实际候选、回归及精确提交需另审。上述已知落点和关键红不要求新用户确认，可在已授权EX04白名单内继续。

## 本次只读源码身份

以下为本报告读取时实际工作区原始字节SHA，不作为后续实现完成身份；没有用HEAD冒充未提交源码。

| 文件 | SHA256 |
| --- | --- |
| AP `skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java` | `41450faac9141e0e3393d7a9ed45b5e5f67e7d09da787f448ddbe86565f24ca5` |
| AP `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java` | `7c98cae7587da4f64bf70e93e9540a16f097bff885de7ea158e147fe8d491b03` |
| AP `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java` | `ec43746f20705a8292294945594fae7a7ad0f3006e24f60657e1ac88c67886a3` |
| AP `skill-registry/src/main/java/cn/agentplatform/skill/infra/AdapterRegistry.java` | `2d1c7484cee53110281154650e00c361b66d6b9c90a0be2e78bdcab238be82f2` |
| AP `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillDigest.java` | `e4ba3d331e36ff0937f5ff3f19ad413a115fccb963233e12df8b558cf168ed07` |
| AP `engine-runtime/src/main/java/cn/agentplatform/engine/application/ToolOutputPolicy.java` | `94cf53c998668fe6d4a7c60d41a063d5c05fced6e7800a96fc3fd1a289fe4c77` |
| AP `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | `c6aa628a0ae436627b2f202f10a0278dc9d3069141582658cadbf98219607716` |
| OR `onto-contract/src/main/java/cn/ontorepo/contract/ContractGenerator.java` | `b19eb968f279544883413e7d568fee6b9419add68d41e38c07da62a97e6d0cfd` |
| OR `onto-contract/src/main/java/cn/ontorepo/contract/ContractValidator.java` | `07389201b7b1e8497fe3582e6ef33ef05105d7ff09061168d0da5adc93f76d2e` |
