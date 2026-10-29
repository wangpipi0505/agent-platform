import {readFile,readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import path from 'node:path';
import {isDeepStrictEqual} from 'node:util';
import {verifyRoutes} from './verify-routes.mjs';

const root=fileURLToPath(new URL('../',import.meta.url));
const freeze=JSON.parse(await readFile(path.join(root,'contracts/freeze.json'),'utf8'));
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
const args=process.argv.slice(2);
const skillInput=args.indexOf('--skill-contract');
let skillContract=path.join(root,'contracts/skill-invocation.md');
if(skillInput!==-1){
 if(!args[skillInput+1]||args[skillInput+1].startsWith('--'))throw new Error('--skill-contract需要文件路径');
 skillContract=path.resolve(args[skillInput+1]);args.splice(skillInput,2);
 if(args.includes('--skill-contract'))throw new Error('--skill-contract不可重复');
}
// EX-05只追加工程消费映射；历史63602字节仍以原freeze摘要逐字节冻结，不能移动边界消除漂移。
const skillPrefixBytes=63602;
const skillMarker='\r\n## 16. 工程增补（EX-05）：本体manifest消费映射\r\n';
const consumption={
 version:'EX-05/1',
 transport:{adapter_type:'type',invocation_kind:'invocationKind',http_method:'httpMethod',accepted:['HTTP','SYNC','POST'],unregisteredBinding:'REJECT'},
 authentication:{scheme:'API_KEY',reference:'authRef',header:'X-API-Key',authorizationHeader:false,secretRefPrefixes:['env:AGENT_SECRET_','vault:'],tenantCheck:true},
 source:{kind:'ONTOLOGY',tool_code:'toolCode','ontology_meta.spaceCode':'spaceCode','ontology_meta.releaseId':'releaseId','ontology_meta.version':'version','ontology_meta.contentDigest':'contentDigest',preview:false},
 exposure:{'exposure.kind':'exposure.kind',QUERY:{readonly:true,sideEffect:false},MUTATION:{readonly:false,sideEffect:'PRESERVE'},risk_level:'riskLevel',riskDowngrade:'REJECT'},
 pagination:{mode:'OFFSET','exposure.pagination.offsetParam':'pagination.request.offset','exposure.pagination.limitParam':'pagination.request.size','exposure.pagination.maxLimit':'pagination.maxPageSize','exposure.pagination.maxRows':'pagination.maxRows',limitType:'POSITIVE_INT32',responsePath:'UNDECLARED',automaticPaging:false,enforcedTotalRows:false,legacyMode:'PAGE'},
 editing:{omitted:['exposure','pagination'],onOmission:'PRESERVE',explicitNull:'REJECT'},
};
function verifySkillContract(bytes,expectedHash){
 if(digest(bytes.subarray(0,skillPrefixBytes))!==expectedHash)throw new Error('冻结契约原文被改：skill-invocation前缀');
 const text=bytes.toString('utf8');
 if(!bytes.subarray(skillPrefixBytes).toString('utf8').startsWith(skillMarker)||text.split(skillMarker).length!==2)throw new Error('EX-05消费映射增补缺失、重复或边界漂移');
 const suffix=bytes.subarray(skillPrefixBytes).toString('utf8');
 const match=suffix.match(/```json\r\n([\s\S]*?)\r\n```\r\n<!-- EX-05-CONSUMPTION-END -->\r\n$/);
 if(!match)throw new Error('EX-05消费映射JSON或结束边界无效');
 const declared=JSON.parse(match[1]);
 for(const key of new Set([...Object.keys(consumption),...Object.keys(declared)])){
  if(!isDeepStrictEqual(declared[key],consumption[key]))throw new Error(`EX-05消费映射漂移：${key}`);
 }
}
if(digest(await readFile(path.join(root,'contracts/snapshot/detailed-design-v1.2.md')))!==freeze.sourceSha256)throw new Error('v1.2原文快照哈希漂移');
for(const item of freeze.contracts){
 if(item.name==='skill-invocation')verifySkillContract(await readFile(skillContract),item.sha256);
 else if(digest(await readFile(path.join(root,item.file)))!==item.sha256)throw new Error(`冻结契约原文被改：${item.name}`);
}
const modules={
 'admin-common':'common','platform-gateway':'gateway','app-management':'app',
 'engine-runtime':'engine','skill-registry':'skill','model-gateway':'model','monitor':'monitor',
};
async function javaFiles(directory){
 const result=[];for(const item of await readdir(directory,{withFileTypes:true})){const file=path.join(directory,item.name);if(item.isDirectory())result.push(...await javaFiles(file));else if(item.name.endsWith('.java'))result.push(file);}return result;
}
for(const [module,namespace] of Object.entries(modules)){
 const pom=await readFile(path.join(root,module,'pom.xml'),'utf8');
 for(const match of pom.matchAll(/<dependency>[\s\S]*?<\/dependency>/g)){
  const block=match[0];if(/<groupId>\s*cn\.agentplatform\s*<\/groupId>/.test(block)&&!/<artifactId>\s*admin-common\s*<\/artifactId>/.test(block))throw new Error(`${module}引入其他业务服务jar`);
 }
 for(const file of await javaFiles(path.join(root,module,'src'))){
  const source=await readFile(file,'utf8');
  for(const match of source.matchAll(/\bimport\s+(?:static\s+)?cn\.agentplatform\.([a-z]+)\./g))if(![namespace,'common'].includes(match[1]))throw new Error(`${path.relative(root,file)}跨业务模块导入${match[1]}`);
 }
}
JSON.parse(await readFile(path.join(root,'contracts/graph-definition.schema.json'),'utf8'));
// G-9：路由目录漂移。只在服务票据面出现 MISSING 时失败；门户面（/admin/**）与 STALE 仅告警。
const routes=verifyRoutes();
if(routes.blocking){
 const lines=routes.missing.map(item=>`  ${item.service} ${item.method} ${item.template}  <= ${item.file}:${item.line} ${item.handler}()`).join('\n');
 throw new Error(`G-9 路由目录漂移：${routes.missing.length} 个服务票据面 handler 没有目录行（运行期会抛 ROUTE_COVERAGE_MISSING）\n${lines}\n补 \`agent-service-authority.json\` 对应行：有真实调用方的按 delegated+caller 登记，无调用方的登记为 disabled+caller null+reason。`);
}
if(routes.portalSurface.length+routes.stale.length>0)console.log(`G-9 路由目录：服务票据面 0 MISSING；门户面缺行告警 ${routes.portalSurface.length} 条、STALE ${routes.stale.length} 条（均不阻断，清单见 docs/evidence/2026-10-27-G9-route-drift/README.md）`);
// EX-09：复用独立校验器的默认全集与 --file 夹具入口，保留诊断和原生失败退出码。
const capability=spawnSync(process.execPath,[path.join(root,'scripts/verify-capability-package.mjs'),...args],{stdio:'inherit',windowsHide:true});
if(capability.error)throw capability.error;
if(capability.status!==0)process.exit(capability.status??1);
console.log('v1.2快照、四契约冻结内容（skill历史前缀）、EX-05消费映射与七模块依赖边界通过');
