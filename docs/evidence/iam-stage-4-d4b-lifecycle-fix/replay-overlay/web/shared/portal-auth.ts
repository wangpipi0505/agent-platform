import {createApi, checkScope, type PortalIdentity, type useAuth} from './api';
import {watch} from 'vue';
export interface PortalTokenProvider {
  getAccessToken(): Promise<string>;
  onSessionChanged(listener: () => void): () => void;
}
export function portalMode(mode?:string):'portal'|'dev' {
  if(mode===undefined||mode===''||mode==='portal')return 'portal';
  if(mode==='dev')return 'dev';
  throw Error('VITE_AUTH_MODE 无效，请配置 portal 或 dev');
}
// The same-origin embedding host is the trust boundary. The server verifies
// every token; URL, storage and postMessage are never identity providers.
export function trustedPortal(host:{agentPlatformPortal?:unknown}):PortalTokenProvider|undefined {
  const value=host.agentPlatformPortal as PortalTokenProvider|undefined;
  if(!value||typeof value.getAccessToken!=='function'||typeof value.onSessionChanged!=='function')return;
  return {getAccessToken:value.getAccessToken.bind(value),onSessionChanged:value.onSessionChanged.bind(value)};
}
function identity(data:any):PortalIdentity {
  if(!data||typeof data!=='object'||Array.isArray(data)||Object.keys(data).sort().join(',')!=='expiresAt,principalId,roles,tenantId'
    ||![data.tenantId,data.principalId].every(x=>typeof x==='string'&&x.trim())
    ||!Array.isArray(data.roles)||!data.roles.every((x:unknown)=>typeof x==='string'&&x.trim())
    ||typeof data.expiresAt!=='string'||!/^\d{4}-\d\d-\d\dT.*(?:Z|[+-]\d\d:\d\d)$/.test(data.expiresAt)
    ||!Number.isFinite(Date.parse(data.expiresAt))||Date.parse(data.expiresAt)<=Date.now())
    throw Error('门户会话响应无效或已到期，请重新连接');
  return {tenantId:data.tenantId,principalId:data.principalId,roles:[...data.roles],expiresAt:data.expiresAt};
}
export function createPortalSession(auth:ReturnType<typeof useAuth>,provider:PortalTokenProvider|undefined,options:{mode:'portal'|'dev';transport?:typeof fetch}) {
  let disposed=false,paused=false,subscribing=false,subscriptionEpoch=0,unsubscribe:(()=>void)|undefined;
  function dropSubscription(){
    // Invalidate queued callbacks before invoking an external cleanup which may
    // itself synchronously notify the listener.
    subscriptionEpoch++;
    const cleanup=unsubscribe;unsubscribe=undefined;
    try{cleanup?.();}catch{/* A failed host cleanup must not revive its old callback. */}
  }
  const stopWatching=watch(()=>auth.connection,state=>{
    if(['signed-out','unauthenticated','expired'].includes(state)){paused=true;dropSubscription();}
  },{flush:'sync',immediate:true});
  function ensureSubscription(){
    if(options.mode!=='portal'||!provider||unsubscribe||subscribing)return;
    const epoch=++subscriptionEpoch;let ready=false;
    subscribing=true;
    try{
      const cleanup=provider.onSessionChanged(()=>{
        if(!ready||disposed||paused||epoch!==subscriptionEpoch||!['connecting','authenticated'].includes(auth.connection))return;
        void reconnect(false);
      });
      if(typeof cleanup!=='function')throw Error('门户会话订阅无效');
      if(disposed||paused||epoch!==subscriptionEpoch){try{cleanup();}catch{}return;}
      unsubscribe=cleanup;
      // Ignore only synchronous registration notifications: this same manual
      // connection fetches the latest token immediately after registration.
      ready=true;
    }catch(error){subscriptionEpoch++;throw error;}
    finally{subscribing=false;}
  }
  async function reconnect(manual:boolean) {
    if(disposed||(!manual&&(paused||!['connecting','authenticated'].includes(auth.connection))))return;
    if(manual)paused=false;
    auth.invalidate(auth.generation,'connecting','正在连接门户会话…');
    const scope=auth.snapshot();
    try {
      if(options.mode==='portal'&&!provider){auth.connection='missing';auth.notice='请从门户打开工作台';return;}
      ensureSubscription();
      if(disposed||paused)return;
      checkScope(scope);
      let token:string;
      if(options.mode==='dev') {
        // An explicit build/start choice, never a fallback from portal failure.
        const r=await (options.transport??fetch)('/auth/dev-token',{method:'POST',credentials:'omit',signal:scope.signal});
        checkScope(scope);const e=await r.json();checkScope(scope);
        if(!r.ok||e.code!=='OK')throw Object.assign(Error(e.message||'开发登录失败'),{status:r.status});
        token=e.data?.accessToken??e.data?.token??e.data;
      } else token=await provider!.getAccessToken();
      checkScope(scope);
      if(typeof token!=='string'||!token.trim())throw Error('门户未提供有效访问票据，请重新连接');
      const data=await createApi(()=>token,options.transport,undefined,scope)('/auth/session');
      checkScope(scope);auth.install(scope.generation,token,identity(data));
    } catch(e:any) {
      if(!scope.current()||disposed||e.name==='AbortError')return;
      auth.connection=e.status===401?'unauthenticated':e.status===403?'forbidden':e.status===503?'unavailable':'error';
      auth.notice=e.status===401?'会话已失效，请重新连接':e.status===403?'当前账号无权访问工作台，请联系管理员':e.status===503?'会话暂不可用，请稍后手动重试':e.message||'门户连接失败，请重新连接';
    }
  }
  return {connect:()=>reconnect(true),dispose(){
    if(disposed)return;
    disposed=true;paused=true;stopWatching();dropSubscription();
    auth.invalidate(auth.generation,'signed-out','已退出，请重新连接');
  }};
}
