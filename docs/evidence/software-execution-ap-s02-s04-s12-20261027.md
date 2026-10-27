# AP-S02 / AP-S04 / AP-S08 / AP-S12 执行与验证记录

- 执行时间：2026-10-27
- 环境：dev，`18080` 平台网关、`18084` 模型网关、`18081` 应用管理、`18082` 引擎、`18083` 技能注册、`18085` 监控
- 模型：DeepSeek `deepseek-flash`（entry 13）
- 说明：本轮只改这四个软件项；工作区存在其他并行改动，未提交、未回滚任何协作者文件。

## AP-S02 模型深度健康

实现：

- `ModelHealthService`：缓存、单飞、刷新窗口、强制探测；只聚合 `ENABLED` 且非 `FIXTURE` 的条目。
- `ModelCalls.entryHealth`：`config / endpoint / auth / model / tools / tokenizer / latency / circuit / quota` 九项检查。
- `ModelQuota.snapshot`：只读 Redis 配额桶，不预占、不回写。
- `ModelHealthController`：`GET /admin/model-health`、`GET /admin/model-health/{id}`、`POST /admin/model-health/{id}/probe`。
- `SystemStatusController`：模型项改为调用深度健康，保留网关可达性检查。

证据：

```text
POST /admin/model-health/13/probe -> 200
status=HEALTHY, stage=OK, latency≈573-655ms
9 checks: config/endpoint/auth/model/tools/tokenizer/latency/circuit/quota 全部 PASS
GET /admin/system/status -> model.status=HEALTHY, detail=深度检查通过（健康 1/1）
```

定向测试：

```text
ModelCallsPostgresTest#deepHealth* + forcedProbeBypassesCachedSnapshot
Tests run: 4, Failures: 0, Errors: 0
```

## AP-S04 文件输入

实现：

- `ArtifactStore.upload`：输入文件写入 MinIO，生成上传 artifact，不绑定历史 run。
- `POST /admin/artifacts`：multipart，最大 64MiB；返回 artifactId/runId/downloadUrl。
- `platform-gateway` 对 `/admin/artifacts` 关闭自身 multipart 解析，避免 part 在网关被消费。
- `SchemaInputs.vue` 对二进制文件渲染“选择文件”控件；`AppRunPanel.vue`、`ResourceDetail.vue` 上传后填入 `artifactId`。
- 技能侧 `MultipartFormMapping` 原逻辑即可把 artifact 下载为临时文件，无需新协议。

证据：

```text
POST /admin/artifacts（22 字节）-> 200
GET  /admin/artifacts/{id} -> 200，内容一致
直接 POST skill-registry:8081/admin/artifacts -> 200（排除网关问题）
ArtifactPersistenceTest#uploadedArtifactPersistsAndDownloads -> PASS
```

## AP-S12 服务接入卡片

实现：

- `ServiceProbe`：只发 HEAD 到 endpoint 根路径，不跟随重定向、不读响应体、拒绝 link-local/any-local/multicast。
- `POST /admin/skills/{code}/versions/{version}/service-probe`：返回 HTTP 状态、耗时、REACHABLE/UNREACHABLE、检查时间。
- `ResourceDetail.vue`：服务地址、调用方式、鉴权/超时、最近检查、OpenAPI 文档链接、探测按钮、服务根链接。
- 重启入口明确标注“待 AP-S05 配置”，不伪造可点击操作。

证据：

```text
POST /admin/skills/space_visibility/versions/2/service-probe -> 200
probeStatus=REACHABLE, httpStatus=200, latency=228ms
ServiceProbeTest: Tests run 5, Failures 0
```

## 前端与构建

```text
vue-tsc --noEmit -> 0 errors
npm test -> 32 files / 238 tests passed
npm run build -> success
```

## 全量测试边界

`mvn -pl skill-registry test` 运行结果为 `Tests run: 427, Failures: 7, Errors: 112`，失败集中在既有测试环境：

- `McpManagementTest`：JWT 密钥不足 32 字符；
- `ReconciliationProofTest` / `McpExecutionPostgresTest` 等：平台测试数据未准备（`平台数据不可用，请稍后重试`）。

这与本轮 AP-S12 新增探测代码无关；定向 `ServiceProbeTest` 通过。该边界不能写成“全量测试通过”。

## AP-S08 评测基线/发布门禁

实现：

- `EvaluationRules.summary`：统一计算 total/passed/failed/passRate/threshold/safety/regressionCount/accepted，接受状态由同一纯函数给出。
- `EvaluationService.execute`：每条结果写入 `baselinePassed` / `regression`；依赖异常也保留基线判定。
- `EvaluationService.report`：回包增加 `summary`，不改变冻结结果与既有门禁语义。
- `web/admin/src/evaluation-observer.ts` + `EvaluationReport.vue`：展示通过率/阈值、基线报告、新增回归与逐案“基线通过”。

真实证据（dev 18085，同一数据集 `ap-s08-ascii2-20261027`）：

```text
er_483e09e6-fb2e-43b7-8b33-a3cfd2348af8  ACCEPTED  passRate=1.0  baselineReportId=""
er_fc2e025c-61f3-4cc8-b7d7-db1582fd886f  ACCEPTED  passRate=1.0  baselineReportId=er_483e...
  case-1: baselinePassed=true, regression=false
POST /admin/apps/smoke_named_inputs_3db244a637/publish/precheck -> PASS
  evalReportId=er_fc2e025c-61f3-4cc8-b7d7-db1582fd886f, passed=true
```

同数据集换用例被服务端以 409 拒绝，说明冻结数据集不可变；真实“回归拒绝”未单独构造，回归判定由单测覆盖。

页面证据：

- `.local/tmp/ap-s02-s07/evaluations-list-real.png`：评测中心显示 `1/1`、状态“已受理”。
- `.local/tmp/ap-s02-s07/evaluation-detail-real.png`：报告详情显示基线报告、通过率 100%、阈值 90%、新增回归 0、逐案“基线通过”。
- `.local/tmp/ap-s02-s07/ap-s08-publish-real.png`：应用发布面板预检六组 PASS，评测门禁绑定候选摘要。

测试与构建：

```text
mvn -pl monitor -am test -Dtest=EvaluationRulesTest -> 7 tests, 0 failures
npm test -> 32 files / 239 tests passed
npm run build -> success
```

## AP-S09 管理台合并与 smoke

按用户要求以本工作区版本合并 AP-S09 前端重构，未回滚协作者文件。实际路由 smoke（15173，7 条）：

```text
/apps /runs /system /model/providers /evaluations
/skills/space_visibility
/apps/smoke_named_inputs_3db244a637/orchestration/publish
-> 每条页面均有内容，0 pageerror；发布页预检后出现数据集/报告/发布控件
```

截图：`.local/tmp/ap-s02-s07/ap-s09-system-smoke.png`、`ap-s09-publish-after.png`、`ap-s09-shell-smoke.png`。

## 提交与复跑

提交（`feat/v1.2-implementation`）：

```text
4cf9b00 AP-S02 模型深度健康与系统状态
b7a811d AP-S04 输入文件上传与网关字节流转发
81995e1 AP-S07 运行诊断、工具调用与时间线
8710ef9 AP-S08 评测基线、回归与报告摘要
40feb65 AP-S12 服务接入探测与链接卡片
9dd1731 AP-S09 管理台路由化页面合并
bd28f85 模型能力别名归一与运行分页
096c7b6 执行证据
6226cd3 一键启停与 PowerShell 5.1 兼容
```

提交后定向复跑：

```text
ModelCallsPostgresTest#deepHealth*+forcedProbeBypassesCachedSnapshot -> 4/4
ModelStoreCapabilitiesTest -> 3/3
RunDiagnosticsTest -> 7/7
EvaluationRulesTest -> 7/7
ServiceProbeTest -> 5/5
npm test -> 32 files / 239 tests passed
npm run build -> success
```

## 未完成

- AP-S05 通用服务生命周期、AP-S10 运维可观测尚未执行；AP-S09 只完成本轮页面合并与 smoke，不替代完整可用性验收。
- 8080 当前仍是 demo 空间服务，IN-04/IN-06 的真实库证据未复跑。
- 工作区仍有其他并行产生的 IAM 证据与 `output/` 截图，未纳入本次提交。
