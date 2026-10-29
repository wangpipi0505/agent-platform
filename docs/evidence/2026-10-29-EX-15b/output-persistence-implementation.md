# EX-15b 二进制输出持久化（2026-10-30）

实现已改，待验；完整EX-15b未关闭。

四生产：ProcessAdapter在callDir回收前交接输出；ProcessExecutionAdapter转发原可信run/attempt；SkillAdapters全组先校验并持受保护句柄；ArtifactStore按真实Actor/run/call/attempt登记二进制产物并精确补偿本批。普通upload原样，无新迁移/POM/第三执行器。

第一轮：output-red-20261030-01，原19P、新3F0E0S，准确缺少fileRefs、错SHA未拒、未执行持久化补偿。第二轮：output-green-20261030-01，69项67P1F1E0S，sourceStable=true，旧66项全P；不得称为通过。正常输出及存储失败例均在文件初检提前拒绝，SHA负例也不能据此声称已走完整分支。

已静态定位：本机JDK21 src.zip的WindowsFileAttributes.fileKey()实现固定return null，原null拒绝假设错误。第二轮后仅移除该错误条件，key非null才补充比对；Windows NOSHARE_WRITE/DELETE+NOFOLLOW、全祖先链接检查、size/SHA与同句柄读取均保留，非Windows拒绝。按用户每批最多两轮，未追加第三轮，修正后的输出正例和补偿链仍待后续整体功能证据覆盖。

跨服务当前最新代原子READY、取消晚到/逃逸孤儿、完整替换攻击防护未验收；不读engine库或伪造skill.call。原始两轮日志/XML与源记录仅在本仓忽略目录.local/ex15b-output-persistence，不提交凭据/运行数据。随后继续绑定、回放与整体实现，不为单项追绿停工。
