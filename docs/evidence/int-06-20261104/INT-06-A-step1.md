# INT-06 子步1（A 席）：会话/运行受理接线 ConfigRelease 与发布页集成交接

- 日期：2026-11-04
- 席位：A（INT-06）
- 基线 HEAD：`d5cc13db`（docs(ui-08)，含 MNT-05 `e50eb1ae` 与 INT-05 `49e6508b`）
- 范围：engine 会话/运行受理的配置版本解析与 PG 事实接线；`AppPublishPanel` 集成交接区。
- 未触：`RunSnapshotService`/`RunAdmissionService`/`RunRecoveryCoordinator` 内部逻辑、`ToolCalls`、app-management Java、网关、MNT-05 语义位与 INT-02 `bindingRefs`。

## 一、原责任 → 新责任

| 责任 | 原位置 | 现位置 |
| --- | --- | --- |
| 会话创建时的配置版本解析与 PG 固定 | 无（只有 app 发布的 experience 投影） | `ChatSessions.create(code, body, actor)` → `RunService.resolveConfig` |
| 运行受理的配置版本解析 | 无 | `RunService.create(...)` → `RunService.resolveConfig` |
| 轮次不得换配置版本 | 无 | `ChatSessions.turn` → `pinnedConfig(session, req)` |
| 受信引用写入运行事实 | 无 | `RunService.bindConfig(runId, config)`（同一受理事务内） |
| 体感交接面的版本引用 | 无 | `AppPublishPanel.vue` 集成交接区（只读展示 + 无密钥示例） |
| 旧客户端行为 | 会话/运行原路径 | 未声明 `configReleaseId` 时同路径、同列（NULL） |

## 二、改动文件

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `engine-runtime/src/main/resources/db/migration/V227__run_config_release.sql` | 70 | 会话/运行各 4 列、3 约束、1 部分索引；文件头含回滚 SQL |
| `engine-runtime/.../application/RunService.java` | 2561（+119/−?） | `ConfigReference`、`resolveConfig`、`bindConfig`、`create`/`createReservedSessionTurn` 重载、10 参 `createInternal` |
| `engine-runtime/.../application/ChatSessions.java` | 363（+99/−?） | `create(code,body,actor)`、`get` 增加 `configReleaseId`、`turn` 固定引用、`pinnedConfig` |
| `engine-runtime/.../api/ChatSessionController.java` | 40 | 创建会话接受可选 body |
| `web/admin/src/pages/workspace/AppPublishPanel.vue` | 483（+112/−?） | 集成交接区：激活版本引用 + 不含密钥的接入示例 |
| `engine-runtime/src/test/.../application/RunConfigReferenceTest.java` | 223 | 9 例：解析/伪造/摘要/环境/缺档案/503 |
| `engine-runtime/src/test/.../application/ChatSessionConfigPinTest.java` | 91 | 5 例：会话固定与轮次冲突 |

## 三、不变量

1. 配置版本只由引擎向 app 的激活投影解析；客户端提交的 `configReleaseId` 仅是“提议”，摘要、环境、绑定修订一律取服务端响应。
2. 未声明 `configReleaseId` 的旧客户端/旧会话走原路径；新列保持 NULL，读侧不推断默认配置。
3. `configReleaseRef.configReleaseId` 与 `digest` 必须同时存在，摘要必须是 64 位小写 hex；否则 409 `CONFIG_RELEASE_NOT_ACTIVE`。
4. 提议值与激活值不一致、投影 `appCode` 不符、接入档案修订缺失、环境与引擎不一致，均为 409 `CONFIG_RELEASE_NOT_ACTIVE`（不新增同义错误码）。
5. 会话创建后引用固定；轮次带不同 releaseId → 409 `CONFIG_REV_CONFLICT`；旧会话（未固定）不接受轮次临时指定。
6. 轮次把固定引用作为**独立参数**传入 `createReservedSessionTurn`，不放进请求体，避免直连运行路径伪造；未固定会话仍走原 5 参重载，既有调用面与测试替身不变。
7. `bindConfig` 只在受理持久化块内、会话/运行插入之后执行；解析网络调用在事务之前，HTTP 不进长事务。
8. `versionRefs` 保持原样，`appVer/flowDefSha/prompt/skill/model` 语义不变；配置版本是并列事实。
9. `environmentBindingRevs` 当前记录 `{kind:"integration-profile",profileId,profileRev,environment}`，由投影的接入档案修订生成。
10. 交接区只读：不缓存密钥、不生成明文凭据；示例中的 ApiKey 位置是占位符。
11. 运行 `engine.chat_session`/`engine.run` 两处写入同形约束（digest 形状、引用成对、绑定修订为数组），索引按 `(tenant_id, config_release_id)` 部分索引。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `mvn -q -pl engine-runtime -am -DskipTests compile` | exit 0 |
| 2 | `mvn -q -pl engine-runtime -am -Dtest='RunConfigReferenceTest,ChatSessionConfigPinTest' -Dsurefire.failIfNoSpecifiedTests=false test` | **2 例失败**（测试夹具把 `RuntimeClients.get` 的返回值当成含信封 JSON；另 1 例断言消息因同一根因落在“无激活版本”分支）——保留，未改主代码 |
| 3 | 同上（夹具改成 `unwrap` 后的 data 形状） | 14/14 通过（9+5） |
| 4 | `mvn -q -pl engine-runtime -am -Dtest='RunConfigReferenceTest,ChatSessionConfigPinTest,RunSnapshotAssemblyTest,RunAuthorityAssemblyTest,RunAdmissionServiceTest,RunServiceContextPackTest,PromptFreezeDigestTest,RunToolProfileTest,RunRecoveryCoordinatorTest' ...` | 59/59 通过（9/5/1/4/8/9/4/13/6） |
| 5 | `web && ./node_modules/.bin/vue-tsc --noEmit` | exit 0 |
| 6 | `vite build --config admin/vite.config.ts` / `webapp` / `assistant.js` | 三端均 built；assistant.js 242.18 kB（gzip 78.94） |
| 7 | `vitest run` | 347 passed / 4 failed（`tests/approval.test.ts` 3 例、`tests/run-protocol.test.ts` 1 例，均为未改动共享模块的既有失败） |
| 8 | 真实 PG：`/dev/tcp/127.0.0.1/35432`、`/25432` | 均 down，未执行 |

## 五、未覆盖

1. **真实 PG 定向未执行**（本轮 35432/25432 均不可用；与 MNT-05 两子步同一环境限制）：V227 未在任何库 apply；“带引用受理后运行事实可反查、伪造引用 HTTP 拒绝、恢复用原引用”仅有单元级证据（解析/固定逻辑），缺数据库事实。
2. `EngineTestDatabase` 旧夹具硬编码 25432，本子步按领取单未规范化。
3. `AppPublishPanel` 交接区只有类型检查与既有 21 例组件测试（未新增浏览器 E2E）；管理员 API 实际返回的激活行未在真实环境核对。
4. `environmentBindingRevs` 只到接入档案修订，未到 connection-level environment binding（投影未暴露）。
5. `ToolCalls` 白名单可用但本子步未改：工具调用的 `bindingRefs` 仍是既有权威，不携带配置版本。

## 六、契约缺口（只登记，未私改）

1. `GET /v1/apps/{code}/experience` 投影未返回 `connectionProfileRefs`/`environmentBindings`，引擎无法把 connection 级绑定写进 `environmentBindingRevs`；当前用接入档案修订替代并显式标注 `kind`。
2. app 无 `/internal/config-releases/...` 解析端点；引擎复用面向浏览器的 experience 投影（需 `SERVICE` 票据通过 `/v1` 认证）。若后续要收紧为内部协议，需新增端点并登记 §8 六项。
3. `AgentPublishPanel` 交接示例使用 `POST /v1/apps/{code}/sessions` 提议 `configReleaseId`；`/embed/ticket` 不接收 `configReleaseId`，票据本身仍只绑定 app/biz/principal/environment 上限。
4. 引擎的“当前环境”取 `agent.integration.environment`（默认 production）；契约未定义该配置键，需在部署文档或 §8 登记。
5. 旧客户端语义为“列 NULL、不解析”；若未来要求历史运行也可反查配置版本，需要回填/迁移策略，未在本子步定义。

## 七、回退

- 代码：`git checkout -- <6 个改动/新增文件>`（或提交后 `git revert <commit>`）。
- 数据：V227 文件头已附逐条回滚 SQL（先删索引、约束，再删列）；回滚只影响新增列，既有 `versionRefs`、运行与会话行不动。
- 会话/运行在回退后按“未绑定配置版本”读取，旧客户端行为不变。
