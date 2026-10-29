# EX-10 真实应用草稿创建（2026-10-30）

已实现：RESOLVED 能力包按环境、route 和实际已注册技能生成真实应用草稿；使用原 AppService 校验、创建、配置及流程保存。远端技能重新核验在本地短事务之前；包、计划与来源关联在同一事务中落库。相同计划返回既有应用，不覆盖后续编辑；不同计划拒绝。来源关系不可由应用配置伪造，复制应用保留来源，删除不隐式重建。V110 为新增迁移。

两轮定向测试，未放宽断言：

- 第一轮：`mvn -B -ntp -pl app-management test -Dtest=CapabilityPackageApplicationsTest`，3 FAIL，缺少入口 404；原始记录 `.local/ex10-package-applications/applications-red-20261030-01/`。
- 第二轮：同模块 `-Dtest=CapabilityPackageApplicationsTest,CapabilityPackageBindingTest,CapabilityPackageImportTest,AppDigestTest,AppRulesTest`，nativeExit=0，5 份新 XML、24 PASS、0 FAIL/ERROR/SKIP，选定源文件稳定。原始命令和 XML 在 `.local/ex10-package-applications/applications-green-20261030-01/`。
- 两轮均使用已有专用 PostgreSQL；完整参数以各目录 command.json 为准，不运行全仓、不安排第三轮。

ROOT 完成一次非作者轻量代码复核，未发现阻断问题。范围为五个生产/迁移文件、一个新增测试、能力包契约和本记录。回滚本地提交即可恢复旧入口，保留已应用迁移与来源事实。

EX-10 整卡仍进行中：网关、目录、运行快照来源与版本一致性、完整多路线及真实联合验收另行推进；当前草稿不代表已评测、已发布或真实业务验收通过。
