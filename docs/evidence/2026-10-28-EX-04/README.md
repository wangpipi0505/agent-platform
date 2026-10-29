# EX-04 投影、传输与分页消费

本卡实施、定向及完整模块方法/原因对照已完成；最终独立审阅见本目录，本地提交与三主入口快照由EX-08增量记录。主入口仍为统一台账、执行卡片、联合验收手册；本目录仅保存证据。开工 AP HEAD 为 `d7880a66b345f15aecf37133a702df769e591fa5`，OR HEAD 为 `da3345a123752c3fda06c5a7a8a1dc9d614b8918`，OR 未改源码。

## 实现范围

AP 恰四生产文件：OntologyManifestImports、SkillRules、SkillCatalog、ToolOutputPolicy；恰四测试文件：OntologyManifestImportsTest、新 OntologyProjectionImportTest、ToolOutputPolicyTest、RunToolProfileTest。无新执行器、绑定目录、迁移、业务 JAR 或依赖升级。未使用 Superpower，未 push/tag/外部发布。

- 正式 HTTP/SYNC/POST 保留来源和 API_KEY；类型/组合检查委托 Catalog 的实际 AdapterRegistry；未登记非 HTTP binding_ref 明确拒绝，整批在首次 create 前预检。
- QUERY/MUTATION 与执行旗标一致，风险只可上调；OFFSET 保存原 offset/size 参数名及 maxPageSize/maxRows，严格正32位整数/键形状。原 PAGE、无分页及手工技能兼容。
- 旧编辑器省略 exposure/pagination 保回旧值，显式 null 拒绝；本体或新投影的既有高风险不因省略降级。保存/发布/摘要与现有审批门保持。
- 引擎仅防御读取和超限提示，保留原阈值/信封/小结果同节点；没有自动翻页、虚构 observed/nextOffset 或已强制总行限额。

## 实际执行顺序

| 阶段及归档目录（均在 collector/runs/） | 结果 | 门与含义 |
| --- | --- | --- |
| red-skill-20261029-101922-472 | 33=18P/15F，0E/0S；native1，无ignore | 原生产下预登记15个断言红；首坏分页混合批实际0→2草稿。首场景即失败，其余三类PG红不能冒称已到达 |
| red-engine-20261029-102649-693 | 47=36P/11F，0E/0S；native1，无ignore | 原22方法保留，新25报告；11个准确断言红 |
| green-skill-20261029-103602-527 | 33P，native0，无ignore/skip | 四类混合批均0→0；实际PG持久化两键JSON来源及治理/发布/HTTP头到达 |
| green-engine-20261029-104408-513 | 47P，native0，无ignore/skip | 两固定consumer逐字节引用实际PG137B，原22方法保持；实际prepareSnapshot冻结透传，服务为协议替身 |
| or-target-20261029-104759-772 | 9P，native0，无ignore/skip | OR原ToolBindingExposureTest，18源前后相同；只验声明/正式化透传 |
| full-skill-20261029-1102 | 689=565P/6F/114E/4旧S；native0(ignore仅采集)，collector1 | 新增8个旧手工保存ERROR，方法/整体门false，原件保留 |
| green-skill-legacy-save-20261029-1110 | 33P，native0，无ignore/skip | 针对上述真实红最小补修后重验；原四Test/断言不改，四批仍0→0 |
| full-skill-legacy-save-20261029-1112 | 71XML/689=573P/6F/106E/4旧S；native0(ignore仅采集)，collector0 | 方法与原因门均通过；原8E恢复P；原674＋15身份齐，新非通过/缺失0；全绿false |
| full-engine-20261029-1124 | 93XML/932=817P/35F/80E；native0(ignore仅采集)，collector0 | 方法门通过；原907＋25齐，930具名＋2原class events、14原source未执行保持；方法与有界原因证据门通过；全绿false |

各Run内 command.json/native-exit.json/source-before.json/source-after.json/methods.csv/xml-manifest.json 为准确命令、原生退出和冻结证据。外层collector退出是ROOT工具当时观察并按脚本控制流复核；没有单独外层原始文件的轮次不冒称有原件。XML/log仅本地保留，安全元数据入Git。共用Full采集器在engine摘要中的SDK/投影布尔为N/A默认值，不能据此声称engine跑了skill SDK或PG导入；实际依据为各模块明确的测试身份与来源证明。

## 完整回归发现的真实回归与最小修复

8个新增ERROR均在 Catalog.save 对旧手工技能缺失 importSource 调用严格 object(null)，于原测试目标断言前失败；对应 EvidenceRebind5、SkillExecution2、PublishDigest1。只改此前来源分类为 Map 判断，object/normalize/input拒绝仍严格，四Test完全不变；并撤回无关formatter差异，Catalog相对HEAD仅27行新增。见[真实红和修复指纹](legacy-save-repair/repair.json)、[独立补修审阅](legacy-save-repair-review.md)。新Full原8身份均恢复P，绝不回写1102旧红。

skill原116非通过的状态/type/Caused-by链/首项目帧均相同；115个message/detail原文完全相同，唯一原迁移身份只在精确2UUID＋2timestamp替换后全文相同，原hash保留。4旧skip精确相同，SDK默认5P/4网络outcome/0诊断。见[完整原因对照](collector/runs/full-skill-legacy-save-20261029-1112/full-reason-comparison.json)。

engine原115非通过的结构原因与方法状态保持；92项原始摘要完全相同，23项为16个Spring对象标识与7个测试connection标识。仅冻结名单、准确位置/次数、message＋detail联合双射/相等关系与其余全文均相等后接受有限表示差异；不归一化业务数值、SQL或原因，不设未来轮次通用豁免。原首次raw比较false及原摘要保持，派生方法/原因证据门通过，见[23项有限证明](collector/runs/full-engine-20261029-1124/engine-bounded-representation-proof.json)。

## 真实PG、协议替身与环境边界

最终skill Full从实际PG/XML抽出的137B投影 SHA 为 `0e99aae36146e3b35a06f9755e2532c1fed3202e095cd5b9cbc89efeedf8b2d9`，与原PG来源、两个engine固定consumer逐字节一致。修复green33的14e041…只是根键序相反，原字节未改，解析两键结构相同；见[键序对照](collector/inputs/full-skill-legacy-save/projection-order-comparison.json)。真实PG/HTTP鉴权与模型/engine协议桩分别记，不是两服务真实业务运行。

完整engine按原条件使用14个精确自有库（包括原app独立HTTP夹具专库）。原TestInputHttp9个Boot ERROR来自decoder Bean冲突，未迁移专库保持，不补schema、skip或测试属性。原timeout夹具仍先建本次UUID库再因缺证据属性产生原class error；后置证实集合7→8，恰1新增且owner正确、旧name/owner无变化，未清理旧库。见[范围](engine-full-pg-scope.md)、[最终准入复核](pg-admission-final-review.md)、[后置差异](collector/inputs/full-engine-candidate/dynamic-database-delta.json)。

原本仓target已有V222__run_context_pack.sql而src无此文件，先前PLAT15已记录。当前精确SHA419c753c…、Flyway校验701700386与PG历史及两源控制相符，前后未改；只将该已知项单列，未知历史仍拒绝。不clean/repair/补源码迁移，不宣称clean构建可复现；源码/构建/历史迁移一致性留EX-27后续准确范围处理。见[独立边界审阅](legacy-runtime-context-review.md)及[实际校验](collector/inputs/full-engine-candidate/legacy-runtime-proof.json)。

采集前发生过PS5无BOM中文路径与JSON数组枚举/历史前提分类停止，均在Maven前；保留pre-maven-stop与inputs里的原件，不计产品红。BOM修复去前三字节后源码完全相同；PG helper只读，未据此前置停止改产品/断言/数据库。

## 评审、后续与回滚

前置/测试/两侧源码/PG桥/旧保存补修/运行/PG准入的独立审阅均为本目录对应review.md；[最终实质审阅](final-review.md)、[精确索引审阅](index-review.md)和统一台账记录共同构成关闭依据。EX-05依赖待本卡正式关闭后解除，本轮不顺手开工；真实OR分页、J4与J1～J9仍归EX-07。O-02/O-03/PLAT-14不随组件绿自动完成。

回滚仅反向本卡4生产＋4Test的准确差异，先检查后续协作；保留已发布版本、专库、构建原件和证据，不重置库/清未知队列/整仓reset。三份仓外主入口在后续EX-08检查点保存精确快照，不声称AP产品提交直接包含仓外原文件。
