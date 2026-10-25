# D4a：会话专用 verifier 的必要范围补充

2026-10-21，实施中依据真实源码与测试结果批准；原 D4a brief、此前命令与 before 采集均不改写。

已批准10位置之外，仅新增第11个生产修改位置：`admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityResponseVerifier.java`。原source377的正式封存基线已经包含该文件；作者必须在编辑它之前保留实际物理SHA及补采时间，清楚说明何时追加批准，不把它伪装成最初10文件overlay的一部分。每轮完整source manifest原件保持。

实际必要性：root完整读取该未改文件，并核旧冻结HttpCurrentAuthorityClient.query第254–268行。原verifyPinned的统一catch把签名正确的elapsed截止也转换为503；原verify在unknown kid处分支却直接403。它们是原有默认接口语义，本次不能全局修改，但会话接口需要区分源JWT401、可信授权截止403及上游无法建立密码学信任503。作者实际新common七个风险用例已有6绿/1红，红是无可信kid的会话上游响应得到403而预期503，原日志与XML保留；后续具体elapsed测试也须取得真实行为红，setup不计。

最小新增session-only入口复用同一签名验证，不复制Nimbus验签链、不引通用callback框架或public proof工厂。保持全部header/issuer/use/audience、必填日期形状、未来iat/nbf、时间顺序及maxTTL的验证。仅对“已流逝exp”可临时在私有验证路径返回已签claims，随后必须完成本次nonce/request摘要/client/call/requestedAt/active/current等绑定，才判断可信截止为403；过期证据绝不产生成功proof或响应。其他无法验证的签名、unknown kid、坏结构或不可能的未来时间均503。

已有verify与static verifyPinned默认路径不改变，旧调用方继续原语义；只有新session入口使用窄能力。来源门户JWT在验签后／首次释放前到期仍401。已建立密码学信任后，主体/resource/operation/grant/origin/ceiling等语义绑定拒绝沿用已有403；nonce/request/client/call也沿用既有AuthorityProtocol.same拒绝规则，不扩为全局异常重分类。root早先消息“无法绑定均503”过宽，按本段澄清，不要求重写已绿的语义绑定负例。

仅跑真实新／受影响的命名用例：保留unknown-kid实际红；增加signed决定在传输／验签期间到期的真实红与修复绿，有限验证旧默认verify/pinned对elapsed仍503，以及实际受影响正例。已有6个风险绿不重复；不跑common179、原B1/N3/B2或全962。ticket/grant/current各实际截止窗口也应按原brief正确归类，不能仅修current而通过旧ticket verifier重新误映射。

作者仍唯一拥有批准source/target及自身证据。root本新增addendum和预备D4b brief是明确具名外部写入，不收进作者own seal，不修改原保护清单以伪造事前状态。原v1.2/四契约、wire/POM/依赖/其他action及生产IAM闭锁保持；实际失败／oracle／setup原件保留，最终独立复审判断实现与本范围要求是否一致。
