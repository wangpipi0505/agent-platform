# 命名测试输入与单用例提升后端验收

本阶段按已批准 `test-input-plan.md` 实施 T20/T22 的后端增量。原设计、四契约、运行/模型/技能/统计/输出策略核心类未修改。未提交 Git，未外部发布，未替换或启停六个既有开发服务。前端消费者另由前端阶段交付，本报告不把本阶段计为完整 T20/T22 或完整评测用例库验收。

## 实现边界

- app 新增 `DraftMetadataService/Controller`，在 app 域读取真实 `app_draft.id`，必须同时具有验签后的 SERVICE 与 EDITOR；按 tenant 查草稿，停用/归档拒绝。engine 不读 app schema。
- engine V214 新建 `debug_input` 与 `debug_input_promotion`。输入按 tenant + created_by 隔离，显式 EDITOR 必需（ADMIN 不替代），名称上限128字符，完整 inputs 对象上限64KiB、16层；列表1~100项、按ID游标分页。更新与删除使用 revision CAS，冲突409。列表不返回变量值，详情/新建/修改/首次提升均校验当前草稿输入声明。
- engine `InputDeclarations.schema(JsonNode)` 将声明数组转换为JSON Schema；`InputDeclarations.validate(draft, inputs)` 同时接受 CHAT `config.inputs`、`config.inputSchema` 与 FLOW `definition.inputs`。与 `web/shared/input-schema.ts` 一致，声明的顶层大写类型规范为小写，required独立为字段名数组，JSON null/false/0不丢失，使用现有networknt1.5.6做约束验证。对象/数组须为已经解析的JSON值，不把字符串强转；default只是声明注解，后端不补齐缺失值，不改写保存数据。拒绝网络/文件引用，允许本地 `$defs` 引用。这里只接入命名输入；RunService正式/DEBUG/EVALUATION入口的通用应用声明校验是后续单独核对项，未声称已经覆盖。
- engine `TestInputClient` 使用独立HTTP适配器，复用 `ServiceTokens.issue` 换发120秒票据传原主体；不改 `RuntimeClients`。草稿检查与 monitor 请求都在事务外，连接3秒/读取10秒。响应读取中断（包括RestClientException包装的EOF）返回502，客户端保留原requestId及完整请求重试。
- 首次提升从PG读取本人的指定revision输入；短事务 `INSERT ... SELECT ... revision=? ON CONFLICT DO NOTHING` 确认该修订并冻结完整source/target。相同requestId不同输入ID、revision、期望值、用例、safety或目标版本409。事务提交后才调用monitor；响应丢失后，即使源输入已编辑/删除，仍沿原冻结快照恢复，同时重新校验草稿归属/可用状态。输入删除不级联删除提升意图。
- monitor V502只在既有 `dataset` 增加提升来源与幂等字段。数据集版本和原主体的requestId均唯一；冲突不更新旧数据。使用原cases形状 `{id, inputs, expected, safety}`，来源不污染评分用例。只有本人EDITOR可读取本阶段提升的数据集，列表不返回cases；同租户其他主体不因知道版本号读到内容。
- 本阶段每次提升创建一个用例的版本。`safety`仅接受布尔值；false可保存，但单独提交现有评测会因缺少安全用例而拒绝。既有评测规则保持：安全100%、通过率阈值、基线防回归、候选和数据集绑定。多用例编辑、完整分类治理、LLM judge及人工标注未实现。

## 固定接口（ApiResponse.data）

全部管理端接口仍需验签JWT，租户/用户不接受请求body提供。网关路由由root补充，证据见 `test-input-gateway.md`。

```http
POST /admin/debug-inputs?appDraftId=123
Content-Type: application/json

{"name":"安全回归","inputs":{"message":"用户填写的输入"}}
```

成功返回 `{id, appDraftId, appCode, name, inputs, revision:1, createdAt, updatedAt}`。

```http
GET /admin/debug-inputs?appDraftId=123&afterId=0&limit=20
GET /admin/debug-inputs/42
PUT /admin/debug-inputs/42

{"revision":1,"name":"修订名称","inputs":{"message":"修订输入"}}

DELETE /admin/debug-inputs/42?revision=2
```

列表返回 `{items:[{id,appDraftId,appCode,name,revision,createdAt,updatedAt}],hasMore,nextAfterId}`；详情返回完整inputs；PUT返回revision递增后的详情；DELETE返回`{deleted:true}`。草稿声明改变导致旧输入不满足时，详情返回400；列表metadata仍可获取，用户可用当前输入表单完整替换并携带revision提交PUT，不隐式补参。

```http
POST /admin/debug-inputs/42/promote

{"requestId":"由客户端生成并在重试时保留","revision":2,"datasetVersion":"safe-v1","caseId":"safe-001","expected":{"answer":"用户预先填写的期望结果"},"safety":true}
```

必须显式提供expected（允许显式JSON null）；请求不接受inputs、runId或output覆盖字段。**当前评分器将expected与完整run.output做JSON精确相等比较**，包括输出中存在的usage/actualModel等字段；本阶段没有改为业务字段投影。不从刚运行出的实际结果生成expected以自评分。示例FLOW可预先指定`{"answer":"source"}`；实际CHAT输出若含动态元数据，需要用户明确理解当前评分限制。

成功返回：

```json
{
  "appCode":"demo","datasetVersion":"safe-v1",
  "cases":[{"id":"safe-001","inputs":{"q":"source"},"expected":{"answer":"source"},"safety":true}],
  "sha256":"64位数据集摘要",
  "source":{"inputId":42,"revision":2,"appDraftId":123,"inputsSha256":"64位来源输入摘要"},
  "requestId":"客户端原requestId","createdAt":"ISO时间"
}
```

datasetVersion限定`[A-Za-z0-9][A-Za-z0-9_.-]{0,127}`。同version同完整提升内容重试返回同数据；不同requestId/来源/内容不能覆写已存在版本。502后必须重试原完整body，不能把当前源输入的新revision替换到原requestId中。

```http
GET /admin/evaluation-datasets?appCode=demo&afterVersion=&limit=20
GET /admin/evaluation-datasets/demo/safe-v1
```

列表返回`{items:[{appCode,datasetVersion,sha256,source,requestId,createdAt}],hasMore,nextAfterVersion}`；详情另含cases。内部接收入口为`POST /internal/datasets/promotions`，由engine调用，网关不透出。数据集详情的原cases连同当前appCode/draftRev/candidateSha256/datasetVersion提交既有`POST /admin/evaluations`；不得修改同版本cases。

## 设计和开源机制实际阅读

- 已读本工程AGENTS、README、implementation-brief、contracts/README、冻结详设快照册30 §1.7/4.2/5.4及第九部分评测库要求。冻结源SHA256为`3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`；`node scripts/verify-contracts.mjs`通过。
- 已读[PostgreSQL16 INSERT官方文档](https://www.postgresql.org/docs/16/sql-insert.html)的ON CONFLICT/RETURNING语义：以唯一约束处理并发占位，随后比较持久内容，不用覆盖式UPSERT替代不可变版本。未声称阅读PG全部源码。
- 已实际读[Spring Framework6.2.7 TransactionTemplate源码](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.7/spring-tx/src/main/java/org/springframework/transaction/support/TransactionTemplate.java)的execute：回调完成后commit，异常rollback。提升HTTP在execute返回后调用。此API及Apache2.0实现按项目依赖使用，业务代码独立编写。
- 输入转换实际对照本工程前端`input-schema.ts`与既有JsonGate、OutputPolicy网络加载阻断配置；networknt使用项目已有1.5.6依赖，未声称通读上游源码，也未复制相邻工程。

## 验证与限制

使用Java21、独立`.tools/m2`、V盘临时目录。新增HTTP测试只连接专用`agent_platform_test_input_test`，真实Spring Security验签、真实PG、真实随机TCP端口。既有全模块回归按原测试使用本工程专用集成测试库，未接入`agent_platform_dev_v12`运行库。

红灯：`test-input-red.log` engine5项均缺失接口404；`test-input-domains-red.log` app1项404；`test-input-monitor-red.log` monitor2项404。`test-input-disconnect-red.log`暴露断响应500；修正后engine5绿。`test-input-chat-declaration-red.log`暴露config.inputs遗漏（应400实际200），`test-input-declaration-schema-red.log`暴露minLength遗漏（应400实际200）；对应green日志保留。

`test-input-e2e-first.json`保留首轮实际三域闭环；`test-input-e2e.json`为最终复跑。测试脚本位于`engine-runtime/src/test/scripts/test-input-e2e.mjs`，仅使用target/classes+独立dependency classpath与随机端口，临时PID/命令行/清理结果记录在证据中。monitor真实写入后由测试代理切断TCP，验证502→源编辑→原requestId恢复→单一旧revision数据集；之后调用原EvaluationService实际执行START→END并生成报告。固定expected在运行前写入测试，不从响应回填。错误期望拒绝、无安全用例拒绝和同版本内容冲突均验证。测试仅使用无外部模型/技能的确定性FLOW，不能替代真实供应商/业务评测集/容量验收。

`test-input-migrations.json`：空库`agent_platform_test_input_empty_test`完整迁移app4次到103、engine15次到214、monitor3次到502；升级库`agent_platform_test_input_upgrade_test`先到103/213/501并插入旧dataset，再升级engine214、monitor502，旧dataset内容保留且新来源字段为空。脚本拒绝覆盖已有测试库，不删除任何库，不启动空库后台服务。

首次尝试默认`mvn package`遇Windows既有JAR重命名锁，见`test-input-package.log`；未据此宣称打包成功，后续全部改classes测试进程，未停止或替换现有服务。全回归存在既有JDK动态agent等非失败警告，具体以日志和测试计数为准。

最终模块计数、源码冻结SHA及独立复核输入见同目录`test-input-verification.json`。交付包含root的网关2文件和`test-input-gateway.md`，前端变更不由本后端报告宣称验收。整体任务状态以root台账为准。
