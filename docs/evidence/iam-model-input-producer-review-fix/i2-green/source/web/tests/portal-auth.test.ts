import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {createPinia, setActivePinia} from 'pinia';
import {useAuth, createApi} from '../shared/api';
import {createPortalSession, portalMode, trustedPortal} from '../shared/portal-auth';
import {downloadArtifact} from '../shared/artifact';
const session=(roles:string[]=[])=>({tenantId:'tenant',principalId:'person',roles,expiresAt:new Date(Date.now()+60_000).toISOString()});
const reply=(data:any,status=200,code='OK')=>new Response(JSON.stringify({code,data}),{status});
const deferred=<T>()=>{let resolve!:(value:T)=>void;const promise=new Promise<T>(r=>resolve=r);return {promise,resolve};};
beforeEach(()=>setActivePinia(createPinia()));
afterEach(()=>{vi.restoreAllMocks();vi.useRealTimers();vi.unstubAllGlobals();});
it('D4b lifecycle logout unsubscribes and old queued callbacks cannot revive or replace a later subscription',async()=>{
 const auth=useAuth(),callbacks:Array<()=>void>=[],getAccessToken=vi.fn(async()=> 'same'),unsubscribe=vi.fn();
 const subscribe=vi.fn((listener:()=>void)=>{callbacks.push(listener);return ()=>{unsubscribe();listener();};});
 const portal=createPortalSession(auth,{getAccessToken,onSessionChanged:subscribe},{mode:'portal',transport:async()=>reply(session(['ADMIN']))});
 await portal.connect();const old=callbacks[0];auth.invalidate(auth.generation,'signed-out');expect(unsubscribe).toHaveBeenCalledTimes(1);
 old();await Promise.resolve();expect(getAccessToken).toHaveBeenCalledTimes(1);expect(auth.token).toBe('');
 await portal.connect();expect(subscribe).toHaveBeenCalledTimes(2);const generation=auth.generation;old();await Promise.resolve();expect(getAccessToken).toHaveBeenCalledTimes(2);expect(auth.generation).toBe(generation);
 callbacks[1]();await vi.waitFor(()=>expect(auth.generation).toBeGreaterThan(generation));await vi.waitFor(()=>expect(auth.token).toBe('same'));expect(subscribe).toHaveBeenCalledTimes(2);
 portal.dispose();portal.dispose();expect(unsubscribe).toHaveBeenCalledTimes(2);
});
it('D4b lifecycle synchronous subscribe and unsubscribe notifications do not recursively connect',async()=>{
 const auth=useAuth(),getAccessToken=vi.fn(async()=> 'latest'),unsubscribe=vi.fn();
 const subscribe=vi.fn((listener:()=>void)=>{listener();return ()=>{unsubscribe();listener();};});
 const portal=createPortalSession(auth,{getAccessToken,onSessionChanged:subscribe},{mode:'portal',transport:async()=>reply(session())});
 await portal.connect();expect(getAccessToken).toHaveBeenCalledTimes(1);expect(auth.token).toBe('latest');
 auth.invalidate(auth.generation,'signed-out');expect(unsubscribe).toHaveBeenCalledTimes(1);expect(getAccessToken).toHaveBeenCalledTimes(1);expect(auth.token).toBe('');portal.dispose();
});
it('D4b lifecycle current401 pauses host notifications until one explicit reconnect',async()=>{
 const auth=useAuth(),getAccessToken=vi.fn(async()=> 'ticket'),subscribe=vi.fn();let notify!:()=>void;
 subscribe.mockImplementation((listener:()=>void)=>{notify=listener;return ()=>{};});
 const portal=createPortalSession(auth,{getAccessToken,onSessionChanged:subscribe},{mode:'portal',transport:async()=>reply(session(['ADMIN']))});
 await portal.connect();auth.invalidate(auth.generation);const old=notify;old();await Promise.resolve();expect(getAccessToken).toHaveBeenCalledTimes(1);expect(auth.token).toBe('');expect(auth.roles).toEqual([]);
 await portal.connect();expect(getAccessToken).toHaveBeenCalledTimes(2);expect(subscribe).toHaveBeenCalledTimes(2);old();await Promise.resolve();expect(getAccessToken).toHaveBeenCalledTimes(2);portal.dispose();
});
it.each([403,503])('D4b lifecycle session HTTP %s entry accepts only manual retry',async status=>{
 const auth=useAuth(),getAccessToken=vi.fn(async()=> 'ticket');let notify!:()=>void,available=false;
 const subscribe=vi.fn((listener:()=>void)=>{notify=listener;return ()=>{};});
 const portal=createPortalSession(auth,{getAccessToken,onSessionChanged:subscribe},{mode:'portal',transport:async()=>reply(session(),available?200:status)});
 await portal.connect();expect(auth.connection).toBe(status===403?'forbidden':'unavailable');notify();await Promise.resolve();expect(getAccessToken).toHaveBeenCalledTimes(1);expect(auth.token).toBe('');
 available=true;await portal.connect();expect(auth.token).toBe('ticket');expect(getAccessToken).toHaveBeenCalledTimes(2);expect(subscribe).toHaveBeenCalledTimes(1);portal.dispose();
});
it('D4b lifecycle active host change supersedes pending provider while preserving a single subscription',async()=>{
 const auth=useAuth(),first=deferred<string>();let notify!:()=>void,calls=0;
 const subscribe=vi.fn((listener:()=>void)=>{notify=listener;return ()=>{};});
 const portal=createPortalSession(auth,{getAccessToken:()=>++calls===1?first.promise:Promise.resolve('B'),onSessionChanged:subscribe},{mode:'portal',transport:async()=>reply(session())});
 const pending=portal.connect();notify();await vi.waitFor(()=>expect(auth.token).toBe('B'));first.resolve('A');await pending;expect(auth.token).toBe('B');expect(subscribe).toHaveBeenCalledTimes(1);portal.dispose();
});
it('D4b lifecycle wall rollback cannot extend a sixty-second installed lifetime',async()=>{
 vi.useFakeTimers({toFake:['setTimeout','clearTimeout','Date']});let monotonic=100;vi.spyOn(performance,'now').mockImplementation(()=>monotonic);
 const auth=useAuth(),portal=createPortalSession(auth,{getAccessToken:async()=> 'ticket',onSessionChanged:()=>()=>{}},{mode:'portal',transport:async()=>reply(session(['ADMIN']))});
 await portal.connect();vi.setSystemTime(Date.now()-3_600_000);monotonic+=60_001;await vi.advanceTimersByTimeAsync(60_001);
 expect(auth.token).toBe('');expect(auth.user).toBeUndefined();expect(auth.roles).toEqual([]);expect(auth.connection).toBe('expired');portal.dispose();expect(vi.getTimerCount()).toBe(0);
});
it('D4b lifecycle stale expiry callback cannot clear a newer generation',async()=>{
 vi.useFakeTimers();const timeout=vi.spyOn(globalThis,'setTimeout');let token='A',lifetime=60_000;
 const auth=useAuth(),portal=createPortalSession(auth,{getAccessToken:async()=>token,onSessionChanged:()=>()=>{}},{mode:'portal',transport:async()=>reply({...session(['ADMIN']),expiresAt:new Date(Date.now()+lifetime).toISOString()})});
 await portal.connect();const previous=timeout.mock.calls.find(x=>x[1]===60_000)?.[0] as ()=>void;expect(previous).toBeTypeOf('function');
 token='B';lifetime=120_000;await portal.connect();const generation=auth.generation;vi.setSystemTime(Date.now()+60_001);previous();expect(auth.token).toBe('B');expect(auth.generation).toBe(generation);portal.dispose();expect(vi.getTimerCount()).toBe(0);
});
it.each([401,403,503])('D4b fix artifact preserves HTTP %s without reading error body or global auth',async status=>{
 const response=new Response('not read',{status}),readBody=vi.fn();response.json=readBody;response.text=readBody;
 vi.stubGlobal('fetch',async()=>response);const auth=useAuth();auth.token='current';auth.roles=['ADMIN'];auth.user={principalId:'current'};
 await expect(downloadArtifact({downloadUrl:'/admin/artifacts/a/download'},'r','current','',true)).rejects.toMatchObject({status,message:`下载附件失败 (${status})`});
 expect(readBody).not.toHaveBeenCalled();expect(auth.token).toBe('current');expect(auth.roles).toEqual(['ADMIN']);
});
it('D4b fix artifact late old 401 is aborted before it can be classified as current',async()=>{
 const response=deferred<Response>(),controller=new AbortController();let current=true;
 vi.stubGlobal('fetch',()=>response.promise);
 const pending=downloadArtifact({downloadUrl:'/admin/artifacts/a/download'},'r','old','',true,{signal:controller.signal,current:()=>current});
 current=false;response.resolve(new Response('',{status:401}));await expect(pending).rejects.toMatchObject({name:'AbortError'});
});
it('D4b fix artifact retains successful unscoped five-argument download',async()=>{
 const click=vi.fn();vi.stubGlobal('document',{createElement:()=>({click})});vi.stubGlobal('fetch',async()=>new Response('file'));
 const create=vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:controlled'),revoke=vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{});
 try{await downloadArtifact({externalDownloadUrl:'/v1/runs/r/artifacts/a/download'},'r','ticket','',false);expect(click).toHaveBeenCalledTimes(1);expect(revoke).toHaveBeenCalledWith('blob:controlled');}finally{create.mockRestore();revoke.mockRestore();}
});
it('D4b late artifact blob cannot create a download after session change',async()=>{
 const blob=deferred<Blob>(),response=new Response('file');response.blob=()=>blob.promise;
 const transport=vi.fn(async()=>response);vi.stubGlobal('fetch',transport);
 const create=vi.spyOn(URL,'createObjectURL'),controller=new AbortController();let active=true;
 const pending=downloadArtifact({downloadUrl:'/admin/artifacts/a/download'},'r','token','',true,{signal:controller.signal,current:()=>active});
 await Promise.resolve();active=false;blob.resolve(new Blob(['old']));await expect(pending).rejects.toMatchObject({name:'AbortError'});expect(create).not.toHaveBeenCalled();expect(transport).toHaveBeenCalledWith('/admin/artifacts/a/download',expect.objectContaining({credentials:'omit',signal:controller.signal}));create.mockRestore();
});
it('defaults to portal, allows only explicit dev, and rejects unknown modes',()=>{
 expect(portalMode(undefined)).toBe('portal');expect(portalMode('dev')).toBe('dev');expect(()=>portalMode('production')).toThrow('VITE_AUTH_MODE');
});
it('validates explicit host provider and preserves method this',async()=>{
 const auth=useAuth(),data=session();
 const host={marker:'host',getAccessToken(){expect(this.marker).toBe('host');return Promise.resolve('candidate');},onSessionChanged(){expect(this.marker).toBe('host');return ()=>{};}};
 const portal=createPortalSession(auth,trustedPortal({agentPlatformPortal:host}),{mode:'portal',transport:async()=>reply(data)});
 await portal.connect();expect(auth.token).toBe('candidate');expect(auth.roles).toEqual([]);expect(auth.user).toEqual(data);expect(auth.can('edit')).toBe(false);portal.dispose();
 expect(trustedPortal({agentPlatformPortal:{getAccessToken:'bad'}})).toBeUndefined();
});
it('missing provider shows portal entry without fetching dev token',async()=>{
 const transport=vi.fn();const auth=useAuth(),portal=createPortalSession(auth,undefined,{mode:'portal',transport});
 await portal.connect();expect(auth.connection).toBe('missing');expect(auth.notice).toBe('请从门户打开工作台');expect(transport).not.toHaveBeenCalled();portal.dispose();
});
it.each([401,403,503])('session HTTP %s never installs a candidate or historical roles',async status=>{
 const auth=useAuth();auth.token='old';auth.roles=['ADMIN'];auth.user={principalId:'old'};
 const portal=createPortalSession(auth,{getAccessToken:async()=> 'candidate',onSessionChanged:()=>()=>{}},{mode:'portal',transport:async()=>reply(session(),status)});
 await portal.connect();expect(auth.token).toBe('');expect(auth.roles).toEqual([]);expect(auth.user).toBeUndefined();expect(auth.connection).toBe(status===401?'unauthenticated':status===403?'forbidden':'unavailable');portal.dispose();
});
it.each([
 {tenantId:'tenant',principalId:'person',roles:[],expiresAt:'bad'},
 {tenantId:'tenant',principalId:'person',roles:[],expiresAt:'2020-01-01T00:00:00Z'},
 {...session(),extra:'unexpected'}, {...session(),roles:['']}, {...session(),tenantId:''},
])('rejects malformed/expired exact session contract %j',async data=>{
 const auth=useAuth(),portal=createPortalSession(auth,{getAccessToken:async()=> 'candidate',onSessionChanged:()=>()=>{}},{mode:'portal',transport:async()=>reply(data)});
 await portal.connect();expect(auth.token).toBe('');expect(auth.user).toBeUndefined();portal.dispose();
});
it('same token reconnect creates a new generation and aborts old API/body and old API factory',async()=>{
 const auth=useAuth(),portal=createPortalSession(auth,{getAccessToken:async()=> 'same',onSessionChanged:()=>()=>{}},{mode:'portal',transport:async()=>reply(session())});
 await portal.connect();const snapshot=auth.snapshot(),body=deferred<any>(),response=reply(null);response.json=()=>body.promise;
 const transport=vi.fn(async()=>response),api=createApi(()=>auth.token,transport,()=>auth.invalidate(snapshot.generation),snapshot);
 const pending=api('/late');await Promise.resolve();await portal.connect();body.resolve({code:'OK',data:'old'});
 await expect(pending).rejects.toMatchObject({name:'AbortError'});expect(snapshot.signal.aborted).toBe(true);expect(auth.generation).toBeGreaterThan(snapshot.generation);
 await expect(api('/new-post','POST')).rejects.toMatchObject({name:'AbortError'});expect(transport).toHaveBeenCalledTimes(1);expect(auth.token).toBe('same');portal.dispose();
});
it('late provider and late session cannot replace a later connected identity',async()=>{
 const auth=useAuth(),first=deferred<string>(),sessionReply=deferred<Response>();let calls=0;
 const portal=createPortalSession(auth,{getAccessToken:()=>++calls===1?first.promise:Promise.resolve('new'),onSessionChanged:()=>()=>{}},{mode:'portal',transport:async()=>reply(session(['EDITOR']))});
 const old=portal.connect();await portal.connect();first.resolve('old');await old;expect(auth.token).toBe('new');
 const second=createPortalSession(auth,{getAccessToken:async()=> 'older',onSessionChanged:()=>()=>{}},{mode:'portal',transport:()=>sessionReply.promise});
 const late=second.connect();await Promise.resolve();await portal.connect();sessionReply.resolve(reply(session(['ADMIN'])));await late;expect(auth.token).toBe('new');expect(auth.roles).toEqual(['EDITOR']);second.dispose();portal.dispose();
});
it('D4b lifecycle expiry waits for manual reconnect and logout unsubscribes before disposal',async()=>{
 vi.useFakeTimers();const auth=useAuth(),unsubscribe=vi.fn(),getAccessToken=vi.fn(async()=> 'token');let changed!:()=>void;
 const portal=createPortalSession(auth,{getAccessToken,onSessionChanged:fn=>{changed=fn;return unsubscribe;}},{mode:'portal',transport:async()=>reply(session())});
 await portal.connect();await vi.advanceTimersByTimeAsync(60_001);expect(auth.token).toBe('');expect(auth.roles).toEqual([]);expect(getAccessToken).toHaveBeenCalledTimes(1);expect(auth.connection).toBe('expired');
 changed();await Promise.resolve();expect(auth.token).toBe('');expect(getAccessToken).toHaveBeenCalledTimes(1);
 await portal.connect();expect(auth.token).toBe('token');auth.invalidate(auth.generation,'signed-out');expect(getAccessToken).toHaveBeenCalledTimes(2);
 portal.dispose();expect(unsubscribe).toHaveBeenCalledTimes(2);expect(vi.getTimerCount()).toBe(0);
});
it('segments a far future deadline and rechecks actual time before expiring',async()=>{
 vi.useFakeTimers();const auth=useAuth(),data={...session(),expiresAt:new Date(Date.now()+3_000_000_000).toISOString()};
 const portal=createPortalSession(auth,{getAccessToken:async()=> 'token',onSessionChanged:()=>()=>{}},{mode:'portal',transport:async()=>reply(data)});
 await portal.connect();await vi.advanceTimersByTimeAsync(60_000);expect(auth.token).toBe('token');vi.setSystemTime(new Date(data.expiresAt).getTime()+1);await vi.advanceTimersByTimeAsync(60_000);expect(auth.token).toBe('');portal.dispose();
});
