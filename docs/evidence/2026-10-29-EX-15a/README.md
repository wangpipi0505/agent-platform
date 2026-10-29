# EX-15a artifact 流式、持久化与正式入口

2026-10-29，验收收口：定向红绿、完整skill相对门、相邻回归、正式JVM链及完整gateway已取得证据。原始gateway原因门未通过，15项逐项复核见下；最终独审、精确索引和本地提交分别记证，不以本说明代替提交。开始时AP基点67d1d41d，正式live和gateway回归HEAD为1fe99d67；不同轮次不拼成一次执行。未使用superpower。

## 实际红绿和验收边界

| 运行目录 | 实际结果 | 结论 |
|---|---|---|
| red-20261029-1240 → green-20261029-1247 | 原11=7P2F2E/native1 → 同11P/native0，0skip | 生产生成URL的art_前缀断链、真实HTTP失败时未消费后续流漏关；原7项及新增4断言保持 |
| upload-red-20261029-1250 | 16=13P3F/native1 | 长流产品缺口与短/断流SDK包装层测试前提错误分别保留 |
| upload-red-v3-20261029-1257 → upload-green-20261029-1305 | 同v3测试16=15P1F/native1 →16P/native0 | 长流唯一产品红后，仅READY前四行完整长度校验修复；严格NoSuchKey链不放宽 |
| multipart-green-upload-20261029-1305 | 11P/native0 | 上传修复后的同multipart回归 |
| collector/runs/full-skill-20261029-ex15a-01 | 71XML/698=582P6F106E4旧S；relative gate=true/allGreen=false | 原689+新增9全部对齐，原116原因保留；native0仅failure.ignore收集原红，不是698P |
| collector/runs/adjacent-20261029-ex15a-01 | 5XML/32P/native0，无ignore/skip | ArtifactBound/Reference、FormMapping、HTTP async与PathParams async相邻边界保持 |
| gateway/gateway-red-20261029-ex15a-01 → gateway/gateway-green-20261029-ex15a-01 | 同新Test 11=7P4F/native1 →11P/native0 | 四个合法大multipart被旧2MiB限制拦截；精确POST/路径/媒体类型给65MiB总wire，其它仍2MiB |
| live/runs/ex15a-live-20261029140249-7cb555ea | native0/complete=true；upload/restart/cleanup各native0 | 正式18080验签、64MiB上传下载、新skill JVM读回与精确scheduler清理全链到达 |
| gateway/runs/gateway-module-20261029-ex15a-02 | 28XML/299=284P7F8E，native/collector各1；原raw/regression=false | 原288+新11身份齐、SSE38/49子进程及1321指纹稳定、四库Before/After均通过；15旧非通过另做逐项复核，不能改成299P |

正式live：gateway38756保持，skill43488退出后59752新PID启动，同一artifact 67,108,864字节及SHA `efd6d21bab5ebabd6d298ee79ffe965f1b23ae1ca4314a7899c0f1541c3e08eb`读回一致。缺/坏Bearer各401且零新行，伪造身份头不改变PG主体；仅精确登记一行到期，正式scheduler DELETED且原对象NoSuchKey。最终两服务均已退出，输入/JAR/HEAD前后稳定；新专库与事实保留。fresh ArtifactStore组件重建与此次真正JVM重启分别记证。

## 最小代码范围与审阅

七个源文件：ArtifactStore、MultipartFormMapping、SkillAdapters、GatewayController四生产；ArtifactPersistenceTest、MultipartStreamingTest两个既有测试；ArtifactUploadGatewayTest一个新测试。V306已承载需要的持久事实，本卡无迁移；原鉴权/主体归属、异步拒绝、PROCESS/ALGORITHM不变。

Skill作者ex04_pg_scope；非作者意见见review-multipart.md、review-upload-boundary.md、root-skill-live-review.md。Gateway产品作者root、新Test作者artifact_boundary_review；非作者意见见gateway/module-candidate.md及final-skill-author-check.md的明确独立网关部分。后者对skill仅作者自查，不充当整卡独立通过。整卡最终实质审阅见final-independent-review.md，索引另审；两者及本地提交齐后方可关闭。

完整gateway原15项中：3项全文一致；11项仅精确列出的fixture UUID或Spring对象地址不同。bounded-representation-proof.json保存逐字段证明，root用verify-root-replay.ps1独立重放，60原文本哈希、28全文字段、34个精确字面替换通过，未改写原件或通用归一化。剩余PublishedMetadataSmokeLauncherTest缺类从client5变为core5，是实际不同症状，不能替换类名当一致；gateway/metadata-classpath-change-review.md独立核到同一固定子进程classpath同时缺这两个依赖，POM/相关源码/固定CP无变更、正式JAR包含两类，接受为原有不完整子CP债务。旧运行未登记的class/JAR字节不能复原，首次缺类顺序仍未知，原方法未通过。

final-acceptance.json单列“本卡无新增回归”的综合判断及上述一项技术归因，不覆盖原summary的rawReasonGate=false/regressionGate=false/allTestsPassed=false。EX-27仍承接旧夹具债务；后续修此smoke须另登记准确范围、先实跑再修，不能在本卡补CP或移动基线。

## 采集器失败历史

- live134252轮在建库/启动前遇PS5泛型List[object]转换错误；原脚本及诊断保留，修为ToArray后独立验证。
- live134925轮实际upload通过，旧skill退出后重复Set-Acl失败；原run/upload及A70B脚本保留，不冒充重启通过。仅PrivateAcl按精确DACL幂等检查和.NET仅DACL持久化修复，见live/acl-fix-20261029.md及root独审。第三完整run另编号，不覆盖前两轮。
- gateway-module-20261029-ex15a-01在Maven前因PS5 JSON嵌套数组误计基线失败，nativeMaven=null；仅Read-Json局部赋值再return修复，原件和独立PS5实测见gateway/read-json-fix-review.md。新02重做实际四库Before；不静默沿用旧批准。

准确命令和SHA分别在各collector/run/admission文件；私密原件在本仓忽略.local目录，公开只存必要安全事实/指纹，不提交凭据或payload。旧模块失败、4旧JDK8跳过、V222构建残留均保留，本卡不能外推clean构建或真实算法worker完成。

回滚仅反向本卡四生产授权差异并保持对应测试/契约一致，保留历史实红/回归与本轮持久事实；任何资源处置只限登记的唯一库/tenant/artifact/bucket/key，不扫未知库、桶或队列，不整仓reset。
