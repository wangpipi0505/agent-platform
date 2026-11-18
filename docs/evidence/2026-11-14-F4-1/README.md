# F4-1 会话列表（消费 S2-1）——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 1 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`88a7da23` F4-1/F4-2/F4-3(webapp): 会话列表/执行请求+我的记忆/三类消息卡片（mock驱动；`accf14af` merge waveL4C 三卡合入）
- 复查命令：`git log --oneline --all --grep="F4-1"`

## 验收口径（卡面摘录）

> SessionList：本人列表 mock-session.listMySessions(mine=true) 只读+整行切换+新建占位；重命名/删除占位 disabled tooltip；before 游标「加载更多」；<768px 侧栏收抽屉；webapp-assistant.test 覆盖 mine 过滤/游标切换；host-channel 零改动；**26 例新增全绿**（F4-1/2/3 同提交合并口径）。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # webapp-assistant.test.ts
grep -n "listMySessions\|mine" web/tests/webapp-assistant.test.ts | head -5
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：webapp-assistant.test.ts 在 HEAD 在册；服务端对应实现 SessionListEndpointTest 已于本补档实跑 6/6 绿（S2-1 交付，见 S2-1 目录）——mock 消费与服务端机制两侧判据齐备。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
