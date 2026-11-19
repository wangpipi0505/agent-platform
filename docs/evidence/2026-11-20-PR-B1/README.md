# PR-B1 执行记录：构建→发布→内部执行（09 细则 §5.3）在 E1 形态栈的完整执行

日期：2026-11-20。执行环境：E2 服务器 192.168.1.2 上的 **E1 形态辅助栈**（compose-e1，18980–18985，dev-hmac，库 **r24_check**@15432，容器名 e1-*）。判定上限=L-链路（细则 §2.1）。

> **数据来源=造数/数据修正**：本卡 monitor.evaluation_report（er_prb1seed4aa92339 / er_prb1seed0f719ea9）与配套 dataset（pr-b1-seed-v1/v2）由 `/home/agent-platform/scripts/seed-eval-report-prb1.py` 直插数据库产生，**不是真实评测会话的产物**，仅用于解锁发布预检查询数据条件；模型供应商/条目/密钥引用的租户与 tokenizer 修正、compose redis env 补齐等数据面修正在 §6 逐项登记。服务**源码零改动**。

## ① 角色

测试执行子智能体（PR-B1）。目标：09 细则 §5.3 构建→发布→内部执行全链在 E1 栈打穿，硬断言三条——FT-EVL-03（评测报告解锁 precheck→commit）、FT-MDL-04（真实 DeepSeek 出话到 run SUCCEEDED）、FT-MDL-05（计费对账）。

## ② 准备

- E1 栈（前序就绪）：六服务 dev-hmac（`AGENT_SECURITY_MODE=dev-hmac`，`AGENT_DEV_AUTH=true`），compose 由 `--env-file /home/agent-platform/.env.test` 起（docker inspect label 实证）；密钥 JWT secret 三方一致（e1-monitor=e1-app=secrets/.env.generated，比对 MATCH）。
- 库 r24_check：demo 租户 satellite-planning-assistant（CHAT，draft_rev=2，config.promptSource=DIRECT+内联 systemPrompt，modelBinding.modelEntryId=1）；DeepSeek provider id=1（api.deepseek.com，密钥 vault:611f11bb）+model.entry id=1（deepseek-flash，ctx 1048576/max 393216）。
- **internal 面鉴权口径（读码确认）**：monitor 的 `ServiceTrustChainConfiguration` 是 `@ConditionalOnProperty(agent.security.mode=iam)`——dev-hmac 下**不装配** ServiceAuthorization 拦截器，走 SecurityConfiguration 旧链：`/internal/**` 需要已认证 JWT（aud=agent-platform-internal、token_use=service）且 roles 含 SERVICE→ROLE_SERVICE（SecurityConfiguration.java:128-144,264-270）。app→monitor 出站在 dev-hmac 下用 `ServiceTokens.issue(Actor.current())` 自签（AppService.remote:735-748）。据此测试直连用同构 HS256 服务票（mint-prb1-tokens.py）。
- 票据：M5-3 harness mint 同构（iss=agent-platform-v12/aud=agent-platform/token_use=user/tenant_id=demo/roles=[ADMIN]/attributes={"region":"E2","dataLabels":["E2_REAL"]}，900s）。attributes.dataLabels 是出域标签判定必需（ModelPolicy.checkLabels：数据未分类仅放行 LOCAL 端点，ModelCalls:97/684）——首轮无 attributes 被 SYS-EGRESS-4031 拒（见 §3）。

## ③ 取证（服务器 /home/agent-platform/evidence/PR-B1/，镜像本仓 e1-raw/）

| 文件 | 内容 |
|---|---|
| 00-tenant-fix-before/after.txt | DeepSeek provider/entry 租户修正前后 |
| 01-seed-eval-report-result.json | 造数回执：rev3 指纹+16/16 消费侧校验全 true+environment=REAL+gate=PASS |
| 02-accepted-http.json | `GET /internal/evaluations/accepted` 直连 e1-monitor **HTTP 200**，accepted=true/matchedBy=SHA256/promptTemplateRef=null |
| 03-precheck.json / 03-precheck-r3.json | 两次预检（rev2 / rev3）passed=true，10 项检查全 PASS |
| 04-commit.json / 04-commit-r2.json / 04-commit-r3.json | commit→versionNo=2 / 同候选复用 versionNo=2 / **rev3→versionNo=3** |
| 05-snapshot-modelrefs-v3.txt（=snapshot.json） | v3 快照：modelRefsCount=1、modelEntryId=1（deepseek-flash）、bindingId=2、modelBinding.modelEntryId=1、promptSource=DIRECT、publicationEvidence REAL |
| 06/07/08/09/11 | 会话 s_eeb3188a…、轮次受理、轮询轨迹、run 终态 SUCCEEDED、会话视图 |
| 10-deepseek-direct-probe.json | 直调 DeepSeek 最小 chat（usage 35/16/51，模型名 deepseek-flash 被真实 API 接受） |
| 12-call-log.json（=call-log.txt） | model.call_log 全行：FORMAL、prompt 206/completion 380/total 586、estimated 181、exact=true/admissionMethod=MODEL_TOKENIZER、duration 3679ms |
| 13-billing-reconciliation.json | 同请求直调 DeepSeek usage 对账（prompt_tokens 206==206 精确一致） |
| 14-runs-trail.txt | 全部 9 次 run 状态（8 FAILED 诊断链 + 1 SUCCEEDED） |
| scripts/（本仓镜像） | seed-eval-report-prb1.py / mint-prb1-tokens.py / prb1-chat-run.sh / prb1-vault.js / prb1-replay-usage.py（服务器同名同内容） |

关键指纹/标识：draftRev=3 candidateSha256=aad661e2d81c84840b0692f3c5568901844f48650caa5516c88528436a09089f，semanticDigest=4ac1d25966f7c2963664b4cc3e1431064489336e43bda2b6cc632e8b31e8ff4c；报告 er_prb1seed0f719ea9；预检 pc_b5998687-8100-40fb-8495-6bb2da427d9a；bindingId=2 sha=e94a30067a78c1f6d939639eae6b502b85de2a66c0babcb375522abedd8c9747；run **r_290fc90bc6a04165bfcd1ff4fb440e97**。

## ④ 断言清单

| # | 断言 | 结果 | 证据 |
|---|---|---|---|
| A1 | 造数报告通过 monitor 读回复算+消费侧 16 项校验全 true | PASS | 01（validPublicationEvidence 逐项 true，environment=REAL，evidenceSha256=faa1f93a…） |
| A2 | E1 dev-hmac 下 monitor internal HTTP 直连可达（自签服务票） | PASS | 02（HTTP 200，accepted=true，matchedBy=SHA256） |
| A3 | precheck passed=true（评测门 PASS+评测环境 PASS，target=PRODUCTION） | PASS | 03-precheck-r3.json（10 项检查：结构/参数/类型/manifest/引用有效性/守卫声明/评测门禁/评测环境全 PASS） |
| A4 | commit → versionNo=3 | PASS | 04-commit-r3.json |
| A5 | 快照 v3：dependencyVersions.modelRefs 非空 + modelBinding.modelEntryId=1（R-25 硬断言） | PASS | 05-snapshot-modelrefs-v3.txt（count=1，entry id=1=deepseek-flash，tokenizerUrl 已冻结） |
| A6 | CHAT 建会话+轮次 → run 终态 SUCCEEDED（stop_reason=FINAL_ANSWER，error={}） | PASS | 09-run-final.json / run-final.txt / 14-runs-trail.txt |
| A7 | PROMPT_TEMPLATE_SNAPSHOT_MISMATCH=0 且 MODEL_NOT_CONFIGURED=0（run_event/模型链无该错误） | PASS | run_event 逐条 payload 扫描均 0 命中；model_invocation 唯一行 status=SUCCEEDED |
| A8 | 出话为真实 DeepSeek（非桩）：按 DIRECT systemPrompt 口径成文（结论先行/不构成审批/副作用须人工确认） | PASS | run output 全文 + actualModel{modelName=deepseek-flash, endpoint=127.0.0.1:18990(→api.deepseek.com)} |
| A9 | FT-MDL-05 计费对账：call_log 三元组（206/380/586）与 DeepSeek 真实 usage 对照 | PASS | 13：**prompt_tokens 206==206 精确一致**；completion 380 vs 297 为采样非确定性（replay reasoning_tokens=152）；metering exact=true/admissionMethod=MODEL_TOKENIZER（estimated 181 为准入保守估计，final=provider 实报） |

**FT-EVL-03：PASS**（A1+A2+A3+A4+A5——评测报告造数解锁评测门/环境门，precheck 真实 200/PASS，commit 发 versionNo=3，快照模型引用齐全）。
**FT-MDL-04：PASS**（A6+A7+A8——真实 DeepSeek 出话、run SUCCEEDED、两个错误码计数 0）。
**FT-MDL-05：PASS**（A9——计费对账，prompt 维度逐 token 一致，completion 差异=采样波动）。

## ⑤ 判定

三条硬断言 **FT-EVL-03 / FT-MDL-04 / FT-MDL-05 全部 PASS**。链路形态：网关用户面（demo ADMIN 票）→ app-management precheck（HTTP 调 monitor accepted，服务票自签）→ commit 固化快照（modelRefs 冻结绑定）→ engine CHAT 会话/轮次 → model-gateway（tokenize 准入计量→Redis 配额→代理转发）→ **真实 DeepSeek API 出话** → call_log 计费落账 → run SUCCEEDED+FINAL_ANSWER。

**未达到的部分（如实登记）**：
1. 评测报告为造数（FT-EVL-03 的"评测门放行"建立在造数报告上，非真实评测会话产物；环境字段 REAL 为造数声明，PRODUCTION 目标对齐）。真实评测链（POST /admin/evaluations）不在本卡范围（E1 栈无 monitor 评测入口前置——与 R-30 判定一致）。
2. 达到 SUCCEEDED 前有 **7 次 FAILED 尝试**（诊断链完整留痕 14-runs-trail.txt + 过程实录）：SYS-EGRESS-4031（票据缺 dataLabels）→ tokenizer 未配置（冻结绑定无 tokenizerUrl）→ secret_ref 形状错（UUID too large）→ vault 文件权限（AccessDenied）→ vault GCM tag（AEADBadTag）→ Redis env 名不匹配（配额 503）。每次修复均数据/部署面修正（§6），无源码改动。
3. versionNo=2 已被首次 commit 占用（其冻结绑定早于 tokenizer 修复）；同候选再 commit 按"内容身份复用"规则不发新版本（AppPublicationService:940 注释），故经 metadata 修订 bump draftRev=3 重走全链产生 versionNo=3。**当前发布态=versionNo 3**。

## ⑥ 问题清单（数据面修正与部署面事实，全部标注数据来源=造数/数据修正）

1. **模型注册租户错位（前序遗留）**：DeepSeek provider id=1/entry id=1 注册在 local-v12 租户，而应用在 demo——ModelStore.entry 按 actor.tenantId 过滤，模型链必 404。修正：两行 tenant_id→demo（00-tenant-fix-*）。
2. **vault 密钥记录租户错位（前序遗留）**：vault:611f11bb payload tenantId=local-v12——demo actor 解密必 SECRET_SCOPE_DENIED。修正：node16 按 SecretVault 同构（AES-256-GCM，SHA-256(AGENT_MASTER_KEY)，nonce||ciphertext||tag）重加密 tenantId=demo 新记录 vault:4e776858（prb1-vault.js；密钥值全程未打印/未出服务器）。
3. **专用 tokenizer 缺失**：平台对未知模型名（deepseek-flash 非 jtokkit 内置）强制 tokenizerUrl 计量（ModelCalls:1252）；且 tokenizer 须与供应商 baseUrl **同源同 authority**（ModelCalls:1203-1206）。处置：起本仓 scripts/model-proxy.mjs（容器 prb1-model-proxy，node:22-bookworm-slim，host 网络 127.0.0.1:18990，UPSTREAM=https://api.deepseek.com，密钥经 /run/prb1-ds.key 挂载读取）——provider base_url→http://127.0.0.1:18990、entry tokenizer_url→http://127.0.0.1:18990/tokenize；真实出话经该代理转发至 DeepSeek（数据面修正，代理为既有仓库脚本）。
4. **compose-e1 redis env 名不匹配**：engine/model 的 application.yml 读 AGENT_REDIS_HOST/PORT/PASSWORD（缺省 26379），compose-e1 只给了 AGENT_SYSTEM_REDIS_ADDRESS→配额 Redis 连接失败→SYS-MODEL-503"分布式模型配额暂不可用"。修正：compose-e1.yaml 增补三 env（备份 compose-e1.yaml.bak-prb1），`docker compose --env-file .env.test up -d` 重建六容器。
5. **候选模型冻结缓存**：candidate_model_snapshot 键为 (tenant,app,rev,candidate)——rev2 预检冻结的绑定（bindingId=1）无 tokenizerUrl，重预检不会重冻结。处置：删除该行一次（派生缓存，平台语义=重新冻结），rev3 预检产生 bindingId=2（含 tokenizerUrl）。
6. **metadata 修订内容说明**：PUT /admin/apps/{code} 重写了 description/domain/bizCategory 文案（PR-B1 执笔的演示级文案；candidateSha256 因此改变，semanticDigest 相应重算；config.modelBinding/promptSource=DIRECT/systemPrompt **未动**）。draft_rev 2→3。
7. **冻结候选快照省略 config.promptSource**（沿 R-30 判据）：monitor promptTemplateRef 对 present 且非 TEMPLATE 的 promptSource 一律 409 PROMPT_TEMPLATE_SNAPSHOT_MISMATCH（EvaluationService.java:656-661），而 accepted()→report() 每次读都触发——DIRECT 应用的报告读回在现运行构件上结构性 409，造数快照按可消费形状省略该键（candidate_sha256 列仍为真实候选指纹）。**平台 backlog：monitor 侧 DIRECT 对齐**。
8. 出站票 attributes：M5-3 harness mint 用 attributes:{}，本链路必须 dataLabels 与供应商 trust_labels（["E2_REAL"]）匹配（或 LOCAL）——票据形状以 ModelPolicy/ModelCalls 为准登记，与 M5-3 harness 的差异属场景差异。
9. 密钥纪律：DeepSeek key 全程未出现在命令行参数/日志/证据/Git（vault 文件+600 权限 /run/prb1-ds.key；对账脚本从文件读）；.user-token/.service-token 仅存服务器 evidence 目录、未入仓。
10. 本卡只动：r24_check 的 monitor 两行造数、model 四处数据修正、app.candidate_model_snapshot 一行删除、compose-e1.yaml（E1 部署面）+代理容器；**服务源码/主栈（compose-test）/其它租户数据零改动**。仓内提交仅 docs/evidence/2026-11-20-PR-B1/。

## 附：E1 dev-hmac internal 面测试调用方式（结论）

`curl http://127.0.0.1:18985/internal/evaluations/accepted?appCode=…&draftRev=…&candidateSha256=…&semanticDigest=… -H "Authorization: Bearer <HS256服务票>"`，服务票=ServiceTokens.issue 同构：HS256(AGENT_JWT_SECRET)、iss=agent-platform-v12、**aud=agent-platform-internal**、token_use=service、roles 含 SERVICE、tenant_id=demo、exp≤120s（mint-prb1-tokens.py）。iam 模式的 ServiceAuthorization/action-resource 校验链在 dev-hmac 下不装配（@ConditionalOnProperty），无需 action/resource claims。
