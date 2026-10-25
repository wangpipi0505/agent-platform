/** Actual engine/skill HTTP path; provider below is an isolated asynchronous protocol fixture. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..'),base='http://127.0.0.1:18080';
const env=Object.fromEntries((await fs.readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/).filter(x=>x&&!x.startsWith('#')).map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1)];}));
const auth=await fetch(base+'/auth/dev-token',{method:'POST'}).then(r=>r.json());assert.equal(auth.code,'OK');const userToken=auth.data.accessToken;
// Local fixture identity retains the verified development principal. This is not production IAM.
const claims=JSON.parse(Buffer.from(userToken.split('.')[1],'base64url').toString());claims.roles=[...new Set([...(claims.roles||[]),'SERVICE'])];claims.aud='agent-platform-internal';claims.token_use='service';claims.exp=Math.floor(Date.now()/1000)+300;
const head=Buffer.from(JSON.stringify({alg:'HS256',typ:'JWT'})).toString('base64url'),payload=Buffer.from(JSON.stringify(claims)).toString('base64url'),signing=head+'.'+payload;
const serviceToken=signing+'.'+crypto.createHmac('sha256',env.AGENT_JWT_SECRET).update(signing).digest('base64url');
async function api(url,method='GET',body,token=userToken,origin=base){const r=await fetch(origin+url,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});const e=await r.json();assert.equal(r.status,200,`${url}: ${JSON.stringify(e)}`);assert.equal(e.code,'OK');return e.data;}
function sql(query){const p=spawnSync(path.join(root,'.tools/postgresql-16.15/pgsql/bin/psql.exe'),['-h','127.0.0.1','-p','25432','-U',env.AGENT_DB_USER||'agent_v12','-d','agent_platform_dev_v12','-At','-v','ON_ERROR_STOP=1','-f','-'],{input:query,encoding:'utf8',env:{...process.env,PGPASSWORD:env.AGENT_DB_PASSWORD}});assert.equal(p.status,0,p.stderr);return p.stdout.trim();}
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function state(id,predicate){for(let n=0;n<120;n++){const s=await api('/admin/debug-runs/'+id+'/status');if(predicate(s))return s;if(s.status==='FAILED')throw Error('Run failed: '+JSON.stringify(s));await pause(75);}throw Error('Run timeout '+id);}
const jobs=new Map(),sockets=new Set();
const fixture=http.createServer(async(req,res)=>{
 const url=new URL(req.url,'http://127.0.0.1:28415'),send=data=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(data));};
 if(req.method==='POST'&&url.pathname.startsWith('/submit/')){
  const mode=url.pathname.slice('/submit/'.length),key=req.headers['idempotency-key'];assert(key);let body='';for await(const b of req)body+=b;assert.deepEqual(JSON.parse(body),{message:'async protocol fixture'});
  let job=jobs.get(key);if(!job){job={id:'job /'+crypto.randomUUID(),key,mode,submits:0,queries:0,done:mode!=='pending'};jobs.set(key,job);}job.submits++;
  if(mode==='ack-lost'){req.socket.destroy();return;}send({jobId:job.id,idempotencyKey:key});return;
 }
 if(req.method==='GET'&&(url.pathname.startsWith('/jobs/')||url.pathname.startsWith('/lookup/'))){
  const lookup=url.pathname.startsWith('/lookup/'),value=decodeURIComponent(url.pathname.slice(lookup?8:6)),job=lookup?jobs.get(value):[...jobs.values()].find(j=>j.id===value);if(!job){res.statusCode=404;return send({error:'missing'});}job.queries++;send({jobId:job.id,idempotencyKey:job.key,status:job.done?'DONE':'RUNNING',result:{value:'原任务已完成'}});return;
 }
 res.statusCode=404;send({error:'unknown fixture route'});
});
fixture.on('connection',s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));});await new Promise((resolve,reject)=>{fixture.once('error',reject);fixture.listen(28415,'127.0.0.1',resolve);});
const evidence=[];
try{
 for(const mode of ['done','pending','ack-lost']){
  const suffix=crypto.randomUUID().replaceAll('-','').slice(0,10),code='smoke_async_'+mode.replace('-','_')+'_'+suffix;
  const skill=await api('/admin/skills','POST',{code,name:'异步HTTP '+mode,description:'隔离协议fixture，不代表供应商验收',type:'HTTP',endpoint:'http://127.0.0.1:28415/submit/'+mode,invocationKind:'SUBMIT_QUERY',inputSchema:{type:'object',properties:{message:{type:'string'}},required:['message'],additionalProperties:false},outputSchema:{type:'object',properties:{value:{type:'string'}},required:['value'],additionalProperties:false},execution:{readonly:true,sideEffect:false,idempotent:true,cancellable:false},guard:{roles:['EDITOR'],attributes:{}},timeoutMs:3000,reconciliation:{endpoint:'http://127.0.0.1:28415/jobs/{jobId}',lookupEndpoint:'http://127.0.0.1:28415/lookup/{idempotencyKey}',method:'GET',donePath:'$.status',doneValue:'DONE'}});
  const test=await api('/admin/skills/'+code+'/drafts/1/connection-test','POST',{inputs:{message:'async protocol fixture'},requestId:crypto.randomUUID()});assert(test.runId);
  let s=await state(test.runId,s=>['SUCCEEDED','WAITING_EXTERNAL'].includes(s.status));const call=Number(s.controlledCallId||s.callId);assert(Number.isSafeInteger(call)&&call>0);
  const binding=JSON.parse(sql(`select json_build_object('key',idempotency_key,'job',external_job_id,'attempt',attempt_id,'runId',run_id,'sent',send_started,'status',status) from skill.external_job_binding where tenant_id='local-v12' and call_id=${call};`));assert.equal(binding.runId,test.runId);assert.equal(binding.sent,true);const job=jobs.get(binding.key);assert(job);assert.equal(job.submits,1);
  let reconciliation;
  if(mode!=='done'){
   assert.equal(s.status,'WAITING_EXTERNAL');assert.equal(sql(`select status from engine.tool_call where id=${call};`),'UNKNOWN');
   const denied=await fetch('http://127.0.0.1:18083/internal/skill-facts/'+call+'/reconcile',{method:'POST',headers:{Authorization:'Bearer '+userToken,'Content-Type':'application/json'},body:'{}'});assert.equal(denied.status,403);
   job.done=true;reconciliation=await api('/internal/skill-facts/'+call+'/reconcile','POST',{},serviceToken,'http://127.0.0.1:18083');
   s=await state(test.runId,s=>s.status==='SUCCEEDED');assert.equal(job.submits,1);
  }
  assert.equal(s.status,'SUCCEEDED');const proof=JSON.parse(sql(`select json_build_object('skillStatus',f.status,'value',f.result->'structured'->>'value','callback',f.callback_state,'engineStatus',t.status,'logicalCommits',(select count(*) from engine.run_event e where e.run_id=t.run_id and e.type='LOGIC_RESULT_COMMITTED' and e.payload->>'nodeInstanceId'=t.node_instance_id::text)) from skill.execution_fact f join engine.tool_call t on t.id=f.call_id where f.call_id=${call} and f.tenant_id='local-v12';`));assert.equal(proof.skillStatus,'SUCCEEDED');assert.equal(proof.engineStatus,'SUCCEEDED');assert.equal(proof.value,'原任务已完成');assert.equal(proof.logicalCommits,1);
  const rechecked=await api('/admin/skills/'+code+'/drafts/1/connection-test','POST',{controlledCallId:call});assert.equal(rechecked.ok,true);
  evidence.push({mode,skillCode:code,skillVersionId:skill.skillVersionId,runId:test.runId,callId:call,submitCount:job.submits,queryCount:job.queries,status:s.status,reconciliation:reconciliation?.status,proof,connectionAccepted:rechecked.ok});
 }
 const result={actualEngineAndSkillProcesses:true,actualPostgres:true,fixtureProviderOnly:true,localDevelopmentDelegatedJwt:true,results:evidence};await fs.writeFile(path.join(root,'.local/skill-http-async-live.json'),JSON.stringify(result,null,2));process.stdout.write(JSON.stringify(result,null,2)+'\n');
}finally{fixture.close();for(const s of sockets)s.destroy();}
