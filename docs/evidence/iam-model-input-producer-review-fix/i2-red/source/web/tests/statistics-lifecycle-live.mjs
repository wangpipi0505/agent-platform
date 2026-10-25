import { chromium } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'../..');
const browser=await chromium.launchPersistentContext(path.join(root,'.local/chromium-statistics-lifecycle-live'),{executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,viewport:{width:1440,height:1000}});
try {
 const page=await browser.newPage();page.setDefaultTimeout(20000);const statuses=[];page.on('response',res=>{if(new URL(res.url()).pathname.startsWith('/admin/'))statuses.push({path:new URL(res.url()).pathname,status:res.status()});});
 await page.goto('http://127.0.0.1:15173/#/apps');
 const loginResponse=page.waitForResponse(res=>res.url().includes('/auth/dev-token'));
 await page.getByRole('button',{name:'进入开发工作台',exact:true}).click();
 const envelope=await(await loginResponse).json();const token=envelope.data.accessToken||envelope.data.token;
 async function get(route){const response=await page.request.get('http://127.0.0.1:15173'+route,{headers:{Authorization:'Bearer '+token}});const e=await response.json();if(!response.ok()||e.code!=='OK')throw Error(route+': '+response.status()+' '+e.message);return e.data;}
 const apps=await get('/admin/apps');if(!apps.length)throw Error('No visible source application');const source=apps.find(a=>a.currentVersionNo>=2)||apps.find(a=>a.currentVersionNo)||apps[0];
 await page.getByPlaceholder('应用编码',{exact:true}).fill(source.code);await page.waitForTimeout(500);
 const sourceRow=page.locator('.el-table__row').filter({hasText:source.code});await sourceRow.getByRole('button',{name:'复制',exact:true}).click();
 const newCode='ui_lifecycle_'+Date.now();await page.getByPlaceholder('小写字母、数字与下划线').fill(newCode);await page.getByRole('dialog').getByLabel('新应用名称',{exact:false}).fill('生命周期前端验收');
 await page.getByRole('button',{name:'创建新草稿',exact:true}).click();await page.getByText('生命周期记录',{exact:true}).waitFor();await page.getByRole('dialog',{name:'复制为新草稿'}).waitFor({state:'hidden'});
 const copied=await get('/admin/apps/'+newCode);if(copied.status!=='DRAFT'||copied.currentVersionNo!=null||copied.draftRev!==1)throw Error('Copy lifecycle mismatch');
 const logs=await get('/admin/apps/'+newCode+'/lifecycle-logs');if(!logs.some(l=>l.action==='COPY'))throw Error('No COPY log');
 await page.screenshot({path:path.join(root,'docs/evidence/frontend-statistics-lifecycle-live-copy.png'),fullPage:true});
 if(await page.getByRole('button',{name:'归档应用',exact:true}).count())throw Error('Draft exposes invalid archive action');
 await page.getByRole('link',{name:'智能体管理',exact:true}).click();await page.getByPlaceholder('应用编码',{exact:true}).fill(newCode);await page.getByText('状态（默认不含归档）',{exact:true}).click();const archivedResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/admin/apps'&&new URL(r.url()).searchParams.get('status')==='ARCHIVED');await page.getByRole('option',{name:'ARCHIVED',exact:true}).click();const archived=await archivedResponse;if(archived.status()!==200||(await archived.json()).data.length!==0)throw Error('Archived filter leaked new draft');await page.getByRole('heading',{name:'智能体管理',exact:true}).click();await page.getByText('没有匹配的应用，请调整筛选',{exact:true}).waitFor();
 await page.screenshot({path:path.join(root,'docs/evidence/frontend-statistics-lifecycle-live-archived.png')});
 await page.getByRole('link',{name:'统计分析',exact:true}).click();await page.getByRole('heading',{name:'运行分组',exact:true}).waitFor();
 await page.getByPlaceholder('全部应用',{exact:true}).fill(source.code);await page.getByRole('button',{name:'查询统计',exact:true}).click();await page.getByRole('heading',{name:'运行分组',exact:true}).waitFor();
 await page.getByRole('button',{name:'重建并保存快照',exact:true}).click();await page.getByRole('button',{name:'读取快照',exact:true}).first().waitFor();
 await page.getByRole('button',{name:'读取快照',exact:true}).first().click();await page.getByRole('heading',{name:'运行分组',exact:true}).waitFor();
 await page.getByText('统计口径与来源时刻',{exact:false}).click();await page.screenshot({path:path.join(root,'docs/evidence/frontend-statistics-lifecycle-live-statistics.png'),fullPage:true});
 const published=apps.filter(a=>a.currentVersionNo).sort((a,b)=>b.currentVersionNo-a.currentVersionNo);let diffValidated=false;
 for(const candidate of published){const versions=await get('/admin/apps/'+encodeURIComponent(candidate.code)+'/versions');if(!versions.length)continue;await page.evaluate(code=>location.hash='/apps/'+encodeURIComponent(code),candidate.code);await page.getByRole('button',{name:'对照版本',exact:true}).waitFor();await page.getByRole('button',{name:'对照版本',exact:true}).click();await page.getByText('版本摘要',{exact:true}).waitFor();await page.screenshot({path:path.join(root,'docs/evidence/frontend-statistics-lifecycle-live-diff.png'),fullPage:true});diffValidated=versions.length>=2?'twoPublishedVersions':'samePublishedVersion';break;}
 const skills=await get('/admin/skills');let skillValidated=false;if(skills.length){await page.evaluate(code=>location.hash='/skills/'+encodeURIComponent(code),skills[0].code);await page.getByRole('heading',{name:'技能调用统计',exact:true}).waitFor();await page.getByText('逻辑调用 ',{exact:false}).waitFor();await page.screenshot({path:path.join(root,'docs/evidence/frontend-statistics-lifecycle-live-skill.png'),fullPage:true});skillValidated=true;}
 const result={realGateway:true,sourceCode:source.code,copiedCode:newCode,copyDraftInvariant:true,copyLog:true,archivedQuery:true,statisticsRead:true,rebuildAndSnapshotRead:true,diffValidated,skillValidated,statuses,noPersistentToken:!Object.keys(await page.evaluate(()=>({...localStorage}))).some(k=>/token/i.test(k))};
 await fs.writeFile(path.join(root,'docs/evidence/frontend-statistics-lifecycle-live.json'),JSON.stringify(result,null,2));process.stdout.write(JSON.stringify(result)+'\n');
}finally{await browser.close();}
