# 输出、统计与应用历史的真实接口联通

范围仅本独立新工程。模型仍为明确标记的本工程HTTP协议fixture，PG/Redis7.2.16/MinIO为真实独立基础设施。没有访问相邻项目、真实业务供应商或生产环境。

## 完整回归与开发进程

2026-10-20 04:58:21，`.local/backend-output-statistics-lifecycle-all.log`：common5 + gateway14 + app34 + engine111 + skill77 + model57 + monitor15 = **313项，全部0失败/错误/跳过，BUILD SUCCESS**。该轮包含H1、G1输出策略及Schema修复、应用复制并发修复和第一版统计；晚到NO_SEND统计修复以其后66项model专项及独立复核为准，不混成一次完整322项。

第一次打包因Windows运行中JAR的重命名锁失败，保存`.local/backend-reviewed-package.log`，未计构建成功。之后`scripts/stop-backend.ps1`逐个校验本工程登记PID/绝对JAR才停止，04:59:13重新打包成功并启动六服务（`.local/backend-reviewed-package-retry.log`）。随后实测18080～18085的`/actuator/health`均200/UP。

NO_SEND统计P2独立关闭后，另安全停止并重打/启动model-gateway，日志`.local/model-nosend-reviewed-deploy.log`。该服务包含model66项对应源码；engine跨副本流式增量仍在施工，不冒称已部署。

## 统计：真实验签、并发幂等与源表核对

`node tests/smoke-statistics.mjs`实际经过网关、内部票据HTTP和两个权威统计服务。发布CHAT fixture `smoke_chat_bff1adb366`的UTC窗口/FORMAL筛选返回4次运行，与独立运行数据库`agent_platform_dev_v12`的源表只读计数4一致。未把同页合计当成全量；该筛选的全部分组在100行内。

三个并发相同requestId重建返回同一snapshotId；读取该持久快照仍4次。相同requestId更换用途409，READER查询/重建403，另租户ADMIN读该快照404，篡改JWT401。测试身份为本工程密钥在测试进程内签发的180秒短期用户票据，未写入证据、浏览器或日志，**不代表生产IAM已验收**。

NO_SEND服务更新后再次执行同脚本通过；最新小证据`.local/statistics-live-smoke.json`，snapshotId `a516f1c9-0add-434d-896c-751a10e58720`。报告如实显示`PER_SOURCE_SNAPSHOT`，未宣称跨服务同一数据库事务。

首次脚本在预期401的空响应上直接`.json()`失败，已修测试读取方式；没有以此推定业务失败或绕过认证。原三次并发成功仍须由最终整轮脚本的明确断言确认，最新执行已全部通过。

## 输出：三个失败分支与发送前Schema门禁

`node tests/smoke-output-policy.mjs`创建独立fixture供应商/模型（声明json_mode，实际故意返回非JSON）和四个DRAFT应用，真实通过模型HTTP及引擎执行，读取专用运行PG的MAIN调用记录：

| 模式 | 实际状态 | 持久MAIN模型调用数 |
| --- | --- | --- |
| TERMINATE | FAILED，节点OUTPUT_INVALID | 1 |
| RETRY，maxRetries=1 | FAILED，节点OUTPUT_INVALID | 2 |
| PASS_WITH_FLAG | SUCCEEDED，outputValidation.valid=false，原文保留，无structured | 1 |
| Schema引用`#/missing` | FAILED，节点OUTPUT_POLICY_INVALID | 0 |

小结果含runId、应用和诊断，保存在`.local/output-policy-live-smoke.json`。脚本最初错误地只读取run.error.semantic；实际语义在节点错误中，后修正为核对节点权威错误，并重新完整执行四例，未放松预期。

PASS应用`smoke_output_pass_with_flag_4d0ffb0927`还经过规则评测ACCEPTED→precheck→commit成为正式v1。评测预期来自固定fixture文本/usage/模型及冻结验证规则，未从实际结果反抄为预期。它可供真实WebApp警告联通；组件层的Chrome最终快照替换/提示/对比度证据另见`frontend-output-policy.md`。

## 应用复制与两个不可变正式版本

`node tests/smoke-application-history.mjs`经真实copy创建新草稿，继而两次真实候选评测ACCEPTED、预检及发布。第二版改变description/systemPrompt。正式应用`smoke_versions_37bb9848b5`：v1→v2的结构化diff只有`/config/systemPrompt`和`/description`，旧v1提示保持原值；6条生命周期日志包含CREATE/COPY及两次PUBLISH。小结果`.local/application-history-live.json`。

早期测试请求遗漏PUT必需的draftRev/name，被400正确拒绝；脚本修正为发送完整元数据并明确恢复指定smoke fixture，未放宽后端校验，也未删除任何已发布记录。此前已生成的fixture v1保留为可审计测试数据。

前端真实Chrome已在该应用读取双版本diff和日志，以及复制、显式归档筛选、统计查询/重建/快照与技能统计；详见`frontend-statistics-lifecycle-report.md`和对应live JSON/截图。前端新增消费者仍按独立评审及后续追加处理，不能据局部联通关闭所有T24/T26/T31出口。
