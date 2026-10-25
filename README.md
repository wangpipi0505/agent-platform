# 智能体构建平台

以详设 v1.2 为基线的新实现。全部源码、Git、构建缓存、开发数据库、运行进程与密钥在本目录独立维护。唯一输入为设计文档、技术要求和明确的开源参考，不读取或复用相邻工程实现。

## 工程与运行

| 目录 | 职责 | 开发端口 |
| --- | --- | --- |
| admin-common | 验签身份、内部票据、异常、JSON、密钥引用 | — |
| platform-gateway | 统一接入、认证SSE、集成出票和域白名单 | 18080 |
| app-management | 草稿、模板、分类、候选预检、原子发布和集成凭据 | 18081 |
| engine-runtime | Agent/Flow双驱动、trace、租约、调试、确认、事件 | 18082 |
| skill-registry | 六维注册、版本、HTTP/MCP/算法执行与事实 | 18083 |
| model-gateway | Spring AI单次模型调用、计量准入、请求快照 | 18084 |
| monitor | 候选评测、确认/统计/审计投影 | 18085 |
| web | admin / webapp / assistant.js / flow-designer / debug-panel / shared | 15173 / 15174 |

锁定 Java 21、Spring Boot 3.5.0、Spring AI 1.0.0；前端 Vue 3.5、TypeScript、Element Plus、AntV X6。生产业务事实落 PostgreSQL；独立开发 PG 16.15 使用 25432，数据 `.local/pgdata`。默认仅监听本机。

```powershell
# 需Java21、Maven、Node和新工程.tools中的专用PostgreSQL；不使用任何已有项目依赖。
./scripts/bootstrap.ps1 -WithObjectStore -WithCache
./scripts/start-backend.ps1
Set-Location web
npm ci
# 隔离本地开发：新工程.env中的AGENT_DEV_AUTH须显式为true。
$env:VITE_AUTH_MODE = 'dev'
npm run dev
# WebApp可另开终端执行 npm run dev:webapp
```

管理前端默认采用 `portal` 模式，由同源可信宿主注入 `window.agentPlatformPortal`，提供 `getAccessToken(): Promise<string>` 与 `onSessionChanged(listener): () => void`；后端 `/auth/session` 验证会话并投影身份。未提供宿主时页面显示连接提示。隔离本地开发才显式设置上例的 `VITE_AUTH_MODE=dev`，并同时在新工程后端 `.env` 启用 `AGENT_DEV_AUTH=true`。恢复门户入口时移除该前端环境变量或设为 `portal` 后重新启动开发进程；其他值显示配置错误。Vite会把该变量嵌入构建，dev构建仅用于隔离开发，生产使用portal构建并关闭后端开发认证。

上述dev启动命令是配置说明，本轮未执行新的dev环境完整进程启动；浏览器的dev分支验证使用隔离harness，不能替代该启动验证。门户及身份切换的验证范围见 [D4b复审](docs/evidence/review-iam-stage-4-d4b-combined-fixes.md)。

启动脚本仅创建新工程自己的 `.env` 和随机密钥，不改变全局 JDK。中文路径下为本目录建立专用 V: 路径别名；若 V: 已属于其他路径，明确失败，不覆盖。Windows JDK NIO临时路径属性也仅传给本工程进程。

停止服务使用 `scripts/stop-backend.ps1`，脚本复核登记PID与本工程JAR绝对路径后操作。遇到端口占用不接管其他进程。

本地登录入口仅在显式 `AGENT_DEV_AUTH=true` 且loopback主机可用，固定开发身份；生产须关闭并配置专用IAM。外部助手由宿主后端使用一次性显示的 `ApiKey <id>:<secret>` 调用 `POST /embed/ticket`，浏览器只接收不超过300秒的短票据。票据不能访问管理API，实时校验吊销、应用绑定和来源。

`bootstrap`只在.tools下载官方PG/Go工具；MinIO从固定官方源码编译。Redis固定7.2.16，官方源码校验后在全新WSL发行版AgentPlatformV12中编译，VHD仅在.local/wsl，监听26379、独立随机密码；不启动或复用Docker发行版。WSL须事先可用，脚本不启用Windows全局功能或重启系统。对象存储仅本机29000/29001。测试使用 `scripts/test.ps1 -Frontend`，运行前启动专用Redis/MinIO以覆盖真实集成测试。

数据库备份/独立恢复使用 `scripts/backup-database.ps1`、`scripts/restore-database.ps1`，目标已存在时拒绝覆盖。部署候选、独立运维观测栈及未满足的生产验收门禁见 [部署运维附录](deploy/README.md)。生产门户静态路径为 `/console/`，管理API仍为 `/admin/`。

调度配置见 `.env.example`：交互（CHAT/TASK/DEBUG）和批量（EVALUATION）分别使用本实例的有界工作槽，待执行状态保留在PG。新交互满载返回429，停止受理返回503，均附 `Retry-After`；已受理的同requestId仍读取原运行。评测受理可能返回PENDING/RUNNING，消费者需读取并恢复同一报告，不能反复创建报告来查询结果。配置的槽数不等于跨副本资源许可或实测容量；对应代码的独立审核、部署与实测状态见进展记录。

## 实现与验证记录

- [设计/契约快照及哈希](contracts/README.md)、[编码约束](AGENTS.md)
- [设计→代码→测试索引](docs/design-traceability.md)、[开源对照](docs/open-source-references.md)、[执行进展及未完成项](docs/evidence/progress.md)
- [引擎报告](docs/evidence/engine-report.md)、[应用评测报告](docs/evidence/application-report.md)、[前端报告](docs/evidence/frontend-report.md)

模拟模型/技能仅出现在测试目录。集成测试中的受控HTTP端点不等于真实供应商、本体、IAM和K8s验收。页面、API或局部测试完成也不等于34项任务、17项功能和容量指标全部通过；当前缺口和外部条件在进展记录中逐项保留。
