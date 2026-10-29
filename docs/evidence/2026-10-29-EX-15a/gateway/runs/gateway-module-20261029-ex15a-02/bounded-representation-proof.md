# EX-15a gateway full：11 项有界表示差异证明

审阅者：ex04_pg_scope。仅纯文件读取/比较；未运行业务，未修改原 raw、summary、测试、产品或索引。

结论：原 15 项非通过中，11 项已逐字段证明只有下列精确字面变化，3 项所有字段原文相同，1 项缺类名称变化另交技术归因。11 项的 FAIL/ERROR 状态没有改变，本证明不是全模块或整卡通过。

## 输入与不可变范围

- baseline 原件 SHA256：`5cf446e1387bbc3cd14938dc1bd48ef13b0ac97817eeae9ff8c044f7509f7c00`。
- actual 原件 SHA256：`995164c24b3d16d2edd6064faa206a996c87dac20d4d0d0caa554722e29cc8ba`。
- 原 summary SHA256：`c5c5d72ef44451242baacb1717b6c7daca0e101f39ce2a720dc1cf877857e546`。
- 读取前后上述文件与 fixture 源 SHA 全部相同。两个私有 raw 路径、源路径及源 SHA 见同名 JSON；公开证明不复制完整原因文本。
- Portal fixture 的 focused-/epoch- UUID 来自当前测试第 120/155 行；差异仅位于相应路径的 `engine-ready.json` 等待失败文本，`child alive=false` 及全部栈保留。

## 精确替换范围

只在指定方法的 message/detail 进行内存中旧→新字面值替换，不写回原件；每值每字段均恰好一次，旧值在 actual 为零、新值在 baseline 为零，位置不变。完整字段剩余字符按 Ordinal 比较；无 UUID/hex 通配归一化，不删除栈/行号/原因，不改换行。22 个字段共 34 处字面替换后全文相同，60 个原 message/detail SHA 均已重算核真。

| Portal 方法 | baseline fixture UUID | actual fixture UUID | message/detail 次数 |
|---|---|---|---|
| `exactPayloadAndRealRenewal` | `27bf11c7-0c74-4036-812f-239b1ca3fd67` | `e34731b4-625a-4c3c-80cd-5c7999cd497e` | 各 1 |
| `independentJoinedChainDeniesBeforeMaterialAndRelaysRealEngineOutput` | `67dbd879-566d-40b3-90b2-24e7295d65ea` | `4d96af55-097c-4d8d-8bfe-42bcca9c7299` | 各 1 |
| `retainedStreamControlledExpiry` | `bf73b54b-e5c3-4d36-8516-ae9c17225ea3` | `112e5d23-a404-45ee-bb74-94f42731efa5` | 各 1 |
| `retainedTerminalHistoryScopeInventory` | `3a6e8f6b-3246-42af-a3d8-33c833207767` | `0cdf3a6e-8109-4991-82c0-bdb54ab565fa` | 各 1 |
| `slowReaderCurrentAuthorityAndExpiry` | `738d60b3-b7a1-4afa-b58c-2277ca87557d` | `68719f44-85c3-4ba0-8a78-56113c832c4f` | 各 1 |

IntegrationSecurityTest 的下列 6 方法，message/detail 每字段恰有以下两项，各 1 次：

- `WebMergedContextConfiguration@33d7765a` → `WebMergedContextConfiguration@3c28e5b6`。
- `ImportsContextCustomizer@69ed5ea2` → `ImportsContextCustomizer@7558c24b`。

- `directServiceApiAuthenticatesApiKeyThenPassesOnlyScopedInternalTicket`。
- `revocationAndPolicyChangeImmediatelyRejectExistingTicket`。
- `refusesInvalidSecretBeforeIssuingTicket`。
- `validTicketCannotAccessAdminOrOtherAppOrOtherAppRun`。
- `corsAllowsRegisteredOriginAndRejectsUnknownOrigin`。
- `boundedEngineRetryAfterSurvivesGatewayWithoutOpeningOtherHeaders`。

## 原 15 项身份矩阵

| 完整身份 | 状态 | 结论 |
|---|---|---|
| `cn.agentplatform.common.authority.GatewayRunStatusChainPostgresTest#realGatewayQueryAndStatusReturnOriginalBytes` | FAIL | 13 字段全部原文相同 |
| `cn.agentplatform.gateway.application.PublishedMetadataSmokeLauncherTest#launchTwoIsolatedJvmFixturesWithFiniteDeadline` | FAIL | 缺类名称变化：排除，另查 |
| `cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest#slowReaderCurrentAuthorityAndExpiry` | FAIL | 限定 fixture UUID 表示差异 |
| `cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest#retainedStreamControlledExpiry` | FAIL | 限定 fixture UUID 表示差异 |
| `cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest#exactPayloadAndRealRenewal` | FAIL | 限定 fixture UUID 表示差异 |
| `cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest#independentJoinedChainDeniesBeforeMaterialAndRelaysRealEngineOutput` | FAIL | 限定 fixture UUID 表示差异 |
| `cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest#retainedTerminalHistoryScopeInventory` | FAIL | 限定 fixture UUID 表示差异 |
| `cn.agentplatform.gateway.infra.PortalRunEventsSseAutomaticCleanupTest#actualCompletionAutomaticallyRefundsAfterTransportHelperReturns` | ERROR | 13 字段全部原文相同 |
| `cn.agentplatform.gateway.infra.PortalRunEventsSseResetCleanupTest#terminalResetAutomaticallyRefundsWhenReaderAbortOverlapsCompletion` | ERROR | 13 字段全部原文相同 |
| `cn.agentplatform.gateway.IntegrationSecurityTest#directServiceApiAuthenticatesApiKeyThenPassesOnlyScopedInternalTicket` | ERROR | 限定两个 Spring 对象地址表示差异 |
| `cn.agentplatform.gateway.IntegrationSecurityTest#revocationAndPolicyChangeImmediatelyRejectExistingTicket` | ERROR | 限定两个 Spring 对象地址表示差异 |
| `cn.agentplatform.gateway.IntegrationSecurityTest#refusesInvalidSecretBeforeIssuingTicket` | ERROR | 限定两个 Spring 对象地址表示差异 |
| `cn.agentplatform.gateway.IntegrationSecurityTest#validTicketCannotAccessAdminOrOtherAppOrOtherAppRun` | ERROR | 限定两个 Spring 对象地址表示差异 |
| `cn.agentplatform.gateway.IntegrationSecurityTest#corsAllowsRegisteredOriginAndRejectsUnknownOrigin` | ERROR | 限定两个 Spring 对象地址表示差异 |
| `cn.agentplatform.gateway.IntegrationSecurityTest#boundedEngineRetryAfterSurvivesGatewayWithoutOpeningOtherHeaders` | ERROR | 限定两个 Spring 对象地址表示差异 |

JSON 对每个身份列出逐字段字节数、原 SHA、精确字面值/次数/偏移、替换后的 SHA、其余全文相同结论。全部 15 项 key/classname/method/status/type/causes/firstProjectFrame/classEvent/xml 九字段相同；11 项只有 message/detail 及其派生 SHA 四字段变化。

## 门禁仍保留

原 summary 保持 28 XML / 299=284P7F8E、identityGate=true、sseAnd49ChildGate=true、sourceStable=true、nativeMavenExit=1 且 noFailureIgnore=true。original15RawReasonGate=false、regressionGate=false、allTestsPassed=false、rawReasonsNormalized=false 均原样保留。四库 After/history 由主线程另行核验，不属于本文件纯原因比较的证明范围。

`PublishedMetadataSmokeLauncherTest#launchTwoIsolatedJvmFixturesWithFiniteDeadline` 的缺类名称变化不属于表示随机量，本文没有给它做替换或判定同因；由另一审阅者单独归因后，主线程才能判断其含义。该项不能因另外 14 项可解释而被自动接受。

证明 JSON SHA256：`e6817b0da75e6681886ffad7861ef80ee5ddd4f088f61e4d13326a07b77a4f78`。
