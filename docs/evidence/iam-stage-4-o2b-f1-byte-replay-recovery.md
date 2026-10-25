# F1私有补丁重放的字节保真恢复裁定

唯一final20已通过，当前source/target及测试epoch保持冻结。本裁定只恢复新F1的可重放artifact，不修改源/测试、项目.gitattributes或任何设计契约，不扩大4newfile scope，不要求重跑测试。root已授权实施者在自有artifact根继续，不需再次请求用户许可。

首次原patch的check=0/apply=0不等于完整字节验证通过。作者随后核完整612发现两个production文件末行CR被Git规范化去掉：Budget3537→3536B，Decoder19120→19119B，两test文件相同。原patch、所有命令、旧replay/.git和已算manifest/mismatch身份必须原样保留，记录“完整612字节验证失败”，不得修补旧replay、重apply原patch、改旧记录或称原首次回放完整成功。

根因是项目.gitattributes文本/eol属性优先于仅core.autocrlf=false的意图。root具名核 [Git官方gitattributes文档](https://git-scm.com/docs/gitattributes) 的属性优先级、text unset与binary宏段：私有Git的info/attributes可以局部覆盖，unset text停用行尾转换，unset diff配合binary补丁保留精确bytes。这只解释恢复机制，不代表命令已经执行或成功。

新的独立恢复artifact按顺序执行：

1. 在自有local根另建byte-corrected-replay，只从Accepted E fixes manifest复制完整608 baseline一次；独立private .git，before add即写私有.git/info/attributes的 `* -text -diff`。用实际check-attr记录四新路径的生效text/diff属性，不修改项目、全局或系统Git配置。
2. 从同一未改测试source4生成新的不同binary/full-index raw patch，另名source-byte-corrected.patch；用明确private git-dir+当前工程work-tree，确保高优先级private attributes也作用于生成。原source.patch及旧首次命令不动。
3. 对这个新patch只在新完整608 baseline首次check/apply各一次，分别持久化命令/退出/输出再后继；成功后逐项核最终612名称/bytes/SHA。禁止任何after-apply source copy/no commit。若仍不一致先记录具体差异报root，不循环尝试apply。
4. 新commands/journal/replay-source/complete路径独立；handoff主replay可指向恢复版，但必须同时显列originalFailedReplay/两文件mismatch/原patch身份和失败阶段。own inventory包括两private .git、两patch、全部旧新记录；只排自身。

代码测试不重做，最新实际source epoch仍是唯一final20的字节。成功物理hash/phase缓存；元数据失败只恢复未完成部分。最终报告明确“原文本规范化回放字节验证失败；另一份不同byte-preserving patch在独立baseline首次check/apply且完整612相等”的两段事实，不能用后者抹去前者。root有限审计与fresh代码review分别核恢复与实现；本裁定不预判它们结论。
