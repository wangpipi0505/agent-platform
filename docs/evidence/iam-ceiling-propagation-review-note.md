# G6 跨阶段复核关注：裁剪上限不能在换票或恢复中丢失

这是 root 阅读已批准计划接口后登记的后续验证关注项，不是当前源码漏洞结论，也不阻塞 Task2 的纯输入实现。依据仍是原计划 §2.3 的有效范围：原委托上限 ∩ issuer/client 声明上限 ∩ 当前可信范围 ∩ 具体操作守卫。

Task2 的 PortalClaimMapper 将原已签门户 JWT 映射为受本地 issuer/client 上限裁剪的 Actor 和 scope；换票时原 JWT 字节仍须完整交给固定 authority，不能改签或把本地裁剪后的普通 JSON 当可信 JWT。Task3/4 要核实：此时已经收窄的上限以什么可信证据约束换出的票据、当前决定及后续转发，不能只验证它们的签名便重新接受原JWT中已被本地裁掉的 ADMIN/region。授权接口禁止把调用方自报 roles/attributes 当权限来源；不要为解决此问题另造不受验证的授权来源。

后续有限矩阵应具体包含：门户原 JWT 的 roles=[ADMIN,EDITOR]、region=[A,B]，本地可信客户端上限仅 roles=[EDITOR]、region=[A]；authority 的候选回复若给回更宽范围，软件必须拒绝或证明有效结果仍限制在原上限。另核恢复：原门户JWT已过期后允许的独立DELEGATED新操作，也不能借当前范围或grant引用补回最初没有的角色/region。持久历史范围是上限，不能当当前授权；两者均需满足。按正常小样本/Clock验证，不重放真实业务或扩展生产IAM。

解决方式以实际 Task3/4/5 的可信grant/票据绑定、上下文和持久scope实现为准。若已有等价验证，记录证据即关闭；本关注项不指定新增协议字段、不改变已固定Java接口或四契约，也不把RFC8693当作自动提供该保证。root应在相关任务审查时移交并追踪结论，生产门禁保持BLOCKED_IAM_SCOPE_ACCEPTANCE。
