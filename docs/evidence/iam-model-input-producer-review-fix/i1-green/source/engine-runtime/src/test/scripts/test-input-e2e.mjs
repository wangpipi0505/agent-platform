// Test-only three-domain HTTP acceptance. No production process/JAR is restarted or replaced.
import { readFileSync, writeFileSync, mkdirSync, openSync, closeSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createServer as netServer } from 'node:net';
import { createServer } from 'node:http';
import { createHmac, randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

const root=process.cwd();
assert.equal(root.split(/[\\/]/).at(-1),'agent-platform');
const env={...process.env};
for(const line of readFileSync(join(root,'.env'),'utf8').split(/\r?\n/)) {
  const m=line.match(/^([^#=]+)=(.*)$/);if(m)env[m[1]]=m[2];
}
const database='agent_platform_test_input_test';
env.AGENT_DB_URL=`jdbc:postgresql://127.0.0.1:25432/${database}`;
const java=join(env.AGENT_JAVA_HOME||'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot','bin/java.exe');
const logDir=join(root,'.local/test-input-e2e');mkdirSync(logDir,{recursive:true});
const processes=[],fds=[];
const evidence={database,startedAt:new Date().toISOString(),processes:[],checks:[]};
async function freePort(){const s=netServer();await new Promise(r=>s.listen(0,'127.0.0.1',r));const p=s.address().port;await new Promise(r=>s.close(r));return p;}
const ports={app:await freePort(),engine:await freePort(),monitor:await freePort()};
const urls=Object.fromEntries(Object.entries(ports).map(([k,v])=>[k,`http://127.0.0.1:${v}`]));
const tenant=`test-input-e2e-${randomUUID()}`;
function token(owner='owner',tenantId=tenant,roles=['EDITOR']){
  const now=Math.floor(Date.now()/1000);
  const claims={iss:'agent-platform-v12',sub:owner,tenant_id:tenantId,aud:'agent-platform',iat:now,exp:now+600,jti:randomUUID(),token_use:'user',roles,attributes:{}};
  const b64=x=>Buffer.from(JSON.stringify(x)).toString('base64url');const signing=`${b64({alg:'HS256',typ:'JWT'})}.${b64(claims)}`;
  return `${signing}.${createHmac('sha256',env.AGENT_JWT_SECRET).update(signing).digest('base64url')}`;
}
const bearer=token();
async function request(domain,method,path,body,expected=200,auth=bearer){
  const r=await fetch(urls[domain]+path,{method,headers:{Authorization:`Bearer ${auth}`,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
  const text=await r.text();assert.equal(r.status,expected,`${method} ${path}: ${text}`);
  return text?JSON.parse(text).data:null;
}
function sql(statement){
  const p=spawnSync(join(root,'.tools/postgresql-16.15/pgsql/bin/psql.exe'),['-h','127.0.0.1','-p','25432','-U','agent_v12','-d',database,'-At','-v','ON_ERROR_STOP=1','-c',statement],{env:{...env,PGPASSWORD:env.AGENT_DB_PASSWORD},encoding:'utf8',windowsHide:true});
  assert.equal(p.status,0,p.stderr);return p.stdout.trim();
}
let dropNext=true,monitorCommits=0;
const proxy=createServer(async(req,res)=>{
  try {
    const chunks=[];for await(const chunk of req)chunks.push(chunk);const body=Buffer.concat(chunks);
    const claims=JSON.parse(Buffer.from(req.headers.authorization.split('.')[1],'base64url').toString());
    assert.equal(claims.sub,'owner');assert.equal(claims.tenant_id,tenant);assert.equal(claims.token_use,'service');assert.equal(claims.aud,'agent-platform-internal');assert.ok(claims.exp-claims.iat<=120);
    // A committed intent must already be visible to a separate connection before monitor HTTP.
    const payload=JSON.parse(body);assert.equal(sql(`select count(*) from engine.debug_input_promotion where tenant_id='${tenant}' and request_id='${payload.requestId}'`),'1');
    const upstream=await fetch(urls.monitor+req.url,{method:req.method,headers:{Authorization:req.headers.authorization,'Content-Type':'application/json'},body});const response=await upstream.text();
    if(upstream.status===200)monitorCommits++;
    if(dropNext && upstream.status===200){dropNext=false;req.socket.destroy();return;}
    res.writeHead(upstream.status,{'Content-Type':'application/json'});res.end(response);
  }catch(e){console.error('test proxy failure',e.message);res.writeHead(502);res.end('{}');}
});
await new Promise(r=>proxy.listen(0,'127.0.0.1',r));
const proxyUrl=`http://127.0.0.1:${proxy.address().port}`;
try {
  for(const [module,main,key] of [['app-management','cn.agentplatform.app.AppApplication','app'],['monitor','cn.agentplatform.monitor.MonitorApplication','monitor'],['engine-runtime','cn.agentplatform.engine.EngineApplication','engine']]) {
    const cp=join(root,module,'target/classes')+';'+readFileSync(join(root,module,'target/test-input-classpath.txt'),'utf8').trim();
    const args=['-Djava.io.tmpdir=V:/.local/tmp','-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Xmx256m','-cp',cp,main,`--server.port=${ports[key]}`,`--spring.datasource.url=${env.AGENT_DB_URL}`,`--agent.services.app=${urls.app}`,`--agent.services.engine=${urls.engine}`,`--agent.services.monitor=${key==='engine'?proxyUrl:urls.monitor}`,'--agent.services.skill=http://127.0.0.1:1','--agent.services.model=http://127.0.0.1:1'];
    const out=openSync(join(logDir,`${key}.log`),'w');fds.push(out);
    const process=spawn(java,args,{cwd:root,env,windowsHide:true,stdio:['ignore',out,out]});processes.push(process);
    evidence.processes.push({module,pid:process.pid,main,port:ports[key],database,args:args.filter((v,i)=>v!=='-cp'&&args[i-1]!=='-cp')});
  }
  writeFileSync(join(logDir,'processes.json'),JSON.stringify(evidence.processes,null,2));
  for(const key of ['app','monitor','engine']) {
    let ready=false;
    for(let i=0;i<100;i++){try{const r=await fetch(urls[key]+'/actuator/health',{signal:AbortSignal.timeout(500)});if(r.ok){ready=true;break;}}catch{} await new Promise(r=>setTimeout(r,200));}
    assert.ok(ready,`${key} failed readiness; inspect own log`);
  }
  let app=await request('app','POST','/admin/apps',{code:'demo',name:'Named input acceptance',type:'FLOW'});
  const definition={definitionVersion:'1.0',trigger:'TASK',entry:'start',inputs:[{name:'q',type:'STRING',required:true}],outputs:[{name:'answer',from:'${input.q}'}],nodes:[{id:'start',type:'START'},{id:'end',type:'END'}],edges:[{from:'start',to:'end'}],joins:[],executionRules:{parallel:false,maxParallel:1,cyclesForbidden:true}};
  app=await request('app','PUT','/admin/flows/demo',{draftRev:app.draftRev,definition,layout:{}});
  const saved=await request('engine','POST',`/admin/debug-inputs?appDraftId=${app.draftId}`,{name:'Safety regression',inputs:{q:'source'}});
  evidence.checks.push('real app draft metadata validates complete FLOW input via internal service ticket');
  const promotion={requestId:'ack-lost',revision:saved.revision,datasetVersion:'safe-v1',caseId:'safe',expected:{answer:'source'},safety:true};
  await request('engine','POST',`/admin/debug-inputs/${saved.id}/promote`,promotion,502);
  assert.equal(sql(`select count(*) from monitor.dataset where tenant_id='${tenant}' and dataset_version='safe-v1'`),'1');
  await request('engine','PUT',`/admin/debug-inputs/${saved.id}`,{revision:1,name:'Edited source',inputs:{q:'edited'}});
  const promoted=await request('engine','POST',`/admin/debug-inputs/${saved.id}/promote`,promotion);
  assert.deepEqual(promoted.cases[0].inputs,{q:'source'});assert.deepEqual(promoted.cases[0].expected,{answer:'source'});assert.equal(promoted.source.revision,1);
  assert.equal(sql(`select count(*) from monitor.dataset where tenant_id='${tenant}' and dataset_version='safe-v1'`),'1');
  evidence.checks.push('monitor commit followed by actual TCP response loss returns 502; edit source; same request recovers original revision and exactly one immutable dataset');
  await request('engine','POST',`/admin/debug-inputs/${saved.id}/promote`,{...promotion,expected:{answer:'changed'}},409);
  await request('engine','POST',`/admin/debug-inputs/${saved.id}/promote`,{...promotion,requestId:'different-request',revision:2},409);
  await request('engine','GET',`/admin/debug-inputs/${saved.id}`,undefined,404,token('other'));
  await request('engine','GET',`/admin/debug-inputs/${saved.id}`,undefined,404,token('owner','different-tenant'));
  await request('monitor','GET','/admin/evaluation-datasets/demo/safe-v1',undefined,404,token('other'));
  evidence.checks.push('conflicting request/version 409; owner and tenant isolation 404');
  const dataset=await request('monitor','GET','/admin/evaluation-datasets/demo/safe-v1');
  const evalBody={appCode:'demo',draftRev:app.draftRev,candidateSha256:app.candidateSha256,datasetVersion:dataset.datasetVersion,cases:dataset.cases};
  const report=await request('monitor','POST','/admin/evaluations',evalBody);
  assert.equal(report.status,'ACCEPTED',JSON.stringify(report));
  const run=await request('engine','GET',`/admin/runs/${report.results[0].runId}`);assert.equal(run.status,'SUCCEEDED');assert.deepEqual(run.output,{answer:'source'});
  assert.equal(sql(`select count(*) from engine.run where tenant_id='${tenant}' and trigger_type='EVALUATION'`),'1');
  evidence.checks.push('existing EvaluationService executes actual START→END engine graph and persists ACCEPTED report; actual output matches explicit expected');
  await request('monitor','POST','/admin/evaluations',{...evalBody,cases:[{...dataset.cases[0],expected:'tampered'}]},409);
  const nonSafety=await request('engine','POST',`/admin/debug-inputs/${saved.id}/promote`,{...promotion,revision:2,requestId:'non-safety',datasetVersion:'non-safety-v1',safety:false});
  await request('monitor','POST','/admin/evaluations',{...evalBody,datasetVersion:nonSafety.datasetVersion,cases:nonSafety.cases},400);
  const wrong=await request('engine','POST',`/admin/debug-inputs/${saved.id}/promote`,{...promotion,revision:2,requestId:'wrong-expected',datasetVersion:'wrong-v1'});
  const rejected=await request('monitor','POST','/admin/evaluations',{...evalBody,datasetVersion:wrong.datasetVersion,cases:wrong.cases});assert.equal(rejected.status,'REJECTED');
  evidence.checks.push('same dataset modified content remains 409; missing safety remains 400; wrong expected remains REJECTED');
  evidence.reportId=report.id;evidence.runId=report.results[0].runId;evidence.monitorAcceptedRequests=monitorCommits;evidence.result='PASS';
  console.log(JSON.stringify(evidence,null,2));
}catch(e){evidence.result='FAIL';evidence.error=e.stack;console.error(e.stack);process.exitCode=1;}
finally{
  for(const child of processes)child.kill();
  await Promise.all(processes.map(child=>child.exitCode!==null?Promise.resolve():new Promise(r=>{child.once('exit',r);setTimeout(r,5000);})));for(const fd of fds)closeSync(fd);
  await new Promise(r=>proxy.close(r));evidence.finishedAt=new Date().toISOString();evidence.cleanedUp=processes.every(p=>p.exitCode!==null||p.signalCode!==null);
  writeFileSync(join(root,'docs/evidence/test-input-e2e.json'),JSON.stringify(evidence,null,2));
}
