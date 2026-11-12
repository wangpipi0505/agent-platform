# 智能体技能参数选择交付记录

范围：`web/admin/src/pages/workspace/AppChatPanel.vue`，覆盖应用对话页与聊天型应用正式运行页；复用 `SchemaInputs.vue` 和 `buildDeclaredInputs`，未引入依赖或修改共享 mock 数据。

- 从 `GET /admin/apps/:code/available-tools?conversationId=...` 获取当前绑定目录；正式运行附带 `versionNo`。目录项包含精确 `code`、`version`、`inputSchema` 与 `parameterTemplate`。
- 可按技能名称或编码搜索，选择后载入该版本模板；执行直接提交 `toolSelection:{code,version}` 与类型化 `parameters`，无需填写自然语言问题。
- 必填、枚举、数值范围、布尔、对象和数组按现有 JSON Schema 校验；不合法参数阻断提交并保留输入。
- 切换技能、运行版本或新对话清空旧参数。执行或审批后更新目录上下文，但保留当前表单；切换技能或“重新载入模板”才采用新模板。
- 自由对话仍使用独立运行参数 JSON，不携带当前技能选择；附件入口保留。

## 定向验证

2026-11-05，在 `web/` 执行：

1. `npx vitest run --config tests/app-chat-tools.config.ts`：2 条通过。覆盖精确版本模板、原类型提交、必填阻断、切技能防串参、目录更新保留用户输入、显式模板重载、自然语言参数隔离与发布版本切换。
2. `npx vue-tsc --noEmit`：退出码 0。

先运行新增检查，原组件因没有下拉与目录请求而失败，再实现。按用户要求未执行全套回归或重复构建；最终 mock 构建和浏览器关键流转由主智能体统一完成。
