# 语义运行时标准接入包（SEM 升级线，v0.1 交付候选）

> 状态：**文档与脱敏 manifest 已交付；两类资源的真实交接、M1a 双端联合出口仍为 `BLOCKED`**，解锁条件见 §12。本文件描述“已实现并可复核的能力与配置口径”，**不表示生产环境已完成交接**，也不替代 `contracts/semantic-runtime.md` 正文。
>
> 适用范围：已完成语义绑定（SEM-02）、事实读取（SEM-03）、规则判定（SEM-04）、回执投影（SEM-05）、定义投影（SEM-06）、分发前条件复核（SEM-07）、目标读回（SEM-08）、配置/使用端（SEM-09）、对象查询与关联（SEM-10～12）、业务变化失效（SEM-13）的应用。
>
> 机器可读清单：同目录 `semantic-runtime-delivery.json`（脱敏，不含密钥、内部账号、连接串）。

## 1. 交付组成

| 组成 | 值 | 说明 |
| --- | --- | --- |
| 交付标识 | `semantic-runtime@0.1` | 草案期标识；契约冻结后随版本递增 |
| 运行契约 | `contracts/semantic-runtime.md` v0.1 | AP 仓为唯一规范源；OR 仓为消费口径登记 |
| 记录 Schema | `urn:agent-platform:semantic-runtime:1.0` | `contracts/semantic-runtime-v1.schema.json`（只读记录） |
| profile | `read-only@1.0`、`condition@1.0`、`decision@1.0`、`goal@1.0` | 四类独立信封；未实现 profile 返回 `SEMANTIC_PROFILE_UNKNOWN` |
| AP 交付提交 | `fd384653`、`c88c56bd`、`16b2266e`、`bbceccbc`、`836c6b7d`、`25fedc6c`、`2734ae2c`、`033926ce`、`48b1813b`、`e4198a9b`、`68f2ea66`、`6ab4ebac` | 语义契约/绑定/客户端/门禁/目标/配置端 |
| OR 交付提交 | `8f06c1f`、`ddea47a`、`68c6e48`、`036fd03`、`2ed467c`、`4ca2e74`、`f05757c`、`bc2c631`、`c003b00`、`6f05550`、`f218386`、`d0d05fd`、`e3e83c7` | 扩展/回执/事实/判定/定义/条件/查询/身份/关联/变化 |
| 本卡文档提交 | 见本轮回执 | 仅文档、manifest 与证据 |

> 提交号用于复核“文档写的是哪一版实现”，不是发布清单；部署以实际构建产物与其摘要为准。

## 2. 四类 profile 的最小字段

所有 profile 共用 `spaceCode/releaseId/version/contentDigest` 四元组与 §8 错误码，但**信封不共用**，不得把只读记录改写成判定/目标记录。

| profile | 关键字段 | 允许的副作用 | 当前状态 |
| --- | --- | --- | --- |
| `read-only@1.0` | `recordId/status/bindingRef/releaseRef/timeliness/objectRefs/factSet/evidenceRefs`；`decisionRef` Schema 级禁止 | 无 | 已实现（SEM-03/05） |
| `condition@1.0` | `conditions[]{conditionKind,targetRef,expected,result}`、`checkedAt`、`sourceRef`；`result∈HOLDS/NOT_HOLDS/UNKNOWN`，UNKNOWN 必填原因 | 无（求值不写业务事实） | 已实现（SEM-07） |
| `decision@1.0` | `decision{conclusion,ruleRef,ruleDigest,usedFactRefs,evaluatedAt}`、`decisionRef{ruleRef,ruleDigest,decisionId}` | 无 | 已实现（SEM-04） |
| `goal@1.0` | `goals[]{goalId,criterion,outcomeState,readbackRef,nextStep}`；`ACHIEVED` 必须有 call 回执 | 无（读回业务源） | 已实现（SEM-08）；Schema 文件与 §7 登记待 AP 收口 |

## 3. 已交付能力与入口

| 能力 | 端点/入口 | 方法 | 服务端权限 | 实现提交 |
| --- | --- | --- | --- | --- |
| 对象引用解析 | `/onto/releases/{releaseId}/semantic/facts/resolve` | POST | `service.execute` | `68c6e48`（OR）、`836c6b7d`（AP 客户端） |
| 单源事实读取 | `/onto/releases/{releaseId}/semantic/facts` | POST | `service.execute` | `036fd03`、`25fedc6c` |
| 规则判定 | `/onto/releases/{releaseId}/semantic/decisions` | POST | `service.execute` | `2ed467c`、`4ca2e74` |
| 条件求值 | `/onto/releases/{releaseId}/semantic/conditions/evaluate` | POST | `service.execute` | `bc2c631` |
| 定义投影 | `/onto/releases/{releaseId}/semantic/definitions/query` | POST | `service.execute` | `f05757c`、`2734ae2c` |
| 身份对应 | `/onto/spaces/{spaceCode}/semantic/identity-links` 及 `/{linkId}/revisions`、`/{linkId}/revoke`、`/resolve` | GET/POST | `mapping.read` / `mapping.write` | `f218386` |
| 有限两源关联 | 库内 `BoundedObjectFederation`（暂无对外路由） | — | 由调用方按来源分别授权 | `d0d05fd` |
| 业务变化接收 | `/onto/spaces/{spaceCode}/semantic/changes` | POST | `service.execute`＋来源验签 | `e3e83c7` |
| 变化状态 | `/onto/spaces/{spaceCode}/semantic/changes/state` | GET | `mapping.read` | `e3e83c7` |
| 语义扩展目录 | `/onto/spaces/{spaceCode}/releases/{releaseId}/semantic-extensions` | GET/POST | `release.read` / `release.write` | `8f06c1f` |
| 正式本体来源 | `/onto/releases/{releaseId}/formal-source` | GET | `release.read`（或 `ontosa_` 服务只读） | `1dbe935` |
| 应用语义绑定 | `/admin/semantic-bindings` 及 revisions/disable | GET/POST | `ADMIN`＋`release.write` 语义 | `16b2266e`、`bbceccbc` |
| 配置发布语义引用 | `/admin/config-releases`（`semanticBindings`） | POST | `ADMIN` | `bbceccbc` |

> 说明：`/onto/r|/onto/s` 业务消费入口仍以既有 `release.read`/服务票据为边界；语义能力不新造授权 token。`formal-source` 是 `WebBoundary` 中唯一新增的服务只读路径。

## 4. 两类资源的同标准复用（配置差异，不是代码分支）

首版选取两类**协议/权限形态不同**的资源，用同一套语义标准、同一套配置 Schema 接入。二者都不需要新增产品或专用适配器代码。

| 维度 | 资源 A：关系型主数据 | 资源 B：算法/文件制品 |
| --- | --- | --- |
| 接入形态 | 冻结数据源 `kind=RELATIONAL`、`dialect=postgresql`，JDBC 只读账号 | 技能调用（INT-02 解析/审批链），产物以受控文件/`artifact` 引用返回 |
| 权限形态 | 空间成员＋`service_endpoint` 授予（用户/角色），`dataScope` 过滤 | 空间成员＋端点授予＋技能版本/`bindingRefs` 冻结，服务票据 |
| 语义记录 | `read-only@1.0`：四元组、四态事实、`evidenceRefs`（`business-record`/`call`） | 同一 `read-only@1.0`：`evidenceRefs`（`artifact`/`call`） |
| 差异仅在于 | `datasources/metaSnapshots/objectMappings/guardCfg/accessScope` | `execution.semanticConditions`、`return_spec` 方言、技能绑定 |
| 代码路径 | `ObjectFactsService`→`FrozenDataExecutor`→`QueryPlanner` | `SemanticExecutionGate`→`SkillExecution`→适配器；结果经 `SemanticReceipt` 投影 |
| 已复核 | PG 集成 4 例（`ObjectFactsServicePgTest`）、查询规划 18 例 | 客户端/门禁 44 例＋回执投影 14 例；**无真实制品源端到端** |

**结论**：同标准复用已在“契约字段、配置 Schema、记录口径、错误码、证据类型”层面实现并由定向测试覆盖；两类资源的**真实交接**仍需运行栈与授权窗口（§12），当前不得记为已通过。

## 5. 配置项（只列键与形态，不列值）

| 键/位置 | 用途 | 形态与安全约束 |
| --- | --- | --- |
| `ONTO_DATASOURCE_KEY` | 解密冻结数据源连接（AES-256-GCM） | 32 字节 Base64；只从部署环境注入；缺失时 503，不回退明文 |
| `ONTO_CHANGE_SOURCES_JSON` | 业务变化来源别名、`secret_ref`、允许空间 | 形如 `{"<alias>":{"secret_ref":"env://<NAME>","spaces":["<space-code>"]}}`；只接受 `env://`，不接受内联密钥 |
| `agent.services.ontology`（AP） | OR 服务基址 | 内网地址；不写入仓库 |
| `agent.semantic.*`（AP，若启用） | 绑定激活联动开关/超时 | 默认关闭“未声明即强制”；声明后不允许降级 |
| 应用语义绑定 | `/admin/semantic-bindings` | 只存 `releaseRef` 四元组／`extensionId`／`extensionDigest`／`opCode`／技能精确版本；不存业务数据 |
| 目标判据 | `run.snapshot.config.goals` | 发布时冻结；验证操作必须 `readonly=true`、`sideEffect=false` |

密钥轮换：`secret_ref` 指向的环境变量换值后，新请求即用新密钥；旧收据保留但不再可验；轮换不需要重启 OR（按请求读取）。

## 6. 权限与租户边界

| 动作 | 最小权限 | 说明 |
| --- | --- | --- |
| 读取语义定义/扩展 | `release.read`＋空间成员 | 无权限返回空投影/403，不泄漏对象存在性 |
| 写语义扩展/绑定 | `release.write`／`ADMIN` | 扩展内容不可原地改写；绑定按 `expectedRev` CAS |
| 事实/判定/条件/变化接收 | `service.execute` | 仅服务票据；调用方主体来自已验证上下文，不信任请求体 `tenant/principal` |
| 身份对应 | `mapping.read` / `mapping.write` | 跨空间/跨租户返回同形拒绝 |
| 目标读回 | 运行归属＋只读验证工具 | 终态 run 也可能 `NOT_ACHIEVED`，完成只认回执 |

## 7. 时效与“未确认”口径

- `observedAt` 是观测时刻；`timeliness.freshness=UNKNOWN` 时必须给出 `unknownReason`，不得用当前时间冒充源时点。
- 事实四态：`PRESENT/NULL/ABSENT/UNKNOWN` 严格区分；`ABSENT`/`UNKNOWN` 不得携带值。
- 条件三态：`HOLDS/NOT_HOLDS/UNKNOWN`；依赖不可达或无法确认一律 `UNKNOWN`，分发前按拒绝/重新确认处理，不得放行。
- 目标五态：`ACHIEVED/NOT_ACHIEVED/PENDING/FAILED/UNKNOWN`；HTTP 200、工具成功、模型自述均不能映射 `ACHIEVED`。
- 业务变化：通知只写“失效位点”，不改写事实；重复/乱序/伪造不重放旧状态；无事件源时如实 `NO_EVENT` 并提示改用批准重查。

## 8. 错误码

`SEMANTIC_*` 最小集（契约 §3）：`SEMANTIC_PROFILE_UNKNOWN`、`SEMANTIC_REF_INVALID`、`SEMANTIC_SUBJECT_UNVERIFIED`、`SEMANTIC_OBJECT_FORBIDDEN`、`SEMANTIC_DIGEST_MISMATCH`、`SEMANTIC_FIELD_OUT_OF_RANGE`、`SEMANTIC_SOURCE_UNAVAILABLE`。

实现侧补充（均在既有错误模型内，不私造同义码）：

| 错误码 | 场景 |
| --- | --- |
| `SEMANTIC_CONDITION_BLOCKED` / `SEMANTIC_CONDITION_UNKNOWN` | 条件不成立/无法确认，分发前拒绝 |
| `SEMANTIC_CONDITION_RECONFIRM` | 审批后条件变化，要求重新确认 |
| `SEMANTIC_CONDITION_INVALID` / `SEMANTIC_CONDITION_UNAVAILABLE` | 声明非法/门禁未装配 |
| `SEMANTIC_CHANGE_SOURCE_FORBIDDEN` / `SEMANTIC_CHANGE_SIGNATURE_INVALID` / `SEMANTIC_CHANGE_TIMESTAMP_INVALID` | 来源、签名、时钟窗口拒绝 |
| `SEMANTIC_CHANGE_CONFLICT` / `SEMANTIC_CHANGE_OUT_OF_ORDER` | 同键异摘要、旧事件乱序 |
| `ENDPOINT_FORBIDDEN` / `ENDPOINT_DISABLED` | 端点未授予/停用 |
| `CONFIG_REF_NOT_FOUND` / `CONFIG_REV_CONFLICT` / `CONFIG_VERSION_UNKNOWN` / `CONFIG_RELEASE_NOT_ACTIVE` | 绑定/发布引用与 CAS |
| `QUERY_UNSUPPORTED_SOURCE` / `CARDINALITY_MISMATCH` / `GUARD_ATTR_UNRESOLVED` | 查询源/基数/守卫不满足 |
| `RELEASE_NOT_READY` / `ARTIFACT_STORAGE_UNAVAILABLE` | 版本未就绪/上游不可达 |

## 9. 交付一致性核对表

| 核对项 | 来源 A | 来源 B | 状态 |
| --- | --- | --- | --- |
| profile 标识与字段 | 本文件 §2 | `contracts/semantic-runtime.md` §7 + Schema | 一致（read-only/条件/判定）；goal Schema 文件待 AP 收口 |
| 错误码 | 本文件 §8 | `contracts/semantic-runtime.md` §3 | 一致；补充码为领域码，未改写契约码 |
| 语义端点 | 本文件 §3 | OR `contracts/openapi.json` | **部分缺登记**：facts/decisions/conditions/identity/changes 未同步 openapi（P 窗口补） |
| 提交号可复核 | 本文件 §1 | 两仓 `git cat-file -e` | 一致（见 manifest `components`） |
| 配置项 | 本文件 §5 | 代码 `@Value`/构造校验 | 一致；无内联密钥 |
| 测试口径 | 本文件 §4/§10 | 各卡 `docs/evidence/` | 一致；真实交接仍未执行 |
| 脱敏 | 全文 | manifest `sanitization` | 无密钥、无内部账号、无连接串 |

## 10. 验证记录

- 定向/回归（各卡证据）：OR 事实 28、判定 18＋61、条件 8、定义 10＋12、查询 18、身份 8＋26、关联 9＋35、变化 9＋37；AP 客户端/门禁 44、目标 7＋19、配置端 14＋2。
- 真实 PG：`ObjectFactsServicePgTest` 4/4、`GoalVerificationServiceTest` 7/7、`SemanticChangeTest` 9/9（隔离 schema；不触碰业务数据）。
- 文档自检：`docs/evidence/semantic-upgrade-20261103/SEM-14/verify-sem14.py` 校验 manifest 结构、引用路径、摘要、禁用模式与 BLOCKED 登记（输出见 evidence）。
- `docs/integration/verify-delivery.mjs` 属 SDK 交付包自检，不覆盖本文档；如运行需先构建 SDK 产物，结果单独记录。

## 11. 回退

| 变更 | 回退方式 | 数据影响 |
| --- | --- | --- |
| 语义绑定 | 停用绑定（`status=DISABLED`）或回退 `config_release` 指针 | 历史修订保留；不恢复已撤授权 |
| 变化接收 | 关闭来源配置/停止投递，保留收据 | 事实不受影响；回到批准按需重读 |
| 条件门禁 | 回退 AP 镜像到未声明版本 | 已声明应用会因 503 拒绝而非裸放行 |
| 目标核验 | 停止新配置，保留 `goal_outcome` | 不篡改旧 run 终态 |
| 定义投影/扩展 | 停用扩展或回退 OR 镜像 | 原 context-pack 字节/摘要不变 |

## 12. BLOCKED 登记（不得以文档冒充完成）

| ID | 事项 | 原因 | 解锁条件 | 责任 |
| --- | --- | --- | --- | --- |
| `SEM14-RESOURCE-HANDOVER` | 两类资源真实交接 | B02 运行栈未就绪；无用户授权窗口 | B02 就绪＋用户确认窗口；按 §4 配置两类源并留存真实正负记录 | P/用户 |
| `SEM14-M1A-JOINT` | M1a 双端联合出口 | 同上 | 同 `SEM14-RESOURCE-HANDOVER`；联合验收单独授权 | P |
| `SEM14-OPENAPI` | 语义端点 openapi 登记 | 小步白名单不含其余端点 | P 窗口统一导出并复核 | P/AP |
| `SEM14-GOAL-SCHEMA` | `goal@1.0` 独立 Schema 与 §7 登记 | 属 AP 契约收口 | AP 补 `semantic-runtime-goal-v1.schema.json` 并登记 | AP |
| `SEM14-EMBED` | SEM-09 嵌入/路由挂载 | 依赖 INT-05/07 与 F/P 席 | 集成窗口 | F/P |

**全卡口径**：本卡文档部分完成；两类资源交接与 M1a 联合出口维持 `BLOCKED`，不把“文档完成”写成“交接通过”。

## 13. 变更记录

| 日期 | 变更 | 影响 |
| --- | --- | --- |
| 2026-11-04 | 首版交付文档与脱敏 manifest | 新增，无运行时行为变化 |
