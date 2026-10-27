# IN-08：12 技能上架联调记录（8080 OpenAPI → skill-registry）

> 日期：2026-10-27 ｜ 环境：agent-platform 六服务（18080-18084 运行中）+ space-ground-visible 8080
> 结论：**导入→创建→连接测试→engine 真实执行全链路打通**；2 个计算技能端到端 SUCCEEDED；
> 其余失败根因全部定位并分类（见 §4），无管道级缺陷遗留。

## 1. 过程与修复

### 1.1 运行服务陈旧（环境）
preview 连续报自相矛盾错误（小文档报 SIZE、大文档报 INVALID）——运行中的 18083 进程加载的是
**旧版构建**（jar 已被后续构建覆盖，进程仍持旧类）。处置：停旧进程→`mvn package`→按
`scripts/start-backend.ps1 -Service skill-registry` 标准重启。教训：**联调前先核对运行进程的
构建时效**（IN-01 检查表新增项）。

### 1.2 multipart 导入 $ref 缺口（软件修复，本仓库）
真实合同 `POST /api/v1/ballistic/files` 的 requestBody 为 `{$ref: MultipartUpload}`，
导入代码未解引用即取 properties → "multipart必须至少有一个部分"。修复：`convert` 中先
`ref(root, schema, ...)` 解引用；可选部分（非 required，真实合同 `purpose` 即是）不进契约并
记录 `skippedMultipartParts` 供契约评审对照（不变量"已声明部分必填"不变）。
单测 10+13 全绿（OpenApiImportsTest + MultipartFormMappingTest 回归）。

### 1.3 文档规范化（导入前置数据处理，脚本见证据包）
582KB 全文档超导入器 40,000 token 预检上限（有意限制，不调）；抽取 12 核心操作 +
components 完整引用闭包后，对 Schema 做规范化：`nullable:true`→删键+移出 required、
删 `readOnly/writeOnly/pattern`、`anyOf`→首分支、`allOf`→深合并（properties/required 合并）。
规范化后 12/12 全部 supported。

### 1.4 12 技能创建（DRAFT v1）
业务语义 overrides：查询/计算类 readonly；异步受理与文件上传 sideEffect=true；
全部 guard roles=[ADMIN,EDITOR,APPROVER,SERVICE]；errorSemantics 统一 400/403/429/500 映射。
`POST /admin/skills/import-openapi` 一次创建 12 DRAFT。

## 2. 连接测试结果（engine 真实执行链）

| 技能 | 终态 | 说明 |
| --- | --- | --- |
| trajectory-simulate | **SUCCEEDED** | 真实弹道模拟，结果含 csvPath（SYSTEM_SIMULATED_LAMBERT） |
| trajectory-fit-package102 | **SUCCEEDED** | 真实 102 拟合（A02 真实样例输入），产出 BALLISTIC_MIDCOURSE_TWO_BODY CSV |
| ballistic-file-preview | 链路通，业务 404 | fileId=1 不存在——**真实触达 8080 并返回业务响应**，管道证明 |
| space-target-query 等 4 空间技能 | FAILED→已定位 | 见 §4 |
| 其余 | 待真实输入复测 | 输入构造问题已解决，同类根因 |

证据：`skill.execution_fact` 每次调用的 status/error.reason/result 摘要（PG 可查）；
run 级 events（RUN_STARTED→NODE_FAILED/RUN_FINISHED）。

## 3. 过程中发现的可观测性佐证（AP-S07 输入）

- run 顶层 `error: {}` 为空、节点 error 只有语义码（OUTPUT_INVALID），**具体原因只能在
  execution_fact.result.error.reason 里挖 SQL**——AP-S07 的"运行错误与工具调用可观测"必要性
  的直接证据。

## 4. 剩余失败根因分类（无管道缺陷）

| 类 | 技能 | 根因 | 归属 |
| --- | --- | --- | --- |
| A 输出 schema 与响应 null 不兼容 | space-target-query 等 | 8080 响应含显式 null 字段（如 minWeight:null），导入规范化删除 nullable 后 schema 对 null 非法——平台校验不支持可空（有意语义）。解法：技能收敛时删除可空字段的属性声明（未声明属性默认放行），或平台评估 nullable 支持 | 技能收敛/平台评估 |
| B 演示数据质量 | space-visibility-* / options 类 | TLE 校验和错误、缺 SPACE 载荷配置（IN-06 已记录） | **外部 owner** |
| C 样例时效 | space-downlink-plan | A02 历史样例 400（服务端业务校验拒绝，需核对当前参数口径） | 联调输入适配 |

## 5. 复现与脚本

规范化+导入+连接测试脚本与中间产物（preview_norm3.json / connection_results.json /
real_input_results.json）保存在联调临时目录；关键 API 序列：
`POST /auth/dev-token` → `POST /admin/skills/import-openapi/preview` → `POST /admin/skills/import-openapi`
→ `POST /admin/skills/{code}/drafts/1/connection-test`（body: {inputs, requestId}）→ `GET /v1/runs/{runId}`。

## 6. 未尽事项

1. 12/12 全绿的收敛（A 类可空字段 schema 修剪脚本 + B 类等 owner 修数据 + C 类样例更新）；
2. 发布尝试（四道门的选型验收需模型就绪——IN-02）；
3. `GET /v1/runs/{id}/events` 独立端点 404（事件内嵌在 run 详情里），不影响本联调。
