# O2a B：部署约束下的额外表示预算决定

root 对正式 B brief 的补充决定，适用于尚未冻结的 B 实现；不改原 brief 已读身份，不改 v1.2/四契约/部署文件。原 brief 为 `iam-stage-4-o2a-b-material-reader-brief.md`，19412 B，SHA `879d6077463aafc5e063a3eb0af58b3600cd4f27d512afbff8d449fa8eb7a340`。

作者具名检查报告：`deploy/compose.yaml:128,160` 与 `deploy/k8s/base.yaml:522,593` 使用 MaxRAMPercentage=70、1Gi 容器，约716.8MiB heap。测试 JVM 的 MaxHeapSize=8535408640 B 不能代替部署约束；初始表示/临时区保守上界达1GiB/工作区，两工作区会超过该部署 heap。root 未据此重复运行既有测试，也不修改部署增大内存绕过问题。

采用额外、同时生效的预算：

| 维度 | 硬上限 |
|---|---|
| 每工作区 model 表示预算 | 64MiB |
| 每工作区 canonical 表示预算 | 8MiB |
| 每工作区全部同时存活表示及临时区 | 192MiB |
| 同一 reader 原两个 permit 的合计表示上界 | 384MiB |

这是原有限原始字节、record/parent/parser 限额之外的**相交限制**，不承诺每个满足单独 raw 限额的输入都能通过。原 scope/initial 1MiB、metadata/root各8MiB、control256KiB、event完整行1MiB/公开payload256KiB及parser/record/parent限制均不变；不改旧 writer 摘要、schema、历史或接受更大的输入。

192MiB 必须是分配前账本的实际约束，不能只写注释。model/canonical 的重叠与包含关系要明确：同一表示不可漏计，原地共享可说明，不得在总量重复声称预算可复用。raw UTF-8、UTF-16、parser/token/实际 backing capacity、DOM、完整父数组、规范化/排序/编码副本和所有有限临时区的同时存活都计入总量，转换前先保留并在真正退出后释放；累计数值防溢出。先检查 precision/scale/canonical长度，再允许 decimal→integer 等可能膨胀的分配。不同失败类别保留容量与材料拒绝的区分。

716.8−384≈332.8MiB 只是给引擎其他 heap 的算术余量；这份决定不证明整个引擎的峰值、GC表现或生产容量，也不声称表示单位等于精确 JVM 对象占用。作者须给出保守单位到各类存活表示/backing的推导，以及实际样本的计数证据。

已成功并归档的 GREEN01 为2类5方法，GREEN02 为2类17方法；二者分阶段，不能加总成一套22项。真实15节点样本已报告 N15/A0/E1/U0、目标父67、所需scope68、H2180、scope UTF-8合计191375 B、单份最大5182 B、事件编码58 B；这些仍待独立报告/产物复核，不能代替未完成的 token/backing/canonical/model/总表示峰值测量。

授权增加一个具名实际测量用例，记录上述未完成的峰值维度。它是新增测量，不重跑已成功的原样本阶段；新增预算边界需 focused 行为 RED→GREEN，再完成正式 brief 指定的单次最终 affected command。root/reviewer只核原始执行证据，不重跑绿测/PG样本。原8源码路径和唯一writer不变；不足须先报告，不默改范围。

EOF
