import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const root=process.cwd(), own='docs/evidence/iam-stage-4-d3b/', local='.local/iam-d3b/';
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const walk=p=>fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isSymbolicLink()?[]:e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]);
const item=p=>{const b=fs.readFileSync(p);return {file:p.replaceAll('\\','/'),bytes:b.length,sha256:sha(b)}};
const write=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v,null,2)+'\n')};
const prior=JSON.parse(fs.readFileSync('docs/evidence/iam-stage-5-e1b-i1-fix/source-after.json'));
const drift=prior.filter(e=>{const a=item(e.file);return a.sha256!==e.sha256||a.bytes!==e.bytes});
if(drift.length)throw Error('Prior source drift '+JSON.stringify(drift));
if(fs.existsSync(local+'before.json'))throw Error('Never overwrite original sampling');
const start=new Date().toISOString();
const sources=prior.map(e=>item(e.file));
const targets=['admin-common','app-management','engine-runtime','platform-gateway','skill-registry','model-gateway','monitor'].flatMap(m=>fs.existsSync(m+'/target')?walk(m+'/target'):[]).map(item);
// Exact task-owned paths only. The formal brief sharing the stem is protected.
const protectedPaths=['AGENTS.md','pom.xml','contracts','docs','.tools','.local'].flatMap(p=>fs.statSync(p).isDirectory()?walk(p):[p]).map(p=>p.replaceAll('\\','/')).filter(p=>!p.startsWith(own)&&!p.startsWith(local)&&p!=='.local/iam-d3b-freeze.mjs');
const protectedFiles=protectedPaths.map(item);
const docs=JSON.parse(fs.readFileSync('.local/checkpoint-iam-e1b-docs-before/manifest.json')).entries.map(e=>item(e.path));
for(const e of [...sources,...targets]){const dst=local+'before/'+e.file;fs.mkdirSync(path.dirname(dst),{recursive:true});fs.copyFileSync(e.file,dst)}
write(local+'before.json',{startedAt:start,endedAt:new Date().toISOString(),sources,targets,protectedFiles,externalDocs:docs,rootConcurrentWrite:'.local/current-work-state.json',priorManifest:item('docs/evidence/iam-stage-5-e1b-i1-fix/source-after.json')});
console.log(JSON.stringify({sources:sources.length,targets:targets.length,protected:protectedFiles.length,externalDocs:docs.length,formalBrief:protectedFiles.find(e=>e.file==='docs/evidence/iam-stage-4-d3b-brief.md'),drift:drift.length}));
