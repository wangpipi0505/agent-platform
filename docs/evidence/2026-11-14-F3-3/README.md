# F3-3 资产管理页——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 1.5 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `603796b0` F3-3(web): 资产管理四页——记忆/规则/插件/MCP+确认中心四类待办矩阵（mock驱动）
  - `edb1a913` merge: waveL4D-F3-3（39/39 相关测试绿）
  - 复查命令：`git log --oneline --all --grep="F3-3"`

## 验收口径（卡面摘录）

> 资产四页（MemoriesPage/RulesPage/PluginsPage/McpConnectionsPage）+ApprovalsPage 四类待办矩阵+角标与列表同源 total=Σ 四类；**asset-pages.test 13 例全绿**（记忆 CAS 409/规则互斥 409/发现状态机/四类 Tab+角标一致性/真实 SFC setup 断言）+vitest 全量 578 例 13 失败=§9.4 基线一致零新增。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # asset-pages.test.ts
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：asset-pages.test.ts 在 HEAD 在册；后续 M5-2 切真实 API 时该页 mock 分支收口（34fd247d→a8aee16a，台账在案），页面结构断言保留。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
