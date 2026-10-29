# EX-04 只读准备：投影与分页映射

2026-10-28，准备者 `/root/ex04_projection_mapping`。**EX-03尚未关闭，本卡未开工；以下为当前源码事实及待主执行者评审的最小映射候选，不是已冻结的新契约或已通过验收。** 用户要求不使用superpower，本轮未使用；只读两仓代码/设计/契约，唯一写入本报告，无源码、测试、主入口、index或提交操作，无Maven/Java/PG/HTTP/前端/服务运行，未读取配置或凭据。

核实HEAD：AP `0cc967278007343bd5ab09faf9953d86cd573bf8`、OR `da3345a123752c3fda06c5a7a8a1dc9d614b8918`。AP导入/目录包含EX-03未提交候选，HEAD不是该候选的完整源码标识；开工仍须重新核最终EX-03源码及证据，不把本报告当交付状态。

## 一页映射与执行门

| OR真实输出 | AP现状 | 最小消费映射／必须先决定的边界 |
| --- | --- | --- |
| `adapter_type=HTTP`、`invocation_kind=SYNC`、正式`endpoint/http_method=POST` | EX-03候选仍严格要求这些值，写`type=HTTP`但未显式写`invocationKind`，依赖目录缺省SYNC | 显式保存现有HTTP/SYNC/POST；保留发布路由、正式四元组与API_KEY，不改旧手工技能默认。HTTP非SYNC仅有枚举不等于本体有异步提交/查询协议，缺少真实异步协议时明确拒绝，不能只透传kind。 |
| MCP/ALGORITHM只带可选`binding_ref`，无`endpoint/http_method` | `AdapterRegistry`只登记执行类型及支持kind，**没有binding_ref→业务绑定目录**；MCP实际需服务器endpoint及toolName，ALGORITHM使用独立可信SDK环境 | 不能把`mcp://ledger/tasks`当真实HTTP endpoint或直接取本体路由。现卡允许“明确未注册绑定负例”，最小可对缺失／未登记引用400且给`binding_ref`字段路径，不伪称绑定解析已支持。未来正例必须先有已授权、租户可见的绑定记录与解析契约；不在此新建通用注册系统。 |
| MCP可声明SYNC；ALGORITHM可声明SUBMIT_QUERY；OR枚举还允许其它组合 | AP真实单元：MCP仅SYNC，ALGORITHM仅SUBMIT_QUERY，HTTP有SYNC和异步单元 | 组合校验复用现注册表答案；OR枚举允许不代表AP运行能力。未知组合/类型须字段化400，不能静默换HTTP/SYNC。 |
| `exposure.kind=QUERY/MUTATION`；OR仅明确拒绝MUTATION+readonly=true | 当前导入丢exposure；Catalog已有只读/副作用互斥、风险归一和既有审批门 | 在导入/目录校验读写声明与execution一致，MUTATION不能降只读；写技能仍走既有CONFIRM_REQUIRED与确认门，不自动合成批准/确认角色。QUERY与sideEffect、MUTATION与sideEffect的更严格组合先由主执行者明确，不把当前OR未校验等同已支持。 |
| `exposure.pagination={limitParam,offsetParam,maxLimit,maxRows}` | 导入不处理；Catalog.normalize也不保存pagination；引擎仅识别`contract.pagination.request.page/size` | **offset不是page，不能直接重命名。** 建议保留显式OFFSET语义：`mode=OFFSET`、`request.offset=offsetParam`、`request.size=limitParam`、`maxPageSize=maxLimit`、`maxRows`原值；引擎以可加的新分支消费，旧PAGE形状保持兼容。此为待评审扩展，不是现有接口。 |
| OR分页声明不含`itemsPath/totalPath`，生成的业务`data`形状任意 | 引擎响应路径本来可缺省，缺失时只不产生observed.total/itemCount | 不从字段名或JSON Schema猜`$.data.items/total`；保存已声明参数/上限，响应路径缺失就不编造。只有真实业务明确声明后才映射路径；maxRows仍是声明，不能声称已遍历全部结果或已执行总行限额。 |
| `ontology_meta`正式四元组、guard/exec/error/return_spec | EX-03候选严格ONTOLOGY六字段来源、API_KEY与真实目录闭环仍待绿 | 本卡从EX-03已接受的契约基线增补；来源/tenant/ADMIN/secret_ref不放宽，分页进入保存契约及语义摘要，发布/运行冻结读回不能丢。`SkillDigest`递归纳入非展示键，暂未发现必须修改该类。 |

**两处现卡范围不足必须先回填**：若按上述真实offset语义实现，AP `engine-runtime/src/main/java/cn/agentplatform/engine/application/ToolOutputPolicy.java`须加入准确生产白名单；现卡仅列其测试命令，未授权改该生产文件。对应既有`engine-runtime/src/test/java/cn/agentplatform/engine/application/ToolOutputPolicyTest.java`也应明确列范围。Importer、SkillRules、SkillCatalog及新OntologyProjectionImportTest已在原卡；目前没有依据改AdapterRegistry、SDK适配器、控制器、POM或新增迁移。

## 调用方与证据定位

- AP唯一生产调用是`skill/api/OntologyImportController.java:21–33`，Map请求有2MiB上限，直接进`OntologyManifestImports.importManifests`；未发现其它生产调用。纯转换/目录保存由既有`OntologyManifestImportsTest`覆盖，当前候选没有exposure/pagination/binding消费。
- `SkillCatalog.java:394–508/652–756`归一化、`AdapterRegistry.java:15–105`与`McpExecutionAdapter`/`AlgorithmExecutionAdapter`定义实际接受面。`McpAdapter.java:57–60/112`使用endpoint、opaque authRef、toolName；算法SDK的外部job binding是已发任务的事实绑定，**不是manifest binding_ref名义目录**。
- `ToolOutputPolicy.java:30–37/109–146`只识别page/size；`RunService.java:1247–1407`将技能definition冻结为工具contract；`SkillDigest.java:51–89`递归纳入pagination。只在Importer加键会被Catalog丢掉，不能用转换mock绿冒PG读回绿。
- OR `ContractGenerator.java:31–73/80–119`由assistant_tool.binding/exposure生成并formalize深拷贝；`ContractValidator.java:29–113`验证类型/HTTP入口、MUTATION冲突与offset字段。`PublicationService.java:567–593/729–739/796–812`只给HTTP建端点、保存exposure并回显报告，用冻结preview正式化。`SupplyController.java:66`共用导入校验。当前未发现OR丢字段的证据，故**OR三个产品文件先保持只读**。
- OR现有`ToolBindingExposureTest`与历史`O-02-03-tool-projection/README.md`能证明测试设计及历史组件范围；本轮未执行，不当作新真实样例。该测试使用`rel_1`演示formalize，不可直接借作AP当前要求UUID来源的正式夹具。

## 开工顺序与最小验收候选

1. EX-03关闭后，先由主执行者确认OFFSET扩展、读写冲突规则及非HTTP“未登记明确拒绝”的本轮边界，更新上述准确白名单并独立评审；不能按原page/size文字直接编码。
2. 在已核归属的真实AP测试库准备合法UUID来源/临时引用及HTTP输入；保存原“字段丢失/offset不被提示”的红。非HTTP未登记、未知type/kind、MUTATION+readonly、坏分页类型/空参数/非正或非整数上限均在任何create前拒绝，并给字段路径。
3. 最小实现只扩既有转换/归一化/提示策略；真实PG创建→读回→保存/发布与运行冻结保留分页及读写属性；无分页旧技能和旧page/size逐字段兼容，分页改变应改变语义摘要。无需新增执行器或改原业务授权。
4. 在真实策略上对导入读回的OFFSET契约制造超限结果，检查提示包含offset/limit真实参数与上限、无伪页号/响应路径；阈值内响应原样，原事实不截断。随后按主卡定向与受影响skill/engine模块逐方法对照；实际OR HTTP业务分页需要另有声明真实消费offset的合法操作样例，缺样例时如实单列，不能用生成器透传替代业务分页。
5. 当前未发现已有名义绑定目录，所以MCP/ALGORITHM只可完成已声明的400未登记负例；若主执行者要求正例，须先明确绑定身份/租户/版本及真实登记来源，再另行登记所需准确范围，不把类型注册表、外部job事实表或SDK全局配置冒充该目录。

本报告只读准备完成，不改变EX-04待启动、EX-03未关闭或任何主项状态；未产生运行通过结论。
