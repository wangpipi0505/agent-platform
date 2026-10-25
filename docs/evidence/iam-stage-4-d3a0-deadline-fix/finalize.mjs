import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const out='docs/evidence/iam-stage-4-d3a0-deadline-fix',report='docs/evidence/iam-stage-4-d3a0-report.md';
assert.equal(process.cwd().replaceAll('\\','/'),'G:/207/项目/算法重组工具/代码/agent-platform');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex'),hash=f=>sha(fs.readFileSync(f));
const read=f=>JSON.parse(fs.readFileSync(f,'utf8'));
const prefix=fs.readFileSync(out+'/original-report-prefix.bin'),h=read(out+'/handoff.json');
assert.ok(fs.readFileSync(report).equals(prefix),'Append once only; original prefix must still be entire report');
const section=`

## D3a0 deadline 测试判别力补强（2026-10-20，fix-only）

本次结论：DONE_WITH_CONCERNS，source/target 归还 root，待同一独立 reviewer 针对 fix-only full-context patch 复审。原独立审查为 Spec compliant / Task quality Approved；本节只修 Minor 1 的测试判别力，不改其原结论。原报告前 15850 字节及 SHA256 2d1c7d3cd6ab7bb0822be0eeee1da91640e92357a3ab7709079b709185325c00 原样保留；original-report-prefix.bin 保存原字节。原 review SHA cc9cb265a98327d2e39d95acdaf640d93979c80ddc0a90e7da7b189e7ac1a4bb 已核对且保持不变。

仅修改 platform-gateway/src/test/java/cn/agentplatform/gateway/application/PublishedMetadataClientTest.java。原夹具发“{”后 sleep 3 秒再 EOF，且测试容许 4 秒内任意 PlatformException，可能由 JSON 不完整造成假阳性。现夹具声明完整 Content-Length，先发送 headers 与既有 valid() 合法 DTO 除最后一字节外的全部内容并 flush，用 partialBodySent 通知测试。完整尾部必须等待 releaseBody；8 秒兜底若届满也只会补发完整合法 DTO，不能自行关闭畸形响应。测试先有界等待前缀发送，再要求真实 lookup future 在 4 秒外界内以 PlatformException 为直接 cause 失败，并断言 releaseBody 仍为 1、handlerFinished 仍为 1；因此客户端实际结束时响应尚未释放、handler 尚未关闭，不能依赖 EOF/JSON 错误通过。没有仅收紧墙钟上限。

同一具名用例继续断言 hits=1、实际 Authorization 为独立 Bearer service ticket 且不含原 source，再通过实际 app.authorize 校验 app/metadata action/真实 selector 并核对 subject。原 overflowAndExplicitCancelCloseTheActualBodySubscription 直接 subscription cancel 断言逐字保持，不将其说成本轮重新执行。正常或任一断言失败均在 finally 释放响应、cancel 本次 lookup；查询 executor shutdownNow 后 3 秒 awaitTermination。HTTP handler 等待尾部最多 8 秒；正常路径另断言 handler 在释放后 3 秒退出，AfterEach 再释放、停止本次 HttpServer、shutdownNow 并 3 秒 awaitTermination 断言其 executor 退出。无新增通用 harness、生产 timeout 或配置。

只运行一次指定方法，实际命令：

    ${h.command}

受控 runner 返回 exitCode=0、timedOut=false；原始 Maven 输出为 Tests run: 1, Failures: 0, Errors: 0, Skipped: 0，Time elapsed: 2.822 s，BUILD SUCCESS。立即归档在本节新证据目录 run-01 的 raw.log、实际 XML/TXT、344 项 source/source-sha.json；XML 仅有 fullBodyDeadlineCancelsARealStalledHttpResponse，不把 target 中其他旧 XML 当作本轮结果。raw SHA256=${h.rawSha256}，XML SHA256=${h.xmlSha256}。本次是已正确生产行为的测试补强，没有制造生产 bug 红轮；Maven 无失败轮，不重跑同码 common155/app9/client 其余9项/smoke、七 reactor/package、主服务或 DB。

新证据均在 docs/evidence/iam-stage-4-d3a0-deadline-fix/。source-before 的 344 项与原 D3a0 source-after SHA 和 frozen 实际字节完全一致；source-after/frozen 仍 344 项，仅本测试一项变化。完整 fix-only full-context change.patch SHA256=${h.patchSha256}；正确 parent Git ceiling 下 rev-parse exit128、真实 git diff --no-index --binary exit1、git apply --binary exit0，replay-check.json 的全部344项逐字节相同。未访问或伪造 HEAD/remote/commit。本轮 apply.log 无 whitespace 警告；原 patch 的8处 whitespace 和原历史失败/警告记录均未修改。

target-before=1632 项，target-after=1633 项；本轮仅 gateway target 6 项发生内容/新增变化：3 个该测试 class、其 XML/TXT 和1个新 Surefire dumpstream。gateway 当前75项 target 全部存 frozen-target；其他模块 target 摘要逐项不变。虽然 -am 顺序经过 common 生命周期，其源码/classes/test-jar 摘要未变，未执行 common 测试。target-changes.json、manifest 与 final-verification.json 登记各自 SHA。

保护共21303项，原 D3a0 证据、原 review、受控 runner、契约及其他保护路径均保持。首次 freeze 的严格“无保护差异”断言实际 exit1，原因唯一为另一个 root-owned 文档 docs/evidence/iam-d3e-first-published-path-proposal.md 并发追加，从 b27b63051d7e63c83fef7503f9cc6af8779503c1bbbc0b27890cf42ece92921b 变为 972e08d6b7c5e4b06488d914989163d170bcb7f850fb0764544019cf58461739。root 确认这是其显式授权的 architecture 原 proposal 第10节和10.8追加且已结束写入；该差异单列 root-protection-disposition.json，不纳入源码 patch。原 protected-before 和真实差异未重写，初次失败记录保留 freeze-attempt-01-failed.json；续核明确匹配这一项例外，其余21302项 SHA 相等，不豁免目录、不重跑测试。

新增诊断关注：本轮 raw.log WARN/ERROR=0，但 Surefire 新建 2026-10-20T16-40-22_085.dumpstream，原文保留于 run-01 和 frozen-target，内容为 Boot Manifest-JAR 绝对 classpath 路径、'other' has different root 及 argLine 提示。没有改配置掩盖该诊断，也不因它重复绿轮；XML 1/0 与实际退出码分别如实保留。本轮首次 freeze 的保护断言失败与此 Surefire 诊断均属证据关注，不能表述为生产缺陷或测试失败。原报告既有 brief 历史保护 SHA 缺口、D3/E/R3/真实 IAM 未验收、BLOCKED_IAM_SCOPE_ACCEPTANCE 等边界保持；未扩大本次范围。

报告追加 helper 的首次 node --check 因模板中 Markdown backtick 过度转义报 SyntaxError exit1，发生于执行/追加前，未改报告或源码/target；改用普通摘要文本和缩进代码块后同一语法检查 exit0。两次实际结果单列 helper-checks.json，不混作 Maven 失败轮或生产红。
`;
// The report prefix is binary-preserved; only this centralized section is appended.
fs.appendFileSync(report,section,'utf8');
assert.ok(fs.readFileSync(report).subarray(0,prefix.length).equals(prefix));
for(const r of read(out+'/source-after.json')){assert.equal(hash(r.file),r.sha256);assert.ok(fs.readFileSync(r.file).equals(fs.readFileSync(out+'/frozen/'+r.file)));}
for(const r of read(out+'/target-after.json')){assert.equal(hash(r.file),r.sha256);if(r.file.startsWith('platform-gateway/target/'))assert.ok(fs.readFileSync(r.file).equals(fs.readFileSync(out+'/frozen-target/'+r.file)));}
for(const r of read(out+'/protected-after.json'))assert.equal(hash(r.file),r.sha256);
const before=fs.readFileSync(out+'/baseline/'+h.changedFiles[0],'utf8'),after=fs.readFileSync(h.changedFiles[0],'utf8');
const direct=s=>s.match(/    @Test void overflowAndExplicitCancelCloseTheActualBodySubscription\(\)\{[\s\S]*?\n    \}/)[0];assert.equal(direct(before),direct(after));
const files=['source-before.json','source-after.json','target-before.json','target-after.json','protected-before.json','protected-after.json','protected-changes.json','changed-files.json','change.patch','replay-check.json','handoff.json','run-01/execution.json','run-01/source-sha.json','run-01/raw.log','run-01/reports/platform-gateway/TEST-cn.agentplatform.gateway.application.PublishedMetadataClientTest.xml','run-01/2026-10-20T16-40-22_085.dumpstream','root-protection-disposition.json','freeze-attempt-01-failed.json','helper-checks.json','capture.mjs','finalize.mjs'];
const result={status:'DONE_WITH_CONCERNS',sourceReturned:true,targetReturned:true,report:{file:report,bytes:fs.statSync(report).size,sha256:hash(report),originalPrefixBytes:prefix.length,originalPrefixSha256:sha(prefix),originalPrefixByteEqual:true},sourceFiles:344,sourceChangedOnly:h.changedFiles,sourceAndFrozenCurrentByteEqual:true,gatewayTargetCurrentFrozenByteEqual:true,otherModuleTargetsUnchanged:true,originalCancellationTestByteEqual:true,protectedUnchanged:21302,rootAuthorizedProtectedDifference:h.protectedChanges,originalD3a0EvidenceProtectedCount:read(out+'/protected-before.json').filter(r=>r.file.startsWith('docs/evidence/iam-stage-4-d3a0/')).length,namedTest:'fullBodyDeadlineCancelsARealStalledHttpResponse',tests:1,failures:0,errors:0,skipped:0,mavenExitCode:0,artifacts:files.map(f=>({file:out+'/'+f,sha256:hash(out+'/'+f)}))};
fs.writeFileSync(out+'/final-verification.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
