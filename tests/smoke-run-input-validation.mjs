/** Verify the real run boundary rejects invalid declarations before creating/charging a run. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..'),base='http://127.0.0.1:18080';
const env=Object.fromEntries((await fs.readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/).filter(x=>x&&!x.startsWith('#')).map(x=>{const i=x.indexOf('=');return[x.slice(0,i),x.slice(i+1)];}));
const login=await fetch(base+'/auth/dev-token',{method:'POST'}).then(r=>r.json());assert.equal(login.code,'OK');const user=login.data.accessToken;
const claims=JSON.parse(Buffer.from(user.split('.')[1],'base64url').toString());claims.aud='agent-platform-internal';claims.token_use='service';claims.roles=[...new Set([...claims.roles,'SERVICE'])];
const signing=Buffer.from(JSON.stringify({alg:'HS256',typ:'JWT'})).toString('base64url')+'.'+Buffer.from(JSON.stringify(claims)).toString('base64url'),service=signing+'.'+crypto.createHmac('sha256',env.AGENT_JWT_SECRET).update(signing).digest('base64url');
async function http(url,body,token=user,origin=base,method=body?'POST':'GET'){const r=await fetch(origin+url,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)});const raw=await r.text();return{status:r.status,envelope:raw?JSON.parse(raw):{},url};}
async function api(url,body,method){const r=await http(url,body,user,base,method);assert.equal(r.status,200,JSON.stringify(r));return r.envelope.data;}
function sql(query){const p=spawnSync(path.join(root,'.tools/postgresql-16.15/pgsql/bin/psql.exe'),['-h','127.0.0.1','-p','25432','-U','agent_v12','-d','agent_platform_dev_v12','-At','-v','ON_ERROR_STOP=1','-f','-'],{input:query,encoding:'utf8',env:{...process.env,PGPASSWORD:env.AGENT_DB_PASSWORD}});assert.equal(p.status,0,p.stderr);return Number(p.stdout.trim());}
const count=code=>{assert(/^[A-Za-z0-9_]+$/.test(code));return sql(`select count(*) from engine.run where tenant_id='local-v12' and app_code='${code}';`);};
const code='smoke_run_input_'+crypto.randomUUID().replaceAll('-','').slice(0,10);
let app=await api('/admin/apps',{code,name:'运行输入声明边界fixture',type:'CHAT',description:'仅本工程协议fixture，不代表供应商'});
app=await api('/admin/apps/'+code+'/config',{draftRev:app.draftRev,config:{modelBinding:{modelEntryId:2},inputs:[{name:'message',type:'STRING',required:true,minLength:2}],systemPrompt:'运行前校验输入声明',tools:[],output:{maxTokens:128},guard:{roles:['EDITOR'],attributes:{}}}},'PUT');
const results=[];
for(const [name,inputs] of [['missing',{}],['wrong-type',{message:7}],['constraint',{message:'x'}]]){
 const before=count(code),r=await http('/admin/debug-runs',{appCode:code,mode:'FULL',inputs});
 results.push({name,trigger:'DEBUG',status:r.status,code:r.envelope.code,runId:r.envelope.data?.runId,runsAdded:count(code)-before});
}
const evalBefore=count(code),evaluation=await http('/internal/evaluations/run',{appCode:code,draftRev:app.draftRev,candidateSha256:app.candidateSha256,inputs:{}},service,'http://127.0.0.1:18082');
results.push({name:'missing',trigger:'EVALUATION',status:evaluation.status,code:evaluation.envelope.code,runId:evaluation.envelope.data?.runId,runsAdded:count(code)-evalBefore});
const formalCode='smoke_chat_bff1adb366',formalBefore=count(formalCode),formal=await http('/admin/apps/'+formalCode+'/runs',{inputs:{},requestId:crypto.randomUUID()});
results.push({name:'missing',trigger:'FORMAL',status:formal.status,code:formal.envelope.code,runId:formal.envelope.data?.runId,runsAdded:count(formalCode)-formalBefore});
const invalidRuns=results.flatMap(r=>r.runId?[r.runId]:[]);
for(const id of invalidRuns){for(let n=0;n<100;n++){const s=await api('/admin/debug-runs/'+id+'/status');if(['SUCCEEDED','FAILED','CANCELLED'].includes(s.status))break;await new Promise(r=>setTimeout(r,50));}}
const invalidInvocations=invalidRuns.length?sql(`select count(*) from engine.model_invocation i join engine.node_instance n on n.id=i.node_instance_id join engine.run r on r.id=n.run_id where r.public_id in (${invalidRuns.map(id=>{assert(/^r_[a-f0-9]+$/.test(id));return "'"+id+"'";}).join(',')});`):0;
const evidence={fixtureModelOnly:true,appCode:code,invalidInvocations,results};await fs.writeFile(path.join(root,'.local/runtime-input-validation-live.json'),JSON.stringify(evidence,null,2));process.stdout.write(JSON.stringify(evidence,null,2)+'\n');
assert(results.every(r=>r.status===400&&r.runsAdded===0),'Every run entry must reject invalid inputs before persisting or sending');assert.equal(invalidInvocations,0);

// The declared NULL type is part of the frozen graph contract. Presence must not be
// reduced to truthiness or hasNonNull by the START node after admission succeeds.
const flowCode='smoke_input_null_'+crypto.randomUUID().replaceAll('-','').slice(0,10);
let flow=await api('/admin/apps',{code:flowCode,name:'显式空值与假值输入fixture',type:'FLOW'});
const valid={nullValue:null,zero:0,disabled:false,items:[],object:{}};
const definition={definitionVersion:'1.0',trigger:'TASK',entry:'start',inputs:[{name:'nullValue',type:'NULL',required:true},{name:'zero',type:'NUMBER',required:true},{name:'disabled',type:'BOOLEAN',required:true},{name:'items',type:'ARRAY',required:true},{name:'object',type:'OBJECT',required:true}],outputs:Object.keys(valid).map(name=>({name,from:'${input.'+name+'}'})),nodes:[{id:'start',type:'START'},{id:'end',type:'END'}],edges:[{from:'start',to:'end'}],joins:[],executionRules:{parallel:false,maxParallel:1,cyclesForbidden:true}};
await api('/admin/flows/'+flowCode,{draftRev:flow.draftRev,definition,layout:{}},'PUT');
const created=await api('/admin/debug-runs',{appCode:flowCode,mode:'FULL',inputs:valid});
let final;
for(let n=0;n<100;n++){final=await api('/admin/debug-runs/'+created.runId+'/status');if(['SUCCEEDED','FAILED','CANCELLED'].includes(final.status))break;await new Promise(r=>setTimeout(r,50));}
evidence.positive={appCode:flowCode,runId:created.runId,status:final.status,inputs:final.inputs,output:final.output};
await fs.writeFile(path.join(root,'.local/runtime-input-validation-live.json'),JSON.stringify(evidence,null,2));
process.stdout.write(JSON.stringify({positive:evidence.positive},null,2)+'\n');
assert.equal(final.status,'SUCCEEDED');assert.deepEqual(final.inputs,valid);assert.deepEqual(final.output,valid);

evidence.sessions=[];
for(const correctedId of ['same','new']){
 const session=await api('/admin/apps/'+formalCode+'/sessions',{});
 const sessionUrl='/admin/apps/'+formalCode+'/sessions/'+session.sessionId;
 const rejectedId='invalid_'+crypto.randomUUID().slice(0,8),runsBefore=count(formalCode);
 const rejected=await http(sessionUrl+'/turns',{requestId:rejectedId,inputs:{}});
 const afterRejection=await api(sessionUrl);
 const item={correctedId,sessionId:session.sessionId,rejectedStatus:rejected.status,rejectedCode:rejected.envelope.code,turnsAfterRejection:afterRejection.turns.length,nextTurnAfterRejection:afterRejection.nextTurn,runsAfterRejection:count(formalCode)-runsBefore};
 evidence.sessions.push(item);
 await fs.writeFile(path.join(root,'.local/runtime-input-validation-live.json'),JSON.stringify(evidence,null,2));
 assert.equal(rejected.status,400);assert.equal(afterRejection.turns.length,0);assert.equal(afterRejection.nextTurn,1);assert.equal(item.runsAfterRejection,0);
 const requestId=correctedId==='same'?rejectedId:'corrected_'+crypto.randomUUID().slice(0,8);
 const body={requestId,inputs:{message:'会话输入修正后继续'}};
 const accepted=await api(sessionUrl+'/turns',body);
 let finished;
 for(let n=0;n<100;n++){finished=await api('/admin/debug-runs/'+accepted.runId+'/status');if(['SUCCEEDED','FAILED','CANCELLED'].includes(finished.status))break;await new Promise(r=>setTimeout(r,50));}
 const duplicate=await api(sessionUrl+'/turns',body),completedSession=await api(sessionUrl);
 Object.assign(item,{runId:accepted.runId,status:finished.status,modelInvocations:sql(`select count(*) from engine.model_invocation i join engine.node_instance n on n.id=i.node_instance_id join engine.run r on r.id=n.run_id where r.public_id='${accepted.runId}';`),turnsAfterCorrection:completedSession.turns.length,nextTurnAfterCorrection:completedSession.nextTurn,duplicateRunId:duplicate.runId});
 await fs.writeFile(path.join(root,'.local/runtime-input-validation-live.json'),JSON.stringify(evidence,null,2));
 assert.equal(finished.status,'SUCCEEDED');assert.equal(duplicate.runId,accepted.runId);assert.equal(item.modelInvocations,1);assert.equal(item.turnsAfterCorrection,1);assert.equal(item.nextTurnAfterCorrection,2);
}
process.stdout.write(JSON.stringify({sessions:evidence.sessions},null,2)+'\n');
