import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';
const out='docs/evidence/iam-stage-4-d2-fixes';
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const walk=d=>fs.readdirSync(d,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(d,e.name)):[path.join(d,e.name).replaceAll('\\','/')]);
const old='docs/evidence/iam-stage-4-d2',report='docs/evidence/iam-stage-4-d2-report.md';
assert.equal(hash(report),'3622e92a45a4fd5bf21776f6bc379f4cca5bd36ee9efffd5161a93c8adecdf0b');
const source=JSON.parse(fs.readFileSync(old+'/source-after.json','utf8'));
for(const r of source){assert.equal(hash(r.file),r.sha256);const dest=out+'/baseline/'+r.file;fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(r.file,dest);}
fs.writeFileSync(out+'/source-before.json',JSON.stringify(source,null,2));
const protectedFiles=[...walk(old),...walk('contracts'),'docs/evidence/review-iam-stage-4-d2.md'];
fs.writeFileSync(out+'/protected-before.json',JSON.stringify(protectedFiles.map(file=>({file,sha256:hash(file)})),null,2));
fs.copyFileSync(report,out+'/report-original.md');
console.log(JSON.stringify({source:source.length,protected:protectedFiles.length,reportSha256:hash(report)}));
