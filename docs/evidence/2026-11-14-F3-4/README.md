# F3-4 集成控制台合并+模型管理合并——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 1 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `16738d8f` F3-4(web): 集成控制台7合1+上下文持久+模型管理master-detail+菜单收敛
  - `4094aa59` merge: waveL4B-F3-4（零新增失败）
  - 复查命令：`git log --oneline --all --grep="F3-4"`

## 验收口径（卡面摘录）

> IntegrationConsolePage（六面板左导航懒挂载+appCode/panel 双 query 持久+旧 5 深链 redirect）+ModelManagementPage master-detail（供应商列表连通状态点+条目表格+连通测试）；菜单 15→14（每组≤10 保持），page-access 27→20；vitest 548 例 13 失败=§9.4 基线一致零新增；vue-tsc 改动文件零错误。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # integration-console.test.ts
grep -n "panel=\|redirect" web/admin/src/pages/integration/IntegrationConsolePage.vue | head -5
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：integration-console.test.ts 在 HEAD 在册；模型管理页 ModelManagementPage.vue 在册（grep 可命中）。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
