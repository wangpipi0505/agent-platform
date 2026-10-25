import { chromium } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'../..');
const fixture=JSON.parse(await fs.readFile(path.join(root,'.local/output-policy-live-smoke.json'),'utf8'));
const browser=await chromium.launchPersistentContext(path.join(root,'.local/chromium-output-policy-live'),{executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,viewport:{width:1440,height:960}});
try {
  const page=await browser.newPage();
  await page.goto('http://127.0.0.1:15174/w/'+fixture.publishedPassApp);
  await page.getByRole('button',{name:'开发登录',exact:true}).click();
  await page.getByRole('textbox',{name:'message',exact:true}).fill('真实网关输出格式提示验证');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await page.locator('.output-warning').waitFor({timeout:30000});
  await page.getByText('受控测试完成',{exact:true}).waitFor();
  assert.match(await page.locator('.output-warning').innerText(),/未通过格式校验/);
  assert.equal(await page.evaluate(()=>localStorage.length),0);
  await page.screenshot({path:path.join(root,'docs/evidence/frontend-output-policy-live-webapp.png')});
  await page.reload();
  await page.getByRole('button',{name:'开发登录',exact:true}).click();
  await page.locator('.output-warning').waitFor({timeout:30000});
  await page.getByText('受控测试完成',{exact:true}).waitFor();
  const sdk=await browser.newPage();
  await sdk.goto('http://127.0.0.1:15174/');
  await sdk.evaluate(async appCode=>{
    document.body.innerHTML='';
    const script=document.createElement('script'); script.src='/assistant.js';
    await new Promise((resolve,reject)=>{script.onload=resolve;script.onerror=reject;document.head.append(script);});
    window.AIAssistant.init({endpoint:location.origin,appCode,ticket:async()=>{
      const result=await fetch('/auth/dev-token',{method:'POST'}).then(r=>r.json());
      return result.data.accessToken;
    }});
  },fixture.publishedPassApp);
  await sdk.getByRole('button',{name:'打开智能助手'}).click();
  await sdk.getByRole('textbox',{name:'输入问题',exact:true}).fill('真实开发票据SDK提示验证');
  await sdk.getByRole('button',{name:'发送',exact:true}).click();
  await sdk.locator('.output-warning').waitFor({timeout:30000});
  assert.match(await sdk.locator('.output-warning').innerText(),/未通过格式校验/);
  assert.equal(await sdk.evaluate(()=>localStorage.length),0);
  await sdk.screenshot({path:path.join(root,'docs/evidence/frontend-output-policy-live-sdk.png')});
  const result={fixtureModelOnly:true,actualGateway:true,appCode:fixture.publishedPassApp,versionNo:1,webappWarning:true,restoredWarning:true,sdkDevTicketWarning:true,localStorageEmpty:true};
  await fs.writeFile(path.join(root,'.local/frontend-output-policy-live.json'),JSON.stringify(result,null,2));
  process.stdout.write(JSON.stringify(result,null,2)+'\n');
} finally {await browser.close();}
