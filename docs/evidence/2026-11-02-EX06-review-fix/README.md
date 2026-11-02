# EX-06 联合验收脚本复审整改证据（2026-11-02）

## 0. 范围与约束

- 只改 `scripts/acceptance/`（以及本证据目录）：`Joint.Common.ps1`、`Test-JointManifestShape.ps1`（新增）、`joint-j4.ps1`、`joint-j7.ps1`、`joint-j8.ps1`、`joint-j9.ps1`、`Invoke-JointAcceptance.ps1`、`Invoke-S1Acceptance.ps1`、`stub-joint-j4.py`、`stub-joint-j5-j9.py`。
- 未改 Java 产品代码、`pom`、`contracts`；未 `git add`/`commit`/`push`。
- 所有 `.ps1` 用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".tools/check-ps.ps1" <文件>` 校验，输出 `OK <文件>`（0 错误）。
- 桩跑只证明脚本契约/装配，按联合手册边界“桩数据不得作为验收通过证据”；真实服务腿见 §11。

复现环境：

```
cd G:/207/项目/算法重组工具/代码/agent-platform
# 桩（J5–J9）
python scripts/acceptance/stub-joint-j5-j9.py --port 18283 --mode normal
# 入口级三场景（脚本内自己起停桩）
bash .local/joint-stub/run-invoke.sh allpass|server500|blocked
# 单段
bash .local/joint-stub/run-stages.sh j7|j9
# S1 B
bash .local/joint-stub/run-s1b.sh
```

本次原始日志均保存在 `.local/ex06-final/`（`manifest-shape.log`、`j7-stage.log`、`j9-stage.log`、`allpass.log`、`server500.log`、`blocked.log`、`s1b-pass.log`、`s1b-blocked.log`、`j9-schema-negative.log`）。

说明：控制台日志由 Windows PowerShell 以本机代码页捕获，中文检查名在日志文件里可能显示为乱码；本文引用的断言值（HTTP/EXIT/`code=`/`changed=`/`requests=` 等 ASCII）与 UTF-8 证据 JSON（`.local/joint-stub-evidence/`、`.local/s1-evidence-*`）为准。

## 1. 结论摘要

| # | 复审问题 | 状态 | 关键证明 |
|---|---|---|---|
| P1-1 | All 的 manifest 白名单缺 `missingStages`，全 PASS 仍抛 `JOINT_MANIFEST_FIELDS_INVALID` | 已修 | `RESULT=PASS cases=11 failures=0`，EXIT=0 |
| P1-2 | J7 用空的 `$ProbeAppCode` 发绑定请求 | 已修 | J7 单段 `EXIT=0`，首绑定 `HTTP 200 changed=True`，`probeAppCode=joint_j7_probe_stubj7` |
| P1-3 | J9 SCHEMA 的 `expected` 是字符串 | 已修 | J9 单段 `EXIT=0`；负控 string→400 `INVALID_ASSERTION`、object→200 |
| P1-4 | S1 C 段把 `r_<32hex>` 当 bigint 主键、把 `error_semantic` 当 JSON | 已修（静态） | check-ps OK；无 PG/psql，运行态未覆盖（§5） |
| P1-5 | S1 B 预检流程缺失（无 body、无 409/WAIVED/撤销/commit 链） | 已修 | S1 B 桩跑 `EXIT=0`（10 请求，409→WAIVED→REVOKED→409→commit 幂等）；缺输入 `EXIT=3` 且 `requests` 不前不后变化=0 |
| P1-6 | J8 认证路径只证“A 凭据/伪造头”，缺“合法身份跨空间” | 已补 | J8 单段 `EXIT=0`，新增 3 检查 PASS；无第二身份输入时如实 `SKIPPED-BLOCKED` |
| P1-7 | J4 检查失败后仍继续导入 | 已修 | normal `EXIT=0 importRequests=1`；preview `EXIT=1 importRequests=0` |
| — | allpass / server500 / blocked 三场景 | 通过 | `EXIT=0` / `EXIT=1` / `EXIT=3`，blocked `requests: 0 → 0` |

## 2. P1-1 `missingStages` 白名单与形状校验

**复审问题**：`Joint.Common.ps1` 的 `$allowed` 不含 `missingStages`；`Prepare-Joint.ps1 -Stage All` 在 `complete=true` 时会写 `missingStages=[]` 并调用 `Assert-JointManifestShape`，于是全 PASS 也必然抛 `JOINT_MANIFEST_FIELDS_INVALID`。

**改法**（`scripts/acceptance/Joint.Common.ps1`）：

- `:53-55`：`$aggregate` 增加 `'missingStages'`，注释说明它是 `Prepare-Joint.ps1:262` 聚合必写字段。
- `:125-144`：新增形状校验——必须是数组；元素必须是字符串且匹配 `^J[1-9]$`；不得重复；`complete=true` 时必须为空；不得与 `stages` 已交付阶段重名；`complete=true` 时该字段不得缺失；均抛 `JOINT_MANIFEST_FIELDS_INVALID`。
- `:5-14`：修 `Get-JointValue` 的空数组展开缺陷——`return $Object[$Name]` 遇 `@()` 会被 PowerShell 展开成“无输出”而变成 `$null`，导致合法的 `missingStages=[]` 被误判缺失；改为先取值、数组用一元逗号返回（`return ,$value`）。
- 新增 `scripts/acceptance/Test-JointManifestShape.ps1`：直接调用断言函数，11 个用例（4 接受 / 7 拒绝），覆盖全 PASS+空数组、JSON 往返、缺段非空、单元素数组，以及 complete 却非空、缺字段、字符串、非 J 元素、重复、非字符串、与 stages 矛盾。

**证明**（真跑）：

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".tools/check-ps.ps1" "scripts/acceptance/Joint.Common.ps1"
OK   Joint.Common.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".tools/check-ps.ps1" "scripts/acceptance/Test-JointManifestShape.ps1"
OK   Test-JointManifestShape.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/Test-JointManifestShape.ps1
...
RESULT=PASS cases=11 failures=0
EXIT=0
```

**仍未覆盖**：未用真实 `Prepare-Joint.ps1 -Stage All` 全量跑（需要全部阶段真实外部输入）；本项按任务允许的第二种方式直接调用断言函数证明。

## 3. P1-2 J7 探针 appCode 变量统一

**复审问题**：`joint-j7.ps1:235` 的 `Set-ProbeBinding` 发 `$ProbeAppCode`，但 `:261-268` 只给 `$ProbeApp` 赋值，发送时 `appCode` 为空串 → 期望 200 的绑定请求实际 400。

**改法**（`scripts/acceptance/joint-j7.ps1`）：`:261-280` 统一写回 `$ProbeAppCode`（`Get-Text` → 缺省生成 `joint_j7_probe_<seed>` → 不合约时回退 `joint_j7_probe`），`stageInputs.probeAppCode` 也读同一变量；删除并存的 `$ProbeApp`。注释写明“只有一个来源”。

**证明**：

```
python scripts/acceptance/stub-joint-j5-j9.py --port 18283 --mode normal
bash .local/joint-stub/run-stages.sh j7
[PASS] J7-临时binder基线写  HTTP 200 code=OK changed=True
...
J7 verdict=PASS
EXIT=0
```

证据文件 `.local/joint-stub-evidence/j7-20261102-172054.json` 中 `inputs.probeAppCode = "joint_j7_probe_stubj7"`（非空），16 项检查全部 `ok: true`。

**仍未覆盖**：真实租户下“撤 binder 角色后传播延迟”的等待窗口仍由 `-PermissionWaitSeconds` 控制，桩跑取 1s 未验证真实缓存窗口。

## 4. P1-3 J9 SCHEMA 的 expected 改为 JSON Schema 对象

**复审问题**：`joint-j9.ps1:301` 附近两个默认安全/功能用例 `assertion='SCHEMA'`，但 `expected` 是字符串 `'EX06_OK'`/`'EX06_REFUSE'`；`EvaluationAssertions` 的 SCHEMA 分支要求 `expected` 为 JSON 对象，真实评测会 400。

**改法**（`scripts/acceptance/joint-j9.ps1:302-305`）：`$schemaExpected = [ordered]@{ type = 'object' }`，两个用例的 `expected` 均指向它，保留 `assertion='SCHEMA'`；注释写明“SCHEMA 的 expected 必须是 JSON Schema 对象，字符串会 400”。字段名沿用 Java `EvaluationAssertions.FIELD="assertion"`（已核对），`safety=true` 只允许 EXACT/SCHEMA。

**证明**：

```
bash .local/joint-stub/run-stages.sh j9
[PASS] J9-发布预检  HTTP 200 code=OK ok
[PASS] J9-预检PASS  passed=True result=PASS gateMode=BLOCKING
[PASS] J9-发布commit  HTTP 200 code=OK ok
[PASS] J9-pin为PINNED  pin.state=PINNED reason=
...
J9 verdict=PASS
EXIT=0
```

负控（证明桩确实按真实语义校验 `expected` 类型）：

```
python scripts/acceptance/stub-joint-j5-j9.py --port 18297 --mode normal
POST /admin/evaluations  expected="EX06_OK"            -> STRING_EXPECTED_HTTP=400
                                                          {"code":"INVALID_ASSERTION","message":"stub: SCHEMA 的 expected 必须是JSON Schema对象"}
POST /admin/evaluations  expected={"type":"object"}    -> OBJECT_EXPECTED_HTTP=200
```

**仍未覆盖**：真实 monitor 的 SCHEMA 求值（JSON Schema 引擎）未跑；桩只校验类型契约。

## 5. P1-4 S1 C 段 SQL：public_id 换数值主键、error_semantic 按文本

**复审问题**：`Invoke-S1Acceptance.ps1:291` 附近直接 `where id='r_<32hex>'`（`engine.run.id` 是 bigint），并把 `error_semantic` 字符串 `ConvertFrom-Json`。

**改法**（`scripts/acceptance/Invoke-S1Acceptance.ps1:323-349`）：

- `public_id` → 数值主键：`select id::text from engine.run where public_id='<runId>'`，非纯数字抛 `S1_C_WAITING_RUN_NOT_FOUND`/`S1_C_FAILED_RUN_NOT_FOUND`；后续 `status`、`tool_call.run_id`、`node_instance.run_id` 全部用该数值 key。
- `error_semantic` 是 `varchar(32)` 文本码：按文本读取并断言 `^[A-Z][A-Z0-9_]{0,31}$`，不再 `ConvertFrom-Json`（注释已写迁移依据）。
- 结构化七字段错误从 `engine.run.error`（jsonb）取：`error->>'code'`、`->>'userMessage'`、`->>'traceId'`，三者必须非空。
- 依据（已读迁移）：`engine-runtime/src/main/resources/db/migration/V200__engine.sql:2-9` `engine.run(id bigint ... primary key, public_id varchar(80) unique not null, ..., error jsonb)`；同文件 `:32` `tool_call.error_semantic varchar(32)`；`V220__run_error_structured.sql:6` 仅新增 `tool_call.external_job jsonb`。

**证明**：

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".tools/check-ps.ps1" "scripts/acceptance/Invoke-S1Acceptance.ps1"
OK   Invoke-S1Acceptance.ps1
```

**仍未覆盖（如实记）**：本机无 `psql`、`5432` 未监听、无已产生的两份真实 run（`WAITING_EXTERNAL` 快照与 `FAILED` 终态），C 段运行态未执行；需要真实 PG + 已注册异步技能后按手册 §5 复跑。C 段未把 SQL 失败吞成 PASS：`Get-S1DbValue` 查询失败抛 `S1_DB_QUERY_FAILED`。

## 6. P1-5 S1 B 预检流程补全

**复审问题**：B 段三次 `POST /publish/precheck` 全部 `-Body $null`（真实预检本来无 body），但从未使用 `BPass*/BFail*` 候选身份；同一个 app/候选既期望 409 又期望 200，且断言 `gateMode=WAIVED`（Java 实际 `gateMode` 是 `BLOCKING/ADVISORY/OFF` 模式名）。缺“预检 PASS/WAIVED 判定、`EVAL_GATE_BLOCKED` 的 409 分支、豁免与撤销、DEMO 确认、commit 幂等”。

**改法**（`scripts/acceptance/Invoke-S1Acceptance.ps1`）：

- `:41` 新增 `[string]$BFailAppCode`（负例候选所在应用）；`:229-230` 缺该参数或与 `BAppCode` 相同 → 计入 missing，整段 `SKIPPED-BLOCKED`，不发请求。
- `:249-258` 先 `GET /admin/apps/{code}` 两应用，校验冻结的 `draftRev/candidateSha256` 就是服务端当前候选（stale → FAIL），避免把参数陈旧导致的门禁结果误判成门禁本身。
- `:259-260` 负例 precheck（无请求体）→ 409 `EVAL_GATE_BLOCKED`。
- `:261-264` ADMIN `POST /admin/apps/{failApp}/publish-waivers`，body `candidateSha256/draftRev/reason/expiresAt`，取 `data.id`（回退 `data.waiverId`）。
- `:265-271` 再 precheck → 200、`passed=true`、`result=PASS`、`gateMode=BLOCKING`（注释说明豁免事实看 `waiverId` 与 `check.result=WAIVED`，不是 `gateMode=WAIVED`）、`waiverId` 匹配、存在 `result=WAIVED` 且 `waiverId` 匹配的 check。
- `:272-273` `DELETE /publish-waivers/{id}` → 200 且 `status=REVOKED`。
- `:274-275` 再 precheck → 恢复 409。
- `:276-278` 正例 app precheck → 200 PASS，取 `precheckId`。
- `:279-292` `-BCommit` 时：若 `environmentConfirmationRequired=true` 则提交体带 `evaluationEnvironmentConfirmation={confirmed=true,precheckId,evalReportId,evidenceSha256=environmentEvidenceSha256}`（DEMO 非 REAL 的显式确认，依据 `AppService.requirePublicationEnvironment`）；commit 取 `versionNo`，同 `idempotencyRequestNo` 重放必须同 `versionNo`。

**证明**（桩 `mode=s1` 为 `joint_stub_resolve` 预置匹配 ACCEPTED 报告；负例用另一应用 `joint_stub_explicit`）：

```
bash .local/joint-stub/run-s1b.sh
STATS_BEFORE={"mode": "s1", "requests": 0}
S1 stages=B
  [PASS] B:
S1 verdict=PASS
EXIT=0
STATS_AFTER={"mode": "s1", "requests": 10}
```

证据 `.local/s1-evidence-s1b/s1-b-20261102-172115.json`：

```
blockedStatus=409 blockedCode=EVAL_GATE_BLOCKED
waivedGateMode=BLOCKING waivedCheckResult=WAIVED
revokedStatus=REVOKED restoredStatus=409
environmentConfirmationRequired=True
commitStatus=200 versionNo=2 replayVersionNo=2
```

缺输入（不给 `BFailAppCode`，其余参数齐全）时仍 `SKIPPED-BLOCKED` 且不发请求：

```
[SKIPPED-BLOCKED] B: 缺外部输入: BFailAppCode(负例候选所在应用)
S1 verdict=BLOCKED
EXIT=3
STATS_BEFORE={"mode": "s1", "requests": 0}
STATS_AFTER={"mode": "s1", "requests": 0}
```

**仍未覆盖**：真实 app-management + monitor 的 waiver 落库（`app.publish_waiver`）、非 ADMIN 403、waiver 开关关闭 403、过期边界未跑；桩只覆盖协议与分支顺序。

## 7. P1-6 J8 认证路径补“合法身份 + 跨空间”

**复审问题**：J8 只证“A 凭据 + 伪造 `X-User-*` 头不扩大权限”，没有“合法身份（B 空间 binder）跨空间仍被拒”的路径。

**改法**：

- `scripts/acceptance/joint-j8.ps1:39-49` 新增 `-IsolationBinderKey`、`-IsolationBinderKeyEnv`（默认 `AGENT_SECRET_JOINT_ISOLATION_BINDER_KEY`）、`-IsolationAppCode`。
- `:242-260` 只从显式参数/专用环境变量读取（不猜 `credentials.env` 里的多个 `*_BINDER`），形状非法进 Missing（BLOCKED）。
- `:272-299` 派生独立的 B 空间探针 appCode（避免把合法写入算成 A 空间泄漏），`stageInputs`/`boundaries` 写明第二身份腿缺失时记 `SKIPPED-BLOCKED`。
- `:387-413` 新增 6b：`J8-B空间合法身份绑定`（B 空间写 200 + bindingId UUID）→ `J8-合法身份跨读A403`（403 `NO_PERMISSION` 且 `data` 为空）→ `J8-合法身份跨写A403`（403 `NO_PERMISSION`）；任一失败即 FAIL。缺 `-IsolationBinderKey` 时打印 `[SKIPPED-BLOCKED] J8-合法身份跨空间 缺 ...`，不伪造身份头，不影响 A 凭据负例。
- `Invoke-JointAcceptance.ps1:47-49/:95/:471/:561` 增加同名参数、仅显式/专用 env 解析、透传 J8，并在 `inputs` 摘要记录 `isolationAppCode`/`isolationBinderKeyProvided`。

**证明**：

```
bash .local/joint-stub/run-stages.sh j8
[PASS] J8-B空间合法身份绑定  HTTP 200 code=OK bindingId=... changed=True
[PASS] J8-合法身份跨读A403  HTTP 403 code=NO_PERMISSION data=<null>
[PASS] J8-合法身份跨写A403  HTTP 403 code=NO_PERMISSION
...
J8 verdict=PASS
EXIT=0
```

入口级全量（`run-invoke.sh allpass`）同样 5 段全 PASS，J8 证据 `.local/joint-stub-evidence/invoke-allpass/j8-20261102-172100.json` 含上述三条 `ok:true`。

**仍未覆盖**：真实 OR 的第二空间/第二租户合法服务身份需现场签发；本次桩用 `SPACE_B` binder key 验证码路径。真实“第二个租户”登录身份没有，未伪造，按手册记明缺失项。

## 8. P1-7 J4 检查失败立即结束、不产生导入副作用

**复审问题**：J4a 的 `Add-Check` 失败只写 `$script:FirstFailure` 不改 `$Verdict`，J4b 仍继续导入；请求体 >2MiB 也不阻止导入。

**改法**（`scripts/acceptance/joint-j4.ps1`）：

- `:299-304` J4a 逐清单循环末 `if ($script:FirstFailure) { break }`；循环后置 `Verdict=FAIL`、`Reason='J4a 清单校验失败，未发起导入：...'`，不再执行“同空间”收尾。
- `:332` 请求体超 2MiB 置 FAIL 且不导入。
- `:336-337` 导入调用移入 `if ($Verdict -eq 'PASS' -and -not $script:FirstFailure)`，失败即无导入副作用。
- `scripts/acceptance/stub-joint-j4.py` 新增 `/__stats`（`requests`/`importRequests`，自身不计数），POST import 时才 `importRequests += 1`，用于证明“fail 出口 0 导入请求”。

**证明**：

```
python scripts/acceptance/stub-joint-j4.py --port 18293 --mode normal
joint-j4.ps1 ...（完整参数见 run 日志）
PS_EXIT=0
J4 verdict=PASS
STATS_AFTER={"mode":"normal","requests":2,"importRequests":1}

python scripts/acceptance/stub-joint-j4.py --port 18294 --mode preview-manifest
PS_EXIT=1
[FAIL] J4a-清单1-meta-preview  preview 必须为 false 或缺失
J4 verdict=FAIL
J4 reason=J4a 清单校验失败，未发起导入：J4a-清单1-meta-preview: preview 必须为 false 或缺失
STATS_AFTER={"mode":"preview-manifest","requests":1,"importRequests":0}
```

即失败出口 `requests=1`（只取清单）而 `importRequests=0`（无导入请求）。

**仍未覆盖**：真实 AP 导入接口与真实 OR manifest 未跑（桩只覆盖传输/装配契约）；J4 的 `-PlatformBase` 若指向真实 AP，导入失败路径仍需现场复跑。

## 9. 入口级三场景（EXIT 原文）

`bash .local/joint-stub/run-invoke.sh allpass`：

```
JOINT totals pass=5 fail=0 blocked=0 skipped=0 planned=0
JOINT verdict=PASS
EXIT=0
```

`bash .local/joint-stub/run-invoke.sh server500`：

```
[FAIL] J5-解析应用-换绑请求 HTTP 500
J5 verdict=FAIL
JOINT verdict=FAIL
EXIT=1
```

`bash .local/joint-stub/run-invoke.sh blocked`（缺 `-LivePublicationApproved`）：

```
[BLOCKED] J9 未执行：缺外部输入: LivePublicationApproved(真实发布显式批准)
  [SKIPPED-BLOCKED] J9: 缺外部输入: LivePublicationApproved(真实发布显式批准)
JOINT verdict=BLOCKED
STATS_BEFORE={"mode": "normal", "requests": 0}
STATS_AFTER={"mode": "normal", "requests": 0}
EXIT=3
```

## 10. check-ps 汇总（8 个 .ps1 全部 0 错误）

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".tools/check-ps.ps1" "scripts/acceptance/<file>"
OK   Joint.Common.ps1
OK   Test-JointManifestShape.ps1
OK   joint-j4.ps1
OK   joint-j7.ps1
OK   joint-j8.ps1
OK   joint-j9.ps1
OK   Invoke-JointAcceptance.ps1
OK   Invoke-S1Acceptance.ps1
```

Python 桩：`python -m py_compile scripts/acceptance/stub-joint-j4.py scripts/acceptance/stub-joint-j5-j9.py` → `PY_COMPILE_OK`。

## 11. 未覆盖与边界（如实记）

- **S1 C**：无 `psql`、无 5432 监听、无真实 run 数据，运行态未执行；SQL 依据迁移（`V200__engine.sql:2-9/:32`、`V220__run_error_structured.sql:6`）静态改正并通过 check-ps，待真实环境按 S1 手册 §5 复跑。
- **S1 A/D**：本次未改，依赖 18080–18085/18182 六服务与 PG，未跑。
- **J8 第二身份**：桩证明码路径；真实第二租户/第二空间合法身份需现场签发，未伪造。
- **J9/J7/J4**：桩只证明协议/装配/失败即止；真实 monitor 评测、真实 OR 绑定与真实 AP 导入仍需按手册在真实服务上复跑。
- 桩数据不作为验收通过证据（联合手册边界）；本次结论是“复审缺陷已修 + 脚本契约自检通过”。
