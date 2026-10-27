# IN-01：联调环境责任表（2026-10-27 实测核验）

> 用途：联调环境唯一权威清单。每项有 owner、端口、启动方式、健康检查。
> 本表由智能体侧联调负责人维护；发现漂移直接更新本表。

## 服务清单

| 服务 | 端口 | 启动方式 | 健康检查 | owner | 2026-10-27 状态 |
| --- | --- | --- | --- | --- | --- |
| platform-gateway | 18080 | `scripts/start-backend.ps1 -Service platform-gateway`（读 .env） | `POST /auth/dev-token` | 智能体侧 | ✅ |
| app-management | 18081 | 同上 | TCP 监听 | 智能体侧 | ✅ |
| engine-runtime | 18082 | 同上 | TCP 监听 | 智能体侧 | ✅ |
| skill-registry | 18083 | 同上 | `GET /v3/api-docs` 200 | 智能体侧 | ✅（09:16 重建后运行） |
| model-gateway | 18084 | 同上 | TCP 监听 | 智能体侧 | ✅ |
| monitor | 18085 | 同上 | TCP 监听 | 智能体侧 | 未监听（本轮未用） |
| 模型代理（DeepSeek 本地） | 28400 | 协作者环境 | `GET /` 404 即活 | 模型负责人 | ✅ |
| 空间服务 space-ground-visible | 8080 | 仓库根：`java -jar backend/target/space-target-visible-1.0.0-SNAPSHOT.jar`，需 env `DB_PASSWORD` / `DB_NAME=space_target_visible` / `OREKIT_DATA_PATH=backend/orekit-data` | `GET /api/v1/space/health` → `{"status":"UP"}` | 空间服务 owner | ✅（10-27 复活） |
| 本地算法 visibility-trajectory | 18090 | 仓库根（JDK8）：`java -jar target/alg-toolkit-1.0.0.jar --server.port=18090` | `GET /api/orbit/health` → OK | 本地算法 owner | ✅（含 HTTP 流式修复） |
| user-mgmt 后端 | 18070 | `scripts\start-backend.bat`（user-mgmt-system） | `GET /captchaImage` | 本体侧 | ✅ |
| user-mgmt 前端 | 18071 | `scripts\start-frontend.bat` | `GET /` 200 | 本体侧 | ✅ |
| ontorepo 后端 | 18182 | **顺序敏感**：先 `deploy/Start-Storage.ps1`（MinIO 15184）→ PG 15472 存活 → 再 `deploy/Start-Local.ps1`。就绪判定：`GET /onto/platform-actors` 返回 401（非 404/拒绝连接） | 同左 | 本体侧 | ✅（10-27 就绪，含 OR-S02） |
| ontorepo PostgreSQL | 15472 | **必须以持久方式运行**（如 `postgres.exe -D .runtime/pgsql -p 15472` 常驻任务）；普通工具调用内 `pg_ctl start` 的进程树会随调用结束被清理（10-27 教训：PG"自愈式死亡"三次的根因）。端口错传会静默起在 5432（postgresql.conf 无 port 行时用默认） | psql SELECT 1（user onto，凭据 .runtime/local-env.json） | 本体侧 | ✅ |
| PostgreSQL（agent） | 25432 | `scripts/start-database.ps1`（.tools/postgresql-16.15） | psql SELECT 1（user agent_v12，凭据 .env） | 智能体侧 | ✅ |
| PostgreSQL（onto） | 15472 | ontorepo `.runtime/local-env.json` / postgres-16.15 | psql SELECT 1（user onto） | 本体侧 | ✅ |
| MySQL | 3306 | Windows 服务 MySQL80 | `mysql -uroot -p -e "select 1"` | 机器公共 | ✅ |
| Redis（agent 用） | 6379 | `scripts/start-cache.ps1` | redis-cli PING | 智能体侧 | ✅ |
| MinIO | 29000 | `scripts/start-object-store.ps1` | TCP 监听 | 智能体侧 | ✅ |

## 凭据索引（不在本表存明文）

| 项 | 位置 |
| --- | --- |
| agent 全套（DB/Redis/MinIO/JWT/MASTER） | agent-platform `.env` |
| ontorepo DB/引导账号 | ontorepo `.runtime/local-env.json` |
| MySQL root | 机器惯例（见三路线方案 §2.5） |
| user-mgmt API keys | `user_mgmt.sys_api_key`（SHA-256 哈希），种子明文见其 README |

## 联调前置检查单（每次联调前过一遍）

1. **运行进程构建时效**：`ls -la <service>/target/*.jar` 对比进程启动时间；改过代码必须重启对应服务（10-27 教训：旧进程导致自相矛盾错误）；
2. 六服务端口监听齐全（monitor 可选）；
3. 8080/18090/18070 健康检查通过；
4. PG/Redis/MinIO/MySQL 存活；
5. dev-token 签发成功。
