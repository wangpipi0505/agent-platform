# B4-1 页面权限配合、画布桥和待办角标——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 8 人日）
- 状态：**通过（2026-11-18 验收）**
- 交付日期：2026-11-18；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `7ba3b54e` B4-1(engine,gateway): 确认中心计数/任务列表confirmableBy=me/画布桥plan-import+网关登记
  - `66b7dc79` merge: waveP4-B4-1（合并冲突收口：RoutesTest 双追加保留 19/19 绿）
  - 复查命令：`git log --oneline --all --grep="B4-1"`

## 验收口径（卡面摘录）

> ConfirmationCenterService+端点 GET /admin/confirmation-center/counts（四类计数 publishReview/highRisk/planConfirmation/draftMemory+total=Σ+授权过滤先于计数+非授权 403 审计+可选 appCode）；Tasks 增 GET /v1/tasks 列表（state+confirmableBy=me 修订为单位）+POST /admin/apps/{code}/draft/plan-import 画布桥；**ConfirmationCenterTest 8/8 绿**；RoutesTest 双追加 19/19 绿。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=ConfirmationCenterTest -DfailIfNoTests=false
# 网关侧 RoutesTest 已由 R-4 归档 19/19（docs/evidence/2026-11-18-gateway/）
grep -rn "confirmableBy" engine-runtime/src/main/java/cn/agentplatform/engine/application/Tasks.java | head -2
grep -n "plan-import" platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java 2>/dev/null | head -2 || grep -rn "plan-import" platform-gateway/src/main --include="*.java" | head -2
```

## 关键输出摘要

**未复跑（本补档未执行 ConfirmationCenterTest；诚实登记）**：
- 交付时点 ConfirmationCenterTest 8/8（2026-11-18 验收记录，"315 基线 vs 317=+2 新增通过，既有错误一致"）在台账卡面在案；
- 生产调用点核查（grep 实测）：Tasks.confirmableBy=me 投影与 plan-import 画布桥在 HEAD 源码可 grep 命中；
- RoutesTest 19/19 于 2026-11-18 R-4 重跑归档（gateway 目录）；本补档复跑清单未含此两类（batch 排期取舍，命令附上即可复现）。

## Surefire 报告指针

- ConfirmationCenterTest/RoutesTest：报告未在本补档实跑（复现命令附上）；R-4 侧 RoutesTest 19/19 归档于 `docs/evidence/2026-11-18-gateway/cn.agentplatform.gateway.domain.RoutesTest.txt`（mtime 2026-11-18）。
