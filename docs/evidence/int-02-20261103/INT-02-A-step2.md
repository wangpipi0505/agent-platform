# INT-02 子步2（A 席）— 运行期解析、连接授权与 skill 受控入口

- 卡：INT-02（业务软件集成与配置管理能力提升）
- 子步：子步2「resolve 端点 + grant 表 + skill 受控入口」
- 席位：A（AP 后端）
- 日期：2026-11-03
- 工作树：`代码/_worktrees/upgrade-20261103/agent-platform`，分支 `upgrade/20261103`
- 基线 HEAD：`0504f798`（其上为 MNT-06 `e2b12d3f`、`b0604b08`）
- 契约依据：`contracts/configuration-profile.md` §2/§3/§10（AP `370fe994` 裁决回写）、`contracts/assistant-integration.md` §6/§8

## 1. 原责任 → 新责任

| 原责任 | 新责任 | 位置 |
| --- | --- | --- |
| 配置目录只有"档案 + 修订 + 状态" | 增加"谁可以用哪一版"的授权事实与运行期只读投影 | `app.connection_profile_grant`、`ApprovedConnections` |
| 一致性只在写入侧（CAS + requestId） | 运行期也要证明"解析到的是哪一版" | `ApprovedConnections.digest()`（不含时间） |
| skill 侧无受控入口 | skill 只能通过内部端点拿一次性解析事实，并留回执 | `ApprovedConnectionResolver`、`V317` |
| 状态码混用（通用 404/409/400） | 按 §10.2 拆出 `CONFIG_REF_NOT_FOUND` / `CONFIG_STATE_TRANSITION_INVALID` | `ConnectionProfileRepository.explain*`、`ConnectionGrants`、`ApprovedConnections` |

## 2. 改动文件

app 侧：
- `app-management/src/main/resources/db/migration/V112__connection_profile_grants.sql`：新增 `app.connection_profile_grant`（外键钉住 `connection_profile_version`、部分唯一索引保证"一使用方一有效行"、`revoked_at` 留痕、不存凭据）。
- `app-management/src/main/java/cn/agentplatform/app/infra/ConnectionGrantRepository.java`：授权行的授予/撤销/查询，`DuplicateKeyException` → 409 `CONFIG_REV_CONFLICT`，撤销 0 行 → 404 `CONFIG_REF_NOT_FOUND`。
- `app-management/src/main/java/cn/agentplatform/app/application/ConnectionGrants.java`：只有 APPROVED 且当前修订可被授权；用途必须 ⊆ 修订 `allowedUses`；同使用方同载荷回放 `replayed=true`，异载荷 409。
- `app-management/src/main/java/cn/agentplatform/app/application/ApprovedConnections.java`：`resolve(profileId, profileRev, environment)` 返回声明 + 钉住的已批准绑定 + 有效授权 + `resolutionDigest` + `resolvedAt`。
- `app-management/src/main/java/cn/agentplatform/app/api/InternalConnectionProfilesController.java`：`GET /internal/connection-profiles/{profileId}/versions/{profileRev}/resolve`（可选 `environment`），不做 ADMIN 判定，由内部票据与路由守卫把关。
- `app-management/src/main/java/cn/agentplatform/app/api/ConnectionProfileController.java`：追加 `GET|POST /{profileId}/versions/{profileRev}/grants`、`POST .../grants/{grantId}/revoke`（均 ADMIN 前置）。
- `app-management/src/main/java/cn/agentplatform/app/infra/ConnectionProfileRepository.java`：新增 `pinnedBindingRevisions`（只取 `status='APPROVED'` 且 `current_rev=binding_rev` 的绑定）；`explainProfile/explainBinding` 改按 §10.2 报码。
- `app-management/src/main/java/cn/agentplatform/app/application/EnvironmentBindings.java`：绑定只能引用 APPROVED 及以上（引用 DRAFT → `CONFIG_STATE_TRANSITION_INVALID`）。
- `app-management/src/main/java/cn/agentplatform/app/application/ConnectionProfiles.java`：`AUTH_KINDS` 增 `BEARER`；新增 `runtimeAuthKind()`——三个冻结形态可声明但解析/运行一律 `CONFIG_AUTH_SCHEME_UNSUPPORTED`。
- `admin-common/src/main/resources/agent-service-authority.json`：仅追加 5 条（3 条 grant 端点、1 条 `app.connection.resolve`（caller=skill）、1 条 `skill.approved-connection.read`），未重排既有条目（`git diff --numstat` = 60 增 0 删）。

skill 侧：
- `skill-registry/src/main/resources/db/migration/V317__connection_resolution_receipt.sql`：`skill.connection_resolution`（每次解析留回执，追加型，不存凭据）。
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/ConnectionResolutionReceipts.java`：写回执并回填 `receiptId/resolvedAt`。
- `skill-registry/src/main/java/cn/agentplatform/skill/application/ApprovedConnectionResolver.java`：调 app 的内部 resolve，校验形状（digest 64 位小写十六进制、authKind ∈ 三值、集合字段为数组），形状不符 502 `CONNECTION_RESOLUTION_MALFORMED`；不缓存。
- `skill-registry/src/main/java/cn/agentplatform/skill/api/ApprovedConnectionSkillController.java`：`GET /internal/skills/{code}/approved-connections/{profileId}/versions/{profileRev}`。

测试：
- `app-management/src/test/java/cn/agentplatform/app/infra/ApprovedConnectionFlowTest.java`（真实 PG，3 例）
- `app-management/src/test/java/cn/agentplatform/app/application/ConnectionProfilesRulesTest.java`（追加 3 例，共 13 例，纯规则）
- `skill-registry/src/test/java/cn/agentplatform/skill/application/ApprovedConnectionResolverShapeTest.java`（5 例，纯规则）

## 3. 不变量

1. 批准 ≠ 授权：`status='APPROVED'` 只是"可被引用"，没有 grant 行时 `grantedUses` 为空，调用方据此拒绝。
2. 授权不悬空：grant 外键钉到具体修订，删除/改写修订被数据库阻断。
3. 一使用方一有效行：部分唯一索引 + 应用层幂等回放；撤销后重新授予产生新行，历史行保留。
4. 只有当前已批准修订能解析：`status` 非 APPROVED 或 `profileRev != currentRev` 一律 `CONFIG_STATE_TRANSITION_INVALID`；停用是 `CONFIG_RELEASE_NOT_ACTIVE`。
5. 摘要稳定：`resolutionDigest` 覆盖声明 + 绑定 + 授权，不含 `resolvedAt`，同一份事实重复解析得到同一摘要。
6. 绑定必须自身已批准且被钉在该修订上：DRAFT 绑定、或指向别的修订的绑定都不进 resolve 结果。
7. 冻结鉴权形态不静默降级：`OAUTH2_CLIENT_CREDENTIALS/MTLS/SIGNED_REQUEST` 可声明，但解析与运行明确报 `CONFIG_AUTH_SCHEME_UNSUPPORTED`。
8. 内部端点不放宽管理面：resolve 不做 ADMIN 判定（调用方是服务），管理面三个 grant 端点仍要求 ADMIN。

## 4. 功能检查（命令与结果）

环境：`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`；
`AGENT_DB_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_test`、`AGENT_DB_USER=dev_ap_test`，口令从 `.tmp-devdb/dev-ap.env` 读入，未回显。

1. 第一轮（编译失败）：`mvn -o -pl app-management,skill-registry -am -Dtest='ConnectionProfilesRulesTest,ApprovedConnectionFlowTest,ApprovedConnectionResolverShapeTest' -Dsurefire.failIfNoSpecifiedTests=false test`
   → `ApprovedConnections.java:[78,49] 不兼容的类型: 无法推断java.util.ArrayList<>的类型参数`（`List<?>` 泛型推断），改流式 `map(String::valueOf).distinct().toList()`。
2. 编译校验：`mvn -o -q -pl app-management,skill-registry -am test-compile` → exit 0。
3. 第二轮（功能）：同一条命令追加 `RouteCoverageVerifierTest`，`-pl app-management,skill-registry,admin-common`：
   - `cn.agentplatform.common.authority.RouteCoverageVerifierTest` 10/10 绿（权限目录 JSON 结构、精确模板与 action 规则可加载）
   - `cn.agentplatform.app.application.ConnectionProfilesRulesTest` 13/13 绿
   - `cn.agentplatform.skill.application.ApprovedConnectionResolverShapeTest` 5/5 绿
   - `cn.agentplatform.app.infra.ApprovedConnectionFlowTest` 3/3 绿（2.7s，真实 PostgreSQL 16）
   过程中修了 3 处**测试夹具**问题（不是产品缺陷）：幂等键过短不合 `REQUEST_ID`、同一 requestId 跨用例外重复使用、`secretRef` 用了非法字符 `#`；另外把用例档案的 `allowedUses` 放到 `[read, write]` 才能构造"同使用方异载荷"冲突。产品代码只改了 `ApprovedConnections` 的泛型推断与 import。
4. 已知未跑：全量 reactor 测试、应用启动（`RouteCoverageVerifier` 会以真实控制器模板校验新增 5 条目录，待 P 全量窗口确认）、前端与生成客户端。

## 5. 未覆盖 / 待确认

- 未跑全量回归与启动；`ROUTE_COVERAGE_MISSING` 类问题只通过 `RouteCoverageVerifierTest` 的目录解析间接验证，真实启动校验留给 P 窗口。
- `skill.approved-connection.read` 的 `callerSource` 暂引 `RunService.java:255`（当前无实际调用方接线），接线后需改到真实调用点。
- 未做：健康探测、凭据实际注入、开放 API/客户端生成、前端页面。

## 6. 契约缺口（只登记，未私改契约）

1. skill 侧入口路径与入参（`/internal/skills/{code}/approved-connections/...`）未在 `assistant-integration.md`/`configuration-profile.md` 冻结。
2. 新增错误码 `CONNECTION_RESOLUTION_MALFORMED`（502）未在 §6 最小登记集登记。
3. `config_write_request` 表只覆盖档案/绑定的 requestId 幂等，grant 用的是"天然键幂等"（profile+rev+grantee+payload），未进统一写入记录表。
4. 解析结果不回传"绑定健康状态"（`healthPolicy` 只是声明），运行期健康判定仍缺席。
5. reserve/commit 两段式解析（长事务窗口）未定义。

## 7. 回退

- app 侧：`git revert <app 提交>`；数据库需手工 `drop table app.connection_profile_grant`（V112 down 已写在迁移文件头）。
- skill 侧：`git revert <skill 提交>`；数据库需手工 `drop table skill.connection_resolution`。
- 权限目录随 app 提交一起回退；网关只有前缀分支（子步1 已提交），不受影响。
