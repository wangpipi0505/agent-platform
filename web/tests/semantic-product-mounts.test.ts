import {readFileSync} from 'node:fs';
import {compileScript,parse} from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import {afterEach,expect,it,vi} from 'vitest';
import * as configuration from '../shared/semantic-configuration';
import {readRunAuthority} from '../shared/server-authority';
import type {CatalogRelease,SemanticApp} from '../shared/semantic-configuration';
const release:CatalogRelease={releaseRef:{spaceCode:'orders',releaseId:'11111111-1111-1111-1111-111111111111',version:'v1',contentDigest:'a'.repeat(64)},semanticExtensions:[],operations:[{opCode:'read',status:'ACTIVE'}],projection:{definitions:{objects:[{ref:{id:'Order',name:'Order'}}],actions:[],rules:[]}}};
const closes:(()=>void)[]=[];afterEach(()=>closes.splice(0).forEach(close=>close()));
async function flush(){for(let i=0;i<30;i++){await Promise.resolve();await Vue.nextTick();}}
function mountConfiguration(){
  const props=Vue.reactive({appCode:'orders-app'}),auth=Vue.reactive({token:'t',roles:['ADMIN'],generation:0,can:()=>true,snapshot:()=>({current:()=>true})});
  const writes:{path:string;body:unknown}[]=[];
  const initial:SemanticApp={code:'orders-app',draftRev:4,config:{systemPrompt:'keep',skillRefs:[{code:'verify',version:1}]}};
  const api=vi.fn(async(path:string,method='GET',body?:unknown)=>{
    if(method==='PUT'){writes.push({path,body});return {code:'orders-app',draftRev:5,config:(body as {config:unknown}).config};}
    if(path==='/admin/apps/orders-app')return initial;
    if(path==='/admin/skills/semantic-catalog')return {releases:[release],errors:[],complete:true};
    if(path==='/admin/skills')return [{code:'verify',status:'PUBLISHED'}];
    if(path==='/admin/skills/verify')return {versions:[{versionNo:1,status:'PUBLISHED',sha256:'b'.repeat(64),definition:{execution:{readonly:true,sideEffect:false},inputSchema:{type:'object'},outputSchema:{type:'object'}}}]};
    throw Error(path);
  });
  const source=readFileSync(new URL('../admin/src/pages/onboarding/SemanticConfigurationStep.vue',import.meta.url),'utf8'),{descriptor,errors}=parse(source);expect(errors).toEqual([]);
  const code=ts.transpileModule(compileScript(descriptor,{id:'SemanticConfigurationMount'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
  const exports={} as {default:Vue.Component&{render:()=>null}};
  new Function('require','exports',code)((id:string)=>id==='vue'?Vue:id.endsWith('/api')?{useApi:()=>api,useAuth:()=>auth}:id.endsWith('/semantic-configuration')?configuration:id.endsWith('.vue')?{default:{render:()=>null}}:{},exports);
  exports.default.render=()=>null;
  const renderer=Vue.createRenderer<object,object>({createComment:()=>({}),insert(){},remove(){},parentNode:()=>null,nextSibling:()=>null,createElement:()=>({}),createText:()=>({}),setText(){},setElementText(){},patchProp(){}});
  const saved=vi.fn(),app=renderer.createApp({render:()=>Vue.h(exports.default,{...props,onSaved:saved})});
  const instance=app.mount({}).$ as unknown as {subTree:{component:{setupState:Record<string,unknown>}}};closes.push(()=>app.unmount());
  return {state:instance.subTree.component.setupState,props,auth,writes,saved,api};
}
it('原组合组件真实提交config.goals和draftRev，保留现有配置并只在成功回执后emit',async()=>{
  const mounted=mountConfiguration();await flush();
  const goal={goalId:'g',criterion:{target:{objectType:'Order',objectId:'B'},readback:{skillCode:'verify',arguments:{id:'B'},objectIdPath:'$.id',resultObjectIdPath:'$.id'},predicate:{path:'$.status',operator:'equals' as const,value:false}}};
  (mounted.state.goalSubmitted as (goal:unknown)=>void)(goal);await flush();
  expect(mounted.writes).toEqual([{path:'/admin/apps/orders-app/config',body:{draftRev:4,config:{systemPrompt:'keep',skillRefs:[{code:'verify',version:1}],goals:[goal]}}}]);
  expect(mounted.saved).toHaveBeenCalledWith(expect.objectContaining({draftRev:5}));
});
it('切换应用或退出立即清除正式选择集和旧草稿',async()=>{
  const mounted=mountConfiguration();await flush();expect(mounted.state.app).toMatchObject({draftRev:4});
  mounted.auth.token='';mounted.auth.generation++;await flush();expect(mounted.state.app).toBeUndefined();expect(mounted.state.catalog).toBeUndefined();
  expect(mounted.writes).toEqual([]);
});
it('服务端UNKNOWN与数字callId保留，模型output伪造goals不起作用',()=>{
  const view=readRunAuthority('r1',{runId:'r1',goals:[{goalId:'g',outcomeState:'UNKNOWN',nextStep:'重查'}],output:{goals:[{goalId:'fake',outcomeState:'ACHIEVED'}]}},{runId:'r1',results:[{callId:41,valid:true,record:{profile:'read-only',schemaVersion:'1.0',status:'PARTIAL',factSet:{items:[{value:false}]}}}],rejected:[]});
  expect(view.goals).toEqual([expect.objectContaining({goalId:'g',outcomeState:'UNKNOWN'})]);expect(view.receipts[0].server.state).toBe('UNKNOWN');expect(view.receipts[0].callId).toBe('41');
  expect(()=>readRunAuthority('r2',{runId:'r1'},undefined)).toThrow('不一致');
});
it('生成正式引用目录白名单，不把扩展正文塞进目录协议',()=>{
  const selected={...release,semanticExtensions:[{extensionId:'ext',opCode:'read',profile:'read-only@1.0',schemaVersion:'1.0',extensionSha256:'c'.repeat(64),descriptor:{objectType:'Order',factFields:[]}}]};
  const body=configuration.bindingWrite('orders-app',{releaseRef:release.releaseRef,extensionId:'ext',opCode:'read',objectType:'Order',skill:{skillCode:'verify',skillVersion:1},profiles:['read-only@1.0'],factFields:[]},selected,{code:'verify',version:1,sha256:'b'.repeat(64),readonly:true,sideEffect:false,inputSchema:{},outputSchema:{}},'request');
  expect(body).toMatchObject({extensionDigest:'c'.repeat(64),skill:{skillCode:'verify',skillVersion:1}});expect(body).not.toHaveProperty('objectType');expect(body).not.toHaveProperty('factFields');
});
it('菜单和既有工作台挂载生产组件，SDK使用同一个服务端边界',()=>{
  const ap=readFileSync(new URL('../admin/src/router.ts',import.meta.url),'utf8'),or=readFileSync(new URL('../../../ontorepo/web/apps/onto-admin/src/views/ReleaseHubView.vue',import.meta.url),'utf8');
  expect(ap).toContain('./pages/integration/SemanticBindingsPanel.vue');expect(or).toContain('SemanticGovernancePanel');
  expect(readFileSync(new URL('../shared/Chat.vue',import.meta.url),'utf8')).toContain('<RunAuthorityPanel');
  expect(readFileSync(new URL('../assistant.js/src/runtime.ts',import.meta.url),'utf8')).toContain("readRunAuthority(run.runId,snapshot,semantic)");
});
