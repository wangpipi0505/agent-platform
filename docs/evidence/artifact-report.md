# 受控产物与大结果实现验证

本轮仅操作全新agent-platform内skill-registry和本报告组文件，未读取/复用相邻旧工程。已读AGENTS、implementation-brief、contracts/README、技能/授权冻结契约、snapshot中MinIO复用职责与内存/归属隔离要求。按systematic-debugging定位共同转存边界，再用TDD失败路径测试和verification-before-completion实测回归；复用已有JDBC、MinIO8.5.17与JWT组件，未新增依赖。

## 已实现

V306新增PG权威artifact元数据：opaque标识、tenant/principal/run/call归属、摘要、字节大小、类型、保留期、上传/清理状态、租约、尝试数、错误类别和删除时间。触发器保护归属与物理定位不可变；物理bucket/key只在infra及数据库出现。旧迁移未改。

上传前先提交UPLOADING登记，随后64MiB硬闸流式上传；上传后有界回读原始JSON验Schema，成功转READY后才返回opaque产物引用。SkillExecution在核对引擎claim登记后显式传入权威runId；对账使用持久执行事实runId。执行事实/outbox先行与确认流程保留。公开对象不再含s3 URI或物理路径。

门户与外部API流代理下载，逐次读取已验Actor，并校验tenant+principal、READY/有效期；外部路由另核对run。未知对象、跨归属与过期均统一404。returnSpec只可引用本次运行已有受控artifactId，任何外部原始URL不能假装为可访问自家产物。接口见artifact-api.md。

七天保留、上传中断一小时恢复窗口、每轮最多8个登记产物、90秒PG清理租约。失败清理精确对象与未完成multipart；临时错误保留待清理记录与5分钟重试时间。每次multipart查询仅完整登记key前缀且max-uploads=8，再核对完整key，不扫描整桶或其他租户对象。数据库不可用窗口仍有上传前的UPLOADING记录可恢复。

## 红绿证据

- artifact-reference-red.log：原实现仅按http/s3等协议接受文件引用，未知外部URL应拒绝而实际未抛异常。
- artifact-persistence-red.log：权限/元数据/清理/下载测试先添加，原实现缺少API导致编译红；未将编译红记成业务断言红。
- artifact-multipart-red.log：真实MinIO创建未完成upload，原removeObject清理后upload仍存在，预期NoSuchUpload而实际未抛异常（1失败、0错误），补清理后通过。
- artifact-http-green.log：真实PG+MinIO七项产物测试通过，包括下载HTTP映射、身份伪造拒绝、传输中断、未完成upload清理。
- artifact-green.log：最终完整技能服务测试；最终统计以此文件为准。原有确认、事实、对账、readonly重试、版本治理及outbox测试均保留。

最终2026-10-20 03:23:08回归：42项测试通过，0失败、0错误、0跳过（SkillExecution20、SkillRules5、ArtifactBound3、ArtifactPersistence9、ArtifactReference5）。BUILD SUCCESS。原27项全部保留，本轮新增15项。

实际正向5MiB包含两类：直接转存下载后逐字节SHA-256核对；真实test目录HTTP服务返回5MiB，SkillExecution完成调用、MinIO转存、PG执行事实与受控下载。后者引擎authority/回调仍用Mockito边界，不表述为全平台端到端。权限覆盖tenant、principal、run；错误覆盖64MiB、坏Schema、接收中断、删除凭据故障后重试；批量上限与DB禁止改归属也覆盖。未使用H2或生产内存Map替代PG。

测试库固定127.0.0.1:25432/agent_platform_v12，真实MinIO127.0.0.1:29000；环境配置仅读入进程，不输出凭据。未清理agent_platform_dev_v12。测试产物各自精确清理；早期红测遗留的fixture产物和未完成upload已用artifact-fixture-cleanup.java按固定测试库、fixture归属、固定时间区间和最多64条登记键恢复，日志确认36项处理，不做全桶扫描。

```powershell
# 先仅将本工程.env注入进程；不打印变量值。
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
mvn -B -ntp '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' -pl skill-registry -am test '-Dsurefire.failIfNoSpecifiedTests=false'
```

## 实际源码对照

实际阅读MinIO Java SDK固定8.5.17标签，Apache-2.0：[S3Base.java](https://github.com/minio/minio-java/blob/8.5.17/api/src/main/java/io/minio/S3Base.java)、[MinioClient.java](https://github.com/minio/minio-java/blob/8.5.17/api/src/main/java/io/minio/MinioClient.java)、[ListMultipartUploadsResult.java](https://github.com/minio/minio-java/blob/8.5.17/api/src/main/java/io/minio/messages/ListMultipartUploadsResult.java)、[Upload.java](https://github.com/minio/minio-java/blob/8.5.17/api/src/main/java/io/minio/messages/Upload.java)。

对照S3Base.putObjectAsync/PartReader分片读取、putMultipartObjectAsync在异常catch里abort、getObject的流资源关闭、listMultipartUploadsAsync精确prefix/max-uploads查询与Upload中StorageClass必填XML映射。本次实测MinIO列表StorageClass为空，SDK列表反序列化报ValueRequiredException；因此MultipartApi仅复用SDK签名executeAsync发送精确有界S3列表，JDK禁DOCTYPE/外实体的XML解析器只提取Key/UploadId，再调用原SDK abort。未替换SDK上传或自行实现S3签名，未复制上游业务实现。注释标出正常异常与进程崩溃的不同恢复窗口。

## 验收边界

真实IAM、模型提供方、真实MCP/K8s和并发堆容量未由本轮验收。MCP SDK传输前缓冲的逐字节硬闸、真实K8s算法输出协议仍沿用skill-report的待验项。本轮只关闭该报告中MinIO正向、metadata/下载授权和故障清理缺口。Schema回读树和转换有明确byte/token上限，但上限不等于高并发容量承诺；网关/前端集成及正式部署由对应工作包验收。取消传播、分布式配额、通用异步回调等原有未完成技能功能不因本轮产物实现而计完成。
