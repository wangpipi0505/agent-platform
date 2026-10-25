import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import {spawnSync} from 'node:child_process';import assert from 'node:assert/strict';
const [stage,modules,tests]=process.argv.slice(2),out='docs/evidence/iam-stage-4-d2-fixes/'+stage;
assert.match(stage,/^iam-stage-4-d2-fix-[a-z0-9-]+$/);assert.ok(!fs.existsSync(out));fs.mkdirSync(out,{recursive:true});
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const source=JSON.parse(fs.readFileSync('docs/evidence/iam-stage-4-d2-fixes/source-before.json','utf8')).map(r=>({file:r.file,sha256:hash(r.file)}));
fs.writeFileSync(out+'/source-sha256.json',JSON.stringify(source,null,2));
for(const file of ['admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java','app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java']){const dest=out+'/source/'+file;fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(file,dest);}
const started=Date.now();
const run=spawnSync('node',['.tools/run-iam-module-tests.mjs',stage,modules,tests],{encoding:'utf8',windowsHide:true,timeout:360000});
fs.writeFileSync(out+'/launcher.log',(run.stdout??'')+(run.stderr??''));
fs.copyFileSync('.local/'+stage+'.log',out+'/raw.log');
const xml=[];
for(const module of ['admin-common','app-management']){
 const reports=module+'/target/surefire-reports';
 for(const name of fs.readdirSync(reports).filter(f=>f.startsWith('TEST-')&&f.endsWith('.xml'))){
  const file=reports+'/'+name;if(fs.statSync(file).mtimeMs<started)continue;
  fs.mkdirSync(out+'/'+module,{recursive:true});fs.copyFileSync(file,out+'/'+module+'/'+name);xml.push({file:module+'/'+name,sha256:hash(file)});
 }
}
fs.writeFileSync(out+'/run.json',JSON.stringify({command:['node','.tools/run-iam-module-tests.mjs',stage,modules,tests],exitCode:run.status,xml,archivedAt:new Date().toISOString()},null,2));
console.log(run.stdout);console.log(JSON.stringify({archive:out,xml:xml.length,exitCode:run.status}));process.exitCode=run.status??1;
