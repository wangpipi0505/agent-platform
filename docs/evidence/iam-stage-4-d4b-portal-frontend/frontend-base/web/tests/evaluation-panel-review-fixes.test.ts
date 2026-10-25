import {readFileSync} from 'node:fs';import {createRequire} from 'node:module';import {compileScript,parse} from '@vue/compiler-sfc';import ts from 'typescript';import * as Vue from 'vue';import {it,expect,vi} from 'vitest';import * as observer from '../admin/src/evaluation-observer';
const candidate={draftRev:3,candidateSha256:'a'.repeat(64)},dataset={datasetVersion:'v1',cases:[{id:'one',inputs:{question:'冻结输入'},expected:{answer:'独立预期'},safety:true}]};
const report={id:'er_one',evalReportId:'er_one',appCode:'app_a',...candidate,...dataset,createdBy:'owner',status:'ACCEPTED',results:[]};
function deferred(){let resolve!:(r:any)=>void;const promise=new Promise(r=>resolve=r);return{promise,resolve};}
async function flush(){await Promise.resolve();await Vue.nextTick();await Promise.resolve();}
function mount(api:any){
 const {descriptor}=parse(readFileSync(new URL('../admin/src/EvaluationPanel.vue',import.meta.url),'utf8'));const code=ts.transpileModule(compileScript(descriptor,{id:'panel'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 const auth=Vue.reactive({token:'token-one',allowed:true,user:{principalId:'owner'},can(){return this.allowed;}}),exports:any={},require=createRequire(import.meta.url);
 new Function('require','exports',code)((id:string)=>id==='vue'?Vue:id.endsWith('/api')?{useApi:()=>api,useAuth:()=>auth}:id.endsWith('/evaluation-observer')?observer:id.endsWith('.vue')?{}:require(id),exports);exports.default.render=()=>null;
 const renderer=Vue.createRenderer<any,any>({createComment:()=>({}),insert(){},remove(){},parentNode:()=>null,nextSibling:()=>null,createElement:()=>({}),createText:()=>({}),setText(){},setElementText(){},patchProp(){}});
 const props=Vue.reactive({appCode:'app_a',draftRev:3,candidate:{...candidate}}),app=renderer.createApp({render:()=>Vue.h(exports.default,props)});const instance=app.mount({}).$ as any;const state=instance.subTree.component.setupState;state.inputs={collect:()=>structuredClone(dataset)};return{state,auth,props,unmount:()=>app.unmount()};
}
it('keeps unknown ACK across permission revoke, restore and token rotation until the owner finds a report',async()=>{
 const ack=deferred();let creates=0;const api=vi.fn((path:string,method?:string)=>path==='/admin/evaluations'?(method==='POST'?(creates++,ack.promise):Promise.resolve([report])):Promise.resolve(report));const v=mount(api);await flush();const first=v.state.create();await flush();v.auth.allowed=false;v.auth.allowed=true;v.auth.token='token-two';await flush();ack.resolve(report);await first;
 expect(v.state.unknownCreate).toBe(true);await v.state.create();expect(creates).toBe(1);expect(v.state.report).toBeUndefined();v.state.selected='er_one';await v.state.choose();expect(v.state.unknownCreate).toBe(false);expect(v.state.report.id).toBe('er_one');v.unmount();
});
it('binds unknown ACK to the original candidate and principal without erasing it on return',async()=>{
 const ack=deferred();const v=mount((path:string,method?:string)=>method==='POST'?ack.promise:Promise.resolve([]));await flush();const first=v.state.create();await flush();v.props.candidate={...candidate,candidateSha256:'b'.repeat(64)};await flush();expect(v.state.unknownCreate).toBe(false);v.props.candidate={...candidate};await flush();expect(v.state.unknownCreate).toBe(true);v.auth.user={principalId:'other'};await flush();expect(v.state.unknownCreate).toBe(false);v.auth.user={principalId:'owner'};await flush();expect(v.state.unknownCreate).toBe(true);ack.resolve(report);await first;expect(v.state.report).toBeUndefined();v.unmount();
});
it('does not allow a selected report detail request and a new creation to overlap',async()=>{
 const detail=deferred();let creates=0;const v=mount((path:string,method?:string)=>path==='/admin/evaluations'?(method==='POST'?(creates++,Promise.resolve(report)):Promise.resolve([report])):detail.promise);await flush();v.state.selected='er_one';const choosing=v.state.choose();await flush();await v.state.create();expect(creates).toBe(0);detail.resolve(report);await choosing;expect(v.state.report.id).toBe('er_one');v.unmount();
});
it('does not let either list refresh finally clear the separate detail lock',async()=>{
 const detail=deferred(),listOne=deferred(),listTwo=deferred();let lists=0,creates=0;const v=mount((path:string,method?:string)=>path==='/admin/evaluations'?(method==='POST'?(creates++,Promise.resolve(report)):++lists===1?Promise.resolve([report]):lists===2?listOne.promise:listTwo.promise):detail.promise);await flush();v.state.selected='er_one';const choosing=v.state.choose();const older=v.state.list(),newer=v.state.list();listTwo.resolve([report]);await newer;listOne.resolve([report]);await older;expect(v.state.choosing).toBe(true);await v.state.create();expect(creates).toBe(0);detail.resolve(report);await choosing;expect(v.state.choosing).toBe(false);v.unmount();
});
it('resolves unknown creation only by finding the same principal and dataset',async()=>{
 let found:any=report;const v=mount((path:string,method?:string)=>method==='POST'?Promise.reject(Error('ACK lost')):Promise.resolve(path==='/admin/evaluations'?[found]:found));await flush();await v.state.create();expect(v.state.unknownCreate).toBe(true);
 found={...report,id:'er_other',evalReportId:'er_other',createdBy:'other'};v.state.selected='er_other';await v.state.choose();expect(v.state.unknownCreate).toBe(true);
 found={...report,datasetVersion:'different'};v.state.selected='er_one';await v.state.choose();expect(v.state.unknownCreate).toBe(true);
 found=report;await v.state.choose();expect(v.state.unknownCreate).toBe(false);v.unmount();
});
it('does not let an old candidate detail finally unlock the new candidate detail',async()=>{
 const first=deferred(),second=deferred();const v=mount((path:string)=>path==='/admin/evaluations'?Promise.resolve([]):path.endsWith('er_one')?first.promise:second.promise);await flush();v.state.selected='er_one';const older=v.state.choose();v.props.candidate={...candidate,candidateSha256:'b'.repeat(64)};await flush();v.state.selected='er_two';const newer=v.state.choose();first.resolve(report);await older;expect(v.state.choosing).toBe(true);expect(v.state.report).toBeUndefined();second.resolve({...report,id:'er_two',evalReportId:'er_two',candidateSha256:'b'.repeat(64)});await newer;expect(v.state.choosing).toBe(false);expect(v.state.report.id).toBe('er_two');v.unmount();
});
