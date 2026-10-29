# EX-06 Model 协议候选独立静态复核

2026-10-29，`artifact_boundary_review`；非本候选作者。结论：**无阻断，准入 root 串行执行本次 Windows PowerShell 5.1 纯协议 driver。** 此结论只对应下列两个 SHA 和原 Joint.Common，不等于已实际通过63项，更不等于 AP provider/技能/三应用或 J/S1 已通过。

本轮只读源码/归档，执行了独立 PS5.1 Parser API（未执行被解析脚本）。未启动 Node、未运行协议 driver/HTTP/Java/PG/产品，未修改候选、产品、index 或三主文档，仅新增本文。

| 文件 | 已核 raw SHA-256 |
| --- | --- |
| `scripts/acceptance/fixtures/Joint-ProtocolFixture.mjs` | `bcfa907779dd98cb69a26a8de9d20d4e048acebc6cbdf2e38255f2ecca5ff401` |
| `docs/evidence/2026-10-29-EX-06/Test-JointProtocol.ps1` | `26107bc6e05f1db85fda28f6c3f02cff34c3f60c0057450619e8a82c2cbff4c0` |
| `scripts/acceptance/Joint.Common.ps1`（既有 A008） | `a008f692d89d18a416b657c9cb3b5f08f804b2bb44205e616253b90db48505cd` |

## 已核事实

- 与本卡 `or-stage-source/` 归档原件比较：原 `/protocol/ok` 至原404的路由块逐字相同；原 driver 首个实际 envelope 断言至私有目录逃逸断言的整个块逐字相同。新增模型角色没有删除/弱化原25项。
- 新段有12个直接 Assert-Protocol 和13个 Assert-ModelReject（每个含错误码与安全错误两断言），静态期望38项；25+38=63。driver UTF-8 BOM存在；Windows PowerShell **5.1.26100.9711** 对当前 driver ParseFile 得到0错误。没有运行 Node语法检查或driver，不把静态计数写成执行结果。
- Model 为显式角色，CLI 拒未知/重复参数；模型名称和已登记工具名有界且唯一，保留 health_probe 专用名。Protocol 默认角色仍有效，两个角色不互相提供业务路由。
- `/v1/models` 只返回已配置模型；chat 使用原生 OpenAI JSON，不经过 Joint.Common 的平台 code/data 信封解析。目录/chat/usage、tool_calls JSON arguments、准确 health_probe 提示和已登记 tool 选择均有明确向量；模型工具选择不是“第一个 offered tool”。
- 二轮必须有匹配 assistant call / tool_call_id /工具名、可解析的工具结果与空参数对象；不匹配历史拒绝。安全提示仅返回固定 EX06_REFUSE 且无调用。未知模型/工具、未提供的工具、坏 JSON、坏 messages、stream=true、重复工具、错误历史及产品 `/admin/evaluations` 路由都明确拒绝，错误不回显输入。
- 内容仅为固定模型协议桩；usage=3/2/5是合成协议字段，不能作为真实外部模型 token计量证明。EX06_SAFETY 是显式测试输入，不是通用模型安全策略或真实模型拒绝能力验收。

## 资源与关闭边界

fixture 只监听随机 loopback端口；最大16连接、100请求、64KiB模型请求体，chunked也累计计数；JSON仅在有界读完后解析，UTF-8使用fatal解码。body总预算1.5s，另有headers/request/idle连接期限及60s进程总寿命。没有上游访问、业务成功假接口、进程派生或任意文件操作；ready文件只在既定 `.local/acceptance` 下以create-new写入。

模型停止需本次nonce；错误nonce向量确认进程仍可用，正确nonce返回ack并等待exit0。停止先关闭监听/空闲连接，250ms后兜底关闭剩余连接；driver每个HTTP有3s读写期限和64KiB响应上限。两个Node都由本次Start-Process持有句柄，失败finally只终止这些owned子进程并有界wait，不按端口/进程名扫杀。

driver拒绝已有EvidenceDirectory，通过现有私有目录机制建立本次输出；nonce+PID+port+role/model/tool登记核验后才发请求。合成密钥测试沿原私有存储，日志/错误不回显原始请求。原Protocol子进程finally的WaitForExit返回值仍沿旧行为未单独计断言，root采集时应一并保留两个owned子进程的实际退出事实；不把新模型的modelStopped=true替代原Protocol进程退出检查。

## 实际窗口与证据范围

使用新的本卡私有EvidenceDirectory，冻结上述SHA、Node实际路径/版本及PS5入口，运行原driver并保留native exit、protocol-checks.json、63个实际check与两个owned子进程退出。失败如实保留phase/safeError，不修改断言或把准备失败称业务红；只有新原件证明63项/native0才可记本次协议通过。

本候选明确拒绝stream=true，只证明当前固定空参数查询、health与两轮非流式协议；尚未证明Spring AI真实wire、模型供应商/条目健康、技能选型/执行、评估发布或AP整链。后续产品接线若使用不同消息形状/流式，需依据真实源另补精确协议范围，不能由本次静态准入推导已兼容。无需为本次纯协议窗口扩大产品或基础设施白名单。
