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

真实双仓 `-Stage All`（J1–J9）、J7 吊销链、J8 双空间 binder、J9 真实评测数据集；J9 安全例的 `拒绝` 文案需与被测应用提示词一致，否则真实模型会按 EXACT 判失败（这属验收输入问题，脚本不再放宽）。
