async page => {
 await page.getByRole('button',{name:'保存编排'}).click();
 const first=JSON.parse(await page.locator('#saved').textContent());
 const start=await page.locator('[data-cell-id="s"]').first().boundingBox();
 await page.mouse.move(start.x+82,start.y+32);await page.mouse.down();await page.mouse.move(start.x+122,start.y+112,{steps:8});await page.mouse.up();
 await page.getByRole('button',{name:'保存编排'}).click();
 const second=JSON.parse(await page.locator('#saved').textContent());
 const result={businessUnchanged:JSON.stringify(first.definition)===JSON.stringify(second.definition),layoutChanged:JSON.stringify(first.layout)!==JSON.stringify(second.layout),undoRestoresLayout:false};
 if(!result.businessUnchanged||!result.layoutChanged)throw new Error(JSON.stringify(result));
 await page.keyboard.press('Control+z');await page.getByRole('button',{name:'保存编排'}).click();
 result.undoRestoresLayout=JSON.stringify(JSON.parse(await page.locator('#saved').textContent()).layout)===JSON.stringify(first.layout);
 if(!result.undoRestoresLayout)throw new Error('undo failed');return result;
}

