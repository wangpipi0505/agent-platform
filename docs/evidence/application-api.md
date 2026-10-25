# 应用域及评测 API 交接

仅使用当前独立工程。统一响应 `ApiResponse`，所有查询取验签 Actor.tenantId；写操作要求 EDITOR（ADMIN为超级集），审批由引擎按节点confirmRoles精确授权。跨服务使用 `ServiceTokens.issue(Actor.current())` 独立票据；不转发用户 Authorization。

## 应用

- GET/POST `/admin/apps`：列表为数组；创建 `{code,name,type:CHAT|FLOW,description?,domain?,bizCategory?,tags?}`。
- GET `/admin/apps/{code}` 返回 `code,name,type,description,status,draftRev,domain,bizCategory,tags,config,definition,layout,currentVersionNo,updatedAt,candidateSha256`。
- PUT `/admin/apps/{code}` 更新元数据，包含 `draftRev,name,description,domain,bizCategory,tags`。
- PUT `/admin/apps/{code}/config` `{draftRev,config}`；GET/PUT `/admin/flows/{code}` `{draftRev,definition,layout}`。业务内容不同递增 draftRev，纯layout改变不递增。乐观锁冲突409。
- GET `/admin/apps/{code}/versions` 数组。首次创建无版本。
- POST `/admin/apps/{code}/publish/precheck` 返回 `{precheckId,draftRev,candidateSha256,evalReportId,passed,result,report:{checks,warnings}}`。阻断用 `passed:false,result:BLOCKED`（HTTP200检查报告，绝不等于已发布）。检查分六组；报告有效15分钟。预检不写版本。
- POST `/admin/apps/{code}/publish/commit` `{precheckId,draftRev,candidateSha256,idempotencyRequestNo}`。摘要/修订/过期/阻断412；请求号换内容409；同一候选不同请求号复用同一版本。事务内锁定草稿，落不可变版本、指针、skill_reference、publish_commit及生命周期日志，无HTTP持锁。
- POST `/admin/apps/{code}/disable|enable|archive|rollback`，rollback含`{versionNo}`；删除仅从未发布DRAFT。

### 运行时内部只读

`GET /internal/apps/{code}/candidate?draftRev=1&candidateSha256=...`
`GET /internal/apps/{code}/versions/{versionNo}`
`GET /internal/apps/{code}/published`

内部快照data含 `{appVersionId,versionNo,appCode,type,config,definition,draftRev,candidateSha256,name,description,domain,bizCategory,tags}`。候选versionNo=0，appVersionId=`code@draft:rev:hash`；版本appVersionId/versionId为真实PG bigint，另appVersionKey=`code@version`。发布快照附interpreterVersion及dependencyVersions。

摘要为对 `{appCode,type,name,description,domain,bizCategory,tags,config,definition}` 深层排序对象键后的JSON执行SHA256；数组保序。layout不参与。候选接口不匹配412。published接口仅PUBLISHED状态有效。运行中的历史快照可按版本查询。

### 引用约定

`config.skillRefs:[{code,version}]`，图中SKILL节点`skillRef:{code,version}`；`config.promptRefs:[{code,version}]`；`config.modelBinding:{modelEntryId}`；`config.guard:{roles:[],attributes:{key:[allowed]}}`。模板引用必须PUBLISHED；技能HTTP `/internal/skills/{code}` 的versions中指定versionNo必须PUBLISHED；守卫需被应用声明覆盖。模型 `/internal/model-entries/{id}` 的provider须ENABLED。外部引用服务故障阻断预检。

图遵循冻结契约nodes/edges(from,to)，暂兼容source/target。START/END各一、无环、全部从START可达且可达END。引擎仍执行自己的运行输入/输出校验。

## 提示词与分类

GET/POST `/admin/classifications`，写 `{kind:DOMAIN|BIZ_CATEGORY|TAG,code,name}`。
GET/POST `/admin/prompt-templates`，创建 `{code,name,description,content,variables:[{name,type,required,defaultValue?}],outputContract}`。
PUT `/{code}` 生成下一草稿版本；GET `/{code}/versions`；POST `/{code}/versions/{version}/publish`；POST `/{code}/versions/{version}/render` `{variables:{name:value}}`。

模板使用严格 `{{var}}`，未知、缺少必填、类型错误均400。发布后DB触发器禁止修改/删除。渲染返回text/rendered与`tokenCountEstimate`，该计数明确为估计，`tokenCountExact:false`。

## 评测

POST `/admin/evaluations` `{appCode,draftRev,candidateSha256,datasetVersion,threshold?:0.85(开发默认),cases:[{id?,inputs:{},expected:任意JSON,safety:boolean}],baselineReportId?}`。

cases 1~200，必须含安全用例；同一应用datasetVersion内容不可改变（409）；先HTTP冻结候选，再持久化候选、不可变数据集与RUNNING报告，后逐用例执行引擎：

`POST /internal/evaluations/run` `{appCode,draftRev,candidateSha256,datasetVersion,evalReportId,inputs}`。

只对返回status=SUCCEEDED且output与expected精确JSON相等判通过。安全全部通过、全用例通过率≥配置阈值、相对同数据集基线无新增失败才ACCEPTED。无安全用例不能通过。依赖故障ERROR、运行失败或输出不符REJECTED。报告results逐条附runId/status/actual/expected/passed/attribution。进程中断留下RUNNING，不能发布；POST `/admin/evaluations/{id}/resume` 使用同报告/caseId与冻结candidate恢复既有run，不读取新草稿。无后台自动恢复调度。

GET `/admin/evaluations` 数组；GET `/admin/evaluations/{id}` 报告。
GET `/internal/evaluations/accepted?appCode=&draftRev=&candidateSha256=`：无匹配返回 `{accepted:false}`，匹配返回报告含`evalReportId,datasetVersion,accepted:true`。app报告绑定evalReportId，不以UI声明为准。

## 监控投影与确认

GET `/admin/approvals|statistics|audit` 只读HTTP投影至engine `/internal/approvals|statistics|audit`。POST `/admin/approvals/{runId}/{nodeId}` `{approved,requestId,...}` 转发engine `/internal/runs/{runId}/approvals/{nodeId}`，引擎执行角色和状态校验。依赖尚未支持时返回真实502，不空数组伪造成功。执行记录/回放由网关路由engine已有runs/events接口，monitor不改引擎表。

## 明确限制

集成凭据已实现下节所列真实接口；发布报告的四件套路径仅提供路由地址，密钥需要管理员另行签发，不能声称发布自动签发了凭据。LLM-as-judge、人工标注、复杂rubric属二期未实现。真实模型/技能/本体/IAM及容量验收仍需外部输入。Schema检查一期覆盖类型声明及参数映射，复杂Schema组合关键字需另行验收，不得宣称完整JSON Schema标准实现。


## 集成凭据（已实现）

ADMIN权限：GET `/admin/apps/{code}/integration` 列表；POST `/admin/apps/{code}/integration/credentials` 签发 `{bizSystemCode,allowedOrigins:["https://example.com"],claimLimits:{principalIds:["alice"],roles:["operator"],attributes:{region:["A"]}}`。

签发返回`secretOnce`，只出现一次。列表不返回secret/ref/hash。`allowedOrigins`须为无路径HTTPS origin，本机HTTP允许；声明范围为明确数组，principalIds不允许空数组和通配符。PUT `/admin/apps/{code}/integration/credentials/{biz}` 修改相同策略；POST `/.../{biz}/revoke` 撤销。

内部GET `/internal/integration/{code}/{biz}` 必须SERVICE，返回`id,status,claimLimits,allowedOrigins,secretRef,appCode,bizSystemCode,issuedAt`。网关应逐次复核撤销与范围，resolve secretRef后验证外部签名；integration token禁止进入管理接口。密钥明文不进业务表，SecretVault使用独立AGENT_MASTER_KEY及共享AGENT_SECRET_DIR保存AES-GCM密文；开发本地实现，生产外部密钥管理需另验收。

新增：GET `/internal/apps/{code}/snapshot?draft=true` 为当前草稿快照；false/default为已发布快照。GET `/internal/prompt-templates/{code}/versions/{version}` 只返回PUBLISHED版本。GET `/internal/skills/{code}/references` 返回app侧原子维护的版本引用，供技能引用保护。

补充：通过率阈值仅校验(0,1]，0.85是开发默认，正式业务阈值D7尚待定标。V102已将主实体application与真实app_draft分表，保留既有数据，主实体/草稿均有bigint id；报告与版本通过真实数字FK关联，commit锁FOR UPDATE OF d；草稿业务字段不再留在主表。


认证专用例外：GET /internal/integration-credentials/{id}要求principalId=_gateway且显式SERVICE/GATEWAY_AUTH，以全局唯一凭据id解析tenantId；不采信body租户。签发返回apiKeyOnce=id:secret，明文仍只在签发响应显示。常规服务读取继续要求已验tenant。


## 发布产物与恢复约束
commit.artifacts 包含 webAppUrl（AGENT_WEBAPP_URL 默认 http://127.0.0.1:15174/w/{code}）、apiDocUrl、embedSnippet、ticketUrl、ticketInstructions。脚本全局 AIAssistant，endpoint 使用 AGENT_PUBLIC_GATEWAY_URL 默认18080。ticketUrl /your-backend/ticket 是宿主须实现的后端，返回 {data:{ticket}}；后端使用 opaque ApiKey 出票，浏览器不可保存长期 key。
评测报告含 createdBy；resume 必须原 principal，其他编辑者收到403，不能替换执行身份重跑。
