async page => {
 await page.getByRole('button',{name:'下一步'}).click();
 if(!await page.getByText('请填写名称及合法编码').count())throw new Error('Missing validation');
 await page.getByRole('textbox',{name:/技能编码/}).fill('browser_skill');await page.getByRole('textbox',{name:/技能名称/}).fill('浏览器组件测试');await page.getByRole('button',{name:'下一步'}).click();
 await page.getByRole('textbox',{name:/调用端点/}).fill('https://example.invalid/fixture');await page.getByRole('button',{name:'下一步'}).click();
 await page.getByRole('button',{name:'下一步'}).click();await page.getByRole('button',{name:'下一步'}).click();
 await page.getByRole('textbox',{name:/允许角色/}).fill('EDITOR, APPROVER');await page.getByRole('button',{name:'下一步'}).click();await page.getByRole('button',{name:'确认注册'}).click();
 const saved=JSON.parse(await page.locator('#saved').textContent());if(saved.code!=='browser_skill'||saved.guard.roles.join(',')!=='EDITOR,APPROVER'||!saved.execution.readonly)throw new Error('Incorrect registration payload');return {steps:6,payloadValidated:true,networkRegistration:false};
}
