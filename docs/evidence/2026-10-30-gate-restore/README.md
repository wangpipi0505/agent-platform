# 门禁恢复：EX-05 冻结边界与 G-9 路由目录（2026-10-30）

## 1. 发现（暂停点恢复时的第一批阻塞）

从 `AP 0bb27ebe` 恢复开发后先跑仓库门禁 `node scripts/verify-contracts.mjs`，连续失败两处：

1. `Error: EX-05消费映射JSON或结束边界无效`（`scripts/verify-contracts.mjs:38`）。
   根因：`contracts/skill-invocation.md` 末尾的 EX24 增补段（10,210 字节）被追加在
   `<!-- EX-05-CONSUMPTION-END -->` 之后，而门禁要求该 JSON 块与 END 标记位于文件末尾（`$` 锚点）。
2. 修好边界后暴露第二处：`G-9 路由目录漂移：1 个服务票据面 handler 没有目录行`
   —— `engine POST /internal/evaluations/turn`（`engine-runtime/src/main/java/cn/agentplatform/engine/api/ChatSessionController.java:19`），
   由 `monitor/src/main/java/cn/agentplatform/monitor/application/EvaluationConversations.java:117` 真实调用。

两处都不是功能缺陷，而是**门禁被文档追加与新增端点绕过**：前者的后果是冻结契约校验失效，后者的后果是带 `RouteCoverageVerifier` 的夹具运行期抛 `ROUTE_COVERAGE_MISSING`。

## 2. 处置

1. 把 EX24 增补段从 END 之后移到 §16 引言与 JSON 块之间（§16 与 JSON 块内容不变），
   使 END 重新成为文件末尾。**冻结前缀 0..63,602 字节逐字节未变**，
   校验命令内断言 `sha256(prefix) == freeze.json[skill-invocation].sha256` 通过。
2. 在 `admin-common/src/main/resources/agent-service-authority.json` 增加一行：
   `POST /internal/evaluations/turn` → `caller=monitor`、`target=engine`、`action=evaluation.turn`、
   `mode=delegated`、`integrationAllowed=false`、`source=…ChatSessionController.java:19`、
   `callerSource=…EvaluationConversations.java:117`。目录行 183 → 184。

未改任何 Java 逻辑、未改冻结契约正文、未改迁移。

## 3. 验证（原样输出）

```
$ node scripts/verify-contracts.mjs
  verification: connection=通过  contract=通过  model=通过  evaluation=关闭(豁免留痕)  safety=通过  concurrency=关闭(豁免留痕)
  错误 0 条，警告 0 条
能力包校验通过：1 个声明，0 条错误，0 条警告
v1.2快照、四契约冻结内容（skill历史前缀）、EX-05消费映射与七模块依赖边界通过
```

```
$ mvn -B -ntp -pl admin-common test -Dtest='RouteCoverageVerifierTest,ServiceAuthorizationTest,RunWakeCatalogTest,EngineConsoleCatalogTest'
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- EngineConsoleCatalogTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- RouteCoverageVerifierTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- RunWakeCatalogTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- ServiceAuthorizationTest
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 4. 未覆盖与边界

- 只跑定向四类；未跑全模块（本轮按开发规则不做全量回归）。
- 门户面缺行告警（26→27 条）与 `STALE` 仍只告警不阻断，处置口径见 `docs/evidence/2026-10-27-G9-route-drift/README.md`。
- EX24 增补段位置变化只影响文档排版，其内容与 `docs/evidence` 对应卡片记录一致；未重跑 EX24 功能测试。
