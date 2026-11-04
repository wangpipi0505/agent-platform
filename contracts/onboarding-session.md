# 接入会话与受控技能导入契约（CR-05）

## 使用入口

`/admin/onboarding-sessions`：GET list、POST create；`/templates`：GET当前PUBLISHED QUERY目录；`/{sessionId}`：GET、PUT save；`/{sessionId}/preview`、`/steps/{step}/execute`、`/cancel`：POST。sessionId为`os_`加32位小写hex。全入口要求当前已验签EDITOR；数据按tenant+创建principal隔离。模板维护旧API仍为ADMIN。

create为`{templateCode,templateRev,appCode,appName,requestId}`。save为`{expectedRev,requestId,plan}`。preview为`{expectedRev,requestId}`。execute为`{expectedRev,requestId,planSha256,evaluation?,evaluationEnvironmentConfirmation?}`；step只接受IMPORT_SKILLS、ASSEMBLE、VERIFY、PUBLISH。cancel为`{expectedRev,requestId,reason}`，reason非空且不超过512字符，PG回读cancelReason。

统一回读为`{sessionId,rev,status,templateCode,templateRev,appCode,appName,plan,planSha256,steps,application?,choices?,links?,handover?}`。status为DRAFT、ASSEMBLED、VERIFIED、PUBLISHED、CANCELLED。steps为当前planSha256下的`{step,status,receipt,error,requestId}`，status为RUNNING、SUCCEEDED、FAILED；历史计划收据仍保留。终态不接受新改稿或步骤。

## 计划来源

plan保留`fieldsBundle/values/selectedOperations/modelEntryId?/connectionRef?`，扩展`sourceDocument?/routePlan?/importOverrides?`。原规格不超过1MiB；routePlan只含environment、routeProfileId；importOverrides按原生operationId逐项提供原importer的name、description、execution、guard、returnSpec、errorSemantics、timeoutMs、constraints。浏览器不得提交code、tenant、principal、authRef、endpoint或执行脚本；真实服务根与secret_ref由批准连接事实提供。

EDITOR的导入连接提议只来自模板基底应用真实发布版本、固定摘要/refs，以及对应精确旧技能的当前read grant和当前守卫。提议只是元数据来源，不授予新技能执行权。ADMIN可在本租户已批准同步HTTP档案中选择精确绑定。来源冻结记录原发布版本/摘要/旧技能/refs，不枚举全部连接给EDITOR。旧授权撤销后不得用未交付的冻结提议新建草稿。

首版为单一标准同步只读HTTP QUERY；原规格完整预检、冻结包、显式人工合同和批准绑定必须共同匹配。缺失资料返回missing，不从模型摘要或地址猜造规格/守卫/验收结果。复杂Schema、返回数组下标、写/MCP/异步导入及任意新增文件获取明确拒绝。

## 导入、验证与来源

IMPORT_SKILLS先冻结当前计划不可变来源，再通过专用app内部能力调用既有OpenApiImports。每operation的编码由skill按sessionId+planSha256+operationId确定；草稿和不可变receipt同事务。GET恢复原receipt，404才以原冻结payload幂等创建；不修改原OPENAPI五字段importSource。

IMPORT_SKILLS的SUCCEEDED仅证明草稿已真实创建。receipt直接为`{drafts:[{operationId,code,version,skillVersionId,sha256,status,workspacePath}],missing,sourceResolved,links:{skillWorkspaces}}`。workspacePath指向原`#/skills/<code>`。用户在原技能工作台完成当前连接授权、受控连接测试、模型选型验收和发布，再用新requestId继续同一步，服务端只读精确PUBLISHED版本并固化session_source。

新技能的连接grant必须由管理员对确定`skill.<code>`显式授予；不继承旧技能授权，不自动授予。执行ref固定profile/bindingId/rev/operation/environment，导入前的目录摘要只留审计，不把空grant时摘要冻结为后续执行许可。每次真实执行仍复查当前grant/凭据。

受控草稿测试的执行refs由skill服务器按原owner、code、version、id、sha和不可变receipt复核后附到已有精确版本读口。engine从自己读取的精确metadata冻结refs；请求自报bindingRefs拒绝。治理证据还核对真实execution_fact.request_snapshot内同一refs，旧无绑定调用不能为新受控技能补造通过证据。

会话source不占用包全局一次性applicationPlan/receipt；当前主体/模板/包摘要、原规格、原生操作、精确技能及mapping摘要独立固化。应用关联的session_source_id与source JSON必须由服务端同事务建立，普通保存/发布仍走AppService来源门。已有人工draftRev变化拒绝覆盖；同会话改计划只能绑定新的不可变来源，旧源和发布快照保留。

## 应用与回执

ASSEMBLE复用原prepare、validateCapabilityDraft、AppService.create、真实来源关系及save。fields/mappings进入标准SKILL.args，false/0/null保留；摘要/卡片进入END.args的既有Values递归方言，不只保存metadata。VERIFY只发起一个真实debug run并保存runId；继续/恢复轮询原run，未知结果不重发副作用。

PUBLISH仍要求原候选评测、结构、权限和环境确认预检及commit；不生成自动PASS。发布请求号固定，commitRequest先落步骤收据再调用原发布门，恢复重放相同请求。真实发布成功才返回handover：templateCode与实际appCode分离，appVersion记录当时结果；`/w/<appCode>`遵当前助手入口语义，不暗示前端支持未消费的固定版本参数。

IAM受控执行仍服从原RunAdmission范围，不能借导入放开DEBUG/controlledDraft。新增DEV导入必须使用独立app请求证明和原真实服务JWT；未配置证明关闭，IAM不回退DEV。开发编译通过不能替代正式授权配置或跨服务真实功能复核。
