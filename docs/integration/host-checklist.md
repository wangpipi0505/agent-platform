# 宿主自检：需要宿主提供什么

按顺序勾选。每一项都要有**可指认的责任人与证据**（配置提交、截图、curl 输出、变更单），不要以口头确认代替。

## A. 业务与入口

| 项 | 宿主提供 | 证据 | 缺失/失败时表现 |
| --- | --- | --- | --- |
| A1 助手入口 | 一个菜单或按钮位置（L2 用按钮；L0 用菜单链接） | 页面截图/路由 | 无入口，L2 验收不成立 |
| A2 应用与接入标识 | `appCode`、`integrationId`、目标环境（production/staging） | 平台配置发布页 | `CONFIG_RELEASE_NOT_ACTIVE` |
| A3 业务记录反查接口 | 按 `businessRecordRefs.source + recordId` 只读查询的接口，服务端重新鉴权 | 接口文档 + 401/403 用例 | 结果只可展示、不可跳转业务详情 |

## B. 身份与票据

| 项 | 宿主提供 | 证据 | 缺失/失败时表现 |
| --- | --- | --- | --- |
| B1 同源短票接口 | 宿主后端从**已验会话**取身份，调用平台 `/embed/ticket`；返回短期 `ticket` | 代码评审 + 抓包（脱敏） | `INTEGRATION_CLIENT_IDENTITY_REJECTED` 或取票失败 |
| B2 密钥保管 | 集成 ApiKey 只存服务端 secret manager，按环境/应用隔离 | 密钥库条目（不含值） | 不得把密钥放浏览器/构建产物 |
| B3 身份源（L3） | 原生身份接口或标准签名令牌，平台批准的字段映射 | 接入档案 + 身份源档案 | `INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE`，零业务执行 |
| B4 退出/撤权 | 用户退出、角色变化、账号停用时的通知或刷新路径 | 流程说明 | 旧票据到期前仍可用是预期；撤权后刷新被拒 |

短票接口的最小契约：

```http
POST /assistant/api/ticket
Cookie: <宿主会话>
Origin: https://host.example.com

200 {"code":"OK","data":{"ticket":"<短期票据>","expiresIn":300}}
```

不得接受浏览器传入的 userId/tenantId/role 作为身份；不得在 URL 或 localStorage 保存票据。

## C. 域名、CSP 与反向代理

| 项 | 要求 |
| --- | --- |
| C1 来源白名单 | 平台接入档案 `allowedOrigins` 精确写宿主 origin（含协议与端口，不带路径/通配）；多环境分档案 |
| C2 同源代理 | 建议 `/assistant/` → 平台网关、`/assistant/api/` → 宿主 BFF；代理**保留** `Origin`/`Authorization`，不重写 Host 之外的来源头 |
| C3 CSP `script-src` | 允许加载固定版本 `assistant.js`（同源路径或平台域名） |
| C4 CSP `style-src` | SDK 在 Shadow DOM 内注入 `<style>`；需允许该来源的 inline style（如 `'unsafe-inline'`），否则界面不可用。优先用 nonce-aware 方案，退而求其次再放开 |
| C5 CSP `connect-src` | 允许 `endpoint` 域名（对话、SSE）与宿主 BFF 域名 |
| C6 CSP `frame-src` / `frame-ancestors` | L1 需允许平台 iframe 域名；平台侧同时配置允许被宿主嵌入，不能与 `X-Frame-Options: DENY` 冲突 |
| C7 SSE | 代理关闭响应缓冲（`proxy_buffering off`），读超时 ≥ 5 分钟，透传 `Last-Event-ID` |
| C8 缓存 | 票据与事件接口 `Cache-Control: no-store` |

nginx 片段（与 `deploy/integration/README.md` 一致，勿另建一套）：

```nginx
location /assistant/ {
  proxy_pass https://assistant.internal/;
  proxy_set_header Host $host;
  proxy_set_header Origin $http_origin;
  proxy_buffering off;
  proxy_read_timeout 300s;
}
location /assistant/api/ {
  proxy_pass https://host-bff.internal/;
  proxy_set_header Host $host;
  proxy_set_header Origin $http_origin;
  proxy_no_cache 1;
}
```

## D. 能力与配置

| 项 | 宿主/平台提供 | 证据 |
| --- | --- | --- |
| D1 体验配置 | 平台侧欢迎语/能力卡/推荐问法（模板见 `templates/experience-template.json`） | 配置发布记录与 digest |
| D2 提示词引用 | 平台侧 `promptRefs` 与变量声明（模板见 `templates/prompt-template.md`） | 版本号 + 摘要 |
| D3 连接与权限 | 平台侧连接档案、接入档案允许来源、grant | 批准记录与修订号 |
| D4 只读探测 | 诊断面板只能组合只读接口；无投影的阶段标注 UNKNOWN | 诊断页截图 |

## E. 上线前功能自检

- [ ] 按钮可打开/关闭同一助手；重复打开不产生第二个实例。
- [ ] 两个应用按钮不会串会话（`sessionId` 不同、上下文不串）。
- [ ] 模板必填变量为空时不能发送；选择推荐问法只生成可编辑输入，不自动执行。
- [ ] 无权限的能力不可见/不可用，且服务端仍拒绝（前端隐藏不算授权）。
- [ ] 中文长文、换行、富文本输入不破坏布局；服务端内容按纯文本渲染。
- [ ] 文件类输入在嵌入端给出可解释提示（无上传通道），不静默丢弃。
- [ ] 断网/SSE 中断后恢复，最终结果与权威记录一致；重复终态不重复刷新。
- [ ] 错误码按 `error-codes.md` 展示，不显示 token/SQL/堆栈。
- [ ] 停用接入档案/撤销凭据后，新取票被拒；既有运行不被回溯修改。
- [ ] 回退路径可用（见 `upgrade-rollback.md`）。
