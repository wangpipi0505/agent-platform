# SEM-09 AP 侧证据：业务配置与使用端的语义体验

日期：2026-11-04　席位：O（OR 与语义）　提交：见本轮回执

## 1. 范围与边界

白名单内新增（AP `web/`）：

- `web/shared/semantic-runtime.ts`：表单构造、权威结果合并、目标处置与权限过滤的纯逻辑。
- `web/shared/SemanticEvidence.vue`：权威证据卡。
- `web/shared/GoalOutcomePanel.vue`：目标处置面板。
- `web/admin/src/pages/onboarding/SemanticBindingStep.vue`：接入向导·语义绑定步骤。
- `web/admin/src/pages/onboarding/GoalCriteriaStep.vue`：接入向导·成功判据步骤。
- `web/admin/src/pages/integration/SemanticBindingsPanel.vue`：SEM-02.2 绑定目录的管理面板。

未改动既有向导页面、路由、`web/shared/Chat.vue`、SDK 事件 Schema 与 `SkillExecution`；页面挂载与路由由 F/P 席按 §2.3 接线（不在本子步白名单）。

## 2. 关键不变量

1. **不手写稳定 JSON**：`buildSemanticBindingRequest` 只从“当前正式发布的选择集”取对象/动作/扩展；扩展必须 `ACTIVE` 且 `opCode` 匹配；对象/动作必须出现在选择集中。
2. **表单不扩权**：请求体只包含 `releaseRef/extensionId/opCode/objectType/skill/profiles/factFields/ruleRefs`；调用方注入的 `scope/grant/spaceCode` 等字段被丢弃（测试断言键集合）。页面不提供授权、数据范围、来源连接字段。
3. **完成只认服务端回执**：`authoritativeView` 在无服务端事实、或 `SUCCEEDED` 但无可核验回执时返回 `UNVERIFIED`；模型文本仅作说明，若与服务端不一致则置 `modelTextIgnored` 并在卡片上显式提示“已忽略”。
4. **目标处置与执行终态分离**：`goalOutcomeRows` 把 `ACHIEVED` 缺 `readbackRef` 降级为 `UNKNOWN` 并列出问题；`PENDING/UNKNOWN` 缺下一步同样列出。
5. **权限不足不露内部资源**：`filterProjection` 在无读取权限时返回空集与原因，不返回对象/动作/规则标识；有权限时也只展示已授权动作。

## 3. 验证

```
cd code/_worktrees/upgrade-20261103/agent-platform/web
npx vitest run tests/semantic-runtime.test.ts
  -> Test Files 1 passed, Tests 14 passed
npx vue-tsc --noEmit
  -> exit 0
```

全量 `npx vitest run`：439 例中 435 通过。4 个失败位于 `tests/approval.test.ts`（3）与 `tests/run-protocol.test.ts`（1），均为既有模块断言，未被本子步修改或引用；已核对失败点（`actionPlanView.secondConfirmRequired` 等）与本卡文件无关。

## 4. 未覆盖与回退

- 未运行真实后端联调，未产出按钮级页面截图（需要启动 dev server 与真实 IAM/engine）；不做 mock 截图充数。
- 路由/菜单挂载、`web/shared/Chat.vue` 的嵌入渲染未改（白名单外）。
- 绑定面板依赖 SEM-02.2 `/admin/semantic-bindings` 的 ADMIN 角色；错误按服务端信封展示，不做前端兜底假数据。
- 回退：删除新增组件与 `semantic-runtime.ts`，既有页面与向导零变化。
