import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const out='docs/evidence/iam-stage-5-e1a';
assert.equal(process.cwd().replaceAll('\\','/'),'G:/207/项目/算法重组工具/代码/agent-platform');
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const read=f=>JSON.parse(fs.readFileSync(out+'/'+f,'utf8'));
const runs=[];
for(const stage of ['red-01','targeted-01','targeted-02','red-02','targeted-03','full-01']){
  const prefix='iam-stage-5-e1a-'+stage,e=read(prefix+'/execution.json');
  const suites=e.reports.filter(r=>r.file.endsWith('.xml')).map(r=>{
    const xml=fs.readFileSync(out+'/'+prefix+'/reports/'+r.file,'utf8'),head=xml.match(/<testsuite\b[^>]*>/)[0];
    const attr=k=>head.match(new RegExp('\\b'+k+'="([^"]*)"'))[1];
    return {name:attr('name'),tests:+attr('tests'),failures:+attr('failures'),errors:+attr('errors'),skipped:+attr('skipped'),cases:[...xml.matchAll(/<testcase\b[^>]*\bname="([^"]+)"/g)].map(m=>m[1]),sha256:r.sha256};
  });
  const totals=suites.reduce((a,s)=>{for(const k of ['tests','failures','errors','skipped'])a[k]+=s[k];return a;},{tests:0,failures:0,errors:0,skipped:0});
  runs.push({stage:prefix,command:e.command,buildSuccess:e.buildSuccess,rawSha256:e.rawSha256,totals,suites,warningCount:e.warnings.length,errorLogLines:e.errors.length});
}
assert.deepEqual(runs.at(-1).totals,{tests:174,failures:0,errors:0,skipped:0});
const targetBefore=read('target-before.json'),targetAfter=read('target-after.json');
const dumps=read('target-changes.json').filter(r=>r.file.endsWith('.dumpstream')||r.file.endsWith('.dump')).map(r=>({file:r.file,sha256:r.after,content:fs.readFileSync(r.file,'utf8')}));
const fullRaw=fs.readFileSync(out+'/iam-stage-5-e1a-full-01/raw.log','utf8');
const fixture=fs.readFileSync('admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java','utf8');
const oldFixture=fs.readFileSync(out+'/baseline/admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java','utf8');
const signatures=s=>[...s.matchAll(/^\s*public ([^\n{]+\([^\n{]*)\{/gm)].map(m=>m[1].trim());
const hooks=signatures(oldFixture);for(const signature of hooks)assert.ok(signatures(fixture).includes(signature),signature);
const result={runs,targetsBefore:targetBefore.length,targetsAfter:targetAfter.length,targetChanged:read('target-changes.json').length,commonFrozenTargetCount:targetAfter.filter(r=>r.file.startsWith('admin-common/target/')).length,otherModuleTargetsUnchanged:read('target-changes.json').every(r=>r.file.startsWith('admin-common/target/')),preservedPublicFixtureHooks:hooks,dumps,fullExpectedStartupErrorLines:fullRaw.split(/\r?\n/).filter(l=>/\bERROR\b/.test(l)),productionFilesChanged:read('changed-files.json').filter(r=>r.file.includes('/src/main/')).map(r=>r.file),testFilesChanged:read('changed-files.json').filter(r=>r.file.includes('/src/test/')).map(r=>r.file)};
fs.writeFileSync(out+'/test-audit.json',JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({runs:runs.map(({suites,...r})=>r),targetsBefore:result.targetsBefore,targetsAfter:result.targetsAfter,targetChanged:result.targetChanged,commonFrozenTargetCount:result.commonFrozenTargetCount,newDumps:dumps.map(({content,...d})=>({...d,excerpt:content.slice(0,550)})),preservedPublicFixtureHooks:hooks},null,2));
