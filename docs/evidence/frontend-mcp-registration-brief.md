# MCP 注册与草稿检查界面

本任务接续 v1.2 T11/T30，补上已实现 MCP 后端协议入口的最小可用前端。后端当前仍在资源边界返修，前端受控 fixture 可以先准备，真实纵切必须等待后端审查、统一回归与更新进程。用户要求简单好用、统一现有第二版风格，无需另作视觉方向探索。

## 范围与约束

- 仅独立新工程；Java/契约不改；Vue3.5/TS/ElementPlus 沿用本工程 API/Auth/permission 组件和样式。
- 当前 SkillWizard 的 MCP 类型没有 toolName 输入，无法形成后端要求的精确工具绑定。添加 MCP 专有的准确工具名字段和校验，接口地址明确为该 SDK 版本支持的 SSE 地址；不要生成占位工具、假契约或自动调用业务来探测。
- 现有管理接口：`POST /admin/skills/{code}/drafts/{version}/mcp-tools`，无需 body。后端只使用已保存的本租户 MCP DRAFT，EDITOR，非空 authRef 另要求 ADMIN。返回 protocolVersion/serverInfo/capabilities/tools{name,description,inputSchema}/limits。不把凭据或 endpoint 放入此请求正文。
- 公开 SkillController.publicView 会递归将 authRef 转为 authConfigured 布尔值；界面权限判断使用所选版本 authConfigured，不尝试读取被遮蔽的引用。secretRef 同理仅公开 secretConfigured。
- 可为所选草稿增加“检查远端工具”的只读操作与可读列表，展示绑定名称是否存在，展开完整原始 inputSchema。操作须显式触发；不得自动调用 tools/call、改写合同/守卫、发布，或把发现结果当作验收成功。
- 本增量不直接把发现结果保存回可变草稿。现有 SkillCatalog.save 缺少 expected version/hash 条件，后续草稿编辑必须先补服务端并发保护；不能靠前端旧副本覆盖当前合同来实现“一键应用”。本次可展示名称/Schema，允许用户明确复制文本；复制不构成保存或验收。
- ResourceDetail 顶层可能是已发布 currentVersion，必须使用当前 selected 对应 versions 条目的完整 definition/展开字段确定 type/toolName/status/authRef/schema，不能检查了另一版本还显示当前绑定。新检查须有 code/version/identity/generation/abort 约束，切换资源/版本、权限或账号变化、卸载时丢弃旧结果并停止重复请求；非 MCP 或非 DRAFT 不发请求。
- HTTP/ALGORITHM/OpenAPI 注册语义不改。若共享向导用于已有值，保留传入 guard roles/attributes，不能初始化空守卫后覆盖。无明文密钥、浏览器持久 token 或假数据回退。
- 不修改已审 EvaluationPanel/Report/observer、NamedInputs、DebugPanel、SchemaInputs、SDK/Chat；App.vue 只在确有必要时最小接线且先保存完整基线。

## 验证及证据

先保存拟修改文件完整基线与 SHA，再写关键失败路径的真实 SFC/交互测试：MCP 缺工具名不出向导、正确绑定原样提交、HTTP 不被新增字段误约束；切选版本准确路由；角色/authRef 提示；慢响应在版本/账号变化后不得回填；重复点击不会并发检查；错误可显式重试；发现不调用业务、不自动保存/发布。只读信息应在 1440/390 宽度可用。

执行完整前端测试、类型检查、三端构建；实际 Chrome 受控协议/权限/慢响应状态及截图检查。Playwright 本机 Chromium 已获用户授权。不要在尚未更新的后端运行真实 MCP runner并冒认成功。保留现有102项/三构建和历史23检查，不弱化测试。

报告 `docs/evidence/frontend-mcp-registration.md`；另写修改文件 SHA、前后完整补丁（Git无HEAD，不能用空diff）和测试/构建/截图路径。冻结并释放 web target，供独立复核；不改全局进展/原评审报告，不提交、不启停其他服务、不派子 agent。
