# G6 C / Task3：approver 角色门槛具名复核

**Spec：发现当前 C/common 的确定缺口，建议 Important I3 同阶段修复。** 本记录只处理 root 指定的 approver 接口，不是 I1/I2 修复版复审，也不更改原 Task3 审查报告。

## I3 — 公共 approver 接口固定要求 APPROVER，阻断冻结 confirmRoles=EDITOR 的合法证明

**位置：** [HttpCurrentAuthorityClient.java:96](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:96)：

```java
if(!result.actor().roles().contains("APPROVER"))throw AuthorityProtocol.denied();
```

该判断位于在线 query、authority 映射及 approvedBy/grant 绑定验证之后，没有读取本调用的冻结 confirmRoles。即使外部 authority 已签发完全有效、绑定正确的当前批准者证明，只要原批准者当前只有 EDITOR 而没有 APPROVER，该公共方法就必然返回 403。Task5 必须经此接口取得证明，因此后续业务层无法仅通过正确核对 confirmRoles 保留合法 EDITOR 正例。

**已批准依据：**

- [root-decisions.md:9](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-service-authority-root-decisions.md:9)：确认角色按本调用冻结 confirmRoles；合法声明 EDITOR 的正例保留；ADMIN/EDITOR 没有自动豁免。
- [implementation-plan.md:143](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-service-authority-implementation-plan.md:143)、[implementation-plan.md:153](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-service-authority-implementation-plan.md:153)：固定 approver 接口返回真实 approvedBy 的当前证明，并绑定批准摘要与主体；未规定所有批准者必须具有 APPROVER。
- [implementation-plan.md:494](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-service-authority-implementation-plan.md:494)：RunAuthority.approver 取持久 approvedBy 与批准摘要后调用 §2.3 公共接口。
- [implementation-plan.md:534](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-service-authority-implementation-plan.md:534)：明确要求按本 call 实际 roles/confirmRoles 判断，保留 EDITOR 正例和声明 APPROVER、当前仅 EDITOR 的负例，并明确禁止全部硬编码 APPROVER。
- 原 Task3 的撤 APPROVER 测试要求位于 [implementation-plan.md:433](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-service-authority-implementation-plan.md:433)，它是特定撤权反例，不能解释为将通用接口收窄成唯一角色。

**影响与归属：** 当前组件错误拒绝已批准设计要求允许的证明获取路径，属于 C 公共接口行为错误，不是等待 E 才能判断的业务实现缺项。它不会扩大权限，但会迫使 E 绕开现有接口或后补 common 修改，当前阶段应纠正。`LoopbackAuthority.java:184` 固定签回 `Set.of("APPROVER")`，现有夹具仅覆盖这一角色，因而未暴露该接口限制。

**最小修复方向：**

1. 移除 common approver 的固定 APPROVER 门槛，保留完整在线 current、签名/nonce、期限、approvedBy、grant、approval 资源和摘要绑定。接口返回当前证明，不替代具体调用的确认规则判定。
2. 仅为有限测试让 fixture 能产生所请求真实批准者的不同当前角色证明；新增有效签名 EDITOR 证明可经公共接口取得的正例，保留原 APPROVER 撤权、错误主体/绑定、过期、503 等默认拒绝。
3. E/G2 仍必须按持久冻结 confirmRoles 核实原批准者，不给 ADMIN/EDITOR 自动豁免；“要求 APPROVER、当前只有 EDITOR”负例仍必须失败。不得把去掉固定门槛解释成跳过业务确认规则。

## 本次边界与证据

只读取该方法、fixture 的 approver 分支及指定批准文档相关内容；没有执行 suite/probe，没有读取其他业务源码，没有改动 common、target、原 Task3 报告或 I1/I2 证据。作者当前修复版的 121 项测试未在本次复核或签收。

读取时 SHA256：

| 文件 | SHA256 |
| --- | --- |
| iam-service-authority-root-decisions.md | `112ab5e67d931cf64319260059d21d1aa2b1d57b125a6ae05bd65cbbecca5fc41` |
| iam-service-authority-implementation-plan.md | `8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14` |
| HttpCurrentAuthorityClient.java | `4544407d13f25d6c5481eb33f1ef98fb518c70c1ebabe6abea6f10412959d540` |
