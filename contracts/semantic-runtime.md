# 语义运行时契约——最小只读（SEM-01.1 工程增补，2026-11-03，v0.1 草案）

> 状态：**草案，未激活**。新增工程增补，不改四契约原文、`capability-package.schema.json` v1、`assistant-integration.md`/`configuration-profile.md`（INT-01 v0.1）及任何历史发布物；激活时按 `freeze.json` engineeringAddenda 模式登记。
> 唯一归属：SEM-01。本文件只冻结**只读（read-only）profile** 的最小完整契约；判定/条件/目标处置等后续 profile 的封闭 Schema 与能力标识属 SEM-01.2，未实现前不得启用，也不得借本契约宣称规则求值已交付。
> 术语按[综合分析§6.1六项术语表](../../文档/优化方案/02-本轮升级依据/本体与智能体运行时协同-综合分析与提升思路-20261103.md)：语义扩展/语义绑定/语义事实/语义条件/语义回执/目标判据。OR 侧对应增补见 `代码/ontorepo/contracts/semantic-runtime.md`（同日 v0.1，同一 schemaVersion）。

## 1. 范围与不做什么

| 本契约冻结（M1a 只读链） | 本契约不做 |
| --- | --- |
| 只读 profile 声明、语义绑定引用、对象引用、事实条目/事实集、证据引用、版本四元组、时效、结果状态与错误语义、兼容规则 | 规则判定与 decisionRef（纯事实查询不产生）；执行条件、目标判据、写入路径；新连接/身份/审批/任务权威；对冻结 CapabilityPackage v1 的任何字段扩展 |
| 拒绝假主体、未知方言、非法引用、越界字段、摘要失配的确定错误 | 通用 Object Query、多源关联、变化回流（SEM-10～13） |

普通无语义 HTTP/文件/算法应用继续原能力路径，不强制经本体。

## 2. 对象模型（字段唯一归属；实现不得增删顶层字段，演进=新 schemaVersion）

### 2.1 版本四元组 `releaseRef`（必填，语义事实的冻结来源）

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `spaceCode` | string | ≤64，`^[A-Za-z0-9._-]+$` |
| `releaseId` | string(uuid) | **OR 正式发布 id 为 UUID**（openapi `/onto/r/{spaceCode}/releases/{releaseId}` 实测） |
| `version` | string | ≤64，正式版本号 |
| `contentDigest` | string | 64位小写hex；**按 manifest 声明核对的本体 bundle 摘要**（OR `ontologyMetaPath=$.meta` 所指），不与投影摘要混比 |

### 2.2 绑定引用 `bindingRef`（必填）

`skillCode`（≤64）、`skillVersion`（整数≥1）、可选 `skillSha256`（64hex）、可选 `extensionDigest`（≤128，语义扩展制品摘要，SEM-02 交付前常缺）、可选 `configReleaseId`（INT-03 交付前常缺）。缺省字段为**未交付**，不是空值伪造。

### 2.3 有类型对象引用 `ObjectReference`（`refKind` 分支必填）

- `space-object`：`spaceCode` + `objectType`（≤64）+ `stableId`（≤128，OR 稳定业务身份）
- `external`：`sourceSystem`（≤64）+ `externalId`（≤128）——**必须先经服务端解析/授权**，解析失败按 `SEMANTIC_REF_INVALID`/越权策略拒绝，不泄漏未授权对象存在性

引用不是权限票据：tenant/principal/授权一律来自服务端已验证上下文（沿用 `current-identity-mapping.md`），不由引用字段或页面身份自报。

### 2.4 事实条目 `FactItem`（`valueState` 四态严格区分）

| 字段 | 规则 |
| --- | --- |
| `objectRef` | 必填，ObjectReference |
| `factKind` | `attribute`（需 `attribute` 名 ≤64）或 `relation`（需 `relation` 名 ≤64） |
| `valueState` | `PRESENT`（attribute 需 `value`；relation 需 `relatedObjectRef`）/ `NULL`（`value` 必为 null）/ `ABSENT`（禁止 `value`）/ `UNKNOWN`（禁止 `value`，需 `unknownReason` ≤256） |
| `value` | 仅标量：string（≤256）/number/boolean/null |
| `relatedObjectRef` | `relation` 且 `PRESENT` 时必填（关系的"值"即目标对象，不另填 `value`） |
| `observedAt` | 必填，观察时间（date-time） |

缺失事实不自动补 0/false/业务默认值；`ABSENT` 与 `UNKNOWN` 与 `NULL` 互斥且都不得写成 false。

### 2.5 事实集 `factSet`（可选；`status∈{OK,NO_MATCH,PARTIAL}` 时必填）

`queryRef`（≤256，记录受支持操作/映射引用）、`items[]`（≤64 条 FactItem）、可选 `factSetDigest`（≤128，规范化事实摘要；**规范化规则在 01.2 冻结前由实现按确定性序列化计算并在证据中登记算法名，不得冒充 `contentDigest`**）、可选 `sourceNote`（≤256）。

### 2.6 证据引用 `EvidenceRef`（`evidenceKind` 分支必填）

`call`：`callId`（必填）+可选 `runId`；`artifact`：`artifactId`（正整数，必填）+可选 `digest`；`business-record`：`recordSource`+`recordId`（均必填）。证据按**当前主体**授权可读；原始 SQL、源表、凭据、FULL 审计不是默认上下文。

### 2.7 时效 `timeliness`（必填）

`observedAt`（必填）；`freshness`：`SOURCE_STAMPED`（需 `dataAsOf` date-time，可选 `sourceVersion` ≤64）或 `UNKNOWN`（**需 `unknownReason` ≤256**）。**禁止把请求时间/now 填成源数据时点**；预检与写入之间的并发窗口仍依赖业务原生机制（SEM-01.2 后续 profile 另声明）。

### 2.8 结果状态与 decisionRef

`status`：`OK` / `NO_MATCH` / `PARTIAL`（需 `error[]` 说明缺项）/ `ERROR`（需 `error[]`，且禁止 `factSet`）。
**`decisionRef` 在 read-only profile 下 Schema 级禁止**：纯事实查询没有规则求值，任何 decisionRef/规则版本出现即非法；求值后的判定契约属 SEM-01.2/SEM-04。

## 3. 错误码最小登记集（前缀 `SEMANTIC_`，实现不得私造同义码；扩展回本文件登记）

| 错误码 | category | 语义 |
| --- | --- | --- |
| `SEMANTIC_PROFILE_UNKNOWN` | PARAM | 未知 profile/方言/未注册 schemaVersion |
| `SEMANTIC_REF_INVALID` | PARAM | 非法/歧义/未知对象引用 |
| `SEMANTIC_SUBJECT_UNVERIFIED` | AUTH | 未验证主体或伪造授权上下文 |
| `SEMANTIC_OBJECT_FORBIDDEN` | AUTH | 当前主体越权（不泄漏对象存在性） |
| `SEMANTIC_DIGEST_MISMATCH` | BUSINESS | 声明摘要与实际制品失配 |
| `SEMANTIC_FIELD_OUT_OF_RANGE` | PARAM | 字段越界/类型不符/分支缺失 |
| `SEMANTIC_SOURCE_UNAVAILABLE` | TRANSPORT | 批准来源不可达（如实阻塞，不造数据） |

category/retryable 语义沿用 error-envelope-boundaries.md；仅 429 retryable=true。

## 4. Schema 与校验边界

- 冻结草案：`contracts/semantic-runtime-v1.schema.json`（`$id: urn:agent-platform:semantic-runtime:1.0`，draft 2020-12）。
- 全部对象 `additionalProperties:false`；分支必填用 allOf+if/then；**禁远程 `$ref`**；生产端实现（SEM-03/05）必须用标准校验器（ajv 或等价），不得以自写脚本替代。
- 演进规则：兼容增补=同 `schemaVersion` 1.x 仅允许**收窄不动**已发布语义？否——本契约未激活即无兼容包袱：v1.0 激活后，任何字段语义变化或新增必填一律升 `schemaVersion` 并新文件+新 freeze 登记；旧记录按其自身 schemaVersion 永久可解释。
- 摘要语义各自独立：`contentDigest`（本体 bundle）≠`factSetDigest`（规范化事实投影）≠AP 侧契约摘要（skill sha256）≠扩展摘要；不得相互混比或忽略不相等字段。

## 5. 授权与撤权

1. 事实/证据读取按**当前主体**授权求值；撤权、停用、凭据失效优先于任何历史快照，旧引用不能恢复访问。
2. 用户手填 ID 先解析和授权；继承授权先于过滤/limit。
3. 缓存返回必须保留原观察时间与时效声明，不刷新为"当前"。

## 6. 与既有机制的共存

- SEM-05.0 已窄修的 `returnSpec.ontologyMetaPath` 提取继续按既有 returnSpec 语义工作；本契约是**新**语义运行时记录的契约，不追溯改写旧 `ontologyMeta` 投影，也不把任意根 meta 识别成本体元数据。
- OR 正式响应（`{code,data,meta}`）中的 meta 四元组是 `releaseRef` 的核对来源；`preview=true` 不得作为实际版本采集（SEM-05.0 既有拒绝语义延续）。
- OR 侧对应增补：`代码/ontorepo/contracts/semantic-runtime.md`（v0.1 同步稿），登记 OR 只读消费的同一 schemaVersion 与字段口径；OR 运行代码改动属 SEM-02.1/03.x 各自白名单，本子步不改 OR 代码。
