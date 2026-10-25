import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';
const root='docs/evidence/iam-stage-5-e1b',hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const walk=p=>fs.readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(p+'/'+e.name):[p+'/'+e.name]);
const stages=[];
for(const stage of fs.readdirSync(root).filter(s=>s.startsWith('iam-stage-'))){const dir=root+'/'+stage,raw=dir+'/raw.log';if(!fs.existsSync(raw))continue;const log=fs.readFileSync(raw,'utf8');
 const names=[...log.matchAll(/\[INFO\] Running ([A-Za-z0-9_.$]+)/g)].map(m=>m[1]);const xmls=walk(dir).filter(f=>f.endsWith('.xml')&&f.includes('/surefire-reports/'));
 const executed=names.map(name=>{const file=xmls.find(f=>path.basename(f)==='TEST-'+name+'.xml');if(!file)throw Error('missing executed XML '+stage+':'+name);const xml=fs.readFileSync(file,'utf8');const header=xml.match(/<testsuite\s[^>]+>/)[0];const attr=k=>header.match(new RegExp(k+'="([^"]*)"'))?.[1];return {name,file:file.slice(dir.length+1),sha256:hash(fs.readFileSync(file)),tests:+attr('tests'),failures:+attr('failures'),errors:+attr('errors'),skipped:+attr('skipped'),time:attr('time'),rawSummary:log.split(/\r?\n/).filter(l=>l.includes('-- in '+name)).map(l=>l.trim())};});
 const incidental=xmls.filter(f=>!names.some(name=>path.basename(f)==='TEST-'+name+'.xml')).map(file=>({file:file.slice(dir.length+1),sha256:hash(fs.readFileSync(file)),counted:false}));
 const totals=executed.reduce((a,s)=>{for(const k of ['tests','failures','errors','skipped'])a[k]+=s[k];return a},{tests:0,failures:0,errors:0,skipped:0});
 const audit={stage,rawSha256:hash(fs.readFileSync(raw)),executed,incidental,totals,buildSuccess:log.includes('[INFO] BUILD SUCCESS'),note:'Only raw Running suites are executed evidence; original incidental XML and original executed-suites.json are preserved.'};
 fs.writeFileSync(dir+'/execution-audit.json',JSON.stringify(audit,null,2));stages.push({...audit,executed:executed.map(s=>s.name),incidental:incidental.length});
}
fs.writeFileSync(root+'/test-audit.json',JSON.stringify(stages,null,2));console.log(JSON.stringify(stages.map(s=>({stage:s.stage,...s.totals,incidental:s.incidental})),null,2));
