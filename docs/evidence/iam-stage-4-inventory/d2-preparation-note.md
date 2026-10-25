# D2 resolver 准备期核对（未实施）

root 在 D1 实施期间仅阅读本新工程现有 app/gateway 源码与批准计划 §2.6；本记录不是 D2 授权或测试通过。

- 实际 app Flyway 最大版本为 V103；D2 候选 V104 仍须开始前再查。`integration_credential` 目前没有 policy_revision，唯一键为 tenant/app/biz。现有 IntegrationService 只有 issue、revoke、policy 三处实际写入；当前没有独立 reissue/key-replacement API。D2 必须保障所有实际变化的原事务 revision，不凭计划列举名称新增无必要产品入口。初次记录版本与后续递增、并发/回滚需真实 PG 验证。
- gateway 的 IntegrationPolicy.clip 当前将 principal 映射为 `biz + ":" + user`，超过64字符则 `ext_` 加 SHA256(biz + NUL + user) 的前60位；角色移除 ADMIN/EDITOR/SERVICE/GATEWAY_AUTH（大写比较）。其 digest 取 secretRef/claimLimits/allowedOrigins 的现有 canonical 语义。resolver 必须与实际规则一致且不返回 secretRef，不允许引入 gateway 业务 JAR 依赖。D2 正式 brief 需裁定最小共用纯规则接缝或受测的局部映射，不能各自发明 principal/digest。
- 原 common LoopbackAuthority 为包私有测试 fixture，内嵌独立测试 resolver，使用固定 credential=7、tenant-a/app-a/biz-a/user alice/principal alice/digest(policy-v1)，并不等同实际 app 的 biz:user 映射。D2 实际 PG 联测必须显式接入真实 resolver URL/独立 policy 公钥、真实 credential/版本/映射/digest 和当前返回上限；不能仅在旧内存 resolver 切 ACTIVE。这些是测试 fixture 接口扩展，不能创建生产 IAM。
- 跨模块复用 common 测试 fixture 需要最小可审测试构建接缝；当前 common POM 未发布 test-jar。不得依赖其他业务服务 JAR、复制一套通用 authority 或用反射隐藏身份边界。D2 正式交接前确定共享测试入口和生命周期，保留 C 的原默认行为与全部历史证据。
- D1 完成不等于六服务已迁移；D2 可用真实 IntegrationController/IntegrationService + 专用 PG + 独立 resolver chain 的有限随机端口应用验证原 ADMIN policy/revoke 接口。完整 gateway→authority→真实 app resolver/PG→真实 downstream 的原 R3 联合链仍须 D3 实际桥接/服务接收落地后执行；不能用组件配置启动或纯 mock 宣称全 Task4 通过。
- 原计划禁止访问生产秘密。真实 IAM 私钥不在本工程创建；测试仅临时生成独立 RSA。PG 必须使用本新工程专用实例/数据库及受控测试凭据注入，具体 runner 由 root 正式交接，不能打印现有 `.env`/vault/master key，也不能因没有外部环境改用 H2/内存业务库。

以上疑点由 root 在 D2 dispatch 前给出具体裁决和输入；当前不改 app/common/gateway 源码、POM、迁移、数据库或进程。
