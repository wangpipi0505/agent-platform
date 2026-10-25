# C：排队与首轮材料额度顺序补充

这是正式C brief的有限实现裁定，不改v1.2/四契约，不增加源码范围、permit、executor容量或预算。默认2个长期writer线程＋2个有限队列，原reader只有2个permit；若排队任务持有两个B结果，会使活动writer无法读取下一轮，而排队任务又等待活动writer退出，形成饥饿。

采用以下顺序：

1. 原真实receiver frame内完成严格请求/用途/readiness、slot准入、唯一client开holder；所有authority网络在RR外。
2. 先保留本轮完整C frame配额，再以原reader获得首个完整保护窗口。这个同步预检必须真实完成owner/root/state/scope/current与事务退出；不能删掉以求少一次读取。
3. 预检成功后同步close并丢弃该B结果，归还该次frame。不得把预检业务字段/编码frame或公开DTO当后续输出proof。排队只持小owner、原slot/holder和原cursor，保持原首次harddeadline；不持B结果/permit或frame。
4. writer实际取得RUNNING后，重新保留frame、fresh capture及同after的完整RR窗口，在这个新结果的guarded consume内获取实际stream、获取后终检、编码/write/flush。只有本轮实际证明允许输出，预检不能替代。
5. PENDING取消/拒绝仍通过CAS证明neverrun，再一次关闭其实际持有资源；迟到run不能进入。RUNNING的实际DB/getter/write/flush/complete阻塞仍只stop，所持B/C/frame直至实际finally退出才释放。

brief的“最多一个pending result”是上限，不要求排队必须持大结果。该裁定消除长期writer与queued reader额度的环形等待，不放宽权限/预算。新增有限验证应核queued owner不独占两个reader permits、活动writer下一轮仍可推进，以及取消/拒绝不泄漏slot/holder；不将先前改动前的focused GREEN记成最终源码已验证。所有实际阶段及source epoch分别保存，最终适用类检查一次即可。
