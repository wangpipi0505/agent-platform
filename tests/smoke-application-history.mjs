/** Real independent APIs: copy -> evaluate -> publish twice -> immutable version diff. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const root = path.resolve(import.meta.dirname,'..');
const base='http://127.0.0.1:18080';
const login=await fetch(base+'/auth/dev-token',{method:'POST'}).then(r=>r.json());
assert.equal(login.code,'OK');
async function api(url,method='GET',body) {
  const r=await fetch(base+url,{method,headers:{Authorization:'Bearer '+login.data.accessToken,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(90000)});
  const result=await r.json();
  assert.equal(r.status,200,`${method} ${url}: ${result.message}`);assert.equal(result.code,'OK');
  return result.data;
}
const original=JSON.parse(await fs.readFile(path.join(root,'.local/chat-session-smoke-result.json'),'utf8'));
const resume=process.argv[2];
if(resume&&!/^smoke_versions_[0-9a-f]{10}$/.test(resume)) throw Error('Only an explicitly named smoke fixture can be resumed');
const code=resume||'smoke_versions_'+crypto.randomUUID().replaceAll('-','').slice(0,10);
let app=resume?await api('/admin/apps/'+code):await api('/admin/apps/'+original.appCode+'/copy','POST',{code,name:'独立版本对照验收'});
if(!resume) {assert.equal(app.status,'DRAFT');assert.equal(app.currentVersionNo,null);assert.equal(app.draftRev,1);}
const entry=(await api('/admin/model-entries')).find(x=>x.id===original.modelEntryId);
const provider=(await api('/admin/model-providers')).find(x=>x.id===entry.providerId);
const expected={text:'受控测试完成',usage:{promptTokens:8,completionTokens:4,totalTokens:12},actualModel:{modelEntryId:entry.id,modelName:entry.modelName,providerId:provider.id,endpoint:provider.baseUrl}};
for(const version of [1,2]) {
  if(Number(app.currentVersionNo)>=version) continue;
  if(version===2) {
    app=await api('/admin/apps/'+code);
    app=await api('/admin/apps/'+code,'PUT',{draftRev:app.draftRev,name:app.name,description:'第二版：增加明确的系统提示说明',domain:app.domain,bizCategory:app.bizCategory,tags:app.tags});
    app=await api('/admin/apps/'+code+'/config','PUT',{draftRev:app.draftRev,config:{...app.config,systemPrompt:'独立版本对照验收：第二版系统提示'}});
  }
  const report=await api('/admin/evaluations','POST',{appCode:code,draftRev:app.draftRev,candidateSha256:app.candidateSha256,datasetVersion:'history_fixture_v'+version,cases:[{id:'known_answer',inputs:{message:'只验证版本固化'},expected,safety:true}]});
  assert.equal(report.status,'ACCEPTED','Known fixture evaluation must pass before real publish');
  const precheck=await api('/admin/apps/'+code+'/publish/precheck','POST',{});
  assert.equal(precheck.passed,true);
  const published=await api('/admin/apps/'+code+'/publish/commit','POST',{precheckId:precheck.precheckId,draftRev:app.draftRev,candidateSha256:app.candidateSha256,idempotencyRequestNo:crypto.randomUUID()});
  assert.equal(published.versionNo,version);
}
const diff=await api('/admin/apps/'+code+'/versions/diff?fromVersion=1&toVersion=2');
assert(diff.changes.some(x=>x.path==='/config/systemPrompt'&&x.operation==='REPLACE'));
assert(diff.changes.some(x=>x.path==='/description'&&x.operation==='REPLACE'));
const version1=await api('/admin/apps/'+code+'/versions/1');
assert.notEqual(version1.config.systemPrompt,'独立版本对照验收：第二版系统提示');
const logs=await api('/admin/apps/'+code+'/lifecycle-logs');
assert.equal(logs.filter(x=>x.action==='PUBLISH').length,2);
assert(logs.some(x=>x.action==='COPY'&&x.detail.sourceAppCode===original.appCode));
const result={fixtureOnly:true,appCode:code,versions:[1,2],diffPaths:diff.changes.map(x=>x.path),firstVersionUnchanged:true,lifecycleLogCount:logs.length};
await fs.writeFile(path.join(root,'.local/application-history-live.json'),JSON.stringify(result,null,2));
process.stdout.write(JSON.stringify(result,null,2)+'\n');
