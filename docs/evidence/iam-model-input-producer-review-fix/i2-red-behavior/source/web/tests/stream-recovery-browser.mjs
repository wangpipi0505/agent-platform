import { chromium, expect } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root = path.resolve(import.meta.dirname, '../..');
const browser = await chromium.launchPersistentContext(path.join(root,'.local/chromium-stream-recovery'), {
  executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe', headless:true,
  viewport:{width:1440,height:960}
});
const ok = data => ({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data})});
const sse = body => ({status:200,contentType:'text/event-stream',body});
const fact = (seq,type,data={}) => `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
const chunk = text => `event: chunk\ndata: ${JSON.stringify({text})}\n\n`;
const deferred = () => { let resolve; const promise = new Promise(r=>{resolve=r;});return {resolve,promise}; };
const summaries=[];
try {
  for (const sdk of [false,true]) {
    const page = await browser.newPage();
    const mode = sdk?'sdk':'webapp';
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    page.on('dialog',dialog=>dialog.accept());
    let creates=0,streams=0,reads=0,decisions=0,cancels=0;
    const cursors=[],streamTickets=[];
    const approval=deferred(),finalRead=deferred();
    const output={text:'权威最终回答，已完整恢复',outputValidation:{valid:false,onInvalid:'PASS_WITH_FLAG',reason:'fixture格式提示'}};
    await page.route('**/v1/**',async route=>{
      const url=new URL(route.request().url()),p=url.pathname;
      if (p.endsWith('/input-schema')) return route.fulfill(ok({type:'CHAT',inputs:[]}));
      if (p.endsWith('/sessions')) return route.fulfill(ok({sessionId:'s_recovery'}));
      if (p.endsWith('/turns')) { creates++;return route.fulfill(ok({runId:'r_recovery'})); }
      if (p.endsWith('/cancel')) {cancels++;return route.fulfill(ok({}));}
      if (p.endsWith('/decision')) {decisions++;approval.resolve();return route.fulfill(ok({}));}
      if (p.endsWith('/approvals')) return route.fulfill(ok(decisions?[]:[{nodeInstanceId:'approval_node',nodeDefId:'确认恢复任务',
        parameterSnapshot:{action:'read'},snapshotDigest:'fixture-digest',expiresAt:'2099-01-01T00:00:00Z'}]));
      expect(p.startsWith('/v1/runs/r_recovery')).toBe(true);
      if (p.endsWith('/events')) {
        streams++;cursors.push(route.request().headers()['last-event-id']);
        streamTickets.push(route.request().headers().authorization);
        if (streams===1) return route.fulfill(sse(fact(7,'NODE_STARTED')+chunk('临时片段，终态必须替换')));
        if (streams===2) return route.fulfill(sse('event: stream_reset\ndata: {"reason":"CURSOR_EXPIRED","eventsWatermark":999,"status":"RUNNING","chunkReplay":false}\n\n'));
        if (streams>3) throw Error('Unexpected fourth stream');
        await approval.promise;
        return route.fulfill(sse(fact(11,'NODE_STARTED')+chunk('恢复后的临时片段')+fact(13,'RUN_FINISHED',{output:{text:'事实终态仍需核对快照'}})));
      }
      reads++;
      if (reads===1) return route.fulfill(ok({runId:'r_recovery',status:'RUNNING',eventsWatermark:8}));
      if (reads===2) return route.fulfill(ok({runId:'r_recovery',status:'WAITING_APPROVAL',eventsWatermark:12}));
      await finalRead.promise;
      return route.fulfill(ok({runId:'r_recovery',status:'SUCCEEDED',eventsWatermark:13,output,fileRefs:[]}));
    });
    await page.goto('http://127.0.0.1:15175/stream-recovery.html'+(sdk?'?sdk=1':''));
    if (sdk) await page.getByRole('button',{name:'打开智能助手'}).click();
    const input=sdk?page.getByRole('textbox',{name:'输入问题'}):page.getByPlaceholder('输入问题，Shift+Enter 换行');
    const send=page.getByRole('button',{name:'发送',exact:true});
    await input.fill('长任务恢复测试');await send.click();
    await page.getByRole('button',{name:'确认执行',exact:true}).waitFor();
    if (sdk) await expect(send).toBeDisabled();else await expect(send).toHaveCount(0);
    expect(creates).toBe(1);expect(reads).toBe(2);
    await page.screenshot({path:path.join(root,'docs/evidence/frontend-stream-'+mode+'-approval.png')});
    await page.getByRole('button',{name:'确认执行',exact:true}).click();
    await page.getByText('事实终态仍需核对快照',{exact:true}).waitFor();
    if (sdk) await expect(send).toBeDisabled();else await expect(send).toHaveCount(0);
    expect(decisions).toBe(1);expect(creates).toBe(1);
    finalRead.resolve();
    await page.getByText(output.text,{exact:true}).waitFor();
    await expect(send).toBeEnabled();
    await page.getByText(/输出未通过格式校验/).waitFor();
    const shown=await page.locator(sdk?'agent-platform-assistant .log':'.chat .messages').innerText();
    expect(shown).not.toContain('临时片段');expect(shown).not.toContain('事实终态仍需核对快照');
    expect(cursors).toEqual(['0','8','12']);expect(creates).toBe(1);expect(cancels).toBe(0);
    if (sdk) expect(new Set(streamTickets).size).toBe(3);
    expect(errors).toEqual([]);
    await page.screenshot({path:path.join(root,'docs/evidence/frontend-stream-'+mode+'-final.png')});
    summaries.push({mode,creates,streams,reads,cursors,decisions,cancels,ticketRefreshed:sdk?new Set(streamTickets).size:undefined,
      authoritativeFinalReplacesChunks:true,warningVisible:true,sendBlockedUntilAuthoritativeTerminal:true});
    await page.close();
  }
  // A revoked WebApp stream must clear the existing auth state and stop recovery.
  const page=await browser.newPage();let reads=0,creates=0;
  await page.route('**/v1/**',route=>{
    const p=new URL(route.request().url()).pathname;
    if(p.endsWith('/input-schema'))return route.fulfill(ok({type:'FLOW',inputs:[]}));
    if(p.endsWith('/runs')){creates++;return route.fulfill(ok({runId:'r_revoked'}));}
    if(p.endsWith('/events'))return route.fulfill({status:401,body:'revoked'});
    reads++;return route.fulfill(ok({status:'RUNNING',eventsWatermark:1}));
  });
  await page.goto('http://127.0.0.1:15175/stream-recovery.html');
  await page.getByPlaceholder('输入问题，Shift+Enter 换行').fill('权限撤销测试');
  await page.getByRole('button',{name:'提交运行',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'401'}).waitFor();
  expect(await page.evaluate(()=>window.fixtureAuth())).toBe('');expect(reads).toBe(0);expect(creates).toBe(1);
  summaries.push({mode:'webapp-401',authCleared:true,statusReads:reads,creates});
  await page.close();
  for (const sdk of [false,true]) {
    const page=await browser.newPage(),statusStarted=deferred(),statusReply=deferred();
    let creates=0,streams=0,reads=0,cancels=0;
    await page.route('**/v1/**',async route=>{
      const p=new URL(route.request().url()).pathname;
      if(p.endsWith('/input-schema'))return route.fulfill(ok({type:'FLOW',inputs:[]}));
      if(p.endsWith('/cancel')){cancels++;return route.fulfill(ok({}));}
      if(p.endsWith('/runs')){creates++;return route.fulfill(ok({runId:'r_dispose'}));}
      if(p.endsWith('/events')){streams++;return route.fulfill(sse(chunk('卸载前临时片段')));}
      reads++;statusStarted.resolve();await statusReply.promise;
      return route.fulfill(ok({status:'RUNNING',eventsWatermark:2}));
    });
    await page.goto('http://127.0.0.1:15175/stream-recovery.html'+(sdk?'?sdk=1':''));
    if(sdk)await page.getByRole('button',{name:'打开智能助手'}).click();
    const input=sdk?page.getByRole('textbox',{name:'输入问题'}):page.getByPlaceholder('输入问题，Shift+Enter 换行');
    await input.fill('仅关闭观察');
    await page.getByRole('button',{name:sdk?'发送':'提交运行',exact:true}).click();
    await statusStarted.promise;
    await page.evaluate(()=>window.disposeFixture());statusReply.resolve();
    await page.waitForTimeout(1000);
    await expect(page.locator(sdk?'agent-platform-assistant':'.chat')).toHaveCount(0);
    expect(creates).toBe(1);expect(streams).toBe(1);expect(reads).toBe(1);expect(cancels).toBe(0);
    summaries.push({mode:sdk?'sdk-destroy':'webapp-unmount',creates,streams,reads,cancels,lateStatusIgnored:true});
    await page.close();
  }
  await fs.writeFile(path.join(root,'.local/frontend-stream-browser.json'),JSON.stringify({componentFixtureOnly:true,results:summaries},null,2));
  process.stdout.write('PASS: Chrome WebApp/SDK EOF → RUNNING → reset → WAITING_APPROVAL → authoritative terminal; cursor, single create, ticket refresh, warning and revoked auth checked.\n');
} finally {await browser.close();}
