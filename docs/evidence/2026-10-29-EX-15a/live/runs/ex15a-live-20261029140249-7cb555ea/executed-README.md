# EX-15a 正式 JVM 重启验证：静态候选

状态：**PREPARED / NOT EXECUTED**。本目录仅有采集器源文件，没有正式重启通过结论。必须先取得网关 64 MiB 上传路径定向红绿及独立复核，再由主线程占用唯一运行窗口。正在进行的 skill 全模块回归不得与本采集器重叠。

本候选新增三文件：`run-live.ps1`（Windows PowerShell 5.1 / UTF-8 BOM）、`ArtifactLiveProbe.java`、本说明。没有新增产品代码、迁移、清理策略、`.gitignore`、测试断言或通用采集框架。

## 验证边界与真实实现

- 正式入口为 `http://127.0.0.1:18080`。`DevAuthentication.token` 返回 `ApiResponse.data.accessToken`，服务固定签发 `local-v12 / developer-v12`；探针仅在内存使用 token，不保存 token 响应或 Authorization。
- 合法 64 MiB 上传前，先以同一受控入口发送两个单字节 multipart：无 Bearer、只改变 JWT 签名首字符的坏 Bearer；两者必须分别为401且 PG artifact 仍为0行。合法上传同时带伪造 `X-Tenant-Id / X-Principal-Id`，最终 PG 仍必须属于服务已验签的 `local-v12 / developer-v12`。401若不成立记真实红，不改为403或允许其它错误。
- `ArtifactController.upload` 返回 **raw Map**，直接验证 `artifactId / downloadUrl / bytes / sha256 / contentType`；不能按 `data` 解包。下载经过网关与 skill 的真实 HTTP，逐块累计长度和 SHA-256。网关当前不转发 Content-Length，因此以实际读取到 EOF 的 67,108,864 字节和完整 SHA 验收，不制造不存在的响应头断言。
- `ArtifactStore.upload` 持久化 `skill.artifact`。探针登记精确 `artifact_id / tenant_id / principal_id / run_id / call_id / bucket / object_key / sha256 / size_bytes`，核 `READY`、固定开发身份、`r_upload_<32hex>`、call_id=1 与实际生成的受控对象键。物理 bucket/key 只在私有目录，公开摘要只含 artifactId 的 SHA。
- 本次验证是真正 **旧 skill JVM 退出 → 新 PID + 新 CreationDate → 同 artifactId 下载**，gateway 始终为同一个进程。前次 fresh ArtifactStore 成功只证明组件重建，本次才能补上正式 JVM 重启证据。
- 精确行核验和唯一行计数全部通过后，只 UPDATE 本次 artifact 的 `expires_at / cleanup_after`。保持正式 `cleanupPulse` 默认 60 秒节奏，等待最多 150 秒到 `DELETED`，核 `deleted_at / cleanup_attempts`，然后对原 bucket/key 执行 MinIO stat，严格要求 `ExecutionException → CompletionException → ErrorResponseException / NoSuchKey`。这是同一 MinIO 8.5.17 已在 EX15a v3 回归验证的链；任一其它错误（含权限、连接失败、超时）均失败。**不调用 cleanTenant，不直接删除对象，不批量修改行，不清队列，不删除数据库。**

## 运行前准入

1. 主线程先审阅本三文件，冻结 SHA；网关修复及对应定向绿已完成，精确提供该绿证据文件 SHA 和当前 `GatewayController.java` SHA。采集器读取真实 summary，严格核 `phase=green / nativeExit=0 / tests=passed=11 / failures=errors=skipped=0`，以及 `identityComplete / sourceStable / contextStable / phaseExpectationMet=true`；再核 methods.xml SHA、native-exit 和前后两份源码记录中的 GatewayController SHA 都匹配本轮。已取得的定向证据为 `../gateway/gateway-green-20261029-ex15a-01/summary.json`，该轮 GatewayController SHA 为 `75ef9a91d893c20d727e1251123901488719fb81c1e38621ee0171ec51f52837`。`-ExecuteApproved` 仅在复核完成、唯一窗口空闲时使用；定向绿不能替代待完成的模块回归或正式 live 断言。
2. 两个服务端口 18080 / 18083 必须无监听。禁止复用、接管或停止旧服务。打包完成后再次检查，避免构建期间竞争。
3. 只认本 AP PostgreSQL 25432 的**实际**监听 PID，核其 exe=`V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe`、命令行 data dir、数据库 `data_directory=V:/.local/pgdata`、`current_user=agent_v12`。不绑定历史 PID。正式启动器会调用 start-database，故先验证该脚本原有两个开发库已经存在且 owner=agent_v12，避免它在本轮隐式创建额外库。
4. 唯一新数据库名由唯一 RunId 推导：`agent_platform_ex15a_live_<14位时间>_<8hex>`。先核不存在，再以 template0 / UTF8 / owneragent_v12 创建，并核零业务表。该库 URL 同时交给两服务各自的 `ServiceEnvironmentFiles`；**skill 的实际 JDBC/Flyway 依赖正常初始化 skill schema**，采集真实 `skill.flyway_schema_history` 并要求无失败历史。gateway 与 admin-common 没有 JDBC/Flyway 依赖，gateway 也没有迁移资源；其 YAML 中出现 datasource/flyway 不证明会创建 schema。仅用 `to_regclass` 记录 gateway history 是否存在，不要求、创建或查询假定存在的 gateway history。skill 默认 scheduler 因此只访问本次新库。
5. 原 `.env` 只读；拒绝父进程或 `.env` 内会压过服务文件的 datasource / Flyway / config / profiles / application JSON / JVM 参数等高优先级覆盖。MinIO 只认本 AP loopback29000 与现 `.env` 配置的 bucket，不创建或遍历其它 bucket。
6. 前后固定全部 `admin-common / platform-gateway / skill-registry` 的 src 文件、三个 pom 和根 pom、start/stop/start-database、`.env`、JDK21 的 java/javac/release、Maven 启动器、installed admin-common JAR、本三采集器文件，以及上述网关绿 summary/XML/native-exit/前后 source 记录。捕获当前 HEAD 并要求运行后不变，**不硬绑 EX05 以前的 HEAD**。所有输入变化均使整体失败。

## 执行方式

在 AP 仓库内使用 Windows PowerShell 5.1；由主线程填入**已实测并审阅的**三个参数，不使用占位符直接运行：

```powershell
& .\docs\evidence\2026-10-29-EX-15a\live\run-live.ps1 `
  -RunId ('ex15a-live-' + (Get-Date -Format yyyyMMddHHmmss) + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)) `
  -GatewayGreenEvidence '<本卡网关绿证据绝对文件路径>' `
  -GatewayGreenSha256 '<该证据 SHA256>' `
  -GatewaySourceSha256 '<本轮 GatewayController.java SHA256>' `
  -ExecuteApproved
```

采集器依序执行：

1. 创建唯一证据目录及本仓忽略的 `.local/ex15a-live/<RunId>`；私有 ACL 仅当前 Windows 用户和 SYSTEM。固定源文件与环境输入。
2. 正式 Maven `-B -ntp -pl platform-gateway,skill-registry -am package -DskipTests -Dmaven.repo.local=<本仓>/.tools/m2`，1 GiB Maven heap、900 秒上限。**无 clean / install / engine-runtime**，不修改 installed admin-common JAR。构建失败即停止后续步骤；这是沿用现 target 的本地正式包验证，不冒称全 clean 构建。
3. 固定三个 reactor JAR、包内所有 `.class` 及嵌套 admin-common JAR SHA；使用本轮 skill JAR 的实际 `BOOT-INF/classes + BOOT-INF/lib/*` 为 Java source-file 探针依赖，不额外安装依赖或借旧产品 JAR。
4. 创建并核唯一空库；服务 env 仅保存本次库 URL、固定服务开关和私有 multipart 临时目录，不写密码/token。调用正式 `start-backend.ps1 -Services @('platform-gateway','skill-registry') -SkipBuild -ServiceEnvironmentFiles ...`，登记 PID/CreationDate/JDK/JAR SHA/监听端口，健康200之后才上传。
5. Java 使用一个 8 KiB 随机块流式生成 own `.local` 下 64 MiB 临时 fixture，`BodyPublishers.ofFile` 流式 multipart 上传（只将边界头尾放内存）；下载以 8 KiB 缓冲完整校验。不得将 64 MiB fixture 或响应体加入证据。
6. 正式 stop 只指定已登记 skill；重核 manifest/PID/CreationDate/JDK/JAR 后停止，等待旧 PID 退出、18083 空闲。正式 start 只重启 skill；核新 PID/CreationDate、原 gateway 仍存活、JAR 没变，再通过18080重新取内存 token 并下载同 id。
7. 精确到期与正式 scheduler / MinIO 缺对象验收。最后只停本轮登记的两个服务，核输入/JAR/HEAD 后置一致；删除且仅删除 own resolved `payload.bin`。新库、数据库业务事实、日志和失败对象全部保留。

## 原始证据与失败处理

正式启动器固定写 `.local/logs/<service>.out/err.log`。采集器只处理 gateway / skill 四个日志：每次覆盖前先复制原始字节到本次私有目录，记录 SHA 与代次，再关闭 ACL 继承、移除全部现有显式访问规则，仅加入当前用户和 SYSTEM；每代服务退出后再归档。私有运行目录采用同一 ACL 规则，避免沿用旧日志中的其它 Allow。不得遍历其它服务日志，不能删除旧日志备份。

`live/runs/<RunId>` 保存输入 before/after、JAR before/after、包内 class SHA、skill 新库迁移历史及 gateway history 的只读存在性观察、三个白名单 safe JSON、PID/代次/退出事实与私有原件指纹。`.local/ex15a-live/<RunId>` 保存完整原始 Maven/启动/服务/探针日志、原始 PG 行/上传 Map、物理 bucket/key、进程登记、解包依赖。密码和 token 不保存到这些 metadata；发生异常只在私有 raw 中记录详情，公开仅稳定失败码。

失败的 finally 不执行到期 UPDATE 或对象清理；若清理阶段已启动，保留当时已发生的 PG / MinIO 事实。只停止已登记且重核身份的本次进程。正式 start 若在写 services.json 之前部分失败，只根据**本次正式启动输出的精确 PID**及 CreationDate/JDK/目标 JAR 登记后停止该 PID，不改服务清单、不搜索并接管同名进程。Maven 超时仅处理从本轮 cmd 父 PID 采到且命令/CreationDate/JDK 重新匹配的 Maven Java 子进程与 cmd，不杀未知子进程。

原件不自动 stage、不自动脱敏或删除。主线程必须逐个审核拟入库安全证据及私有原件指纹；未经审阅不得 `git add -A`。失败不得称 JVM 重启验收完成。三阶段任何一项失败、源码/JAR/HEAD 漂移、身份不一致、保留旧进程或缺失精确 NoSuchKey 都使整体 `complete=false`。

本候选静态复核范围：PowerShell 5.1 AST、路径/参数/真实响应格式、精确清理谓词和进程归属；**尚未 javac/Java/Maven/PG/HTTP 执行，也未验证实际服务启动成功**。静态通过不等于 live 绿。
