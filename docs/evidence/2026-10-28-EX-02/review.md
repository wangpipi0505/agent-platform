# EX-02 独立只读评审

- 评审对象：EX-02（PLAT-18/T18.0 的 pin 空间字段补差）；仅修复实际 ReleasePins 请求缺少 `spaceId`，关闭条件为原失败红绿、真实绑定/幂等/跨空间零新增及本卡回归。不得据此关闭 EX-07 或 PLAT-18 其它残项。
- 作者：`/root`。
- 审阅者：独立代理 `/root/ex02_independent_review`；未参与产品、测试或夹具实现，仅只读预审、独立比较公开证据并新增本评审文件；未使用 superpower。
- 候选：AP HEAD `20333f35bc9f97dc11df296c04c8028e27fd42c8`，冻结 index tree `8ee068ab470172d53d985b2847a7cb21b5dc0f53`，30 文件（产品/测试 3、证据 27）；[candidate-manifest.json](candidate-manifest.json) SHA256 `f6f686e5e717505c10732c4468f6fbdcaf9ebad5a2c1a27a2810109e3edfb325`。OR HEAD `70b278ff28abbe60dca9c3b78880e785d3cf33cd`，冻结 tree `eb953e3708575156c1f4251051abe3d5131613ac`，11 个证据文件；[OR 清单](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/candidate-manifest.json) SHA256 `15b3a058443ca41dab76bb8a10bb24d837fd4f3cb3c72fe9e4e497ff73b38e7e`。manifest 与 review 在冻结后附入；EX-01、EX-35 及原无关材料不属于候选。
- 证据：[本卡 README](README.md)、两仓命令元数据/方法 CSV/summary、AP 红绿与完整 app 比较、真实客户端 RED/GREEN/REPEAT、[OR 夹具](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/fixture-mvs3szui.json)、[HTTP 与行数后验](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/live-protocol-verification.json)及本地原始档案指纹。
- 已核查：两仓 AGENTS、三主入口 EX-02/协议短路径/§14.4；全部三文件 diff；ReleasePins 的字段来源/UUID/未声明空间/请求头/SecretVault 租户约束；AppService 冻结候选与事务后调用；OR ReleaseBindingController、WebBoundary、服务账号认证及 ReleaseBindingService 的空间/租户/release 归属、幂等与写入顺序；夹具与采集脚本可复跑边界、失败记录与敏感信息留存边界。
- 未覆盖：本审阅者未运行 Maven、PG、HTTP、Java、前端或操作进程，未读取真实配置、私有凭据、原始 XML properties/原始日志正文；依公开方法清单与元数据独立复核执行证据。未验平台正常五门禁的真实 publish/commit→OR→`app.publish_pin`（J9）、完整 J1～J8、并发撤权与写入门的原子性；后者留 EX-07/J7 实测，不从静态检查推断已通过或已构成缺陷。未做整仓或 OR 完整 Designer 模块回归。

| 问题ID/级别 | 文件:行号/场景 | 问题与影响 | 处理/理由 | 复核证据/状态 |
| --- | --- | --- | --- | --- |
| PRE-01 / P2（证据） | 两仓 README 开工状态 | 原说明仍称未执行，无法表达当前候选与实际验收结果 | 作者保留开工 HEAD，并另列实际运行 OR HEAD、结果/命令/边界/回滚 | 冻结 README 已核对，关闭 |
| PRE-02 / P2（证据） | 真实跨空间拒绝后的绑定事实 | 单有 HTTP403 不足以记录卡片要求的“无新绑定” | 作者补本轮限定 UUID/app 的只读 SQL 命令、退出码与 own=1/foreign=0 | `live-protocol-verification.json` 与独立空间 fixture 对应，关闭 |
| PRE-03 / P2（证据） | GREEN 的原夹具重复执行 | 首次 `changed=true` 的 GREEN 不能直接作为已有绑定的复跑模式 | 作者新增显式 REPEAT；GREEN 仍严格要求 true，REPEAT 两次严格要求 false、同 ID；探针 UTF-8 输出并恢复采集端编码 | 新 REPEAT 退出0，跨空间403，旧 RED/GREEN/SETUP 失败原件保留，关闭 |
| 无新增产品问题 | ReleasePins.java:92 及两测试 | 一行把已取得且已校验的 `space` 放入 `spaceId`；不改变凭据来源、OR 授权规则、发布快照或失败策略 | 无需产品回改；未引入额外抽象、依赖或迁移 | 三产品/测试 SHA 与红绿/模块运行元数据一致 |

- 逐卡结论：**EX-02 候选可接受；待本地提交并将实际提交号与回滚方式回填统一台账 DoD⑤后，可转完成。** 当前不伪填未来提交号，不称模块全绿，不外推 J9 或并发撤权结论。
- 结论依据：DoD①满足：原定向 19 项 17PASS/2FAIL，失败均为两条实际 HTTP 载荷断言；补字段后 19PASS。真实原客户端 HTTP400，修复后 PINNED/首次 changed=true、重复同 bindingId/changed=false，跨空间403；OR 后验 own=1/foreign=0。DoD②满足本卡范围：app 23XML/178 项为 160PASS/18ERROR/0FAIL/0SKIP；审阅者自行按 CSV 重算 EX-00 完整 app 基线177→178，原方法缺失0，仅新增缺空间边界1项，18 个非通过的 class/method/status 多重集完全一致、新非通过0。OR 无产品修改，指定两类 11PASS，与 EX-00 原11方法/状态独立比较差异0。DoD③满足：两仓 README 与当次 run.json 有命令、数字和限制。DoD④由本独立评审满足。DoD⑤待作者实际提交及台账回填。
- 作者处置：PRE-01～03 的补证据由 `/root` 完成；未因评审扩大产品修改。`agent.pin.enabled=false` 仅是停止写入的临时开关，不能作为通过事实；正式回滚恢复本卡提交，保留证据与历史绑定。
- 审阅者复核：2026-10-28 21:26（Asia/Shanghai）后完成冻结复核。AP30/OR11 文件逐项 SHA256 匹配，候选文件均无未暂存差异，cached `git diff --check` 两仓退出0。独立对这41个公开候选文本做服务账号 key/JWT 值模式扫描，命中0；这不代替对所有可能秘密的保证，也未读取私有值。manifest SHA 与上列一致；最终结论仅绑定这些候选指纹，产品或相关证据变化须重新复核。
