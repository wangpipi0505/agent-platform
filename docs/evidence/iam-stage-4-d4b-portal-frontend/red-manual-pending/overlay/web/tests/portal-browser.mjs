import {chromium,expect} from '@playwright/test';
import {createServer as viteServer} from 'vite';
import vue from '@vitejs/plugin-vue';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(fileURLToPath(new URL('../..',import.meta.url)));
const phase=process.argv[2]||'browser-1',out=path.join(root,'docs/evidence/iam-stage-4-d4b-portal-frontend',phase);
const selector=process.argv[3];
fs.mkdirSync(out,{recursive:true});
const raw=[],results=[],errors=[],gates=new Map();
let behavior={status:200,code:'OK',roles:['ADMIN'],reset:undefined,http:undefined,runStatus:'SUCCEEDED',expiry:60_000},statusReads=0;
const save=(f,x)=>fs.writeFileSync(path.join(out,f),JSON.stringify(x,null,2)+'\n');
const webRoot=path.join(root,'web/admin'),shared='/@fs/'+root.replaceAll('\\','/')+'/web/shared/';
const vite=await viteServer({configFile:false,root:webRoot,plugins:[vue()],server:{middlewareMode:true,hmr:false,fs:{allow:[root]}},appType:'custom',logLevel:'error'});
const appData=token=>({code:'app',name:'应用 '+token,type:'FLOW',status:'PUBLISHED',draftRev:1,draftId:1,currentVersionNo:1,config:{},definition:{inputs:[],nodes:[],edges:[]},layout:{}});
const server=http.createServer(async(req,res)=>{
 const url=new URL(req.url,'http://local'),p=url.pathname;
 if(p.endsWith('/web/shared/chart.ts')&&gates.has('chart')){const held=gates.get('chart');held.started();await held.promise;}
 if(p==='/dev-main.js'){
  const transformed=await vite.transformRequest('/src/main.ts');
  if(!transformed.code.includes('import.meta.env.VITE_AUTH_MODE'))throw Error('main env expression changed');
  res.setHeader('Content-Type','text/javascript');res.end(transformed.code.replace('import.meta.env.VITE_AUTH_MODE',JSON.stringify('dev')));return;
 }
 if(p==='/ambient'){raw.push({at:new Date().toISOString(),method:req.method,url:req.url,rawHeaders:req.rawHeaders,cookie:req.headers.cookie??null,kind:'ambient-positive-control'});res.end('ambient cookie positive control');return;}
 if(p==='/probe.js'){
  res.setHeader('Content-Type','text/javascript');res.end(`import {useAuth,createApi} from ${JSON.stringify(shared+'api.ts')};import {downloadArtifact} from ${JSON.stringify(shared+'artifact.ts')};
  window.initD4b=()=>window.d4b={auth:useAuth(),createApi,downloadArtifact,capture:()=>document.querySelector('.shell')?.__vueParentComponent.setupState};`);return;
 }
 if(p==='/fixture.html'){
  const dev=url.searchParams.get('mode')==='dev';
  const module=dev?`import '/dev-main.js';`:`import '/src/main.ts';`;
  const html=`<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>D4b controlled portal</title></head><body><div id="app"></div><script type="module">${module}import '/probe.js';window.initD4b();</script></body></html>`;
  res.setHeader('Content-Type','text/html');res.end(html);return;
 }
 if(/^\/(auth|admin|v1)\//.test(p)){
  const token=String(req.headers.authorization||'').replace(/^Bearer /,'');
  const row={at:new Date().toISOString(),method:req.method,url:req.url,rawHeaders:req.rawHeaders,authorization:req.headers.authorization??null,cookie:req.headers.cookie??null};raw.push(row);
  let chunks=[];for await(const chunk of req)chunks.push(chunk);row.body=Buffer.concat(chunks).toString();
  const gate=gates.get(token+' '+p);if(gate){gate.started?.();await gate.promise;}
  let status=200,code='OK',data=[];
  if(p==='/auth/session'){status=behavior.status;code=behavior.code;data={tenantId:'tenant_'+token,principalId:token,roles:behavior.roles,expiresAt:new Date(Date.now()+behavior.expiry).toISOString()};}
  else if(p==='/auth/dev-token')data={accessToken:'dev-ticket'};
  else if(p.endsWith('/download')){res.setHeader('Content-Type','application/octet-stream');res.end('controlled artifact');row.response={status:200,kind:'artifact',body:'controlled artifact'};return;}
  else if(p.endsWith('/events')){
   if(behavior.http){status=behavior.http.status;code=behavior.http.code;data=null;}
   else {const body=behavior.reset?`event: stream_reset\ndata: ${JSON.stringify({reason:behavior.reset})}\n\nid: 3\nevent: NODE_STARTED\ndata: {"seq":3}\n\n`:behavior.statusFailure?'id: 3\nevent: WAITING_STEP\ndata: {"seq":3,"type":"WAITING_STEP"}\n\n':'event: stream_status\ndata: {"chunkReplay":false}\n\n';res.setHeader('Content-Type','text/event-stream');res.end(body);row.response={status:200,kind:'sse',body};return;}
  } else if(/\/debug-runs\/?$/.test(p)&&req.method==='POST')data={runId:'r_debug'};
  else if(p==='/admin/debug-inputs')data={items:[],hasMore:false,nextAfterId:0};
  else if(p.includes('/debug-runs/')&&p.endsWith('/status'))data={runId:'r_debug',status:'RUNNING',eventsWatermark:0,nodes:[],events:[]};
  else if(/^\/admin\/runs\//.test(p))data={runId:p.split('/')[3],status:behavior.runStatus,eventsWatermark:0,nodes:[],fileRefs:[{artifactId:'file',name:'result.txt',bytes:19,downloadUrl:'/admin/artifacts/file/download'}]};
  else if(p==='/admin/apps')data=[appData(token)];
  else if(p==='/admin/apps/app/copy')data={code:'old_copy'};
  else if(p==='/admin/apps/app/versions')data=[{versionNo:1}];
  else if(p==='/admin/apps/app')data=appData(token);
  else if(p==='/admin/flows/app')data={definition:appData(token).definition,layout:{}};
  else if(p.endsWith('/input-schema'))data={type:'FLOW',inputs:[]};
  if(behavior.statusFailure&&(p.endsWith('/status')||/^\/admin\/runs\/[^/]+$/.test(p))&&++statusReads>=behavior.statusFailure.after){status=behavior.statusFailure.status;code=behavior.statusFailure.code;data=null;}
  row.response={status,kind:'json',envelope:{code,message:status===200?'OK':'controlled '+code,data}};
  res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(row.response.envelope));return;
 }
 vite.middlewares(req,res,()=>{res.statusCode=404;res.end('not found');});
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const port=server.address().port,origin='http://127.0.0.1:'+port;
save('lifecycle-start.json',{cwd:process.cwd(),pid:process.pid,origin,readyAt:new Date().toISOString(),owned:'node HTTP+Vite middleware; child Chromium launched by this test; no existing processes touched'});
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const context=await browser.newContext({viewport:{width:1440,height:960},acceptDownloads:true});
context.setDefaultTimeout(8000);
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const reset=()=>{behavior={status:200,code:'OK',roles:['ADMIN'],reset:undefined,http:undefined,runStatus:'SUCCEEDED',expiry:60_000};statusReads=0;};
async function pageFor({provider=true,token='A',dev=false,hash='/apps',held=false}={}){
 const page=await context.newPage();page.on('pageerror',e=>{errors.push({message:e.message,url:page.url()});save('page-errors.json',errors);});
 await page.addInitScript(({provider,token,held})=>{
  const native=setTimeout;window.setTimeout=(fn,ms,...args)=>native(fn,[1000,2000].includes(ms)?50:ms,...args);
  window.host={token,calls:0,unsubscribed:0,listeners:[],held,release:undefined};
  if(provider)window.agentPlatformPortal={getAccessToken(){window.host.calls++;return window.host.held?new Promise(r=>window.host.release=r):Promise.resolve(window.host.token);},onSessionChanged(fn){window.host.listeners.push(fn);return ()=>{window.host.unsubscribed++;window.host.listeners=window.host.listeners.filter(x=>x!==fn);};}};
  window.switchIdentity=token=>{window.host.held=false;window.host.token=token;window.host.listeners.forEach(fn=>fn());};
  window.downloadClicks=0;const click=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){window.downloadClicks++;return click.call(this);};
 },{provider,token,held});
 await page.goto(origin+'/fixture.html'+(dev?'?mode=dev':'')+'#'+hash);
 await page.waitForFunction(()=>window.d4b);
 return page;
}
async function connected(page,token='A'){await expect(page.getByTestId('portal-identity')).toHaveText(token);await expect(page.locator('.shell')).toBeVisible();}
async function run(name,fn){if(selector&&!new RegExp(selector).test(name))return;reset();const start=raw.length;try{const data=await fn();results.push({name,passed:true,rawStart:start,rawEnd:raw.length,...data});}catch(e){results.push({name,passed:false,error:e.stack,rawStart:start,rawEnd:raw.length});save('cases.json',results);throw e;}save('cases.json',results);console.log(JSON.stringify(results.at(-1)));}
function gate(key){let resolve,started;const promise=new Promise(r=>resolve=r),ready=new Promise(r=>started=r);const value={promise,started};gates.set(key,value);return {ready,release:()=>{gates.delete(key);resolve();}};}
try{
 await run('missing provider uses portal entry with no development fallback',async()=>{const page=await pageFor({provider:false});try{await expect(page.getByTestId('portal-notice')).toHaveText('请从门户打开工作台');expect(raw.filter(x=>x.url.startsWith('/auth/dev-token'))).toHaveLength(0);await page.screenshot({path:path.join(out,'missing-desktop.png'),fullPage:true});return {screenshot:'missing-desktop.png'};}finally{await page.close();}});
 await run('pending provider mounts connection entry immediately; same token identity switch clears drafts',async()=>{const page=await pageFor({held:true});try{await expect(page.getByTestId('portal-entry')).toBeVisible();await page.evaluate(()=>window.host.release('A'));await connected(page);await page.evaluate(()=>{window.old=window.d4b.capture();window.old.dialog='app';window.old.form={name:'old draft'};window.switchIdentity('A');});await connected(page);await expect(page.getByRole('dialog')).toHaveCount(0);expect(await page.evaluate(()=>window.d4b.auth.generation)).toBe(2);return {sameTokenNewGeneration:true,draftGone:true};}finally{await page.close();}});
 await run('manual reconnect supersedes held provider while connecting and ignores late old provider',async()=>{
  const page=await pageFor({held:true});try{await expect(page.getByTestId('portal-entry')).toBeVisible();await expect(page.getByTestId('portal-reconnect')).toBeEnabled({timeout:1000});await page.evaluate(()=>{window.oldRelease=window.host.release;window.host.held=false;window.host.token='B';});await page.getByTestId('portal-reconnect').click();await connected(page,'B');await page.evaluate(()=>window.oldRelease('A'));await delay(100);expect(await page.evaluate(()=>window.d4b.auth.token)).toBe('B');return {manualSupersededPendingProvider:true,lateProviderIgnored:true};}finally{await page.close();}
 });
 for(const [status,code,message] of [[401,'UNAUTHORIZED','会话已失效'],[403,'SYS-AUTH-403','无权访问'],[503,'IAM_AUTHORITY_UNAVAILABLE','会话暂不可用']])await run('session '+status+' entry and manual reconnect',async()=>{behavior.status=status;behavior.code=code;const page=await pageFor();try{await expect(page.getByTestId('portal-notice')).toContainText(message);expect(await page.evaluate(()=>window.d4b.auth.roles)).toEqual([]);expect(await page.evaluate(()=>window.host.calls)).toBe(1);behavior.status=200;behavior.code='OK';await page.getByTestId('portal-reconnect').click();await connected(page);return {status,manualRecovery:true};}finally{await page.close();}});
 await run('explicit development mode is manual and verifies session before workspace',async()=>{const page=await pageFor({provider:false,dev:true});try{await expect(page.getByRole('button',{name:'进入开发工作台'})).toBeVisible();await page.getByRole('button',{name:'进入开发工作台'}).click();await connected(page,'dev-ticket');return {devOnly:true};}finally{await page.close();}});
 await run('real axios session and business HTTP, fetch SSE and artifact omit same-origin Cookie',async()=>{
  await context.addCookies([{name:'ambient',value:'portal-cookie',url:origin}]);behavior.runStatus='RUNNING';const start=raw.length,page=await pageFor({hash:'/runs/r_cookie'});
  try{await connected(page);await page.evaluate(()=>fetch('/ambient'));await expect(page.getByRole('button',{name:'下载附件'})).toBeVisible();const download=page.waitForEvent('download');await page.getByRole('button',{name:'下载附件'}).click();const file=await download;await file.saveAs(path.join(out,'downloaded-result.txt'));await expect.poll(()=>raw.slice(start).filter(x=>x.url.endsWith('/events')).length).toBeGreaterThan(0);
   const requests=raw.slice(start);for(const category of ['/auth/session','/admin/runs/r_cookie','/admin/runs/r_cookie/events','/admin/artifacts/file/download']){const rows=requests.filter(x=>new URL(x.url,origin).pathname===category);expect(rows.length).toBeGreaterThan(0);for(const row of rows){expect(row.cookie).toBeNull();expect(row.authorization).toBe('Bearer A');}}
   expect(await page.evaluate(()=>document.cookie)).toContain('ambient=portal-cookie');return {cookiePresentInDocument:true,rawCategories:4,download:file.suggestedFilename()};
  }finally{await page.close();}
 });
 await run('late App allSettled/finally and old closures cannot mutate new identity, navigation, message or network',async()=>{
  const hold=gate('A /admin/model-entries'),page=await pageFor({hash:'/apps/app/orchestration/flow'});
  try{await connected(page);await hold.ready;await page.evaluate(()=>{window.old=window.d4b.capture();window.old.form={secret:'draft'};window.switchIdentity('B');});await connected(page,'B');await expect.poll(()=>page.evaluate(()=>window.d4b.capture().busy)).toBe(false);hold.release();await delay(100);
   const before=raw.length;await page.evaluate(async()=>{window.old.go('/stale');window.old.failure({status:401,message:'stale failure'});window.old.success('stale toast');await window.old.load();await window.old.operation(async()=>{throw Error('must not execute');});});
   expect(raw.length).toBe(before);expect(await page.evaluate(()=>location.hash)).toBe('#/apps/app/orchestration/flow');expect(await page.evaluate(()=>window.d4b.auth.token)).toBe('B');expect(await page.evaluate(()=>window.d4b.auth.roles)).toEqual(['ADMIN']);expect(await page.evaluate(()=>window.d4b.capture().form.secret)).toBeUndefined();await expect(page.getByText('stale toast',{exact:true})).toHaveCount(0);return {oldAllSettledReleased:true,oldClosuresStopped:true,newBusy:false};
  }finally{hold.release();await page.close();}
 });
 await run('late real App copy POST cannot navigate new identity or replay a POST',async()=>{
  const page=await pageFor(),hold=gate('A /admin/apps/app/copy');try{await connected(page);await page.evaluate(()=>{window.old=window.d4b.capture();window.old.startCopy({code:'app',name:'old'});window.old.copyForm={code:'copy',name:'copy'};window.copyPromise=window.old.copyApplication();});await hold.ready;await page.evaluate(()=>window.switchIdentity('B'));await connected(page,'B');hold.release();await page.evaluate(()=>window.copyPromise);expect(await page.evaluate(()=>location.hash)).toBe('#/apps');expect(raw.filter(x=>x.url==='/admin/apps/app/copy')).toHaveLength(1);return {postCount:1,hash:'#/apps'};}finally{hold.release();await page.close();}
 });
 await run('late providerOptions and published input consumers remain inside old workspace',async()=>{
  const page=await pageFor();try{await connected(page);const hold=gate('A /admin/model-providers');await page.evaluate(()=>{window.old=window.d4b.capture();window.old.create('entry');});await hold.ready;await page.evaluate(()=>window.switchIdentity('B'));await connected(page,'B');hold.release();await delay(100);expect(await page.evaluate(()=>window.d4b.capture().providerOptions)).toEqual([]);
   const schema=gate('B /v1/apps/app/input-schema');await page.evaluate(()=>location.hash='/apps/app/run');await schema.ready;await page.evaluate(()=>window.switchIdentity('C'));await connected(page,'C');schema.release();await delay(100);expect(await page.evaluate(()=>window.d4b.auth.token)).toBe('C');expect(raw.filter(x=>x.method==='POST'&&x.authorization==='Bearer C')).toHaveLength(0);return {providerOptionsCleared:true,schemaIsolated:true};
  }finally{for(const g of gates.values())g.started?.();gates.clear();await page.close();}
 });
 await run('late artifact blob from actual component cannot click after identity switch',async()=>{
  const page=await pageFor({hash:'/runs/r_blob'});try{await connected(page);await expect(page.getByRole('button',{name:'下载附件'})).toBeVisible();await page.evaluate(()=>{const native=Response.prototype.blob;Response.prototype.blob=async function(){const value=await native.call(this);await new Promise(r=>window.releaseBlob=r);return value;};});await page.getByRole('button',{name:'下载附件'}).click();await page.waitForFunction(()=>window.releaseBlob);await page.evaluate(()=>window.switchIdentity('B'));await connected(page,'B');await page.evaluate(()=>window.releaseBlob());await delay(100);expect(await page.evaluate(()=>window.downloadClicks)).toBe(0);return {downloadClicks:0};}finally{await page.close();}
 });
 await run('late chart dynamic import does not restore old workspace or busy state',async()=>{
  const held=gate('chart'),page=await pageFor({hash:'/runs'});try{await connected(page);await held.ready;await page.evaluate(()=>{window.old=window.d4b.capture();window.switchIdentity('B');});await connected(page,'B');held.release();await expect.poll(()=>page.evaluate(()=>window.d4b.capture().busy)).toBe(false);expect(await page.evaluate(()=>window.old===window.d4b.capture())).toBe(false);expect(await page.evaluate(()=>window.d4b.auth.token)).toBe('B');return {oldWorkspaceUnmounted:true,newBusy:false};}finally{held.release();await page.close();}
 });
 await run('empty roles authenticate without admin controls, expiry and unmount never auto reconnect',async()=>{
  behavior.roles=[];behavior.expiry=1200;const page=await pageFor();try{await connected(page);await expect(page.getByRole('button',{name:'创建智能体',exact:true})).toHaveCount(0);await expect(page.getByTestId('portal-entry')).toBeVisible();expect(await page.evaluate(()=>window.d4b.auth.roles)).toEqual([]);expect(await page.evaluate(()=>window.host.calls)).toBe(1);await page.evaluate(()=>document.querySelector('#app').__vue_app__.unmount());expect(await page.evaluate(()=>window.host.unsubscribed)).toBe(1);return {emptyRoles:true,expired:true,hostCalls:1,unsubscribed:1};}finally{await page.close();}
 });
 for(const consumer of ['RunDetail','DebugPanel'])for(const reason of ['AUTHORITY_EXPIRED','AUTHORITY_DENIED','AUTHORITY_UNAVAILABLE'])await run(consumer+' stops '+reason+' without status/open/cancel/POST growth',async()=>{
  behavior.reset=reason;behavior.runStatus='RUNNING';const page=await pageFor({hash:consumer==='RunDetail'?'/runs/r_control':'/apps/app/orchestration/debug'});
  try{await connected(page);if(consumer==='DebugPanel'){await expect(page.getByRole('button',{name:'开始调试'})).toBeEnabled();await page.getByRole('button',{name:'开始调试'}).click();}await expect(page.getByRole('button',{name:'重新连接观察'})).toBeVisible();const count=raw.length,post=raw.filter(x=>x.method==='POST').length;await delay(220);expect(raw.length).toBe(count);expect(raw.filter(x=>x.method==='POST')).toHaveLength(post);return {reason,consumer,requestsStable:true,postStable:true};}finally{await page.close();}
 });
 for(const consumer of ['RunDetail','DebugPanel'])await run(consumer+' stops named IAM 503 with manual recovery entry',async()=>{
  behavior.http={status:503,code:'IAM_AUTHORITY_UNAVAILABLE'};behavior.runStatus='RUNNING';const page=await pageFor({hash:consumer==='RunDetail'?'/runs/r_http':'/apps/app/orchestration/debug'});try{await connected(page);if(consumer==='DebugPanel'){await expect(page.getByRole('button',{name:'开始调试'})).toBeEnabled();await page.getByRole('button',{name:'开始调试'}).click();}await expect(page.getByRole('button',{name:'重新连接观察'})).toBeVisible();const count=raw.length;await delay(220);expect(raw.length).toBe(count);return {consumer,code:'IAM_AUTHORITY_UNAVAILABLE',requestsStable:true};}finally{await page.close();}
 });
 for(const consumer of ['RunDetail','DebugPanel'])for(const status of [401,403])await run(consumer+' stops HTTP '+status+' without retry or cancel',async()=>{
  behavior.http={status,code:status===401?'UNAUTHORIZED':'SYS-AUTH-403'};behavior.runStatus='RUNNING';const page=await pageFor({hash:consumer==='RunDetail'?'/runs/r_denied':'/apps/app/orchestration/debug'});try{await connected(page);if(consumer==='DebugPanel'){await expect(page.getByRole('button',{name:'开始调试'})).toBeEnabled();await page.getByRole('button',{name:'开始调试'}).click();}await expect(status===401?page.getByTestId('portal-entry'):page.getByRole('button',{name:'重新连接观察'})).toBeVisible();const count=raw.length;await delay(220);expect(raw.length).toBe(count);if(status===401){expect(await page.evaluate(()=>window.d4b.auth.roles)).toEqual([]);expect(await page.evaluate(()=>window.d4b.auth.user)).toBeUndefined();}return {consumer,status,requestsStable:true};}finally{await page.close();}
 });
 for(const consumer of ['RunDetail','DebugPanel'])await run(consumer+' ordinary dependency 503 retains status/reopen recovery',async()=>{
  behavior.http={status:503,code:'DEPENDENCY_UNAVAILABLE'};behavior.runStatus='RUNNING';const start=raw.length,page=await pageFor({hash:consumer==='RunDetail'?'/runs/r_transient':'/apps/app/orchestration/debug'});try{await connected(page);if(consumer==='DebugPanel'){await expect(page.getByRole('button',{name:'开始调试'})).toBeEnabled();await page.getByRole('button',{name:'开始调试'}).click();}await expect.poll(()=>raw.slice(start).filter(x=>x.url.endsWith('/events')).length).toBeGreaterThanOrEqual(2);await expect(page.getByRole('button',{name:'重新连接观察'})).toHaveCount(0);expect(raw.slice(start).filter(x=>x.url.endsWith('/cancel'))).toHaveLength(0);return {consumer,ordinary503Retried:true,cancelCount:0};}finally{await page.close();}
 });
 for(const consumer of ['RunDetail','DebugPanel'])await run(consumer+' status authorization denial after business frame stops observation',async()=>{
  behavior.runStatus='RUNNING';behavior.statusFailure={after:consumer==='RunDetail'?3:2,status:403,code:'SYS-AUTH-403'};
  const page=await pageFor({hash:consumer==='RunDetail'?'/runs/r_event_status':'/apps/app/orchestration/debug'});try{await connected(page);if(consumer==='DebugPanel'){await expect(page.getByRole('button',{name:'开始调试'})).toBeEnabled();await page.getByRole('button',{name:'开始调试'}).click();}await expect(page.getByRole('button',{name:'重新连接观察'})).toBeVisible();const count=raw.length;await delay(220);expect(raw.length).toBe(count);return {consumer,status403AfterFrame:true,requestsStable:true};}finally{await page.close();}
 });
 await run('logout clears identity and waits for explicit reconnect; desktop and narrow layout',async()=>{
  const page=await pageFor();try{
   await connected(page);await page.screenshot({path:path.join(out,'connected-desktop.png'),fullPage:true});await page.setViewportSize({width:390,height:844});
   const layout=await page.evaluate(()=>({viewport:innerWidth,width:document.documentElement.scrollWidth,inheritedMinimum:parseFloat(getComputedStyle(document.querySelector('.shell')).minWidth)}));
   expect(layout.width).toBeLessThanOrEqual(Math.max(layout.viewport,layout.inheritedMinimum));await page.screenshot({path:path.join(out,'connected-narrow.png'),fullPage:true});
   await page.getByRole('button',{name:'退出',exact:true}).click();await expect(page.getByTestId('portal-entry')).toBeVisible();expect(await page.evaluate(()=>window.d4b.auth.roles)).toEqual([]);expect(await page.evaluate(()=>window.d4b.auth.user)).toBeUndefined();expect(await page.evaluate(()=>window.host.calls)).toBe(1);
   expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:path.join(out,'signed-out-narrow.png'),fullPage:true});await page.getByTestId('portal-reconnect').click();await connected(page);
   return {screenshots:['connected-desktop.png','connected-narrow.png','signed-out-narrow.png'],layout,entryOverflow:false,manualReconnect:true};
  }finally{await page.close();}
 });
}finally{
 for(const g of gates.values())g.started?.();
 await context.close();await browser.close();await vite.close();server.closeAllConnections();await new Promise(r=>server.close(r));
 save('raw-http.json',raw);save('page-errors.json',errors);save('lifecycle-end.json',{pid:process.pid,closedAt:new Date().toISOString(),httpClosed:!server.listening,viteClosed:true,chromiumClosed:!browser.isConnected()});
 save('result.json',{phase,cwd:process.cwd(),origin,cases:results.length,passed:results.filter(x=>x.passed).length,failed:results.filter(x=>!x.passed).length,errors});
}
if(results.some(x=>!x.passed)||errors.length)process.exitCode=1;
