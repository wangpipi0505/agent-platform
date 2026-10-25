async page => {
 const nodes=page.locator('.x6-node');const source=nodes.filter({hasText:'START'}).locator('circle[port="out"]');const target=nodes.filter({hasText:'END'}).locator('circle[port="in"]');await source.dragTo(target);await page.locator('.x6-edge').waitFor();
 const response=page.waitForResponse(r=>r.url().includes('/admin/flows/frontend_browser_flow_20261020')&&r.request().method()==='PUT');await page.getByRole('button',{name:'保存编排',exact:true}).click();const saved=await (await response).json();if(saved.code!=='OK')throw new Error(JSON.stringify(saved));
 const definition=saved.data.definition; if(definition.nodes.length!==2||definition.edges.length!==1||definition.entry!==definition.nodes.find(n=>n.type==='START').id)throw new Error('Invalid saved definition');return {saved:true,draftRev:saved.data.draftRev,nodes:definition.nodes.length,edges:definition.edges.length,layoutIndependent:!('position' in definition.nodes[0])};
}
