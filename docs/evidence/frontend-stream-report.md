# G5 长任务 SSE 消费者恢复：本地与组件阶段

范围固定为本新工程。只修改共享观察器、Chat/SDK 局部接入、SSE HTTP 错误的 status 属性与本组测试/证据；没有修改统计/历史组件、App 主接线、RunDetail、tokens、后端与契约，没有 Git 提交、Maven 执行或业务服务启停。已使用 Vue、TDD、Ponytail、Playwright 与完成前验证技能。已有 `frontend-stream-plan.md` 为本轮实现输入。

## 行为与边界

`web/shared/run-observer.ts` 只观察已经受理的同一个 runId；创建、确认、取消仍由原消费者调用。每次正常 EOF、reset、流网络异常之后先读并应用权威状态，只有非终态才重连。状态服务失败时只重试状态读取，不会抢先开下一条流。默认连续 5 次状态读取失败结束观察；失败等待 750/1500/3000/6000ms（上限 8s），正常恢复等待 750ms；全部等待可由 AbortSignal 打断。停止观察不会调用取消 API。

只有已消费的有序事实 seq 或已成功应用的快照 eventsWatermark 能推进游标。chunk 即使携带错误 seq 也不推进；stream_status/reset 的提示水位不承认已消费。事实按游标去重。EVENT_LIMIT 后只定时读状态直到终态，避免反复请求同一超大永久事实。401/403/404/其他确定 4xx（408/425/429 除外）停止重试；WebApp 仍通过原 Auth 路径清除 401 token，没有自动重新登录。

Chat/SDK 在恢复、等待确认、终态事实已到但权威状态未到时保持发送禁用。权威结果覆盖临时文本，沿用既有 finalAnswer 的格式校验提示。Chat 卸载与 SDK destroy 仅 abort 观察，晚到的状态/旧流回调不会应用；用户主动停止仍由 Chat 原 cancel 成功后 abort。SDK 每次打开 SSE/读取状态重新调用宿主后端短票据函数，并且确认请求和附件下载也按需获取票据；没有向浏览器引入 API Key。

## 已执行测试

命令均在本根 `web`，已有 fixture15175/admin15173/WebApp15174 保持运行，未重启。日志位置均为本根 `.local/`。

| 命令/证据 | 实际结果 |
| --- | --- |
| 原 `.local/frontend-stream-red.log` | 缺少模块的加载失败，不当作行为红测 |
| `npm test -- tests/run-observer.test.ts` / `frontend-stream-behavior-red.log` | 导出尚未实现的最小函数后，原 5 条断言全部真实失败 |
| 同命令 / `frontend-stream-initial-green.log` | 最小恢复循环后，5 条通过 |
| 同命令 / `frontend-stream-boundaries-red.log` | 新边界共 13 条中 1 条真实失败，发现状态不可达时提前重开 SSE；其余 12 条通过 |
| 同命令 / `frontend-stream-boundaries-green.log` | 修复快照读取顺序后 13 条通过 |
| `npm test` / `frontend-stream-all-green.log` | 24 文件、70 条全部通过，0 失败；原 57 + 新观察器 13（含 HTTP 400/401/403/404/422 五个参数用例） |
| `npm run build` / `frontend-stream-build.log` | vue-tsc + admin/WebApp/SDK 三构建及 SDK 拷贝全部成功；admin/WebApp 保留原 >500kB bundle 警告，没有宣称零警告 |
| `node tests/stream-recovery-browser.mjs` / `frontend-stream-browser.log` 与 `.json` | 独立本工程 profile、真实 Chrome 可执行文件、5 个组件场景全部通过 |

新增边界还覆盖网络断流但 status 可达、永久 HTTP 错误立即停止、状态连续不可达有界退出、暂停/挂起状态读取可 abort、Abort 后旧 stream 回调与晚到状态被忽略、EVENT_LIMIT 不再打开 SSE。注入 pause 只用于单测无等待；生产默认定时器真实退避。

## Chrome 组件证据，明确非真实网关纵切

测试页面 `web/tests/stream-recovery.html` 真实加载本工程 Chat.vue 与 SDK 源码；Playwright route 仅在测试中替代 `/v1` 网络结果，不属于生产降级。Chrome 路径 `C:/Program Files/Google/Chrome/Application/chrome.exe`，profile `.local/chromium-stream-recovery`。

- WebApp/SDK 各自 EOF → RUNNING(水位8) → reset(CURSOR_EXPIRED 提示999) → WAITING_APPROVAL(权威水位12) → 真实点击确认 → RUN_FINISHED → 权威 SUCCEEDED。每端创建仅1次、打开3流、读取3状态、游标精确0/8/12、确认1次、cancel0次。第三流重播的旧事实 seq11 不交付；终态事实到达后仍保持禁发，直到最后权威状态返回。
- SDK 三条流分别取得不同短票据（fixture 假票据仅测试）；最终结果替换所有临时片段与事实内临时答案，并显示 PASS_WITH_FLAG 原提示。WebApp 同样验证。
- WebApp 401：auth 清空、创建1次、status 读取0次，不重新登录或重开流。
- WebApp 卸载、SDK destroy：在 status 请求挂起时关闭组件，放回晚到 RUNNING；各创建/流/status均1次、cancel0次，无后续重连。

截图 `frontend-stream-webapp-approval.png`、`frontend-stream-webapp-final.png`、`frontend-stream-sdk-approval.png`、`frontend-stream-sdk-final.png` 均由实际 Chrome 截取；已用图片查看工具亲自检查全部四张，确认运行身份/停止操作/待确认按钮可用，最终提示与答案可读，终态无临时片段残留。

## 自审与剩余验收

已逐行检查观察器的权威快照先后顺序、仅事实游标、重复过滤、错误分类、状态失败上限、可中止等待、超大事实退化，以及两消费者的同 runId/busy/最终答案/短票据路径。新增代码未创建新依赖，没有用浏览器存储冒充业务权威。最后构建没有源代码错误；原体积提示保留。

该证据仅完成“消费者本地 + 真实 Chrome 组件 fixture”阶段，不证明真实网关、实际55秒EOF、两个独立 engine 进程或 Redis 跨进程恢复已贯通，也不证明 T15/G5 全部完成。后续由主协调者在后端独立修复评审后继续实际双进程纵切。chunkReplay=false，PubSub 不重播丢失 token；最终完整内容以 PG 权威快照覆盖，未宣称在线 token 无损。

