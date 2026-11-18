# R-8 EngineContextPostgresTest 夹具 bean 名冲突修复——证据补档（R-12）

- 台账归属：§7.6 R 系列收口补丁（预估 0.2 人日）
- 状态：**通过（2026-11-18，c0776c0e）**
- 交付日期：2026-11-18；补档日期：2026-11-19（R-12 批量补档；本卡不在索引第四节 54 张清单内——清单生成时本卡尚为"未开始"，2026-11-19 补档顺带补齐）
- 合入提交：`c0776c0e` R-8(test): 夹具扫描碰撞消除——12个独立启动型夹具@Configuration→@TestConfiguration+EngineContextPostgresTest补mode=test/service-id/test profile
- 复查命令：`git log --oneline --all --grep="R-8"`

## 验收口径（卡面摘录）

> 根因修正=12 个独立启动型夹具嵌套 @Configuration 换 @TestConfiguration（Spring Boot 组件扫描排除机制；作为显式 SpringApplication 源完全等价，零移动零导入 churn），扫描残留清零；**EngineContextPostgresTest 补 agent.security.mode=test+service-id+@ActiveProfiles(test)——2/2 绿=引擎全上下文本环境首次可启动**；独立启动器抽验全绿（PortalChildEvents 10/TaskEventsSseRealPort 2/IamInboundTrustChain 11/PortalRunEventsHttp 6/RunEventsHttp 1）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=EngineContextPostgresTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.638 s -- in ...EngineContextPostgresTest
```

**主判据 2/2 全绿**（引擎全上下文本环境可启动复验通过）。诚实登记：卡面"独立启动器抽验 5 类 30 例"未在本补档整批复跑（命令同上替换 `-Dtest` 逐一执行）；相邻佐证——同日本补档在相同环境实跑 PublishedAdmissionHttpPostgresTest 20/20（R-11，引擎全上下文+n3 子进程链）与 MemoryApiTest 9/9（R-9① 修复点）均绿，与"全上下文装配修复生效"互相印证。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.EngineContextPostgresTest.txt`
- 报告 mtime：**2026-11-19 01:53:51**（本补档实跑产物）
