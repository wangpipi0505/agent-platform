async page => {
 for(const type of ['START','END']){await page.getByRole('button',{name:'添加节点 ▾'}).click();await page.getByRole('menuitem',{name:type,exact:true}).click();}
 await page.locator('.x6-node').nth(1).waitFor();const boxes=await page.locator('.x6-node').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height};}));if(boxes.length!==2||boxes[0].x===boxes[1].x&&boxes[0].y===boxes[1].y)throw new Error('New nodes overlap: '+JSON.stringify(boxes));return {added:2,distinctPositions:true};
}

