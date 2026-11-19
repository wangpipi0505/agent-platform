# R-27/R-28/R-29 验收证据（主线执行，2026-11-20）

三卡合并归档（各自小节）；执行环境=192.168.1.2 E2（compose-test.yaml，host 网络+iam 模式）。

## R-27 验证脚本端口参数化（0.3）

**交付**：`scripts/perf/verify-r24-agent-terminal.mjs`/`verify-r25-model-chain.mjs` 增 `E2E_DB_HOST/E2E_DB_PORT/E2E_DB_NAME`（默认 127.0.0.1:25432/r24_check 不回归）；`start-stack-r24.sh` 全端口段经 `E2E_PORT_BASE`（默认 189）与 `E2E_GATEWAY_PORT/E2E_APP_PORT/E2E_ENGINE_PORT/E2E_SKILL_PORT/E2E_MODEL_PORT/E2E_MODEL_STUB_PORT` 覆盖+DB 三键。E2 上跑法（细则 §9.1③"一条命令"）：

```bash
E2E_GATEWAY=http://127.0.0.1:18090 E2E_DB_NAME=agent_platform \
E2E_APP_PORT=18081 E2E_ENGINE_PORT=18082 E2E_SKILL_PORT=18083 \
E2E_MODEL_PORT=18084 E2E_MODEL_STUB_PORT=18985 \
node scripts/perf/verify-r25-model-chain.mjs
```

**判定**：不设 env 时 189xx/r24_check 默认值保持（E1 回归口径不变）；E2 只给 env 即整链指向 1808x（R-30 执行时已实测 env 注入路径——其 HTTP 段受阻于 ingress 形状属平台 backlog，非参数化问题）。

## R-28 frontend healthz 与上游路由修复（0.5）

**根因**：E2 host 网络无 docker DNS——nginx upstream `platform-gateway:8080` 解析失败（`host not found in upstream`，emerg×71 次 restart 留痕）。healthz 端点原图本有（`location = /healthz` return 200），restart 根因纯上游。

**修复**（源码 `deploy/nginx-test.conf` + compose-test frontend 段挂载+entrypoint 覆盖；`deploy/nginx.conf` release 原图零改动）：
- `deploy/nginx-test.conf`：`listen 18091`（E2 对外入口）+ `proxy_pass http://127.0.0.1:18090`（host 回环直达 gateway）；compose 挂载 `nginx-test.conf:/etc/nginx/nginx-e2.conf:ro` + `entrypoint: nginx -c /etc/nginx/nginx-e2.conf`（镜像 ENTRYPOINT exec 形式须用 compose entrypoint 覆盖——command 只替换 CMD 的教训留痕；行内注释用 `#` 非 `//` 的教训留痕）
- healthcheck 对齐：`wget http://127.0.0.1:18091/healthz`（compose 覆盖镜像默认 8080 探测）

**DoD 实录（服务器 2026-11-20）**：`docker compose up -d --force-recreate frontend` → **healthy、RestartCount=0**（修复前 restarts=71）；`curl 18091/healthz`=**200**；`curl -L 18091/`=**200**（index）；`curl 18091/v1/tasks`=**401**（未带票被网关正确拒绝=代理链+真实鉴权面通）。

## R-29 release gate 正规置位收口（1.0）

**SEC-04..06 负向留证（服务器，evidence/r29-neggate.log / r29-negdev.log）**：
1. **gate 未置位**：`AGENT_RELEASE_GATE=BLOCKED_IAM_SCOPE_ACCEPTANCE` + `AGENT_DEV_AUTH=false` 直跑镜像默认入口 → **exit=78**，stderr=`Deployment blocked: IAM and service-scope acceptance required.`
2. **dev-auth 开启被拒**：`GATE=IAM_SCOPE_ACCEPTED` + `AGENT_DEV_AUTH=true` → **exit=78**，stderr=`dev-auth is forbidden in this deployment.`
3. **验签面负向**（R-26 同卡已留证）：篡改票 401/过期票 401/HS256 伪造 401/同 kid 异钥伪造拒（docs/evidence/2026-11-20-R-26/）。

**恢复 entrypoint 原样**：compose-test 删除 7 处 `entrypoint: *backend-entrypoint` 覆盖（偏差③收口）→ 全栈 force-recreate。

**置位后放行验证（正规入口）**：七容器（六业务+authority）全部 `Started`；健康探测 gateway/app/skill/monitor=200、engine/model=503（依赖探针既有口径，服务活）；**放行过程中暴露并修复**：authority 服务段缺 `AGENT_RELEASE_GATE` env（entrypoint.sh 默认 BLOCKED→exit 78）→ 补齐后 authority Started+jwks.json=200。env 快照：compose-test.yaml authority 段 `AGENT_RELEASE_GATE: ${AGENT_RELEASE_GATE:-IAM_SCOPE_ACCEPTED}`，.env.test 该键=IAM_SCOPE_ACCEPTED（`docker inspect` 可复核）。

**无绕过路径核查**：entrypoint 覆盖已从 compose 移除（grep 零命中）；镜像 ENTRYPOINT 为原版 entrypoint.sh；两负向实验证明脚本判定生效。**置位记录**：本 README 即台账外显记录，正式置位由发布负责人确认（用户拍板③授权测试期先行置位，本卡完成"负向留证→正规入口放行"闭环）。

**遗留**：SEC-07 的"USER 票调 ADMIN 端点 403"在网关 iam 链 `/admin/**` 仅查 scope 的形状下归 SEC-07/E3（R-26 evidence 已登记）；authority identity/delegation 两模块保持关闭（偏差⑦，E3 复核）。
