# T17 —— 模板摘要校验与冻结引用留痕

- 卡片：`文档/优化方案/派发卡片-B1-20261027.md` 卡片 17（T17）
- 仓库：`代码/agent-platform`，分支 `feat/v1.2-implementation`，基线 HEAD `7839085`
- 日期：2026-10-28
- 环境：`JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`；真实库用例先 `source .tools/env-load.sh`，测试库 `127.0.0.1:25432/agent_platform_v12`（用户 `agent_v12`）。

## 1. 改动清单

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `app-management/src/main/java/cn/agentplatform/app/application/TemplateService.java` | +32 / -1 | 新增 `PUBLISHED_SHA` 形状常量；`version(...)` 读到行后调用私有 `verifyDigest(version, row)` 复算并比对摘要；摘要缺失/非 64 位小写 hex、内容与摘要不一致均抛 409 `TEMPLATE_DIGEST_MISMATCH`（消息含 `v<version>`） |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | +36 / -3 | `versionRefs(...)` 新增 `promptShas`（`TreeMap`，`{code: {version, sha256}}`），`promptVers` 形状不变；冻结循环取回模板后校验 `sha256` 形状，非法抛 409 `PROMPT_TEMPLATE_SHA_INVALID`；新增 `promptDigestCheck` 开关字段与 `@Autowired` setter（`${agent.prompt.digest-check:true}`，字段默认 `true`） |
| `app-management/src/test/java/cn/agentplatform/app/application/TemplateDigestTest.java` | 新增 214 行 | 真实库：读路径摘要校验、正常版本防误伤、摘要缺失/非法、内部端点 409 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/PromptFreezeDigestTest.java` | 新增 191 行 | 依赖桩 + 反射：`promptShas` 留痕、非法摘要拒绝冻结、去重只读一次、关闭开关行为 |
| `docs/evidence/2026-10-28-PLAT-17-prompt-digest/` | 新增 | 本 README 与红/绿/回归原始日志（`*.log` 被仓库 `.gitignore` 的 `**/*.log` 忽略，未提交） |

未改动：`ContextAssembler`、迁移、`pom.xml`、`contracts/**`、`scripts/**`、web 与其它模块；未 `git add` / `git commit`。

## 2. 行为与实现要点

1. **读路径即校验**：`TemplateService.version(code, version)` 是内部端点（`AppController` `/internal/prompt-templates/{code}/versions/{version}`）与 admin 读端点的共同入口，校验放在这里同时覆盖两条读路径（卡片行为 1、2）。材料与写路径 `insert(...)` 完全一致：`Digest.sha256(AppRules.canonical({content, variables, outputContract}))`；缺省 `outputContract` 视为 `{}`。
2. **同码两类失败**：摘要缺失/非 64 位小写 hex（含 `char(64)` 空串）与内容被改都返回 409 `TEMPLATE_DIGEST_MISMATCH`，消息分别说明"摘要缺失或格式非法"与"内容与发布摘要不一致"，都带 `v<version>`，便于定位。
3. **冻结先校验、再留痕**：冻结是模板正文的唯一取回时刻；取回后先校验形状，`promptShas` 只收录通过形状校验的摘要，避免把无效摘要写进不可变运行证据（卡片行为 3、4、5）。
4. **去重不变**：沿用既有 `promptKeys` 去重，`config.promptRefs` 与节点 `promptRef` 指向同一 `code:version` 时只发生一次 `clients.get`，`promptShas` 只有一条。
5. **兜底开关**：`agent.prompt.digest-check`（缺省 `true`）；关闭时只跳过引擎侧冻结校验，app 侧读校验不受影响。

## 3. 先红后绿证据

两轮都只跑卡片列出的定向 `-Dtest`，未跑模块全量。原始日志见本目录 `red-app.log`、`red-engine.log`、`green-app.log`、`green-engine.log`、`regression-app.log`、`regression-engine.log`（本地文件，被 `.gitignore` 忽略）。控制台中文断言消息按平台 GBK 输出，故下面只保留可机读原文行。

### 3.1 红（实现前，仅新增测试类）

```
mvn -pl app-management test -Dtest='TemplateDigestTest'
```

```
[ERROR] Tests run: 4, Failures: 3, Errors: 0, Skipped: 0
[ERROR]   TemplateDigestTest.internalReadEndpointRejectsTamperedVersion:207 Status expected:<409> but was:<200>
[ERROR]   TemplateDigestTest.missingOrMalformedDigestIsRejectedWithReason:167 ==> Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.
[ERROR]   TemplateDigestTest.tamperedPublishedContentIsRejectedOnRead:132 ==> Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.
```

（`untamperedPublishedVersionStillReadsBackWithSameDigest` 在红轮通过，证明"防误伤"用例本身不是靠新行为才成立的。）

```
mvn -pl engine-runtime test -Dtest='PromptFreezeDigestTest'
```

```
[ERROR] Tests run: 4, Failures: 3, Errors: 1, Skipped: 0, Time elapsed: 0.278 s <<< FAILURE! -- in cn.agentplatform.engine.application.PromptFreezeDigestTest
[ERROR]   PromptFreezeDigestTest.duplicatePromptReferencesFreezeOnceAndRecordOneDigest:174 expected: <1> but was: <0>
[ERROR]   PromptFreezeDigestTest.frozenPromptRecordsDigestInVersionRefs:139 ==> expected: <1> but was: <0>
[ERROR]   PromptFreezeDigestTest.templateWithoutValidDigestIsRejected:153->freezeFailure:105 Expected java.lang.reflect.InvocationTargetException to be thrown, but nothing was thrown.
[ERROR]   PromptFreezeDigestTest.disabledDigestCheckSkipsRejectionButNeverRecordsInvalidDigest:183->digestCheck:122 ? NoSuchField promptDigestCheck
```

红轮失败点与卡片 5 条用例对应关系：① = `tamperedPublishedContentIsRejectedOnRead`（含内部端点用例）仍未 409；③ = `frozenPromptRecordsDigestInVersionRefs` / `duplicatePromptReferencesFreezeOnceAndRecordOneDigest` 的 `promptShas` 缺失；④ = 非法摘要未拒绝冻结；⑤ = 去重后 `promptShas` 条数为 0；开关字段不存在 = 行为 5 无载体。

### 3.2 绿（实现后）

```
mvn -pl app-management test -Dtest='TemplateDigestTest'
```

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.792 s -- in cn.agentplatform.app.application.TemplateDigestTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```
mvn -pl engine-runtime test -Dtest='PromptFreezeDigestTest'
```

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.268 s -- in cn.agentplatform.engine.application.PromptFreezeDigestTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 4. 回归（卡片命令）

```
mvn -pl engine-runtime test -Dtest='RunService*Test,ContextAssembler*Test'
```

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.156 s -- in cn.agentplatform.engine.application.ContextAssemblerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.241 s -- in cn.agentplatform.engine.application.RunServiceContextPackTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```
mvn -pl app-management test -Dtest='*Template*Test'
```

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.322 s -- in cn.agentplatform.app.application.TemplateDigestTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 5. 偏差（相对卡片）

1. **新增开关注入点**：卡片只允许改 `versionRefs` 与冻结循环，但行为 5 必须有开关载体，故在 `RunService` 新增 `promptDigestCheck` 字段与 `@Autowired` setter（沿用既有 `contextPacks` 的 setter 注入惯例和"保持既有构造器夹具不变"的注释约定）；字段初始 `true`，直接 `new RunService(...)` 的既有夹具语义不变。
2. **`promptShas` 只收录合法摘要**：关闭开关时也不把无效/缺失摘要写成运行留痕（卡片未规定关闭时的留痕语义，取"不写未经校验的数据"这一更保守的解释）。键始终存在，结构稳定。
3. **校验覆盖 `version()` 的所有调用点**：`create`/`revise`/`publish`/`versions`/`render` 内部都走同一读方法，因此历史坏数据在这些路径同样 409，而不只是 GET 端点；这是"校验放在唯一读方法"的直接结果。
4. **测试①的故障注入方式**：已发布行受 `immutable_template_version` 触发器保护，测试里临时 `ALTER TABLE ... DISABLE TRIGGER` → 改 `content` → `finally` 恢复；`@BeforeAll` 再做一次 `ENABLE TRIGGER` 自愈。只作用于隔离测试库，不改迁移；用例使用随机 `digest-<uuid>` tenant，不污染既有数据。已用 psql 复核触发器恢复为 `tgenabled='O'`。

## 6. 未覆盖项

- 未跑任何模块全量，也未跑 `InitialChatAdmissionHttpPostgresTest`、`PublishedAdmissionHttpPostgresTest`、`ModelInputMaterialPostgresTest`、`RunObservationStatusHttpPostgresTest` 等不在卡片回归命令内的真实库用例；已代码检索确认这些测试没有通过 HTTP 读取模板（无 `prompt-templates` 桩），新校验不会触发。若需要可另行跑。
- 同样出于"只跑卡片列出的定向 `-Dtest`"约束，未跑 `ApplicationLifecycleTest`、`PublishTransactionTest` 等同样覆盖模板端点的 app 侧用例（它们写读的是刚发布的一致数据，预期不受影响）。
- 未覆盖多实例并发下"读版本"与"篡改/回滚"的竞态（读路径是单次查询 + 本地复算，无跨语句事务）。
- 未覆盖 `agent.prompt.digest-check=false` 的 Spring 属性注入端到端：测试用反射设置字段验证行为，属性名/默认值由 `@Value("${agent.prompt.digest-check:true}")` 声明。
- 未覆盖 admin 读端点的 HTTP 形状，只测了内部端点；两者共用同一 `TemplateService.version(...)`，服务层 409 已覆盖。
- 未覆盖 `promptShas` 在实际 `engine.run` 行/材料快照中的落库形状（`versionRefs(...)` 是私有方法，用反射验证其返回值）。

## 7. 回滚方式

本次改动未提交，回滚只需丢弃工作区改动：

```
git checkout -- app-management/src/main/java/cn/agentplatform/app/application/TemplateService.java \
                engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java
rm app-management/src/test/java/cn/agentplatform/app/application/TemplateDigestTest.java \
   engine-runtime/src/test/java/cn/agentplatform/engine/application/PromptFreezeDigestTest.java
rm -rf docs/evidence/2026-10-28-PLAT-17-prompt-digest
```

无迁移、无配置、无新依赖，因此无需数据库回滚。
