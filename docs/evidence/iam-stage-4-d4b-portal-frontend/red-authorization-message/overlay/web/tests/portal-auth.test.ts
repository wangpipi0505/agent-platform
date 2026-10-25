import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {createPinia, setActivePinia} from 'pinia';
import {useAuth, createApi} from '../shared/api';
import {createPortalSession, portalMode, trustedPortal} from '../shared/portal-auth';
import {downloadArtifact} from '../shared/artifact';
const session=(roles:string[]=[])=>({tenantId:'tenant',principalId:'person',roles,expiresAt:new Date(Date.now()+60_000).toISOString()});
const reply=(data:any,status=200,code='OK')=>new Response(JSON.stringify({code,data}),{status});
const deferred=<T>()=>{let resolve!:(value:T)=>void;const promise=new Promise<T>(r=>resolve=r);return {promise,resolve};};
beforeEach(()=>setActivePinia(createPinia()));
afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals();});
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
it('expiry and logout invalidate without reconnecting, disposal unsubscribes and releases timers',async()=>{
 vi.useFakeTimers();const auth=useAuth(),unsubscribe=vi.fn(),getAccessToken=vi.fn(async()=> 'token');let changed!:()=>void;
 const portal=createPortalSession(auth,{getAccessToken,onSessionChanged:fn=>{changed=fn;return unsubscribe;}},{mode:'portal',transport:async()=>reply(session())});
 await portal.connect();await vi.advanceTimersByTimeAsync(60_001);expect(auth.token).toBe('');expect(auth.roles).toEqual([]);expect(getAccessToken).toHaveBeenCalledTimes(1);expect(auth.connection).toBe('expired');
 changed();await vi.waitFor(()=>expect(auth.token).toBe('token'));auth.invalidate(auth.generation,'signed-out');expect(getAccessToken).toHaveBeenCalledTimes(2);
 portal.dispose();expect(unsubscribe).toHaveBeenCalledTimes(1);expect(vi.getTimerCount()).toBe(0);
});
it('segments a far future deadline and rechecks actual time before expiring',async()=>{
 vi.useFakeTimers();const auth=useAuth(),data={...session(),expiresAt:new Date(Date.now()+3_000_000_000).toISOString()};
 const portal=createPortalSession(auth,{getAccessToken:async()=> 'token',onSessionChanged:()=>()=>{}},{mode:'portal',transport:async()=>reply(data)});
 await portal.connect();await vi.advanceTimersByTimeAsync(60_000);expect(auth.token).toBe('token');vi.setSystemTime(new Date(data.expiresAt).getTime()+1);await vi.advanceTimersByTimeAsync(60_000);expect(auth.token).toBe('');portal.dispose();
});
