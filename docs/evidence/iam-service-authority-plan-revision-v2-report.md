# IAM 可信服务权限计划第二轮返修记录

日期：2026-10-20。仅规划返修，未实现功能、未运行功能测试。输入为 `review-iam-service-authority-plan.md` 返修复审 RR1（P1）/RR2（P2），以及 root 本轮 DELEGATED operation 软件 profile 和 Task1/2 行为红口径指示。原审查状态仍为 **REQUEST_CHANGES，待原 reviewer 再核**；本记录不自行声明问题已关闭或 APPROVE。

## 本轮材料

| 文件（均在 docs/evidence） | SHA256 |
| --- | --- |
| `iam-service-authority-plan-revision-v2-baseline.md` | `792b502c25973f325366d456b8de4be2f46362f1242d2ff6245a85aebb3ebfaf` |
| `iam-service-authority-implementation-plan.md` | `8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14` |
| `iam-service-authority-plan-revision-v2.diff` | `a8c6847bb420b8b9eb17b45eb27c00355ecba6d5bc10841c9cdfce74c77c831c` |

在修改前核原计划 SHA256 精确等于 reviewer 冻结的 792b…，再完整复制为 v2 baseline。真实 diff 使用 `git diff --no-index --no-ext-diff --no-color` 生成，只将旧侧 baseline 文件名归一为计划文件名、行尾统一 LF，便于 reviewer 对当前计划作只读反向检查。未用文字摘要冒充 diff，没有执行 apply/reverse apply。

## 返修对应

| 输入 | 当前计划落实 | 后续实施验收要求 |
| --- | --- | --- |
| RR1：合法原响应缺可用材料能力 | §2.4.1 定义独立签名 response-completion permit、ResponseMaterialAccess/ResponseMaterial、readForResponse/acceptForResponse；普通过期 VerifiedAuthority 仍不可用。固定签名来源、owner、operation/response/resource、原 deadline、完整 ceiling、可信 manifest/version/digest；不能换票、新开流或授权新模型/技能/下载/回放 | Task3 的能力负向；Task4 真实 RunController+PG 第二事件尚未读取 payload 的 Clock/闩锁正反例；model final 经本域 scope、同一原模型流私有 manifest、RuntimeClients 接收接口再记账。Task6核真正读取与wire/外发计数；PORTAL不可drain，未首发不能借permit发首次业务 |
| RR1：跨域 manifest 同样缺 current | 正常跨域接口继续要求 fresh current；drain 不再访问通用 manifest GET，不使用过期 service token。model 原连接先私有 material_manifest 再 final，原业务 final/四契约对象不改；非流式原响应使用受限私有 header，内部消费且边界剥离 | 同连接、producer/consumer、invocation/attempt/round/outputAttempt、原请求摘要、version/payload digest 均核；缺帧/乱序/错绑定/跨连接复用拒绝。跨域新 manifest HTTP=0，原响应后新动作=0 |
| RR2：grant 与原门户寿命冲突 | §2.4.2 落 root 裁决：authority 对有效持久grant/current签新DELEGATED operation，原PORTAL来源仅审计，不存/重放原JWT。resume仅显式新动作；forward保持同operation签名截止，响应按operation/grant/current和材料约束，不享受drain | Task5/6 真实 driveRecovered→ModelExchange→RuntimeClients→model stream，原JWT已过期而grant/current有效正例；撤grant/收窄/伪source/改期限反例，DELEGATED在途撤权/503关闭，原PORTAL observer仍在原JWT到期关闭 |
| Task1/2 编译接缝不算关键红 | 两任务明确类缺失只能是初始编译接缝失败 | 最小可编译骨架后，须在真实错误授权/启动/验签路径取得行为断言失败，再实现转绿；本轮未运行 |

重新读取定位：RunController 当前 `:227` 起 PG 下一事件查询与 `:259–264` payload 发送、ModelExchange `:319` 附近的真实 chatStream 与后续 response 记账、ModelController 虚拟线程 chunk/final 发送，以及完整复审 RR1/RR2。仅把观察到的源码缺口转为未来实施步骤，未改这些源码。

## 实际完成的文档检查

- `git apply --reverse --check -- docs/evidence/iam-service-authority-plan-revision-v2.diff` 返回成功，只检查未应用。
- `git apply --numstat`：当前计划 **59 行新增、16 行删除**。
- 当前计划 **7 个 Task、52 个未执行实施 checkbox、11 个文档自检 checkbox**；本轮增加4个未执行实施项、2个文档自检项。PowerShell Get-Content 计639个内容行；不是测试数或实现通过数。
- 第一轮三个材料重新核 SHA256 未变：baseline=`be3d88ea5afd3752082297010b50daa84d7bb0cb83f1b7388b8ce9dfd5be223d`；diff=`844a1778d6e3c48010b8ce4771dd5eefd91cd5f24b1a2427f9370ec7c8d935e7`；report=`3c3618aaed9bead6aa5d43ea86c7a65cf749bcce6e067ada9142206ec09705dc`。

本轮仅修改计划并新增自己的 v2 baseline/diff/本记录。没有改业务源码、测试、target、root裁决、原四契约、执行台账或第一轮返修材料；没有运行 Maven/npm/浏览器/服务/数据库，没有读取真实凭据。所有 authority 能力仍是生产客户端适配合同和将来有限 fixture 验收计划，不新增生产 IAM 服务端；未签收等价生产能力时保持 `BLOCKED_IAM_SCOPE_ACCEPTANCE`。
