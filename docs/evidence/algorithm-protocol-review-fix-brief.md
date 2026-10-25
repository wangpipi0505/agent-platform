# 算法协议独立审查统一返修

只有root确认审查报告完成、skill target归还并移交后才实施。唯一工程G:/207/项目/算法重组工具/代码/agent-platform，遵守AGENTS；不得访问或关联相邻旧实现/配置/存储/进程。用户持续开发及隔离端点验证授权有效，不索要真实环境。

## 输入与本轮范围

先读`review-algorithm-protocol.md`完整报告及原任务brief、`algorithm-readonly-retry-decisions.md`、`algorithm-protocol-report.md`。独立审查基线为原16文件patch674fcc2b768c0ebca9be11980e5a261b2343a818db1842dd979c5950bb228159；作者skill377/common10与定向80为修复前历史，不可代替本轮验证。审查者实际首轮46项3失败、随后失败3项单独绿也不能抹去首轮。

仅修本次完整审查发现：算法结果转存中断、必要的同一尾段取消/资源完成边界，以及三项首轮执行失败的安全诊断/因果与有限回归可靠性。允许必要的skill生产/测试文件、自己的测试入口与新返修证据；其中Java AlgorithmApiFixture可为审查R2新增必要的后台异常捕获/向测试线程显式回传及有限断言，不为措辞限制另造重复协议实现。不改common/engine/其他服务、前端、POM、原迁移/四契约/v1.2、已冻结Node fixture tests/smoke-algorithm-protocol.mjs、暂停的真实进程runner/其测试、root总表或三份外部执行文档。没有必要新增生产功能或框架。

## 已定位问题和验收

1. MinIO8.5.17同步入口对future.get的InterruptedException仅包装RuntimeException，清掉中断标记且不取消原future。当前算法store的close仅cancelAll/evict/shutdown，不能单凭它证明本地调用已退出；AlgorithmAdapter只识别直接InterruptedException也不能覆盖包装路径。按实际固定SDK/OkHttp源码修复，保持同一绝对deadline、原共享store/API/45s正常行为，不关闭共享池/dispatcher。可以改用SDK公开异步入口或其他已验证的窄机制，不能仅Future超时返回后遗留I/O。
2. 用小正文和Latch在真正PUT等待、回读等待时中断。先取得行为红，再验证中断保留、本地SDK future/call/输入响应流按实际机制完成或关闭、调用返回/释放前无原I/O残留、无READY、无迟到上传或后续网络、共享store随后真实PUT/GET可用。特别注意原上传尚可能完成时不能先做对象删除并记DELETED，造成迟到写回；清理状态与外部对象事实如实保存，不承诺外部事务回滚。仅远端fixture sleep handler尚未退出不等于本地仍在I/O，不能拿等待700ms后的观察替代本地完成屏障证据。恢复中断标记、关闭与必要PG清理的顺序必须说明。
3. 三项首轮失败：preparingAndRegisteredExistBeforeSubmittedAndFirstPost、twoAuthorizedRetryWorkersPrepareOnlyOneGeneration、staleObservationCannotAdoptReplacementBindingUid。审查专库记录分别在SENDING/UID=null等早期阶段，duration约141/120/70ms；默认500ms但不能据此断言预算耗尽，UID注入点未达。请先根据审查者实际命令/记录定位；必要时增加受限阶段/原因分类诊断，保留exception类别或HTTP状态等安全信息，不输出原始异常正文、参数、端点凭据、Authorization或secret。原call/attempt/阶段和允许的错误分类可以用于审计，不能泄露外部body或改变错误语义逃避失败。
4. 若确认是非期限用例未到达被测阶段，可用明确闩锁/预热或与正常成功路径相符的有限测试预算保证阶段，不放宽产品deadline、删除保护断言或改大真正期限用例。若是实现问题，修具体因果。保留首轮失败和每轮诊断；后次偶然绿不当作已找到原因。完成原有限关键集、原HTTP/MCP/proof兼容及最终完整skill/common，记录真实计数，不拼凑新七模块数。
5. N1报告时序更正：POST到达时fact callback_state实际仍是PREPARING，binding已SENDING，engine已接收submitted。允许仅更正当前`algorithm-protocol-report.md`中相反描述并纳入本轮新差异；原frozen报告、原patch/manifest保留历史字节，不为迎合错误报告改变生产PREPARING时序。返修记录明确区分三种状态。

测试只用本工程明确专用库/小型loopback端点，不清运行库、不做资源耗尽/慢正则探针、不package/启停root服务、不执行真实JVM纵切。使用Receiving Code Review、Systematic Debugging和关键失败路径红绿。需要扩范围先说明具体理由，未确认的推测不当事实。

## 固定与交付

起步先完整复制本轮开始的源码/测试/报告基线和SHA。原algorithm-protocol-artifacts的baseline/frozen/patch/log与manifest均保留原字节，不能覆盖；新的修复baseline/frozen/完整diff/变更清单/实际apply/前后SHA另存`algorithm-protocol-review-fixes/`。写`algorithm-protocol-review-fixes.md`，逐项说明实因、实际红绿/中断时序/安全诊断、正常deadline与旧入口保护及限制；先不自行宣布APPROVE。最终冻结交还target，由原独立审查者复核后root统一回归、固定制品、真实进程纵切，再恢复G6。不得提交、发布或派子agent。
