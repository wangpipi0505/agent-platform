# R-4 网关测试留痕：Routes/IntegrationSecurity/verify-routes 重跑归档

日期：2026-11-18。卡片：台账 §7.6 **R-4 网关测试留痕**（核对发现：此前 RoutesTest 本地报告停在 2026-11-14 00:56 tests=12，T2-11/T2-15/T2-7 交付时口头声明的 14/15/16 与 A3-5/A3-6/A3-8 的 7/7、6/6 无本地报告在案）。

## 本卡执行（合入基线 e79aee1a 之上重跑）

| 项 | 结果 | 归档文件 |
| --- | --- | --- |
| `RoutesTest` | **19/19 绿**（`mvn -B -ntp test -pl platform-gateway -Dtest='RoutesTest,IntegrationSecurityTest'`） | `cn.agentplatform.gateway.domain.RoutesTest.txt` |
| `IntegrationSecurityTest` | **7/7 绿**（同命令） | `cn.agentplatform.gateway.IntegrationSecurityTest.txt` |
| `node scripts/verify-routes.mjs` | **MISSING=0**（handler 324 / 目录行 336 / STALE 6，blocking=false 通过；STALE 提示含 `app POST /internal/iam/integration-policy/resolve` 排除表失效条目，属信息性） | `verify-routes-2026-11-18.txt` |

## 用例数演进对账（诚实登记）

- 2026-11-14 00:56 报告 tests=12（此后各卡新增用例仅在各门 tag 说明中声明、未重跑归档——即本卡登记的欠账）；
- 门 tag 说明数字：T2-11 声明 14、T2-15 声明 15、T2-7 声明 16（各卡合入时点的新增计数，与 19 的现值一致递增：后续 A3-4 记忆五路径、A3-2/A3-6 治理与 MCP 前缀、B4-1 确认中心/任务列表/画布桥、T2-15 result 回读等每卡 +1~3）；
- 本卡现值 **19** = 各卡递增累计；逐卡对应断言均在 RoutesTest 源内带卡号注释（T2-11 事件流子路径/T2-15 result 回读/T2-7 子代理目录/A3-4 记忆/A3-2 插件治理/A3-6 MCP/B4-1 确认中心与画布桥等）。

## 结论

报告 mtime 2026-11-18（晚于本卡开工），MISSING=0，网关 authority 目录行与 Routes 显式接线一致。部署栈全链（真实网关进程+四服务联动）仍随 G5-0 承接，本卡为机制级留痕。
