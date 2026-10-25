import fs from 'node:fs';import crypto from 'node:crypto';import assert from 'node:assert/strict';
const out='docs/evidence/iam-stage-4-d2-fixes',report='docs/evidence/iam-stage-4-d2-report.md';
const bytesHash=b=>crypto.createHash('sha256').update(b).digest('hex'),hash=f=>bytesHash(fs.readFileSync(f));
const original=fs.readFileSync(out+'/report-original.md');assert.equal(bytesHash(original),'3622e92a45a4fd5bf21776f6bc379f4cca5bd36ee9efffd5161a93c8adecdf0b');
assert.ok(fs.readFileSync(report).equals(original),'Only append once to the exact original report');
fs.appendFileSync(report,fs.readFileSync(out+'/report-appendix.md'));
assert.ok(fs.readFileSync(report).subarray(0,original.length).equals(original));
const checks=[];
for(const manifest of ['source-after.json','admin-common-target-sha256.json','app-management-target-sha256.json','platform-gateway-target-sha256.json','protected-before.json']){
 const rows=JSON.parse(fs.readFileSync(out+'/'+manifest,'utf8'));assert.ok(rows.every(r=>hash(r.file)===r.sha256),manifest+' drift');checks.push({manifest,count:rows.length,mismatches:0});
}
const final=JSON.parse(fs.readFileSync(out+'/source-after.json','utf8'));
for(const stage of ['iam-stage-4-d2-fix-final-common','iam-stage-4-d2-fix-final-http'])assert.deepEqual(JSON.parse(fs.readFileSync(out+'/'+stage+'/source-sha256.json','utf8')),final);
const result={checkedAt:new Date().toISOString(),checks,finalRunsMatchFrozenSource:true,originalReportPrefixBytes:original.length,originalReportPrefixSha256:bytesHash(original),reportSha256:hash(report),patchSha256:hash(out+'/change.patch'),sourceAndTargetReturned:true};
fs.writeFileSync(out+'/handoff-verification.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
