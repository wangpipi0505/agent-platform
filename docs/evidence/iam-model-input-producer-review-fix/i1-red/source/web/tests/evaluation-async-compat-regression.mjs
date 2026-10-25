import fs from 'node:fs/promises';import path from 'node:path';import {spawnSync} from 'node:child_process';
const scratch=path.join(import.meta.dirname,'.evaluation-async-compat.mjs');
try{
 for(const name of (process.argv.includes('--evaluation-only')?['named-evaluation-browser.mjs']:['named-inputs-browser.mjs','named-evaluation-browser.mjs'])){
  let source=await fs.readFile(path.join(import.meta.dirname,name),'utf8');
  source=source.replaceAll('docs/evidence/frontend-test-inputs-','docs/evidence/frontend-evaluation-async-compat-');
  if(name==='named-evaluation-browser.mjs'){
   // The frozen legacy stub omitted all report identity fields and returned the same
   // non-list object to GET. Add contract metadata, retaining its failed result and cases.
   const old="if(url.pathname==='/admin/evaluations')return route.fulfill(ok({reportId:1,passed:false}));";
   if(!source.includes(old))throw Error('Frozen compatibility fixture changed; inspect rather than silently adapting');
   source=source.replace(old,"if(url.pathname==='/admin/evaluations'){if(method==='GET')return route.fulfill(ok([]));return route.fulfill(ok({reportId:1,passed:false,id:'er_fixture',evalReportId:'er_fixture',status:'REJECTED',appCode:'named_fixture',draftRev:4,candidateSha256:'candidate-fixture-sha',createdBy:'fixture-owner',datasetVersion:route.request().postDataJSON().datasetVersion,results:[]}));}");
   // New GET/list requests are not evaluation creation attempts. Retain each original
   // no-create / exact-POST-body assertion while making its HTTP method explicit.
   source=source.replaceAll("r=>r.path==='/admin/evaluations'", "r=>r.path==='/admin/evaluations'&&r.method==='POST'");
  }
  await fs.writeFile(scratch,source);const result=spawnSync(process.execPath,[scratch,'regression'],{cwd:path.resolve(import.meta.dirname,'../..'),stdio:'inherit'});if(result.error)throw result.error;if(result.status!==0)throw Error(name+' failed '+result.status);
 }
}finally{await fs.rm(scratch,{force:true});}
