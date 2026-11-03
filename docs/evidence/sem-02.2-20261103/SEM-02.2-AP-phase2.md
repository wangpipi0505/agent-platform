# SEM-02.2 阶段2 证据：配置发布钉住语义绑定修订

- 日期：2026-11-04
- 责任：O 席（AP 语义绑定接线）
- 前置：SEM-02.1（`8f06c1f`）、SEM-02.2 阶段1（`16b2266e`）、INT-03 子步2（`6122cf44`）
- 契约：`contracts/semantic-runtime.md` §2.2 `bindingRef`、§5.1“撤权、停用、凭据失效优先于任何历史快照”

## 1. 范围与文件

| 文件 | 改动 |
| --- | --- |
| `app-management/src/main/resources/db/migration/V115__config_release_semantic_refs.sql` | 新增发布→绑定修订引用表 |
| `app-management/src/main/java/cn/agentplatform/app/infra/ConfigReleaseRepository.java` | 发布与引用同事务写入；读路径回填 `bindingId/bindingRev` |
| `app-management/src/main/java/cn/agentplatform/app/application/IntegrationConfigReleases.java` | `semanticBindings` 声明、摘要、校验/激活撤权复核、回显与运行投影 |
| `app-management/src/main/java/cn/agentplatform/app/application/SemanticBindings.java` | `pinned/live` 修订解析（指定 actor 版本） |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java` | `/admin/semantic-bindings` 转发 app |
| `app-management/src/test/java/cn/agentplatform/app/infra/SemanticConfigWiringFlowTest.java` | 真实 PG 定向 5 例 |

**白名单外最小必要扩展声明**：阶段2 白名单只列了 `IntegrationConfigReleases`、`SemanticBindings`、`Routes` 与权限目录，但原配置发布表没有任何列/表可存语义引用；没有持久引用就无法证明“激活使用绑定精确修订、旧发布可反查”。因此新增 V115 一张引用表（不新增业务语义，仅承载 `release → bindingId+bindingRev` 的外键关系）。原四制品、冻结包与旧发布行不变。

## 2. 数据模型与事务边界

```sql
app.integration_config_release_semantic_ref(
  tenant_id, release_id, ref_index, binding_id, binding_rev,
  PK(tenant_id, release_id, ref_index),
  UNIQUE(tenant_id, release_id, binding_id),
  FK(tenant_id, release_id) -> integration_config_release,
  FK(tenant_id, binding_id, binding_rev) -> semantic_binding_revision)
```

- 发布行与引用行在 `ConfigReleaseRepository.insert` 的同一 `TransactionTemplate` 内写入；引用缺失或修订不存在时整体回滚，不存在“发布已建、引用丢失”的中间态。
- 引用不复制扩展/技能声明内容，只存指针；声明永远从 `semantic_binding_revision` 的不可变修订读回。
- `ref_index` 保持声明顺序，供稳定回显；同一发布内 `binding_id` 唯一。

## 3. 行为不变量

1. **精确修订**：创建时按 `bindingId+bindingRev` 解析并钉住；之后绑定新增修订不改旧发布的 `bindingRev`、`opCode`、`releaseRef` 与摘要。
2. **撤权优先**：`validate`、`activate`、对外 `experience` 三处都重新读绑定父行状态；`DISABLED` 时分别抛 `CONFIG_RELEASE_NOT_ACTIVE`（校验/激活）与 `SEMANTIC_REF_INVALID`（运行投影），即使发布自己钉住的修订仍然完整。
3. **可反查**：`get/list` 返回解析后的绑定投影（含 `status`），停用后旧发布仍能读出当时钉住的修订，只是不能继续作为能力面。
4. **幂等**：同 `requestId` 且同载荷只写一次发布与一组引用；异载荷仍按 `CONFIG_REV_CONFLICT` 拒绝（沿用 `ConfigWriteLog`）。
5. **应用归属**：绑定必须属于发布声明的应用；跨应用引用在创建阶段即 409 `CONFIG_REV_CONFLICT`，修订不存在 400 `CONFIG_VERSION_UNKNOWN`。
6. **组合摘要**：`digest` 覆盖应用版本、体验/接入修订、连接引用与语义绑定引用（按 `bindingId` 排序），不覆盖扩展或技能内容本身。
7. **旧装配兼容**：阶段1 的 8 参构造保留；未注入目录时，任何 `semanticBindings` 声明显式 409，不静默忽略。

## 4. 两轮功能检查

环境：dev-ap `127.0.0.1:35432/agent_platform_test`、`dev_ap_test`（`AGENT_DB_PASSWORD` 来自私有凭据根，不落仓库）；Flyway 已应用到 V115。

第一轮（定向，含两处修正）：

```
mvn -o -pl app-management -am -Dtest=SemanticConfigWiringFlowTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 首跑 3 error：`validate` 走 `get()` 拿到的 `semanticBindings` 已被展开为可读投影，内部复核又按请求白名单解析，报“包含未知键: appCode”。修正：内部行改用 `rowRefs`（只取 `bindingId/bindingRev`），外部请求仍走严格白名单。
- 次跑 1 failure：停用绑定后新发布在 `validate` 即被阻断（实现正确），测试原期望在 `activate` 才失败。修正测试为在 `validate` 断言 `CONFIG_RELEASE_NOT_ACTIVE`。
- 最终：5/5 通过。

第二轮（定向 + 回归）：

```
mvn -o -pl app-management,admin-common -am \
  -Dtest=SemanticConfigWiringFlowTest,ConfigReleaseFlowTest,SemanticBindingsFlowTest,RouteCoverageVerifierTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- `SemanticConfigWiringFlowTest` 5/5、`ConfigReleaseFlowTest` 8/8、`SemanticBindingsFlowTest` 6/6、`RouteCoverageVerifierTest` 10/10，合计 **29/29**，BUILD SUCCESS。
- 另：`mvn -o -q -pl app-management,platform-gateway -am -DskipTests compile` exit 0。

## 5. 未覆盖与回退

- 未跑网关真实 HTTP 全链（`/admin/semantic-bindings` 仅验证了 `Routes` 分支与权限目录覆盖；网关端到端留 P 统一验收）。
- 未跑并发激活/并发停用竞态（行锁与 CAS 依赖既有的 `lockRelease` 与绑定父行锁，未新增压力测试）。
- 未回查 OR 侧 release 四元组是否真存在（绑定只本地校验形状；跨系统一致性属阶段2 之后的联合补验）。
- 回退：删除 V115 引用表与本次代码改动即可回到阶段1；不触碰既有发布行与绑定修订，历史数据无需修复。
