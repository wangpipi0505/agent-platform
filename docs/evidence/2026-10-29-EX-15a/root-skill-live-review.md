# EX-15a 主执行者补充独立核验

2026-10-29，root。本次独立核验 skill 三生产文件与两Test（作者ex04_pg_scope），不是root所写网关限额选择的自审。只读Git差异及相邻生命周期，未改已有或新增断言。

- 仅接受生成器已有`art_`前缀的相对下载路径；原完整matches及tenant/principal持久授权不变，不把URL当任意远程资源。两新增测试使用生产引用生成器且精确核opaque ID。
- 同步HTTP局部变量保留已装载multipart，finally统一关闭；构建失败仍沿原opened列表关闭，FilePartBody原CAS维持单次关闭。实际断流和对端断连均核确已进入HTTP、两个资源已装载、后续流未读及两个资源各仅关闭一次。
- 上传在READY之前精确检查SDK累计字节和源流额外一字节；长流不会静默发布，失败沿已有精确tenant/id/bucket/key补偿。正常64MiB不读成大数组；短流/断流/声明超限/64MiB重建及HTTP完整SHA分别有实测。原unrelated-tenant仅改为本用例UUID租户派生名，0行及READY断言原样保留。
- 已读取独立review-multipart.md（algorithm_interrupt_readonly）与review-upload-boundary.md（artifact_boundary_review），两者均为对应实现作者之外的复核。当前上述五源SHA与定向绿/skill完整698及相邻32运行冻结一致。

正式live第三轮`ex15a-live-20261029140249-7cb555ea`已native0/complete=true：upload/restart/cleanup三探针各native0；无/坏Bearer各401、拒绝写入0、伪造头不改变PG身份；67,108,864字节SHA `efd6d21bab5ebabd6d298ee79ffe965f1b23ae1ca4314a7899c0f1541c3e08eb`前后相同。gateway38756保持，skill43488真正退出后59752启动，同一artifact读回；精确到期1行、schedulerDeleted及原对象NoSuchKey全部成立。两服务最终退出，输入/JAR/HEAD前后稳定，新库及事实保留。

live采集器42E8仅PrivateAcl变化，root实际AST移除唯一函数后的全文比较与A70B完全相同；精确protected DACL符合时幂等返回，不符合则新建仅DACL对象由.NET File/Directory.SetAccessControl持久化。已读作者PS5实际报告：旧Set-Acl方式仍PrivilegeNotHeld，最终自有文件/目录两次执行均通过、内容/owner/group未变、子项继承正确。未设置owner/group/SACL，不放宽到Everyone。原两失败run保留，完整第三轮不与其局部成功拼接。

网关完整回归run02已完成299=284P7F8E、native/collector各1，身份/SSE38及49子进程/源1321指纹门通过，四库Before/After通过。原raw原因门与regressionGate=false保留。root对11项精确字面表示差异和3项原样记录独立重放通过（root-replay.json）；1项缺类变化单列metadata-classpath-change-review.md独立技术归因，未当成表示差异。首次根证明脚本误取PSObject.Properties.Count导致类型转换停止，修为枚举后的准确13字段数再跑；未改测试、原始文本或proof。本报告不是全模块全绿，也不替代网关产品/新Test的非作者实质审阅、精确索引审和本地提交。首module在Maven前的PS5 Read-Json故障原run01/nativeMaven=null保留。
