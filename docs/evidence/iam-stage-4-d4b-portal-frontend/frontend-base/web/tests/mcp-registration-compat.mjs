import fs from 'node:fs/promises';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

// Preserve the reviewed historical assertions and their artifacts. Only output
// paths and Chrome profiles are redirected into this increment's evidence.
for(const name of ['evaluation-async-browser.mjs','evaluation-async-scenarios.mjs','evaluation-async-compat-regression.mjs']){
  const scratch=path.join(import.meta.dirname,'.mcp-history-'+name);
  try{
    let source=await fs.readFile(path.join(import.meta.dirname,name),'utf8');
    source=source.replaceAll('docs/evidence/frontend-evaluation-async','docs/evidence/frontend-mcp-registration-history').replaceAll('.local/chromium-','.local/chromium-mcp-registration-history-');
    if(name.endsWith('compat-regression.mjs')){
      source=source.replace("source=source.replaceAll('docs/evidence/frontend-test-inputs-'", "source=source.replaceAll('.local/chromium-','.local/chromium-mcp-registration-history-');\n  source=source.replaceAll('docs/evidence/frontend-test-inputs-'");
    }
    await fs.writeFile(scratch,source);
    const result=spawnSync(process.execPath,[scratch,'mcp-check'],{cwd:path.resolve(import.meta.dirname,'../..'),stdio:'inherit'});
    if(result.error)throw result.error;
    if(result.status!==0)throw Error(name+' failed '+result.status);
  }finally{await fs.rm(scratch,{force:true});}
}
