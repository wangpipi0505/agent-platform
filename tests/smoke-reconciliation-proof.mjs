/** Real engine/skill/PG proof path. The external peer is a local protocol fixture. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';

const root=path.resolve(import.meta.dirname,'..'),base='http://127.0.0.1:18080';
const env=Object.fromEntries((await fs.readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/).filter(x=>x&&!x.startsWith('#')).map(x=>{const i=x.indexOf('=');return[x.slice(0,i),x.slice(i+1)];}));
const login=await fetch(base+'/auth/dev-token',{method:'POST'}).then(r=>r.json());assert.equal(login.code,'OK');
const user=login.data.accessToken,claims=JSON.parse(Buffer.from(user.split('.')[1],'base64url').toString());
function ticket(overrides){const body={...claims,roles:[...new Set([...claims.roles,'SERVICE'])],exp:Math.floor(Date.now()/1000)+300,...overrides};const signed=Buffer.from(JSON.stringify({alg:'HS256',typ:'JWT'})).toString('base64url')+'.'+Buffer.from(JSON.stringify(body)).toString('base64url');return signed+'.'+crypto.createHmac('sha256',env.AGENT_JWT_SECRET).update(signed).digest('base64url');}
const service=ticket({aud:'agent-platform-internal',token_use:'service'}),portalService=ticket({aud:'agent-platform',token_use:'user'});
async function request(url,body,token=user,origin=base){const response=await fetch(origin+url,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});const raw=await response.text();return {status:response.status,envelope:raw?JSON.parse(raw):{}};}
async function api(url,body,token=user,origin=base){const result=await request(url,body,token,origin);assert.equal(result.status,200,JSON.stringify(result));assert.equal(result.envelope.code,'OK',JSON.stringify(result));return result.envelope.data;}
function sql(query){const result=spawnSync(path.join(root,'.tools/postgresql-16.15/pgsql/bin/psql.exe'),['-h','127.0.0.1','-p','25432','-U','agent_v12','-d','agent_platform_dev_v12','-At','-v','ON_ERROR_STOP=1','-f','-'],{input:query,encoding:'utf8',env:{...process.env,PGPASSWORD:env.AGENT_DB_PASSWORD}});assert.equal(result.status,0,result.stderr);return result.stdout.trim();}
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
const sockets=new Set(),jobs=new Map();
const fixture=http.createServer(async(req,res)=>{
 const url=new URL(req.url,'http://127.0.0.1:28417');
 const send=data=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(data));};
 if(req.method==='POST'&&url.pathname==='/submit'){
  const key=req.headers['idempotency-key'];assert.equal(typeof key,'string');let raw='';for await(const chunk of req)raw+=chunk;assert.deepEqual(JSON.parse(raw),{message:'proof fixture'});
  let job=jobs.get(key);if(!job){job={id:'proof job /'+crypto.randomUUID(),key,submits:0,queries:0,state:'RUNNING'};jobs.set(key,job);}job.submits++;return send({jobId:job.id,idempotencyKey:key});
 }
 if(req.method==='GET'&&url.pathname.startsWith('/jobs/')){
  const job=[...jobs.values()].find(item=>item.id===decodeURIComponent(url.pathname.slice(6)));if(!job){res.statusCode=404;return send({error:'missing'});}job.queries++;return send({jobId:job.id,idempotencyKey:job.key,status:job.state});
 }
 res.statusCode=404;send({error:'unknown fixture route'});
});
fixture.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
await new Promise((resolve,reject)=>{fixture.once('error',reject);fixture.listen(28417,'127.0.0.1',resolve);});
const evidence={actualServicesAndPostgres:true,fixtureProviderOnly:true,developmentServiceJwt:true};
let runId;
try{
 const code='smoke_proof_'+crypto.randomUUID().replaceAll('-','').slice(0,10);
 await api('/admin/skills',{code,name:'不可变对账证明fixture',description:'仅本工程NOT_APPLIED协议证明，不代表真实供应商',type:'HTTP',endpoint:'http://127.0.0.1:28417/submit',invocationKind:'SUBMIT_QUERY',inputSchema:{type:'object',properties:{message:{type:'string'}},required:['message'],additionalProperties:false},outputSchema:{type:'object',properties:{value:{type:'string'}},required:['value']},execution:{readonly:true,sideEffect:false,idempotent:true,cancellable:false},guard:{roles:['EDITOR'],attributes:{}},timeoutMs:3000,reconciliation:{endpoint:'http://127.0.0.1:28417/jobs/{jobId}',method:'GET',donePath:'$.status',doneValue:'DONE',notAppliedValue:'NOT_APPLIED'}});
 const created=await api('/admin/skills/'+code+'/drafts/1/connection-test',{inputs:{message:'proof fixture'},requestId:crypto.randomUUID()});runId=created.runId;
 let state;
 for(let n=0;n<120;n++){state=await api('/admin/debug-runs/'+runId+'/status');if(state.status==='WAITING_EXTERNAL')break;assert.notEqual(state.status,'FAILED',JSON.stringify(state));await new Promise(resolve=>setTimeout(resolve,75));}
 assert.equal(state.status,'WAITING_EXTERNAL');const call=Number(state.controlledCallId||state.callId);assert(Number.isSafeInteger(call)&&call>0);
 const binding=JSON.parse(sql(`select json_build_object('key',idempotency_key,'job',external_job_id,'attempt',attempt_id,'sent',send_started) from skill.external_job_binding where tenant_id='local-v12' and call_id=${call};`));
 const job=jobs.get(binding.key);assert(job);assert.equal(job.submits,1);assert.equal(binding.sent,true);assert.equal(binding.job,job.id);job.state='NOT_APPLIED';
 const reconcileUrl='/internal/skill-facts/'+call+'/reconcile';
 const first=await api(reconcileUrl,{},service,'http://127.0.0.1:18083');
 assert.equal(first.status,'UNKNOWN');assert.equal(first.outcome,'NOT_APPLIED');assert.equal(first.retryBusinessAllowed,false);assert.match(first.proofRef,/^srp:\d+$/);assert.match(first.proofSha256,/^[a-f0-9]{64}$/);
 const proofUrl='/internal/skill-facts/'+call+'/reconciliation-proofs/'+binding.attempt;
 const proof=await api(proofUrl,undefined,service,'http://127.0.0.1:18083');
 assert.equal(proof.callId,call);assert.equal(proof.attemptId,binding.attempt);assert.equal(proof.runId,runId);assert.equal(proof.idempotencyKey,binding.key);assert.equal(proof.externalJobId,job.id);assert.equal(proof.outcome,'NOT_APPLIED');assert.equal(proof.retryBusinessAllowed,false);
 const {proofSha256,retryBusinessAllowed,...body}=proof;assert.equal(crypto.createHash('sha256').update(JSON.stringify(canonical(body))).digest('hex'),proofSha256);assert.equal(first.proofSha256,proofSha256);
 const repeated=await api(reconcileUrl,{},service,'http://127.0.0.1:18083');assert.equal(repeated.proofRef,first.proofRef);assert.equal(repeated.proofSha256,first.proofSha256);
 const denied=await request(proofUrl,undefined,portalService,'http://127.0.0.1:18083');assert.equal(denied.status,403);
 const foreign=await request(proofUrl,undefined,ticket({aud:'agent-platform-internal',token_use:'service',sub:'proof_other_owner'}),'http://127.0.0.1:18083');assert.equal(foreign.status,404);
 const final=await api('/admin/debug-runs/'+runId+'/status');assert.equal(final.status,'WAITING_EXTERNAL');assert.equal(job.submits,1);
 assert.equal(Number(sql(`select count(*) from skill.reconciliation_proof where tenant_id='local-v12' and call_id=${call};`)),1);
 assert.equal(sql(`select status from engine.tool_call where id=${call};`),'UNKNOWN');
 Object.assign(evidence,{skillCode:code,runId,callId:call,attemptId:binding.attempt,proofRef:first.proofRef,proofSha256,proofRows:1,submitCount:job.submits,queryCount:job.queries,status:final.status,retryBusinessAllowed:false,portalServiceStatus:denied.status,foreignOwnerStatus:foreign.status});
 await fs.writeFile(path.join(root,'.local/skill-reconciliation-proof-live.json'),JSON.stringify(evidence,null,2));process.stdout.write(JSON.stringify(evidence,null,2)+'\n');
}finally{
 if(runId)await request('/admin/debug-runs/'+runId+'/cancel',{}).catch(()=>{});
 fixture.close();for(const socket of sockets)socket.destroy();
}
