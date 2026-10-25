# MCP 注册与草稿检查前端交接

日期：2026-10-20。状态：实现与受控验证完成，已冻结，等待独立复核；web target 已释放给 root。真实 MCP 后端纵切未执行。

## 实现范围

生产文件仅 `web/admin/src/SkillWizard.vue`、`web/admin/src/ResourceDetail.vue` 与新增 `web/admin/src/McpToolsPanel.vue`。

- MCP 向导增加准确工具名（区分大小写），空值及纯空白不能离开接口步骤；地址明确标为 MCP SSE 地址。工具名原样进入确认与 save 事件。HTTP / ALGORITHM 无新增必填条件，OpenAPI 导入路径未改。已有 guard.roles / guard.attributes 作为向导初值保留。
- 详情从 selected 对应 versions 条目构造完整 definition，嵌套合同字段优先、状态使用该版本元数据；展示的绑定名、类型、权限与 Schema 不再取顶层 published currentVersion。详情加载增加 code / token / tenant / principal 的请求归属与 abort / generation，旧资源响应不能覆盖当前选择。
- MCP 面板仅显式点击时请求 `POST /admin/skills/{code}/drafts/{version}/mcp-tools`，body 为 undefined；endpoint、凭据或合同不进入发现请求正文。仅已保存 MCP DRAFT 可检查。EDITOR 为前提，使用凭据引用时另需 ADMIN。公开 API 已将引用脱敏，权限判断使用所选版本 `authConfigured === true`，同时兼容旧 fixture 的非空 authRef；界面不要求读取原引用。
- 发现结果展示协议、服务信息、能力、限制、工具名与说明，并能展开及复制完整原始 inputSchema；精确比较绑定名称，缺失时给出提示。复制只取得文本，无一键应用。错误只能显式重试；在途点击不会并发。code / version / definition / token / tenant / principal / roles / 有效权限变化或卸载，会清除旧结果并 abort；generation 与 identity 同时防止忽略取消的旧响应及 finally 回填。
- 390 宽度下详情顶栏允许换行，新面板文本和完整 Schema 可换行/滚动读取；沿用现有 Element Plus 与第二版设计令牌。

没有修改 Java、契约、App.vue、EvaluationPanel / Report / observer、NamedInputs、DebugPanel、SchemaInputs、SDK 或 Chat；没有更新全局进展或原评审报告，没有提交、发布、启停其他服务或派子 agent。受控 fixture 只放在 tests，生产没有假数据回退、明文密钥或持久化 token。

## 失败证据与修复验证

修改前先保存两个已有生产文件的完整字节基线与 SHA：`frontend-mcp-registration-baseline/`、`frontend-mcp-registration-baseline-sha256.json`。新增文件在最终清单中记为 before=null / newFile=true。

- `frontend-mcp-registration-unit-red.log`：原向导缺工具名放行、已有守卫清空、缺少所选 definition、旧资源加载及面板不存在，共 5 项断言失败。
- `frontend-mcp-registration-panel-red.log`：面板空骨架阶段 17 项断言失败，无未处理错误；随后实现请求、权限、取消与并发锁。并非宣称最终所有新增用例都曾在修改前失败，既有正确分支也保留在回归中。
- `frontend-mcp-registration-template-red.log`：补充 SFC parse 错误断言，捕获新增模板闭合标签错误；修复后重跑测试及真实构建。
- `frontend-mcp-registration-authconfigured-red.log`：公开响应仅给 authConfigured 时，原 authRef 判断错误地发出请求，单项红测后修复。
- `frontend-mcp-registration-roles-red.log`：有效 edit/admin 布尔不变但角色集合改变时旧请求未取消，单项红测后补充角色身份约束。
- `frontend-mcp-registration-mobile-red.json` / `frontend-mcp-registration-tools-390-red.png`：Chrome 实际测得详情顶栏按钮越界；已局部换行修复。测量另等待下拉菜单退场，截图禁用动画，避免把退场浮层当最终布局。

最终命令在本工程 `web` 目录执行，均退出 0：

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `npm test -- --reporter=dot` | 28 文件 / 127 项通过；原 102 项 + 新增 25 项 | `frontend-mcp-registration-unit-all.log` |
| `npm test -- tests/mcp-registration.test.ts --reporter=verbose` | 25 项真实 SFC setup/watchers 测试通过，包含模板解析检查 | `frontend-mcp-registration-unit-green.log` |
| `npm run build` | vue-tsc 与 Admin / WebApp / SDK 三端构建通过；保留原有 chunk 大小提示 | `frontend-mcp-registration-build.log` |
| `node tests/mcp-registration-browser.mjs` | 本机实际 Chrome、真实 SFC、受控 API 共 13 项检查通过，pageErrors=[] | `frontend-mcp-registration-browser.log/.json` |
| `node tests/mcp-registration-compat.mjs` | 原异步 3 项、原边界 13 项、原命名输入 18 项 + 评测输入 5 项全部通过，pageErrors=[] | `frontend-mcp-registration-history.log` 及 `frontend-mcp-registration-history-*.json` |

新增 Chrome 检查覆盖：空工具名阻断；准确绑定和守卫原样提交；所选草稿精确路由与空请求正文；完整原始 Schema 与协议；显式复制不保存；已发布版本与 HTTP 不检查；EDITOR / authConfigured 的 ADMIN 提示；服务错误显式重试、名称大小写不匹配；慢响应在版本、账号、权限、code 切换后不回填；重复点击只有一次请求；请求账本无业务调用、保存或发布。

历史重放读取原冻结脚本，仅重定向证据前缀与 Chrome profile；保留原兼容 runner 已有的报告元数据适配与全部业务断言。原测试、原证据文件及原评审未改写。

## 截图与冻结

以下实际截图已读图核对；1440 / 390 宽度检查均断言 document.scrollWidth <= innerWidth：

- `frontend-mcp-registration-wizard-1440.png`、`frontend-mcp-registration-wizard-390.png`。
- `frontend-mcp-registration-tools-1440.png`、`frontend-mcp-registration-tools-390.png`：绑定、完整原始 Schema、服务信息及限制可读；390 顶栏正常换行。
- `frontend-mcp-registration-credential-permission-1440.png`、`frontend-mcp-registration-slow-switch-390.png`：凭据权限提示和切换后的无旧结果状态。

`frontend-mcp-registration-sha256.json` 冻结七个生产/测试/fixture/runner 文件的 before/after SHA、完整冻结副本路径，并验证原冻结组件/测试共 16 项 SHA 不变。已有文件完整基线位于 `frontend-mcp-registration-baseline/`；全部修改后完整副本位于 `frontend-mcp-registration-frozen/`。

本仓库 Git 无 HEAD，补丁由保存的真实基线和最终文件生成，不使用空 diff：`frontend-mcp-registration-forward.patch` 与 `frontend-mcp-registration-reverse.patch`，每份 53,193 字节、完整上下文。`git apply --reverse --check docs/evidence/frontend-mcp-registration-forward.patch` 及 `git apply --check docs/evidence/frontend-mcp-registration-reverse.patch` 均退出 0；只检查，不实际应用。

## 剩余边界

受控接口验证不能视为真实 MCP 协议、资源限制、业务执行、连接测试或验收通过。本次未访问真实 MCP runner；后端资源修复、独立审查、统一回归与进程更新完成后，由 root 统一执行真实纵切。

发现结果不保存到草稿。`SkillCatalog.save` 目前缺少 expected version/hash 并发保护；需先补服务端条件更新后再设计草稿编辑/应用，不能用前端旧副本覆盖合同或守卫。此限制已在界面及本报告明确。
