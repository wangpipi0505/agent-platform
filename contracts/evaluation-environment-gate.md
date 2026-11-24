# 评测来源环境门增补（EX-17第一批）

质量BLOCKING/ADVISORY/OFF及普通ADMIN豁免只影响质量，不跳过来源环境门。app服务部署属性agent.publish.target-environment缺省PRODUCTION；可通过该服务独立环境文件AGENT_PUBLISH_TARGET_ENVIRONMENT显式设NON_PRODUCTION，不能由发布请求指定目标。新预检固定当前候选/报告/来源摘要，commit在原锁内复核；旧checks=[]或缺来源报告需新评测/预检。同一已提交request重放保留原版本事实。

monitor在首次外调前持久化sourceEvidence，基于服务端冻结candidate、PG不可变dataset身份/摘要及engine返回的运行模式生成publicationEvidence。scope=ENGINE_RUNTIME_AND_DATASET_IDENTITY，包含原tenant/app/draftRev/hash/semantic/report/dataset及环境、质量结果和canonical evidenceSha256。质量与来源消费同一报告，不能拼不同报告的PASS；保留原accepted同修订、SHA优先/semantic匹配规则。缺标签或旧RUNNING恢复为UNKNOWN，不能事后按当前模式追认REAL。

这一批REAL仅表示该scope内的引擎模式及数据集身份来源；不证明模型/技能/外源和用例数据真实性。其它参与者来源采集与统一判定仍由EX17后续实现，不把本批称为完整端到端真实性门。引擎DEMO/UNKNOWN不能用于PRODUCTION；显式NON_PRODUCTION的DEMO须人工确认当次预检、报告与摘要，UNKNOWN仍拒绝。

非生产确认请求追加evaluationEnvironmentConfirmation={confirmed:true,precheckId,evalReportId,evidenceSha256}，值全部来自本次服务端预检；缺少、旧绑定或目标变化拒绝。Vue真实宿主仅在服务端允许NON_PRODUCTION+DEMO时显示确认，切换预检/身份/候选/失败或提交结束清除，不提供普通质量豁免绕过入口。

回滚不删除旧报告、预检或版本snapshot。历史缺字段不能改写为REAL；保留当前默认PRODUCTION与来源缺失拒绝，不能用质量OFF恢复演示生产放行。live OpenAPI/正式服务与真实模型/IAM联合另按EX06/07/17承接。

## IAM 评测入口

`POST /admin/evaluations` 使用门户用户票。网关从请求中的 `appCode`、正整数 `draftRev` 与
`candidateSha256` 换取目标为 monitor 的委派票，资源为 `evaluation-candidate`、
`<appCode>@<draftRev>` 与候选摘要。monitor 在读取候选和提交评测前复核资源绑定及门户来源。
app 在候选读取端点复核同一绑定及编辑权限。
