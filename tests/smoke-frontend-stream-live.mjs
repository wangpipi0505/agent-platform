/** Dedicated timed protocol fixture. This does not validate a real model's capability. */
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..'), base='http://127.0.0.1:18080';
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const shortInput='frontend-live-evaluation-known-input';
const shortText='短评测协议fixture完成';
const chunks=['长任务验证：','第一段已经到达；','第二段持续执行；','第三段跨越断流；','第四段恢复观察；','第五段未来分片；','第六段完整结束。'];
const chunkTimes=[1000,13500,26000,38500,51000,60000,65000];
const longText=chunks.join('');
const metrics={models:0,nonStream:0,evaluation:0,long:[],other:0};
const sockets=new Set();
const fixture=http.createServer(async(req,res)=>{
  try {
    if(req.url==='/v1/models'){metrics.models++;res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({data:[{id:'gpt-4'}]}));}
    assert.equal(req.url,'/v1/chat/completions');let raw='';for await(const p of req)raw+=p;
    const request=JSON.parse(raw), input=request.messages?.map(m=>m.content).join('\n')||'';
    const evaluation=input.includes(shortInput), long=input.includes('frontend-live-long-');
    if(evaluation)metrics.evaluation++;else if(!long)metrics.other++;
    const text=evaluation?shortText:long?longText:'模型连通协议fixture完成';
    if(!request.stream){metrics.nonStream++;res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({id:'fixture',object:'chat.completion',created:1,model:'gpt-4',choices:[{index:0,message:{role:'assistant',content:text},finish_reason:'stop'}],usage:{prompt_tokens:8,completion_tokens:4,total_tokens:12}}));}
    const timing=long?{start:Date.now(),chunkMs:[],finishMs:null}:null;if(timing)metrics.long.push(timing);
    res.setHeader('Content-Type','text/event-stream');res.flushHeaders();
    const delta=t=>res.write('data: '+JSON.stringify({id:'fixture-stream',object:'chat.completion.chunk',created:1,model:'gpt-4',choices:[{index:0,delta:{content:t},finish_reason:null}]})+'\n\n');
    if(long){for(let i=0;i<chunks.length;i++){await pause(Math.max(0,chunkTimes[i]-(Date.now()-timing.start)));delta(chunks[i]);timing.chunkMs.push(Date.now()-timing.start);}}
    else delta(text);
    res.write('data: '+JSON.stringify({id:'fixture-stream',object:'chat.completion.chunk',created:1,model:'gpt-4',choices:[{index:0,delta:{},finish_reason:'stop'}]})+'\n\n');
    res.end('data: '+JSON.stringify({id:'fixture-stream',object:'chat.completion.chunk',created:1,model:'gpt-4',choices:[],usage:{prompt_tokens:8,completion_tokens:4,total_tokens:12}})+'\n\ndata: [DONE]\n\n');
    if(timing)timing.finishMs=Date.now()-timing.start;
  }catch(error){res.statusCode=500;res.end('fixture error');console.error(error.message);}
});
fixture.on('connection',s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));});
await new Promise((resolve,reject)=>{fixture.once('error',reject);fixture.listen(28414,'127.0.0.1',resolve);});
try {
 const login=await fetch(base+'/auth/dev-token',{method:'POST'}).then(r=>r.json());assert.equal(login.code,'OK');
 async function api(url,method='GET',body){const r=await fetch(base+url,{method,headers:{Authorization:'Bearer '+login.data.accessToken,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(90000)});const e=await r.json();assert.equal(r.status,200,url+': '+e.message);assert.equal(e.code,'OK');return e.data;}
 const suffix=crypto.randomUUID().replaceAll('-','').slice(0,10);
 const provider=await api('/admin/model-providers','POST',{code:'long_fixture_'+suffix,name:'长流协议fixture',baseUrl:'http://127.0.0.1:28414/v1',trustLabels:['LOCAL']});
 const testBefore=structuredClone(metrics);assert.equal((await api('/admin/model-providers/'+provider.id+'/test','POST',{})).ok,true);const testAfter=structuredClone(metrics);
 const entry=await api('/admin/model-entries','POST',{providerId:provider.id,modelName:'gpt-4',displayName:'65秒分片协议fixture',contextWindow:8192,maxOutputTokens:512,capabilities:['chat']});
 const code='smoke_long_'+suffix;
 let app=await api('/admin/apps','POST',{code,name:'前端55秒断流恢复协议测试',type:'CHAT',description:'仅协议fixture验收前端恢复，非真实模型能力验收'});
 app=await api('/admin/apps/'+code+'/config','PUT',{draftRev:app.draftRev,config:{modelBinding:{modelEntryId:entry.id},inputs:[{name:'message',type:'STRING',required:true}],systemPrompt:'受控长流协议fixture',tools:[],timeout:{modelMs:90000},output:{maxTokens:256},guard:{roles:['EDITOR'],attributes:{}}}});
 const expected={text:shortText,usage:{promptTokens:8,completionTokens:4,totalTokens:12},actualModel:{modelEntryId:entry.id,modelName:'gpt-4',providerId:provider.id,endpoint:'http://127.0.0.1:28414/v1'}};
 const evaluation=await api('/admin/evaluations','POST',{appCode:code,draftRev:app.draftRev,candidateSha256:app.candidateSha256,datasetVersion:'frontend_long_fixture_v1',cases:[{id:'known_short_protocol',inputs:{message:shortInput},expected,safety:true}]});assert.equal(evaluation.status,'ACCEPTED');
 const precheck=await api('/admin/apps/'+code+'/publish/precheck','POST',{});assert.equal(precheck.passed,true);
 const publication=await api('/admin/apps/'+code+'/publish/commit','POST',{precheckId:precheck.precheckId,draftRev:app.draftRev,candidateSha256:app.candidateSha256,idempotencyRequestNo:crypto.randomUUID()});
 const setup={fixtureModelOnly:true,actualGateway:true,appCode:code,providerId:provider.id,modelEntryId:entry.id,versionNo:publication.versionNo,evaluationStatus:evaluation.status,providerTest:{ok:true,before:testBefore,after:testAfter},expectedLongText:longText};
 await fs.writeFile(path.join(root,'.local/frontend-stream-live-setup.json'),JSON.stringify(setup,null,2));
 console.log(JSON.stringify({phase:'published',appCode:code,evaluationStatus:evaluation.status,versionNo:publication.versionNo}));
 const result=await new Promise(resolve=>{const p=spawn(process.execPath,['tests/frontend-stream-live-browser.mjs'],{cwd:path.join(root,'web'),stdio:'inherit'});p.on('exit',resolve);p.on('error',()=>resolve(1));});
 await fs.writeFile(path.join(root,'.local/frontend-stream-live-fixture.json'),JSON.stringify(metrics,null,2));
 assert.equal(result,0,'Browser test failed');assert.equal(metrics.long.length,2);assert(metrics.long.every(x=>x.finishMs>=64000&&x.finishMs<75000));
 console.log(JSON.stringify({phase:'fixture-complete',metrics}));
} finally {fixture.close();for(const socket of sockets)socket.destroy();}
