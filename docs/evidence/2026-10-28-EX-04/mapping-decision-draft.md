# EX-04 字段映射决策候选（只读复核，未核准）

2026-10-29，复核者 `/root/ex04_mapping_decision`。**EX-03仍未关闭，EX-04仍待启动；本稿是供root选择并回填卡片的技术候选，不能据此直接改源码、宣称新契约冻结或验收通过。** 未使用Superpower；未运行Maven/Java/PG/HTTP/Node/前端/服务，未读取环境配置或凭据，未操作Git index/提交。唯一写入本稿；既有[preflight-mapping.md](preflight-mapping.md)保持原件。

读取了AP/OR各自AGENTS、AP implementation-brief/contracts README、OR continuation-plan/independent-audit-remediation-plan及p2/p4契约。OR没有同名implementation-brief/contracts README，采用其AGENTS指定文档；其中历史Superpower要求被当前用户“不使用superpower”覆盖。当前只读HEAD：AP `28aa99ea12cb96bbbf781859c017acebd30647a3`、OR `da3345a123752c3fda06c5a7a8a1dc9d614b8918`；AP Importer/Catalog为EX-03未提交候选，HEAD不是这些候选完整身份。

## 建议root一次确认的最小方案

选“HTTP/SYNC真实消费＋非HTTP未登记明确拒绝＋新增显式OFFSET提示”；保留原PAGE，无新执行器、绑定目录、分页请求改写器或数据库迁移。OR三个产品文件当前没有输出丢失证据，继续只读。现卡把offset写成page的文字必须先纠正。

| OR正式manifest | AP候选规范形／处理 | 边界及失败路径 |
| --- | --- | --- |
| `adapter_type=HTTP`、`invocation_kind=SYNC` | 显式`type=HTTP`、`invocationKind=SYNC`，HTTP `endpoint/httpMethod=POST` | 保持正式消费路由、四元组、API_KEY/secret_ref、ADMIN/租户校验；HTTP的SUBMIT_QUERY/CALLBACK虽有枚举/平台单元，但OR未给本导入路径完整异步协议，明确拒绝`invocation_kind`，不仅透传枚举。 |
| MCP/ALGORITHM与调用形态 | 先按现`AdapterRegistry`类型与组合校验，再对缺失/未登记`binding_ref`返回400字段错误 | 不产生草稿；非HTTP若带`endpoint/http_method`先定位矛盾字段拒绝，不能用伪HTTP URL兜底。不存在名义绑定目录时所有非HTTP均走明确未登记分支，不能宣称已支持真实解析。 |
| 可选`exposure.kind` | 建议保存规范`exposure:{kind:QUERY或MUTATION}`，作为声明和摘要一部分 | exposure缺失不新增键、不推断kind。pagination移至下行规范字段，**不同时存两份可各自编辑的分页权威值**；这项AP规范载体是待root核准增量。 |
| `exposure.pagination.limitParam/offsetParam/maxLimit/maxRows` | `pagination:{mode:OFFSET,request:{offset:offsetParam,size:limitParam},maxPageSize:maxLimit,maxRows}` | 参数名原样保留；不写`page`、不做offset↔page换算。maxLimit/maxRows必须是正32位整数，拒绝小数/字符串/0/负数/溢出，不能用Number.longValue截断。没有源码依据要求maxRows≥maxLimit，不新增该限制。 |
| OR未声明响应路径 | 不写`pagination.response` | 不猜`$.data.items/total`，不从任意业务Schema反推。无响应路径时observed仍为空对象，不虚构总数/条数。 |
| 原AP PAGE声明 | 缺mode的`request.page/size`按原形保存；可支持显式`mode=PAGE`，但不主动补入 | 原可选response.itemsPath/totalPath及maxPageSize语义不变；无分页的契约仍无键。未知mode与OFFSET/PAGE互相混用参数须字段错误，不默认为PAGE。 |

上述`pagination`位于技能definition顶层；引擎工具由`config.tools[].contract=skill.definition`获得，因此运行载体是`config.tools[].contract.pagination`。它不是HTTP query/body自动映射，当前调用输入仍由调用方按技能inputSchema提供。ToolOutputPolicy没有原请求参数，不能生成真实nextOffset/下一页号或声称已经续拉；只提示声明参数和上限。

## 读写及审批的准确决策

已实现事实：OR ContractValidator只拒绝`MUTATION+readonly_flag=true`；OR并未强制QUERY必须只读或MUTATION必须side_effect=true。AP Catalog已拒绝readonly与sideEffect同时true，并按readonly推导风险：readonly=false一律CONFIRM_REQUIRED；没有approval时兼容SINGLE_STEP；CONFIRM_REQUIRED的发布必须有confirmRoles，连接/模型证据仍必须满足。

**建议本轮导入规则如下，新增的QUERY约束须root明确签收，不能称它已有实现：**

| exposure | readonly | sideEffect | 建议结果 |
| --- | --- | --- | --- |
| 缺省 | 原显式布尔 | 原显式布尔 | 完整保持原Catalog互斥/风险/审批规则，不合成QUERY/MUTATION。 |
| QUERY | true | false | 接受并保留；若manifest明示更高risk，不能把它当成降低审批的依据。 |
| QUERY | false或sideEffect=true | 任意 | 新消费侧一致性检查400，定位`exposure.kind`与冲突`exec_attrs`；不将标签改成QUERY后自动改执行旗标。 |
| MUTATION | true | 任意 | 400，既有OR冲突语义，在任何catalog.create前拒绝整批。 |
| MUTATION | false | true或false | 原值保留，均CONFIRM_REQUIRED；不把未声明的sideEffect改成true，也不因false绕过审批。要求MUTATION必须sideEffect=true会再收窄OR已接受集合，现无必要另加。 |

manifest当前没有确认角色/prepare端点的映射声明，所以本卡不自动发明confirmRoles、审批角色或PREPARE_EXECUTE。写投影导入可生成待补配置的DRAFT；缺确认角色的发布按既有409拒绝。若要写技能发布正例，由测试/管理员在独立草稿显式配置合法confirmRoles并走原门，不能把“写草稿导入成功”写成“审批或发布成功”。只读来源明示CONFIRM_REQUIRED但旧Importer忽略risk_level的问题应在root决策时明确：最小方案是保留上调riskLevel并测试，不读取风险声明降低Catalog推导值；这是现卡“与approval一致”的范围澄清，不应无意沿用注释当作可降级授权。

## 归一化、范围与实施顺序候选

1. root核准上述规范载体、QUERY新增约束和风险只可上调规则，再回填执行卡/手册；EX-03关闭是硬门，不以本报告提前开工。
2. Importer在整批`catalog.create`之前完成投影结构/组合/冲突/分页校验。Catalog.normalize需保存已验证`exposure`和`pagination`，防止直接注册/后续编辑丢字段；最小可复用SkillRules中一个纯校验/规范化入口，Importer/Catalog共用，不另建框架。
3. 分页规范化仅接受已列键与文本/整数类型，未知键不能悄悄丢弃。新OFFSET必需mode/request.offset/request.size/maxPageSize/maxRows；旧PAGE沿用page/size必需、response及maxPageSize可选。null/空对象不能被视为有效声明；缺键保持缺省，不自动补响应路径或默认限额。原PAGE响应点路径解释器保持现状，不扩JSONPath语法。
4. ToolOutputPolicy增加OFFSET独立读取/提示分支，超限提示带mode、原offset/size参数及maxPageSize/maxRows。原PAGE分支、字节阈值、错误信封、原始事实留存和阈值内同节点返回均保持；运行时畸形声明仍防御性视为未声明，不新增运行抛错。maxRows是声明元信息，不等于策略已累计/强制总行数。
5. OR只有真实生成/正式化样例证明丢字段才另开其白名单修改；目前Generator深拷贝、formalize深拷贝、Publication存exposure/回显均在，不能为“两个仓都要改”硬改OR。

现卡必须补的生产/测试白名单：

- AP `engine-runtime/src/main/java/cn/agentplatform/engine/application/ToolOutputPolicy.java`。
- AP `engine-runtime/src/test/java/cn/agentplatform/engine/application/ToolOutputPolicyTest.java`。

现卡已有Importer、SkillRules、Catalog及OntologyManifestImportsTest/新增OntologyProjectionImportTest。若本卡要给“实际prepareSnapshot冻结未丢”的动态结论，建议再明确扩展既有 `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunToolProfileTest.java`：已有技能只读客户端stub与调用真实prepareSnapshot的夹具，可加一条definition分页/投影逐字段相等断言，不新增执行器/生产类；这只证明实际冻结函数，非真实HTTP/PG运行。若不补此测试，应将实际运行冻结验收准确留给EX-07，不用手工构造tool冒充真实freeze。RunService生产代码当前原样透传definition，无修改依据；SkillDigest递归纳入非展示键，无修改依据。

## 最小红→绿及验收矩阵（未执行）

| 场景 | 必须观测的旧缺口／修后结果 | 最小落点 |
| --- | --- | --- |
| HTTP/SYNC正式来源、API_KEY | 原鉴权/来源门不回退；真实本仓PG创建→读回→保存→发布definition保留OFFSET/QUERY，出站仍精确X-API-Key无Authorization | OntologyProjectionImportTest扩现有真实PG链；模型/engine协议替身必须逐处注明，不称OR真实业务。 |
| 分页丢失及超限 | 旧Catalog归一化丢pagination、旧策略OFFSET无提示可真实复现；修后用实际PG读回契约经策略生成准确offset/size提示，无page/假response/假observed/自动续拉 | 新Projection测试＋ToolOutputPolicyTest；跨模块不导入业务JAR，以独立脱敏JSON契约或相同规范样例衔接并记录来源。 |
| MCP/SYNC、ALGORITHM/SUBMIT_QUERY | 无HTTP入口、未登记binding_ref→明确400含路径且整批零create；不是所有非HTTP都报“必须HTTP” | Projection测试；不得用mock映射出不存在的名义绑定正例。 |
| 未知type/kind、非HTTP携HTTP入口、HTTP非SYNC | 400准确字段且零create，未知传输不落HTTP | 同上；类型/组合答案来自既有注册表。 |
| MUTATION只读、QUERY可写、畸形分页 | 对每项单点变异：冲突布尔、未知mode、缺字段、非对象/非字符串/非整数/溢出、混用page/offset；均在首create前失败 | Projection测试参数化，失败类型/字段真实断言；QUERY约束以root最终核准规则为准。 |
| 合法写DRAFT及审批边界 | readonly=false维持CONFIRM_REQUIRED；无confirmRoles发布仍拒绝，不静默升级两段/发权限；显式高风险不下调 | Projection测试＋既有Catalog/审批门相邻回归。 |
| PAGE与无分页旧技能 | 缺mode旧PAGE按原形保存、原提示/响应路径观测不变；无声明不新增键；阈值内返回同节点，原输出不截断 | ToolOutputPolicyTest＋normalize保存读回。 |
| 摘要/冻结 | 只改分页参数或上限必改semanticDigest；读回与publish不可丢；实际prepareSnapshot若验则逐字段保留，不把手工tool当freeze证据 | 直接调用现SkillDigest，无需改类；可扩RunToolProfileTest或留EX-07。 |

定向绿后仍须skill/engine完整模块按EX-00方法/类清单比较（保留原错误、空方法类事件、4旧JDK8跳过及未执行事实），新非通过/坏类/跳过/未执行为0；OR仅运行现卡指定ToolBindingExposureTest验证真实生成/正式化，不把历史绿或生成器透传当成实际业务offset分页已执行。真实OR HTTP业务分页另需确实读取offset/limit的合法操作样例，现未核得，不补造。

## 关键源码定位（此次只读）

- AP OntologyManifestImports.java:44–79整批创建前门、90–151仅HTTP/SYNC及已有转换、104–114布尔映射；现没有exposure/pagination分支，也没有risk_level映射。
- AP SkillCatalog.java:394–487类型/组合由AdapterRegistry判定；670–684布尔互斥/风险；703–772当前规范结果未保留pagination/exposure；782–833审批模式兼容；868–879确认角色/查询守卫发布门。
- AP AdapterRegistry.java:84–100仅类型/组合/缺省kind，没有binding_ref名义目录；本仓skill-registry/src/main检索binding_ref/bindingRef未命中。
- AP ToolOutputPolicy.java:109–115仅page/size；119–141现提示；145–153点路径解释；71–78阈值内同节点及超限替换。RunService.java:1315–1335将skill.definition原样置入工具contract；RunToolProfileTest.java:115–120已有真实prepareSnapshot夹具。
- AP SkillDigest.java:31–39展示键排除，61–90递归保留其它字段，分页及投影会自动进入摘要。
- OR ContractGenerator.java:38–75生成binding/exposure/exec/risk，80–93正式化深拷贝；ContractValidator.java:32–59传输/exec/risk、81–95分页与唯一现有MUTATION冲突；ContractFields.java:36–40明确正32位整数。
- OR PublicationService.java:568–593只HTTP建端点并存exposure，728–739回显投影，794–812从冻结preview正式化。ToolBindingExposureTest.java:125–159合法QUERY/写投影与MUTATION只读负例，210–224正式化保留分页（此轮未执行）。

本稿不构成独立终审或运行证据；root需先确认新增消费规则并同步准确白名单，随后按EX-03→EX-04硬依赖执行。
