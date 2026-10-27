# AP-S03：真实异步任务信封差异与适配器修正

- 日期：2026-10-27
- 范围：`skill-registry` 的 HTTP `SUBMIT_QUERY` 适配器与契约校验；只改平台代码，不改 8080 服务。
- 关联：IN-06 真实响应取证、AP-S07 运行错误观测。

## 1. 真实响应差异

`docs/evidence/route-support/in-06-async-contract-evidence.md` 记录了 8080 的真实响应：

```json
POST /api/v1/space/visibility/tasks
{"taskId":"faf9efa9-2f33-4eff-9212-ca357a7b7d61"}

GET /api/v1/space/visibility/tasks/{taskId}
{"taskId":"...","status":"FAILED","errorMessage":"..."}
```

两个响应都**没有回显调用方发送的 `Idempotency-Key`**。`HttpAsyncAdapter` 原有不变量是：受理与轮询响应必须回显原幂等键，否则返回 `RESULT_UNKNOWN`，不把外部任务绑定到本地调用。这个不变量对多数服务成立，但对只返回自身任务 ID 的真实服务，会把一个合法的已受理任务长期留在 UNKNOWN，导致无法恢复。

IN-06 当时只按 `jobIdPath=$.taskId` 判断"无需改代码"，没有覆盖适配器的响应校验；本轮通过真实 taskId 信封测试复现并修正了这个结论。

## 2. 修正

### 2.1 契约显式开关

在 `SkillCatalog` 的 `reconciliation` 中新增布尔字段：

```json
"reconciliation": {
  "endpoint": "http://host/tasks/{jobId}",
  "donePath": "$.status",
  "doneValue": "SUCCEEDED",
  "idempotencyKeyEcho": false
}
```

- 默认值为 `true`，保持原有严格校验；只有显式 `false` 才跳过受理/轮询的幂等键回显校验。
- 非布尔值在创建/更新技能时返回 400 `INVALID_ASYNC_PROTOCOL`，不允许模糊配置。
- 关闭回显后仍必须满足：任务 ID 非空且不超过 128 字符、轮询任务 ID 与已绑定 ID 一致、响应来自契约端点、结果按冻结 schema 校验。幂等键仍通过 `Idempotency-Key` 请求头发送，绑定表仍以原键定位；这是"服务不返回回显"的显式弱化，不是取消幂等。
- `HttpAsyncAdapter.echoRequired(contract)` 是唯一判定点，受理与轮询共用，避免两处语义漂移。

### 2.2 文件

| 文件 | 改动 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java` | 校验并冻结 `reconciliation.idempotencyKeyEcho`，默认 true |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java` | 受理/轮询的幂等回显校验受契约开关控制 |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/AsyncHttpExecutionTest.java` | 真实 taskId 信封的正向与默认严格反向用例 |

## 3. 测试

环境：真实 PostgreSQL `agent_platform_skill_async_test`（Flyway schema 313）、JDK 21、Mockito；HTTP 响应由测试内 JDK `HttpServer` 产生，包含真实 8080 的字段形状（仅 taskId / taskId+status+result）。

```bash
set -a; . ./.env; set +a
export JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
mvn -q -pl skill-registry -am test \
  -Dtest='AsyncHttpExecutionTest#realSpaceTaskEnvelopeNeedsExplicitlyDisabledIdempotencyEcho+taskEnvelopeWithoutExplicitOptOutStillRequiresIdempotencyEcho+idempotencyEchoSwitchMustBeBoolean' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine='-Xmx512m -XX:MaxMetaspaceSize=256m'
```

结果：3 条通过，0 失败、0 错误。

| 用例 | 契约 | 断言 |
| --- | --- | --- |
| `realSpaceTaskEnvelopeNeedsExplicitlyDisabledIdempotencyEcho` | `taskId` + 显式 `idempotencyKeyEcho=false` | 受理成功；轮询 `SUCCEEDED`；结果通过冻结 schema；仅一次 POST |
| `taskEnvelopeWithoutExplicitOptOutStillRequiresIdempotencyEcho` | `taskId` + 未声明开关 | 受理保持 `UNKNOWN`；不重发；轮询也不被接受 |
| `idempotencyEchoSwitchMustBeBoolean` | `idempotencyKeyEcho="false"` | 创建契约返回 400 `INVALID_ASYNC_PROTOCOL`，不接受字符串布尔 |

## 4. 未完成边界

- 8080 当前异步任务因外部数据（TLE 校验和、缺少 SPACE 载荷）全部 FAILED，**真实 SUCCEEDED 的 result 信封仍未实测**；本轮只验证了真实受理/轮询字段形状与适配器的恢复逻辑，result 形状仍以 A02 operation17/18 为准。
- 因此 AP-S03 记为"契约差异已修、真实成功端到端待外部数据"，不把测试夹具当作真实算法成功。
- 全量 `AsyncHttpExecutionTest` 存在既有的测试顺序污染（`completedFactIsQueued...` 把 mock `SkillClients` 换成真实实例后未恢复），不是本次改动引入；定向用例通过。该问题应单独修测试隔离，不能据此宣称整个测试类全绿。

## 5. 复现与证据

- 代码：`skill-registry/.../SkillCatalog.java`、`HttpAsyncAdapter.java`、`AsyncHttpExecutionTest.java`
- 相关真实响应：`docs/evidence/route-support/in-06-async-contract-evidence.md`
- 运行痕迹：本次命令退出码 0；surefire 报告在 `skill-registry/target/surefire-reports/`
