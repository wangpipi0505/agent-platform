# OpenAPI 两步导入 UI 验证（2026-10-20）

唯一工作根：`G:/207/项目/算法重组工具/代码/agent-platform`。先读取本工程 AGENTS.md、implementation-brief、contracts/README、skill-openapi-report；采用 frontend-design、vue-skilld、ui-ux-pro-max、ponytail、test-driven-development、Playwright、verification-before-completion 的适用流程。保持工程锁定 Vue 3.5 / Element Plus，不采用技能文档中的 Vue beta API，不新增依赖。

## 实现

- `web/admin/src/SkillWizard.vue` 增加“手工注册 / OpenAPI 导入”入口与详情事件透传，手工六步流程继续保留。
- 新 `OpenApiImport.vue` 只通过共享认证 API 调用两个实际导入接口。第一步接受 JSON/YAML 文本或浏览器本地文件读取，预览保留所有操作与诊断，不支持操作禁选；Schema 折叠只读。第二步对所选操作逐项填写名称编码、四段职责说明、四项显式执行属性、角色与属性范围、返回规范、失败语义与超时，创建真实 DRAFT。
- 服务基础地址手动填写，拒绝凭据、查询和模板；忽略上传文档中的服务器地址。四项执行属性初始未确认，查询必须声明输入参数对应的属性守卫，写操作必须填写确认角色。空范围数组表示取已验身份的授权集，不扩大授权。
- Bearer 操作明确提示管理员配置已登记 secret_ref；仅 ADMIN 显示引用输入，非管理员提交引用被客户端阻断。没有默认密钥、拼接 vault 引用或产品假数据；凭据仍由服务端验证，成功后清空表单中的引用。
- 本地文件先检查字节大小再 `.text()`；文本按 UTF-8 字节检查 1 MiB。文件选择只读取浏览器 File，不接受主机路径或抓取 URL。文档改动清空旧预览与选择。
- 成功页明确“导入成功，已创建草稿（DRAFT）”，说明尚未发布，提供“进入详情继续验收”。App.vue 的事件监听由该文件原 owner 单独接入，已真实验证关闭弹窗并跳到技能详情；本任务未写 App / SDK / ResourceDetail。
- 服务端错误与 traceId 展示，网络失败给出“检查网关连接后重试”。没有静默跳过诊断或自动发布。

## 红绿与构建

工作目录：`.../agent-platform/web`。

```powershell
npm test -- tests/openapi-import.test.ts
npm test
npm run build
```

- `frontend-openapi-red.log`：最小空实现时 4 项关键失败路径出现预期失败（UTF-8、必填字段、选中合同转换、unsupported/身份/地址限制）。
- `frontend-openapi-green.log`：最终针对性 6/6 通过，含额外显式执行属性、失败语义、写操作确认角色回归。
- `frontend-openapi-regression.log`：最终全站 21 个测试文件、39 项测试通过，0 失败（包含并行协作者新增回归）。
- `frontend-openapi-build.log`：`vue-tsc --noEmit` 与 admin/webapp/assistant 全部构建成功，退出 0。保留现有大包提示，不扩展为打包重构。

## 真实浏览器与 API

使用本工程测试源码和实际前端 `127.0.0.1:15173` → 网关 `18080` → 技能服务；开发票据仅内存。Chrome 通过独立 CLI session `openapi-import` 驱动，未引用相邻工程。

```powershell
npx --yes --package @playwright/cli playwright-cli -s=openapi-import open http://127.0.0.1:15173 --browser=chrome
npx --yes --package @playwright/cli playwright-cli -s=openapi-import run-code --filename=tests/openapi-import-start.js
npx --yes --package @playwright/cli playwright-cli -s=openapi-import run-code --filename=tests/openapi-import-create.js
```

`openapi-import-start.js` 完整登录/导航，上传 `tests/openapi-import.fixture.json`，实际预览 200，GET 可选、HEAD 不支持且诊断可见/不可选；空文档阻断。测试专用文档仅位于 tests，无产品默认示例。截图 `frontend-openapi-preview.png`。

`openapi-import-create.js` 实际填写结构化业务表单，缺失基础地址会明确阻断。导入返回 200，真实 PG 草稿 `openapi_ui_1792441614565`，status=DRAFT，includeOps 只有 readResult；来源摘要 `0c3d43518e53ecdc54f1ea97191b4cd4d5bc10b4bf909cafe7899e04d6063a8e`。实际跳转详情后，通过结构化 message / region 输入发起受控连接，run `r_cfd05340231d45d997ebbdcf3a583104` 从 RUNNING 到 SUCCEEDED，刷新报告为 ok:true。输出日志 `frontend-openapi-browser-create.log`；截图 `frontend-openapi-contract.png`、`frontend-openapi-created.png`、`frontend-openapi-detail.png`。

`openapi-import-errors.js` 在技能列表运行：350000 个中文字符超过 1 MiB，预览请求数为 0；损坏文本 `{` 返回真实 400 与明确诊断/traceId；通过 Playwright **仅在测试中**中断预览请求，确认显示可恢复网络错误，然后解除路由拦截。日志 `frontend-openapi-browser-errors.log`；截图 `frontend-openapi-network-error.png`。

`openapi-import-yaml.js` 在 start.js 的第二步页面运行：返回选择，粘贴 YAML，验证旧候选即时清空、实际预览 200 且 yamlResult 支持，未创建草稿。日志 `frontend-openapi-browser-yaml.log`。

初次真实浏览器检查发现复选组继承零字号导致诊断不可见；已在操作卡片局部恢复 token 字号并重新浏览器验证。脚本使用 Element Plus 可见标签交互，避免对其隐藏原生输入操作。

本次连接仅使用本机测试 fixture `127.0.0.1:28412/result`，不计作真实业务服务、真实模型选型或外部验收。没有运行发布动作，没有创建选型通过报告，没有提交、部署、服务修改或依赖复用。
