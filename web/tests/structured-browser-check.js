async page => {
 await page.goto('http://127.0.0.1:15175');
 await page.locator('[data-cell-id="s"]').first().click();
 await page.getByRole('button',{name:'添加输入字段',exact:true}).click();
 await page.getByRole('textbox',{name:'输入字段名称 1',exact:true}).fill('count');
 await page.locator('.field-card .el-select').first().click();
 await page.getByRole('option',{name:'数字',exact:true}).click();
 await page.getByRole('textbox',{name:'字段说明 1',exact:true}).fill('需要处理的数量');
 await page.getByRole('button',{name:'保存编排',exact:true}).click();
 const saved=JSON.parse(await page.locator('#saved').textContent());
 if(saved.definition.inputs[0].type!=='NUMBER'||saved.definition.inputs[0].name!=='count')throw Error('Structured input not saved');
 await page.setViewportSize({width:2560,height:1440});
 await page.screenshot({path:'../docs/evidence/frontend-structured-2560.png',fullPage:true});
 return {inputs:saved.definition.inputs,businessHasLayout:!!saved.definition.nodes[0].position};
}

