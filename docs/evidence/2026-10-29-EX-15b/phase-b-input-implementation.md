# EX-15b 输入物化实现记录（2026-10-30）

本批实现已完成，完整EX-15b仍进行中。按用户新策略仅执行两轮测试；无全仓复跑、无新增断言豁免。

- 两生产：ProcessAdapter在原执行门/配额后、worker前提供callDir受控物化接点；SkillAdapters复制参数、剥离files控制键并经ArtifactStore真实授权读取。
- 文件名由平台生成，逻辑名只进入信封键；16项/单16MiB/总64MiB按实际读取检查，长度/SHA匹配，流自动关闭；下载准备失败清本callDir，共用原剩余timeout。
- 只窄映射真实ARTIFACT_NOT_FOUND；旧false/缺省/scalar/array路径及所有原测试断言保留。未改POM、迁移或ArtifactStore。

第一轮：input-red-20261030-01，两整类25项=22P3F0E0S，原21全P、新兼容例P。真实三失败为7字节输入读到0且未开/关下载流，及跨主体/到期引用未在worker前拒绝。源/上下文/存储/退出门稳定。原collector因候选身份漏记三个方法的(Path)参数后缀，原gate=false保留；post-analysis只解释本次现有XML，不重跑、不改原结果。

第二轮：input-green-20261030-01，六类66项=66P0F0E0S，native0、6freshXML、四源码前后相同。覆盖原两类、ProcessAdapterTest、ProcessProtocolTest、ProcessFilesTest、ProcessAdapterHardeningTest。轻量非作者代码检查未发现阻断，不新增审阅报告。

原始记录位于本仓忽略目录.local/ex15b-phase-b-input-{red,green}/对应run，不提交raw日志/XML、凭据、包或运行数据。首次准入的中文subst解码/MinIO双回环误判保留，改用Unicode映射及同PID的IPv4/IPv6回环检查，非产品失败。

待后续：输出持久化与run/attempt贯通、二进制回放、正式重启、真实算法worker；单次SDK阻塞返回上限、配额/损坏故障分支及真实schema/guard入口未专项验，不冒称完整验收。当前生产调用仍经原SkillExecution入口，未扩大其白名单。
