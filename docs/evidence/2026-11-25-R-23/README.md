# R-23 FT-INT 负向联验

2026-11-25，使用本地真实 gateway、app-management 和 authority-service，通过随机隔离凭据验证 FT-INT-02、03、04 受理口径。短票经 authority 的 JWKS 验签。E2 公开 Nginx 入口另做无凭据拨测。

| 场景 | 实测结果 |
| --- | --- |
| FT-INT-02 白名单外任务 cancel 与 resume | 两次均为 403 `APP_BINDING_DENIED`，网关记录 `INTEGRATION_PATH_FORBIDDEN` 审计 |
| FT-INT-03 未登记 Origin | 403 |
| FT-INT-03 错密钥 | 401 `INVALID_CREDENTIAL` |
| FT-INT-04 已验签集成票达到 exp 后读取 | 401 `INTEGRATION_TICKET_EXPIRED` |
| FT-INT-04 未过期集成票对应凭据被撤销后立即读取 | 403 |
| E2 无 ApiKey 请求 `POST /embed/ticket` | 401 `API_KEY_REQUIRED` |

审计记录包含凭据编号、方法、路径摘要和 traceId。使用测试密钥原值在本地网关日志中检索，结果为空。回归脚本位于本工作区 `.local/r40-force-audit.ps1`、`.local/r40-expiry.ps1`，运行前先启动该分支本地七服务与隔离 PostgreSQL。

FT-INT-01 的真实外部宿主受理、详情、事件、结果及下载摘要待联验。当前卡记录平台侧联验，外部宿主的有效接入凭据和 E2 环境授权仍需实际衔接。

## FT-INT-01 独立宿主联验

使用 `space-ground-visible` 真实 Spring Boot 进程监听回环 18899，与网关 18880 分开运行。宿主使用 Java 8 和独立 H2 演示库。平台使用真实 IAM、已发布应用及 PostgreSQL。模型是本地 OpenAI 兼容回环端。宿主的测试主体和接入档案单独置备。真实 user-mgmt 两实例可达，本次宿主使用该独立声明主体。

宿主草稿首次已提交到 SUCCEEDED，响应未保留对象结果与事件。修复后，任务详情轮询完成即读取事件与结果，保留 JSON 对象和产物数组。相同请求号可重试，按出票响应的真实有效期刷新短票。修复位于宿主本地接入草稿。

| 验证 | 结果 |
| --- | --- |
| 短票出票与到期前轮换 | 两次签发均返回 59 秒有效期，换票后同一任务可读 |
| 宿主受理、详情、事件、结果 | 宿主 200，任务与结果均 SUCCEEDED，读回 5 条事件及原样 JSON 结果 |
| 相同请求重放，异参同号 | 200 且任务号一致，409 REQUEST_REPLAY |
| FT-INT-05 直连凭据自报租户 | 403 INTEGRATION_CLIENT_IDENTITY_REJECTED |
| 下载回执和摘要 | CHAT 运行的产物列表为空，留作正式 PR-B2 联验 |

JDK 8 打包通过。联验状态见 `host-http.txt`。启动已配置短票的宿主后，可从任意能够访问宿主的机器设置 `R23_HOST_URL`、`R23_EXPECTED_RESULT_TEXT` 和可选的 `R23_TASK_GOAL`，用 Node.js 执行 `node scripts/e2e/verify-r23-host.mjs` 检查四端点和重放。脚本只输出任务业务号、状态、事件数、产物数和重放结果。

E2 正式联验仍需部署宿主和新版本平台，使用正式接入档案及测试账号，选用实际产出文件的业务应用。运行级二进制下载目前未列入正式 IAM 集成白名单，进入该项验收前须落实受控下载的归属校验与摘要取证。

## 任务结果文件与 IAM 受控下载

契约提交 `f241df0e`。复用已提交任务的规范化 JSON 字节与 PostgreSQL 终态摘要，产出可下载结果文件。下载回执带运行号、产物号、精确下载路径、字节数、文件摘要和所绑定的发布摘要。下载阶段经网关、authority-service 和引擎重新核验应用、租户、业务系统、所有者、数据标签及策略版本。技能服务原有附件仍由其原路径处理。

真实本地 IAM 七服务与隔离 PostgreSQL 经评测、预检和发布生成应用 `r23file7e397d53f3` 版本 1。以同一真实集成票完成任务提交、详情、事件、结果、下载。测试使用回环 OpenAI 兼容模型端和隔离目录夹具。落盘文件 645 字节，以 `Get-FileHash -Algorithm SHA256` 再算，与回执、HTTP ETag 和 PostgreSQL 的 `result_digest` 一致。回执的 `publishedDigest` 与发布 API、PostgreSQL 的 `config_digest` 各自相符。

| 边界 | 实测 |
| --- | --- |
| 同票下载和同凭据直连 ApiKey | HTTP 200，ETag 一致 |
| 无票 | 401 SYS-AUTH-401 |
| 跨主体、跨软件、跨应用、标签不足、替换产物标识 | 403 APP_BINDING_DENIED |
| 非约定查询参数 | 400 TASK_QUERY_INVALID |
| 策略版本递增、重新出票、撤销 | 原票 403，当前票 200，撤销后 403 |
| 独立 space-ground-visible 宿主 | 同一张票完成提交到下载，读回 5 条事件，实际下载 654 字节，核对文件长度、SHA-256 和 ETag。同参重试 200，异参 409 |

JDK 21 的平台定向 69 例全部通过，另复验网关入口及服务目录 14 例。首次回归复用旧测试库遇到 Flyway V236 乱序，改用独立库重新跑完。首次 HTTP 下载因 authority-service 成品包内仍嵌有旧公共模块拒绝，强制生成全套新 jar 后核查授权目录随包更新，重跑通过。宿主 JDK 8 打包通过，源码改动位于宿主接入草稿。

`node scripts/e2e/verify-r23-artifact.mjs` 使用 `R23_GATEWAY_URL`、`R23_API_KEY`、`R23_TICKET_REQUEST_FILE`（出票请求 JSON）、`R23_PUBLISHED_DIGEST` 和新文件路径 `R23_DOWNLOADED_FILE`。可选 `R23_ORIGIN` 和 `R23_TASK_GOAL`。脚本核验发布版本、文件长度、SHA-256、ETag 与内容，校验成功后写出文件。`verify-r23-host.mjs` 加 `R23_HOST_DOWNLOAD=true` 与 `R23_PUBLISHED_DIGEST` 可复跑宿主侧校验。实际输出见 `download-http.txt`。

正式 E2 仍需使用真实宿主身份目录和正式业务输入重放。当前交付的文件类型为运行结果 JSON。
