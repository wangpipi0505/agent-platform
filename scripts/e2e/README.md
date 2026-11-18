# M5-3 场景转 E2E/剧本（API 级，可复现）

台账 §7.6 M5-3：场景转 E2E/剧本，交付=E2E+多角色剧本，验收=可复现。本目录是一组 **API 级**
E2E 剧本：八场景全部经**真实网关 HTTP**（SecurityFilterChain 验签 + 路由目录 + 集成策略）驱动，
多角色（ADMIN/BUILDER/OPERATOR/USER/SERVICE）以本地铸造的 HS256 用户票据经网关验证后走查角色门。
浏览器两角色走查仍归 G5-0（A13 注记），本卡不越界。

## 一、前置条件

| 项 | 值 |
| --- | --- |
| JDK | Java 21：`C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot` |
| Maven | 3.9.x（choco） |
| PostgreSQL | 127.0.0.1:25432（本机实例；psql 取自 `agent-platform/.tools/postgresql-16.15`） |
| 专用库 | **`m53_e2e`**（只建此库；不碰 dev 库/共享 test 库）。建库：`CREATE DATABASE m53_e2e OWNER agent_v12;` |
| 迁移+种子 | 各服务启动时 Flyway 自举（app/skill/engine/model 全链迁移 + **V901 演示种子**：app 38/skill 32/engine 2 行，tenant=`demo`） |
| 密钥 | 只从仓库根 `.env` 读取（AGENT_JWT_SECRET/AGENT_MASTER_KEY/AGENT_DB_*）；不入命令行参数、日志、证据、Git |
| Redis/MinIO | **本机未起**。engine/model 健康探针 503/DOWN 属预期（观察流单副本本地降级、Redis PubSub 缺席不阻塞业务链），五服务监听即算起栈成功 |
| node | ≥18（fetch 内置） |

## 二、起栈与入口

```bash
# 1. 构建五服务（首次或源码变更后）
mvn -B -ntp -pl platform-gateway,app-management,engine-runtime,skill-registry,model-gateway -am -DskipTests package

# 2. 起栈（184xx 段；幂等——已监听端口跳过）
bash scripts/e2e/start-stack.sh

# 3. 单命令 E2E
node scripts/e2e/run-all.mjs        # 非零退出码=失败；控制台逐断言 PASS/FAIL + 场景汇总；JSON 报告落 docs/evidence/2026-11-18-M5-3/m5-3-e2e-report.json

# 4. 停栈
bash scripts/e2e/stop-stack.sh
```

### 端口矩阵

| 端口 | 服务 | 说明 |
| --- | --- | --- |
| 18480 | platform-gateway | 业务入口（场景主路径）；日志 `.local/m53-logs/gateway.out.log` |
| 18481 | app-management | 应用/发布/集成凭据档案 |
| 18482 | engine-runtime | 任务/会话/记忆；**PropertiesLauncher + shim 外挂起（见缺陷登记①）** |
| 18483 | skill-registry | 技能/插件/MCP |
| 18484 | model-gateway | 模型供应商/条目 |
| 18485 | model-stub.mjs | 本地 OpenAI 兼容模型桩（run-all 自起自停；承接供应商连通测试与 engine 真实出话链） |

## 三、场景清单与期望结果

| # | 场景 | 覆盖 | 关键期望 |
| --- | --- | --- | --- |
| S1 | 直接任务全链 | 受理→幂等→事件游标→详情→取消/恢复 | 同号同参重放返回原任务；同号异参 409 REQUEST_REPLAY；events `afterSeq` 排他（`nextAfterSeq`=最后扫描 seq 不 +1）；重复取消幂等不推进 stateVersion；终态无恢复；WAITING_EXTERNAL 恢复→RUNNING+RESUME 资格；恢复投影 USER 三字段 vs OPERATOR 特权字段 |
| S2 | 计划任务全链 | 修订 CAS→确认→execute→事件 | 过期基线 409 PLAN_REVISION_CONFLICT；digest 不符确认/执行 409 PLAN_DIGEST_CHANGED；确认重放不追加 confirm_seq；修订读 confirmation.state=CONFIRMED_VALID+canExecute；execute 返回 executionTaskId+pinnedDigest，子任务 purpose=EXECUTE/role=PLANNED_EXEC/parent 回指，计划任务进 RUNNING |
| S3 | 记忆域 | 私有 CRUD/CAS/可见性/删除 | 保存幂等（确定性 memoryId）+异参 409；USER 写共享 403 SCOPE_WRITE_DENIED（策略收窄下 ADMIN 同样 403，见登记⑥）；列表不含正文；他人 PRIVATE 列表/读取不可见；过期 CAS 409 MEMORY_VERSION_CONFLICT；非所有人删 403；删除/撤销→REVOKED 退出检索+重复幂等 |
| S4 | 插件发现闭环（R-6） | 登记→202 QUEUED→轮询→enable→grant/revoke | discover 受理 202 {discoveryId,QUEUED,batchNo=1}；轮询至 SUCCEEDED+changes.added（差异确认门材料）；执行器发布版本 enable 过门 200；grant 子集 GRANTED→重复 409 PLUGIN_GRANT_EXISTS→revoke REVOKED；USER 登记来源 403 |
| S5 | MCP 连接档案 | 登记→test→discover→目录→审核 | 同码 409 MCP_CONNECTION_CONFLICT；PUT 变更 revision+1（修订绑定）；test 失败带具体类别 UNREACHABLE（桩不静默成功，见登记⑦）；discover 失败整批作废+catalogPreserved=true；无已发布目录审核 409 MCP_CONNECTION_STATE |
| S6 | 确认中心 | 四类计数+total+confirmableBy=me（B4-1） | total=四类之和；USER 403 CONFIRMATION_CENTER_FORBIDDEN；BUILDER 可读；PLAN_READY+VALID 无凭据头修订→计入 USER `confirmableBy=me` 投影与 ADMIN planConfirmation 计数；确认后退出投影且计数-1；`confirmableBy=you` 400 TASK_CONFIRMABLE_INVALID |
| S7 | 外部集成（SERVICE 票据面） | 出票→白名单四端点→白名单外→过期 | 无 Origin（服务端出票）受理；未登记 Origin 403（CORS 层）；错密钥 401 INVALID_CREDENTIAL；票据走白名单四端点（create/detail/events/result）全 200；cancel/resume 403 APP_BINDING_DENIED+审计行（日志含 AUDIT INTEGRATION_PATH_FORBIDDEN、不含密钥本体）；mode=PLAN 403 SERVICE_AGENT_ONLY；伪造 policy_digest 旧票 401 INTEGRATION_TICKET_EXPIRED；凭据撤销后旧票即刻 401（bound 前置、实时读权威状态）；直连 ApiKey 面 200、自报 X-User-Tenant 403 INTEGRATION_CLIENT_IDENTITY_REJECTED |
| S8 | 角色门矩阵 | 五身份×敏感端点逐项断言 | resource-manifest ADMIN/BUILDER/OPERATOR 200、USER/SERVICE 403 RESOURCE_MANIFEST_FORBIDDEN；会话事件诊断视图（surfaceOnly=false）仅 OPERATOR/ADMIN（自有会话逐身份验证；USER/BUILDER 403 SESSION_DIAGNOSTIC_VIEW_FORBIDDEN）；跨主体会话/任务 404（归属先于角色，不泄露存在性）；插件/MCP/模型供应商/集成凭据 ADMIN 门；任务列表按受理人过滤 |

**多角色票据**：`lib/harness.mjs` 按 `ServiceTokens` 同构形状本地铸造（与仓库 tests/smoke-statistics.mjs
先例同一机制），claims=iss/aud/token_use/sub/tenant_id/roles/attributes/iat/exp/jti，经网关真实验签。
SERVICE 票据面另有两面：角色矩阵中的 minted SERVICE 身份（第 8 场景）与真实 `/embed/ticket`
出票的 integration 短票（第 7 场景）。

## 四、登记项（诚实边界与平台发现）

**平台缺陷（修复归口服务卡，本卡不改服务源码）**
1. **engine 全上下文启动阻塞**：`Memories`（@Service）自 734fe1a7（A3-4）起两个 public 构造器均无
   @Autowired → Spring 无法选定构造器，任何部署形态启动即失败（No default constructor found）。
   本卡以 `scripts/e2e/shim/`（BeanFactoryPostProcessor 挂确定性实例供给器，PropertiesLauncher
   `-Dloader.path` 外挂）补足装配，**不改服务源码**；上游修复合入后 shim 可整体下线。
2. **网关路由目录未登记任务计划端点**：`Routes.service` 不含 `/v1/tasks/{id}/plans*`、
   `/v1/tasks/{id}/execute`（命中 `/v1/tasks/[^/]+(?:/.*)?` 抛"此任务入口不存在"）——计划子链
   （S2/S6 修订/确认/执行）经引擎端口直连（同一票据、同一业务语义）。
3. **网关代理不支持 PATCH**：`HttpURLConnection.setRequestMethod("PATCH")` ProtocolException，
   经网关 PATCH 全部 500——S3 的 PATCH CAS 断言经引擎端口直连。
4. **网关代理丢弃 DELETE 请求体**：仅 POST/PUT/PATCH 转发 body——S3 删除请求经引擎端口直连。

**环境边界（随部署/G5-0 承接的诚实降级）**
5. **集成凭据签发/撤销端点绑定 IAM 权威链**（`AuthorityContext.requireFor`→app-version 资源绑定）：
   dev-hmac 本地栈无权威复核入口（403 AUTHORITY_CONTEXT_MISSING，S7 已如实断言）——S7 凭据行与
   撤销经权威库数据面置备（`lib/harness.mjs` vaultPut+pgExec；与 platform-gateway
   IntegrationHostTicketTest 夹具同口径）；出票/白名单/绑定/过期语义全部走真实网关 HTTP。
6. **记忆共享/审批分层收窄**：V901 种子应用未配 memoryPolicy→策略收窄为仅 PRIVATE（契约口径
   "未配置时 read/writeScopes 均仅含 PRIVATE"的真实行为）：SHARED 读写一律 403（含 ADMIN）、
   DRAFT 记忆不可经 API 产生（草稿属模型抽取路径）——共享/审批分层语义由服务层测试覆盖。
7. **MCP 传输为 StubMcpTransport**（本构建唯一实现，不发起网络请求）：S5 断言档案/修订/批次
   语义与失败类别（UNREACHABLE 不静默成功）；真实 Streamable HTTP/SSE 线缆与乱序批次 409
   （发布门并发拦截）随接入卡与单元层覆盖。
8. **任务执行派发停受理态**：AGILE 任务 ACCEPTED→QUEUED 派发属 T2-5 步骤映射接续卡——
   S2 执行任务无 task_step 行（步骤锚点 404 TASK_STEP_NOT_FOUND 为真实契约）、execute 直插
   任务行不写 TASK_ACCEPTED；模型出话链（provider 连通测试/单 token 出话）经本地桩为真，
   真实业务模型出话随部署环境（demo-provider 即本地桩，如实命名）。
9. **PLUGIN_DIFF_NOT_SHOWN / 来源停用拦截不可经 API 触达**：未展示差异版本需数据面直写
   （recordDiscoveredVersion 无 HTTP 端点）、来源启停端点未暴露 HTTP——两分支由
   PluginDiscoveryExecutorTest 单元覆盖（含"认领后停用"分支），S4 断言其正向路径。
10. **monitor 未在栈内**：/admin/statistics 等监控面场景不在本卡最小集；四类计数中的
    publishReview/highRisk 只做结构与联动断言，不做业务计数预期（种子环境两类恒 0）。
11. **模型真实出话未覆盖**：规划候选合并（PLAN_READY 的自然路径属规划子代理出话）在 S2/S6 以
    数据面置位（与 SubagentPlanning.merge 终态同口径）替代；确认/执行/计数联动自 PLAN_READY
    起全部真实走通。

## 五、可复现性

- 全部场景以 `runId` 生成新实体（requestId/planId/来源码/连接码/凭据 biz 各异），重复运行不依赖
  也不破坏历史状态；JSON 报告含逐断言记录（scene/label/expected/actual）。
- 2026-11-18 已验证：停栈→起栈→全量复跑，两轮均 8/8 场景 PASS、208/208 断言
  （证据：`docs/evidence/2026-11-18-M5-3/m5-3-e2e-run.log` 与 `m5-3-e2e-report.json`）。

## 六、清理步骤

```bash
bash scripts/e2e/stop-stack.sh                       # 停五服务
node scripts/e2e/fixtures/model-stub.mjs --help      # 模型桩由 run-all 自起自停，无残留
# 专用库整体重置（可选，彻底回到空库）：
#   drop database m53_e2e;  create database m53_e2e owner agent_v12;  → 下次起栈 Flyway 重新自举
# 证据与报告保留在 docs/evidence/2026-11-18-M5-3/；.local/（密文密钥/日志/shim 产物）不入 Git
```

## 七、文件地图

```
scripts/e2e/
├── run-all.mjs            # 单命令入口（起模型桩→幂等注册模型路由→8 场景→汇总→JSON 报告→非零退出）
├── start-stack.sh         # 184xx 起栈（含 shim 编译；幂等）
├── stop-stack.sh          # 按端口停本栈 java 进程
├── lib/harness.mjs        # 本地出票/HTTP 客户端/权威库辅助/断言/数据面置备
├── fixtures/model-stub.mjs# OpenAI 兼容本地模型桩（/v1/models + /v1/chat/completions）
├── scenarios/s1..s8.mjs   # 八场景剧本（每场景 export meta+run）
└── shim/src/cn/agentplatform/engine/m53shim/MemoriesInstanceSupplierShim.java
```
