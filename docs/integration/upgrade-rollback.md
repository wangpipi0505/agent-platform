# 升级与回退

## 原则

- SDK 与 Schema 只做**附加式**演进：旧字段不删除、语义不变；未知字段忽略；Schema 演进用新的 `schemaVersion`，不原地覆盖。
- 配置回退**不会**恢复已撤销的权限；撤销/停用优先于历史快照。
- 任何回退都不得清空审计、会话或运行事实；只切换入口/版本。

## 升级步骤

1. 记录当前组合：`versions.md` 中的 SDK 版本、产物 sha256、契约摘要、生效的 ConfigRelease 引用。
2. 对照新交付包的 `versions.md` 与变更说明，确认宿主 API 使用面（`init` 选项/句柄方法/事件字段）无破坏性变更。
3. 在预发环境重建产物并核对 checksum：

   ```bash
   cd web && ./node_modules/.bin/vite build --config assistant.js/vite.config.ts && cd ..
   sha256sum -c docs/integration/checksums.txt
   ```

4. 用 `host-checklist.md` 的 E 节逐项回归；重点看旧调用路径（不带新字段）行为是否为零变化。
5. 灰度：先对一个接入档案/环境切换 SDK 文件版本，观察取票、SSE 重连、结果回传、撤权刷新。
6. 全量切换后保留上一版本文件与 checksum，至少一个回退窗口。

## 回退步骤

1. 把页面引用的 SDK 文件换回上一版本（固定 URL/文件名带版本或摘要），**同时**恢复上一版 `checksums.txt` 记录。
2. 若问题来自配置：在配置管理台停用/回退对应 ConfigRelease；不要直接改数据库。
3. 若问题来自身份/来源：停用新接入档案或收紧 `allowedOrigins`，并撤销相关凭据；旧票据按有效期自然失效。
4. 验证：新取票失败应为可解释码（`INTEGRATION_SOURCE_NOT_ALLOWED` / `INTEGRATION_TICKET_EXPIRED` 等），既有运行不被回溯修改。
5. 记录事实：时间、影响范围、回退到的版本/摘要、脱敏后的 HTTP 状态与错误码、是否撤销凭据。**不记录** token/ApiKey 明文。

## 兼容事实（1.0.0）

| 场景 | 行为 |
| --- | --- |
| 旧调用 `init`→`destroy` | 原行为：浮球 + 抽屉 + 对话/确认/下载 |
| 新版无体验端点/无激活配置 | 优雅回退直接对话，不伪造默认能力；不阻塞提问 |
| `autoLauncher:false` | 不挂浮球，等待宿主按钮 `open()` |
| 重复 `init` 同一挂载点 | 先销毁旧实例再挂载新实例；不同挂载点各自独立 |
| 未知事件/未知 `schemaVersion` | 忽略并记录，不执行导航或脚本，不崩溃 |
| SSE 中断 | 按 `Last-Event-ID` 游标恢复；终态只回传一次 `result` |
| 会话固定配置版本 | 轮次沿用会话创建时的引用；客户端提议与服务端不一致时以服务端为准并如实提示 |

## 不兼容变更的处理

若必须破坏兼容（例如事件字段语义变化）：提升 `schemaVersion`，在 `versions.md` 说明迁移方式，宿主按版本分支处理；平台不得静默改变旧版本语义。
