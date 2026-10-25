async page => {
 await page.locator('.x6-node').filter({hasText:'START'}).first().click();
 await page.getByRole('button',{name:'添加输入字段',exact:true}).click();
 await page.getByRole('textbox',{name:'输入字段名称 1',exact:true}).fill('stage2Count');
 await page.locator('.field-card .el-select').first().click();await page.getByRole('option',{name:'数字',exact:true}).click();
 const response=page.waitForResponse(r=>r.request().method()==='PUT'&&r.url().includes('/admin/flows/'));
 await page.getByRole('button',{name:'保存编排',exact:true}).click();
 const r=await response;const body=await r.json();if(!r.ok()||body.code!=='OK')throw Error(JSON.stringify(body));
 await page.setViewportSize({width:2560,height:1440});await page.screenshot({path:'../docs/evidence/frontend-live-structured-2560.png',fullPage:true});
 return {status:r.status(),definitionInputs:body.data.definition?.inputs,draftRev:body.data.draftRev};
}
