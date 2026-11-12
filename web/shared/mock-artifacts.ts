/** Browser-only demonstration artifact store. Binary payloads share one ID across chat and runs. */
export const mockArtifactsEnabled = () => typeof import.meta.env?.VITE_MOCK === 'string' && import.meta.env.VITE_MOCK !== 'false';
type StoredArtifact = { artifactId:string; name:string; mediaType:string; bytes:number; blob:Blob; runId?:string;sha256?:string };
const memory = new Map<string, StoredArtifact>();
let database:Promise<IDBDatabase|undefined>|undefined;
function db() {
  return database ||= new Promise(resolve => {
    if (typeof indexedDB === 'undefined') return resolve(undefined);
    const request=indexedDB.open('agent-platform-demo-artifacts',1);
    request.onupgradeneeded=()=>request.result.createObjectStore('files',{keyPath:'artifactId'});
    request.onsuccess=()=>resolve(request.result); request.onerror=()=>resolve(undefined);
  });
}
export function mockFileRef(file:Omit<StoredArtifact,'blob'>) {
  return { artifactId:file.artifactId,name:file.name,mediaType:file.mediaType,bytes:file.bytes,sha256:file.sha256,runId:file.runId, id:file.artifactId, size:file.bytes, downloadUrl:`/admin/artifacts/${encodeURIComponent(file.artifactId)}/download`, externalDownloadUrl:file.runId ? `/v1/runs/${encodeURIComponent(file.runId)}/artifacts/${encodeURIComponent(file.artifactId)}/download` : undefined };
}
export async function registerMockArtifact(input:{name:string;mediaType?:string;content:string|Blob|ArrayBuffer;runId?:string}) {
  const blob=input.content instanceof Blob ? input.content : new Blob([input.content],{type:input.mediaType||'text/plain;charset=utf-8'});
  const sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))).map(byte=>byte.toString(16).padStart(2,'0')).join('');
  const file:StoredArtifact={artifactId:'a_'+crypto.randomUUID(),name:input.name,mediaType:input.mediaType||blob.type||'application/octet-stream',bytes:blob.size,sha256,blob,runId:input.runId};
  memory.set(file.artifactId,file);
  const database=await db();
  if(database) await new Promise<void>(resolve=>{const tx=database.transaction('files','readwrite');tx.objectStore('files').put(file);tx.oncomplete=()=>resolve();tx.onerror=()=>resolve();tx.onabort=()=>resolve();});
  else if(blob.size<200000) try { const bytes=new Uint8Array(await blob.arrayBuffer());let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);sessionStorage.setItem('demo-artifact:'+file.artifactId,JSON.stringify({...mockFileRef(file),base64:btoa(binary)})); } catch {}
  return mockFileRef(file);
}
async function stored(id:string) {
  if(memory.has(id)) return memory.get(id);
  const database=await db();
  const file=database ? await new Promise<StoredArtifact|undefined>(resolve=>{const r=database.transaction('files').objectStore('files').get(id);r.onsuccess=()=>resolve(r.result);r.onerror=()=>resolve(undefined);}) : undefined;
  if(file){memory.set(id,file);return file;}
  try {const fallback=JSON.parse(sessionStorage.getItem('demo-artifact:'+id)||'null');if(fallback){const bytes=fallback.base64?Uint8Array.from(atob(fallback.base64),(char:string)=>char.charCodeAt(0)):fallback.content;return {...fallback,blob:new Blob([bytes],{type:fallback.mediaType})} as StoredArtifact};}catch{}
}
export async function readMockArtifact(id:string) { const file=await stored(id);return file ? {...mockFileRef(file),content:await file.blob.text(),blob:file.blob}:undefined; }
export async function resetMockArtifacts(){memory.clear();const database=await db();if(database)await new Promise<void>(resolve=>{const tx=database.transaction('files','readwrite');tx.objectStore('files').clear();tx.oncomplete=()=>resolve();tx.onerror=()=>resolve();});try{for(const key of Object.keys(sessionStorage))if(key.startsWith('demo-artifact:'))sessionStorage.removeItem(key)}catch{}}
export async function mockArtifactRequest(path:string|URL|Request,init:RequestInit={}):Promise<Response|undefined> {
  const url=new URL(typeof path==='string'?path:path instanceof URL?path.href:path.url,'http://demo.local');
  const match=url.pathname.match(/^\/(?:admin\/artifacts|v1\/runs\/([^/]+)\/artifacts)(?:\/([^/]+)(?:\/(download))?)?$/);
  if(!match)return undefined;
  const method=(init.method||'GET').toUpperCase();
  const json=(data:unknown,status=200)=>new Response(JSON.stringify({code:status===200?'OK':'NOT_FOUND',message:status===200?'':'附件不存在',data}),{status,headers:{'Content-Type':'application/json'}});
  if(method==='POST'&&url.pathname==='/admin/artifacts') {
    if(!(init.body instanceof FormData)) return new Response('请选择上传文件',{status:400});
    const file=init.body.get('file');if(!(file instanceof Blob))return new Response('文件内容为空',{status:400});
    return json(await registerMockArtifact({name:file instanceof File?file.name:'upload',mediaType:file.type,content:file}));
  }
  if(method==='GET'&&match[2]) {const file=await stored(decodeURIComponent(match[2]));if(!file||match[1]&&file.runId!==decodeURIComponent(match[1]))return json(null,404);
    if(match[3])return new Response(file.blob,{headers:{'Content-Type':file.mediaType,'Content-Disposition':`attachment; filename*=UTF-8''${encodeURIComponent(file.name)}`,'Content-Length':String(file.bytes)}});
    return json(mockFileRef(file));
  }
  return undefined;
}
