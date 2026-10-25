# HTTP SUBMIT_QUERY 实际服务纵切

2026-10-20，仅在独立新工程执行。原 `review-skill-async-http.md` 两项 P1 定向关闭后才更新本工程 skill 进程；迁移 V307/V308 没有用于任何相邻库。构建启动日志 `.local/skill-async-reviewed-deploy.log`，skill PID 65324、端口 18083；主引擎 18082。

执行 `node tests/smoke-http-async.mjs`，退出码 0。脚本独占 28415 的外部协议 fixture，实际走网关创建技能 DRAFT、受控 DEBUG、引擎调用登记/认领/submitted、技能外发、事实投递、引擎唯一逻辑提交，以及连接验收重检。PG 是本工程运行库。查询恢复使用本地测试服务 JWT，保留开发主体并增加内部 SERVICE，用于隔离测试，不代表生产 IAM 或可信 relay 验收。

| 场景 | runId / callId | 外部提交 | 原任务查询 | 最终结果 |
| --- | --- | --- | --- | --- |
| 提交后立即完成 | r_1d364fbd7c3e46c9afc5f582f51395b5 / 48 | 1 | 1 | SUCCEEDED |
| 首次 RUNNING，完成后对账 | r_9002aaa1eb71412b8b08c7d7a5d9bf74 / 49 | 1 | 2 | SUCCEEDED |
| 真实断开提交响应，按原 key 找回任务 | r_7f7deba12dfb48d9a213880e1bb1b257 / 51 | 1 | 1 | SUCCEEDED |

各场景同时断言：绑定 run/key/实际发送标记正确；技能事实和引擎 tool_call 都成功；结构化结果为 fixture 的 `原任务已完成`；该技能节点恰有一次 LOGIC_RESULT_COMMITTED；outbox 为 DELIVERED；DRAFT 同摘要受控连接验收为 true。等待场景在查询前为 WAITING_EXTERNAL、tool_call UNKNOWN；普通用户票据访问内部对账接口均 403。jobId 故意含空格和斜线，实际按原 job 路径编码查询。

最初脚本将整个 START/SKILL/END 图的三次逻辑提交误当技能节点的一次，断言失败。改为按 tool_call.node_instance_id 过滤后，重新创建独立技能并完整跑完三场景。业务实现没有因此调整，失败 fixture 记录保留。最终原始记录为 `.local/skill-http-async-live.log` / `.json`。

这不是 CALLBACK、NOT_APPLIED 自动重试、取消三态或人工审计验收。未做 OS 进程 kill、真实供应商或生产 IAM 联调，不能据此关闭全部 T11。

后续skill167对账证明及票据修复经独立复核后，本脚本再次真实执行退出0，三场景仍各提交1次、技能节点逻辑提交1次并成功。上表及PID是98阶段历史记录；其原始JSON/日志已归档为 `.local/skill-http-async-live-stage98.json` / `.log`，当前无阶段后缀文件对应167阶段。此轮测试服务票据已明确设置internal audience/service用途，未以portal票据附SERVICE角色代替内部票据。新服务与证明纵切合并说明见 `skill-reconciliation-proof-live.md`，不修改前述范围限制。
