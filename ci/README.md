# 独立工程验证入口

本工程不接入任何已有项目CI。构建系统可直接运行以下固定入口，无需修改业务代码或原契约：

```powershell
./scripts/bootstrap.ps1 -WithObjectStore -WithCache
./scripts/test.ps1 -Frontend
```

`verify-contracts.mjs`校验原v1.2快照和四份契约哈希、跨业务服务jar/import纪律。后端测试必须启用真实专用PG/Redis/MinIO，环境缺失或失败不得统计为通过；各报告分开记录“测试未运行/跳过”。前端执行类型检查、测试和三端构建。依赖审计独立执行web中的`npm audit`，不能把不可达审计服务当0漏洞。

Linux CI需显式准备同版本Java21/Node及专用依赖端点：PG25432、Redis26379、MinIO29000，创建agent_platform_v12、agent_platform_engine_test和agent_platform_model_test测试库，独立密钥通过环境注入；设置ENGINE_PG_TEST=true。Maven命令`mvn -B -ntp test -Dmaven.repo.local=.tools/m2`，前端`npm ci && npm test && npm run build`。Windows专用NIO属性只在Windows入口设置，不复制V盘路径到Linux。

原始测试报告包含运行材料，发布前检查敏感信息；交付长期证据使用docs/evidence下的已检查摘要。当前尚未配置远程仓库或触发云端CI，不把本地通过记作云端流水线已执行。
