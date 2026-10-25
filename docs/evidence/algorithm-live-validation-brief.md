# 算法真实进程纵切验收准备

本文件仅为执行需求，尚无真实进程通过结果。实施顺序：算法源码冻结→独立审查及返修关闭→root同轮七模块回归与源码SHA→固定新制品→本验收。不得以当前fixture的syntax通过代替实际engine/skill进程。

## 范围与隔离

- 只在新工程执行，使用固定制品目录中的engine-runtime/skill-registry JAR，记录完整SHA；新建唯一`agent_platform_algorithm_live_<suffix>`专用库，保留追溯，不清空或替换运行库。
- 私有engine/skill各一JVM，端口事前无监听，启动后确认TCP owner为记录PID、命令行包含对应本工程固定JAR，才发HTTP请求。数据库URL、服务间URL、K8s fixture API/命名空间/镜像允许项仅覆盖这些子进程，不修改主开发配置或接管六主服务。
- K8s有限HTTP端点使用`tests/smoke-algorithm-protocol.mjs`的fixture并精确验证本轮假Bearer。假Bearer仅为该测试端点，存本轮私有SecretVault；不输出任何真实凭据或将真实密钥放命令行。fixture不能代表集群、容器或RBAC。
- 优先使用实际草稿注册→`connection-test`→真实engine受控草稿run→内部skill调用→原tool_call claim/submitted/fact路径。engine自行构建冻结草稿快照，无需伪造应用发布/模型调用；未使用的上游指向本轮拒绝端点，计数应为零。

## 有限场景与证据

1. 原Job首次读404→创建一次→Complete→合法Pod controller归属与algorithm容器→完整JSON结果。核对一个非Mock skill事实、完整绑定UID/DONE、原attempt一致、引擎一次工具逻辑提交。重检原controlledCallId不增加业务POST；结果与fixture日志一致。
2. POST端点已收后丢ACK：原事实/运行进入UNKNOWN或WAITING_EXTERNAL；同原call发受限内部对账，只查原Job采纳终态，不创建第二个Job。记录对账前后同call/key/attempt/Job/UID和提交次数，禁止对未知发送做业务重试。
3. 原Job可信Failed：不可重试失败正常交付，保持原UID/终态，重检或对账不创建新Job。它不构成NOT_APPLIED证明。
4. 原Job仍Running的未知结果：对账不增加POST；若本轮用本地取消释放测试run，核对引擎CANCELLED和原外部事实仍UNKNOWN，不能将此解释成Job删除、集群停止或效果撤销。
5. 小体积强制转存正例：按公开SkillCatalog下限使用outputMaxBytes=1024和约1.2KiB JSON，走本工程真实MinIO验证算法专用deadline客户端可正常上传/回读/READY及受控下载SHA一致；不修改生产阈值，仅创建本轮tenant/run归属对象。fixture的8KiB上限保持，不做大体积或容量测试。这样实际进程不只验证全部内联输出，也覆盖新增ArtifactStore入口的真实存储兼容。

各场景使用独立有限fixture/namespace，不把fixture单Job状态误复用于不同call。轮询有绝对上限，收到明确未知/丢ACK不重新提交连接测试。实际矩阵若某场景不走此路径，应保存红/限制，不虚构通过。

## 清理与报告

仅停止本轮创建且再次核验PID/JAR的子进程；逐项清理，单项失败不跳过另一子进程/fixture/最终记录，保留原错误与清理错误。fixture关闭有上限并销毁本轮连接。记录最终端口/进程归属，无新库drop、无旧证据删除。

交付测试runner、原始log/JSON、进程记录、制品SHA、真实只读PG核对、长期摘要。不得将日志计数等同于真实K8s执行、生产IAM签收、容量测试或全部G2验收。之后再由独立只读审查核源/证据/事实。

## 当前代码的执行接口定位

`SkillController`提供实际草稿创建和连接测试；`SkillGovernance.connection`调用`POST /internal/skill-draft-tests`，`RunService.createDraftTest`从精确技能版本及合同摘要构建START→draft_skill→END的FLOW冻结快照，因此这条纵切不需要app/model业务fixture。内网受控创建要求SERVICE+EDITOR，带authRef的管理连接测试还要求ADMIN；票据使用本轮新工程开发隔离模式的签名，不声称生产IAM。

子进程只覆盖`SERVER_PORT`、`AGENT_DB_URL`、本轮engine/skill service URL、K8s配置、私有SecretVault目录以及有限调度worker设置；其他upstream指向本轮明确拒绝端点。新库中engine/skill分别由自身Flyway创建schema，不直接把测试事实SQL写入生产调用路径。可以用PSQL只读核验事实、绑定、事件和实际迁移号。

沿现有`scheduler`真实JVM脚本的PID/JAR/TCP归属保护及已修复的逐项清理思路，避免新增通用进程管理框架；原脚本/原清理helper及其证据保持。新runner只写自己的测试文件、原始输出和报告，不修改生产、POM、迁移、其他smoke、总台账，不读取相邻工程。任何实际执行都要等待root明确给出固定新JAR目录并移交，不能用旧846制品试出假的算法成功。
