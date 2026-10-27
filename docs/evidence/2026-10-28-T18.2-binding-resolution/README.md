# T18.2 运行期按发布绑定解析 releaseId（卡片 20）

日期：2026-10-28　分支：`feat/v1.2-implementation`（基线 HEAD `4caf8af`）

## 目的

发布期把"这个应用在本环境用哪个版本"写进了本体侧的 release binding（`platformTenant` + `appCode` +
`environment` → `releaseId`）。运行期冻结快照只带 `config.ontology.space`，不带 releaseId，而 T13 的
`applyOntologyContext` 要求 `space` 与 `releaseId` 都是 UUID，缺 releaseId 直接 400。结果是"发布绑定"这套
模型在运行期根本走不通，只有手工把 releaseId 抄进快照才能取到包。

本卡把解析点补在冻结阶段：缺 releaseId 时按绑定查询拿 releaseId，再走原来的取包路径；显式 releaseId 优先且
完全不受影响。绑定只由运行所属租户 + 快照 appCode + 部署环境决定，不受调用方传入参数影响。

## 改动（5 个文件）

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/OntologyContextPacks.java` | +140 / -0 | `Binding` record；`resolveBinding`；`queryBinding`；`bindingPayload`；`errorCode`；`bindingUnavailable`；`encode`；30s 绑定 TTL 与独立 64 容量 LRU；`BINDING_NOT_FOUND` 常量 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | +62 / -2 | `pinResolutionEnabled` / `pinEnvironment` 字段与 setter（`@Value`，默认 `true` / `production`）；`applyOntologyContext` 的解析分支、fail-open 跳过、审计回填 |
| `engine-runtime/src/main/resources/application.yml` | +5 / -0 | 新增 `agent.pin.resolution-enabled`（默认 `true`）与 `agent.pin.environment`（默认 `production`），均带环境变量覆盖 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunServiceContextPackTest.java` | +202 / -0 | 新增 5 例 + `BindingStub` 双端点桩；既有 4 例与其断言**零改动** |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OntologyBindingResolutionTest.java` | 新增 280 行 | 6 例：查询串/请求头、30s TTL、404 码、网关 404、5xx 与连接拒绝、坏响应 |

要点：

- 绑定查询与取包共用同一个 base-url、同一个 `X-API-Key`（`SecretVault.resolve` / 测试工厂），同样
  `Redirect.NEVER`、connect 5s / 请求 15s / 响应上限 256KiB、失败不缓存。
- 绑定缓存 30s、key `tenant|appCode|environment`，与取包 5min 缓存**分开**：命中绑定不等于包还在窗口内。
- 只有 `404` **且**错误体带 `code=BINDING_NOT_FOUND` 才算"没有生效绑定"；网关/路由错配的 404、5xx、连接
  失败、响应形状非法、`releaseId` 非 UUID、绑定 `status` 非 `ACTIVE` 一律 `503 CONTEXT_PACK_UNAVAILABLE`。
- 参数走 `URLEncoder`：appCode 由应用侧决定，未编码的 `&`/`=` 能改写查询结构。
- 审计：解析成功写 `pinResolved=true` + `bindingId`（其余五字段不变）；跳过写
  `pinResolved=false` + `pinFailure=BINDING_NOT_FOUND` + `reason`；显式 releaseId 路径不写这些新字段，
  与 T13 逐字段一致。

## 红绿原文

三份原始日志（GBK 字节，`*.log` 被忽略，故关键行贴在此处）：

- `red-1-compile.log`（测试先写、实现未写）
- `red-2-behaviour.log`（客户端与开关已就位、`applyOntologyContext` 未接线）
- `green-final.log`

### 红-1：接口不存在，编译失败（测试先于实现）

```
[INFO] -------------------------------------------------------------
[ERROR] .../application/RunServiceContextPackTest.java:[396,20] 找不到符号
  符号:   方法 pinResolutionEnabled(boolean)
  位置: 类型为cn.agentplatform.engine.application.RunService的变量 service
[ERROR] .../infra/OntologyBindingResolutionTest.java:[136,32] 找不到符号
  符号:   方法 resolveBinding(java.lang.String,java.lang.String,java.lang.String,cn.agentplatform.common.Actor)
  位置: 类型为cn.agentplatform.engine.infra.OntologyContextPacks的变量 packs
[INFO] 17 errors
[INFO] BUILD FAILURE
```

（同形错误共 17 处，全部是 `resolveBinding` 与 `pinResolutionEnabled` 未定义。）

### 红-2：接线未做，行为红

```
[ERROR] Tests run: 9, Failures: 1, Errors: 2, Skipped: 0, Time elapsed: 0.450 s <<< FAILURE! -- in cn.agentplatform.engine.application.RunServiceContextPackTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.engine.infra.OntologyBindingResolutionTest
[ERROR]   RunServiceContextPackTest.bindingFailureFailsRunWithDependencyCode:373 expected: <503> but was: <400>
[ERROR]   RunServiceContextPackTest.bindingNotFoundSkipsContextButRunContinues:350->freeze:108 ? Platform 本体上下文必须声明 space 与 releaseId，且均为 UUID
[ERROR]   RunServiceContextPackTest.missingReleaseIdResolvesFromBinding:327->freeze:108 ? Platform 本体上下文必须声明 space 与 releaseId，且均为 UUID
[ERROR] Tests run: 15, Failures: 1, Errors: 2, Skipped: 0
[INFO] BUILD FAILURE
```

红-2 里两条新用例是绿的：`explicitReleaseIdWinsWithoutBindingCall` 与
`resolutionDisabledKeepsLegacyBehaviour`。它们守的是"既有显式路径与回滚开关不变"，接线前本就该绿，属于
回归护栏而非新行为断言，未为凑红而改动。

### 绿：三个定向类全通过

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.engine.application.RunServiceContextPackTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.engine.infra.OntologyBindingResolutionTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.engine.infra.OntologyContextPacksTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（第三个类是 T13 的既有客户端用例，一并跑是为了证明共用路径没被改坏。）

## 为什么"缺省 releaseId 遇 404 跳过"而不是硬失败

判断的依据是**区分"事实"与"故障"**，而不是错误码好看：

1. `404 BINDING_NOT_FOUND` 表达的事实是"这个应用还没发布到本环境"。这是运行的前置条件不满足，不是依赖
   故障。把它变成 5xx，等于让所有尚未发布本体的应用（含所有新建应用、所有非生产环境）在运行入口被一刀切
   拦住，回归面远大于收益；而"没注入上下文"本身是可见的（审计里有 `pinFailure`），不是静默错误。
2. 反过来，5xx、连接失败、超时、网关 404、响应形状非法、非 ACTIVE 绑定必须硬失败。这些是"本应能查到却
   没查到"，静默跳过会让运行在没有任何本体上下文的前提下产出结果，而没人知道为什么答得不一样——这与
   "不能回退到最新版本"是同一类风险：用错数据比报错更贵。
3. 判据是**错误码**而非 HTTP 状态码：只有本体自己给出 `BINDING_NOT_FOUND` 才跳过。一次网关/路由错配的
   404 若被当作"没绑定"，会把配置错误永久伪装成正常空结果，所以它也归 503。
4. 跳过仍留痕：`pinResolved=false` + `pinFailure=BINDING_NOT_FOUND` + `reason` 写进
   `snapshot.contextPack`，事后可回答"这次运行为什么没有本体上下文"。
5. 需要立刻恢复严格语义时有运行时开关：`agent.pin.resolution-enabled=false` 精确回到 T13 行为（只有显式
   releaseId 才注入），不需要回滚代码。

## 偏差

1. **用例 5「关闭开关后缺 releaseId 照旧跳过」→ 实现为 400（T13 原行为），不是静默跳过。**
   规格 7 的原话是"关闭时……行为等同今天"，而今天 `space` 合法 + `releaseId` 缺失得到的是
   `400 CONTEXT_PACK_CONFIG_INVALID`，T13 里根本不存在"跳过"分支（唯一的跳过是 `config.ontology`
   缺失或空对象）。所以"照旧"只能等于 400：若关闭后改成静默跳过，等于让回滚开关引入一个从未存在过的
   行为——回滚期悄悄丢掉本体上下文，恰恰是规格 5 明确反对的"悄悄"。这不是被既有断言逼出来的：既有用例
   `invalidOntologyConfigIsRejectedWithoutHttp` 的快照没有 `appCode`，命中的是"缺 appCode"那条 400，
   与开关取值无关，两种实现它都绿；这里是刻意把 legacy 语义做实，用例名
   `resolutionDisabledKeepsLegacyBehaviour` 指的也是"legacy = 400"。
2. **网关 404 比卡片更严。** 规格 5 写"非 2xx（404 除外）→ 503"，字面上任何 404 都不算故障；实现只在
   404 **且** `code=BINDING_NOT_FOUND` 时 fail-open，其余 404 归 503（`gatewayFourOhFourWithoutBindingCodeIsUnavailable`
   锁定）。理由：错配路由/网关的 404 若被当成"没绑定"，一次配置错误会被永久伪装成正常空结果；本体契约里
   404 必带该码，收紧不误伤真实场景。
3. **快照缺 `appCode` 时**：解析开启 → 仍是 400，但消息改为"缺少 releaseId，且运行快照没有 appCode，
   无法解析发布绑定"（码不变）。生产快照必有 appCode（`RunService.java:860` 的发布校验与
   `ModelInputMaterials.java:280` 的读取都依赖它），该分支只影响手工/历史快照。
4. 审计在成功路径新增两个字段（`pinResolved`、`bindingId`），跳过路径新增三个（`pinResolved`、
   `pinFailure`、`reason`）；显式 releaseId 路径一个都不加，与 T13 逐字段一致。
5. `application.yml`：卡片"允许改动 #3"只点了 `agent.pin.environment`，但规格 7 要求开关也在此登记，
   故两条一起加（均带环境变量覆盖），未新增其它 key。

## 未覆盖

- 不写 `context_pack` 列、不加迁移（T13 已声明写入路径不在范围内，本卡同样不碰 DB）。
- 不校验绑定 `releaseId` 与本体 `space` 的匹配关系：本体侧保证绑定属于该 space，引擎只信 binding 返回的
  releaseId，多一次查询换不来更多保证。
- 无绑定变更推送，只靠 30s TTL 收敛；重新发布后最坏 30 秒生效。
- 首查无锁，并发解析同一 key 可能各发一次请求（与既有 `fetch` 一致）；只多一次请求，不会产生不一致。
- 只跑 3 个定向测试类，未跑模块全量（既有失败与并发限制），也未跑 Spring 上下文启动测试：`@Value` 注入
  沿用既有 `promptDigestCheck` 同一模式（字段带默认值 + `@Autowired` 方法注入），启动语义不变。
