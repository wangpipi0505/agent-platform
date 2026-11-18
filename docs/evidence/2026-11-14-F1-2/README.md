# F1-2 壳层（菜单/面包屑/命令面板/角标）——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 2 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `a0d5ad67` F1-2(web): 壳层——五分组菜单/面包屑/命令面板/待办角标mock/page-access六角色类型
  - `b79df713` merge: wave2-F1-2（无新增失败）
  - 复查命令：`git log --oneline --all --grep="F1-2"`

## 验收口径（卡面摘录）

> AppShell 五分组（图标/折叠tooltip/localStorage记忆）+两级面包屑+Ctrl/K命令面板+确认中心角标（15s轮询mock-confirmation-counts）+page-access 六角色类型与映射；每组≤10 断言（最大组8）+26条目全落组+菜单15+挂起2 既有断言全过；vitest 505/518 较 §9.4 基线无新增失败。

## 复现命令

```bash
cd web && npm ci && npx vitest run
# 壳层锚点测试：web/tests/workspace-shell.test.ts（后续 F2-1 扩展同名文件）
grep -l "page-access\|面包屑\|命令面板" web/tests/workspace-shell.test.ts web/tests/navigation-policy.test.ts
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：page-access 六角色映射与菜单锚点在 `web/admin/src/page-access.ts` 与 `web/tests/navigation-policy.test.ts` 在册（F3-1 时 navigation-policy 16 例全绿口径在案）；壳层结构断言文件 workspace-shell.test.ts 在 HEAD 在册。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
