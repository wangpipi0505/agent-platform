# 前端真实服务长任务恢复证据

2026-10-20。唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。使用 Playwright、Ponytail 与 verification-before-completion；遵守 AGENTS、implementation-brief 和 contracts/README。只新增本组测试与证据，没有修改业务源码、设计/契约、现有 provider/app、数据库配置或已有六服务/Vite进程，没有 Maven、Git提交或外部发布。

## 当前结论

**最终真实纵切通过。** 主协调者修复并独立复核MVC超时及前端非终态快照边界后，最后一轮严格测试exit0：WebApp首EOF55018ms、SDK55017ms，两端都只创建一个turn/run、重连同一run并最终SUCCEEDED，完整结果无重复。修复前约30秒EOF的失败、两次测试诊断问题与最终绿证据均在本文保留。该结论仅限本工程真实服务与协议fixture，不代表真实模型能力、容量或宿主生产短票据全链验收。

## 独立测试栈

- `tests/smoke-frontend-stream-live.mjs` 仅使用Node标准库启动专用HTTP模型协议fixture，绑定127.0.0.1:28414。启动前检查端口无占用，结束时finally关闭server与所有自己创建的连接；第二轮结束后检查无监听。
- 实际网关18080、已有admin15173/WebApp15174、主engine18082、真实PG/Redis均使用本工程现有服务。浏览器从WebApp15174的Vite代理访问真实网关，没有page.route、虚假status或替代运行接口。
- Chrome `C:/Program Files/Google/Chrome/Application/chrome.exe`；专用profile `.local/chromium-frontend-stream-live`；WebApp开发登录。SDK真实加载 `/assistant.js` 已构建产物，每次票据回调实际调用 `/auth/dev-token`，明确仅开发票据路径，未验证宿主真实短票据全链。
- 每轮都新建独立provider/model/CHAT应用，tools为空、无格式限制、`timeout.modelMs=90000`，不修改既有应用。模型wire总时长65秒；初轮分片约1/13.5/26/38.5/51/65秒，最终轮新增60秒分片；末尾发送fixture真实协议usage 8/4/12。`/v1/models`探测、非流连通请求、短评测与长运行计量分开。
- 已实际完成provider.test `ok=true`、已知短输入/预先固定expected评测ACCEPTED、precheck passed及publish version1。期望评测输出从测试常量构造，不从待测结果复制。

## 首轮测试诊断问题与修正

首轮app `smoke_long_30dc1aed73` 已真实发布，两个模型请求分别65024/65053ms完成。浏览器失败后清理时，尚未结算的Playwright `response.finished()`拒绝未被处理，掩盖原断言。保存 `.local/frontend-stream-live-first-failure.log` 与 `frontend-stream-live-first-fixture.json`，该轮不计通过。

随后只修测试的异步诊断和清理：每条response观察捕获自身异常，两个并发场景使用allSettled，失败也输出网络JSON。没有修改前端或后端实现。

## 第二轮真实结果：30秒EOF，与55秒要求不符

命令：在工程根执行 `node tests/smoke-frontend-stream-live.mjs`，实际exit1。app `smoke_long_f237624a67`，provider/model id均9，评测ACCEPTED，发布version1。

| 消费者 | runId | 每条实际SSE时长ms | SSE游标 | 实际状态读取 |
| --- | --- | --- | --- | --- |
| WebApp | `r_133fbc25dcde43f6bc6ace12968dc841` | 30640 / 30162 / 4185 | 0 / 2 / 2 | RUNNING / RUNNING / SUCCEEDED |
| SDK | `r_3560fc4e88c74c18811e733b3f9deaec` | 30015 / 30227 / 4646 | 0 / 2 / 2 | RUNNING / RUNNING / SUCCEEDED |

所有SSE均HTTP200且Playwright `response.finished()`无网络错误。每端真实网络只创建一个turn；两个RUNNING快照到达时busy都为true，SUCCEEDED时为false。最终权威输出文本都完整为“长任务验证：第一段已经到达；第二段持续执行；第三段跨越断流；第四段恢复观察；第五段完整结束。”，usage均promptTokens8/completionTokens4/totalTokens12。

第二轮fixture独立计量：`models=1`、`nonStream=1`、短评测`evaluation=1`；两个长请求各65043/65063ms结束，没有把provider.test连通请求计为长运行。

该轮在55秒严格断言处失败，因此尚未执行其后的最终DOM、localStorage、权威session.turns数量及最终截图断言；不把未执行项写成通过。测试已调整为以后先采集其余全部证据、最后执行55秒严格断言，未放宽目标。

原始证据保留：

- `.local/frontend-stream-live-30s-failure.log`
- `.local/frontend-stream-live-30s-fixture.json`
- `.local/frontend-stream-live-30s-webapp-network.json`
- `.local/frontend-stream-live-30s-sdk-network.json`

当时源码中RunController writer明确设置55秒期限；初轮engine application.yml及源码搜索未见Spring MVC async request-timeout覆盖。30秒实际EOF与MVC默认async超时吻合，首次报告将其列为待主协调者确认的原因推断。此后由主协调者负责修复、独立复核与部署，本测试者未修改配置或启停已有服务。

## 已反馈的前端实际表现

初轮两个RUNNING快照均带 `output:{}`。当时Chat/SDK的onStatus对非null output立即applyFinal；恢复截图真实显示回答变为“{}”，busy仍保持，后续chunk被receivedFinal门控，直到终态快照替换。已第一时间反馈主协调者，没有自行修改业务源码；后续由独立修复者修复并经评审关闭。

历史截图已保留为 `frontend-stream-live-30s-webapp-recovery.png`、`frontend-stream-live-30s-sdk-recovery.png`，已用view_image亲自查看：WebApp“停止运行”和SDK禁用发送符合busy保持，回答区域的“{}”清晰可见。第二轮当时尚未提供最终截图；现最终截图见下文。

## 失败轮次的等待与边界（历史）

需要已有服务的MVC异步超时允许writer达到55秒后，再运行相同严格测试。当前证据不签收55秒EOF、全部G5/T15、真实模型、宿主生产短票据或容量。chunkReplay=false，在线丢失token不重播；最终完整内容以PG权威快照为准。

按主协调者要求本轮停止，不再启动长跑。28414最终监听数实查为0。两个新增脚本的 `node --check` 均exit0。为下轮已调整浏览器脚本：严格55秒断言最后执行；补事实seq严格递增、无localStorage写入及SDK按请求取票计数断言。另修正测试SSE证据解析允许`event:foo/id:1/data:{}`冒号后无空格；此前30秒网络时长与状态证据不受此解析问题影响。上述新增断言只做语法检查，尚未真实长跑验证，不计通过。

## 修复后的测试诊断记录

主协调者完成MVC60秒超时修复并独立复核/部署，前端独立修复者完成Chat/SDK非终态快照修复并经评审关闭后，本测试者才继续真实长跑，没有改动两组业务修复。

1. app `smoke_long_2b1f3a91cb` 已达真实约55秒EOF，但测试把未来分片DOM可见错误限定为UI点击后57–64秒，忽略模型实际请求晚于UI开始和逐层传递延迟，exit1。保留 `.local/frontend-stream-live-55s-window-*` 与 `frontend-stream-live-55s-window-{webapp,sdk}-future.png`。未把它计为通过。
2. 此前Playwright `response.body().toString()`保存的chunk中文呈mojibake，而产品DOM正常。新增对**真实fetch Response.clone().body**的只读观测，用明确UTF-8 TextDecoder、按SSE空行分帧，记录每帧收到时间；原Response原样返回产品，没有route或替代响应。新记录中文正常，未将诊断解码差异当成业务编码缺陷。
3. app `smoke_long_3d24d9d955` 的UTF-8记录确认第二流收到“第四段恢复观察；”后，DOM在RUNNING/busy=true时持续显示它；原选择的第五段与最后分片同批到达，距终态仅约176ms，1秒采样无法稳定命中，故仍exit1。完整保留 `.local/frontend-stream-live-55s-utf8-sampling-*`。最终断言明确选择已证实第二流且终态前实际显示的第四段，仍同时要求网络与DOM证据，没有用网络有chunk替代DOM通过。

最终脚本把恢复窗口定义为真实首次EOF之后、权威终态之前的busy样本；busy、DOM文本、缺失提示在同一次浏览器任务内原子读取，避免分开await跨越终态。严格55秒断言仍为53000–58000ms并在其余证据收集后执行，从未放宽到30秒。

## 最终真实绿证据

工程根命令 `node tests/smoke-frontend-stream-live.mjs`，05:57输出实际exit0。新建app `smoke_long_dda255a0e0`、providerId/modelEntryId均12；真实provider.test ok、固定expected候选评测ACCEPTED、precheck passed、publish version1。fixture计量models1、nonStream1、evaluation1、long2、other1；两个长请求65014/65011ms完成。

| 消费者 | runId | 首EOFms / 重连流ms | 实际stream/status | 创建turn / 权威session.turns | 最终状态 |
| --- | --- | --- | --- | --- | --- |
| WebApp | `r_3487497dabe44ba3ba6b9607d7b548fb` | 55018 / 11967 | 2 / 2 | 1 / 1 | SUCCEEDED |
| SDK | `r_1e4e429e757e427986c976583d48ea19` | 55017 / 11989 | 2 / 2 | 1 / 1 | SUCCEEDED |

每端首流事实seq1/2，状态快照RUNNING、水位2后以Last-Event-ID=2打开第二流，事实seq3/4/5/6严格递增且无重复；全部流HTTP200且正常EOF，没有复制run或turn。两次状态读取分别RUNNING/SUCCEEDED，恢复后既有内容保持且不出现“{}”，缺失临时片段提示持续可见，busy保持至权威终态。

网络UTF-8第二流收到“第四段恢复观察；”后，它在最终权威终态前的DOM原子样本中实际可见：WebApp为UI起62720ms（早于终态5193ms），SDK63646ms（早于终态4354ms），当时均busy=true，随后完整终态替换内容，无重复。两端最终DOM精确等于测试预先固定答案：

> 长任务验证：第一段已经到达；第二段持续执行；第三段跨越断流；第四段恢复观察；第五段未来分片；第六段完整结束。

两端实际usage均promptTokens8/completionTokens4/totalTokens12；localStorage.length=0且通过只读写入计数观测到setItem次数0。SDK出票回调实际执行7次，包含schema/session/turn和每次stream/status请求；身份来自开发票据接口，明确不是宿主真实短票据全链。

分片计时证据保持原值：两个fixture的第四段emit epoch为1792447065432/1792447065481；WebApp/SDK浏览器实际SSE收到该段为1792447074446/1792447074506，DOM首次采到为1792447074507/1792447075346。此处只报告实际fixture emit→浏览器SSE→DOM观测，未旁路重复调用内部模型流，也没有凭这三点猜测中间层延迟根因或宣称在线token无损。

最终原始证据：`.local/frontend-stream-live.log`、`frontend-stream-live-setup.json`、`frontend-stream-live-fixture.json`、`frontend-stream-live-browser.json`（errors为空）、两端`frontend-stream-live-{webapp,sdk}-network.json`及`-samples.json`。报告者亲自用view_image检查以下六图：恢复时旧文本保留，新片段出现时仍busy/提示可见，最终答案完整且发送恢复。

- `frontend-stream-live-webapp-recovery.png`、`frontend-stream-live-webapp-future.png`、`frontend-stream-live-webapp-final.png`
- `frontend-stream-live-sdk-recovery.png`、`frontend-stream-live-sdk-future.png`、`frontend-stream-live-sdk-final.png`

运行前后核对生产冻结SHA一致：Chat `FACA2CA174BC577AB20FD82833F2A1E11FAB0E9CC753B4369E8797C36B377A42`，SDK `1E0CEE4A93B8DB268716480F8B417839A7C40814E836553AFAA113B21A0B9448`。测试者没有修改业务源码、重跑Maven、启停主协调者服务或清理审计数据。Chrome已由finally关闭，28414由finally关闭后实查监听数0。

最终结论覆盖实际网关、真实55秒EOF、WebApp/SDK消费者恢复、PG权威状态与完整最终输出。仍不签收真实模型能力、宿主生产短票据、容量或全部G5/T15；chunkReplay=false，在线丢失token不重播，完整最终内容以权威快照为准。
