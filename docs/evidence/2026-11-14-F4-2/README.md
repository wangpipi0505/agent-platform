# F4-2 执行请求+我的任务+我的记忆——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 2 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`88a7da23` F4-1/F4-2/F4-3(webapp) 同笔交付（`accf14af` merge waveL4C 合入，mock-task 双追加保留）
- 复查命令：`git log --oneline --all --grep="F4-2"`

## 验收口径（卡面摘录）

> AssistantChat：输入区 [Plan/Agent] 默认 AGENT→submitTask(requestId/mode/goal/inputs/sessionId/sessionCursor)→任务进度卡消费 taskEvents 游标增量→终态「带回会话草稿重新提交」；MyMemories：列表（REVOKED 退出检索/q/tags 过滤）→单条正文→PATCH 版本 CAS（409 MEMORY_VERSION_CONFLICT）→DELETE 先 REVOKED 幂等；webapp-assistant.test 记忆 CRUD 形状+submitTask 幂等+mode 必填全绿。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # webapp-assistant.test.ts
grep -n "MEMORY_VERSION_CONFLICT\|submitTask" web/tests/webapp-assistant.test.ts | head -5
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：webapp-assistant.test.ts 在 HEAD 在册；服务端对应机制 MemoryApiTest（CAS/REVOKED/403）已于本补档实跑 9/9 绿（A3-4 交付，见 A3-4 目录）——mock 契约形状与服务端判据两侧齐备。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
