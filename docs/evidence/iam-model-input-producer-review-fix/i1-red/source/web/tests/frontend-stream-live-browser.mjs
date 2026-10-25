import { chromium } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'../..');
const setup=JSON.parse(await fs.readFile(path.join(root,'.local/frontend-stream-live-setup.json'),'utf8'));
const browser=await chromium.launchPersistentContext(path.join(root,'.local/chromium-frontend-stream-live'),{executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,viewport:{width:1440,height:960}});
const results=[];
async function exercise(kind){
 const page=await browser.newPage(), network=[], pending=[], started=new Map();let monitoring=false;
 await page.addInitScript(()=>{window.liveLocalStorageWrites=0;const set=Storage.prototype.setItem;Storage.prototype.setItem=function(...args){if(this===localStorage)window.liveLocalStorageWrites++;return set.apply(this,args);};});
 // Observe a clone of the actual response with the same UTF-8 decoder as the product.
 // Playwright's Chromium response-body text can already be charset-decoded; it is not raw wire.
 await page.addInitScript(()=>{
  window.liveSseReads=[];const original=window.fetch;
  window.fetch=async(...args)=>{
   const response=await original(...args),url=new URL(typeof args[0]==='string'?args[0]:args[0].url,location.href);
   if(!/^\/v1\/runs\/[^/]+\/events$/.test(url.pathname))return response;
   const record={path:url.pathname,at:Date.now(),frames:[],done:false};window.liveSseReads.push(record);
   void(async()=>{try{const reader=response.clone().body.getReader(),decoder=new TextDecoder('utf-8');let buffer='';
    for(;;){const part=await reader.read();if(part.done)break;buffer+=decoder.decode(part.value,{stream:true}).replaceAll('\r\n','\n');let at;
     while((at=buffer.indexOf('\n\n'))>=0){const raw=buffer.slice(0,at);buffer=buffer.slice(at+2);let event='message',id,data=[];
      for(const line of raw.split('\n')){if(line.startsWith('event:'))event=line.slice(6).trim();if(line.startsWith('id:'))id=Number(line.slice(3).trim());if(line.startsWith('data:'))data.push(line.slice(5).trimStart());}
      if(data.length){const value=JSON.parse(data.join('\n'));record.frames.push({event,id,at:Date.now(),...(event==='chunk'?{text:value.text}: {})});}
     }
    }record.done=true;record.end=Date.now();
   }catch(error){record.error=error.message;}})();return response;
  };
 });
 const busy=()=>kind==='webapp'?page.getByRole('button',{name:'停止运行',exact:true}).isVisible():page.getByRole('button',{name:'发送',exact:true}).isDisabled();
 page.on('request',req=>{const p=new URL(req.url()).pathname;if(p.startsWith('/v1/')){const item={path:p,method:req.method(),at:Date.now(),cursor:req.headers()['last-event-id']??null};network.push(item);started.set(req,item);}});
 page.on('response',response=>{
  const item=started.get(response.request());if(!item)return;item.status=response.status();item.responseAt=Date.now();
  if(item.path.endsWith('/events')){pending.push((async()=>{const failure=await response.finished();item.end=Date.now();item.durationMs=item.end-item.at;item.error=failure?.message||null;})().catch(error=>{item.observerError=error.message;}));}
  else pending.push((async()=>{const e=await response.json();if(item.method==='POST'&&item.path.endsWith('/turns'))item.runId=e.data?.runId;
   if(item.method==='GET'&&/^\/v1\/runs\/[^/]+$/.test(item.path)){item.runStatus=e.data?.status;item.output=e.data?.output;item.watermark=e.data?.eventsWatermark;if(monitoring){item.busyAtSnapshot=await busy();if(!['SUCCEEDED','FAILED','CANCELLED'].includes(item.runStatus)){await page.screenshot({path:path.join(root,`docs/evidence/frontend-stream-live-${kind}-recovery.png`)});}}}
  })().catch(error=>{item.observerError=error.message;}));
 });
 try {
 await page.goto('http://127.0.0.1:15174/'+(kind==='webapp'?'w/'+setup.appCode:''));
 if(kind==='webapp')await page.getByRole('button',{name:'开发登录',exact:true}).click();
 else {
  await page.evaluate(async appCode=>{document.body.innerHTML='<main><h1>SDK 开发票据长流恢复验证</h1><p>真实网关；65秒协议 fixture；不代表宿主短票据全链。</p></main>';window.liveTickets=0;const script=document.createElement('script');script.src='/assistant.js';await new Promise((resolve,reject)=>{script.onload=resolve;script.onerror=reject;document.head.append(script);});window.AIAssistant.init({endpoint:location.origin,appCode,ticket:async()=>{window.liveTickets++;const e=await fetch('/auth/dev-token',{method:'POST'}).then(r=>r.json());return e.data.accessToken;}});},setup.appCode);
  await page.getByRole('button',{name:'打开智能助手'}).click();
 }
 const input=page.getByRole('textbox',{name:kind==='webapp'?'message':'输入问题',exact:true});await input.fill('frontend-live-long-'+kind);
 const begin=Date.now();monitoring=true;await page.getByRole('button',{name:'发送',exact:true}).click();
 const busySamples=[];let futureScreenshot=false;const futureChunk='第四段恢复观察；';
 const reply=kind==='webapp'?page.locator('.bubble.assistant'):page.locator('agent-platform-assistant').locator('.bubble').last();
 for(let i=0;i<90;i++){
  await page.waitForTimeout(1000);
  // Read busy, text and hint in one browser task; separate awaits can cross a terminal update.
  const sample=await page.evaluate(({kind,begin})=>{
   const host=kind==='webapp'?document.querySelector('.chat'):document.querySelector('agent-platform-assistant').shadowRoot;
   const text=(kind==='webapp'?host.querySelector('.bubble.assistant'):Array.from(host.querySelectorAll('.bubble')).at(-1)).innerText.replace(/^智能体\s*/, '').trim();
   const busy=kind==='webapp'?Array.from(host.querySelectorAll('button')).some(x=>x.textContent.trim()==='停止运行'):host.querySelector('.send').disabled;
   const connection=Array.from(host.querySelectorAll('[role="status"]')).map(x=>x.textContent).join(' '),at=Date.now();return{at,ms:at-begin,busy,text,connection};
  },{kind,begin});busySamples.push(sample);
  if(sample.busy&&!futureScreenshot&&sample.text.includes(futureChunk)){futureScreenshot=true;await page.screenshot({path:path.join(root,`docs/evidence/frontend-stream-live-${kind}-future.png`)});}
  if(!sample.busy&&network.some(x=>x.runStatus==='SUCCEEDED'))break;
 }
 await Promise.all(pending);
 await fs.writeFile(path.join(root,`.local/frontend-stream-live-${kind}-samples.json`),JSON.stringify(busySamples,null,2));
 const creates=network.filter(x=>x.method==='POST'&&x.path.endsWith('/turns')), streams=network.filter(x=>x.path.endsWith('/events')), statuses=network.filter(x=>x.runStatus);
 const wire=await page.evaluate(()=>window.liveSseReads);assert.equal(wire.length,streams.length);
 for(let i=0;i<streams.length;i++){assert.equal(wire[i].done,true);assert.equal(wire[i].error,undefined);streams[i].frames=wire[i].frames;streams[i].frameTypes=wire[i].frames.map(x=>x.event);streams[i].factIds=wire[i].frames.filter(x=>x.id!==undefined).map(x=>x.id);streams[i].chunkText=wire[i].frames.filter(x=>x.event==='chunk').map(x=>x.text).join('');}
 assert.equal(creates.length,1,JSON.stringify(network));const id=creates[0].runId;assert(id);
 assert(streams.length>=2);assert(statuses.length>=1);assert(streams.every(x=>x.path==='/v1/runs/'+id+'/events'));
 const factIds=streams.flatMap(x=>x.factIds);assert(factIds.length>0);assert(factIds.every((id,i)=>i===0||id>factIds[i-1]),'Fact events must not repeat after reconnect');
 assert.equal(streams[0].error,null);
 assert(statuses.some(x=>x.runStatus==='RUNNING'&&x.busyAtSnapshot===true));assert.equal(statuses.at(-1).runStatus,'SUCCEEDED');
 assert(busySamples.filter(x=>x.ms>=52000&&x.ms<=63000).every(x=>x.busy));
 const recoverySamples=busySamples.filter(x=>x.ms>streams[0].end-begin&&x.busy);
 assert(recoverySamples.length>0);assert(recoverySamples.every(x=>x.busy&&x.text.startsWith('长任务验证：第一段已经到达；')&&x.text!=='{}'),'Running snapshots must retain existing text');
 assert(recoverySamples.every(x=>x.connection.includes('临时片段可能缺失')),'Missing temporary chunks notice must remain during recovery');
 assert(recoverySamples.some(x=>x.text.includes(futureChunk)&&x.at<statuses.at(-1).responseAt),'A new chunk received after reconnect must appear in the DOM while busy before terminal');
 assert(streams.slice(1).some(x=>x.frames.some(f=>f.event==='chunk'&&f.text.includes(futureChunk)&&f.at<statuses.at(-1).responseAt)),'UTF-8 future chunk must arrive on a recovered actual stream before authoritative terminal');
 assert(busySamples.filter(x=>x.ms<statuses.at(-1).responseAt-begin).every(x=>x.busy),'Busy must remain until authoritative terminal response');
 const finalText=(await reply.innerText()).replace(/^智能体\s*/, '').trim();assert.equal(finalText,setup.expectedLongText);assert.equal(await busy(),false);
 assert.deepEqual(statuses.at(-1).output.usage,{promptTokens:8,completionTokens:4,totalTokens:12});
 const localStorageLength=await page.evaluate(()=>localStorage.length);assert.equal(localStorageLength,0);
 const localStorageWrites=await page.evaluate(()=>window.liveLocalStorageWrites);assert.equal(localStorageWrites,0);
 const sessionId=new URL(page.url()).searchParams.get('sessionId');assert(sessionId);
 const session=await page.evaluate(async({appCode,sessionId})=>{const login=await fetch('/auth/dev-token',{method:'POST'}).then(r=>r.json());const e=await fetch('/v1/apps/'+appCode+'/sessions/'+sessionId,{headers:{Authorization:'Bearer '+login.data.accessToken}}).then(r=>r.json());return e.data;},{appCode:setup.appCode,sessionId});
 assert.equal(session.turns.length,1);assert.equal(session.turns[0].run_public_id,id);
 await page.screenshot({path:path.join(root,`docs/evidence/frontend-stream-live-${kind}-final.png`)});
 const tickets=kind==='sdk'?await page.evaluate(()=>window.liveTickets):undefined;
 if(kind==='sdk')assert(tickets>=streams.length+statuses.length+3,'SDK must obtain tickets for create/schema and each stream/status request');
 const proof={kind,runId:id,sessionId,turnCreates:creates.length,authoritativeTurns:session.turns.length,streams:streams.length,statusReads:statuses.length,firstEofMs:streams[0].durationMs,finalStatus:statuses.at(-1).runStatus,finalText,usage:statuses.at(-1).output.usage,localStorageLength,localStorageWrites,tickets,futureChunk,uiBeginAt:begin,authoritativeTerminalAt:statuses.at(-1).responseAt,busySamples,network};
 results.push(proof);console.log(JSON.stringify({kind,runId:id,streams:streams.length,statusReads:statuses.length,firstEofMs:streams[0].durationMs,finalStatus:proof.finalStatus,tickets}));
 assert(streams[0].durationMs>=53000&&streams[0].durationMs<58000,'Expected actual engine EOF near 55 seconds; observed '+streams[0].durationMs+'ms');
 } catch(error){console.error(kind+': '+error.stack);throw error;}
 finally {await fs.writeFile(path.join(root,`.local/frontend-stream-live-${kind}-network.json`),JSON.stringify(network,null,2));await page.close();}
}
try {const completed=await Promise.allSettled(['webapp','sdk'].map(exercise));await fs.writeFile(path.join(root,'.local/frontend-stream-live-browser.json'),JSON.stringify({fixtureOnly:true,actualGateway:true,routeInterception:false,results,errors:completed.filter(x=>x.status==='rejected').map(x=>x.reason.message)},null,2));assert(completed.every(x=>x.status==='fulfilled'),'One or more real browser checks failed');}
finally {await browser.close();}
