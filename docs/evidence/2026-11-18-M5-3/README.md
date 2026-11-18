# M5-3 场景转 E2E/剧本——验收证据（2026-11-18）

卡片：台账 §7.6 **M5-3 场景转 E2E/剧本（可复现）**；分支 `m53-e2e`（基线 f1f40e66）。
交付=API 级 E2E+多角色剧本（浏览器走查仍归 G5-0）；验收=**可复现**（停栈→起栈→复跑两轮全绿）。

## 场景清单×结果

| # | 场景 | 结果 | 断言 |
| --- | --- | --- | --- |
| S1 | 直接任务全链（受理/幂等/事件游标/取消恢复） | PASS | 28/28 |
| S2 | 计划任务全链（CAS 修订/确认凭据/执行） | PASS | 29/29 |
| S3 | 记忆域（私有CRUD/CAS/可见性/删除退出） | PASS | 31/31 |
| S4 | 插件发现闭环（发现/差异确认门/授权撤权） | PASS | 19/19 |
| S5 | MCP 连接档案（登记/test/discover/目录/审核） | PASS | 19/19 |
| S6 | 确认中心（四类计数/confirmableBy=me/确认闭环） | PASS | 17/17 |
| S7 | 外部集成（出票/白名单/绑定/过期语义） | PASS | 34/34 |
| S8 | 角色门矩阵（五身份×敏感端点） | PASS | 31/31 |
| **合计** | | **8/8 PASS** | **208/208** |

## 复现验证（本卡验收核心）

1. 第一轮：全量修绿后 8/8 PASS、208/208（2026-11-18 15:4x）。
2. 第二轮（可复现证明）：`stop-stack.sh` → `start-stack.sh`（全新五服务进程）→ `run-all.mjs`
   → 再次 8/8 PASS、208/208。两轮运行数据共存于 m53_e2e（场景以 runId 生成新实体，互不依赖）。
- 完整控制台留痕：`m5-3-e2e-run.log`（第二轮；逐断言 PASS/FAIL+场景汇总+入口命令回显）。
- 逐断言 JSON 报告：`m5-3-e2e-report.json`（scene/label/expected/actual/ok 全量记录，
  totals/scenarios/stack 汇总）。

## 环境快照（起栈方式与端口/库/版本）

- OS：Windows 10.0.26200 x64（Git Bash）；JDK：Temurin 21.0.12+8-LTS；Maven 3.9.16；Node v24.16.0。
- PostgreSQL：16.15（127.0.0.1:25432，本机实例），专用库 **m53_e2e**（agent_v12 属主；
  不触碰 dev 库 agent_platform_dev_v12 与任何共享 *_test 库）。
- 迁移+种子：五服务启动 Flyway 自举——gateway（无迁移目录）、app/skill/engine/model 全链迁移
  + **V901 演示种子**（app 38/skill 32/engine 2 行，tenant='demo'）。
- 端口：gateway 18480 / app 18481 / engine 18482 / skill 18483 / model 18484
  （`AGENT_BIND_ADDRESS=127.0.0.1`；`AGENT_SECURITY_MODE=dev-hmac`）；模型桩 18485（run-all 自起自停）。
- Redis/MinIO 未启动：engine/model `/actuator/health` 503/DOWN 属预期（观察流单副本本地降级），
  不阻塞业务链。
- 服务进程命令行见 `.local/m53-logs/*.out.log` 首行（Starting … with PID）；engine 经
  PropertiesLauncher+shim 外挂（见 scripts/e2e/README.md 缺陷登记①）。

## 剧本入口与复现步骤

```bash
mvn -B -ntp -pl platform-gateway,app-management,engine-runtime,skill-registry,model-gateway -am -DskipTests package
bash scripts/e2e/start-stack.sh
node scripts/e2e/run-all.mjs      # 非零退出码=失败
bash scripts/e2e/stop-stack.sh
```

多角色：ADMIN/BUILDER/OPERATOR/USER/SERVICE 五身份本地铸造 HS256 票据（ServiceTokens 同构，
经网关真实验签）+ SERVICE 票据面真实 /embed/ticket 出票（ApiKey+Origin）。密钥只从 .env 读取，
不入命令参数/日志/证据/Git。

## 诚实登记项（详见 scripts/e2e/README.md「四、登记项」）

- 平台缺陷（归口服务卡，本卡不改源码）：① Memories 双构造器致 engine 全上下文启动阻塞
  （shim 外挂补装配）；② 网关路由目录未登记任务计划端点（计划子链经引擎端口直连）；
  ③ 网关代理 PATCH ProtocolException；④ 网关代理丢弃 DELETE 体。
- 环境边界（随部署/G5-0 承接）：⑤ 集成凭据签发/撤销绑定 IAM 权威链（数据面置备凭据，
  IntegrationHostTicketTest 同口径）；⑥ 记忆共享/DRAFT 审批分层随 memoryPolicy 收窄不可经 API 触达；
  ⑦ MCP 传输为 StubMcpTransport（真实线缆随接入卡）；⑧ 任务执行派发停受理态（T2-5 接续），
  模型出话链经本地桩为真、真实业务模型出话未覆盖；⑨ PLUGIN_DIFF_NOT_SHOWN/来源停用拦截分支
  由单元层覆盖；⑩ monitor 未在栈内；⑪ 规划候选合并（PLAN_READY 自然路径）以同口径数据面置位替代。

## 本卡改动范围

`scripts/e2e/**`（新增剧本集）+ `docs/evidence/2026-11-18-M5-3/**`（本证据）。服务源码/前端/台账零改动。
