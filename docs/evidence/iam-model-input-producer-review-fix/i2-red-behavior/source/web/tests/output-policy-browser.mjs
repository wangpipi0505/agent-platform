import { chromium } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root = path.resolve(import.meta.dirname, '../..');
const browser = await chromium.launchPersistentContext(path.join(root,'.local/chromium-output-policy'), {
  executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe', headless:true,
  viewport:{width:1440,height:960}
});
try {
  for (const sdk of [false,true]) {
    const page = await browser.newPage();
    let run = 0;
    const outputs = [
      {text:'最终已修正回答',outputValidation:{valid:true}},
      {text:'保留的格式异常结果',outputValidation:{valid:false,reason:'$.count must be integer',onInvalid:'PASS_WITH_FLAG'}}
    ];
    const ok = data => ({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data})});
    await page.route('**/v1/**', async route => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith('/input-schema')) return route.fulfill(ok({type:'CHAT',inputs:[]}));
      if (url.pathname.endsWith('/sessions')) return route.fulfill(ok({sessionId:'s_component'}));
      if (url.pathname.endsWith('/turns')) { run++; return route.fulfill(ok({runId:'r_component_'+run})); }
      const output = outputs[run-1];
      if (url.pathname.endsWith('/events')) return route.fulfill({status:200,contentType:'text/event-stream',body:
        'id: 1\nevent: CHUNK\ndata: '+JSON.stringify({type:'CHUNK',text:'不应留在终态的无效流式片段'})+'\n\n'+
        'id: 2\nevent: RUN_FINISHED\ndata: '+JSON.stringify({type:'RUN_FINISHED',output})+'\n\n'});
      return route.fulfill(ok({runId:'r_component_'+run,status:'SUCCEEDED',output,fileRefs:[]}));
    });
    await page.goto('http://127.0.0.1:15175/output-policy.html'+(sdk?'?sdk=1':''));
    if (sdk) await page.getByRole('button',{name:'打开智能助手'}).click();
    const input = sdk ? page.getByRole('textbox',{name:'输入问题'}) : page.getByPlaceholder('输入问题，Shift+Enter 换行');
    for (let i=0;i<2;i++) {
      await input.fill('输出策略组件验证'+i);
      await page.getByRole('button',{name:'发送',exact:true}).click();
      await page.getByText(outputs[i].text,{exact:false}).waitFor();
      await page.waitForTimeout(100);
      const text = await page.locator(sdk?'agent-platform-assistant':'.chat').innerText();
      // innerText on host does not pierce shadow DOM, so inspect the shadow log for SDK.
      const shown = sdk ? await page.locator('agent-platform-assistant .log').innerText() : text;
      if (shown.includes('不应留在终态')) throw new Error('Invalid streamed attempt survived durable final replacement');
    }
    await page.getByText(/输出未通过格式校验/).waitFor();
    const contrast = await page.locator('.output-warning').last().evaluate(element => {
      const style = getComputedStyle(element);
      const luminance = color => {
        const [r,g,b] = color.match(/[\d.]+/g).slice(0,3).map(Number).map(x => {
          x /= 255; return x <= .04045 ? x / 12.92 : ((x + .055) / 1.055) ** 2.4;
        });
        return .2126*r + .7152*g + .0722*b;
      };
      const text = luminance(style.color), background = luminance(style.backgroundColor);
      return (Math.max(text,background)+.05)/(Math.min(text,background)+.05);
    });
    if (contrast < 4.5) throw Error('Warning text contrast below 4.5:1: '+contrast);
    await page.screenshot({path:path.join(root,'docs/evidence/frontend-output-policy-'+(sdk?'sdk':'webapp')+'.png')});
    await page.close();
  }
  await fs.writeFile(path.join(root,'.local/frontend-output-policy-browser.json'),JSON.stringify({componentFixtureOnly:true,webapp:true,sdk:true,finalSnapshotReplacesChunks:true,warningVisible:true},null,2));
  process.stdout.write('PASS: real Chromium WebApp/SDK component fixtures replace provisional output and show validation warnings.\n');
} finally { await browser.close(); }
