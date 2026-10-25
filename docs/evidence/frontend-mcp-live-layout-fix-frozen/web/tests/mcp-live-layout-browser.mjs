import { chromium } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const root=path.resolve(import.meta.dirname,'../..'),phase=process.argv[2]||'green',code=process.argv[3];
assert(/^smoke_mcp_good_[a-f0-9]{10}$/.test(code),'Read only an explicitly provided protocol smoke draft');
const browser=await chromium.launchPersistentContext(path.join(root,'.local/chromium-mcp-layout-'+phase),{executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,viewport:{width:1440,height:1000}});
const evidence={realGateway:true,mockedRoutes:0,discoveryTriggered:false,code,phase,viewports:[],requests:[],pageErrors:[]};
const page=await browser.newPage();page.on('pageerror',error=>evidence.pageErrors.push(error.message));
page.on('request',request=>{const url=new URL(request.url());if(url.pathname.startsWith('/admin/')&&!url.pathname.startsWith('/admin/src/'))evidence.requests.push({path:url.pathname,method:request.method()});});
try{
  await page.goto('http://127.0.0.1:15173/#/skills/'+code);await page.getByRole('button',{name:'进入开发工作台',exact:true}).click();await page.getByRole('region',{name:'MCP 草稿工具检查',exact:true}).waitFor();await page.getByRole('heading',{name:'技能调用统计',exact:true}).waitFor();await page.locator('.skill-statistics-filters').waitFor();await page.locator('.el-skeleton').waitFor({state:'hidden'});
  for(const width of [1440,390]){
    await page.setViewportSize({width,height:width===390?844:1000});
    const dom=await page.evaluate(()=>{const box=el=>{const r=el.getBoundingClientRect(),s=getComputedStyle(el);return{tag:el.tagName,class:typeof el.className==='string'?el.className:'',left:r.left,right:r.right,width:r.width,scrollWidth:el.scrollWidth,clientWidth:el.clientWidth,minWidth:s.minWidth,overflowX:s.overflowX,display:s.display,text:el.textContent.trim().slice(0,90)}};return{width:innerWidth,documentWidth:document.documentElement.scrollWidth,bodyWidth:document.body.scrollWidth,important:[...document.querySelectorAll('.shell,.main,.content,.topbar,.pagehead,.resource-detail,.skill-statistics-filters,.el-table')].map(box),overflow:[...document.querySelectorAll('body *')].filter(el=>{const r=el.getBoundingClientRect();return r.width>0&&(r.right>innerWidth+1||r.left<0)&&getComputedStyle(el).visibility!=='hidden'}).map(box).slice(0,45)};});
    const screenshot='docs/evidence/frontend-mcp-live-layout-fix-'+phase+'-'+width+'.png';await page.screenshot({path:path.join(root,screenshot),fullPage:true,animations:'disabled'});evidence.viewports.push({...dom,screenshot,fits:dom.documentWidth<=width});
  }
  assert.deepEqual(evidence.requests.filter(request=>request.method!=='GET'),[],'No discovery, business call, save or publish');assert.deepEqual(evidence.pageErrors,[]);assert.equal(evidence.viewports.every(viewport=>viewport.fits),true,'Full skill detail with SkillStatistics must fit 1440 and 390');
}finally{await fs.writeFile(path.join(root,'docs/evidence/frontend-mcp-live-layout-fix-'+phase+'.json'),JSON.stringify(evidence,null,2));process.stdout.write(JSON.stringify({phase,viewports:evidence.viewports.map(v=>({width:v.width,documentWidth:v.documentWidth,fits:v.fits})),requests:evidence.requests,pageErrors:evidence.pageErrors})+'\n');await browser.close();}
