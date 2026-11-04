# CR05 R6：新增技能草稿的向导接线

状态：新增实现，仅 Vue/TS 编译通过，功能未复核。按本组两轮上限没有启动第三轮功能测试；未修改原测试或断言，没有覆盖阶段1历史结果与哈希。本记录不表示 CR05 全部收口或功能 PASS。

## 精确文件白名单

- `web/admin/src/pages/onboarding/OnboardingSessionWorkspace.vue`
- `web/shared/onboarding-session.ts`
- 本证据 `docs/evidence/cr05-r6-onboarding-import-ui-20261104.md`

未修改其他前端、后端、依赖或既有功能用例；没有 stage/commit。

## 实现内容

计划的可选字段为 `sourceDocument`、`routePlan:{environment,routeProfileId}`、`importOverrides:{[operationId]:manualFields}`。复用已批准模板的原路线无需提供这些字段。

- 增加真实 OpenAPI JSON/YAML 文件上传与粘贴入口，按 UTF-8 限制1MiB；超限保留原内容。
- 专业配置只从 JSON 文件导入，不要求业务用户手写合同；文件只接 `routePlan/importOverrides`，限制256KiB、深度32、2万个节点、最多32项操作，拒绝危险对象键和未知人工合同字段。
- 人工合同必须显式提供名称、说明、执行、守卫、返回和失败语义；不生成 Schema、guard、expected，也不接客户端凭据或 authRef。后端 R1 仍须完整验证批准 profile/binding 与合同，文件内容不是授权证据。
- 专业配置操作提供显式候选选择，标注待服务端核验；允许取消旧的缺合同选择，不把这些候选写成 `supported=true`。
- 文件异步读取以当前权限、计划对象和独立读取代次约束；切会话、保存/回读、权限变化或后续同字段输入后，旧文件读取不能回填新表单。原 false/0/null 恢复方式保持不变。
- 新增 `IMPORT_SKILLS` 执行类型与按钮。有规格即可尝试后端受控导入，不受原 `preview.ready` 阻挡；dirty、busy、终态和编辑权限仍限制写操作。
- “刷新导入 / 继续”使用新的用户请求号触发后端重新回读原导入及核对已发布精确版本；前端不直接调用技能导入接口，不重建会话。执行失败只 GET 原会话恢复，防重和404恢复沿后端已冻结导入自然键。
- 与 connections 对齐直接 step.receipt：`{drafts,missing,sourceResolved,links:{skillWorkspaces}}`。显示实际 code/version/status/operationId，说明普通 GET 会话只回持久回执，并非实时发布检查。
- 技能入口优先取服务端 links，再取 draft.workspacePath；只接受当前实际 `#/skills/{code}` 详情路由（或等价 `/skills/{code}`），拒绝任意 URL 后按同一真实路由构造回退。实际技能编码先验证，不把API链接当工作台。
- 页面明确要求在原技能工作台完成真实 connection-test、acceptance、publish，由 ADMIN 显式授权新技能编码对应连接用途，再返回刷新。导入成功不等于验证、授权或发布；sourceResolved 只有后端明确 true 才展示来源就绪。

## 实际验证

2026-11-04，在本工作树 `web` 目录运行：

```text
npx vue-tsc --noEmit
```

结果：exit 0，无输出。该命令只编译 Vue/TS，没有运行测试、Vite构建或浏览器。阶段1原功能结果及最后夹具修正未复跑的状态不变，不能把本次编译记为其 GREEN。

未覆盖：浏览器文件上传/粘贴交互、dirty与权限切换时的异步竞态、实际session保存恢复、正式IAM调用、真实导入receipt刷新/丢响应、管理员授权、真实技能验收/发布后来源解析、完整向导出口。这些新增行为均登记为未复核。

## R6最终文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| OnboardingSessionWorkspace.vue | d4b2b128e90b28af62f871ad28bb4c7f7afdaa51be29250f2b7a9c3684ef908f |
| onboarding-session.ts | ca0199875d9c08397b79c83ff25657ae92f5f80b0cf7044fb4be863ecf5debcb |
