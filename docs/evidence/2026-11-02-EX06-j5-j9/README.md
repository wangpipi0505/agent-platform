# EX-06 J5–J9 交付证据（2026-11-02）

## 1. 目的与结论

EX-06 的联合验收入口此前只实现 J1–J4，`Prepare-Joint.ps1 -Stage All` 的 `requiredStages`
把 J5–J9 硬编码成"未交付"，因此 `complete` 永远为 false。本次把 J5–J9 实现为**真实阶段**：

- 新增 `scripts/acceptance/joint-j5.ps1` … `joint-j9.ps1`，每段独立执行、独立写证据、
  退出码 0/1/3，缺前提时 `SKIPPED-BLOCKED` 且**不发任何请求**。
- `Invoke-JointAcceptance.ps1` 的 `-Stages` 支持 `J5..J9`（含 `-Stages J5,J6` 逗号写法），
  逐段准备/执行/记录，删除了"该阶段脚本未交付"的兜底说法。
- `Prepare-Joint.ps1 -Stage All` 的 `requiredStages` 改为**按真实存在的阶段脚本推导**
  （`Test-Path`），只要 J5–J9 五个脚本都在，All 就按 J1–J9 聚合；`complete` 判定未放宽：
  仍是"字段齐 + 阶段齐 + verdict=PASS"。
- 用本地协议桩证明每段正/负路径与入口三场景（全 PASS 退出 0、500 退出 1、缺输入退出 3）。

本轮**未对真实双仓（AP 18080 / OR 18182）执行**：桩只证明协议/装配，不能作为验收通过证据。
这不影响"J5–J9 已实现、complete 可达"的结论，但真实环境跑仍需 EX-07 按手册执行。

## 2. 交付物（新增/修改文件）

| 文件 | 变更 |
| --- | --- |
| `scripts/acceptance/joint-j5.ps1` | 新增：绑定更新/显式优先（手册 §4 J5） |
| `scripts/acceptance/joint-j6.ps1` | 新增：解绑/故障边界（手册 §4 J6） |
| `scripts/acceptance/joint-j7.ps1` | 新增：轮换/撤权（手册 §4 J7） |
| `scripts/acceptance/joint-j8.ps1` | 新增：租户/空间隔离（手册 §4 J8） |
| `scripts/acceptance/joint-j9.ps1` | 新增：平台真实发布→pin（手册 §4 J9） |
| `scripts/acceptance/stub-joint-j5-j9.py` | 新增：本地 AP+OR 协议桩（一个进程两种形状） |
| `scripts/acceptance/Invoke-JointAcceptance.ps1` | 修改：J5–J9 编排、参数、缺输入判定、manifest 补缺 |
| `scripts/acceptance/Prepare-Joint.ps1` | 修改：`requiredStages` 按脚本推导、All 聚合传 J5–J9 参数 |
| `docs/evidence/2026-11-02-EX06-j5-j9/README.md` | 本文件 |

未改 Java 产品代码、`pom.xml`、`contracts/`。新增/修改的 `.ps1` 全部 UTF-8 BOM + CRLF。
（仓库既有 `scripts/acceptance/joint-j3.ps1` 为 BOM+LF，是存量文件，本轮未改。）

## 3. J5–J9 阶段语义与参数

手册中 J5–J9 的原始检查项位于
`文档/优化方案/联合验收与回归执行手册-20261028.md` §4（行 711–721）。
每段都只调用**平台已有接口**，不新增产品服务、不伪造评测/发布成功。

### J5 绑定更新/显式优先

- 原检查项：把 `appCode` 与 `explicitAppCode` 的当前 ACTIVE 绑定都改到 `replacementReleaseId`；
  分别运行 `appCode`（冻结配置无 releaseId）与 `explicitAppCode`（冻结配置仍为旧 ID）。
- 必须满足：最多 30s 缓存期后 `appCode` 取新绑定；保存 explicitAppCode
  "当前 pin=new、冻结配置=old、最终 run=old" 的冲突证据；发布新本体不自动改 pin。
- 真实接口：`POST/GET /onto/internal/release-bindings`（binder key）、
  `GET /admin/apps/{code}`、`POST /admin/apps/{code}/runs` + `GET /admin/runs/{id}`
  （`observation.releaseId` 来自 `snapshot.contextPack`）。
- 参数：`-OntologyBase -PlatformBase -PlatformTenant -Environment -SpaceId -SpaceCode
  -ReleaseId -ReplacementReleaseId -AppCode -ExplicitAppCode -BinderKey -BearerToken
  -CredentialsFile -CacheWaitSeconds(0..90, 默认31) -RunTimeoutSeconds(10..900) -EvidenceDir -SessionId`。
- 判定：换绑返回 `releaseId=new`（`changed=true`，已收敛时允许 `changed=false` 但 bindingId 不变）；
  解析应用 run 的 `observation.releaseId=new`；
  显式应用 run 的 `observation.releaseId=old`（显式优先）；运行前后绑定 `bindingId` 不变。
  缺 `ReplacementReleaseId`/`ExplicitAppCode`/binder/Bearer → BLOCKED，不发请求。

### J6 解绑/故障边界

- 原检查项：DELETE 绑定后等缓存期；另注入非 `BINDING_NOT_FOUND` 的 404/5xx。
- 必须满足：真 404 `BINDING_NOT_FOUND` 按既有策略跳过并写 `pinFailure`；
  网关 404/坏响应/5xx 必须 503，不静默当无绑定。
- 真实接口：`DELETE /onto/internal/release-bindings/{id}`、`GET`（404 形状）、
  `POST /admin/apps/{code}/runs`；故障面是**第二个 AP**（`AGENT_ONTOLOGY_URL` 指向故障 OR）。
- 参数：`-PlatformBase`（正常 AP）、`-FaultPlatformBase`（故障 AP，**必填**）、
  `-SpaceId -AppCode -BinderKey -BearerToken -CacheWaitSeconds -RunTimeoutSeconds -EvidenceDir -SessionId`。
- 判定：故障侧 run 必须 503 `CONTEXT_PACK_UNAVAILABLE`；解绑返回 `status=UNBOUND`、
  读回 404 `BINDING_NOT_FOUND`；缺绑定后正常 run 非 503 且 `observation.releaseId` 为空
  （证明跳过策略），最后把绑定恢复到基线。
  缺 `FaultPlatformBase` → BLOCKED（不用"跳过故障注入"冒充通过）。

### J7 轮换/撤权

- 原检查项：用另建独立 platformActor/主体的临时凭据组做 grace=0 轮换并撤 binder 角色/消费凭据；
  不得撤销 manifest 主 readonly/binder/consumer。
- 必须满足：老 key 按 grace 拒绝；新 key 可用；撤掉 release.bind 后下一次写 403；
  撤凭据 401；缓存窗口内/外分别记录，不能宣称即时远端重鉴权。
- 真实接口：`POST /onto/platform-actors/{id}/rotate {graceSeconds:0}`、
  `POST /onto/platform-actors/{id}/revoke`、`GET /onto/system/users|roles`、
  `PUT /onto/system/users/{id}`（去掉 `SERVICE_ACCOUNT_BINDER`）、
  `POST /onto/r/{spaceCode}/releases/{id}/ops/{opCode}`、`GET /onto/releases/{id}/manifest`。
- 参数：`-SpaceId -SpaceCode -ReleaseId -OpCode -OntologyAdminBearer(或 AGENT_ACCEPTANCE_OR_BEARER)
  -ReadKey -RevocationBinderKey/-RevocationBinderCredentialId/-RevocationBinderPrincipalId
  -RevocationConsumerKey/-RevocationConsumerCredentialId -DestructiveApproved -PermissionWaitSeconds`。
- 判定：临时 binder 基线写 200 → 轮换拿新 key → 旧 key 401 → 新 key 200 →
  撤角色后有界重试至 403 `NO_PERMISSION` → 撤 binder 凭据 401 → 撤消费凭据 401 →
  主只读 key 仍 200。未 `-DestructiveApproved` → BLOCKED。

### J8 租户/空间

- 原检查项：A 凭据请求 B 空间/不同 tenant 的绑定与操作；伪造 `X-User-*` 不能扩大权限。
- 必须满足：拒绝、无泄漏/无源访问/无新绑定。
- 真实接口：`POST /onto/internal/release-bindings`（B `spaceId`/B `releaseId`）、
  `GET`（伪造 `platformTenant`）、`GET /onto/releases/{B}/bindings`（人工 Bearer）、
  `POST /onto/internal/releases/{B}/ops/{op}`。
- 参数：`-SpaceId -ReleaseId -AppCode -IsolationSpaceId -IsolationReleaseId -IsolationOpCode
  -BinderKey -OntologyAdminBearer -ProbeAppCode`（隔离夹具来自 manifest `isolationFixtures`）。
- 判定：跨空间写 403 `NO_PERMISSION`；B 空间绑定列表无探针 appCode；A 空间+B release 403；
  伪造 `platformTenant` 403；伪造 `X-User-Id/X-User-Roles/X-Tenant/X-User-Principal` 仍 403；
  跨空间 ops 403 且 `data` 为空；A 空间绑定未变。缺隔离夹具/人工 Bearer → BLOCKED。

### J9 平台真实发布→pin

- 原检查项：发布 `pinAppCode` 触发 ReleasePins，再读本体内部绑定与 `app.publish_pin`。
- 必须满足：请求含 spaceId、绑定成功且落库一致；重复同发布请求不重复副作用；
  pin 失败响应/落库可查；DISABLED/SKIPPED 不算成功。
- 真实接口：`GET /admin/apps/{code}`、`PUT /admin/apps/{code}/config`、
  `POST /admin/evaluations` + `GET /admin/evaluations/{id}`、
  `POST /admin/apps/{code}/publish/precheck`、`POST /admin/apps/{code}/publish/commit`、
  `GET /admin/apps/{code}/pin-status`、`GET /admin/apps/{code}/versions[/{n}]`、
  `GET /onto/internal/release-bindings`。
- 参数：`-SpaceId -SpaceCode -ReleaseId -ReplacementReleaseId -PinAppCode -BinderKey
  -BearerToken -EvaluationPlanPath -PollTimeoutSeconds -PollIntervalSeconds
  -LivePublicationApproved -EvidenceDir -SessionId`。不传 `-EvaluationPlanPath` 时用
  TestFixture 合成评测集，并在检查行 `J9-评测数据来源 modelMode=TestFixture` 与证据
  `inputs.modelMode/datasetVersion` 中显式标注（真实模型评测需传计划文件，此时记 `Real`）。
- 判定：基线 pin 为 `PINNED/旧release`；改配置指向新 release（draftRev+1、candidateSha256 变）；
  评测 ACCEPTED；预检 PASS（BLOCKING）；commit 版本 +1、`pin.state=PINNED`、
  `pin.bindingId` GUID、`pin.changed=true`；`pin-status` 与 OR 内部绑定一致；
  同 `requestNo` 重放：同版本、同 bindingId、`changed=false`、无新版本行、pin 行不变。
  `SKIPPED/DISABLED/FAILED` 或 502 `PIN_FAILED` → FAIL。未 `-LivePublicationApproved` → BLOCKED。

## 4. 编排变更

- `Invoke-JointAcceptance.ps1`
  - 新增参数：`-ExplicitAppCode -PinAppCode -ReplacementReleaseId -OpCode -FaultPlatformBase
    -EvaluationPlanPath -IsolationSpaceId -IsolationReleaseId -IsolationOpCode -OntologyAdminBearer
    -OntologyAdminBearerEnv -DestructiveApproved -LivePublicationApproved -CredentialsFile
    -CacheWaitSeconds`；`-ManifestPath` 现在真正用于补缺（主字段 + `revocationFixtures`
    + `isolationFixtures`，命令行显式值优先）。
  - `Get-JointStageRequirements` 增加 J5–J9 分支：缺任何一项 → 该段 `SKIPPED-BLOCKED`
    且不发起 HTTP（`[BLOCKED] ... 未执行`）。
  - 逐段脚本映射：J5→`joint-j5.ps1` … J9→`joint-j9.ps1`；证据 glob `j5-*.json` … `j9-*.json`。
  - 删除了"该阶段脚本未交付（当前只交付 J1/J2、J3、J4）"的兜底，替换为
    "该阶段未产生执行行（编排缺失，不得记通过）"。
  - 平台 Bearer 自动获取条件从 `J4` 扩展到 `J4/J5/J6/J9`（仍校验 `tenantId=local-v12`）。
- `Prepare-Joint.ps1`
  - `Invoke-JointAllAggregation` 内：
    `$stageScripts=@{J1..J9}`，`$requiredStages=@('J1'..'J9') | Where-Object { Test-Path $stageScripts[$_] }`。
    缺 J5–J9 脚本时它们不会进入 requiredStages；五个脚本都在时 All 必须跑 J1–J9。
  - All 聚合调用新增传参：`ReplacementReleaseId/PinAppCode/ExplicitAppCode/OpCode/
    OntologyAdminBearer/FaultPlatformBase/DestructiveApproved/LivePublicationApproved/
    EvaluationPlanPath（来自 -PlatformEvaluationPlanPath）/
    IsolationSpaceId/IsolationReleaseId/IsolationOpCode`。
  - 新增准备参数 `-FaultPlatformBase`、`-DestructiveApproved`（`-LivePublicationApproved` 复用）。
  - `complete` 判定未改：`verdict=PASS && missingFields=0 && missingStages=0`。

## 5. 本地桩与跑法

桩：`scripts/acceptance/stub-joint-j5-j9.py`（Python 3），一个进程同时提供 AP（`/admin/**`）
与 OR（`/onto/**`）形状，`--port`、`--mode`，`GET /__stats` 返回 `{mode,requests}`；
`/__stats` 自身不计数，用于证明"缺输入 0 请求"。

模式：`normal`、`server-error`（绑定 POST 500）、`gateway-500`（run 503）、
`pin-skipped`、`pin-failed`、`binding-404`。

常量（阶段参数必须一致）：tenant `local-v12`、environment `production`；
space A `11111111-1111-4111-8111-111111111111`（`joint_a`）、
space B `22222222-2222-4222-8222-222222222222`（`joint_b`）；
old `aaaaaaaa-…`、new `bbbbbbbb-…`、B release `cccccccc-…`、opCode `joint_list`；
bearer `stub-acceptance-bearer`；binder key `ontosa_`+43×`b`、read key `ontosa_`+43×`r`、
temp binder `ontosa_`+43×`t`、temp consumer `ontopa_`+43×`c`；
app：`joint_stub_resolve`（只有 space）、`joint_stub_explicit`/`joint_stub_pin`（space+old）、
`joint_stub_j8`（A 空间绑定基线）。

入口三场景（脚本在 `.local/joint-stub/` 下，`.local/` 被 gitignore；键/ID 与上述常量一致）：

```
bash .local/joint-stub/run-invoke.sh allpass
bash .local/joint-stub/run-invoke.sh server500
bash .local/joint-stub/run-invoke.sh blocked
```

三个场景的完整输出留档在 `.local/joint-stub-evidence/{allpass,server500,blocked}.log`
（`.local/` 被 gitignore，故把 `EXIT=` 原文抄录如下）。

### 5.1 全 PASS（期望退出 0）

```
JOINT stages=J5,J6,J7,J8,J9
  [PASS] J5:
  [PASS] J6:
  [PASS] J7:
  [PASS] J8:
  [PASS] J9:
JOINT totals pass=5 fail=0 blocked=0 skipped=0 planned=0
JOINT verdict=PASS
JOINT summary=.local\joint-stub-evidence\invoke-allpass\joint-summary.json
EXIT=0
```

### 5.2 某阶段 500（期望退出 1）

桩 `--mode server-error` 让绑定 POST 恒 500，J5 必须判 FAIL：

```
[FAIL] J5-解析应用-换绑请求  HTTP 500 code=INTERNAL_ERROR stub: 注入 500
J5 verdict=FAIL
J5 reason=appCode 换绑失败：HTTP 500 INTERNAL_ERROR
JOINT stages=J5
  [FAIL] J5: [FAIL] J5-解析应用-换绑请求  HTTP 500 code=INTERNAL_ERROR stub: 注入 500
JOINT totals pass=0 fail=1 blocked=0 skipped=0 planned=0
JOINT verdict=FAIL
EXIT=1
```

### 5.3 缺输入（期望退出 3，且 0 请求）

不传 `-LivePublicationApproved` 跑 J9：

```
[BLOCKED] J9 未执行：缺外部输入: LivePublicationApproved(真实发布显式批准)
JOINT stages=J9
  [SKIPPED-BLOCKED] J9: 缺外部输入: LivePublicationApproved(真实发布显式批准)
JOINT verdict=BLOCKED
STATS_BEFORE={"mode": "normal", "requests": 0}
STATS_AFTER={"mode": "normal", "requests": 0}
EXIT=3
```

### 5.4 单阶段跑法（各段独立证据）

`.local/joint-stub/run-stages.sh j5|j6|j7|j8|j9` 直连桩，逐段退出码：

```
bash .local/joint-stub/run-stages.sh j5   → J5 verdict=PASS   EXIT=0
bash .local/joint-stub/run-stages.sh j6   → J6 verdict=PASS   EXIT=0
bash .local/joint-stub/run-stages.sh j7   → J7 verdict=PASS   EXIT=0
bash .local/joint-stub/run-stages.sh j8   → J8 verdict=PASS   EXIT=0
bash .local/joint-stub/run-stages.sh j9   → J9 verdict=PASS   EXIT=0
```

J5 冲突三元组原文：`J5-冲突三元组 currentPin=bbbb… frozenConfig=aaaa… finalRun=aaaa…`。
J9 pin 落库原文：`J9-pin为PINNED pin.state=PINNED` / `J9-pin-bindingId非空 bindingId=<guid>` /
`J9-OR绑定落库一致 releaseId=bbbb… bindingId=<guid>` / `J9-重放不重复绑定 … changed=False`。

### 5.5 必跑阶段由实际脚本计算（`complete=true` 可达性）

`Prepare-Joint.ps1` 里不再硬编码"只交付 J1–J4"。把文件里那两行真实表达式取出求值
（`.local/joint-stub/check-required-stages.ps1`，只读校验，不连任何服务）：

```
STAGE_EXPR=$stageScripts=@{J1='joint-j1-j2.ps1';J2='joint-j1-j2.ps1';J3='joint-j3.ps1';J4='joint-j4.ps1';J5='joint-j5.ps1';J6='joint-j6.ps1';J7='joint-j7.ps1';J8='joint-j8.ps1';J9='joint-j9.ps1'}
REQUIRED_EXPR=$requiredStages=@('J1','J2','J3','J4','J5','J6','J7','J8','J9')|Where-Object{Test-Path -LiteralPath (Join-Path $PSScriptRoot $stageScripts[$_]) -PathType Leaf}
REQUIRED=J1,J2,J3,J4,J5,J6,J7,J8,J9
COUNT=9
SCRIPT J5 joint-j5.ps1 exists=True
SCRIPT J6 joint-j6.ps1 exists=True
SCRIPT J7 joint-j7.ps1 exists=True
SCRIPT J8 joint-j8.ps1 exists=True
SCRIPT J9 joint-j9.ps1 exists=True
COMPLETE_RULE=$complete=($verdict -eq 'PASS' -and $missing.Count -eq 0 -and $missingStages.Count -eq 0)
```

即：五个脚本都在 → All 必跑 J1–J9；`complete` 判据本身一个字没改，仍要求
`verdict=PASS` + 主字段齐 + 无缺段。**All 的端到端真跑仍未执行**（需真实双仓），
见 §8 第 1 条。

### 5.6 J9 pin 负例（SKIPPED / FAILED 不算成功）

桩 `--mode pin-skipped` 让 commit 返回 `pin.state=SKIPPED`：

```
[FAIL] J9-pin为PINNED  pin.state=SKIPPED reason=stub: config.ontology 缺少 space/releaseId
[FAIL] J9-pin-bindingId非空  bindingId=
[FAIL] J9-pin-changed=true  pin.changed=
J9 verdict=FAIL
J9 reason=发布返回的 pin 不满足 PINNED + bindingId + changed=true（state=SKIPPED changed=）
J9_PIN_SKIPPED_EXIT=1
```

桩 `--mode pin-failed` 让 commit 直接 502 `PIN_FAILED`：

```
J9 verdict=FAIL
J9 reason=发布时 pin 失败（fail-closed 502 PIN_FAILED）：pin 未成功，本段不算通过
J9_PIN_FAILED_EXIT=1
```

两次都用**全新桩进程**跑（桩状态跨进程共享会污染基线），日志见
`.local/joint-stub-evidence/{pin-skipped,pin-failed}.log`。

## 6. `.tools/check-ps.ps1` 输出

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".tools/check-ps.ps1" \
  scripts/acceptance/joint-j5.ps1 scripts/acceptance/joint-j6.ps1 scripts/acceptance/joint-j7.ps1 \
  scripts/acceptance/joint-j8.ps1 scripts/acceptance/joint-j9.ps1 \
  scripts/acceptance/Invoke-JointAcceptance.ps1 scripts/acceptance/Prepare-Joint.ps1
```

```
OK   joint-j5.ps1
OK   joint-j6.ps1
OK   joint-j7.ps1
OK   joint-j8.ps1
OK   joint-j9.ps1
OK   Invoke-JointAcceptance.ps1
OK   Prepare-Joint.ps1
```

0 错误（退出码 0）。所有新增/修改 `.ps1` 均为 UTF-8 BOM + CRLF（PowerShell 5.1 无 BOM
会把中文当 ANSI，吞引号导致解析错误）。

## 7. J5–J9 与本手册 §3 原检查项的对应表

> 手册里该表实际位于 §4（"后置 J1～J9 统一验收"，行 711–721）；标题沿用父任务称呼"§3"。

| 阶段 | 手册原检查项（§4 行） | 实现 | 本轮证据 |
| --- | --- | --- | --- |
| J5 绑定更新/显式优先 | 双应用改绑 replacement、appCode 取新绑定、explicit 仍取旧 ID（行 717） | `joint-j5.ps1`，真实 `POST/GET /onto/internal/release-bindings` + AP run observation | 桩跑 PASS（EXIT=0）；真实双仓未跑 |
| J6 解绑/故障边界 | DELETE 后缓存期、真 404 跳过写 pinFailure、5xx 必须 503（行 718） | `joint-j6.ps1`：正常 AP 解绑/跳过腿 + `-FaultPlatformBase` 故障腿 | 桩跑 PASS（EXIT=0）；`pinFailure` 字段真实落库不可 HTTP 读，见 §8 |
| J7 轮换/撤权 | 临时主体 grace=0 轮换、撤 binder 角色/消费凭据、主凭据不动（行 719） | `joint-j7.ps1`，`rotate{graceSeconds:0}` + `PUT /system/users/{id}` + revoke | 桩跑 PASS（EXIT=0）；真实 IAM 未跑 |
| J8 租户/空间 | A 凭据请求 B 空间/异租户被拒、伪造 `X-User-*` 无效（行 720） | `joint-j8.ps1`，跨空间/跨租户/伪造头/跨空间 ops 四类负例 | 桩跑 PASS（EXIT=0）；第二租户**合法**身份未构造，见 §8 |
| J9 平台真实发布→pin | 发布 `pinAppCode` 触发 ReleasePins、读内部绑定与 `app.publish_pin`、重放无重复副作用（行 721） | `joint-j9.ps1`，config→评测→预检→commit→pin-status→OR 绑定→同 requestNo 重放 | 桩跑 PASS（EXIT=0）；真实发布未跑 |

## 8. 未覆盖与边界（不得当作已通过）

1. **未对真实双仓执行**：AP/OR 均为本地协议桩，只证明脚本契约与装配，不证明真实发布、
   真实评测、真实 IAM、真实 OR 绑定落库。
2. **J6 的 `pinFailure` 字段**：AP 把 `pinFailure=BINDING_NOT_FOUND` 只写进 run 快照（数据库），
   `GET /admin/runs/{id}` 只暴露 `observation`。因此 J6 以"非 503 + `observation.releaseId` 为空"
   证明跳过策略，不伪造该字段；真实环境如需该字段须另查库（本轮未做）。
3. **J7 的缓存窗口**：真实 AP 的绑定读缓存 TTL 30s；桩不模拟缓存，J7 只证明撤权后的实时拒绝，
   "缓存窗口内/外分别记录"需在真实环境用 `-PermissionWaitSeconds` 与时间戳补证。
4. **J8 的第二租户**：没有第二个**合法**租户身份（需真实 IdP/JWT），本轮用伪造
   `platformTenant` 参数与 `X-User-*` 头证明"不能扩大权限"；跨租户**正向**访问未构造。
5. **J9 的 spaceId 在请求中的证据**：`spaceId` 由 AP `ReleasePins` 固定写入
   `{spaceId,platformTenant,appCode,environment,releaseId}`，OR 侧再从凭据行取空间/租户做二次校验；
   本轮以"OR 绑定落库一致"间接证明，未抓 AP→OR 原始 HTTP 报文。
6. **未跑真实评测/发布**：J9 的评测使用 TestFixture 或用户提供的 `-EvaluationPlanPath`；
   `DISABLED/SKIPPED/FAILED` 的判定靠严格等值 + 桩模式 `pin-skipped`/`pin-failed` 自检
   （§5.6，均判 FAIL 退出 1），真实"pin 失败落库"仍需真实环境复现。
7. **All 端到端未跑**：`requiredStages` 推导与 `complete` 判据已用真实表达式求值验证（§5.5），
   但 `Prepare-Joint.ps1 -Stage All` 需要真实 OR/AP 才能跑完 J1–J9 并把 `complete` 置 true。

## 9. 复现

```bash
cd 代码/agent-platform
python scripts/acceptance/stub-joint-j5-j9.py --port 18283 --mode normal &
python scripts/acceptance/stub-joint-j5-j9.py --port 18284 --mode gateway-500 &
bash .local/joint-stub/run-invoke.sh allpass
bash .local/joint-stub/run-invoke.sh server500
bash .local/joint-stub/run-invoke.sh blocked
```

（`.local/` 为 gitignore 的本机辅助目录，内含 `run-invoke.sh`、`run-stages.sh`、
`manifest.json`、`to-bom.py`；证据 JSON 落在 `.local/joint-stub-evidence/`。）
