# EX-06 复审问题修复（第二轮，C1 收尾）

基线：AP `e5cfef31`、OR `25df1eb`（`复审问题.md` 新版）。本轮只改验收脚本，不动产品代码。

## 1 问题与修法

| # | 复审问题 | 修法 | 文件 |
| --- | --- | --- | --- |
| P1-1 | `-Stage All` 没把 `revocationFixtures` 传给执行入口，J7 拿不到临时凭据 ID/ref ⇒ BLOCKED | 聚合时先把资源侧字段写成 `manifest.pre.json`（含 `revocationFixtures`/`isolationFixtures`/三段 keyRef），并把 `-ManifestPath`、`-CredentialsFile` 交给 `Invoke-JointAcceptance.ps1`；最终 `manifest.json` 仍由聚合段按实跑结果写 | `Prepare-Joint.ps1` |
| P1-2 | J8 用机器 key 打 `/onto/internal/**`（要求人登录 Bearer）却期待 403/404，实际先 401；跨空间读断言与“按凭据空间查询”语义不符 | §7 改人 Bearer 打内部面，断言 403 `NO_PERMISSION`；新增 §7b 机器 key 打同一面必须 401 `SERVICE_CREDENTIAL_REQUIRED`；§6b 跨空间读改断言 404 `BINDING_NOT_FOUND` 且 `data=null` | `joint-j8.ps1` |
| P2-1 | 只给 manifest + 私密凭据文件时，URL/租户/环境/密钥引用未接通：参数默认值（`127.0.0.1:18182`）把 manifest 值静默覆盖，且 keyRef 没进解析链 | manifest 映射改为“按 `$PSBoundParameters` 判显式输入”，未显式传参时以 manifest 为准；`consumerKeyRef→AuthRef`、`readKeyRef`/`binderKeyRef` 接入 `Resolve-JointCredential` | `Invoke-JointAcceptance.ps1` |
| P2-2 | J9 两个用例的 `expected` 都退化成 `{type:'object'}`，任何对象都能过安全例 | 恢复内容断言：正例 `CONTAINS 'EX06_J9_PIN'`（锚定输入回显），安全例 `CONTAINS '拒绝'`（锚定拒绝措辞） | `joint-j9.ps1` |

## 2 验证

1. **语法**：`powershell -File .tools/check-ps.ps1 <4 文件>` → 4/4 `OK`；四个文件仍为 UTF-8 BOM + CRLF（`crlf_crlf=0`、`bare_lf=0`）。
2. **manifest 优先级（红 → 绿）**：同一份只含 manifest 的调用，修复前 `-OntologyBase` 落默认 `127.0.0.1:18182` 并报 `无法连接到远程服务器`（`J4 verdict=FAIL`）；修复后同一命令打 manifest 里的 `18282`，桩返回清单 → `J4 verdict=PASS`、`JOINT totals pass=1`、`EXIT=0`。
3. **keyRef 接通**：上述 PASS 的 `[RUN]` 行显示 `-AuthRef "env:AGENT_SECRET_JOINT_CONSUMER_KEY"`（来自 manifest `consumerKeyRef`），`-ReadKey <redacted>` 由 `AGENT_SECRET_ONTO_ENGINE_READ_KEY` 解析 ⇒ 只给 manifest + 环境变量即可跑，不依赖手传明文。
4. **P1-1 覆盖**：`Prepare-Joint.ps1` 在调用 `Invoke-JointAcceptance.ps1` 前写 `manifest.pre.json` 并传 `-ManifestPath`/`-CredentialsFile`；`Invoke` 侧 `revocationFixtures→$script:Revocation`、`isolationFixtures` 的映射在修复前已存在，本轮补的是 keyRef 与优先级。

## 3 未覆盖

- 真实双仓 `-Stage All`（J1–J9 全程）：需要 OR 18182 + AP 18080 与真实 release/评测环境；本轮只用桩验证 J4 与 manifest 传递。
- J7/J8/J9 的真实服务跑：J7 需要临时凭据吊销链、J8 需要两个空间的真实 binder、J9 需要真实评测数据集；脚本侧语义已改，运行证据待联合验收。
- J9 安全例的拒绝措辞（`拒绝`）依赖被测应用提示词；真实模型评测若措辞不同，需按应用调整 `$safetyExpected` 并在证据里写明。

---

# 第三轮复审（2026-11-02 17:5x，基线 AP `92b043c7`）

第三版 `复审问题.md`：EX-12/26/46 维持待验收；EX-06 仍有 3 处阻断，口径 13 完成 / 32 待验收 / 1 进行中 / 3 后置。本轮仍只改验收脚本。

## 4 三条与修法

| # | 复审问题 | 修法 | 文件 |
| --- | --- | --- | --- |
| R1 | `CredentialsFile` 只登记路径、未装载内容：只给 manifest＋凭据文件时 keyRef 解析不到临时密钥，J7 必 BLOCKED | 在 keyRef 解析前真正读文件：逐行 `^(AGENT_SECRET_[A-Z0-9_]+)=(.+)$` 校验、重复键与格式非法 `[FAIL]` 退出 1、文件缺失 `[FAIL]` 退出 1，装好的表供 `Resolve-JointCredential` 用 | `Invoke-JointAcceptance.ps1` |
| R2 | J9 安全例 `safety=true` 配 `CONTAINS`，产品只允许 EXACT/SCHEMA，真实提交 400 | 安全例改 `EXACT` + `expected='拒绝'`（保留内容约束且合法），注释引用 `EvaluationAssertions.java:31/135` | `joint-j9.ps1` |
| R3 | J8 机器 key 打人登录面实际回 `401 UNAUTHORIZED`，脚本强要求 `SERVICE_CREDENTIAL_REQUIRED` 会误判 | 断言改 401 且 code ∈ {`UNAUTHORIZED`,`SERVICE_CREDENTIAL_REQUIRED`}，失败文案同步 | `joint-j8.ps1` |

## 5 验证

1. **Parser**：3 个 `.ps1` `check-ps` 3/3 `OK`。
2. **只给 manifest + credentials.env（不设任何 key 环境变量）跑 J4**：`JOINT verdict=PASS`、`totals pass=1`、退出码 0 —— 修前这条路径取不到只读 key。
3. **负例 A（凭据文件缺 `AGENT_SECRET_ONTO_ENGINE_READ_KEY`）**：`[SKIPPED-BLOCKED] J4: 缺外部输入: 只读key(...)`、退出码 3（缺输入不发起调用）。
4. **负例 B（凭据文件写成 `NOT_A_SECRET=1`）**：`[FAIL] 凭据文件格式非法（需 AGENT_SECRET_<NAME>=<value>）: NOT_A_SECRET=1`、退出码 1。

## 6 环境坑（值得记住）

同一端口出现两个 `LISTENING` 进程时（`netstat -ano` 两个 PID），旧桩仍会应答并返回 401，表现像“凭据没读到”。正确做法：先把该端口所有 `LISTENING` PID `taskkill //F`，确认只剩一个实例再跑，否则会误判修复失败。

## 7 未覆盖

真实双仓 `-Stage All`（J1–J9）、J7 吊销链、J8 双空间 binder、J9 真实评测数据集；J9 安全例原先的 `拒绝` 文案已改为 `EX06_REFUSE` 常量 Schema（见第四轮 §8）。

---

# 第四轮复审（2026-11-02 18:0x，基线 AP `a5d6848d`）

第四版 `复审问题.md`：EX-12/26/46 维持待验收；EX-06 仍有 2 处功能阻断 + 1 处日志问题。本轮仍只改验收脚本。

## 8 三条与修法

| # | 复审问题 | 修法 |
| --- | --- | --- |
| P1·J9 | 安全例改成合法 EXACT 但 expected 是字符串 `拒绝`，而运行输出是 `{text,usage,actualModel}` 对象；默认模型桩固定回答 `EX06_REFUSE`/`EX06_OK` | 两个用例都改用 text 常量 Schema（`{type:'object',required:['text'],properties:{text:{type:'string',const:…}}}`，形状取自既有候选实现），断言统一 `SCHEMA`；正例 message 用 `EX06_TOOL:<pinAppCode>`、const `EX06_OK`，安全例 const `EX06_REFUSE` |
| P1·J8 | §7 用人登录管理账号打 B 空间，而夹具里该账号同时是 A/B 的 OWNER，只能证明“端点未授权”（`ENDPOINT_FORBIDDEN`），证明不了空间隔离 | 改用 **A 空间自己的 `ontosa_` binder 凭据**（只对 A 有权）调 B 的 release 执行入口；稳定拒绝码集合补 `ENDPOINT_FORBIDDEN`（另两种为 `NO_PERMISSION`/`RELEASE_NOT_FOUND`），仍断言 `data` 为空 |
| P2·日志 | 凭据文件格式错误分支回显原行前 24 字符，误贴裸密钥会泄露片段 | 改为只报行号与固定错误码 `JOINT_CREDENTIALS_FORMAT_INVALID`，不再回显任何内容 |

## 9 验证

1. Parser：三个 `.ps1` `check-ps` 3/3 `OK`（BOM+CRLF 保留）。
2. P2 实测：凭据文件写成 `ontosa_FAKE_SECRET_SHOULD_NOT_LEAK_0123456789` → 输出 `[FAIL] JOINT_CREDENTIALS_FORMAT_INVALID: 第 1 行不符合 AGENT_SECRET_<NAME>=<value>`、退出码 1，**无任何内容片段**。
3. J9/J8 只能静态核对：J9 的 `expected` 已是对象 Schema（用 `PSScriptAnalyzer` 外的方式无法再跑真实评测，需真实模型与数据集）；J8 的 §7 请求头已从 `Authorization: Bearer <admin>` 换成 `X-API-Key: <A binder>`，稳定码集合含 `ENDPOINT_FORBIDDEN`。

## 10 仍未闭合（验收输入）

真实双仓 `-Stage All`（J1–J9）、J7 吊销链、J8 双空间 binder 与 A-only 主体的真实账号、J9 真实评测数据集与真实模型；J9 两个用例的 const 值依赖默认模型桩的回答（`EX06_OK`/`EX06_REFUSE`），换成真实模型时必须改评测计划（`-EvaluationPlanPath`）。

---

# 第五轮复审（2026-11-02 18:2x，基线 AP `8bc60e3e`）

第五版 `复审问题.md` 收敛为两处：J8 走错鉴权面、J9 把应用编码当技能编码。

## 11 两条与修法

| # | 复审问题 | 修法 |
| --- | --- | --- |
| P1·J8 | 仍用 `ontosa_` binder 调 `/onto/internal/releases/...`（人登录面），实际 401，脚本却要 403/404；证明不了跨空间消费 | §7 改为 **A 空间的 `ontopa_` 消费凭据** 打 **`POST /onto/r/{B 空间 code}/releases/{B release}/ops/{op}`**，断言 **403 `NO_PERMISSION` 且无 `data`**；内部面的 401 负例独立保留在原 §7b（binder key 打 `/onto/internal` 必须 401）。新增 `-ConsumerKey`/`-ConsumerKeyEnv`/`-IsolationSpaceCode`，`Invoke-JointAcceptance.ps1` 从 `consumerKeyRef` 解析并向下传 |
| P1·J9 | `EX06_TOOL:<PinAppCode>` 的后缀被模型桩解释为技能编码，而默认应用编码≠技能编码，会触发 `FIXTURE_TOOL_UNKNOWN/NOT_OFFERED` | 读完 pinApp 后从 `config.skillRefs[0].code` 取真实技能编码，仅在 `modelMode=TestFixture` 时改写 `j9_pin` 的 `inputs.message = 'EX06_TOOL:<skillCode>'`；`skillRefs` 为空则 BLOCKED（不猜）。Schema 常量断言（`EX06_OK`/`EX06_REFUSE`）保持不变 |

## 12 验证

1. Parser：三个 `.ps1` `check-ps` 3/3 `OK`。
2. J9 逻辑单测（`$config.skillRefs=@(@{code='joint_unit_op'})`）：`skillCode=joint_unit_op`、`pinMessage=EX06_TOOL:joint_unit_op`、`safetyMessage=EX06_SAFETY` —— 证明只改写正例且用真实技能编码。
3. J8 静态核对：`§7` 的 URL 组装为 `/onto/r/{B spaceCode}/releases/{B releaseId}/ops/{opCode}`、Header `X-API-Key: <A consumer>`、断言 `403 + NO_PERMISSION + data 为空`；`§7b` 保持 `401 + UNAUTHORIZED/SERVICE_CREDENTIAL_REQUIRED`。

## 13 仍未闭合（验收输入）

真实 A 空间 `ontopa_` 消费凭据与 B 空间 code/release/op（`isolationFixtures` 已有 `spaceCode`，缺真实消费凭据时该腿记 `SKIPPED-BLOCKED`）、真实双仓 `-Stage All`、J7 吊销链、J9 真实评测数据集与真实模型。

---

# 第六轮复审（2026-11-02 18:4x，基线 AP `5c47a52f`）

第六版 `复审问题.md`：**J8 缺输入时可能误报通过**——`§7` 在缺 `-ConsumerKey`/`-IsolationSpaceCode` 时只打印 `SKIPPED-BLOCKED`、不改变最终状态，其余检查通过后 J8 仍退出 0，统一入口记为 PASS。

## 14 修法

| 位置 | 修法 |
| --- | --- |
| `Invoke-JointAcceptance.ps1` 的 `Get-JointStageRequirements('J8')` | 增加两条必需输入：`IsolationSpaceCode`（含形状 `^[A-Za-z][A-Za-z0-9_-]*$`）与 `ConsumerKey`（`^ontopa_[A-Za-z0-9_-]{43}$`）；缺任一 → 该段 `SKIPPED-BLOCKED` 且 **verdict=BLOCKED、退出码 3**，不再进入 `joint-j8.ps1` |
| `joint-j8.ps1` 前置 `$Missing` | 同样把两条列为必需（含“B 空间 code 必须不同于 A 空间 code”与 `ontopa_` 形状校验），缺任一 → `Complete-Stage 'J8' 'BLOCKED'` |
| `joint-j8.ps1` §7 兜底分支 | 原本静默 `SKIPPED-BLOCKED` 后继续；改为 `Add-Check 'J8-跨空间消费输入齐备' $false` + `$Verdict='FAIL'`（前置已强制这两个输入，走到这里只能是状态不一致，不能算通过） |
| `Invoke-JointAcceptance.ps1` 的 `$secretKeys` | 补 `ConsumerKey`：`[RUN]` 行此前会原样打印 `ontopa_…` 消费凭据（本轮实测发现），属凭据泄漏，一并修掉 |

## 15 验证

1. Parser：两个 `.ps1` `check-ps` 2/2 `OK`。
2. **缺输入（只缺这两条，其余全部给齐的假值）**：`[BLOCKED] J8 未执行：缺外部输入: IsolationSpaceCode(B空间code); A空间ontopa_消费凭据(-ConsumerKey 或 AGENT_SECRET_JOINT_CONSUMER_KEY)`，`joint-summary.json` 中 `verdict=BLOCKED`、`J8 SKIPPED-BLOCKED`，**进程退出码 3**。
3. **输入齐备（指向未启动的 18182 桩地址）**：不再 BLOCKED，直接进入 `joint-j8.ps1` 并因连接被拒 `FAIL` —— 证明两条输入已真正成为执行前提，而不是“打印一行继续走”。
4. **脱敏**：`[RUN]` 行显示 `-ConsumerKey <redacted>`。

## 16 仍未闭合（验收输入）

真实 A 空间 `ontopa_` 消费凭据、B 空间 `spaceCode/release/op`、真实双仓 `-Stage All`、J7 吊销链、J9 真实评测数据集与模型。
