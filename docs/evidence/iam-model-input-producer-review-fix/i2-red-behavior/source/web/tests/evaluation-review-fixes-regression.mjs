import fs from 'node:fs/promises';import path from 'node:path';import {spawnSync} from 'node:child_process';
const names={browser:'evaluation-async-browser.mjs',scenarios:'evaluation-async-scenarios.mjs',compat:'evaluation-async-compat-regression.mjs'};
for(const [kind,name] of Object.entries(names)){
 if(process.argv[2]&&process.argv[2]!==kind)continue;
 const scratch=path.join(import.meta.dirname,'.review-recheck-'+name);
 try{
  const original=await fs.readFile(path.join(import.meta.dirname,name),'utf8');
  await fs.writeFile(scratch,original.replaceAll('docs/evidence/frontend-evaluation-async','docs/evidence/frontend-evaluation-review-fixes-recheck'));
  const result=spawnSync(process.execPath,[scratch,'patch'],{cwd:path.resolve(import.meta.dirname,'../..'),stdio:'inherit'});if(result.error)throw result.error;if(result.status!==0)throw Error(name+' failed '+result.status);
 }finally{await fs.rm(scratch,{force:true});}
}
