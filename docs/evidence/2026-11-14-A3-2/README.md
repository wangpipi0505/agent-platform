# A3-2 skill V321 迁移及技能双轨——证据补档（R-12）

- 台账归属：§7.4 阶段 3（预估 5 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `a2b59ec5` A3-2(skill): V321技能双轨与插件五表——kind扩列/GUIDE登记/意图预检/插件数据面
  - （后续 A3-6 `bb79d630` 顺带修复本域 2 项链增长失败；R-7 `91948e45` 修链尾断言）
  - 复查命令：`git log --oneline --all --grep="A3-2"`

## 验收口径（卡面摘录）

> SkillV321MigrationPostgresTest+SkillGuideDualTrackPostgresTest 24/24 绿；双路径（空库 agent_platform_v321_test migratedTo=321+存量 V320 存量形状→回填）；skill-registry 全量 893 例失败集与基线一致零回归；RoutesTest 12/12；GUIDE 五字段白名单解释器（TOOL 字段混入拒绝）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl skill-registry test -Dtest='SkillV321MigrationPostgresTest,SkillGuideDualTrackPostgresTest' -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.skill.application.SkillV321MigrationPostgresTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running cn.agentplatform.skill.application.SkillGuideDualTrackPostgresTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

**两类合计 12/12 全绿**。用例数演进对账（诚实登记）：卡面 24/24 为交付时点（2026-11-14）；A3-6 交付时该类已收敛为 4 例（"邻接回归 SkillV321 4/4"，台账在案），链尾断言经 R-7 改 ≥322 下限（M5-1 V901 种子合入后 322→901 演进）——现值 4+8=12，全绿，演进链在 git 历史可查（`git log --oneline --follow -- skill-registry/src/test/java/cn/agentplatform/skill/application/SkillV321MigrationPostgresTest.java`）。

## Surefire 报告指针

- `skill-registry/target/surefire-reports/cn.agentplatform.skill.application.SkillV321MigrationPostgresTest.txt`（mtime 2026-11-19 01:41:59）
- `skill-registry/target/surefire-reports/cn.agentplatform.skill.application.SkillGuideDualTrackPostgresTest.txt`（mtime 2026-11-19 01:41:42）
- 均为本补档实跑产物；target/ 不入 Git。
