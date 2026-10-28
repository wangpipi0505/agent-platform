# T14.1 / PLAT-14（工具档案 + toolProfile 选择）证据 — 2026-10-28

分支 `feat/v1.2-implementation`，基线 HEAD `0f3310a`（engine-runtime 最近提交 `928c3a0` 同为准线，开工前 `git status` 对已跟踪文件干净）。
方案依据：平台侧优化方案 §4.14（工具接入"变成可重复的产品流程"）+ §11.2.2 tools 区块（T22A schema 已冻结的工具声明形状）+ T22A schema `contracts/capability-package.schema.json`（tools[] 键集）。
载体界定（按派发单与 T22D 盘点）：**既有应用配置 `config.tools[]`**——组装点唯一，在 `RunService.prepareSnapshot`（`config.skillRefs[]` + 图节点 `skillRef` 组装为 `config.tools`）；**不依赖也不实现 P-1 能力包运行层装载**。开发计划 §6.1 全程遵守：profile 是声明式数据，无任何 `if (route == ...)` 特判。

## 1. 改动文件清单

主代码（1 个修改，白名单 `engine-runtime/src/main/java/**`）：

- `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java`
  - `toolInProfile(ref, profile)`（新增静态谓词）：工具是否属于所选档案。**只读两条声明数据**——工具引用上的 `profiles` 数组与应用配置的 `toolProfile` 字符串；profile 未声明（null/空白）→ 恒真（全部可见，存量零回归）；未声明 `profiles` 的工具不属于任何档案；成员判定是声明值精确相等，与名字内容无关。
  - `annotateToolSelection(snapshot, profile, selected, excluded, declared)`（新增静态）：写快照顶层 `toolSelection`（与 T13 `contextPack`、T20 `runtime` 同层级，随 run 记录不可变）。`declared=false`（应用未使用任何档案键）时不落键。
  - `surfaceToolSelection(status, snapshotJson)`（新增静态）：把快照里的 `toolSelection` 铺到运行详情顶层；旧运行快照无该对象时不落键（与 `surfaceRuntimeAnnotation` 同规则）。
  - `prepareSnapshot` 工具组装循环：组装前读 `config.toolProfile`；每个引用在既有 guard 可见性判定**之后**追加两道声明裁决——`enabled`（缺省 true，false → 不装载，对齐 22A schema `tools[].enabled` 语义）与 `toolInProfile`。装载的工具条目在引用声明了 `profiles` 时冻结该键（`config.tools[].profiles` 记录档案归属，未声明不落键）；被声明裁决排除的记入 `excluded`（reason：`DISABLED` / `PROFILE_NOT_MEMBER`，停用优先）。`pinned` 表、版本冲突检测、图节点 `skillVersionId` 回填、`versionRefs.skillVers`（派生自生效 `config.tools`）均不受影响。
  - 运行详情组装处（`surfaceRuntimeAnnotation` 调用点之后）追加 `surfaceToolSelection`。

测试（1 个新类，白名单 `engine-runtime/src/test/java/**`）：

- `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunToolProfileTest.java`（12 例，无 PG 依赖，`RuntimeClients` 脚本化桩驱动真实 `prepareSnapshot` 冻结链）：
  - ① 声明 profile 后只有成员工具装载（配置引用与图节点引用两个声明位置同裁决），`config.tools[].profiles` 随冻结留痕，快照 `toolSelection{profile,selected}` 可见；
  - ② 存量零回归锚点：无任何档案键 → 冻结快照无 `toolSelection` 键、全量工具装载、工具条目 10 键逐键一致（无 `profiles` 键）；
  - ③ 引用未列目标档案 → 不装载，`excluded=[{code,reason:PROFILE_NOT_MEMBER}]`；
  - ④ profile 无命中 → 冻结不失败、空工具集放行、`toolSelection.warning` 显式留痕；
  - ⑤ `enabled=false` → 不装载 `reason=DISABLED`；停用与档案命中同时成立时停用优先；
  - ⑥ 无 route 特判结构证明：形似路线名/软件名的任意档案名（`route-one`/`route-three`/`ROUTE_X`/`space-visibility`/`任意档案名`）行为完全由成员声明决定 + 谓词直测（空白等价未声明、精确匹配、空数组不属于任何档案）；
  - 边界补充：声明 profiles 但未选档案 → 全量装载仅留痕；guard 拒见保持既有静默语义不冒充档案排除；详情 surface 两例（有留痕透出 / 旧运行不落键）。

证据文件（命令输出原文）：`red-compile.txt`、`revert-check-profile-bypassed.txt`、`regression-multi.txt`。

## 2. 红→绿输出摘要

红阶段（编译红，实现前 12 用例全部引用不存在的档案 API，全文见 `red-compile.txt`）：

```
$ mvn -B -pl engine-runtime test -Dtest='RunToolProfileTest' "-DargLine=-Xmx1024m"
[ERROR] ... 找不到符号  符号: 方法 toolInProfile(JsonNode,String)      （6 处）
[ERROR] ... 找不到符号  符号: 方法 surfaceToolSelection(Map,JsonNode)  （2 处，另 4 处同型）
[ERROR] BUILD FAILURE
```

绿（实现落地后，回退验证复绿 + 最终回归批原文见 `regression-multi.txt`）：

```
$ mvn -B -pl engine-runtime test -Dtest='RunToolProfileTest,...' "-DargLine=-Xmx1024m"
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in c.a.e.application.RunToolProfileTest
[INFO] BUILD SUCCESS
```

回退验证（证伪绿状态非偶然，全文见 `revert-check-profile-bypassed.txt`：`toolInProfile` 临时改为恒真）：

```
[ERROR] Tests run: 12, Failures: 4, Errors: 0, Skipped: 0
  profilePredicateReadsOnlyRefDeclarationAndProfileString   未列 profiles 的工具不属于任何档案
  profileWithoutAnyMatchRunsEmptyToolSetWithVisibleWarning  无命中时不得回退为全量装载
  selectionDependsOnlyOnDeclaredMembershipNotProfileName    不得对档案名特判: route-one
  toolOutsideDeclaredProfileIsExcludedWithReason            expected: <[demo_read]> but was: <[demo_read, demo_write]>
```

恰好档案选择语义 4 例回红、不依赖过滤器的 8 例不受影响 → 绿由实现承载。还原后复绿。

环境：`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`（JDK 21）；`source .tools/env-load.sh`（`ENGINE_PG_TEST=true`，PostgreSQL 16.15 @ 127.0.0.1:25432）。

## 3. 回归对照（新增失败 = 0）

派发指定相邻类 + 两个直接驱动 `prepareSnapshot` 的近邻类，九类同跑（`regression-multi.txt`，2026-10-28 最终复跑）：

```
RunToolProfileTest                Tests run: 12, Failures: 0, Errors: 0  （新增）
RunEnvironmentAnnotationTest      Tests run: 11, Failures: 0, Errors: 0
ToolOutputPolicyTest              Tests run: 10, Failures: 0, Errors: 0
ToolOutputLargeResponsePostgresTest  Tests run: 4, Failures: 0, Errors: 0  （真库）
EngineErrorSurfacePostgresTest    Tests run: 4,  Failures: 0, Errors: 0  （真库）
RunDiagnosticsTest                Tests run: 7,  Failures: 0, Errors: 0
RunServiceContextPackTest         Tests run: 9,  Failures: 0, Errors: 0  （同走 prepareSnapshot）
PromptFreezeDigestTest            Tests run: 4,  Failures: 0, Errors: 0  （同走 prepareSnapshot）
SkillErrorSurfaceTest             Tests run: 9,  Failures: 0, Errors: 0
合计 Tests run: 70, Failures: 0, Errors: 0, Skipped: 0
```

各相邻类用例数与 HEAD 基线一致（11/10/4/4/7/9/4/9），零新增失败。`RuntimeStreamTest` 未触碰、未运行；未跑模块全量。

## 4. 档案键清单（哪些已支持 / 哪些最小新增）

对齐 T22A schema（`contracts/capability-package.schema.json` tools[] 区块）形状，逐键口径：

| 22A tools[] 键 | 本卡落点 | 状态 |
| --- | --- | --- |
| `enabled`（缺省 true，false=停用不对外暴露） | 应用配置工具引用（`config.skillRefs[]` / 图节点 `skillRef`）上的 `enabled` | **最小新增**（引擎侧消费） |
| `profiles`（本卡引入的档案归属声明） | 同上引用上的 `profiles` 数组；冻结时进入 `config.tools[].profiles` | **最小新增**（22A schema 无此键，应用配置面专用；contracts/ 冻结面未动，键在此登记） |
| `toolProfile`（所选档案名，字符串） | 应用配置顶层 `config.toolProfile` | **最小新增**（自由约定标签，任意字符串合法） |
| `exposure`（READ_ONLY/WRITE_WITH_APPROVAL） | 技能契约 `execution.readonly/sideEffect` + T15 `approval` | 已支持（经 `config.tools[].contract/execution` 契约透传，本卡未改） |
| `confirmRoles` | 技能契约 `execution.confirmRoles`（T15 normalizeApproval 入口） | 已支持（同上透传） |
| `pagination` | 技能契约 `pagination`（O-2 声明语义，T14.2 `ToolOutputPolicy.declaredPagination` 消费） | 已支持（同上透传） |
| `errorSemantics` | 技能契约 `errorSemantics`（PLAT-05 §11.2 两形态） | 已支持（同上透传） |

留痕结构（本卡新增的运行面事实，非声明键）：快照/详情顶层 `toolSelection = {profile?, selected[], excluded?[{code,reason}], warning?}`。

## 5. 未覆盖风险

- **P-1 边界**：能力包→运行链路装载（22D 盘点 P-1）未实现，本卡不依赖不实现；`config.tools[]` 仍由应用配置组装，能力包声明不会自动出现在应用工具清单（下轮主体）。
- **图节点仍可执行被排除技能**：档案/enabled 排除只影响模型可见工具（`config.tools`）；图节点 `skillRef` 回填不受影响，流程图节点仍按既有语义执行被排除技能（与 guard 拒见行为同构）。若业务期望"排除=节点也禁跑"，需另立卡裁决，本卡按"工具档案=模型可见性"定死。
- **web/应用管理侧无编辑面**：`toolProfile`/`profiles`/`enabled` 目前只能经应用配置 JSON 生效，应用管理台未提供选择器（属前端卡范围，本卡白名单未含 web/**）。
- **SSE 未透出**：`toolSelection` 落快照与运行详情；RUN_STARTED 载荷未追加（派发单只要求快照/详情可见，未做超范围扩散）。
- **并发协作者改动**：本卡工作期间同一工作树出现 `platform-gateway` 的 5 处非本卡改动（T18.1 入口限流实现者并发作业，未提交）。本卡全部改动隔离在白名单内，回归批仅 `-pl engine-runtime`，互不影响；合入时需各卡分别只提交各自文件。

## 6. 回滚方式

纯 Java 单文件回滚，无迁移、无配置、无契约变更：

```
git checkout HEAD -- engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java
# 删除新测试类与证据目录（可选，均为新增文件）：
rm engine-runtime/src/test/java/cn/agentplatform/engine/application/RunToolProfileTest.java
git clean -fd docs/evidence/2026-10-28-PLAT-14-tool-profile/
```

已带档案声明的存量应用配置在回滚后回到"全量装载"行为（声明键被忽略，不报错）——配置向前兼容，回滚不破坏数据。

## 7. 偏差与定死决策（单列）

- **D1（无命中语义定死）**：声明了 `toolProfile` 但没有任何工具命中 → **放行空工具集继续运行 + `toolSelection.warning` 显式留痕**，不拒绝启动、不回退全量。理由：拒绝启动会把声明笔误升级为业务中断，而空工具集+可读警告足以让问题在运行详情可见（采纳派发单建议项）。
- **D2（停用优先）**：`enabled=false` 与档案命中同时成立 → 按停用排除（reason=DISABLED），档案命中不能复活停用工具。对齐 22A schema "停用的工具不对外暴露"。
- **D3（留痕键名定 `toolSelection`）**：派发单建议 `snapshot.tools` 或等价结构；取 `toolSelection`（对象：profile/selected/excluded/warning），避免与 `config.tools`（生效工具数组）同名歧义；与 `contextPack`/`runtime` 同为快照顶层键。
- **D4（guard 拒见不计入 excluded）**：既有 guard 可见性排除保持静默（安全语义，行为零变化），`excluded` 只记录本卡引入的声明裁决（DISABLED/PROFILE_NOT_MEMBER），不冒充档案语义。
- **D5（profile 名不设枚举/格式约束）**：任意非空白字符串都合法（含形似路线名者）——这正是"无路线特判"的结构性质；空白/缺失等价未声明。形状校验（如需长度上限/字符集）留给前端卡与能力包校验脚本，引擎层不做名字特判。
- **D6（`contracts/skill-invocation.md` 未动）**：按派发单，工具档案是应用配置面不是技能契约面；新增键（`toolProfile`/`profiles`/`enabled` 引擎消费口径）以本清单登记为准，未触碰 contracts/ 冻结面。
