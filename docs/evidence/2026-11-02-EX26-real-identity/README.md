# EX-26 真链路（真实 user-mgmt 作为身份源）

## 1. 目的

2026-10-30 的 EX-26 收口用的是 `user-mgmt` 桩（18170）。口径 B 要求平台把
`user-mgmt` 作为人身份权威源，本批把同一套 `session → authorize → forward →
skill → engine` 链路改为对**真实** `user-mgmt`（18070，RuoYi 3.9.2）取身份，
验证“口径 B 的接入点真能落地”，而不是只在桩上通。

## 2. 环境

| 组件 | 端口 | 说明 |
| --- | --- | --- |
| authority-service | 18086 | `AGENT_AUTHORITY_DELEGATION_ENABLED=true`，`identity.enabled=true` |
| user-mgmt（RuoYi） | 18070 | 真实服务；身份查询 `GET /system/apikeys/identity/self`（`X-API-Key`） |
| platform-gateway | 18080 / 18090 | iam 模式，portal 域含 `bizSystemCode`/`userId` |
| skill-registry | 18083 | `agent.skill.service-ticket-mode=AUTHORITY` |
| engine-runtime | 18082 | iam 模式，采纳 `skill→engine` 票 |

## 3. 过程中修的三处（都是本地运行环境，不是产品代码）

1. `AGENT_AUTHORITY_IDENTITY_URL` 仍是 `http://127.0.0.1:18170`（桩）→ 会话 **503
   `IAM_IDENTITY_UNAVAILABLE`**，审计 detail `api_key_ref_missing`/`identity_unavailable`。
   改为 `http://127.0.0.1:18070`。
2. `EX26_USERMGMT_API_KEY` 是 10-30 桩用的旧键 → 会话 **403**，审计原文
   `reason=user-mgmt 拒绝该 API-Key`。换成真实 `user-mgmt.sys_api_key` 里的 admin 键。
3. `restart-authority.sh` 会再次 `source ./env.sh`，**覆盖调用方 export 的同名变量**
   —— 先 export 再启动是无效的，必须改 `env.sh` 本身（本次即如此）。

## 4. 结果（`Ex26AuthorityChainTest`，EX26_EXPECT=delegation-on，rc=0）

```
[b-2] request_sha256=023865bd… recomputed=023865bd… match=true ttl=5s
[b-2] actor={tenant=local-***, sub=a***, roles=["admin"]}
[b-2] initial_ceiling={tenant_id="local-v12", sub="admin", roles=["admin"],
      attributes={"bizSystemCode":["agent-platform"],"userId":["admin"]}} operation={operation_id=op-435***}
[b-3] POST /oauth/token operation=forward client=gateway -> http=200 ticket.len=2412 ticket.typ=at+jwt kid=ex26-20261030
[b-3] ticket1.claims={aud=urn:ag***, sub="admin", caller="gateway", scope=proxy.***,
      authority_mode="delegated", grant_ref=grt-cc***, grant_expires_at=…}
[b-4] skill.authorize -> ok caller=gateway decisionId=dec-00*** grant=grt-cc*** authorizationVersion=delegated-v1
[b-5] ServiceTickets(AUTHORITY).ticket POST http://127.0.0.1:18082/internal/runs/run-ex26/wake -> ok ticket.len=2326
[b-5] ticket2.claims={aud=urn:ag***, sub="admin", caller="skill", scope="run.wake",
      authority_mode="delegated", grant_ref=grt-cc***}
[b-6] engine.authorize(ticket2) -> ok caller=skill tenant=local-*** sub=a*** roles=[admin]
      decisionId=dec-7b*** resource=skill-definition/ex26-demo-skill@1
```

要点：`sub=admin`、`roles=[admin]`、`attributes.userId=[admin]` 都来自真实
`user-mgmt` 的身份响应（`userName`/`roles[].roleKey`），不是桩造的数据；
委派天花板只剩四个键，`request_sha256` 与本地重算逐字节相等。

## 5. 未覆盖

- 该测试的 a/b/c 三组只覆盖“单次会话 + 单次委派 + 引擎采纳”，**不覆盖**多用户并发、
  角色变更后的历史会话失效、`user-mgmt` 宕机时的降级（当前按 503 fail-closed）。
- 十二张待验收卡的统一验收仍按台账 §14 单独跑，本批只补 EX-26 的身份源真实性。
