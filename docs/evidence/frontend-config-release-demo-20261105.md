# 配置发布演示恢复（2026-11-05）

范围：用户明确授权的纯前端 Mock 演示；未变更生产服务、演示版本号、Vue 页面或其他协作者文件。

## 根因

- Mock 接入档案使用旧平铺字段 `revision/profileVersion/environment`，缺少 `appCode/profileRev/revisions`；配置页按 `appCode` 过滤后得到空列表。
- 连接、环境绑定、接入档案使用 `ACTIVE`，页面需要 `APPROVED` 和修订数组。
- 旧发布摘要为 `sha256-demo-*`，不满足激活检查的 64 位十六进制 DTO 契约；规划和账号助手没有相应配置发布种子。
- 旧恢复逻辑只接受当前版本的 sessionStorage，早期 localStorage 和版本 1/2 的用户配置被忽略。

## 修改

- `web/shared/mock-config-releases.ts` 集中管理上述五类配置资源，按 UI DTO 补齐历史字段，只在首次迁移时为内置应用添加缺少的演示记录。保留用户 ID、声明、自定义字段、历史修订及停用状态，不给自定义应用注入发布记录。
- `web/shared/mock-state-extensions.ts` 初始化配置资源并委派配置路由，避免旧处理分支先截断请求。
- `web/shared/mock-transport.ts` 仅在缓存恢复处兼容旧版本及 localStorage，同名 sessionStorage 优先；显式重置同时清理旧 localStorage，避免数据复活。
- 配置发布按已发布应用版本冻结技能引用；校验核对体验、接入档案、连接与环境绑定；批准要求成功校验；激活再次复核且同应用/环境仅保留一个 ACTIVE。停用不自动回退，历史 SUPERSEDED 可重新校验、批准并激活。

## 种子读回

通过 Vite SSR 直接调用 mockTransport 读取：

| 资源 | 数量 |
| --- | ---: |
| 配置发布 | 16 |
| ACTIVE / APPROVED / DRAFT / SUPERSEDED | 5 / 5 / 5 / 1 |
| 连接档案 / 环境绑定 / 接入档案 / 应用体验 | 各 5 |

卫星规划助手、账号开通与角色授权助手各 1 条 ACTIVE、APPROVED、DRAFT；卫星运行助手另保留原 SUPERSEDED 发布。

## 定向检查

红灯：新增 3 条用例首先在旧实现上全部失败，直接复现档案为空、规划发布不存在、旧 localStorage 被忽略。

绿灯命令：`npm exec vitest run tests/mock-config-releases.test.ts tests/integration-console.test.ts tests/mock-transport.test.ts`，3 文件、18 用例通过。

覆盖：UI DTO 解析及激活阻断；新建→校验→批准→激活→停用→旧发布回退；刷新读回唯一激活指针；v2 localStorage 迁移保留自定义应用/欢迎语/端点/字段；体验历史修订与过期修订拒绝；环境绑定停用后激活返回 409。

`npm exec vue-tsc -- --noEmit` 退出码 0。未执行全量回归或重新生成发布包。
