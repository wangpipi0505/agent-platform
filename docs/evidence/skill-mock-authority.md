# 调试替身权威与附件复用

2026-10-20，改动只在新工程。先运行新增失败用例，`.local/skill-authority-mock-red.log` 实际44项中2项失败：DEBUG可被body发明Mock、合法LAST_REAL未消费。修复后44项全绿；增加真实5MiB附件归属/过期回归后，`.local/skill-authority-artifact-green.log` 为45项、0失败、0跳过、BUILD SUCCESS。

执行器只消费引擎claim返回的持久 `mock_snapshot`。body.mock 无论是否带已验服务身份都不能创建、替换或取消替身；FORMAL/EVALUATION忽略调试替身。FIXED校验当前合同原始outputSchema；LAST_REAL先独立检查skill事实：同tenant/principal、精确skillVersion、argsDigest、contractDigest、成功非Mock、源run与canonical结果摘要一致。已验证的returnSpec投影不再冒充原始输出重验Schema。

LAST_REAL保留原structured/modelSummary/card/ontology信息，owner与guardTrace重新生成。附件通过原tenant/principal/run授权读取，按有界流重新登记、上传和校验，为当前run生成新opaque artifactId及下载地址。真实MinIO回归验证5MiB内容摘要一致、源run过期清理不损坏新副本、其他主体无法复制。未使用外部业务HTTP作为替身回退。

补充：只读连接测试也统一登记受控DEBUG run，返回runId后等待完成再用controlledCallId核验；只读无需人工确认。这样超大响应也有run归属，不能绕到无归属的直接探活路径。重检额外限定原principal。对应失败用例实际得到“预期r_controlled，实际null”，修复后技能全量46项、0失败、0跳过，日志 `.local/skill-controlled-readonly-green.log`。

46项包含原技能规则/HTTP执行/PG事实与对象存储测试，不等于所有技能册能力验收。CALLBACK、完整取消协议、生产K8s/MCP容量等仍按总台账跟踪。此次未改原契约或相邻工程。
