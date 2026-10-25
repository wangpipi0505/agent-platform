# 已审查流式与 HTTP 异步阶段的整轮回归

2026-10-20 05:41:11 +08:00，`scripts/test.ps1` 完整后端单次运行退出码 0，BUILD SUCCESS。

| 模块 | tests | failures / errors / skipped |
| --- | --- | --- |
| admin-common | 5 | 0 / 0 / 0 |
| platform-gateway | 14 | 0 / 0 / 0 |
| app-management | 34 | 0 / 0 / 0 |
| engine-runtime | 134 | 0 / 0 / 0 |
| skill-registry | 98 | 0 / 0 / 0 |
| model-gateway | 66 | 0 / 0 / 0 |
| monitor | 15 | 0 / 0 / 0 |
| 合计 | **366** | **0 / 0 / 0** |

包含原文/快照及跨服务依赖边界校验，使用本工程真实专用 PG、Redis7.2.16、MinIO；测试只按既有测试库白名单运行。模型/外部业务仍使用协议 fixture，不能描述为真实供应商验收。

实际命令与原始日志：`scripts/test.ps1` → `.local/backend-stream-async-reviewed-all.log`。时长 3:44 仅用于追溯。日志含已有 ByteBuddy/CDS/Lettuce 警告，以及故意异常测试的 Spring AI error 栈，不称零警告。模块 Results 汇总分别是 5/14/34/134/98/66/15，不把各类单测行重复累计。

这轮覆盖独立复核后的 G1 输出策略、G3 统计及 NO_SEND 修复、G4 生命周期、G5 Redis/游标两项修复及 HTTP SUBMIT_QUERY 两项 P1 修复。对应真实服务证据见 `engine-stream-replicas-live.md`、`skill-http-async-live.md` 和 `integration-output-statistics-lifecycle.md`。

**阶段边界：** 此轮运行结束后，真实 65 秒浏览器试验进一步发现 MVC 默认约 30 秒结束连接、非终态空输出误当终答；其修复与 CALLBACK 后续代码不包含在 366 项的通过声明中。前端阶段基线是单独的 83 项全套与三构建，不与后端数字相加。完整17功能/34任务/30验收仍未签收。
