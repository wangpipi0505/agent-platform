# C1（EX-06 未完成部分）证据：Joint All 聚合、两个 Invoke 入口、J4 真实契约

- 执行卡：`文档/优化方案/执行卡片-统一补差-20261028.md` 第 168、172、531 行（EX-06：All 未接通、两个 Invoke 入口不存在、J4 与真实契约不匹配）。
- 范围：只改 `代码/agent-platform/scripts/acceptance/` 与本证据目录；未改 `pom.xml`、未改任何 Java 产品代码、未跑模块全量测试、未执行 `git add/commit/push`。
- 时间：2026-11-02；主机 PowerShell 5.1.26100；脚本全部 UTF-8 BOM + CRLF。

## 1. 改动/新增文件

| 文件 | 行数 | 性质 | 说明 |
| --- | --- | --- | --- |
| `scripts/acceptance/Prepare-Joint.ps1` | 417（原 346） | 修改 | 删除 `JOINT_ALL_STAGE_NOT_DELIVERED`；`-Stage All` 在同一 session 内先做 Ontology 准备（或 `-Resume`/`-ReuseManifestPath` 复用），再调用 `Invoke-JointAcceptance.ps1`（J1/J2→J3→J4），读回 `joint-summary.json` 组装最终 `manifest.json`；缺字段写 `manifest.incomplete.json` 且 `complete=false`。新增参数 `-EvidenceDirectory -AppCode -PinAppCode -ExplicitAppCode -ExpectSha256 -SummaryPath`。 |
| `scripts/acceptance/Invoke-JointAcceptance.ps1` | 485 | 新增 | 入口：`-ConfigFile -Stages（默认 J1,J2,J3,J4）-EvidenceDir -DryRun`，可 `-Prepare` 先跑准备；逐段判定、缺输入 `SKIPPED-BLOCKED` 且不发起调用；写 `joint-summary.json`；退出码 0=PASS、1=FAIL、3=BLOCKED。 |
| `scripts/acceptance/Invoke-S1Acceptance.ps1` | 385 | 新增 | 入口：包裹 S1 出口验收 A（可配置断言）/B（发布门禁与豁免）/C（异步任务态）/D（去桥直连）四段；每段缺输入 `SKIPPED-BLOCKED` 且不调用；写 `s1-summary.json`；退出码同上；`-DryRun` 只打印计划。 |
| `scripts/acceptance/joint-j4.ps1` | 401（原 176） | 重写 | 按 OR `ContractGenerator.formalize` 与 AP `OntologyManifestImports` 真实契约重写：snake_case 清单全字段断言、导入打到 gateway `-PlatformBase`、`{manifests,authScheme:'API_KEY',authRef}` 请求体、解析 `drafts/warnings`；不声称连接测试/选型/发布/运行完成。 |
| `scripts/acceptance/Joint.Common.ps1` | 1237（原 1213） | 修改 | `Assert-JointManifestShape` 允许最终 manifest 的聚合字段（`schemaVersion/complete/verdict/generatedAt/stages/missingFields`）并强类型校验，仍拒绝其它未知字段。 |
| `scripts/acceptance/stub-joint-j4.py` | 297 | 新增 | 契约自检桩：OR manifest/context-pack/release-bindings + AP 导入 + dev-token，真实形状；模式 `normal/server-error/bad-draft/empty-manifest/preview-manifest/bad-endpoint`。 |

## 2. 与真实契约对齐的要点

- OR 清单：`GET /onto/releases/{id}/manifest` 返回 `{"code":"OK","data":{"manifests":[...]}}`；每项为 `ContractGenerator.formalize` 输出：`tool_code`、`name`、`description`、`invocation_kind=SYNC`、`adapter_type=HTTP`、`category=ontology_service`、`endpoint={base}/onto/r/{spaceCode}/releases/{releaseId}/ops/{tool_code}`、`http_method=POST`、`input_schema/output_schema`、`guard_attrs`、`exec_attrs{readonly_flag,side_effect,idempotent,cancellable,timeout_ms,max_rows}`、`risk_level`、`error_semantics`、`return_spec`、`ontology_meta{spaceCode,releaseId,version,contentDigest}`（正式化后无 `preview`）。
- AP 导入：`POST /admin/skills/import-ontology-manifests` 到 **gateway 18080**（不再是 app 服务 18081）；请求体只含 `manifests/authScheme/authRef`，spaceCode/releaseId 在每条 `ontology_meta` 内；`authRef` 必须是服务端已登记的 secret_ref（`env:`/`vault:`），由 `SecretVault.resolve` 校验；响应 `data={drafts,warnings}`，不再解析 `created/failed`。
- 导入目标为 skill 草稿（`status=DRAFT`、`currentVersion=null`、仅 1 个版本），J4 边界明确写明「仍需连接测试、模型选型与发布，才能作为运行证据」。

## 3. 真跑记录（均已真跑，日志原文在证据根）

### 3.1 J4 单独三跑（`Invoke-JointAcceptance.ps1 -Stages J4`）

| 场景 | 命令要点 | 退出码 | 关键输出 |
| --- | --- | --- | --- |
| PASS | `-ConfigFile .../c1-pass.json`，stub 18282 normal，env 注入只读 key 与 bearer | 0 | `J4 verdict=PASS`、`JOINT totals pass=1`、57 项检查、`manifestCount=1 draftCount=1 warningCount=2`；日志 `run-pass.log` |
| FAIL | `-ConfigFile .../c1-fail.json`，stub 18283 server-error | 1 | `[FAIL] J4a-取清单 HTTP 500`、`[FAIL] J4a-信封 code=INTERNAL_ERROR`、`J4 verdict=FAIL`、`JOINT verdict=FAIL`；日志 `run-fail.log` |
| BLOCKED | `-ConfigFile .../c1-blocked.json`（无 spaceId），base 指向未监听端口 | 3 | `[SKIPPED-BLOCKED] J4: 缺外部输入: SpaceId(UUID); 只读key(...); ADMIN Bearer(...)`、`JOINT verdict=BLOCKED`，未发起任何请求；日志 `run-blocked.log` |

### 3.2 `Prepare-Joint.ps1 -Stage All` 三场景（手工 journal 复用，模拟 Ontology 已完成）

命令模板：

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/Prepare-Joint.ps1 `
  -SessionId <session> -Stage All -Resume `
  -OntologyBaseUrl <url> -PlatformBaseUrl <url> `
  -OutputDirectory "G:/207/项目/算法重组工具/代码/agent-platform/.local/acceptance/<session>" `
  -EvidenceDirectory "docs/evidence/2026-11-02-C1-joint-all/<all-*>" `
  -AppCode ... -PinAppCode ... -ExplicitAppCode ... `
  -SummaryPath C:/Users/Administrator/AppData/Local/Temp/<session>-summary.json
```

| 场景 | 退出码 | 关键输出 | manifest |
| --- | --- | --- | --- |
| PASS（18282） | 0 | `JOINT totals pass=4`、`JOINT verdict=PASS`、`[ALL] verdict=PASS complete=True` | `all-pass-manifest.json`：J1–J4 全 PASS、`missingFields=[]` |
| FAIL（18283，J4 清单 500） | 1 | `JOINT totals pass=3 fail=1`、`JOINT verdict=FAIL`、`[ALL] verdict=FAIL complete=False` | `all-fail-manifest.json`：J4=FAIL（reason 含 `[FAIL] J4a-取清单 HTTP 500`），另写 `all-fail-manifest.incomplete.json` |
| BLOCKED（不传 appCode） | 2（Prepare 内部约定 2=BLOCKED） | `[SKIPPED-BLOCKED] J1/J2: 缺外部输入: AppCode`、`JOINT verdict=BLOCKED`、`[BLOCKED] MANIFEST_INCOMPLETE missing=appCode,pinAppCode,explicitAppCode`、`[ALL] verdict=BLOCKED complete=False` | `all-blocked-manifest.json`：J1/J2 SKIPPED-BLOCKED、J3/J4 PASS、`complete=false` |

注：`Prepare-Joint.ps1` 的阶段退出码沿用历史约定（0=ONTOLOGY/A11 完成、1=FAIL、2=NOT_DELIVERED/BLOCKED）；对外入口 `Invoke-JointAcceptance.ps1`/`Invoke-S1Acceptance.ps1` 才是 0/1/3。

### 3.3 DryRun 安全冒烟

- `run-dryrun-joint-planned.log`：exit 0，只打印 `[PLAN] & joint-j4.ps1 ...`（ReadKey/Bearer 显示 `<redacted>`），`[PLANNED] J4`、`verdict=DRYRUN`，**未创建**证据目录、未发请求。
- `run-dryrun-joint.log`：exit 3，缺 key+bearer → `SKIPPED-BLOCKED`，未发请求。
- `run-dryrun-s1.log`：exit 3，A/B/C/D 四条 `SKIPPED-BLOCKED` 并逐条列出缺失项，未创建目录、未发请求。

## 4. 证据文件清单

- 三份 J4 日志原文：`run-pass.log`、`run-fail.log`、`run-blocked.log`；证据 JSON：`pass/j4-*.json`、`fail/j4-*.json`。
- 三份 All 日志原文：`run-all-pass.log`、`run-all-fail.log`、`run-all-blocked.log`；逐段证据：`all-pass/`、`all-fail/`、`all-blocked/`（`joint-summary.json`、`j1-j2-*.json`、`j3-*.json`、`j4-*.json`、`all-invoke.log`）。
- 最终 manifest 副本：`all-pass-manifest.json`、`all-fail-manifest.json`、`all-blocked-manifest.json`（+ 两份 `.incomplete.json`）。
- DryRun 日志：`run-dryrun-joint-planned.log`、`run-dryrun-joint.log`、`run-dryrun-s1.log`。

## 5. 偏差与未覆盖

1. **J1–J3 未对真实 OR/AP 服务验证**：本卡只保证脚本层可用。`joint-j1-j2.ps1`（release-bindings 绑定/幂等）、`joint-j3.ps1`（context-pack 四段数组与 sha256）由同一桩的真实形状路由驱动，真实 `18182/18080` 服务仍需按手册执行。
2. **All 的 Ontology 段未真跑**：真实准备需要本体登录凭据（`-OntologyCredential`）与真实 OR；本次用 `-Resume` + 手工 journal（`partial.json`/`credentials.env`，结构同 Prepare 写入的形状）模拟 `ONTOLOGY_READY`，验证的是 All 聚合逻辑，不是 Ontology 准备逻辑。
3. **J4 导入对象是桩**：`drafts` 来自 `stub-joint-j4.py`，只验证请求体/响应解析与断言，不代表 AP 真实落库；未有连接测试、模型选型、发布、运行证据（J4 边界已写入证据 JSON）。
4. **S1 的 A/B/C/D 只做 DryRun 与 `SKIPPED-BLOCKED` 冒烟**：A 需要真实评测应用与 `psql`/`monitor.evaluation_report`；B 需要真实 `-BCommit` 授权与门禁数据；C 需要真实异步 run 与数据库；D 需要真实 OR 消费凭据与 manifest。真实执行按 `文档/优化方案/S1-验收运行手册-20261027.md` 逐段补输入后进行。
5. **`joint-j3.ps1` 既有 180 处 bare LF**：属既有文件，本卡未改该文件，未做行尾统一（其余 7 个 ps1 均 `bom=True bareLF=0 parseErrors=0`）。
6. **`Prepare-Joint.ps1` 阶段退出码 2 = BLOCKED**：与本卡「入口脚本 0/1/3」不冲突，但调用方需按该脚本历史约定解释。
7. `stub-joint-j4.py` 的绑定状态在进程内按 `appCode` 累积，重跑 All 场景需使用不同 `appCode` 或重启桩。

## 6. 复现步骤

```powershell
cd G:\207\项目\算法重组工具\代码\agent-platform
# 桩（两个终端）
python scripts\acceptance\stub-joint-j4.py --port 18282 --mode normal `
  --read-key "ontosa_<43 字符>" --bearer stub-admin-token `
  --auth-ref env:AGENT_SECRET_JOINT_TEST_CONSUMER
python scripts\acceptance\stub-joint-j4.py --port 18283 --mode server-error `
  --read-key "ontosa_<43 字符>" --bearer stub-admin-token `
  --auth-ref env:AGENT_SECRET_JOINT_TEST_CONSUMER

# J4 三跑
$env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = "ontosa_<43 字符>"
$env:AGENT_ACCEPTANCE_BEARER = "stub-admin-token"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\acceptance\Invoke-JointAcceptance.ps1 `
  -ConfigFile C:\path\c1-pass.json -Stages J4 -EvidenceDir docs\evidence\2026-11-02-C1-joint-all\pass
```

All 场景还需 `-Resume` + `<output>/<session>/partial.json` 与 `credentials.env`（本卡测试用的 seed 脚本为临时文件，未入库；journal 字段见 `Joint.Common.ps1` 的 `Get-JointPreparationPlan`/`Assert-JointManifestShape`）。
