# SEM-02.2 阶段1（AP 语义绑定目录）实施证据

日期：2026-11-03　席位：A（AP 后端）　分支：`upgrade/20261103`

## 1. 范围与白名单

| 动作 | 文件 |
| --- | --- |
| 新增迁移 | `app-management/src/main/resources/db/migration/V114__semantic_bindings.sql` |
| 新增领域 | `app-management/src/main/java/cn/agentplatform/app/domain/SemanticBinding.java` |
| 新增仓储 | `app-management/src/main/java/cn/agentplatform/app/infra/SemanticBindingRepository.java` |
| 新增服务 | `app-management/src/main/java/cn/agentplatform/app/application/SemanticBindings.java` |
| 新增控制器 | `app-management/src/main/java/cn/agentplatform/app/api/SemanticBindingsController.java` |
| 权限目录 | `admin-common/src/main/resources/agent-service-authority.json`（5 条，`/admin/semantic-bindings*`） |
| 新增测试 | `app-management/src/test/java/cn/agentplatform/app/infra/SemanticBindingsFlowTest.java` |

未触达：`IntegrationConfigReleases.java`、`AppService`、`PublishedMetadataService`（A 席子步2 文件）、`skill-*`/`engine-*` 任何文件。

## 2. 数据模型与不变量

- 父表 `app.semantic_binding(tenant_id, app_code, binding_id, current_rev, status ACTIVE|DISABLED, ...)`，
  修订表 `app.semantic_binding_revision(tenant_id, binding_id, binding_rev, release_space_code, release_id,
  release_version, release_content_digest, extension_id, extension_digest, op_code, skill_code,
  skill_version, skill_sha256, profiles jsonb, declaration_sha256, ...)`。
- 修订只追加：写入路径只有 INSERT；`current_rev` 用 `select ... for update` + CAS 推进，0 行即 409。
- 声明摘要 `declaration_sha256` 覆盖 releaseRef 四元组、扩展摘要、opCode、技能精确版本与只读能力集合；
  不含 `requestId`/`expectedRev` 这类写入控制字段。
- `profiles` 只接受 `read-only@1.0`（非空、无重复、≤4）；`extensionId` 可缺省为 NULL，缺省不等于空串。
- 绑定状态 ACTIVE 只表示目录可用；对外生效仍需阶段2把它组合进 ConfigRelease。

## 3. 接口与错误映射

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/admin/semantic-bindings?appCode=` | 列出应用下的绑定当前状态 |
| POST | `/admin/semantic-bindings` | 新建绑定（拒绝 `expectedRev`） |
| GET | `/admin/semantic-bindings/{bindingId}` | 当前状态 + 全部不可变修订 |
| POST | `/admin/semantic-bindings/{bindingId}/revisions` | 追加修订（`expectedRev` 必填） |
| POST | `/admin/semantic-bindings/{bindingId}/disable` | 停用（`expectedRev` 必填，保留历史） |

| 情形 | HTTP | code |
| --- | --- | --- |
| 未知字段、类型/长度/枚举越界、坏 UUID 形状 | 400 | `SEMANTIC_FIELD_OUT_OF_RANGE` |
| 同一 requestId 已用于不同载荷 | 409 | `CONFIG_REV_CONFLICT` |
| `expectedRev` 与当前修订不一致 | 409 | `CONFIG_REV_CONFLICT` |
| 应用不存在 / 绑定不存在 | 404 | `CONFIG_REF_NOT_FOUND` |
| 已停用后追加或重复停用 | 400 | `CONFIG_STATE_TRANSITION_INVALID` |

`requestId` 重放走既有 `app.config_write_request`：同号同载荷返回上次响应并标记 `replayed=true`，
同号异载荷 409；不新增审计表。

## 4. 功能检查（每项最多两轮）

环境：`AGENT_DB_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_test`、`AGENT_DB_USER=dev_ap_test`，
真实 PostgreSQL 16.15，Flyway `app` schema（当前版本 114）。

第一轮（定向，先红后绿）：

```
mvn -o -pl app-management -am -Dtest=SemanticBindingsFlowTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 首跑测试编译失败：`PlatformException.status/code` 为私有字段，改用 `status()/code()` 访问器；
  未放宽任何断言。修正后 **Tests run: 6, Failures: 0, Errors: 0**。
- 覆盖：四元组与技能版本落库、同 requestId 重放只写一次、修订追加且旧修订摘要不变、
  `expectedRev` 过期 409、未知字段/坏 UUID 400、停用保留历史并阻断后续修订、未知应用 404。

第二轮（回归，含权限目录路由覆盖）：

```
mvn -o -pl app-management,admin-common -am \
  -Dtest=SemanticBindingsFlowTest,ConfigReleaseFlowTest,RouteCoverageVerifierTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- `SemanticBindingsFlowTest` 6/6、`ConfigReleaseFlowTest` 8/8、`RouteCoverageVerifierTest` 10/10，
  合计 **24/24，0 失败**。
- 编译：`mvn -o -q -pl app-management -am -DskipTests compile` 退出码 0。

## 5. 未覆盖与交接

1. **网关入口未接线**：`platform-gateway/.../domain/Routes.java` 只把 `/admin/config-releases` 等前缀转
   app，`/admin/semantic-bindings` 目前会被网关判为不存在入口。该文件不在本子步白名单，本席未改；
   需在阶段2（或网关批次）补一行并在真实 HTTP 链路验收。
2. **与 ConfigRelease 的组合引用**：本阶段只保存"应用 -> 扩展/技能版本"的目录，尚未在发布快照中
   固定 bindingId+bindingRev，也没有把绑定状态纳入发布门禁；这是阶段2的范围。
3. **与 OR 的联调**：绑定只按声明做形状校验，未回查 OR 扩展是否真实存在于该 release；阶段2 应由
   受权路径核验（不得信任客户端自报摘要）。
4. **HTTP 层**：本轮未启动服务做 HTTP 级测试，控制器仅通过编译与路由目录覆盖；真实 JWT/网关链路
   留统一验收。

## 6. 回退

按 V114 末尾手写回滚删除两张表（先 `semantic_binding_revision` 再 `semantic_binding`，最后索引）；
本卡未改动既有表，回退不影响其他配置目录。
