import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const out='docs/evidence/iam-stage-5-e1a';
assert.equal(process.cwd().replaceAll('\\','/'),'G:/207/项目/算法重组工具/代码/agent-platform');
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const read=f=>JSON.parse(fs.readFileSync(out+'/'+f,'utf8'));
const after=read('source-after.json');
assert.deepEqual(after,read('iam-stage-5-e1a-full-01/source-sha.json'));
for(const r of after){
  assert.equal(hash(r.file),r.sha256);
  assert.ok(fs.readFileSync(r.file).equals(fs.readFileSync(out+'/frozen/'+r.file)));
}
// Preserve the first protection snapshot; the unrelated root proposal is still being written.
for(const name of ['protected-after.json','protected-changes.json'])fs.copyFileSync(out+'/'+name,out+'/first-freeze-'+name);
const env={...process.env,GIT_CEILING_DIRECTORIES:path.dirname(path.resolve(out)),GIT_CONFIG_NOSYSTEM:'1',GIT_CONFIG_GLOBAL:'NUL',GIT_ATTR_NOSYSTEM:'1'};
for(const k of ['GIT_DIR','GIT_WORK_TREE','GIT_INDEX_FILE'])delete env[k];
const git=(args,cwd)=>spawnSync('git',['-c','core.autocrlf=false','-c','core.attributesFile=NUL',...args],{cwd:path.resolve(cwd),env,encoding:'utf8',windowsHide:true,maxBuffer:32*1024*1024});
const boundary=git(['rev-parse','--show-toplevel'],out);
fs.writeFileSync(out+'/git-boundary.log',`exit=${boundary.status}\n${boundary.stdout??''}${boundary.stderr??''}`);assert.equal(boundary.status,128);
const diff=git(['diff','--no-index','--binary','--unified=100000','--no-ext-diff','--no-textconv','--','baseline','frozen'],out);assert.equal(diff.status,1);
let patch=diff.stdout;for(const side of ['a','b'])for(const dir of ['baseline','frozen'])patch=patch.replaceAll(side+'/'+dir+'/',side+'/');
fs.writeFileSync(out+'/change.patch',patch);fs.writeFileSync(out+'/diff.stderr.log',diff.stderr??'');
assert.ok(!fs.existsSync(out+'/replay'));fs.cpSync(out+'/baseline',out+'/replay',{recursive:true});
const apply=git(['apply','--binary',path.resolve(out+'/change.patch')],out+'/replay');
fs.writeFileSync(out+'/apply.log',`exit=${apply.status}\n${apply.stdout??''}${apply.stderr??''}`);assert.equal(apply.status,0);
const replay=after.map(r=>({...r,replaySha256:hash(out+'/replay/'+r.file),byteEqual:fs.readFileSync(out+'/replay/'+r.file).equals(fs.readFileSync(out+'/frozen/'+r.file))}));
assert.ok(replay.every(r=>r.byteEqual));fs.writeFileSync(out+'/replay-check.json',JSON.stringify(replay,null,2)+'\n');
const result={sourcesBefore:read('source-before.json').length,sourcesAfter:after.length,changedFiles:read('changed-files.json').map(r=>r.file),patchSha256:hash(out+'/change.patch'),replayByteEqual:replay.length,gitBoundaryExit:boundary.status,gitDiffExit:diff.status,gitApplyExit:apply.status,applyDiagnostics:apply.stderr??'',protectionStatus:'awaiting final named root proposal disposition',sourceReturned:false,targetReturned:false};
fs.writeFileSync(out+'/patch-check.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
