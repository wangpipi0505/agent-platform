# INT-06 子步2（A 席）：SDK 客户端携带配置版本提议，恢复时以服务端引用为准

- 日期：2026-11-04
- 席位：A（INT-06 客户端子步）
- 基线 HEAD：`b685c52a`（INT-06 服务端子步）
- 范围：`web/shared/session.ts`、`web/shared/run-observer.ts`（仅核对，未改）、`web/assistant.js/src/runtime.ts`、SDK 自检脚本；`webapp/src` 无必要联动（见五）。
- 未触：服务端 Java、数据库迁移、`web/admin` 其他文件、其他席位未提交的 onboarding 页面文件。

## 一、原责任 → 新责任

| 原责任 | 现责任 |
| --- | --- |
| 客户端不感知配置版本，建会话/运行只带 inputs | 客户端可提议 `configReleaseId`：建会话 body 与 FLOW 运行 body 携带；仅此两处 |
| 轮次由客户端自行决定发送内容 | 轮次**从不**携带 `configReleaseId`；会话固定引用由服务端从会话行读取 |
| 恢复会话只回填历史 | 恢复时读取服务端返回的 `session.configReleaseId`，与本地提议比较；不一致以服务端为准并在日志中如实提示 |
| 旧客户端行为无显式约束 | 无提议时建会话 body 仍为 `{}`，不新增字段、不新增提示 |
| `run-observer` 的游标恢复无配置语义 | 保持不变：SSE 中断仍按 `Last-Event-ID`/状态水位恢复；恢复后的轮次沿用同一会话固定引用（服务端钉住） |

## 二、改动文件

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `web/shared/session.ts` | 26 | 新增 `sessionConfigReleaseId(session)`，只读服务端返回的会话引用 |
| `web/assistant.js/src/runtime.ts` | 483 | 提议入口（init 选项或 URL 查询）、建会话/FLOW 运行透传、恢复对齐与提示、轮次不带提议 |
| `web/tests/assistant-sdk-split-check.mjs` | 1247 | 新增 4 项定向检查与夹具；证据改写到本子步目录 |
| `docs/evidence/int-06-20261104/assistant-sdk-structure.json` | — | 本次自检的请求/断言证据（含 `lastEventId`） |

## 三、不变量

1. 提议只来自宿主：`init({configReleaseId})`（JS 宿主）或页面 URL `?configReleaseId=`；客户端不解析、不判断版本是否可用。
2. 建会话：有提议时 body 为 `{configReleaseId}`；服务端返回的 `configReleaseId` 才被采纳，未返回则视为未固定。
3. FLOW 运行：允许一次提议；CHAT 轮次永不携带提议（避免与服务端固定值冲突）。
4. 恢复：`session.configReleaseId` 与本地提议不一致时，不覆盖、不重试，只提示“继续使用创建时的配置版本（以服务端返回为准）”。
5. 旧会话（服务端无引用）遇到本地提议：提示“未固定配置版本，继续按会话创建时的应用版本执行”，轮次仍不携带提议。
6. 无提议宿主：建会话 body 严格为 `{}`，日志无新增提示——请求级零变化。
7. SSE 中断：沿用既有 observer 的游标/状态水位恢复；同一轮次只有一次 turns 请求，恢复使用同一 run。
8. 客户端不读取、不缓存 tenant/密钥/物理地址；提示文本不包含服务端未返回的值。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `node tests/assistant-sdk-split-check.mjs` | 失败：新夹具的兜底 `404` 被插入到路由中间，`r_3` 事件流被误判 404，`第二应用回答` 超时（保留） |
| 2 | 同上（修正路由顺序） | 失败：中断夹具的 `eventsWatermark` 用字符串，`decodeRunStatus` 要求 JSON 数字，观察器重试后超时（保留） |
| 3 | 同上（改为数字水位） | 通过：16 项 checks 全 true，85 条请求，`pageErrors: []`，仅审批 dialog 1 次 |
| 4 | `./node_modules/.bin/vue-tsc --noEmit`（当前 worktree） | **exit 2，但失败均在 `web/admin/src/pages/onboarding/OnboardingTemplatesPage.vue` 的缺失依赖**，该文件是其他席位未提交的并行工作（`git status` 显示 `web/admin/src/pages/onboarding/`、`web/shared/onboarding.ts`、`web/admin/src/router.ts`、`web/admin/src/page-access.ts` 均非本卡改动，未触碰） |
| 5 | 干净验证 worktree（`git worktree add --detach HEAD` + 仅复制本卡两个产品文件 + junction `web/node_modules`） | `vue-tsc --noEmit` exit 0；`vite build admin` / `webapp` / `assistant.js` 全部 exit 0（`dist/assistant.js 243.09 kB`，gzip 79.28 kB） |
| 6 | 同一干净 worktree `vitest run` | 347 passed / 4 failed（`tests/approval.test.ts` 3、`tests/run-protocol.test.ts` 1，均为未改动共享模块的既有失败） |
| 7 | 当前 worktree `vite build assistant.js` / `webapp` | 均 exit 0；`admin` 因上述他人未提交页面失败（同一外部原因） |

## 五、未覆盖

1. 真实业务页面/iframe 中的宿主提议链路未跑（本卡只有 Playwright 夹具页）。
2. `webapp/src` 无必要联动：独立业务对话页由 `web/shared/Chat.vue` 建会话，该文件不在本子步白名单且未接配置提议；本卡不改。
3. 真实 PG 定向仍不可用（35432/25432 down），客户端子步无数据库写入，服务端引用语义以服务端单测为准。
4. 仅 Chrome 验证；未验证 `crypto.randomUUID` 不可用的非安全上下文（既有降级行为未变）。

## 六、白名单外改动与契约缺口

- 白名单内：`session.ts`、`runtime.ts`、测试脚本；`run-observer.ts` 只核对未改。
- 白名单外：无产品代码改动（证据 JSON 属交付物）。
- 契约缺口（只登记，不私改）：
  1. `AssistantOptions` 类型未暴露 `configReleaseId`（`types.ts` 不在本子步白名单），TS 宿主目前只能通过 URL 提议；JS 宿主可直接传。
  2. SDK 与宿主的提议协议（URL 参数名、选项名）尚未写入 `contracts/assistant-integration.md`。
  3. webapp 独立对话页未接配置提议，与 SDK 的行为存在差异。
  4. “应用已更新”的提示文案未进入契约/文案表。

## 七、回退

- `git revert <本子步提交>`（提交信息 `feat(int-06): SDK 客户端携带配置版本提议并对齐服务端引用`）；改动仅前端三文件，无数据/迁移影响。
- 回退后：建会话仍无 `configReleaseId` 字段、恢复不读服务端引用、轮次行为不变；服务端子步 `b685c52a` 继续接受旧请求，无需回退。
