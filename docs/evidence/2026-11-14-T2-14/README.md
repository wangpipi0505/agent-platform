# T2-14 前端配合：任务/计划/会话 API mock 契约——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 5 人日；泳道 L4 首卡）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`c8a9d472` T2-14(web): 任务/计划/会话mock契约模块——按冻结契约先行交付供F3/F4开发
- 复查命令：`git log --oneline --all --grep="T2-14"`

## 验收口径（卡面摘录）

> mock-task/plan/session.ts + 契约测试 7/7 绿（幂等/REQUEST_REPLAY/状态矩阵/游标不+1/PLAN 三错误码/TASK_INTERNAL 排除）；F3/F4 可开发（消费在阶段 4）。

## 复现命令

```bash
# 前端 vitest（本工作树未装 node_modules，未复跑——诚实登记；主线 web/ 可复现）：
cd web && npm ci && npx vitest run
# 契约模块在册核查：
ls web/shared/mock-task.ts web/shared/mock-plan.ts web/shared/mock-session.ts 2>/dev/null || find web -name "mock-task*" -o -name "mock-plan*" -o -name "mock-session*" | head
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树无 node_modules）**。登记项：
- 契约模块在 HEAD 在册（mock-task/mock-plan/mock-session 供 F3-1/F3-2/F4-1/F4-2/F4-3 消费，后续 R-9⑤ 任务端点收业务号时同步演进）；
- 卡面 7/7 为交付时点（2026-11-14）口径；本补档仅核查模块在册与 git 演进，未重跑 vitest——诚实登记。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕（node_modules 不入 Git），复现命令附上。
