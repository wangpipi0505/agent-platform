# 技能服务 API 与执行交接

仅当前独立新工程，Spring Boot3.5.0/Java21，schema skill，迁移V300~V306。所有常规查询以验签Actor的tenant限定；执行事实还限定principal。内部调用使用新签ServiceTokens，不透传用户token。管理写EDITOR，带authRef的创建/修改/连接测试另需ADMIN；公共回包隐藏authRef为authConfigured。

## 管理与精确版本

- GET/POST `/admin/skills`；GET/PUT/DELETE `/admin/skills/{code}`；PUT `/{code}/draft`同保存；删除仅从未发布的草稿，发布历史禁止删除。
- GET `/{code}/versions`；POST `/{code}/versions/{v}/publish`与`/{code}/drafts/{v}/publish`。
- 创建支持brief平铺：`{code,name,type:HTTP|MCP|ALGORITHM,description,endpoint,authRef?,inputSchema,outputSchema,execution:{readonly,sideEffect,idempotent,cancellable,confirmRoles?},guard:{roles,attributes},timeoutMs}`。
- 亦支持详细设计表单`invocation:{adapterType,endpoint,httpMethod,authRef,timeoutMs,kind}`、`access:{roles,guardAttrs}`，以及constraints.outputMaxBytes、returnSpec、errorSemantics、reconciliation。修改已发布生成新DRAFT版本；修改现有DRAFT保留版本号但重新计算sha，旧报告不匹配新摘要。
- readonly与sideEffect互斥，风险READ_ONLY可上调CONFIRM_REQUIRED；写/需确认技能必须声明execution.confirmRoles。查询需声明守卫属性。guard.attributes可限制静态范围，空数组仅表示声明属性，实际允许集仍取已验Actor并要求非空；参数与允许集求交，不能自报扩大。
- authRef只能vault:或授权env:AGENT_SECRET_*，SecretVault同租户解密；env必须AGENT_SECRET_BINDINGS显式归属。密钥永不明文入业务契约。
- GET `/internal/skills/{code}?version={exactVersionNo}`、GET `/internal/skill-versions/{id}` 返回精确合同、真实bigint skillVersionId、status及skillStatus。无version的内部GET兼容列表/当前指针，含versions数组供应用预检；执行必须显式精确versionId。
- PUBLISHED→新发布后旧版本ARCHIVED，旧引用仍可执行；DISABLED拒绝执行；被应用引用时停用拒绝，引用来自app的tenant受控HTTP投影。

## 上架门禁

POST `/admin/skills/{code}/drafts/{v}/connection-test`：`{inputs:{...},requestId}`创建技能专属受控DEBUG运行，支持精确DRAFT/PUBLISHED/ARCHIVED版本；DISABLED拒绝。只读无需确认，写操作仍走真实确认。成功后以`{controlledCallId}`重检，必须同tenant/principal、精确版本、当前合同摘要及非Mock成功事实，不接受预留ID冒充完成。

POST `/.../acceptance`：`{modelEntryId,cases:[{question,expectSelected,expectArgs?}]}`。逐题真实POST模型`/internal/model/chat`，提供该技能function定义；从toolCalls取实际selected/arguments并Schema判分，hitRate≥80%才可发布。模型服务不可用返回真实错误；不能上传pass字段绕过验收。连接/选型报告均绑定版本id+当前sha，改稿后旧报告失效。

## 执行权威与八步

POST `/internal/skills/{code}/execute` 接引擎原始请求：
`{callId,runId,nodeInstanceId,attemptId,skillVersionId,arguments,idempotencyKey,leaseOwner,triggerType,mock?}`。

1. networknt Draft2020-12参数校验，禁止远程$ref；2.角色与属性交集守卫；3.写操作在claim前查引擎`/internal/tool-calls/{callId}/approval`，必须approved且skillVersionId/parametersDigest相符；无批准返回WAITING_APPROVAL与确认快照，body.confirmed无效；4.引擎claim核对版本/code/argsDigest/node/run/idempotency_key，再按权威trigger_type仅DEBUG命中Mock；5.发外部请求前必须engine submitted成功；6.逐字节有界接收+Schema验收；7.ResultEnvelope四份内容与归属链；8.PG事实提交后才回调engine fact。

返回`{status:SUCCEEDED|FAILED|UNKNOWN|WAITING_APPROVAL,errorSemantic?,result:ResultEnvelope,confirmation?}`。信封含owner/error/modelSummary/structured/fileRefs/cardPayloadJson/isMock/guardTrace；实际响应完整ontologyMeta字段存在时采集并附callId，不把注册的预期版本冒充观测版本。

returnSpec支持structuredPath、modelSummary(PATH/TEMPLATE)、fileRefPaths、JSON cardTemplate。路径支持点路径、数组下标和[*]；模板值安全写为JSON字符串，不能注入额外卡片字段。超大响应使用真实对象引用，不把整个payload带入上下文。

事实唯一键callId；PG.execution_fact是最新事实和统计行，execution_fact_history保留旧attempt代。重复调用只重投事实，不重做业务。唯一例外：只读RETRYABLE_FAILED且引擎明确把同call置REGISTERED并换新attempt，才允许新代claim执行；重试层仅引擎。业务总时长累加真实执行段，回调仅报告本attempt_duration_ms；不把退避/治理/对账耗时混入业务段。

POST `/internal/skill-facts/{callId}/retry` 只投既存事实。回调失败或旧lease LATE返回UNKNOWN，不伪造引擎已采纳成功。

## UNKNOWN对账与统计

POST `/internal/skill-facts/{callId}/reconcile`：仅权威UNKNOWN。HTTP对账只GET契约登记的reconciliation.endpoint，核对响应idempotencyKey归属、donePath/doneValue和resultPath，再校验原输出Schema；ALGORITHM只查确定性原Job。未生效/仍未知不自动重发，retryBusinessAllowed=false。已核实结果更新同callId终态，独立reconciliation_note保留查询段；过期lease仍需引擎受控人工采纳。

GET `/admin/skills/{code}/stats` 与 `/internal/statistics` 从真实execution_fact按skill/version/callKind/isMock/status聚合，callId唯一、Mock分列、p95/平均耗时取实际执行段；UNKNOWN终态更新不会增调用数。

## 适配器与部署边界

HTTP使用RestClient；常规方法为SimpleClientHttpRequestFactory，PATCH为有界单次Java21 HttpClient/JdkClientHttpRequestFactory（关闭重定向、finally关闭）。固定连接3秒与契约读取超时，JSON标量经Jackson正确编码；外部认证仅解引用secret_ref。
MCP使用MCP Java SDK0.10.0 SSE client真实initialize/callTool；ALGORITHM使用K8s Java Client23，必须显式AGENT_K8S_API/专用namespace/serviceAccount/允许镜像，无默认读取用户旧kubeconfig；确定性Job名，先PG绑定，不重复创建，非root/不挂载serviceaccount token/资源上限，实际stdout有界读取。

超outputMaxBytes后prefix(max+1)加余流直接MinIO multipart（5MiB分块），原始对象回读验证Schema。绝对64MiB、250000 token、深度100、单字符串8MiB上限。MinIO缺失/上传/验收失败返回真实失败，不生成虚假artifact。SDK文件和环境都必须只指向当前独立工程；独立MinIO已部署并以真实5MiB上传/受控下载/清理/跨run复制验证，详见artifact-report与skill-mock-authority。

已补OpenAPI3.0文本导入安全子集、声明式path/query/JSON映射，以及仅认引擎权威并重新核当前有效范围的LAST_REAL，见skill-openapi-report和review-execution-followup。仍未实现/待验收：签名异步CALLBACK、通用HTTP SUBMIT_QUERY完成协议、技能侧分布式并发/熔断、完整取消令牌穿透、MCP传输层预缓冲的逐字节上限改造、生产K8s/MCP和对象存储容量联合验收。保存这些配置字段不等于这些能力已完成，当前不能据此宣布所有技能任务完成。

## 最终修订
- V304：PG outbox 使用 tenant 内 SKIP LOCKED 认领与 owner/lease CAS；2秒调度，每次最多8条，重投现有事实，绝不调用业务适配器；失败指数退避。系统发现器只枚举待投递租户，执行仍按持久 tenant/principal 最小 SERVICE 身份签票。
- V305：事实保存 skill_contract_sha256，连接验收须匹配当前草稿摘要。
- 写技能首次上架：connection-test {inputs,requestId} 调 engine 受控 /internal/skill-draft-tests，正常确认后回查受控成功事实。仅权威 controlled_draft_skill_version_id/digest 精确匹配且 DEBUG+EDITOR 可执行草稿；普通 body 标记无效，草稿修改摘要漂移拒绝。
- CHAT/TASK 权威触发归为 FORMAL 统计，request_snapshot 保留 authorityTriggerType 原值。Mock 仅权威 DEBUG。
- GET /internal/skill-facts/{callId} 返回持久证据，仅 SERVICE 且原 tenant/principal。factSha256=SHA256(Json.canonical(所有返回字段排除factSha256))；字段 factId(sf:call:attempt)、callId、runId、nodeInstanceId、skillVersionId、argsDigest、skillContractSha256、attemptId、status、result、durationMs(本代)、isMock、callKind。读取不触发业务。


## 受控复现附件接口

POST `/internal/skill-facts/{sourceCallId}/reproduce-artifacts`：仅SERVICE+EDITOR，body `{callId,runId,nodeInstanceId,attemptId,leaseOwner}`。重新通过engine claim核验有效租约与源call/digest/当前归属，从技能PG核源事实和当前guardedArguments，再流式复制源受控附件为当前run新artifact。body不能提供结果或对象键。此接口不调用模型或业务技能。真实跨服务5MiB复现下载与零新增业务查询见integration-report。
