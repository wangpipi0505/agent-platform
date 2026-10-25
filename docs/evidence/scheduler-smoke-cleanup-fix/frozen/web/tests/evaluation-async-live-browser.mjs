import fs from 'node:fs/promises';import path from 'node:path';import {spawnSync} from 'node:child_process';
if(!process.argv.includes('--g5-ready'))throw Error('仅在 root 确认 G5 服务部署就绪后使用 --g5-ready');
const scratch=path.join(import.meta.dirname,'.evaluation-async-live.mjs');
try{
 let source=await fs.readFile(path.join(import.meta.dirname,'named-inputs-live-browser.mjs'),'utf8');
 source=source.replaceAll('docs/evidence/frontend-test-inputs-live','docs/evidence/frontend-evaluation-async-live');
 const marker='const evaluated = await data(await evaluationResponse);';
 if(!source.includes(marker))throw Error('Frozen live script changed; inspect explicitly');
 source=source.replace(marker,`let evaluated = await data(await evaluationResponse);
  evidence.initialEvaluationStatus = evaluated.status;
  const observedId = evaluated.evalReportId || evaluated.id;
  if(evaluated.status !== 'ACCEPTED') {
    await page.getByText('评测已通过', {exact:true}).waitFor({timeout:60000});
    await Promise.all(pending);
    const updates=evidence.requests.filter(item=>item.path==='/admin/evaluations/'+observedId||item.path==='/admin/evaluations/'+observedId+'/resume');
    evaluated=updates.map(item=>item.response?.data).findLast(item=>item?.status==='ACCEPTED');
    assert(evaluated, 'The original report must reach accepted through actual GET/resume responses');
    evidence.observationReads=updates.filter(item=>item.method==='GET').length;
    evidence.resumeRequests=updates.filter(item=>item.method==='POST').length;
  }
  assert.equal(evidence.requests.filter(item=>item.path==='/admin/evaluations'&&item.method==='POST').length,1);
  evidence.checks.singleOriginalReportObservedToAccepted=true;`);
 // GET/list is tenant-scoped; retain only this test application's rows in saved logs.
 source=source.replace('entry.response = envelope;',"entry.response = url.pathname==='/admin/evaluations'&&request.method()==='GET'&&Array.isArray(envelope.data)?{...envelope,data:envelope.data.filter(item=>item.appCode===fixture.appCode)}:envelope;");
 await fs.writeFile(scratch,source);const run=spawnSync(process.execPath,[scratch,'--engine-ready'],{stdio:'inherit',cwd:path.resolve(import.meta.dirname,'../..')});if(run.error)throw run.error;if(run.status!==0)throw Error('Live async evaluation failed '+run.status);
}finally{await fs.rm(scratch,{force:true});}
