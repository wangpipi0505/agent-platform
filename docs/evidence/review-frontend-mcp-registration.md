# MCP注册与草稿只读检查：独立前端评审

日期：2026-10-20。结论：**APPROVE（本次前端增量），未发现确定P1/P2。** 不代签真实MCP协议、后端资源限制、业务执行或完整T11/T30验收。web target已释放。

## 范围与基线

唯一工程根`G:/207/项目/算法重组工具/代码/agent-platform`。沿本工程AGENTS、implementation-brief、contracts/README与冻结v1.2；本轮读`frontend-mcp-registration-brief.md`、实现报告、完整forward补丁、SHA清单及三个生产文件。使用Vue/code-review/requesting-code-review/frontend-design/verification-before-completion/ponytail检查原则，不重开已审异步评测。

亲自执行Get-FileHash：7/7 after摘要匹配，16项protected均匹配，forward/reverse补丁SHA均匹配。无HEAD，采用保存基线与完整补丁评审，不称Git diff。生产改动仅SkillWizard、ResourceDetail、新McpToolsPanel；原评测/命名输入/调试保护文件未变。

生产冻结SHA：

```text
SkillWizard.vue    382c85cbcee71d1b9ac49244b17ee82a37d845fd60b27d9322878b6f450b2d3f
ResourceDetail.vue 1e2dbfbf1b64168af0ade67db0d8f95cdfc845db684dab465186e5a96fdbda86
McpToolsPanel.vue  7acb199a19842fa641f020c8e1df5190e19a89f294d59426c7e41f9294420a8f
```

只读查看SkillController.publicView、SkillCatalog的版本表示/发现入口与Actor.requireRole，核对真实公开字段及权限语义；未运行Maven、访问真实MCP、改后端或启停服务。本次只写本新报告，不修改作者源码、测试、报告、证据或其他历史评审。

## 核验结论

| 关注点 | 结果 |
| --- | --- |
| 准确工具名与注册守卫 | MCP接口步骤拒绝空白toolName；准确名称不做大小写转换，原样进入确认/save事件。guard.roles与attributes从传入值初始化，正常角色列表和属性原样保留，不用空守卫覆盖。HTTP/ALGORITHM不受新MCP必填约束；OpenAPI接线不变。 |
| 所选版本与完整definition | ResourceDetail按selected查versions，嵌套definition优先、status取版本元数据；展示绑定名、类型、schema、执行/guard来自同一选定版本，未回退顶层published绑定。后台实际versionNo契约匹配。 |
| 精确路由与正文 | 仅显式检查调用`POST /admin/skills/{编码}/drafts/{version}/mcp-tools`，code编码，body=undefined；不会把endpoint、凭据、schema或输入传作发现正文。非MCP或非DRAFT不检查。 |
| 公开凭据状态与权限 | 后端递归将authRef转为authConfigured布尔值；前端从所选definition的authConfigured===true判断额外ADMIN要求，不要求读取已脱敏引用。兼容旧非空authRef fixture。共用auth.can与Actor.requireRole均允许ADMIN覆盖EDITOR，未把这一实际公共权限语义误报为缺EDITOR。EDITOR单独检查带凭据草稿会被前端阻断。 |
| 请求归属 | 面板identity包含code/version/完整definition/token/tenant/principal/角色集合与有效权限，同步watch重置并abort。成功、错误、finally均核generation、identity和原signal；忽略取消的晚响应不能回填或清新请求锁。ResourceDetail读资源也按code/kind/token/tenant/principal与generation约束；卸载失效。 |
| 重复与恢复 | checking同步入口guard阻止在途重复请求，按钮也禁用；失败不自动重试，用户明确重试才再次发起。响应不完整显示错误，不用假数据补齐。 |
| 只读发现 | 远端工具说明/schema使用插值及JSON文本展示，不执行HTML或schema引用。绑定名按完全相等比较，大小写不同提示未找到。复制只写剪贴板或显示手工复制提示，没有应用合同、保存、连接业务调用、验收或发布。 |

既有ResourceDetail的受控连接测试、发布等动作继续独立存在；本次“无业务/保存/发布”的结论仅针对新增发现路径，未将它扩张为整个详情页不能执行原动作。

## 本reviewer亲跑证据

### 25项真实SFC定向测试

web目录实际执行：

```text
npm test -- tests/mcp-registration.test.ts --reporter=verbose
```

08:08:47，进程退出0，1文件/25测试通过，633ms。该测试编译真实SFC setup并运行Vue watchers，覆盖空/空白名、准确绑定与guard、HTTP/ALGORITHM、nested definition、旧资源读取、authConfigured、版本/code/token/principal/tenant/权限/角色集合/definition/卸载变化、重复请求、新旧finally、错误显式重试与畸形响应。未重跑127项全套或三端构建。

### 独立Chrome：公开权限、角色变更及旧finally组合探针

使用新临时Chrome上下文、15175的真实`mcp-registration.html`，在web目录PowerShell内联Node执行；全部`/admin/`业务路由由本探针拦截，不连接真实MCP或后端。故意让顶层published与版本平铺元数据含错误绑定/HTTP类型/authConfigured=true，而选定v2嵌套definition为准确MCP且authConfigured=false。

1. EDITOR查看v3 credential草稿：公开authConfigured=true导致禁用，未发发现请求。
2. 选择v2：虽顶层及平铺字段不同，实际按所选嵌套MCP定义检查；重复dispatch在途点击，首个请求计数仍1。
3. 首响应挂起时只把角色集合由EDITOR改为EDITOR+auditor，保持有效edit/admin布尔不变；重新检查同一v2。
4. 放行旧响应后，新请求仍禁点且无旧工具；再放行新响应，显示准确`Exact.Tool`。
5. 测1440与390宽度，禁止落盘截图，仅将截图buffer用于等待最终布局；检查请求账本无其他写请求。

命令入口为`@' ... '@ | node --input-type=module`，导入本工程现有Playwright，finally关闭browser。实际输出，进程退出0：

```json
{"probe":"selected-public-auth-and-role-race","fixtureOnly":true,"credentialBlocked":true,"duplicateBlocked":true,"oldFinallyDidNotUnlock":true,"bound":"Exact.Tool","checkRequests":[{"path":"/admin/skills/lookup_fixture/drafts/2/mcp-tools","method":"POST","body":null},{"path":"/admin/skills/lookup_fixture/drafts/2/mcp-tools","method":"POST","body":null}],"otherWrites":[],"widths":[{"viewport":1440,"documentWidth":1440},{"viewport":390,"documentWidth":390}],"pageErrors":[],"passed":true}
```

此组合探针补充同版本角色变化后立即重查的时序，属于组件HTTP fixture证据，不能冒称真实SSE握手/工具列取或后端授权已经验收。

## 已读作者记录与视觉复核

作者已有127项、vue-tsc/三端构建、新Chrome13项及历史3+13+23项结果由本次读取报告/日志/脚本/JSON核对，不称本次全部重跑。原guard、cases、expected及历史评审仍保留，不以历史兼容成功代替新发现路径检查。

本次实际view_image复核`frontend-mcp-registration-tools-1440.png`、`frontend-mcp-registration-tools-390.png`、`frontend-mcp-registration-wizard-390.png`：绑定与工具列表可识别，完整原始schema可展开/滚动读取，长值换行，详情动作在390下换行，向导准确工具名及错误提示可见。没有发现本轮确定视觉阻断。schema或元数据的JSON用于准确合同核对，而非业务执行结果的默认整块替代。

## 边界与交接

发现不保存到草稿；现有SkillCatalog.save缺expected version/hash条件更新，不在本次增量中伪造“一键应用”。未来编辑保存须先补并发保护。这个已声明限制不作为当前只读消费者缺陷。

真实MCP后端资源返修、独立审查、统一回归与更新服务后才能做真实纵切；本reviewer未访问真实MCP、跑后端Maven、启停服务、发外部消息或派子agent。生产票据/业务数据未持久化。**范围内无修复项；web target已释放给root。**
