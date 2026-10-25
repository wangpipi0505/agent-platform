async page => {
 await page.getByText('单步',{exact:true}).click();const created=page.waitForResponse(r=>r.url().endsWith('/admin/debug-runs')&&r.request().method()==='POST');await page.getByRole('button',{name:'开始调试'}).click();const run=await(await created).json();if(run.code!=='OK')throw new Error(JSON.stringify(run));
 await page.getByRole('heading',{name:'挂起检查器'}).waitFor();const resumed=page.waitForResponse(r=>r.url().endsWith('/resume')&&r.request().method()==='POST');await page.getByRole('button',{name:'执行本节点',exact:true}).click();const response=await resumed;const payload=response.request().postDataJSON();if(!payload.nodeInstanceId||!Number.isFinite(payload.pauseSeq)||!payload.requestId)throw new Error('Missing CAS boundary');
 await page.waitForResponse(async r=>{if(!r.url().endsWith('/status'))return false;try{const d=(await r.json()).data;return d.paused&&d.paused.nodeInstanceId!==payload.nodeInstanceId;}catch{return false;}});
 await page.getByRole('button',{name:'运行到结束',exact:true}).click();await page.waitForFunction(()=>Array.from(document.querySelectorAll('.row .status')).some(n=>n.textContent==='SUCCEEDED'));
 return {runId:run.data.runId,mode:'STEP',nextNode:true,runToEnd:true,status:'SUCCEEDED',casNodeInstanceId:payload.nodeInstanceId,pauseSeq:payload.pauseSeq};
}
