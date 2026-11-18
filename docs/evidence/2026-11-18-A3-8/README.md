# A3-8 轻量装载+资源清单——证据补档（R-12）

- 台账归属：§7.4 阶段 3（预估 4 人日）
- 状态：**通过（2026-11-18 验收）**
- 交付日期：2026-11-18；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`308f2279` A3-8(engine,gateway): 轻量装载——resource-manifest端点/两级清单/RESOURCE_NOT_LOADABLE定位
- 复查命令：`git log --oneline --all --grep="A3-8"`

## 验收口径（卡面摘录）

> ResourceManifestTest 6/6 绿（聚合/digest 409/空 skillCode MISSING+定位受理阶段/规则形状/角色门/启动清单 N/A）+RoutesTest 16/16+verify-routes MISSING=0；GET /v1/deploy/resource-manifest（appCode+releaseDigest 校验 409 RELEASE_DIGEST_MISMATCH）+角色门 BUILDER/OPERATOR/ADMIN 403；验收备注：ResourceManifestController:12 注入 ResourceManifestService 并实际调用。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=ResourceManifestTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.application.ResourceManifestTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.683 s
```

**6/6 全绿**——与卡面判据数一致，实测复验通过。RoutesTest 16/16 与 verify-routes MISSING=0 于 2026-11-18 R-4 重跑归档（现值 20/20 随 R-9，见 `docs/evidence/2026-11-18-gateway/` 与 `docs/evidence/2026-11-18-R-9/`）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.ResourceManifestTest.txt`
- 报告 mtime：**2026-11-19 01:40:06**（本补档实跑产物）
