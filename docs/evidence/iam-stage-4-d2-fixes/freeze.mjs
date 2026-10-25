import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';import {spawnSync} from 'node:child_process';
const out='docs/evidence/iam-stage-4-d2-fixes';
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const walk=d=>fs.readdirSync(d,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(d,e.name)):[path.join(d,e.name).replaceAll('\\','/')]);
const before=JSON.parse(fs.readFileSync(out+'/source-before.json','utf8')),after=before.map(r=>({file:r.file,sha256:hash(r.file)})),changed=after.filter((r,i)=>r.sha256!==before[i].sha256);
assert.deepEqual(changed.map(r=>r.file).sort(),['admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java','app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java']);
for(const r of after){const dest=out+'/frozen/'+r.file;fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(r.file,dest);}
fs.writeFileSync(out+'/source-after.json',JSON.stringify(after,null,2));fs.writeFileSync(out+'/changed-files.json',JSON.stringify(changed,null,2));
const protection=JSON.parse(fs.readFileSync(out+'/protected-before.json','utf8')).map(r=>({...r,actualSha256:hash(r.file),unchanged:hash(r.file)===r.sha256}));
assert.ok(protection.every(r=>r.unchanged));fs.writeFileSync(out+'/protected-check.json',JSON.stringify(protection,null,2));
const targets={};
for(const module of ['admin-common','app-management','platform-gateway']){
 const files=walk(module+'/target');targets[module]=files.length;
 const manifest=files.map(file=>({file,sha256:hash(file)}));
 for(const r of manifest){const dest=out+'/frozen-target/'+r.file;fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(r.file,dest);}
 fs.writeFileSync(out+'/'+module+'-target-sha256.json',JSON.stringify(manifest,null,2));
}
const gateway=JSON.parse(fs.readFileSync('docs/evidence/iam-stage-4-d2/platform-gateway-target-sha256.json','utf8'));assert.ok(gateway.every(r=>hash(r.file)===r.sha256));
const gitEnv=d=>({...process.env,GIT_CEILING_DIRECTORIES:path.dirname(path.resolve(d))});
const diff=spawnSync('git',['-c','core.autocrlf=false','diff','--no-index','--binary','--','baseline','frozen'],{cwd:path.resolve(out),env:gitEnv(out),encoding:'utf8',windowsHide:true,maxBuffer:32*1024*1024});
assert.equal(diff.status,1);let patch=diff.stdout;for(const side of ['a','b'])for(const dir of ['baseline','frozen'])patch=patch.replaceAll(side+'/'+dir+'/',side+'/');
fs.writeFileSync(out+'/change.patch',patch);fs.writeFileSync(out+'/diff.stderr.log',diff.stderr??'');
assert.ok(!fs.existsSync(out+'/replay'));fs.cpSync(out+'/baseline',out+'/replay',{recursive:true});
const apply=spawnSync('git',['-c','core.autocrlf=false','apply','--binary',path.resolve(out+'/change.patch')],{cwd:path.resolve(out+'/replay'),env:gitEnv(out+'/replay'),encoding:'utf8',windowsHide:true});
fs.writeFileSync(out+'/apply.log',(apply.stdout??'')+(apply.stderr??''));assert.equal(apply.status,0);
const replay=after.map(r=>({...r,replaySha256:hash(out+'/replay/'+r.file),identical:hash(out+'/replay/'+r.file)===r.sha256}));assert.ok(replay.every(r=>r.identical));
fs.writeFileSync(out+'/replay-check.json',JSON.stringify(replay,null,2));
const runs=fs.readdirSync(out,{withFileTypes:true}).filter(e=>e.isDirectory()&&e.name.startsWith('iam-stage-4-d2-fix-')).map(e=>{
 const run=JSON.parse(fs.readFileSync(out+'/'+e.name+'/run.json','utf8'));
 return {stage:e.name,exitCode:run.exitCode,xml:run.xml.length};
});
fs.writeFileSync(out+'/freeze-summary.json',JSON.stringify({sourceCount:after.length,changedCount:changed.length,protectedCount:protection.length,targets,patchSha256:hash(out+'/change.patch'),replayedBytesIdentical:true,gatewayTargetUnchanged:true,runs,frozenAt:new Date().toISOString()},null,2));
console.log(fs.readFileSync(out+'/freeze-summary.json','utf8'));
