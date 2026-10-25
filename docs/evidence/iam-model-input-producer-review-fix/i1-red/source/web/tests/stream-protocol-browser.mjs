import {chromium,expect} from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash} from 'node:crypto';
const root=path.resolve(import.meta.dirname,'../..'),own=path.join(root,'.local/iam-o2b-f2c2c-frontend-protocol');
const epoch='browser-'+Date.now(),out=path.join(own,epoch),evidence=path.join(root,'docs/evidence/iam-stage-4-o2b-f2c2c-frontend-protocol',epoch);
await fs.mkdir(out,{recursive:true});await fs.mkdir(evidence,{recursive:true});
const server=net.createServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;await new Promise(r=>server.close(r));
if([15173,15174,15175].includes(port))throw Error('reserved port');
const profile=path.join(out,'chrome-profile'),results=[],processes=[],pages=[];let browser,viteOutput='',viteExit;
const from=process.env.C2C_FROM_PHASE||'chat';
const phases=['chat','admin','upgrade','close','extra'];
const phase=name=>phases.indexOf(name)>=phases.indexOf(from);
const viteArtifacts=new Map(),artifactJobs=[];
const vite=spawn(process.execPath,['node_modules/vite/bin/vite.js','--config','tests/vite.config.ts','--port',String(port)],{cwd:path.join(root,'web'),windowsHide:true,stdio:['ignore','pipe','pipe']});
const closed=new Promise(resolve=>vite.once('close',(code,signal)=>{viteExit={code,signal,at:new Date().toISOString()};resolve();}));
for(const s of [vite.stdout,vite.stderr])s.on('data',b=>{viteOutput+=b;});
const proc=async(label)=>{const ps=await promisify(execFile)('powershell.exe',['-NoProfile','-Command',`Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*${profile.replaceAll("'","''")}*' -and $_.Name -eq 'chrome.exe' } | Select-Object ProcessId,ParentProcessId,Name,CommandLine | ConvertTo-Json -Depth 3`],{windowsHide:true});processes.push({label,at:new Date().toISOString(),output:ps.stdout});};
const ok=data=>({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data})});
const snapshot=(status,watermark,extra={})=>({status:200,contentType:'application/json',body:`{"code":"OK","data":{"runId":"r_c2c","status":"${status}","eventsWatermark":${watermark},"nodes":[],"events":[],${Object.entries({output:{text:'权威最终结果'},...extra}).map(([k,v])=>JSON.stringify(k)+':'+JSON.stringify(v)).join(',')}}}`});
const sse=body=>({status:200,contentType:'text/event-stream',body});
const fact=(id,type,data)=>`id: ${id}\r\nevent: ${type}\r\ndata: ${typeof data==='string'?data:JSON.stringify(data)}\r\n\r\n`;
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{resolve,promise};};
const n1='9007199254740992',n2='9007199254740993',n3='9007199254740994',max='9223372036854775807';
async function pageFor(mode,unsupported=false){
 const page=await browser.newPage(),errors=[],consoleErrors=[];page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')consoleErrors.push(m.text());});page.on('dialog',d=>d.accept());
 page.on('response',response=>{const url=new URL(response.url());if(url.origin!==`http://127.0.0.1:${port}`||url.pathname.startsWith('/admin/')||url.pathname.startsWith('/v1/')||!['document','script','stylesheet'].includes(response.request().resourceType())||viteArtifacts.has(url.pathname+url.search))return;const name=url.pathname+url.search;viteArtifacts.set(name,{url:name});artifactJobs.push((async()=>{try{const body=await response.body(),sha256=createHash('sha256').update(body).digest('hex'),file='vite-output/'+sha256;await fs.mkdir(path.join(out,'vite-output'),{recursive:true});await fs.writeFile(path.join(out,file),body);viteArtifacts.set(name,{url:name,file,bytes:body.length,sha256});}catch(error){viteArtifacts.set(name,{url:name,error:error.message});}})());});
 await page.addInitScript(({unsupported})=>{
  if(unsupported){const native=JSON.parse;JSON.parse=(text,reviver)=>native(text,reviver?function(key,value){return reviver.call(this,key,value);}:undefined);}
  window.readerRecords=[];window.fixtureHang=false;
  const native=window.fetch;window.fetch=async(...args)=>{
   const url=String(args[0]);let response;
   if(url.endsWith('/events')&&window.fixtureHang){response=new Response(new ReadableStream({start(c){c.enqueue(new TextEncoder().encode('event: chunk\ndata: null\n\n'));},cancel(){window.hangSourceCancelled=(window.hangSourceCancelled||0)+1;}}),{headers:{'content-type':'text/event-stream'}});}
   else response=await native(...args);
   if(url.endsWith('/events')&&response.body){const body=response.body,get=body.getReader.bind(body);body.getReader=(...opts)=>{const reader=get(...opts),entry={url,cancel:0,release:0};window.readerRecords.push(entry);const cancel=reader.cancel.bind(reader),release=reader.releaseLock.bind(reader);reader.cancel=(...params)=>{entry.cancel++;return cancel(...params);};reader.releaseLock=()=>{entry.release++;return release();};return reader;};}
   return response;
  };
 },{unsupported});
 const item={page,errors,consoleErrors,mode};pages.push(item);return item;
}
async function common(route,counts){const p=new URL(route.request().url()).pathname;
 if(p.endsWith('/input-schema')){await route.fulfill(ok({type:'CHAT',inputs:[]}));return true;}
 if(p.startsWith('/admin/debug-inputs')){await route.fulfill(ok({items:[],hasMore:false,nextAfterId:0}));return true;}
 if(p.startsWith('/admin/apps/')){await route.fulfill(ok({draftId:1,type:'CHAT',config:{inputs:[]}}));return true;}
 if(p.endsWith('/sessions')){await route.fulfill(ok({sessionId:'s_c2c'}));return true;}
 if(p.endsWith('/turns')||p==='/admin/debug-runs'){counts.creates++;await route.fulfill(ok({runId:'r_c2c'}));return true;}
 if(p.endsWith('/cancel')){counts.cancels++;await route.fulfill(ok({}));return true;}return false;
}
const go=(page,mode)=>page.goto(`http://127.0.0.1:${port}/stream-protocol.html?mode=${mode}`);
async function start(page,mode){if(mode==='debug')await page.getByRole('button',{name:'开始调试',exact:true}).click();else if(mode!=='detail'){if(mode==='sdk')await page.getByRole('button',{name:'打开智能助手'}).click();await page.getByRole('textbox',{name:mode==='sdk'?'输入问题':undefined}).filter({visible:true}).first().fill('C2c synthetic protocol');await page.getByRole('button',{name:'发送',exact:true}).click();}}
async function finish(item,extra={}){const readers=await item.page.evaluate(()=>window.readerRecords);expect(item.errors).toEqual([]);expect(item.consoleErrors.filter(x=>!x.includes('Failed to load resource'))).toEqual([]);results.push({mode:item.mode,errors:item.errors,consoleErrors:item.consoleErrors,readers,...extra});await item.page.close();}
try{
 for(let i=0;i<100;i++){if(viteExit)throw Error('Vite exited');try{if((await fetch(`http://127.0.0.1:${port}/stream-protocol.html`)).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launchPersistentContext(profile,{executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,viewport:{width:1440,height:1000}});await proc('browser-active');
 const capability=await browser.pages()[0].evaluate(()=>{let source;JSON.parse('9007199254740993',(k,v,c)=>{if(k==='')source=c?.source;return v;});return{userAgent:navigator.userAgent,source};});expect(capability.source).toBe(n2);results.push({nativeCapability:capability});
 for(const mode of phase('chat')?['chat','sdk']:[]){
  const item=await pageFor(mode),{page}=item,counts={creates:0,cancels:0,reads:0,streams:0,decisions:0},cursors=[],approved=deferred(),final=deferred();
  await page.route('**/v1/**',async route=>{const p=new URL(route.request().url()).pathname;if(await common(route,counts))return;
   if(p.endsWith('/decision')){counts.decisions++;approved.resolve();return route.fulfill(ok({}));}
   if(p.endsWith('/approvals'))return route.fulfill(ok(counts.decisions?[]:[{nodeInstanceId:'n_confirm',nodeDefId:'C2c测试确认',parameterSnapshot:{synthetic:true},snapshotDigest:'synthetic-digest',expiresAt:'2099-01-01T00:00:00Z'}]));
   if(p.endsWith('/events')){counts.streams++;cursors.push(route.request().headers()['last-event-id']);if(counts.streams===1)return route.fulfill(sse(fact(n1,'NODE_STARTED','null')+fact(n2,'界'.repeat(32),'[1," 中 "]')+fact('','chunk','null')+fact('','CHUNK','[]')+fact('','CHUNK','"scalar"')+fact('','chunk',{text:'临时片段'})+fact(n3,'RUN_FINISHED','null')));if(counts.streams===2)return route.fulfill(sse('event: stream_reset\ndata: {"reason":"CURSOR_EXPIRED","eventsWatermark":9223372036854775807}\n\n'));await approved.promise;return route.fulfill(sse(fact(max,'RUN_FINISHED',{output:{text:'事件暂定结果'}})));}
   counts.reads++;if(counts.reads===1)return route.fulfill(snapshot('RUNNING',n2));if(counts.reads===2)return route.fulfill(snapshot('WAITING_APPROVAL',n3));await final.promise;return route.fulfill(snapshot('SUCCEEDED',max));
  });
  await go(page,mode);await start(page,mode);await page.getByRole('button',{name:'确认执行',exact:true}).waitFor();expect(counts.creates).toBe(1);expect(counts.reads).toBe(2);
  await page.screenshot({path:path.join(evidence,mode+'-approval.png')});await page.getByRole('button',{name:'确认执行',exact:true}).click();await page.getByText('事件暂定结果',{exact:true}).waitFor();
  if(mode==='sdk')await expect(page.getByRole('button',{name:'发送',exact:true})).toBeDisabled();else await expect(page.getByRole('button',{name:'发送',exact:true})).toHaveCount(0);
  final.resolve();await page.getByText('权威最终结果',{exact:true}).waitFor();await expect(page.getByRole('button',{name:'发送',exact:true})).toBeEnabled();expect(cursors).toEqual(['0',n2,n3]);expect(counts).toEqual({creates:1,cancels:0,reads:3,streams:3,decisions:1});
  await finish(item,{counts,cursors,exactIndependentSdkStatus:mode==='sdk',eofNotTerminal:true,approvalPreserved:true});
 }
 for(const mode of phase('admin')?['debug','detail']:[]){
  const item=await pageFor(mode),{page}=item,counts={creates:0,cancels:0,reads:0,streams:0},cursors=[];let allow=false;
  await page.route('**/admin/**',async route=>{const p=new URL(route.request().url()).pathname;if(!p.startsWith('/admin/'))return route.continue();if(await common(route,counts))return;
   if(p.endsWith('/events')){counts.streams++;cursors.push(route.request().headers()['last-event-id']);if(counts.streams===1)return route.fulfill(sse(fact(n2,'NODE_STARTED','null')+fact(n3,'界'.repeat(32),'[]')+'event: stream_reset\ndata: {"reason":"CURSOR_EXPIRED","eventsWatermark":9223372036854775807}\n\n'));return route.fulfill(sse(allow?fact(max,'NODE_FINISHED','null'):'event: stream_reset\ndata: {"reason":"AUTHORITY_EXPIRED"}\n\n'));}
   counts.reads++;return route.fulfill(snapshot(allow?'SUCCEEDED':'RUNNING',counts.reads===1?n1:n3));
  });
  await go(page,mode);await start(page,mode);await page.getByRole('button',{name:'重新连接观察',exact:true}).waitFor();expect(cursors[0]).toBe(n1);expect(cursors[1]).toBe(n3);const stoppedReads=counts.reads;await page.waitForTimeout(250);expect(counts.reads).toBe(stoppedReads);
  await page.screenshot({path:path.join(evidence,mode+'-reauth.png')});allow=true;await page.getByRole('button',{name:'重新连接观察',exact:true}).click();await page.getByText('SUCCEEDED',{exact:true}).first().waitFor();expect(counts.reads).toBe(stoppedReads+1);expect(counts.cancels).toBe(0);await finish(item,{counts,cursors,resetLoadsExactStatus:true,manualReauth:true});
 }
 for(const mode of phase('upgrade')?['debug','detail','chat','sdk']:[]){
  const item=await pageFor(mode,true),{page}=item,counts={creates:0,cancels:0,reads:0};
  await page.route('**/*',async route=>{const p=new URL(route.request().url()).pathname;if(!p.startsWith('/admin/')&&!p.startsWith('/v1/'))return route.continue();if(await common(route,counts))return;if(p.endsWith('/events'))return route.fulfill(sse(fact(n1,'NODE_STARTED','null')));counts.reads++;return route.fulfill(snapshot('RUNNING',n2));});
  await go(page,mode);await start(page,mode);await page.getByRole('alert').filter({hasText:'升级 Chrome 或 Edge'}).waitFor();await page.screenshot({path:path.join(evidence,mode+'-upgrade.png')});expect(counts.reads).toBe(1);await finish(item,{counts,unsupportedNativeContextRecoverable:true});
 }
 for(const mode of phase('close')?['detail','chat','sdk']:[]){
  const item=await pageFor(mode),{page}=item,counts={creates:0,cancels:0,reads:0};
  await page.route('**/*',async route=>{const p=new URL(route.request().url()).pathname;if(!p.startsWith('/admin/')&&!p.startsWith('/v1/'))return route.continue();if(await common(route,counts))return;counts.reads++;return route.fulfill(snapshot('RUNNING',n2));});
  await page.addInitScript(()=>{window.fixtureHang=true;});await go(page,mode);await start(page,mode);await expect.poll(()=>page.evaluate(()=>window.readerRecords.length)).toBe(1);
  await page.evaluate(()=>window.disposeFixture());await expect.poll(()=>page.evaluate(()=>window.hangSourceCancelled)).toBe(1);await expect.poll(()=>page.evaluate(()=>window.readerRecords[0].release)).toBe(1);expect(counts.cancels).toBe(0);await finish(item,{counts,actualPendingReaderCancelled:true,unmountOrDestroy:true});
 }
 for(const mode of process.env.C2C_EXTRA==='generation'?['chat']:!process.env.C2C_EXTRA?['debug','detail','chat']:[]){
  const item=await pageFor(mode),{page}=item,counts={creates:0,cancels:0,reads:0};
  await page.addInitScript(()=>window.fixtureHang=true);
  await page.route('**/*',async route=>{const p=new URL(route.request().url()).pathname;if(!p.startsWith('/admin/')&&!p.startsWith('/v1/'))return route.continue();if(await common(route,counts))return;counts.reads++;return route.fulfill(snapshot('RUNNING',n2));});
  await go(page,mode);await start(page,mode);await expect.poll(()=>page.evaluate(()=>window.readerRecords.length)).toBe(1);await page.evaluate(()=>window.invalidateFixture());
  await expect.poll(()=>page.evaluate(()=>window.hangSourceCancelled)).toBe(1);await expect.poll(()=>page.evaluate(()=>window.readerRecords[0].release)).toBe(1);const reads=counts.reads;await page.waitForTimeout(300);expect(counts.reads).toBe(reads);expect(counts.cancels).toBe(0);await finish(item,{counts,authGenerationStopsOriginalReader:true});
 }
 if(!process.env.C2C_EXTRA||process.env.C2C_EXTRA==='late')for(const mode of ['debug','detail','chat','sdk']){
  const item=await pageFor(mode),{page}=item,counts={creates:0,cancels:0,reads:0},pending=deferred(),started=deferred();
  await page.route('**/*',async route=>{const p=new URL(route.request().url()).pathname;if(!p.startsWith('/admin/')&&!p.startsWith('/v1/'))return route.continue();if(await common(route,counts))return;if(p.endsWith('/events'))return route.fulfill(sse(fact(n1,'NODE_STARTED','null')));counts.reads++;started.resolve();await pending.promise;await route.fulfill(snapshot('SUCCEEDED',max,{output:{text:'STALE_RESPONSE_MUST_NOT_APPLY'}})).catch(()=>{});});
  await go(page,mode);await start(page,mode);await started.promise;await page.evaluate(()=>window.disposeFixture());pending.resolve();await page.waitForTimeout(300);expect(await page.locator('body').innerText()).not.toContain('STALE_RESPONSE_MUST_NOT_APPLY');expect(counts.cancels).toBe(0);expect(counts.reads).toBe(1);await finish(item,{counts,lateStatusIgnoredAfterUnmount:true});
 }
 if(!process.env.C2C_EXTRA||process.env.C2C_EXTRA==='auth')for(const mode of ['chat','sdk']){
  const item=await pageFor(mode,true),{page}=item,counts={creates:0,cancels:0,reads:0};
  await page.route('**/v1/**',async route=>{const p=new URL(route.request().url()).pathname;if(await common(route,counts))return;if(p.endsWith('/events'))return route.fulfill(sse(fact(n1,'NODE_STARTED','null')));counts.reads++;return route.fulfill({status:401,contentType:'application/json',body:'{"code":"AUTH","message":"synthetic active auth expired","data":null}'});});
  await go(page,mode);await start(page,mode);if(mode==='chat'){await expect.poll(()=>page.evaluate(()=>window.fixtureAuth())).toBe('');await expect(page.getByRole('button',{name:'发送',exact:true})).toBeEnabled();await expect(page.getByRole('alert').filter({hasText:'升级 Chrome 或 Edge'})).toHaveCount(0);}else await page.getByRole('alert').filter({hasText:'synthetic active auth expired'}).waitFor();expect(counts.reads).toBe(1);await finish(item,{counts,authErrorNotMaskedByUnsupportedContext:true});
 }
 if(process.env.C2C_EXTRA==='iam'){
  const item=await pageFor('sdk'),{page}=item,counts={creates:0,cancels:0,reads:0};await page.route('**/v1/**',async route=>{const p=new URL(route.request().url()).pathname;if(await common(route,counts))return;if(p.endsWith('/events'))return route.fulfill(sse(fact(n1,'NODE_STARTED','null')));counts.reads++;return route.fulfill({status:503,contentType:'application/json',body:'{"code":"IAM_AUTHORITY_UNAVAILABLE","message":"synthetic IAM unavailable"}'});});await go(page,'sdk');await start(page,'sdk');await page.getByRole('alert').filter({hasText:'synthetic IAM unavailable'}).waitFor({timeout:20000});expect(counts.reads).toBe(1);await finish(item,{counts,namedIamFailureStopsWithoutRetry:true});
 }
 if(process.env.C2C_EXTRA==='unknown-node'){
  const item=await pageFor('debug'),{page}=item,counts={creates:0,cancels:0,reads:0,streams:0};await page.route('**/admin/**',async route=>{const p=new URL(route.request().url()).pathname;if(!p.startsWith('/admin/'))return route.continue();if(await common(route,counts))return;if(p.endsWith('/events')){counts.streams++;return route.fulfill(sse(fact('9007199254740995','constructor',{nodeInstanceId:{toString:null},nodeDefId:'node_safe'})));}counts.reads++;return route.fulfill(snapshot('RUNNING',n3,{nodes:[{nodeInstanceId:'n_synthetic',nodeDefId:'node_safe',status:'RUNNING'}]}));});await go(page,'debug');await start(page,'debug');await expect.poll(()=>counts.streams).toBe(1);await page.waitForTimeout(100);await expect(page.getByRole('alert')).toHaveCount(0);await page.evaluate(()=>window.disposeFixture());await finish(item,{counts,unknownBusinessFieldsSafelyNarrowed:true});
 }
 if(process.env.C2C_EXTRA==='backfill')for(const mode of ['debug','detail']){
  const item=await pageFor(mode),{page}=item,counts={creates:0,cancels:0,reads:0,streams:0},cursors=[];
  const rows='[{"seq":1e5,"seq":9007199254740993,"type":"NODE_STARTED","payload":null,"created_at":"synthetic-1"},{"seq":9007199254740994,"type":"NODE_FINISHED","payload":{"seq":9007199254740993},"created_at":"synthetic-2"},{"seq":9223372036854775807,"type":"RUN_FINISHED","payload":[null," 中 "],"created_at":"synthetic-3"}]';
  await page.route('**/admin/**',async route=>{const p=new URL(route.request().url()).pathname;if(!p.startsWith('/admin/'))return route.continue();if(await common(route,counts))return;if(p.endsWith('/events')){counts.streams++;cursors.push(route.request().headers()['last-event-id']);return route.fulfill(sse('event: stream_reset\ndata: {"reason":"CURSOR_EXPIRED","eventsWatermark":9007199254740993}\n\n'));}counts.reads++;return route.fulfill({status:200,contentType:'application/json',body:`{"code":"OK","data":{"status":"${counts.reads===1?'RUNNING':'SUCCEEDED'}","eventsWatermark":${max},"nodes":[],"events":[{"seq":1e5}],"events":${rows},"output":{}}}`});});
  await go(page,mode);await start(page,mode);await expect.poll(()=>counts.streams).toBe(1);let shown;
  if(mode==='debug'){await page.getByText('事件与输出',{exact:true}).click();const pre=page.locator('.el-collapse-item__content pre');await expect(pre).toContainText('9007199254740993');shown=JSON.parse(await pre.innerText()).events;await expect(page.getByText(/游标/)).toContainText(max);}
  else{await page.getByRole('tab',{name:'事件回放',exact:true}).click();const pre=page.locator('.el-tab-pane:visible pre');shown=[];const slider=page.getByRole('slider');await slider.focus();for(let i=0;i<3;i++){if(i)await slider.press('ArrowRight');await expect(slider).toHaveAttribute('aria-valuenow',String(i));shown.push(JSON.parse(await pre.innerText()));}}
  expect(shown.map(row=>row.seq)).toEqual([n2,n3,max]);expect(shown[0].payload).toBeNull();expect(shown[1].payload.seq).toBe(9007199254740992);for(const row of shown)expect(Object.hasOwn(row,'rawData')).toBe(false);expect(cursors).toEqual([max]);expect(counts.cancels).toBe(0);await expect(page.getByRole('alert')).toHaveCount(0);await page.screenshot({path:path.join(evidence,mode+'-exact-backfill.png')});await page.evaluate(()=>window.disposeFixture());await finish(item,{counts,cursors,renderedBackfillSeq:[n2,n3,max],snapshotPayloadNative:true,noFabricatedRawData:true,localReplayIndexPreserved:mode==='detail'});
 }
 // Cancel is a backend action only when explicitly selected by the user.
 if(!process.env.C2C_EXTRA||['auth','cancel'].includes(process.env.C2C_EXTRA)){const item=await pageFor('chat'),{page}=item,counts={creates:0,cancels:0,reads:0};await page.route('**/v1/**',async route=>{if(await common(route,counts))return;throw Error('unexpected cancel-fixture request');});await go(page,'chat');await page.evaluate(()=>window.fixtureHang=true);await start(page,'chat');await page.getByRole('button',{name:'停止运行',exact:true}).click();await expect.poll(()=>counts.cancels).toBe(1);await expect.poll(()=>page.evaluate(()=>window.readerRecords[0].release)).toBe(1);await finish(item,{counts,explicitCancel:true});}
 console.log('PASS C2c selected browser scenarios:',JSON.stringify(results.map(({mode,...flags})=>({mode,checks:Object.keys(flags).filter(key=>flags[key]===true)}))));
}catch(error){for(const item of pages){if(!item.page.isClosed()){await fs.writeFile(path.join(out,item.mode+'-failure.txt'),await item.page.locator('body').innerText());await item.page.screenshot({path:path.join(evidence,item.mode+'-failure.png')});results.push({failedPage:item.mode,errors:item.errors,consoleErrors:item.consoleErrors});}}results.push({failure:{message:error.message,stack:error.stack}});throw error;}
finally{
 await Promise.allSettled(artifactJobs);if(browser)await browser.close();await proc('browser-closed');vite.kill();await closed;
 await fs.writeFile(path.join(out,'vite-output.txt'),viteOutput);await fs.writeFile(path.join(out,'result.json'),JSON.stringify({epoch,port,profile,phases:{from,extra:process.env.C2C_EXTRA},syntheticFixturesOnly:true,results,processes,viteArtifacts:[...viteArtifacts.values()],vite:{pid:vite.pid,exit:viteExit,waitedFor:'child close; stdout/stderr drained'},browserClosed:true},null,2));
 console.log('Collected',path.relative(root,out));
}
