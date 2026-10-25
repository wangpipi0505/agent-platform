# 实现交接（v1.2）

用户明确要求新工程完全隔离，直接从前置任务持续开发。根目录固定为 G:/207/项目/算法重组工具/代码/agent-platform，禁止与相邻工程建立任何关联。

## 全局约定

模块：platform-gateway(18080)、app-management(18081)、engine-runtime(18082)、skill-registry(18083)、model-gateway(18084)、monitor(18085)、admin-common、web。开发服务默认仅监听127.0.0.1。独立PG127.0.0.1:25432/agent_platform_v12、Redis26379、MinIO29000/29001、前端15173/15174；真实环境通过显式配置注入。

独立Maven父工程及公共安全/异常/JSON/JDBC辅助由主智能体负责。模块自己负责其业务迁移，禁止修改他人V文件。各服务连接独立新PG的独立schema（app/engine/skill/model/monitor）；跨服务只HTTP或设计允许的只读投影。使用JdbcClient/JdbcTemplate与真实PG，服务每次都限定tenant_id。

ApiResponse<T>(String code,String message,T data,String traceId)，成功code=OK；PlatformException(int status,String code,String message)。Actor(String tenantId,String principalId,Set<String> roles,Map<String,Set<String>> attributes)提供静态current()读取SecurityContext中验签JWT，requireRole(String)。服务调用用ServiceTokens.issue(Actor)生成独立内部票据，不能透传用户token；接收方仍校验签名/受众/来源。
Json.write(Object)、Json.read(String)、Json.tree(Object)；Digest.sha256(String)。公共Jackson依赖Spring管理；数值ID初期PG identity，外部runId采用r_。

每个服务主类@SpringBootApplication(scanBasePackages={"cn.agentplatform.<模块无横杠>","cn.agentplatform.common"})。默认service包分别app/engine/skill/model/monitor/gateway。服务pom和application.yml由主智能体建立，不覆盖；需要新增依赖请说明。

## 第一批真实API

统一以网关18080为前端入口。网关按路由转发：/admin/apps、/admin/flows、/admin/prompt-templates、/admin/classifications到app；/admin/skills到skill；/admin/model-providers、/admin/model-entries到model；/admin/debug-runs、/admin/runs、/v1/runs到engine；/admin/evaluations、/admin/approvals、/admin/statistics、/admin/audit到monitor。

GET/POST /admin/apps；GET /admin/apps/{code}；PUT /admin/apps/{code}/config（{draftRev,config}）；GET/PUT /admin/flows/{code}（{draftRev,definition,layout}）；POST /admin/apps/{code}/publish/precheck；POST /.../publish/commit（{precheckId,draftRev,candidateSha256,idempotencyRequestNo}）。app返回{code,name,type:CHAT|FLOW,description,status,draftRev,domain,bizCategory,tags,config,definition,layout,currentVersionNo,updatedAt}，列表data为数组。GET /admin/apps/{code}/versions返回数组。首次新建草稿没有发布版本。

GET/POST /admin/skills（{code,name,type:HTTP|MCP|ALGORITHM,description,endpoint,authRef,inputSchema,outputSchema,execution:{readonly,sideEffect,idempotent,cancellable},guard:{roles,attributes},timeoutMs}）；GET /admin/skills/{code}；POST /admin/skills/{code}/versions/{version}/publish。skill记录{id,code,name,type,status,currentVersion,description,...}。

GET/POST /admin/model-providers（{code,name,protocol,baseUrl,trustLabels,secretRef}）；GET/POST /admin/model-entries（{providerId,modelName,displayName,contextWindow,maxOutputTokens,capabilities}）；POST /admin/model-providers/{id}/test。GET/POST /admin/prompt-templates与版本render。

POST /admin/debug-runs（{appCode,mode:FULL|STEP,inputs}）；GET /admin/debug-runs/{id}/status；POST /.../resume（{nodeInstanceId,pauseSeq,requestId,action}）；POST /.../cancel。GET /admin/runs与/{id}；POST /admin/apps/{code}/runs由网关转engine（{inputs,versionNo,requestId}）。debug状态{runId,status,paused,eventsWatermark,nodes:[],events:[],output,error,versionRefs}。SSE /admin/runs/{id}/events（fetch Authorization，id=seq）。

评测：POST /admin/evaluations（{appCode,draftRev,candidateSha256,datasetVersion,cases:[{inputs,expected,safety}]}）；GET列表/报告。必须真实执行候选且绑定摘要，不可用常量PASS代替。app发布消费通过报告，缺失或摘要不一致阻断。

页面依据v1.2路由，主要路径创建配置→编排→调试→候选评测→发布集成→运行监控。错误显示操作建议，空态提供本页所需下一步，不自动生成虚假运行或发布成功。

## 协作责任

前端智能体仅写web/及docs/evidence/frontend-*；应用域智能体仅写app-management/与monitor/及docs/evidence/application-*；运行时智能体仅写engine-runtime/与contracts/graph-definition.schema.json及docs/evidence/engine-*。主智能体负责公共底座、技能/模型、网关、基础设施与总集成。

不能将功能骨架或局部单测计作全部34任务已完成。真实模型/本体/IAM/容量输入未具备时，明确登记等待外部验收；模拟端点仅允许位于测试目录。
