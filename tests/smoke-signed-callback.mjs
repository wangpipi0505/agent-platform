/** Dedicated local HMAC fixture + actual engine/skill/outbox; never a production relay. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..'),base='http://127.0.0.1:18080';
const env=Object.fromEntries((await fs.readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/).filter(x=>x&&!x.startsWith('#')).map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1)];}));
const login=await fetch(base+'/auth/dev-token',{method:'POST'}).then(r=>r.json());assert.equal(login.code,'OK');const userToken=login.data.accessToken;
const claims=JSON.parse(Buffer.from(userToken.split('.')[1],'base64url').toString());claims.roles=[...new Set([...(claims.roles||[]),'SERVICE'])];claims.aud='agent-platform-internal';claims.token_use='service';claims.exp=Math.floor(Date.now()/1000)+300;
const signing=Buffer.from(JSON.stringify({alg:'HS256',typ:'JWT'})).toString('base64url')+'.'+Buffer.from(JSON.stringify(claims)).toString('base64url');
const serviceToken=signing+'.'+crypto.createHmac('sha256',env.AGENT_JWT_SECRET).update(signing).digest('base64url');
// Fixture-only provisioning of the existing vault record format. No real integration key is used
// or passed to a background helper; generated cleartext exists only in this local test process.
const secret=crypto.randomBytes(32).toString('hex'),vaultId=crypto.randomUUID(),nonce=crypto.randomBytes(12),cipher=crypto.createCipheriv('aes-256-gcm',crypto.createHash('sha256').update(env.AGENT_MASTER_KEY).digest(),nonce);
const encrypted=Buffer.concat([cipher.update(JSON.stringify({tenantId:claims.tenant_id,value:secret}),'utf8'),cipher.final()]);
await fs.mkdir(env.AGENT_SECRET_DIR,{recursive:true});await fs.writeFile(path.join(env.AGENT_SECRET_DIR,vaultId+'.secret'),Buffer.concat([nonce,encrypted,cipher.getAuthTag()]),{flag:'wx'});
async function request(url,method='GET',body,token=userToken,origin=base){const r=await fetch(origin+url,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});const e=await r.json();assert.equal(r.status,200,`${url}: ${JSON.stringify(e)}`);assert.equal(e.code,'OK');return e.data;}
function sql(query){const p=spawnSync(path.join(root,'.tools/postgresql-16.15/pgsql/bin/psql.exe'),['-h','127.0.0.1','-p','25432','-U',env.AGENT_DB_USER||'agent_v12','-d','agent_platform_dev_v12','-At','-v','ON_ERROR_STOP=1','-f','-'],{input:query,encoding:'utf8',env:{...process.env,PGPASSWORD:env.AGENT_DB_PASSWORD}});assert.equal(p.status,0,p.stderr);return p.stdout.trim();}
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function state(id,predicate){for(let n=0;n<150;n++){const s=await request('/admin/debug-runs/'+id+'/status');if(predicate(s))return s;await pause(75);}throw Error('Run timeout '+id);}
function bound(call){assert(Number.isSafeInteger(call)&&call>0);return JSON.parse(sql(`select json_build_object('runId',run_id,'attemptId',attempt_id,'callId',call_id,'skillVersionId',skill_version_id,'idempotencyKey',idempotency_key,'jobId',external_job_id,'sent',send_started) from skill.external_job_binding where tenant_id='local-v12' and call_id=${call};`));}
async function callback(body,expected=200,token=serviceToken,badSignature=false){const raw=typeof body==='string'?body:JSON.stringify(body),timestamp=String(Math.floor(Date.now()/1000)),signature=crypto.createHmac('sha256',secret).update(timestamp+'\n').update(raw).digest('hex');const id=JSON.parse(raw).callId;const r=await fetch('http://127.0.0.1:18083/internal/skill-jobs/'+id+'/callback',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json','X-Callback-Timestamp':timestamp,'X-Callback-Signature':badSignature?'0'.repeat(64):signature},body:raw});const text=await r.text();assert.equal(r.status,expected,`callback ${id}: ${text}`);const e=text?JSON.parse(text):{};return e.data;}
const jobs=new Map(),sockets=new Set();
const fixture=http.createServer(async(req,res)=>{
 try{assert.equal(req.method,'POST');const mode=req.url.slice('/submit/'.length),key=req.headers['idempotency-key'];assert(key);let raw='';for await(const b of req)raw+=b;assert.deepEqual(JSON.parse(raw),{message:'callback fixture'});let job=jobs.get(key);if(!job){let release;job={id:'job-'+crypto.randomUUID(),key,mode,submits:0,ack:new Promise(r=>release=r),release:()=>release()};jobs.set(key,job);}job.submits++;if(mode==='early')await job.ack;res.setHeader('Content-Type','application/json');res.end(JSON.stringify({jobId:job.id,idempotencyKey:key}));}
 catch(error){res.statusCode=500;res.end('{}');console.error(error.message);}
});
fixture.on('connection',s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));});await new Promise((resolve,reject)=>{fixture.once('error',reject);fixture.listen(28416,'127.0.0.1',resolve);});
const results=[],createdRuns=[];
try{
 for(const mode of ['success','failed','early','cancelled']){
  const code='smoke_callback_'+mode+'_'+crypto.randomUUID().replaceAll('-','').slice(0,8);
  const skill=await request('/admin/skills','POST',{code,name:'签名回调 '+mode,description:'本工程协议fixture，非真实外部供应商',type:'HTTP',endpoint:'http://127.0.0.1:28416/submit/'+mode,invocationKind:'CALLBACK',callback:{secretRef:'vault:'+vaultId},inputSchema:{type:'object',properties:{message:{type:'string'}},required:['message'],additionalProperties:false},outputSchema:{type:'object',properties:{value:{type:'string'}},required:['value'],additionalProperties:false},execution:{readonly:true,sideEffect:false,idempotent:true,cancellable:false},guard:{roles:['EDITOR'],attributes:{}},timeoutMs:10000});
  assert.equal(skill.callback.secretConfigured,true);assert.equal(JSON.stringify(skill).includes(vaultId),false);
  const test=await request('/admin/skills/'+code+'/drafts/1/connection-test','POST',{inputs:{message:'callback fixture'},requestId:crypto.randomUUID()});assert(test.runId);
  createdRuns.push(test.runId);
  let current=await state(test.runId,s=>Number(s.controlledCallId||s.callId)>0),call=Number(current.controlledCallId||current.callId),binding;
  for(let n=0;n<100;n++){try{binding=bound(call);if(binding.sent&&jobs.has(binding.idempotencyKey))break;}catch{}await pause(20);}assert(binding?.sent);const job=jobs.get(binding.idempotencyKey);assert(job);
  if(mode!=='early'){current=await state(test.runId,s=>s.status==='WAITING_EXTERNAL');binding=bound(call);assert.equal(binding.jobId,job.id);}
  const body={eventId:crypto.randomUUID(),runId:binding.runId,callId:call,attemptId:binding.attemptId,skillVersionId:binding.skillVersionId,idempotencyKey:binding.idempotencyKey,jobId:job.id,status:mode==='failed'?'FAILED':'SUCCEEDED',...(mode==='failed'?{errorSemantic:'QUOTA'}:{result:{value:'签名回调完成'}})};
  if(mode==='cancelled')await request('/admin/debug-runs/'+test.runId+'/cancel','POST',{});
  if(mode==='early'){await callback(body,409);assert.equal(sql(`select outcome from skill.callback_receipt where tenant_id='local-v12' and call_id=${call};`),'WAITING_BINDING');job.release();await state(test.runId,s=>s.status==='WAITING_EXTERNAL');}
  if(mode==='success'){await callback(body,403,userToken);await callback(body,401,serviceToken,true);assert.equal(sql(`select count(*) from skill.callback_receipt where tenant_id='local-v12' and call_id=${call};`),'0');}
  let ack=await callback(body);assert.equal(Object.hasOwn(ack,'logicalAccepted'),false);assert.equal(typeof ack.deliveryAccepted,'boolean');assert.equal(ack.outcome,mode==='cancelled'?'LATE':'STORED');
  if(mode!=='cancelled'){current=await state(test.runId,s=>s.status===(mode==='failed'?'FAILED':'SUCCEEDED'));ack=await callback(body);await callback({...body,result:{value:'冲突内容'}},409);}
  else current=await state(test.runId,s=>s.status==='CANCELLED');
  const facts=JSON.parse(sql(`select json_build_object('status',f.status,'structured',f.result->'structured','semantic',f.result->'error'->>'semantic','callbackState',f.callback_state,'receipts',(select count(*) from skill.callback_receipt c where c.tenant_id=f.tenant_id and c.call_id=f.call_id),'verifiedRecoveryAdoptions',(select count(*) from engine.attempt_note a where a.kind='VERIFIED_FACT_ADOPTED' and a.payload->>'callId'=f.call_id::text and a.payload->>'factId'=concat('sf:',f.call_id,':',f.attempt_id)),'logicalCommits',(select count(*) from engine.run_event e join engine.tool_call t on t.run_id=e.run_id where t.id=f.call_id and e.type='LOGIC_RESULT_COMMITTED' and e.payload->>'nodeInstanceId'=t.node_instance_id::text)) from skill.execution_fact f where f.tenant_id='local-v12' and f.call_id=${call};`));
  assert.equal(job.submits,1);assert.equal(facts.receipts,1);assert.equal(facts.logicalCommits,['success','early'].includes(mode)?1:0);
  assert.equal(facts.status,mode==='failed'?'FAILED':mode==='cancelled'?'UNKNOWN':'SUCCEEDED');if(mode==='failed'){assert.equal(facts.structured,null);assert.equal(facts.semantic,'QUOTA');}if(mode==='cancelled')assert.equal(ack.deliveryAccepted,false);
  // W3 may adopt the durable proof first. A later delivery is then LATE; it is not evidence
  // against the authoritative engine adoption, which is checked independently by fact identity.
  if(mode!=='cancelled'&&!ack.deliveryAccepted)assert.equal(facts.verifiedRecoveryAdoptions,1);
  results.push({mode,skillCode:code,runId:test.runId,callId:call,submitCount:job.submits,status:current.status,receipt:ack.outcome,deliveryAccepted:ack.deliveryAccepted,proof:facts});
 }
 const evidence={actualEngineSkillAndPostgres:true,fixtureProviderOnly:true,developmentServiceJwt:true,generatedVaultFixtureOnly:true,results};await fs.writeFile(path.join(root,'.local/signed-callback-live.json'),JSON.stringify(evidence,null,2));process.stdout.write(JSON.stringify(evidence,null,2)+'\n');
}finally{for(const job of jobs.values())job.release();fixture.close();for(const s of sockets)s.destroy();for(const id of createdRuns){try{const s=await request('/admin/debug-runs/'+id+'/status');if(!['SUCCEEDED','FAILED','CANCELLED'].includes(s.status))await request('/admin/debug-runs/'+id+'/cancel','POST',{});}catch{process.stderr.write('Own fixture run cleanup needs inspection: '+id+'\n');}}}
