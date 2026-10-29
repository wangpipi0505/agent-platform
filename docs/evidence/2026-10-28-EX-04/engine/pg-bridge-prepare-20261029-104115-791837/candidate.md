# EX-04 实际PG投影到引擎的固定JSON衔接

ROOT实际green-skill-20261029-103602-527已33P/native0/collector0/noignore/无skip并交还实际窗后，仅更新获准两个Test。真实PG生成两字段JSON为137 UTF-8字节，不含换行；其原件及XML来源/完整SHA见provenance.json。

两Test的PERSISTED_PROJECTION拼接字符串逐字节等于该实际JSON，均新增默认运行的Digest断言绑定来源SHA。ToolOutputPolicy直接消费这两个PG字段组成的contract；RunToolProfile在原完整SkillStub上仅加入这两个实际字段，再调用真实prepareSnapshot并用其冻结tool交实际策略。不新增resource、外部路径/环境依赖、跳过或业务JAR，不声称真实跨服务平台运行/J4。

原红规范样例skip/take/37/7已保留在before快照和原RED运行；现在正例为真实PG的startAt/takeRows/6/2，对应已存断言仅更新期望字面值，不删除或条件化义务。分页名/模式/精确键、两上限、空observed、无response/nextOffset、原输出不变和阈值同节点仍保留；新JSON来源摘要断言更严格。负例仍是明确的单项非法声明向量，21参数身份不变。

原22个测试方法全文反撤新import/新块后精确还原最初原字节；默认仍47报告（ToolOutputPolicy34、RunToolProfile13）。PG-only-delta.diff说明仅本次固定数据与来源断言增量，full-test-candidate.diff保留整卡两个Test变化。生产5d2596ba未改，git diff --check通过；本代理未跑Java/Maven/PG/HTTP、未读.env、未操作index/commit。实际默认47绿色仍待ROOT独审和唯一实际窗口，不把静态检查当运行通过。
