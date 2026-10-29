# EX-04 本体侧字段与执行边界只读核查

2026-10-29，核查者 `/root/ex04_or_audit`。用户当前已授权实施EX-04，EX-03已完成；早期三份准备稿中的“EX03未关闭/EX04未核准”仅为历史时点，不覆盖本次读取的主入口v2.12/v1.12/v1.12；这些版本号只标记读取时点，后续台账回填可继续变更。本次只读源码、契约、已有基线安全方法清单及Git状态，唯一新增本报告。未使用Superpower；没有运行Java/Maven/PG/SQL/HTTP/前端/服务，没有读取真实环境文件或凭据，没有修改OR产品/测试、其它AP文件或Git index/提交。

## 结论

本体侧现有Generator→冻结preview→正式化→HTTP端点投影链没有发现需要扩大EX-04生产白名单才能保留`exposure`的缺口。当前卡片的“OR产品只读、复验既有ToolBindingExposureTest”可继续执行。AP应按已批准的消费规范严格映射并补批量预检；不能把OR的描述性字段或类型枚举当作绑定目录、异步执行协议、真正业务分页或完整审批。

本次结论来自源码/既有测试设计及历史基线文件，没有执行新复验，不是OR/J4通过或EX-04 DoD签收。

## 读取依据与身份

OR HEAD为`da3345a123752c3fda06c5a7a8a1dc9d614b8918`，以下六文件写报告前重新哈希一致、在OR相对HEAD没有tracked diff。

已读OR `AGENTS.md`及其指定的`docs/continuation-plan.md`，契约职责简报`docs/evidence/continuation/c4-contract-brief.md`，`contracts/p2-supply.md`与`p4-release.md`。OR没有AP同名的`docs/implementation-brief.md`或`contracts/README.md`，没有据此阻塞。历史计划含Superpower工作流文字，本轮按用户明确“不使用superpower”不采用。另读三份当前主入口EX04、`preflight-mapping.md`、`mapping-decision-draft.md`、`mapping-review.md`；没有改写这些原件。

| OR仓内文件 | SHA256 |
| --- | --- |
| `onto-contract/src/main/java/cn/ontorepo/contract/ContractGenerator.java` | `b19eb968f279544883413e7d568fee6b9419add68d41e38c07da62a97e6d0cfd` |
| `onto-contract/src/main/java/cn/ontorepo/contract/ContractValidator.java` | `07389201b7b1e8497fe3582e6ef33ef05105d7ff09061168d0da5adc93f76d2e` |
| `onto-contract/src/main/java/cn/ontorepo/contract/ContractFields.java` | `00d4bac97d2a32edfdc6f40ca5dcf87572363bd38ad9a769723cf6cad49862f3` |
| `onto-contract/src/test/java/cn/ontorepo/contract/ToolBindingExposureTest.java` | `ade9ac2f83665b36fc6d4cab59a2d32ee38c61c79a0d5f873f80a572339c69c7` |
| `onto-release/src/main/java/cn/ontorepo/release/application/PublicationService.java` | `81e1a09a4538557f30e3b608ded4d650d8f36ed0e25943d93ee513e22cb39db0` |
| `onto-release/src/main/java/cn/ontorepo/release/application/ReleaseGatewayService.java` | `abcdfbbf7f28033e6b8704c04bae0deb7fbaa8c13c6d7c76d7b2caa931dc13c2` |

## 生产字段的真实形状与生效位置

- `ContractGenerator.java:30–72`从`assistant_tool`读取binding/exposure、执行属性、风险和return_spec。输出使用`adapter_type`、`invocation_kind`、可选`binding_ref`、`risk_level`；AP技能definition的`type`、`invocationKind`、`riskLevel`是消费侧映射名，不能倒称OR输出这些camelCase键。
- `ContractGenerator.java:94–114`绑定缺失或null时默认HTTP/SYNC。绑定声明的type与invocation枚举分别校验，缺invocation默认SYNC；非HTTP引用来自`binding.reference`，输出`binding_ref`。HTTP仅生成POST本体消费路由，非HTTP不会补endpoint/http_method。允许某个枚举值不表示该组合在AP AdapterRegistry已实现，也不表示存在可解析引用的名义绑定目录。
- `ContractGenerator.java:54–55`对非缺失、非null的exposure做deepCopy；`:80–93`正式化再次deepCopy冻结preview，只填版本四元组和HTTP正式route，再validateImport。因此不会因正式化丢分页，也不会为非HTTP补假HTTP入口。null exposure在生成阶段省略，不能把端点目录的`exposure:null`直接当成“正式manifest显式分页”。
- `PublicationService.java:794–809`从候选保存的`manifestPreview`逐个formalize，用预分配releaseId、`v`＋preVersionNo及候选sha，不重新生成助手声明。`:568–593`只为HTTP绑定或旧缺省binding建`service_endpoint`，将`tool.exposure`原样写JSONB；`:728–739`把binding/projected/exposure放入发布报告projections。
- `ReleaseGatewayService.java:97–118`查询端点目录时返回exposure；同空间凭据及空间授权门仍在。目录回显不是消费操作已经执行分页的证据。

## 校验强度及AP必须自行落实的规则

| 字段/组合 | OR当前代码语义 | EX-04消费侧含义 |
| --- | --- | --- |
| adapter_type / invocation_kind | Validator:31–32分别只认HTTP/MCP/ALGORITHM与SYNC/SUBMIT_QUERY/CALLBACK；Generator绑定声明同样枚举校验 | AP还须复用现有注册表校验组合；HTTP非SYNC缺本导入路径异步协议应按卡片拒绝invocation_kind，不能仅透传枚举 |
| HTTP入口 | Validator:34–36要求合法HTTP(S) URL，http_method只能POST | 保留真实正式路由，不改写成另一个执行器 |
| 非HTTP入口/引用 | Validator:38–42对非null endpoint/http_method报BINDING_ENDPOINT_CONFLICT；binding_ref存在时须非空文本，但可缺失 | 描述性mcp://引用不证明名义绑定已登记；无目录时缺失/未登记均按binding_ref明确400。不得强行HTTP化 |
| exec/risk | Validator:48–58严格读取布尔及正整数；risk只认READ_ONLY/CONFIRM_REQUIRED。readonly=false或side_effect=true却READ_ONLY时报RISK_DOWNGRADE | 保留显式只读高风险；非只读/副作用不能降级，整批首create前拒绝；勿扩大旧手工API风险兼容边界 |
| exposure.kind | Validator:80–95只认QUERY/MUTATION；明确拒绝MUTATION＋readonly=true | OR并未建立QUERY⇒readonly且无副作用的反向保证；卡片的QUERY消费一致性是本轮AP新增规则，应有独立负例 |
| MUTATION side_effect | 生产只约束readonly=false；在risk=CONFIRM_REQUIRED下未强制side_effect=true | 原显式sideEffect=false也应保存，不补true、不降确认。ToolBindingExposureTest:139的“side_effect=true才允许”是测试注释的过强概括，不能替代生产规则或当前卡片 |
| exposure.pagination | Validator:86–92对象且四键limitParam/offsetParam/maxLimit/maxRows必需；参数非空文本 | AP转为单一顶层pagination权威，exposure只留kind，禁止offset变page |
| 数字边界 | ContractFields.positiveInt:36–40要求integralNumber、canConvertToInt且>0；不宽松转字符串/小数/溢出 | maxLimit/maxRows均正32位整数；源码没有maxRows≥maxLimit约束，不增加该限制 |
| 未知字段 | 当前Validator按所需键读取，没有检查exposure/pagination未知键集合，Generator会deepCopy它们 | AP本卡既定严格键集合/模式规则需自行检查，不可声称OR已把所有未知字段过滤或拒绝 |
| 可选声明 | 缺失/null exposure、缺失/null pagination在OR相应读取处跳过，旧契约不自动补QUERY/PAGE | 对源生成后真正缺键的旧技能保持缺省；AP新规范的显式null/空对象/模式混用按卡片严格拒绝，不臆造默认分页 |

## 可直接用于AP测试的源码结构样例

以下是**按已读源码写出的增补片段**，未在本轮调用Java生成，不是从真实OR运行制品读取的manifest。完整必需字段（schemas/guard/return_spec等）沿已验EX03合法夹具保留；测试鉴权输入沿EX03 secret_ref/ADMIN/tenant门。OR并不在Generator中生成AP的authScheme或authRef。

```json
{
  "adapter_type":"HTTP",
  "invocation_kind":"SYNC",
  "endpoint":"https://onto.example.com/onto/r/sp/releases/6f447594-0eb2-4f3f-8d0e-00b26af86d51/ops/page_tasks",
  "http_method":"POST",
  "exposure":{"kind":"QUERY","pagination":{"limitParam":"limit","offsetParam":"offset","maxLimit":200,"maxRows":5000}},
  "exec_attrs":{"readonly_flag":true,"side_effect":false,"idempotent":true,"cancellable":true,"timeout_ms":5000,"max_rows":20},
  "risk_level":"READ_ONLY",
  "ontology_meta":{"spaceCode":"sp","releaseId":"6f447594-0eb2-4f3f-8d0e-00b26af86d51","version":"v1","contentDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
}
```

其中分页期望规范片段应为`exposure:{kind:QUERY}`和`pagination:{mode:OFFSET,request:{offset:offset,size:limit},maxPageSize:200,maxRows:5000}`；这项AP规范增补由卡片规定，并非OR原输出。max_rows=20是独立原执行policy声明，不应与pagination.maxRows混为同一限额。

既有`ToolBindingExposureTest`里formalize使用`releaseId="rel_1"`（:76/:222），原sample-2/3/4也有`rel_001`。它们只用于OR契约非空四元组核，不能直接用作AP正式ONTOLOGY来源UUID正例。AP应使用合法UUID（真实PG正例从其专库/正式夹具取得），并让route/meta/source一致，不放宽EX03来源断言。

建议AP负例保留单点变异：已知类型的未知kind组合、HTTP非SYNC、非HTTP携HTTP入口、缺失/未登记binding_ref、QUERY可写或副作用、MUTATION只读、风险降级、分页字符串/小数/溢出/空参数/缺键/未知键；混合批必须有首项有效后项无效。单项坏字段被OR类型校验拒绝，并不能替代AP首create前整批零写入测试。

## 既有样例能证明到哪一层

`ToolBindingExposureTest`是纯契约核，构造普通ObjectNode，未启动Spring、PG、MinIO、HTTP或真实操作执行。现有9个@Test身份如下；历史EX00 methods.csv对应9个passed，**这些是2026-10-28基线，不是本轮复验结果**：

1. mcpDeclarationNoLongerRequiresHttpUrlOrPost
2. algorithmBindingWithSubmitQueryKindIsDescribedNotExecuted
3. undeclaredBindingKeepsLegacyHttpSyncManifest
4. nonHttpManifestCarryingHttpEntryIsRejected
5. exposureDeclarationFlowsIntoManifestAndValidates
6. mutationExposureCannotPretendReadOnly
7. invalidExposureDeclarationsFailWithTypedErrors
8. malformedBindingDeclarationFailsWithTypedErrors
9. formalizePreservesExposureDeclaration

另只读了现有`onto-designer-api/.../ToolProjectionRegistryTest.java:14–178`：其P4Fixture通过发布主线、PG/MinIO验证exposure在端点目录/发布报告中的投影、非HTTP无本体端点。这是可定位的既有整合测试设计，本轮未运行，不扩大为本卡复验范围。它的分页用例给`listClasses`添加声明后断言回显，不发送offset/limit操作调用，也不比较实际不同页的数据。

精确扫描`onto-*/src/main/**/*.java`对`offsetParam|limitParam|maxLimit`的文字引用，仅在ContractValidator:89–91字段校验处命中；未据此得出“本体所有操作都不能分页”。可以确认的是这些声明键本身没有在当前扫描到的业务入口变成自动输入改写器。需要真实J4正例时，应选择确实接收并读取offset/limit的合法业务操作，验证不同请求/响应及实际平台冻结技能，不用生成器/目录回显替代。OR其它`policy.max_rows`执行约束与此不同，不在本报告撤销或泛化。

## 指定复验入口、依赖及安全前提（本轮未执行）

当前卡片指定唯一OR定向为：

```powershell
Test-OR onto-contract 'ToolBindingExposureTest' -Database BASE
```

它是联合手册§2.1当前会话函数；在OR根目录工作，实际Maven选择器为`-pl onto-contract test -Dtest=ToolBindingExposureTest`，保留手册现有`-B -ntp`、JDK21、`-Xmx512m`、本仓临时目录双tmpdir属性、surefire.timeout=900和failIfNoSpecifiedTests=true。该定向不加failure.ignore、不加-am、不改fork/order/skip；应为1份新鲜XML、原9精确身份9P/native0/无skip。实际参数/退出/时间/源码SHA与XML身份必须重新归档，不能沿用历史9P。

onto-contract/pom.xml声明的直接依赖为json-schema-validator2.0.4和JUnit；本报告未运行依赖树命令或新增依赖。无相邻业务模块JAR、PG或HTTP集成依赖，ToolBindingExposureTest本身不使用数据库。测试仍须纳入ROOT独占实际窗口，不因纯模块就擅自并跑。若用手册Test-OR，沿已核OR配置导入并finally恢复BASE URL，不读AP环境/凭据、不改onto_test为P4；不要使用仍全量重定向P4的旧deploy/Test-Local.ps1包装器。

原EX00整模块五类101P只作对照，非本次必需新增运行。若ROOT另要完整onto-contract复验，准确原5类是ContractReviewRegressionTest、ContractTest、P3GuardTest、SchemaRecursionTest、ToolBindingExposureTest；不得擅自扩到onto-release/onto-designer-api、重启服务或触碰P4库。命令准备、实际运行与字段业务验收三层分别记录。

## 当前处置边界

没有发现必须修改OR Generator/Validator/PublicationService的本卡阻断；没有改OR产品或测试，也没有读真实运行manifest/PG队列/凭据。现有HTTP非SYNC及非HTTP未登记只能支持AP准确拒绝，不提供本卡“真实非HTTP执行成功”正例。真实业务offset分页、OR操作端到端与J4仍留EX-07。当前报告不改变EX04进度、其它能力项或任何旧验收状态。
