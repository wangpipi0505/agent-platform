# 实际异步评测页面验证

2026-10-20，root在846新开发服务就绪后执行 node web/tests/evaluation-async-live-browser.mjs --g5-ready，退出0。没有route mock，实际登录→gateway→app/engine/monitor→PG。沿既有纯START→END草稿smoke_named_inputs_3db244a637、draft34/rev3，候选摘要保持676db1269ccad10ec2fca00a4fd9bc90fc4e8df33d4b8e9a38e1ec5089c55e8d，无模型/外部业务。

10项检查通过：保存命名输入3/rev2、显式恢复、debug输出answer=命名输入完整联调、FLOW390两节点完整、修改rev2、独立expected/safety提升、不可变数据集选择、只创建一份原报告并观察到通过、原报告ACCEPTED、实际API收到精确冻结用例。原始受控及同步脚本未改；新增runner只将旧脚本的立即终态假设适配为实际异步GET/resume。

报告er_350f803c-530b-4aad-9789-1304cc3b9e3d首次RUNNING，页面GET1次、对原报告resume1次后ACCEPTED；用例runId保持r_0e2be7f772094f8abd3c8c5abfd68ee4，evaluation创建POST恰好1。pageErrors为空，390文档宽390、画布244且两节点可见；已实际读图accepted-390。没有发布版本。详细请求脱敏记录为frontend-evaluation-async-live.json，长期摘要和SHA见同名summary.json。

这是一个有限纯FLOW候选的真实异步链路，不替代写技能等待审批、整页刷新创建幂等或全数据集治理验收。127单测、三构建和两P2独立关闭另见frontend-evaluation-review-fixes、review-frontend-evaluation-async。
