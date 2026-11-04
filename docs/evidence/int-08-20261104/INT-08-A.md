# INT-08（A 席）：配置管理台六页面与接入诊断（只读组合）

日期：2026-11-04
席位：A（后端/集成）
基线 HEAD：`7256fdb5`（feat(onb): 业务接入模板目录与服务端预检…）
范围：`web/admin/src/pages/integration/` 六个页面、共享纯函数模块、`web/shared/api.ts` 客户端方法、路由与 page-access 单一来源登记、定向测试。
未触：后端 app-management/engine-runtime 代码、`web/shared/routes.ts`、生成链、既有 onboarding 页面与 `web/shared/onboarding.ts`（其他席位未提交改动，未暂存）。

## 一、原责任 → 新责任

| 能力 | 原责任 | 本子步落点 |
| --- | --- | --- |
| 连接档案维护 | 无管理台入口 | `ConnectionProfilesPage.vue`：列表、修订、创建、批准/停用（expectedRev） |
| 环境绑定维护 | 无管理台入口 | `EnvironmentBindingsPage.vue`：列表、修订（secretRef 仅显示“已配置引用”）、创建、批准/停用 |
| 接入档案维护 | 无管理台入口 | `IntegrationProfilesPanel.vue`：按应用列表、复制为草稿、批准/停用 |
| 体验编辑 | 无管理台入口 | `ExperienceEditor.vue`：欢迎语/能力说明/推荐问法/呈现编辑，批准/停用 |
| 配置发布流程 | 无管理台入口 | `ConfigReleasesPanel.vue`：激活卡片 → 复制草稿 → 差异/权限检查 → 校验 → 批准 → CAS 激活 → 影响面/脱敏导出 |
| 接入诊断 | 无管理台入口 | `IntegrationDiagnosticsPanel.vue`：身份/票据/版本权限/档案/连接/绑定六阶段只读状态，UNKNOWN 如实标注 |
| HTTP 客户端 | `shared/api.ts` 无配置管理方法 | `integrationClient(call)` + `integrationPaths`，本地类型，不依赖生成链 |
| 路由/权限 | 仅既有页面 | `router.ts` 六条路由 + `page-access.ts` 六条目（读=session，写=admin；仅配置发布进菜单） |

## 二、改动文件

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `web/admin/src/pages/integration/integration-console.ts` | 615 | 形状解析、复制/校验/差异/阻断/影响/脱敏导出/诊断组合、错误码文案 |
| `web/admin/src/pages/integration/ConnectionProfilesPage.vue` | 154 | 连接档案 |
| `web/admin/src/pages/integration/EnvironmentBindingsPage.vue` | 159 | 环境绑定 |
| `web/admin/src/pages/integration/IntegrationProfilesPanel.vue` | 202 | 接入档案 |
| `web/admin/src/pages/integration/ExperienceEditor.vue` | 165 | 体验编辑 |
| `web/admin/src/pages/integration/ConfigReleasesPanel.vue` | 262 | 发布主流程 |
| `web/admin/src/pages/integration/IntegrationDiagnosticsPanel.vue` | 117 | 只读诊断 |
| `web/tests/integration-console.test.ts` | 260 | 11 例定向 |
| `web/shared/api.ts` | 300（+102） | 客户端方法与类型 |
| `web/admin/src/router.ts` | 205（+36） | 六条路由 |
| `web/admin/src/page-access.ts` | 109（+6） | 六条目 |
| `web/tests/navigation-policy.test.ts` | +8/−4 | 单一来源计数 15→16 |

## 三、不变量

1. 页面不直改生产：激活必须经过“草稿 → 校验 → 批准 → 激活”，激活按钮仅在 `APPROVED` 且本地阻断为空时可用；后端仍会 CAS 与引用复核。
2. 复制草稿仅允许同环境激活版本；跨环境复制被前端拒绝并提示重新选择引用。
3. 全部写操作携带新 `requestId`；批准/停用携带当前 `expectedRev`；409 `CONFIG_REV_CONFLICT` 显示“刷新后以新的 expectedRev 重试，同一 requestId 只能重放相同内容”。
4. 停用/回退展示影响：激活不回溯既有运行，旧发布转 SUPERSEDED，停用后新会话返回 `CONFIG_RELEASE_NOT_ACTIVE` 且不回退其他环境。
5. 导出只含逻辑引用与摘要（releaseId/appCode/appVersion/status/digest/candidateSha256/activationScope/experienceRef/integrationProfileRef/connectionProfileRefs/skillRefs），单测断言不含 secretRef/token/createdBy/baseEndpoint/password。
6. 环境绑定页不展示 `secretRef` 原值，只显示“已配置引用/未配置”。
7. 诊断只读：不创建会话/运行/审批；身份阶段无只读投影时标 `UNKNOWN`，不得伪造成 OK。
8. 复用 MNT-02 `page-access.ts` 单一来源（路由 meta.access 与菜单同源）与 `resource-types.ts` 的稳定外壳思路（本卡新增同名解析器，不改既有导出）。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `vitest run tests/integration-console.test.ts` | 9/11，2 例失败（错误码文案断言、未知列表形状未抛错） |
| 2 | 修正 `parseRows` 对非数组/非 items 形状抛错、断言改匹配既有文案 | 11/11 |
| 3 | 干净 worktree（`git worktree add --detach HEAD`，仅复制本卡文件 + `node_modules` junction）`vue-tsc --noEmit` | exit 0 |
| 4 | 同 worktree `vitest run` 全量 | 402 passed / 4 failed（`approval.test.ts` 3、`run-protocol.test.ts` 1，均为既有基线失败，未改断言） |
| 5 | 同 worktree `vite build admin/webapp/assistant.js` | 三端均 exit 0；`dist/assistant.js 251.96 kB`（gzip 81.90 kB） |
| 6 | 主 worktree `vue-tsc` | 仅剩其他席位未提交 onboarding 改动导致的 2 条错误（`TEMPLATE_SERVICE_UNAVAILABLE` 缺失），本卡文件 0 错误 |

说明：主 worktree 同时存在其他席位的未提交改动（`web/shared/onboarding.ts`、`web/admin/src/pages/onboarding/*`、`engine-runtime/.../RunService.java` 与 goal 相关新文件），`vue-tsc`/admin 构建被其阻断，故用干净 worktree 做交付验证；未暂存、未修改这些文件。

## 五、未覆盖

1. 真实后端 HTTP 联调：35432/25432 均 down，未验证列表/创建/批准/激活的真实响应与 `expectedRev` 冲突路径。
2. 浏览器端到端：未在真实管理台会话中走完六页流程（仅类型检查、组件编译与纯函数单测）。
3. 生成链 P 窗口：`/admin/connection-profiles` 等新路径未进入 `generated/api.d.ts`，`web/shared/routes.ts` 的 `satisfies` 未收录；本卡把路径集中在 `shared/api.ts`。
4. `GET /admin/apps/{code}/integration` 的真实投影形状（credential vs list）未验证，`parseCredential` 对 `claimLimits` 的解析按现有读法实现。
5. 诊断面板的“连接可达/health”阶段只依据连接档案状态，未做实际网络探针（本卡要求只读、不触业务写入）。

## 六、契约缺口 / 白名单外改动

1. 无 ADMIN 接入诊断端点：诊断只能组合只读投影，身份源阶段标记 UNKNOWN；若后续提供只读诊断端点，应替换该阶段而非新增写探针。
2. `integration-console.ts` 为白名单外新增共享模块（六页共用），已登记；未新增后端接口或生成链文件。
3. `navigation-policy.test.ts` 的主入口计数由 15 改为 16，原因是新增“配置发布”菜单项（单一来源策略要求同步），非放宽断言。
4. 错误码沿用登记集（`CONFIG_REV_CONFLICT`/`CONFIG_STATE_TRANSITION_INVALID`/`CONFIG_VERSION_UNKNOWN`/`CONFIG_REF_NOT_FOUND`/`CONFIG_RELEASE_NOT_ACTIVE`），未私造。
5. 页面提交体字段按各 Controller 的 BODY_KEYS 构造；创建发布使用客户端生成 `releaseId`（`rel_` + 随机），若后端要求服务端生成需在契约中明确。

## 七、回退

- `git revert <提交号>` 可整体回退六页、客户端方法、路由与 page-access 条目；无数据库迁移、无后端接口变更。
- 若仅需隐藏入口：移除 `page-access.ts` 的 `integration-config` 菜单项与 `router.ts` 对应路由即可，不影响既有页面与后端。
