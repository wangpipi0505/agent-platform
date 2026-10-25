import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';
const stage=process.argv[2],dir='docs/evidence/iam-stage-5-e1b/'+stage;fs.mkdirSync(dir,{recursive:true});fs.copyFileSync('.local/'+stage+'.log',dir+'/raw.log');
const walk=p=>fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(p+'/'+e.name):[p+'/'+e.name]);
const executed=new Set([...fs.readFileSync('.local/'+stage+'.log','utf8').matchAll(/\[INFO\] Running ([A-Za-z0-9_.$]+)/g)].map(m=>'TEST-'+m[1]+'.xml'));
const src=['pom.xml',...['admin-common','engine-runtime','app-management','platform-gateway','skill-registry','model-gateway','monitor'].flatMap(m=>[m+'/pom.xml',...walk(m+'/src')])];
for(const f of [...src,...['admin-common','engine-runtime'].flatMap(m=>fs.existsSync(m+'/target/surefire-reports')?walk(m+'/target/surefire-reports').filter(f=>executed.has(path.basename(f))):[])]){const out=dir+'/'+f;fs.mkdirSync(path.dirname(out),{recursive:true});fs.copyFileSync(f,out)}
fs.writeFileSync(dir+'/source.json',JSON.stringify(src.sort().map(file=>({file,sha256:crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')})),null,2));
console.log(dir);
