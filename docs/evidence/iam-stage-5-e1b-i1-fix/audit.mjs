import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';
export const base='docs/evidence/iam-stage-5-e1b-i1-fix';
export const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
export const walk=p=>fs.existsSync(p)?fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>{const f=p+'/'+e.name;assert(!e.isSymbolicLink(),f);return e.isDirectory()?walk(f):[f]}):[];
export const manifest=files=>[...new Set(files)].sort().map(file=>{const b=fs.readFileSync(file);return {file,sha256:hash(b),bytes:b.length}});
export const mods=['admin-common','platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor'];
export const sources=()=>manifest(['pom.xml',...mods.flatMap(m=>[m+'/pom.xml',...walk(m+'/src')])]);
export const targets=()=>manifest(mods.flatMap(m=>walk(m+'/target')));
export const save=(name,value)=>fs.writeFileSync(base+'/'+name+'.json',JSON.stringify(value,null,2));
export const copy=(rows,folder)=>{for(const r of rows){const dest=base+'/'+folder+'/'+r.file;fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(r.file,dest)}};
const protectedWalk=p=>fs.existsSync(p)?fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>{const f=p+'/'+e.name;if(f===base||f==='docs/evidence/iam-stage-5-e1b-i1-fix-report.md'||['node_modules','dist'].includes(e.name))return [];assert(!e.isSymbolicLink());return e.isDirectory()?protectedWalk(f):[f]}):[];
export const protectedFiles=()=>manifest([...['docs','contracts','web','deploy','config'].flatMap(protectedWalk),'AGENTS.md','.tools/run-iam-module-tests.mjs']);
export const changes=(before,after)=>{const a=new Map(after.map(r=>[r.file,r])),b=new Map(before.map(r=>[r.file,r]));return [...new Set([...b.keys(),...a.keys()])].sort().filter(f=>b.get(f)?.sha256!==a.get(f)?.sha256).map(file=>({file,before:b.get(file)??null,after:a.get(file)??null}))};
if(process.argv[2]==='before'){
 assert(!fs.existsSync(base+'/source-before.json'));const src=sources(),tar=targets();save('source-before',src);save('target-before',tar);save('protected-before',protectedFiles());copy(src,'before');copy(tar.filter(r=>/^(admin-common|engine-runtime)\/target\//.test(r.file)),'target-before');
 assert.equal(src.length,356);assert.deepEqual(src,JSON.parse(fs.readFileSync('docs/evidence/iam-stage-5-e1b/source-after.json')));console.log(JSON.stringify({source:src.length,target:tar.length,protected:JSON.parse(fs.readFileSync(base+'/protected-before.json')).length}));
}
if(process.argv[2]==='archive'){
 const stage=process.argv[3],dir=base+'/'+stage;assert(!fs.existsSync(dir));fs.mkdirSync(dir);fs.copyFileSync('.local/'+stage+'.log',dir+'/raw.log');const raw=fs.readFileSync(dir+'/raw.log','utf8');const names=[...raw.matchAll(/\[INFO\] Running ([A-Za-z0-9_.$]+)/g)].map(m=>m[1]);const executed=[];
 for(const name of names){const files=mods.flatMap(m=>walk(m+'/target/surefire-reports')).filter(f=>path.basename(f)==='TEST-'+name+'.xml');assert.equal(files.length,1,name);const file=files[0],dest=dir+'/'+file;fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(file,dest);const header=fs.readFileSync(file,'utf8').match(/<testsuite\s[^>]+>/)[0],attr=k=>header.match(new RegExp(k+'="([^"]*)"'))?.[1];executed.push({name,file,sha256:hash(fs.readFileSync(file)),...Object.fromEntries(['tests','failures','errors','skipped'].map(k=>[k,+attr(k)]))})}
 const src=sources();for(const r of src){const dest=dir+'/source/'+r.file;fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(r.file,dest)}fs.writeFileSync(dir+'/source.json',JSON.stringify(src,null,2));const totals=executed.reduce((a,s)=>{for(const k of ['tests','failures','errors','skipped'])a[k]+=s[k];return a},{tests:0,failures:0,errors:0,skipped:0});const result={stage,rawSha256:hash(fs.readFileSync(dir+'/raw.log')),executed,totals,incidental:[],sourceSha256:hash(fs.readFileSync(dir+'/source.json')),buildSuccess:raw.includes('[INFO] BUILD SUCCESS')};fs.writeFileSync(dir+'/execution-audit.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}
