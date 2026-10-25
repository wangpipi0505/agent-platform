async page => {
 const dialog=page.getByRole('dialog',{name:'创建智能体'});
 await dialog.getByText('流程智能体',{exact:true}).click();await dialog.getByRole('textbox',{name:/应用名称/}).fill('前端真实联调流程');await dialog.getByRole('textbox',{name:/唯一编码/}).fill('frontend_browser_flow_20261020');await dialog.getByRole('textbox',{name:'应用说明'}).fill('独立新工程真实网关浏览器验收记录');await dialog.getByRole('textbox',{name:'专业领域'}).fill('软件验证');await dialog.getByRole('textbox',{name:'业务分类'}).fill('联调验收');
 await dialog.getByRole('button',{name:'保存',exact:true}).click();await page.getByRole('link',{name:/前端真实联调流程/}).waitFor();await page.getByRole('link',{name:/前端真实联调流程/}).click();await page.getByRole('link',{name:'编排',exact:true}).click();await page.getByRole('button',{name:'添加节点 ▾'}).waitFor();return {created:true,url:page.url(),noTokenInLocalStorage:!Object.keys(await page.evaluate(()=>({...localStorage}))).some(k=>/token/i.test(k))};
}

