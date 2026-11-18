# F3-5 manifest 前端分区编辑+发布联调——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 3 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `c21a9ffd` F3-5(web): manifest分区卡编辑——七分区受控编辑/CAS 409/预检同屏/checklist联动
  - `c84ceda5` merge: waveL4D-F3-5（11 例新增绿+零新增失败）
  - 复查命令：`git log --oneline --all --grep="F3-5"`

## 验收口径（卡面摘录）

> ManifestPanel 七分区卡受控编辑（照冻结 manifest.schema.json；无裸 JSON textarea——元素级断言锁定）+PUT CAS 409 同屏重载+本地预校验镜像后端+预检 checks 与校验 issues 合并同屏+发布 checklist 联动；**任务 a 步数=6≤6**（测试用例源码可追踪）；mock-manifest demo:true 镜像 A3-1 语义；**manifest-panel.test 11 例全绿**+vitest 576 例 13 失败=§9.4 基线一致零新增。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # manifest-panel.test.ts
grep -c "分区\|section" web/tests/manifest-panel.test.ts
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：manifest-panel.test.ts 在 HEAD 在册；发布联调链（预检→提交→digest）的机制级锚由 A11 证据承接（`docs/evidence/2026-11-18-A11-A13/`）。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
