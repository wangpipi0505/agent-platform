# EX-15b 二进制 LAST_REAL 回放（2026-10-30）

已实现：PROCESS 的历史二进制不再进入 JSON offload 解析；核验来源 owner/tenant/run 后，以当前 run/call/attempt 创建独立对象。原始来源不修改；批次失败只补偿本批新对象。SkillExecution 仅两处已核实执行代参数透传，原 claim/submitted 机制不变，原非 PROCESS JSON 回放保留。

两轮测试，未放宽断言：

- 第一轮 `ProcessBinaryReplayTest`：2 项，1 PASS / 1 FAIL，nativeExit=1；独立 JVM 正常读取通过，LAST_REAL 错进 JSON 解析失败。记录 `.local/ex15b-binary-replay/binary-first-20261030-01/`。
- 第二轮：`mvn -B -ntp -pl skill-registry test '-Dtest=cn.agentplatform.skill.infra.ProcessBinaryReplayTest,cn.agentplatform.skill.application.SkillExecutionTest#lastRealRequiresPersistedOwnUnchangedNonMockSource+lastRealCannotReuseBroaderGuardedArgumentsAfterPermissionNarrows+controlledArtifactCopyRequiresAuthoritativeSourceBindingAndCurrentLease'`；5 PASS、0 FAIL/ERROR/SKIP，nativeExit=0，2 份新 XML，源文件稳定。完整参数和结果 `.local/ex15b-binary-replay/binary-second-20261030-01/`。

使用真实专用 PG、MinIO 和独立 JVM。ROOT 完成一次非作者轻量复核，改动限 ArtifactStore、SkillAdapters、SkillExecution 两调用点、新测试及本记录。回滚对应本地提交；不删除历史产物。

本批不重跑上一输出持久化的 69 项（原 67 PASS / 1 FAIL / 1 ERROR 仍保留），不将新 JVM 读取等同正式服务重启，也不代表最新代取消、跨服务晚到发布和真实算法 worker 的联合验收完成。EX-15b 整卡仍进行中。
