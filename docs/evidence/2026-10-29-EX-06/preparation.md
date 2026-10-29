# EX-06 最小实施与准入准备

2026-10-29，`algorithm_interrupt_readonly` 静态准备。EX-05 已由主线程记录两仓提交：AP `824294a451f2a7a8a5986183897358343a56525d`、OR `f192996`。**本文件是待主线程采纳的接口/范围说明，不是脚本交付或实跑报告。** 未运行 API、PG、Java/Maven、测试或服务，没有读取密钥；本次仅新增本文件，不使用 Superpower。

## 1. 先决条件与当前白名单

- EX-06 硬依赖 EX-01/02/03/05；完整 J1～J9、S1 由 EX-07 执行，J4 投影扩展还依赖 EX-04。未关闭的依赖不能由夹具绿替代。
- 卡片现允许新增 AP `scripts/acceptance/Prepare-Joint.ps1`、`Invoke-JointAcceptance.ps1`、`Invoke-S1Acceptance.ps1` 及本卡 JSON 请求样例；三个脚本当前均不存在，不能直接照手册调用。
- OR `deploy/Smoke-Release.mjs` 只读借用发布顺序。它末尾会 deprecate release、revoke 凭据并注销，**不能直接执行后把其结果作为联合主夹具**。
- 仅这三个 PS 文件加 JSON 不足以实现可检查的负例与 S1 HTTP 模型/异步/故障桩。建议主线程先精确认领：本卡证据内 `Test-JointPreparation.ps1`（测试驱动）及 `scripts/acceptance/fixtures/Joint-ProtocolFixture.mjs`（如现有入口不能复用才新增）。后者只做 loopback、有界请求/等待、实际退出可核的验收协议桩，分别标记 Model/Async/Fault；不新增产品服务、执行器或业务框架。JSON 样例放同一 fixtures 子目录，不含真实密钥。**本文件不擅自扩大写入授权。**

## 2. 三入口候选接口

| 入口 | 必需参数/最小行为 |
|---|---|
| `Prepare-Joint.ps1` | 沿手册 `-SessionId -OntologyBaseUrl -PlatformTenant -Environment -OutputDirectory`；默认本机 18182 根 URL、local-v12、production。建议增加 `-PlatformBaseUrl`（默认 18080）和显式 `-ReuseManifestPath`，便于可核的复用；既有 manifest 未显式复用则拒绝覆盖。 |
| `Invoke-JointAcceptance.ps1` | `-ManifestPath -EvidenceDirectory`，逐 J 用例输出 PASS/FAIL/BLOCKED 及实际阶段；固定凭据用途，不暗改配置或草稿救场。 |
| `Invoke-S1Acceptance.ps1` | 上述二参数加显式 `-ModelMode TestFixture\|Real`，按 S1 手册 A/B/C/D 分项执行。TestFixture 只说明协议/确定性行为；Real 必须有真实可用 modelEntryId。 |

主入口、测试驱动与新 PS 均用 Windows PowerShell 5.1 可解析语法和 UTF-8 BOM；先 ParseFile，再安排实际窗。S1 辅助手册尚有“以下均为 PowerShell 7”旧口径，实施时由主线程同步文档，不因此要求本机安装 pwsh。每个脚本必须有有限 HTTP/轮询/子进程总预算、finally 环境恢复及固定安全失败码。原生退出码与采集状态分别落盘；禁止失败后继续生成“READY manifest”。

### manifest 十九个主字段

```text
sessionId, platformTenant, environment, spaceId, spaceCode,
releaseId, replacementReleaseId, appCode, pinAppCode, explicitAppCode,
opCode, readCredentialId, binderCredentialId, consumerCredentialId,
readKeyRef, binderKeyRef, consumerKeyRef, platformBaseUrl, ontologyBaseUrl
```

`spaceId/releaseId` 是 UUID，`spaceCode` 是消费路由代码，不互换；URL 取服务根，不重复追加 `/onto`。manifest 只含 ID/URL/ref/环境，不能含 key、token、密码或带凭据原始响应。另有卡片已要求的 `revocationFixtures` 独立主体/临时三凭据组；建议以小子对象记录本次 principalId/endpointId/发布版本及 candidateSha，便于恢复，不复制业务事实表。

完整 J8 还需受本会话所有的 `isolationFixtures`：B 空间及真实可消费 release/端点，而不能对随机不存在 UUID 测拒绝后宣称空间隔离。A 服务 key 请求不同 platformTenant 的绑定可直接验证拒绝，不需伪造 AP 用户；若验 AP 第二租户主体，则另需真实已验身份，开发入口不能自选 tenant/roles。此附加夹具字段及资源范围先由主线程冻结。

## 3. 可直接复用的最小顺序

1. **服务与归属。** 沿联合手册 §3.0 核本仓 PG/MinIO/进程/JAR 后使用 OR 原 Initialize/Start-Storage/Start-Local -Build；保留已有配置，不接管不明监听。OR `/onto/auth/me` 未登录 401 只证明 HTTP 响应。AP 原 stop/start-backend 从当前源重建，六健康后取 `/auth/dev-token` 的 `data.accessToken`，核实际 local-v12/developer-v12。原 Smoke 使用 region=A，本地 dev-token 实际固定 region=LOCAL，联合守卫/授予/样例须据真实身份统一，不能靠头部扩大授权。
2. **OR 新内容与两版。** 用本仓管理会话建独立空间/模型/operation/guards/assistant/test-case；每次写取得新 draftRev。沿 Smoke 的 candidate→validate→tests→同 revision/sha OWNER review→prepare READY_TO_COMMIT→commit→READY，核四制品摘要。再有意修改本会话草稿发布 replacementRelease，保留旧版，不弃用主 release。两个 release 的实际 endpoint 均按本次消费主体授予，否则 J5 换版会混入端点授权缺失。J8 的 B 夹具单独记录，不冒充 A 的 replacement。
3. **OR 凭据/角色。** `POST /onto/platform-actors` 的真实字段是 `kind`。readonly/binder 各 `kind=SERVICE_ACCOUNT`，带 environment、deptScope、attributes，用不同 platformActor；不可指定 principalId，服务端自动创建独立 principal+VIEWER+READONLY。由返回的 binder principalId 经 `/onto/system/users`、`/roles` 找当前 roleIds/revision，仅给该主体追加 BINDER，PUT 用户时保留原状态/角色并带 expectedRevision。消费 `kind=PLATFORM_ACTOR` 需 ACTIVE 本地 principal、空间/端点授予、deptScope/attributes，不带非空 environment；不能拿旧 `/api-credentials` 的 key 代替 ontopa。J7 另建临时组，不能撤主三凭据。
4. **AP 环境隔离。** 复用 EX-01 的 `start-backend.ps1 -ServiceEnvironmentFiles`：app.env 只带 binder ref，engine.env 只带 readonly ref，skill.env 只带 consumer ref；每个子进程的 `AGENT_SECRET_BINDINGS` 都绑定已验 local-v12。现有 SecretVault 支持 env:AGENT_SECRET_* / vault:，没有通用创建 vault 的公开管理入口，最小用受限 env 文件，不新造 API。两个进程的 `AGENT_ONTOLOGY_URL` 为 18182 根；pin enabled/record 与 resolution 开关须实核。先建立受限输出目录、验证可写，再签发一次性 key；写密钥文件失败即停止并记录本次 credential ID，不打印/重签覆盖旧主凭据。
5. **AP 三应用与真实门禁。** 复用 `/admin/apps`、config/flows、正式 manifest 导入、原连接/选型/评测/precheck/commit。appCode 的冻结 ontology 仅 space，不含 releaseId，手工 binder 建其初始 pin；pinAppCode 含 space+releaseId，保留真正发布触发 pin 给 J9；explicitAppCode 固定旧 release，J5 改远端 pin 而不改其冻结配置。不假设 POST run 能覆盖 ontology，不用 `seed-sample-agent.ps1` 的“建草稿”冒称已发布，也不静默选第一个模型。发布未成功只保存安全阶段日志，不输出完成 manifest。
6. **复跑/执行。** 首次资源 ID 与会话一致后才原子写 manifest；断点日志只存安全 ID/阶段。显式复用时核资源仍存在、READY/ACTIVE、主体和 ref 可用；不因同环境唯一键冲突就撤旧凭据重建。J1/J2 基础负正例→J3/J4→J5/J6→临时 J7→J8→J9；本体绑定缓存 30s、context-pack 缓存 5min 分别观察，有限等待，不能宣称缓存命中时即时远端重鉴权。异常记录并关联原卡，不在验收脚本修生产或清旧队列。

## 4. 首个红测及后续最小测试集

建议先以**输出已存在且未显式复用时必须拒绝**建立首个无业务副作用红测，再做真实 HTTP 协议夹具。复用 `scripts/Test-ServiceEnvironment.ps1` 的 AST 提取生产纯函数方式，测试只装载新入口的准备计划/manifest 校验函数，禁止执行顶层启动器或读取真实 `.env`。测试驱动及可解析、可调用入口先落盘；缺文件/函数、Parse 错误只记准备失败，**不计目标实红**。

拟新增驱动认领后的首条命令（本次未执行，实际参数随驱动冻结）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  "$ApRoot/docs/evidence/2026-10-29-EX-06/Test-JointPreparation.ps1" `
  -Case ExistingManifestCollision `
  -EvidenceDirectory "$ApRoot/.local/acceptance/test-$RunId"
```

用 JSON 样例在本仓独立测试目录放一个既有 manifest，记录其 SHA；调用真实准备计划/校验入口。目标红必须是“错误允许 Create/覆盖，而不是明确冲突拒绝”，保留具体断言/native；前后 manifest 字节、零网络/启动副作用均可核。绿后再加显式合法复用和跨 session/未知资源拒绝。若首个实现已正确拒绝，就如实记绿，不能放松规则或故意删守卫造红；转下一已定义失败路径。

随后只补必要表驱动向量：

| 正/负向量 | 必须证明 |
|---|---|
| 合法 19 字段、显式同 session 复用 | 同资源映射、不重复创建、不覆盖已编辑/已发布版本；丢失私密文件不能假装 ref 可用 |
| tenant 与实际 dev-token 不符、同 principal 冒 readonly/binder、普通 key 写绑定 | 首次写前拒绝/403；无业务写、无新增绑定，不能仅测试请求头自报身份 |
| 业务请求成功但 publication 未 READY、制品不齐、授权/保存环境失败 | 阶段明确、没有最终 manifest/伪成功；一次签发明文响应不入日志 |
| J5/J6/J7/J8 | 显式旧配置优先；真 BINDING_NOT_FOUND 与其它 404/5xx 分开；临时撤权不破坏主 key；实际 B 资源越界拒绝 |
| S1 TestFixture 与 Real | 固定输出正文/元数据支持 CONTAINS 与 EXACT 对照；独立候选隔离报告/豁免；SUBMIT_QUERY 协议、真实内部唤醒票据和终态分段；Real 缺输入只阻对应项 |

HTTP/模型/异步协议桩只验协议与装配，不能当真实 OR/真实模型/worker 绿。真正 J1～J9 与 S1 的执行和逐例验收仍按 EX-07，不用一组单测替代。

## 5. 启动门与证据

主线程下一步仅需：关闭 EX-05并冻结上述额外测试/夹具文件及参数→实现测试驱动/薄入口并采首个有效红→最小实现→同源定向绿→受控真实 Prepare-Joint→完整 J/S1 交 EX-07。准备脚本不管理另一套数据库/发布生命周期，OR 模板只用现有 API。

本地 OR 部署、新空间/READY release、授予、签 key、确定性协议桩均为我方可准备事项，不一律报外部阻塞。真正外部输入是 Real 模型端点/访问权、真实算法 worker X-1、外部桥 X-3、真实 IAM 或五类外源，仅阻对应路径；管理员确无授权或本仓部署实际失败时保留真实落点。所有证据以新 session/RunId 归档，记录源/JAR/PID/环境安全指纹、命令/native/阶段、实际 ID 与未覆盖项；撤销/清理只针对本会话资源，保留旧业务及失败原件。
