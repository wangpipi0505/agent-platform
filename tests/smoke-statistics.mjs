/** Independent development smoke: real gateway JWT chain, PG counts and concurrent rebuild. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
const root = path.resolve(import.meta.dirname,'..');
const env = Object.fromEntries((await fs.readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/).filter(x=>x&&!x.startsWith('#')).map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1)];}));
const base = 'http://127.0.0.1:18080';
const b64 = value => Buffer.from(JSON.stringify(value)).toString('base64url');
// Test identities are signed only inside this local process; keys/tokens never enter evidence.
function token(tenant, roles) {
  const now = Math.floor(Date.now()/1000);
  const signing = b64({alg:'HS256',typ:'JWT'})+'.'+b64({iss:'agent-platform-v12',aud:'agent-platform',token_use:'user',sub:'statistics-smoke',tenant_id:tenant,roles,attributes:{},iat:now,exp:now+180,jti:crypto.randomUUID()});
  return signing+'.'+crypto.createHmac('sha256',env.AGENT_JWT_SECRET).update(signing).digest('base64url');
}
async function request(url, authorization, method='GET', body, expected=200) {
  const r = await fetch(base+url,{method,headers:{Authorization:'Bearer '+authorization,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
  const raw = await r.text();
  const envelope = raw ? JSON.parse(raw) : {};
  assert.equal(r.status,expected,`${method} ${url}: ${envelope.code}`);
  return envelope.data;
}
const login = await fetch(base+'/auth/dev-token',{method:'POST'}).then(r=>r.json());
assert.equal(login.code,'OK');
const admin = login.data.accessToken;
const fixture = JSON.parse(await fs.readFile(path.join(root,'.local/chat-session-smoke-result.json'),'utf8'));
const from = new Date().toISOString().slice(0,10)+'T00:00:00Z', to = new Date().toISOString();
const filters = {from,to,appCode:fixture.appCode,callKind:'FORMAL',groupBy:'day'};
const query = '/admin/statistics?'+new URLSearchParams({...filters,page:'1',pageSize:'100'});
const report = await request(query,admin);
assert.equal(report.consistency,'PER_SOURCE_SNAPSHOT');
assert(report.groupsTotal>0 && report.groupsTotal<=100);
const runCount = report.groups.reduce((n,x)=>n+x.runs,0);
assert(runCount>=2);
const sqlQuote = value => "'"+value.replaceAll("'","''")+"'";
const sql = `select count(*) from engine.run where tenant_id='local-v12' and app_code=${sqlQuote(fixture.appCode)} and trigger_type in ('CHAT','TASK') and started_at>=${sqlQuote(from)}::timestamptz and started_at<${sqlQuote(to)}::timestamptz;`;
const pg = spawnSync(path.join(root,'.tools/postgresql-16.15/pgsql/bin/psql.exe'),['-h','127.0.0.1','-p','25432','-U',env.AGENT_DB_USER||'agent_v12','-d','agent_platform_dev_v12','-At','-v','ON_ERROR_STOP=1','-f','-'],{input:sql,encoding:'utf8',env:{...process.env,PGPASSWORD:env.AGENT_DB_PASSWORD}});
assert.equal(pg.status,0,'Independent PG source-count query failed');
assert.equal(runCount,Number(pg.stdout.trim()),'Report run count differs from authoritative source');
const body = {...filters,requestId:'stats_'+crypto.randomUUID().replaceAll('-','')};
const results = await Promise.all([0,1,2].map(()=>request('/admin/statistics/rebuild',admin,'POST',body)));
const snapshotId = results[0].snapshotId;
assert(snapshotId);
assert(results.every(x=>x.snapshotId===snapshotId),'Concurrent identical request created multiple snapshots');
const saved = await request('/admin/statistics/snapshots/'+snapshotId,admin);
assert.equal(saved.groupsTotal,report.groupsTotal);
assert.equal(saved.groups.reduce((n,x)=>n+x.runs,0),runCount);
await request('/admin/statistics/rebuild',admin,'POST',{...body,callKind:'DEBUG'},409);
const reader = token('local-v12',['READER']);
await request(query,reader,'GET',undefined,403);
await request('/admin/statistics/rebuild',reader,'POST',{...body,requestId:body.requestId+'_reader'},403);
await request('/admin/statistics/snapshots/'+snapshotId,token('statistics-other-tenant',['ADMIN']),'GET',undefined,404);
await request(query,admin.slice(0,-3)+'bad','GET',undefined,401);
const result = {fixtureOnly:true,realGatewayJwtChain:true,from,to,appCode:fixture.appCode,runCount,pgSourceCount:Number(pg.stdout.trim()),snapshotId,concurrentRequests:3,sameSnapshot:true,readerDenied:true,crossTenantHidden:true,tamperedJwtDenied:true,consistency:report.consistency};
await fs.writeFile(path.join(root,'.local/statistics-live-smoke.json'),JSON.stringify(result,null,2));
process.stdout.write(JSON.stringify(result,null,2)+'\n');
