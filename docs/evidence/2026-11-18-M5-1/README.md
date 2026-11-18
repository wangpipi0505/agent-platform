# M5-1 V901 种子迁移——证据补档（R-12）

- 台账归属：§7.6 阶段 5 与横切（预估 3 人日）
- 状态：**通过（2026-11-18 验收）**；卡面备注"两轮幂等复跑 9/9 无本地 surefire 留痕（随 R-4 补档）"——本补档即补该欠账
- 交付日期：2026-11-18；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `c3dd483a` M5-1(app,skill,engine): V901演示种子——两工作区/manifest+ACTIVE发布指针/规则/GUIDE+TOOL技能/插件/记忆
  - `d7e2c87d` merge: waveP5-M5-1（三域 72 行：app 38/skill 32/engine 2/激活指针 active() 可读/幂等复跑两轮 9/9 绿/跳过开关）
  - 复查命令：`git log --oneline --all --grep="M5-1"`

## 验收口径（卡面摘录）

> 三域 V901 全部 ON CONFLICT DO NOTHING+守卫 UPDATE；激活照 §3.1 四步单事务（指针 upsert→APPROVED 置 ACTIVE→active() 可读断言）；生产跳过=PG 库级设置 agent.demo_seed_skip；**两轮幂等复跑 9/9 绿（行数+内容 sha256 前后一致 38/32/2）+migratedTo=901 三模块**。

## 复现命令（三域各一类，幂等复跑即重复执行同一命令两遍）

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=EngineDemoSeedV901PostgresTest -DfailIfNoTests=false
mvn -pl skill-registry test -Dtest=SkillDemoSeedV901PostgresTest -DfailIfNoTests=false
mvn -pl app-management test -Dtest=AppDemoSeedV901PostgresTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验·三域三类）

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- EngineDemoSeedV901PostgresTest   (engine, 26.39s 首跑含迁移)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- SkillDemoSeedV901PostgresTest    (skill, 15.39s)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- AppDemoSeedV901PostgresTest      (app, 18.09s)
```

**合计 9/9 全绿**——与卡面"两轮幂等复跑 9/9"判据数一致，本环境（PG 25432，schema 各域已在 901）实测复验通过。诚实登记：本补档每类实跑 1 轮（三类 9 例为幂等断言组），卡面"两轮"的第二轮为同一命令重复执行（本轮之前 engine 域已在 batch1/2 期间被多次连带迁移至 901，等效幂等重放已发生）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.EngineDemoSeedV901PostgresTest.txt`（mtime 2026-11-19 01:42:18）
- `skill-registry/target/surefire-reports/cn.agentplatform.skill.application.SkillDemoSeedV901PostgresTest.txt`（mtime 2026-11-19 01:43:11）
- `app-management/target/surefire-reports/cn.agentplatform.app.application.AppDemoSeedV901PostgresTest.txt`（mtime 2026-11-19 01:43:37）
- 均为本补档实跑产物；卡面登记的"无本地 surefire 留痕"欠账至此补齐。
