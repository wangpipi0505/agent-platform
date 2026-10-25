import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
const R=process.cwd(), O=path.join(R,'.local/iam-o2b-f2c2b2-joined-chain'), E=path.join(R,'docs/evidence/iam-stage-4-o2b-f2c2b2-joined-chain');
const phase=process.argv[2];if(!/^(prepare|test)-[0-9]{2}$/.test(phase))throw Error('FINITE_PHASE');
const method=process.argv[3];if(method&&!/^[A-Za-z][A-Za-z0-9]+$/.test(method))throw Error('FINITE_METHOD');
if(phase.startsWith('test')&&method!=='slowReaderCurrentAuthorityAndExpiry')throw Error('RESUME02_ONLY_REMAINING_METHOD');
const out=path.join(E,'stages',phase);fs.mkdirSync(out,{recursive:true});if(fs.existsSync(path.join(out,'process-exit.json')))throw Error('PHASE_ALREADY_COMPLETED');
const sources=JSON.parse(fs.readFileSync('.local/iam-o2b-f2c2b2-sse-cleanup-fix/final/source-current.json','utf8').replace(/^\uFEFF/,''));
const input=JSON.parse(fs.readFileSync('.local/iam-o2b-f2c2b2-joined-chain-frozen-inputs.json','utf8'));
const ident=f=>{const b=fs.readFileSync(f);return {file:path.relative(R,f).replaceAll('\\','/'),bytes:b.length,sha256:crypto.createHash('sha256').update(b).digest('hex')}};
const save=(f,v)=>{fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,JSON.stringify(v,null,2)+'\n')};
const locked=['query-timeout','sse-cleanup'].flatMap(name=>JSON.parse(fs.readFileSync('.local/iam-o2b-f2c2b2-'+name+'-fix-acceptance.json','utf8')).scope.lockedSourceIdentities);
for(const x of locked){const actual=ident(x.file);if(actual.bytes!==x.bytes||actual.sha256!==x.sha256)throw Error('LOCKED_NESTED_FIX_CHANGED '+x.file);}
const epoch=sources.map(x=>ident(path.join(R,x.file))).sort((a,b)=>a.file.localeCompare(b.file));
fs.copyFileSync('.tools/iam-o2b-f2c2b2-joined-chain-run.mjs',path.join(out,'runner-source.mjs'));
save(path.join(out,'source.json'),epoch);for(const f of input.newSources){const p=path.join(out,'source',f);fs.mkdirSync(path.dirname(p),{recursive:true});fs.copyFileSync(f,p);}
const java='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot';
const password=process.env.AGENT_DB_PASSWORD??fs.readFileSync('.env','utf8').split(/\r?\n/).find(l=>l.startsWith('AGENT_DB_PASSWORD=')).slice('AGENT_DB_PASSWORD='.length);
const env={SystemRoot:process.env.SystemRoot,ComSpec:process.env.ComSpec,TEMP:process.env.TEMP,TMP:process.env.TMP,PATH:java+'/bin;'+process.env.PATH,JAVA_HOME:java,MAVEN_OPTS:'-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8',AGENT_DB_PASSWORD:password,AGENT_JWT_SECRET:crypto.randomBytes(48).toString('hex'),AGENT_MASTER_KEY:crypto.randomBytes(48).toString('hex'),AGENT_SECRET_DIR:path.join(O,'fresh-secrets',phase)};
const args=['-o','-B','-ntp','-pl',phase.startsWith('prepare')?'engine-runtime':'platform-gateway','-am',...(phase.startsWith('prepare')?['test-compile','org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath','-Dmdep.outputFile='+path.join(O,'engine-dependencies.txt')]:['test','-Dtest=PortalRunEventsJoinedChainTest'+(method?'#'+method:''),'-Dsurefire.failIfNoSpecifiedTests=false']),'-Dmaven.repo.local=.tools/m2','-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'];
save(path.join(out,'command.json'),{cwd:R,args:['mvn.cmd',...args],sourceEpoch:ident(path.join(out,'source.json'))});
const log=fs.openSync(path.join(out,'raw.log'),'wx');const p=spawnSync(process.env.ComSpec??'cmd.exe',['/d','/s','/c','mvn.cmd '+args.map(a=>a.includes(' ')?'"'+a+'"':a).join(' ')],{cwd:R,env,stdio:['ignore',log,log],windowsHide:true,timeout:300000});fs.closeSync(log);
save(path.join(out,'process-exit.json'),{status:p.status,signal:p.signal,error:p.error?.message??null,waitForReturned:true,raw:ident(path.join(out,'raw.log'))});
const finite=new Set();
for(const mod of ['admin-common',phase.startsWith('prepare')?'engine-runtime':'platform-gateway']){
 const status=path.join(R,mod,'target/maven-status/maven-compiler-plugin');
 function walk(dir){if(!fs.existsSync(dir))return [];return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)])}
 for(const f of walk(status).filter(f=>f.endsWith('.lst'))){finite.add(f);const dst=path.join(out,'compiler',path.relative(R,f));fs.mkdirSync(path.dirname(dst),{recursive:true});fs.copyFileSync(f,dst);if(path.basename(f)==='createdFiles.lst')for(const n of fs.readFileSync(f,'utf8').split(/\r?\n/).filter(Boolean)){const t=path.join(R,mod,'target',f.includes('testCompile')?'test-classes':'classes',n);if(fs.existsSync(t))finite.add(t);}}
 for(const type of ['main','test'])for(const f of walk(path.join(R,mod,'src',type,'resources'))){const t=path.join(R,mod,'target',type==='main'?'classes':'test-classes',path.relative(path.join(R,mod,'src',type,'resources'),f));if(fs.existsSync(t)){finite.add(t);}}
}
const jar=path.join(R,'admin-common/target/admin-common-0.1.0-SNAPSHOT-tests.jar');if(fs.existsSync(jar))finite.add(jar);
const raw=fs.readFileSync(path.join(out,'raw.log'),'utf8');const running=[...raw.matchAll(/Running (cn\.[A-Za-z0-9_.]+)/g)].map(m=>m[1]);
for(const clazz of running)for(const n of ['TEST-'+clazz+'.xml',clazz+'.txt']){const f=path.join(R,clazz.startsWith('cn.agentplatform.common')?'admin-common':'platform-gateway','target/surefire-reports',n);if(fs.existsSync(f)){finite.add(f);const dst=path.join(out,'reports',n);fs.mkdirSync(path.dirname(dst),{recursive:true});fs.copyFileSync(f,dst);}}
save(path.join(out,'target-finite.json'),[...finite].sort().map(ident));save(path.join(out,'result.json'),{exit:p.status,running,finiteTarget:finite.size,sourceCount:epoch.length});
if(phase.startsWith('prepare')&&p.status===0){const dependencies=fs.readFileSync(path.join(O,'engine-dependencies.txt'),'utf8').trim().split(path.delimiter).filter(f=>!f.includes('cn\\agentplatform')&&!f.includes('cn/agentplatform'));const cp=[path.join(R,'engine-runtime/target/test-classes'),path.join(R,'engine-runtime/target/classes'),path.join(R,'admin-common/target/classes'),jar,...dependencies].join(path.delimiter);fs.writeFileSync(path.join(O,'engine-classpath.txt'),cp);}
console.log(JSON.stringify({phase,exit:p.status,running,finiteTarget:finite.size}));process.exit(p.status??1);
