# EX-43 第二阶段 B：model-gateway 与 monitor 的 iam 入站装配

## 1. 目的

EX-43 要求六服务在 `agent.security.mode=iam` 下可启动、可拒绝无票内部调用、并隐藏遗留
`/auth/dev-token`。第一阶段（admin-common 基座）与第二阶段 A（app/engine/skill）、
B 的前半（gateway）已入库；本批补齐 `model-gateway` 与 `monitor`。

## 2. 改动

| 文件 | 改动 |
| --- | --- |
| `model-gateway/src/main/java/cn/agentplatform/model/infra/ServiceTrustChainConfiguration.java` | 新增：`@ConditionalOnProperty(agent.security.mode=iam)`，装配 `ServiceAuthorization`、`ServiceCurrentAuthorityClient`、`ServiceTrustChain` 与入站 `MappedInterceptor` |
| `monitor/src/main/java/cn/agentplatform/monitor/infra/ServiceTrustChainConfiguration.java` | 同上，`SERVICE_ID=monitor` |
| `model-gateway/src/main/resources/application.yml` | `agent.security.mode` / `service-id` / `authority{issuer,jwks-uri,token-uri,decision-uri,client-id,client-secret-ref,allowed-algorithm,audience}` |
| `monitor/src/main/resources/application.yml` | 同上（与既有 `jwt-secret`/`issuer` 合并，避免 YAML 重复键） |

## 3. 实测（2026-11-02 09:4x，本机 1808x）

六服务同一套 iam 环境变量启动（`SPRING_PROFILES_ACTIVE=test`、`AGENT_DEV_AUTH=false`、
`AGENT_SECURITY_MODE=iam`、`AGENT_SECURITY_SERVICEID=<id>`、`AGENT_IAM_*` 指向
`http://127.0.0.1:18086`）：

```
18080:dev-token=404 internal=401    mgmt18090/actuator/health=200
18081:dev-token=404 internal=401
18082:dev-token=404 internal=401
18083:dev-token=404 internal=401
18084:dev-token=404 internal=401    mgmt18094/actuator/health=200
18085:dev-token=404 internal=401    mgmt18095/actuator/health=200
```

`model-gateway` 业务端口 `/actuator/health` 为 403、管理端口为 200，和 gateway 的口径一致：
iam 模式下业务链不再对 actuator 放行，探针走 `MANAGEMENT_SERVER_PORT`。

## 4. 未覆盖与边界

- 本批只验证“能启动 + 拒绝无票 + 隐藏 dev-token”，**没有**验证 model/monitor 的真实业务调用
  在 iam 下拿到合法票据（那属于 EX-26 真链路与 §14 统一验收）。
- 两模块的装配代码与 app/engine/skill 同构；共享基座（解码器、启动门、双链）的 77 例测试在
  `0acfa16d` 已入库，本批未新增模块级测试，判据以运行时探针为准。
- `agent.security.authority.client-secret-ref` 使用 `env:AGENT_SECRET_IAM_<SERVICE>_CLIENT`，
  `IamStartupGate` 要求该环境变量在本机可解析；缺失时启动直接失败（fail-fast），不静默降级。
