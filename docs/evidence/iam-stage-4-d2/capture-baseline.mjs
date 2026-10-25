import fs from 'node:fs'; import path from 'node:path'; import crypto from 'node:crypto';
const root=process.cwd(), out='docs/evidence/iam-stage-4-d2';
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const walk=d=>fs.readdirSync(d,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(d,e.name)):[path.join(d,e.name).replaceAll('\\','/')]);
const modules=['admin-common','app-management','platform-gateway','engine-runtime','skill-registry','model-gateway','monitor'];
const source=modules.flatMap(m=>[m+'/pom.xml',...walk(m+'/src')]);
const protectedFiles=[...walk('contracts'),...walk('docs/evidence').filter(f=>!f.startsWith(out)),...source.filter(f=>!['admin-common','app-management','platform-gateway'].some(m=>f.startsWith(m+'/')))];
fs.writeFileSync(out+'/source-before.json',JSON.stringify(source.map(file=>({file,sha256:hash(file)})),null,2));
fs.writeFileSync(out+'/protected-before.json',JSON.stringify(protectedFiles.map(file=>({file,sha256:hash(file)})),null,2));
for(const f of source){const dest=out+'/baseline/'+f;fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(f,dest);}
console.log(JSON.stringify({source:source.length,protected:protectedFiles.length}));
