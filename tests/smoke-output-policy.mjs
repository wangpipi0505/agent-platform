/** Invalid fixture answers exercise real model wire, engine policy, evidence and publish gate. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'..'),base='http://127.0.0.1:18080';
const login=await fetch(base+'/auth/dev-token',{method:'POST'}).then(r=>r.json());
assert.equal(login.code,'OK');
async function api(url,method='GET',body){const r=await fetch(base+url,{method,headers:{Authorization:'Bearer '+login.data.accessToken,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(90000)});const e=await r.json();assert.equal(r.status,200,`${url}: ${e.message}`);assert.equal(e.code,'OK');return e.data;}
const env=Object.fromEntries((await fs.readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/).filter(x=>x&&!x.startsWith('#')).map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1)];}));
function invocations(runId){assert(/^r_[0-9a-f]+$/.test(runId));const sql=`select count(*) from engine.model_invocation i join engine.node_instance n on n.id=i.node_instance_id join engine.run r on r.id=n.run_id where r.tenant_id='local-v12' and r.public_id='${runId}' and i.kind='MAIN';`;const p=spawnSync(path.join(root,'.tools/postgresql-16.15/pgsql/bin/psql.exe'),['-h','127.0.0.1','-p','25432','-U',env.AGENT_DB_USER||'agent_v12','-d','agent_platform_dev_v12','-At','-v','ON_ERROR_STOP=1','-f','-'],{input:sql,encoding:'utf8',env:{...process.env,PGPASSWORD:env.AGENT_DB_PASSWORD}});assert.equal(p.status,0);return Number(p.stdout.trim());}
async function wait(id){for(let n=0;n<100;n++){const state=await api('/admin/debug-runs/'+id+'/status');if(['SUCCEEDED','FAILED','CANCELLED'].includes(state.status))return state;await new Promise(r=>setTimeout(r,100));}throw Error('Output policy run did not finish');}
const suffix=crypto.randomUUID().replaceAll('-','').slice(0,10);
const provider=await api('/admin/model-providers','POST',{code:'output_fixture_'+suffix,name:'输出校验协议fixture',baseUrl:'http://127.0.0.1:28410/v1',trustLabels:['LOCAL']});
assert.equal((await api('/admin/model-providers/'+provider.id+'/test','POST',{})).ok,true);
const entry=await api('/admin/model-entries','POST',{providerId:provider.id,modelName:'gpt-4',displayName:'故意返回无效JSON的协议fixture',contextWindow:8192,maxOutputTokens:512,capabilities:['chat','json_mode']});
const results=[];
let passApp;
for(const mode of ['TERMINATE','RETRY','PASS_WITH_FLAG','INVALID_REFERENCE']){
  const code='smoke_output_'+mode.toLowerCase()+'_'+suffix;
  let app=await api('/admin/apps','POST',{code,name:'输出策略 '+mode,type:'CHAT',description:'仅本工程HTTP fixture，不能作为模型能力验收'});
  app=await api('/admin/apps/'+code+'/config','PUT',{draftRev:app.draftRev,config:{modelBinding:{modelEntryId:entry.id},inputs:[{name:'message',type:'STRING',required:true}],systemPrompt:'独立输出校验协议测试',tools:[],output:{maxTokens:256,format:{type:'json',schema:mode==='INVALID_REFERENCE'?{$ref:'#/missing'}:{type:'object'}},onInvalid:mode==='INVALID_REFERENCE'?'RETRY':mode,maxRetries:1},guard:{roles:['EDITOR'],attributes:{}}}});
  const run=await api('/admin/debug-runs','POST',{appCode:code,mode:'FULL',inputs:{message:'返回既定fixture响应'}});
  const state=await wait(run.runId),count=invocations(run.runId);
  if(mode==='PASS_WITH_FLAG'){assert.equal(state.status,'SUCCEEDED');assert.equal(state.output.outputValidation.valid,false);assert.equal(state.output.structured,undefined);passApp=app;}
  else{assert.equal(state.status,'FAILED');const semantic=state.error?.semantic||state.nodes?.find(n=>n.error?.semantic)?.error.semantic;assert.equal(semantic,mode==='INVALID_REFERENCE'?'OUTPUT_POLICY_INVALID':'OUTPUT_INVALID',JSON.stringify({runId:run.runId,error:state.error,nodes:state.nodes?.map(n=>({nodeDefId:n.nodeDefId,error:n.error}))}));}
  assert.equal(count,mode==='RETRY'?2:mode==='INVALID_REFERENCE'?0:1);
  results.push({mode,appCode:code,runId:run.runId,status:state.status,modelInvocations:count,validation:state.output?.outputValidation,error:state.error?.semantic});
}
// Expected content comes from the known protocol fixture and frozen validation rule, not the run.
const expected={text:'受控测试完成',usage:{promptTokens:8,completionTokens:4,totalTokens:12},actualModel:{modelEntryId:entry.id,modelName:'gpt-4',providerId:provider.id,endpoint:'http://127.0.0.1:28410/v1'},outputValidation:{valid:false,semantic:'OUTPUT_INVALID',path:'output',reason:'模型终答不是严格JSON（含尾随内容或重复字段）',onInvalid:'PASS_WITH_FLAG',outputAttemptNo:0}};
const evaluation=await api('/admin/evaluations','POST',{appCode:passApp.code,draftRev:passApp.draftRev,candidateSha256:passApp.candidateSha256,datasetVersion:'output_invalid_fixture_v1',cases:[{id:'known_invalid_json',inputs:{message:'返回既定fixture响应'},expected,safety:true}]});
assert.equal(evaluation.status,'ACCEPTED');
const precheck=await api('/admin/apps/'+passApp.code+'/publish/precheck','POST',{});assert.equal(precheck.passed,true);
const publication=await api('/admin/apps/'+passApp.code+'/publish/commit','POST',{precheckId:precheck.precheckId,draftRev:passApp.draftRev,candidateSha256:passApp.candidateSha256,idempotencyRequestNo:crypto.randomUUID()});
const proof={fixtureOnly:true,providerId:provider.id,modelEntryId:entry.id,results,publishedPassApp:passApp.code,versionNo:publication.versionNo};
await fs.writeFile(path.join(root,'.local/output-policy-live-smoke.json'),JSON.stringify(proof,null,2));
process.stdout.write(JSON.stringify(proof,null,2)+'\n');
