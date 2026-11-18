# A3-1 app V122 迁移、manifest 和发布预检——证据补档（R-12）

- 台账归属：§7.4 阶段 3（预估 5.5 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`e80a148d` A3-1(app): V122工作区manifest——两表迁移+CAS CRUD+发布预检固化+网关登记（子智能体交付）
- 复查命令：`git log --oneline --all --grep="A3-1"`

## 验收口径（卡面摘录）

> 专用库 migratedTo=123（24 迁移全链，V122/V123 有序）+WorkspaceManifestPostgresTest 5/5+RoutesTest 10/10；V122 两表（workspace_manifest 不可变触发器+成对 CHECK 扩列）+manifest CRUD（CAS 409+digest 去重）+发布预检 manifest 引用核验+发布固化成对列+网关 3 目录行。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export A31_DB_URL="jdbc:postgresql://127.0.0.1:25432/agent_platform_v122_test"
set -a; source .env; set +a
mvn -pl app-management test -Dtest=WorkspaceManifestPostgresTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0 -- in ...WorkspaceManifestPostgresTest
[ERROR] WorkspaceManifestPostgresTest.v122DeliversWorkspaceManifestAndPairedReleaseColumns
org.opentest4j.AssertionFailedError: app schema 必须迁移到 V123 ==> expected: <123> but was: <901>
（测试源 :235 assertEquals("123", version, "app schema 必须迁移到 V123")）
```

**4/5 绿+1 例断言过期（诚实登记）**：
- 1 例失败为**链尾断言过期**，非功能回归——M5-1 V901 演示种子（`c3dd483a`）把 app 迁移链头从 123 推进到 901，A3-1 交付时写死的 `==123` 断言在 HEAD 过期；与 R-7 修复的 M5-1×A3-6 互作（McpConnectionTest ==322→≥322）**同类不同卡**，R-7 未覆盖此处。
- 失败前的机制断言（V122 两表/不可变触发器/成对 CHECK 拒绝/CAS）在本用例内已按序通过至 :235 行（失败点在链尾版本号断言，位于触发器/CHECK 断言之后）。
- 本卡为证据补档（R-12 红线：不动源码），该过期断言**登记待主线处置**（建议随 R-20 待确认裁决或同类"≥123 下限"修法），不在本补档擅自修改。

## Surefire 报告指针

- `app-management/target/surefire-reports/cn.agentplatform.app.application.WorkspaceManifestPostgresTest.txt`
- 报告 mtime：**2026-11-19 01:47:18**（本补档实跑产物；A31_DB_URL 需显式设置——测试 @EnabledIfEnvironmentVariable(named="A31_DB_URL")，缺省时 5 例全 skip，第一次 batch 运行即因未设该变量而全跳过）
