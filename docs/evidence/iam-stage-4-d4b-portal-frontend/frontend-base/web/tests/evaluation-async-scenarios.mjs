import {chromium} from '@playwright/test';import fs from 'node:fs/promises';import path from 'node:path';import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'../..');
const browser=await chromium.launchPersistentContext(path.join(root,'.local/chromium-evaluation-async-scenarios'),{executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,viewport:{width:1440,height:1000}});
const evidence={fixtureOnly:true,checks:{},requests:[],pageErrors:[]};const ok=data=>({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data})});const denied=status=>({status,contentType:'application/json',body:JSON.stringify({code:'BIZ-FIXTURE',message:'fixture rejected'})});
function gate(){let release,start;const wait=new Promise(r=>release=r),started=new Promise(r=>start=r);return{release,start,wait,started};}
async function fixture(kind){
 const page=await browser.newPage();page.on('pageerror',e=>evidence.pageErrors.push(e.message));const state={kind,hash:'a'.repeat(64),resumes:0,reads:0,creates:0,status:'RUNNING',waiting:'PENDING',getGate:undefined,createGate:undefined,available:false,owner:'owner',readFailure:0,networkFailures:0};
 const report=()=>({id:'er_'+kind,evalReportId:'er_'+kind,appCode:'async_a',draftRev:3,candidateSha256:'a'.repeat(64),datasetVersion:'frozen-v1',createdBy:state.owner,status:state.status,accepted:state.status==='ACCEPTED',results:[{id:'case_one',safety:true,expected:{answer:'独立预期'},actual:state.status==='ACCEPTED'?{answer:'独立预期'}:null,passed:state.status==='ACCEPTED',runId:'r_'+kind,status:state.status==='ACCEPTED'?'SUCCEEDED':state.waiting,...(state.status==='ERROR'?{error:'依赖暂不可用',attribution:'DEPENDENCY_FAILURE'}:{})}]});
 await page.route('**/admin/**',async route=>{const url=new URL(route.request().url()),method=route.request().method();if(!url.pathname.startsWith('/admin/')||url.pathname.startsWith('/admin/src/'))return route.continue();evidence.requests.push({kind,path:url.pathname,method,body:route.request().postDataJSON()});
  if(/^\/admin\/apps\/[^/]+$/.test(url.pathname))return route.fulfill(ok({code:url.pathname.split('/').at(-1),name:'异步评测边界验证',draftId:7,draftRev:3,type:'FLOW',status:'DRAFT',config:{}}));
  if(url.pathname.endsWith('/publish/precheck'))return route.fulfill(ok({precheckId:1,draftRev:3,candidateSha256:state.hash,passed:state.status==='ACCEPTED'&&state.hash==='a'.repeat(64),report:{checks:[]}}));
  if(url.pathname==='/admin/evaluation-datasets')return route.fulfill(ok({items:[{datasetVersion:'frozen-v1'}],hasMore:false}));
  if(url.pathname.includes('/admin/evaluation-datasets/'))return route.fulfill(ok({datasetVersion:'frozen-v1',cases:[{id:'case_one',inputs:{question:'冻结输入'},expected:{answer:'独立预期'},safety:true}]}));
  if(url.pathname==='/admin/evaluations'){
   if(method==='GET')return route.fulfill(ok(state.available?[report()]:[]));state.creates++;state.available=true;
   if(state.createGate){state.createGate.start();await state.createGate.wait;}
   if(kind==='ack-lost')return route.abort('failed');return route.fulfill(ok(report()));
  }
  if(url.pathname.endsWith('/resume')){state.resumes++;if(kind!=='approval'||state.waiting!=='WAITING_APPROVAL')state.status='ACCEPTED';return route.fulfill(ok(report()));}
  if(url.pathname==='/admin/evaluations/er_'+kind){state.reads++;if(state.getGate){state.getGate.start();await state.getGate.wait;}
    if(state.readFailure)return route.fulfill(denied(state.readFailure));if(state.networkFailures-->0)return route.fulfill(denied(503));return route.fulfill(ok(report()));}
  return route.fulfill(ok([]));
 });
 const requests=()=>evidence.requests.filter(r=>r.kind===kind);
 const open=async()=>{await page.goto('http://127.0.0.1:15175/evaluation-async.html#/apps/async_a/orchestration/publish');await page.getByRole('button',{name:'运行发布预检',exact:true}).click();await page.getByRole('combobox',{name:'已保存的数据集版本',exact:true}).waitFor();};
 const create=async()=>{await page.getByRole('combobox',{name:'已保存的数据集版本',exact:true}).locator('xpath=ancestor::div[contains(@class,"el-select")][last()]').click();await page.getByRole('option',{name:'frozen-v1',exact:true}).click();await page.getByText('已载入 1 条不可变用例，执行时使用此版本保存的输入与预期值。',{exact:true}).waitFor();await page.getByRole('button',{name:'执行候选评测',exact:true}).evaluate(button=>{button.click();button.click();});};
 const choose=async()=>{await page.getByText('找回已受理报告',{exact:true}).click();await page.getByRole('combobox',{name:'已受理评测报告',exact:true}).locator('xpath=ancestor::div[contains(@class,"el-select")][last()]').click();await page.getByRole('option',{name:/frozen-v1 ·/}).click();};
 return{page,state,report,open,create,choose,requests};
}
try{
 {
  const f=await fixture('approval');f.state.waiting='WAITING_APPROVAL';await f.open();await f.create();await f.page.getByText('等待人工确认',{exact:true}).waitFor();assert.equal(await f.page.getByRole('link',{name:'前往确认中心',exact:true}).getAttribute('href'),'#/approvals');await f.page.waitForTimeout(3300);assert(f.state.resumes>=1);assert.equal(f.requests().some(r=>r.path.includes('/approvals')&&r.method==='POST'),false);assert.equal(await f.page.getByText('通过 0 · 失败 0 · 待完成 1 · 共 1 条',{exact:true}).count(),1);f.state.waiting='RUNNING';await f.page.getByText('评测已通过',{exact:true}).waitFor({timeout:7000});assert.equal(f.state.creates,1);evidence.checks.waitingApprovalNoAutoApprove=true;await f.page.close();
 }
 {
  const f=await fixture('error');f.state.status='ERROR';f.state.waiting=undefined;await f.open();await f.create();await f.page.getByRole('button',{name:'重试此报告',exact:true}).waitFor();await f.page.waitForTimeout(1200);assert.equal(f.state.resumes,0);assert.equal(f.state.reads,0);await f.page.getByRole('button',{name:'重试此报告',exact:true}).click();await f.page.getByText('评测已通过',{exact:true}).waitFor();assert.equal(f.state.creates,1);assert.equal(f.state.resumes,1);evidence.checks.errorRequiresExplicitSameReportRetry=true;await f.page.close();
 }
 {
  const f=await fixture('network');f.state.networkFailures=2;await f.open();await f.create();await f.page.getByText('连接暂时中断，正在退避重试原报告。',{exact:true}).waitFor({timeout:5000});await f.page.getByText('评测已通过',{exact:true}).waitFor({timeout:12000});assert.equal(f.state.reads,3);assert.equal(f.state.creates,1);evidence.checks.networkRetryKeepsSingleReport=true;await f.page.close();
 }
 {
  const f=await fixture('reenter');f.state.available=true;await f.open();await f.choose();await f.page.getByText('评测已通过',{exact:true}).waitFor({timeout:5000});assert.equal(f.state.creates,0);assert.equal(f.state.resumes,1);assert.equal(await f.page.getByRole('button',{name:'确认发布版本',exact:true}).isDisabled(),true);await f.page.getByRole('button',{name:'重新预检并绑定报告',exact:true}).click();await f.page.waitForFunction(()=>Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='确认发布版本')?.disabled===false);assert.equal(await f.page.getByRole('button',{name:'确认发布版本',exact:true}).isEnabled(),true);f.state.hash='b'.repeat(64);await f.page.getByRole('button',{name:'运行发布预检',exact:true}).click();await f.page.getByRole('heading',{name:'评测报告',exact:true}).waitFor({state:'hidden'});assert.equal(await f.page.getByRole('heading',{name:'评测报告',exact:true}).count(),0);assert.equal(await f.page.getByRole('button',{name:'确认发布版本',exact:true}).isDisabled(),true);
  evidence.checks.reentryUsesExistingReportAndRequiresFreshBinding=true;await f.page.close();
 }
 for(const status of [400,401,403]){
  const f=await fixture('denied'+status);f.state.readFailure=status;await f.open();await f.create();if(status===401)await f.page.getByRole('button',{name:'进入开发工作台',exact:true}).waitFor({timeout:5000});else await f.page.getByText(status===403?'没有读取或恢复此报告的权限，已停止观察。':'fixture rejected',{exact:true}).waitFor({timeout:5000});const count=f.requests().length;await f.page.waitForTimeout(1500);assert.equal(f.requests().length,count);assert.equal(f.state.resumes,0);evidence.checks['status'+status+'StopsRequests']=true;await f.page.close();
 }
 for(const target of ['cross-app','candidate','leave','permission']){
  const f=await fixture(target);const hold=gate();f.state.getGate=hold;await f.open();await f.create();await hold.started;
  if(target==='cross-app')f.page.evaluate(()=>{location.hash='/apps/async_b/orchestration/publish';});
  else if(target==='candidate'){f.state.hash='b'.repeat(64);await f.page.getByRole('button',{name:'运行发布预检',exact:true}).click();}
  else if(target==='leave')await f.page.evaluate(()=>window.disposeEvaluation());else await f.page.evaluate(()=>window.denyEvaluation());
  f.state.status='ACCEPTED';hold.release();await f.page.waitForTimeout(1400);assert.equal(f.state.resumes,0);assert.equal(f.requests().some(r=>r.path.includes('/cancel')),false);assert.equal(await f.page.getByText('评测已通过',{exact:true}).count(),0);evidence.checks[target+'DiscardsLateReport']=true;await f.page.close();
 }
 {
  const f=await fixture('ack-lost');await f.open();await f.create();await f.page.getByText('尚未确认创建结果。请刷新已受理报告列表找回，页面不会自动重建报告。',{exact:true}).waitFor();assert.equal(await f.page.getByRole('button',{name:'执行候选评测',exact:true}).isDisabled(),true);f.state.status='ACCEPTED';await f.page.getByRole('button',{name:'刷新报告列表',exact:true}).click();await f.page.getByRole('combobox',{name:'已受理评测报告',exact:true}).locator('xpath=ancestor::div[contains(@class,"el-select")][last()]').click();await f.page.getByRole('option',{name:/frozen-v1 ·/}).click();await f.page.getByText('评测已通过',{exact:true}).waitFor();assert.equal(f.state.creates,1);evidence.checks.unknownCreateAckRecoveredWithoutSecondPost=true;await f.page.close();
 }
 {
  const f=await fixture('other-owner');f.state.owner='someone-else';f.state.available=true;await f.open();await f.choose();await f.page.getByText('仅原发起者且具备编辑权限时可恢复评测。当前仅查看报告。',{exact:true}).waitFor();await f.page.waitForTimeout(1100);assert.equal(f.state.resumes,0);evidence.checks.otherOwnerIsReadOnly=true;await f.page.close();
 }
 assert.deepEqual(evidence.pageErrors,[]);
}finally{await fs.writeFile(path.join(root,'docs/evidence/frontend-evaluation-async-scenarios.json'),JSON.stringify(evidence,null,2));process.stdout.write(JSON.stringify({checks:evidence.checks,pageErrors:evidence.pageErrors})+'\n');await browser.close();}


