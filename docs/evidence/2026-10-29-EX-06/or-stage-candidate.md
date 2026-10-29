# EX-06 Ontology诊断阶段静态候选

2026-10-29，作者docs_reader_check；等待root独立通读/唯一运行窗口。未运行PS候选、Node、API、PG、Maven或服务，未读取真实凭据、签key、改Java/主入口/index。4份PS在Windows PowerShell 5.1.26100.9711 ParseFile均0错误，PS均UTF-8 BOM。源码精确字节/所读OR源码指纹见[候选冻结](or-stage-source/manifest.json)，本文件不是实跑或独审报告。

## 已交付和明确待齐

- `Prepare-Joint.ps1`仅交付显式`-Stage Ontology`诊断链，真实调用现有OR API；成功也只记受限`partial.json`、`stage=ONTOLOGY_READY`、`complete=false`，**不写manifest.json**。默认`-Stage All`在任何资源写前以`JOINT_ALL_STAGE_NOT_DELIVERED`/exit2拒绝，不能静默退成Ontology成功。
- OR诊断链规划为本会话A空间的两个READY release、B隔离空间的一个READY release、实际端点；readonly/binder/consumer和独立临时三组凭据（六个不同principal），两个独立MEMBER消费用户、各自A空间/两端点授予。它不弃用release、不撤销旧key、不修改旧空间/用户、不代替J7破坏性撤权。
- main app/engine/skill的受限env和六key私密env由共享原子写入器保存，父目录ACL仅当前用户/SYSTEM/Administrators；公开journal/输出仅ID/ref/阶段，安全元数据检查拒绝key/token/password或原始key/JWT形状。一次签发响应只在内存取所需元数据，key值只入私密env，不落证据/manifest/日志。
- `-Resume`只恢复本session独立journal。确认完成的动作复用；pending的未知写结果/一次key保存失败必须先人工核实，不能自动重试、重签、撤旧或猜成功。显式最终`-ReuseManifestPath`必须静态19字段同context、已有OR_READY journal且live资源/ref仍可用；只检查OR层，仍complete=false，不声明AP已验证。
- AP三应用/正式导入及评测发布门禁、All链、Invoke-JointAcceptance和Invoke-S1Acceptance尚未交付；不会选择第一个模型或伪造发布。ModelMode TestFixture/Real及Real显式ModelEntryId（别名RealModelEntryId）已传入准备计划/上下文，Real模型未在OR诊断层冒称已验证。

## 先跑非业务协议/基础层

原32准备断言驱动字节59A8AA不变；上轮实际Common4DD33262的19协议＋32准备及四源原件已归档[protocol-20261029-1401](protocol-20261029-1401/README.md)。本次增加prepare非READY拒绝、私密env读取/原子替换/路径逃逸拒绝，原19向量不删；新版需要root重新执行协议与原32，而不是沿用旧绿。新协议仅loopback，不模拟OR发布或数据库；若解析/缺函数/进程准备失败，记录准备失败，不当业务实红。

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File '<AP>/docs/evidence/2026-10-29-EX-06/Test-JointProtocol.ps1' -EvidenceDirectory '<AP>/.local/acceptance/ex06-protocol-<新RunId>'
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File '<AP>/docs/evidence/2026-10-29-EX-06/Test-JointPreparation.ps1' -Case All -EvidenceDirectory '<AP>/.local/acceptance/ex06-plan-<新RunId>'
```

## 真实写操作与资源清单（未执行）

AP只调用现有`POST /auth/dev-token`，从真实响应核local-v12/developer-v12后才允许OR业务写；不接受自报tenant/roles。OR仅用配置管理员的当前会话，经`POST /onto/auth/login`/`GET /onto/auth/me`核principal；finally只登出本次OR会话，不撤其它会话/凭据。

| 现有HTTP接口 | 本会话写入/复核边界 |
| --- | --- |
| POST /onto/spaces | A/B各一个，编码/namespace绑定journal随机nonce；Resume还核同code/namespace、ACTIVE及OWNER，拒借旧空间 |
| POST /onto/classes、/operations、/test-cases | A的JointTask/JointRevision、B的JointTask，各一个只读joint_list及一个精确用例；查询只取JointTask，新增revision类不篡改旧版/不改变结果 |
| PUT /onto/spaces/{id}/guards、/grants/{userId}；PUT /onto/operations/{id}/assistant | region=LOCAL，与真实AP开发身份一致；每次读当前draftRev写；只改本会话空间的OWNER/新消费用户授权 |
| POST /onto/spaces/{id}/candidates；POST /onto/candidates/{id}/validate、tests、review、prepare、commit | A两版/B一版，真实测试passed/候选SHA、prepare四制品ready/size/SHA与实际READY/ACTIVE端点逐步核验；不伪造状态、不跳机器/OWNER门 |
| POST /onto/system/users | 仅两个本会话消费principal，显式已有ACTIVE MEMBER角色，密码随机且仅在请求内存使用，不保存/回显 |
| POST /onto/platform-actors | 4 SERVICE_ACCOUNT＋2 PLATFORM_ACTOR，六个不同actor/principal，tenant与环境精确；readonly/binder由服务端生成主体，消费主体为上述新用户；12小时有效，不用/api-credentials旧key |
| PUT /onto/system/users/{id} | 只给本轮两个binder的自动主体追加既有ACTIVE BINDER角色，保留原角色/状态及expectedRevision；实时核readonly没有release.bind、binder确有 |
| PUT /onto/endpoints/{id}/grants/{userId} | A两release对main/temp consumer共四授予，region LOCAL；B保留真实ACTIVE端点而不授A凭据 |
| GET release/manifest/endpoints、platform-actors、users/roles；POST /onto/r/{space}/releases/{release}/ops/joint_list | live复核所存ID/READY/ACTIVE、六key/ref可用及A两版消费；会产生本会话服务读审计/四次消费事实，不外推跨仓J验收 |

每个业务写前原子保存pending动作，后端响应只摘白名单ID/状态/摘要，未知结果不重试。单HTTP最长30s（上限60s）、响应2MiB且不重定向；每次Invoke-JointOr入口检查900s步骤预算；dev/login及后段key探针各自使用有界请求超时，不能将其称为严格端到端900s截止，未启动任何进程/数据库。root须先确认本仓已登记服务/JAR和权限，再给唯一窗口；不得与Maven/PG/服务操作并行。

## 精确凭据来源与运行参数

root wrapper可读取**OR本仓**`G:/207/项目/算法重组工具/代码/ontorepo/.runtime/local-env.json`的`ONTO_BOOTSTRAP_USERNAME`、`ONTO_BOOTSTRAP_PASSWORD`构造PSCredential；配置真实值不在此文件/CLI/日志，作者没有读取该文件。Prepare本身只接受`-OntologyCredential [PSCredential]`，不自己搜索/复制配置，不要求用户再提供已有管理员密码。

```powershell
# 由root在受限wrapper中安全构造$ontologyCredential，命令行只传wrapper路径，不能传明文。
& '<AP>/scripts/acceptance/Prepare-Joint.ps1' -SessionId 'joint-or-20261029-01' `
    -OntologyBaseUrl 'http://127.0.0.1:18182' -PlatformBaseUrl 'http://127.0.0.1:18080' `
    -PlatformTenant 'local-v12' -Environment 'production' -ModelMode TestFixture `
    -OutputDirectory '<AP>/.local/acceptance/joint-or-20261029-01' `
    -Stage Ontology -OntologyCredential $ontologyCredential
# 同上下文确认阶段恢复时仅追加-Resume；有pending时须先核实，不自动跳过。
```

Ontology exit0只表示本阶段到达，并且仍complete=false。任何失败只留partial阶段/已知资源ID/安全错误码，stdout不含密钥；无最终manifest、无AP发布成功。root必须先审当前冻结SHA并实际执行新协议/32准备，再据实际结果评审是否进入真实OR创建。此候选的语法/首次新能力失败均不得冒充已有产品业务红。
