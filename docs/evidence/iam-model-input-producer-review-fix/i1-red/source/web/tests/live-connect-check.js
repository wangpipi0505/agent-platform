async page => {
 const nodes=page.locator('.x6-node');const source=await nodes.filter({hasText:'START'}).locator('circle[port="out"]').boundingBox();const target=await nodes.filter({hasText:'END'}).locator('circle[port="in"]').boundingBox();await page.mouse.move(source.x+source.width/2,source.y+source.height/2);await page.mouse.down();await page.mouse.move(source.x+20,source.y+source.height/2,{steps:5});await page.mouse.move(target.x+target.width/2,target.y+target.height/2,{steps:12});await page.mouse.up();return {source,target,edges:await page.locator('.x6-edge').count()};
}
