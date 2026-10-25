/** Real HTTP / PostgreSQL / Redis7 proof using two isolated engine JVMs.
 * The timed model is a protocol fixture, never a production model substitute. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..'), base='http://127.0.0.1:18080';
const env=Object.fromEntries((await fs.readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/).filter(x=>x&&!x.startsWith('#')).map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1)];}));
let token;
async function login(){const r=await fetch(base+'/auth/dev-token',{method:'POST'});assert.equal(r.status,200);token=(await r.json()).data.accessToken;}
await login();
async function api(url,method='GET',body){const r=await fetch(base+url,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});const e=await r.json();assert.equal(r.status,200,`${url}: ${e.message}`);return e.data;}
function sql(text){const p=spawnSync(path.join(root,'.tools/postgresql-16.15/pgsql/bin/psql.exe'),['-h','127.0.0.1','-p','25432','-U',env.AGENT_DB_USER||'agent_v12','-d','agent_platform_dev_v12','-At','-v','ON_ERROR_STOP=1','-f','-'],{input:text,encoding:'utf8',env:{...process.env,PGPASSWORD:env.AGENT_DB_PASSWORD}});assert.equal(p.status,0,p.stderr);return p.stdout.trim();}
function owner(id){assert(/^r_[a-f0-9]+$/.test(id));return sql(`select split_part(n.lease_owner,'_',1) from engine.node_instance n join engine.run r on r.id=n.run_id where r.public_id='${id}' and n.status='SUCCEEDED' order by n.id limit 1;`);}
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(id,test){for(let n=0;n<120;n++){const s=await api('/admin/debug-runs/'+id+'/status');if(test(s))return s;await pause(75);}throw Error('Run state timeout: '+id);}
if(process.argv[2]==='prepare'){
  const created=await api('/admin/debug-runs','POST',{appCode:'smoke_chat_bff1adb366',mode:'FULL',inputs:{message:'单实例所有者校准'}});
  const s=await until(created.runId,s=>['SUCCEEDED','FAILED'].includes(s.status));assert.equal(s.status,'SUCCEEDED');
  const primaryOwner=owner(created.runId);assert(primaryOwner);
  await fs.writeFile(path.join(root,'.local/engine-stream-primary.json'),JSON.stringify({runId:created.runId,primaryOwner}));
  process.stdout.write(JSON.stringify({phase:'prepare',runId:created.runId,primaryOwner})+'\n');
  process.exit(0);
}
assert.equal(process.argv[2],'verify','Run prepare with only the primary engine before starting the replica');
const calibration=JSON.parse(await fs.readFile(path.join(root,'.local/engine-stream-primary.json'),'utf8'));
const frames=(response,onFrame)=> (async()=>{
  assert.equal(response.status,200);assert.match(response.headers.get('content-type'),/^text\/event-stream/);
  let buffer='';const decoder=new TextDecoder();
  for await(const part of response.body){buffer+=decoder.decode(part,{stream:true}).replaceAll('\r\n','\n');let at;while((at=buffer.indexOf('\n\n'))>=0){const raw=buffer.slice(0,at);buffer=buffer.slice(at+2);let id,event='message',data=[];for(const line of raw.split('\n')){if(line.startsWith('id:'))id=Number(line.slice(3).trim());if(line.startsWith('event:'))event=line.slice(6).trim();if(line.startsWith('data:'))data.push(line.slice(5).trimStart());}if(data.length)onFrame({event,id,data:JSON.parse(data.join('\n'))});}}
})();
async function open(port,id,cursor=0){const events=[],abort=new AbortController();let readyResolve;const ready=new Promise(r=>readyResolve=r);const response=await fetch(`http://127.0.0.1:${port}/admin/debug-runs/${id}/events`,{headers:{Authorization:'Bearer '+token,'Last-Event-ID':String(cursor)},signal:abort.signal});const done=frames(response,f=>{events.push(f);if(f.event==='stream_status'){assert.equal(f.data.chunkAvailable,true);readyResolve();}});return {port,events,ready,done,abort};}
const sockets=new Set();let modelRequests=0;
const model=http.createServer(async(req,res)=>{
 if(req.url==='/v1/models'){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({data:[{id:'gpt-4'}]}));}
 assert.equal(req.url,'/v1/chat/completions');let body='';for await(const b of req)body+=b;const request=JSON.parse(body);modelRequests++;
 const texts=['跨进程','观察完成'];
 if(!request.stream){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({id:'fixture',object:'chat.completion',created:1,model:'gpt-4',choices:[{index:0,message:{role:'assistant',content:texts.join('')},finish_reason:'stop'}],usage:{prompt_tokens:8,completion_tokens:4,total_tokens:12}}));}
 res.setHeader('Content-Type','text/event-stream');res.flushHeaders();
 for(const text of texts){res.write('data: '+JSON.stringify({id:'fixture-stream',object:'chat.completion.chunk',created:1,model:'gpt-4',choices:[{index:0,delta:{content:text},finish_reason:null}]})+'\n\n');await pause(650);}
 res.write('data: '+JSON.stringify({id:'fixture-stream',object:'chat.completion.chunk',created:1,model:'gpt-4',choices:[{index:0,delta:{},finish_reason:'stop'}]})+'\n\n');
 res.end('data: '+JSON.stringify({id:'fixture-stream',object:'chat.completion.chunk',created:1,model:'gpt-4',choices:[],usage:{prompt_tokens:8,completion_tokens:4,total_tokens:12}})+'\n\ndata: [DONE]\n\n');
});
model.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
await new Promise((resolve,reject)=>{model.once('error',reject);model.listen(28413,'127.0.0.1',resolve);});
let observers=[];
try{
 const suffix=crypto.randomUUID().replaceAll('-','').slice(0,10);
 const provider=await api('/admin/model-providers','POST',{code:'stream_fixture_'+suffix,name:'跨进程流协议fixture',baseUrl:'http://127.0.0.1:28413/v1',trustLabels:['LOCAL']});
 assert.equal((await api('/admin/model-providers/'+provider.id+'/test','POST',{})).ok,true);
 const entry=await api('/admin/model-entries','POST',{providerId:provider.id,modelName:'gpt-4',displayName:'定时流协议fixture',contextWindow:8192,maxOutputTokens:256,capabilities:['chat']});
 const code='smoke_stream_'+suffix;let app=await api('/admin/apps','POST',{code,name:'双进程流观察测试',type:'CHAT',description:'独立协议fixture，不能作为真实模型能力验收'});
 await api('/admin/apps/'+code+'/config','PUT',{draftRev:app.draftRev,config:{modelBinding:{modelEntryId:entry.id},inputs:[{name:'message',type:'STRING',required:true}],systemPrompt:'双进程流观察测试',tools:[],output:{maxTokens:128},guard:{roles:['EDITOR'],attributes:{}}}});
 const created=await api('/admin/debug-runs','POST',{appCode:code,mode:'STEP',inputs:{message:'双进程共享Redis与PG'}});
 const paused=await until(created.runId,s=>s.status==='WAITING_STEP');assert(paused.paused);
 observers=[await open(18082,created.runId),await open(18086,created.runId)];
 await Promise.race([Promise.all(observers.map(o=>o.ready)),pause(5000).then(()=>{throw Error('Redis subscriptions did not become ready');})]);
 const requestsBeforeResume=modelRequests;
 await api('/admin/debug-runs/'+created.runId+'/resume','POST',{...paused.paused,action:'RUN_TO_END',requestId:crypto.randomUUID()});
 await Promise.race([Promise.all(observers.map(o=>o.done)),pause(15000).then(()=>{throw Error('Streams did not terminate');})]);
 const final=await until(created.runId,s=>['SUCCEEDED','FAILED'].includes(s.status));assert.equal(final.status,'SUCCEEDED');assert.equal(final.output.text,'跨进程观察完成');assert.equal(modelRequests-requestsBeforeResume,1);
 const producerOwner=owner(created.runId),primaryProduced=producerOwner===calibration.primaryOwner;
 const persisted=JSON.parse(sql(`select coalesce(json_agg(json_build_object('id',e.seq,'event',e.type) order by e.seq),'[]') from engine.run_event e join engine.run r on r.id=e.run_id where r.public_id='${created.runId}';`));
 const facts=observers.map(o=>{
   const facts=o.events.filter(f=>f.id!==undefined),chunks=o.events.filter(f=>f.event==='chunk');
   assert.deepEqual(facts.map(f=>({id:f.id,event:f.event})),persisted);assert.equal(facts.at(-1).id,final.eventsWatermark);
   assert.equal(chunks.map(f=>f.data.text).join(''),'跨进程观察完成');assert(chunks.every(f=>f.id===undefined&&f.data.seq===undefined));
   return {port:o.port,factIds:facts.map(f=>f.id),chunks:chunks.map(f=>f.data.text),chunkIdsAbsent:true};
 });
 const replayPort=primaryProduced?18086:18082,cursor=Math.max(0,final.eventsWatermark-2),replay=await open(replayPort,created.runId,cursor);observers.push(replay);await replay.done;
 assert.deepEqual(replay.events.filter(f=>f.id!==undefined).map(f=>({id:f.id,event:f.event})),persisted.filter(f=>f.id>cursor));assert.equal(replay.events.filter(f=>f.event==='chunk').length,0);
 const proof={fixtureModelOnly:true,actualTwoJvms:true,actualRedis7:true,runId:created.runId,appCode:code,primaryOwner:calibration.primaryOwner,producerOwner,producerPort:primaryProduced?18082:18086,remoteObserverPort:replayPort,modelRequestsBeforeResume:requestsBeforeResume,modelRequestsForRun:modelRequests-requestsBeforeResume,finalStatus:final.status,finalText:final.output.text,eventsWatermark:final.eventsWatermark,observers:facts,reconnect:{port:replayPort,cursor,factIds:replay.events.filter(f=>f.id!==undefined).map(f=>f.id),chunkReplay:0}};
 await fs.writeFile(path.join(root,'.local/engine-stream-replicas-live.json'),JSON.stringify(proof,null,2));process.stdout.write(JSON.stringify(proof,null,2)+'\n');
} finally {for(const o of observers)o.abort.abort();model.close();for(const socket of sockets)socket.destroy();}
