# 只读前置事实与采集分类修正

2026-10-29 00:56:19完成一次只读PG前置，先保存安全事实再进入测试阶段。root已明确冻结AP HEAD `28aa99ea12cb96bbbf781859c017acebd30647a3`，独占实际窗口；EX09暂存与本诊断无关，不要求index/工作区为空。

- 当前127.0.0.1:25432仅本仓PID15204，实际data_directory规范化为`V:/.local/pgdata`，role=agent_v12。
- 既有`agent_platform_skill_algorithm_test`库owner、skill schema owner、`skill.flyway_schema_history` relation owner均agent_v12；实际catalog schema/name/owner精确匹配。regclass显示名本次恰为`skill.flyway_schema_history`，原显示字符串谓词也通过；原路径规范化的反斜线字面量长度为1，不将其误写为已证转义错误。
- history共17行。rank0的实际字段为version=NULL、type=SCHEMA、script=`"skill"`（包含两端双引号）、checksum=NULL、installed_by=agent_v12、success=true。该行是本仓AlgorithmTestDatabase默认skill schema的Flyway内部建立记录，不是V SQL迁移。
- 其余16行均为SQL、version300至315，每条script精确对应本仓V300～V315文件、installed_by=agent_v12、success=true；实际checksum安全值保存在pg-history.json。

原collector把**所有**history行script都要求出现在V*.sql，因此对上述合法SCHEMA行会返回false。本轮按type做精确分类：SCHEMA只允许最多一条rank0/versionNULL/scriptQuotedSkill/installedByAgentV12/successTrue；SQL仍要求version存在、逐条对应本仓V文件、agent_v12及成功；未知type、未知script或任何失败/owner不符都拒绝。本次badRows=0，SQL16/SCHEMA1。不创建库，不reset/drop/truncate，不补标、不放松既有实例/owner/schema归属门。

**原轮无阶段证据，同采集谓词实证会拒绝此合法记录。** 当前只能证实这个确定的采集分类错误，不能把它反写为上次历史停止的唯一已证根因。旧004058原件保持。

新preflight.ps1固定phase/reason枚举及InvocationInfo.ScriptLineNumber，无异常消息、密钥或URL输出。语句均readonly，连接5秒与statement_timeout5秒仅用于此只读前置，独立进程finally恢复环境。实际准入字段见listener/pg-instance/pg-database/pg-schema/pg-history/pg-history-predicates/admission.json。

后续run-once.ps1只执行已批准原类4项一次，固定读取本次admission.json SHA256 `9C724E9F2F134A2EEAAC2E33572AEC4BAEB5FB6E6998BD4094A0B2B5E5EEE900`，同一独占窗口内重核HEAD、D64测试/9DEB ArtifactStore、.env及仍是PID15204/原实例，要求准入年龄不超过15分钟；不重复PG查询。该分段不绕开或替代刚完成的真实既有库/owner/Flyway核验。主测试仍由原AlgorithmTestDatabase(..., null)运行，不调用reset分支。

本轮仅改证据采集器，源码D64/9DEB、200ms/2s、全部断言不变。原Full新增FAIL不因准备或本次前置通过而关闭。
