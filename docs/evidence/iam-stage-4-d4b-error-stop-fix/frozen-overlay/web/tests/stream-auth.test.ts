import { afterEach, expect, it, vi } from 'vitest';
import axios from 'axios';
import { createApi } from '../shared/api';
import { streamEvents } from '../debug-panel/events';

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });
it.each(['AUTHORITY_EXPIRED','AUTHORITY_DENIED','AUTHORITY_UNAVAILABLE'])('D4b fix cleanup rejection preserves %s and releases reader without facts',async reason=>{
 const cancel=vi.fn(()=>Promise.reject(new TypeError('cleanup failed')));
 const stream=new ReadableStream({start(c){c.enqueue(new TextEncoder().encode(`event: stream_reset\ndata: {"reason":"${reason}"}\n\nid: 9\ndata: {"seq":9}\n\n`));},cancel});
 vi.stubGlobal('fetch',async()=>new Response(stream));const events=vi.fn();
 const outcome=await streamEvents('/events',()=> 'token',0,events,new AbortController().signal).then(()=>({resolved:true}),error=>({name:error.name,reason:error.reason,message:error.message}));
 expect({outcome,cancels:cancel.mock.calls.length,locked:stream.locked,facts:events.mock.calls.length}).toMatchObject({outcome:{reason},cancels:1,locked:false,facts:0});
});
it('D4b fix cleanup rejection preserves caller AbortError and releases pending reader',async()=>{
 const controller=new AbortController(),cancel=vi.fn(()=>Promise.reject(new TypeError('cleanup failed')));let ready!:()=>void;const reading=new Promise<void>(r=>ready=r);
 const stream=new ReadableStream({pull(){ready();},cancel});vi.stubGlobal('fetch',async()=>new Response(stream));
 const pending=streamEvents('/events',()=> 'token',0,vi.fn(),controller.signal);await reading;controller.abort();
 await expect(pending).rejects.toMatchObject({name:'AbortError'});expect(stream.locked).toBe(false);expect(cancel).toHaveBeenCalledTimes(1);
});
it('D4b fix cleanup rejection never replaces primary parser failure',async()=>{
 const stream=new ReadableStream({start(c){c.enqueue(new TextEncoder().encode('data: invalid-json\n\n'));},cancel:()=>Promise.reject(new TypeError('cleanup failed'))});
 vi.stubGlobal('fetch',async()=>new Response(stream));await expect(streamEvents('/events',()=> 'token',0,vi.fn(),new AbortController().signal)).rejects.toMatchObject({name:'SyntaxError'});expect(stream.locked).toBe(false);
});
it('D4b fix cleanup preserves normal EOF and primary network read error',async()=>{
 const cancel=vi.fn(()=>Promise.reject(new TypeError('cleanup failed'))),closed=new ReadableStream({start(c){c.close();},cancel});
 vi.stubGlobal('fetch',async()=>new Response(closed));await expect(streamEvents('/events',()=> 'token',0,vi.fn(),new AbortController().signal)).resolves.toBeUndefined();expect(closed.locked).toBe(false);expect(cancel).not.toHaveBeenCalled();
 const primary=new TypeError('network read failed'),errored=new ReadableStream({start(c){c.error(primary);}});vi.stubGlobal('fetch',async()=>new Response(errored));
 await expect(streamEvents('/events',()=> 'token',0,vi.fn(),new AbortController().signal)).rejects.toBe(primary);expect(errored.locked).toBe(false);
});
const ok = () => new Response(JSON.stringify({ code: 'OK', data: { status: 'RUNNING' } }));
const revoked = () => new Response('', { status: 401 });
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
};

it('keeps existing GET and POST call signatures and bodies', async () => {
  const transport = vi.fn(async () => ok());
  const api = createApi(() => 'current', transport);
  await expect(api('/status')).resolves.toEqual({ status: 'RUNNING' });
  await api('/decision', 'POST', { approved: true });
  expect(transport.mock.calls[1]).toEqual(['/decision', expect.objectContaining({
    method: 'POST', body: '{"approved":true}', headers: expect.objectContaining({ Authorization: 'Bearer current' }),
  })]);
});

it('API rejects an already aborted request before transport', async () => {
  const transport = vi.fn(async () => ok()), controller = new AbortController();
  controller.abort();
  const api = createApi(() => 'current', transport);
  await expect(api('/status', 'GET', undefined, controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
  expect(transport).not.toHaveBeenCalled();
});

it('passes the observation signal to a fetch-compatible transport', async () => {
  const transport = vi.fn(async () => ok()), controller = new AbortController();
  await createApi(() => 'current', transport)('/status', 'GET', undefined, controller.signal);
  expect(transport.mock.calls[0]).toEqual(['/status', expect.objectContaining({ signal: controller.signal })]);
});

it('passes the observation signal to the default axios transport', async () => {
  const request = vi.spyOn(axios, 'request').mockResolvedValue({ status: 200, headers: {}, data: '{"code":"OK","data":1}' });
  const controller = new AbortController();
  await expect(createApi(() => 'current')('/status', 'GET', undefined, controller.signal)).resolves.toBe(1);
  expect(request).toHaveBeenCalledWith(expect.objectContaining({ signal: controller.signal }));
});

it.each([200, 401])('ignores late API HTTP %s after abort even if the transport completes', async status => {
  const reply = deferred<Response>(), unauthorized = vi.fn(), controller = new AbortController();
  const api = createApi(() => 'current', () => reply.promise, unauthorized);
  const pending = api('/status', 'GET', undefined, controller.signal);
  controller.abort(); reply.resolve(status === 200 ? ok() : revoked());
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  expect(unauthorized).not.toHaveBeenCalled();
});

it('checks abort again after asynchronous API body decoding', async () => {
  const body = deferred<any>(), unauthorized = vi.fn(), controller = new AbortController();
  const response = revoked(); response.json = () => body.promise;
  const pending = createApi(() => 'current', async () => response, unauthorized)('/status', 'GET', undefined, controller.signal);
  await Promise.resolve(); controller.abort(); body.resolve({ code: 'UNAUTHORIZED' });
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  expect(unauthorized).not.toHaveBeenCalled();
});

it('does not clear a new login when the old API token receives 401', async () => {
  const reply = deferred<Response>(); let token = 'old';
  const unauthorized = vi.fn(() => { token = ''; });
  const pending = createApi(() => token, () => reply.promise, unauthorized)('/status');
  token = 'new'; reply.resolve(revoked());
  await expect(pending).rejects.toMatchObject({ status: 401 });
  expect(token).toBe('new'); expect(unauthorized).not.toHaveBeenCalled();
});

it('clears the current API token on an active 401 with an unreadable body', async () => {
  let token = 'current'; const unauthorized = vi.fn(() => { token = ''; });
  const pending = createApi(() => token, async () => revoked(), unauthorized)('/status');
  await expect(pending).rejects.toMatchObject({ status: 401 });
  expect(token).toBe(''); expect(unauthorized).toHaveBeenCalledTimes(1);
});

it('SSE rejects an already aborted request before fetch', async () => {
  const transport = vi.fn(async () => revoked()), controller = new AbortController(); controller.abort();
  vi.stubGlobal('fetch', transport);
  await expect(streamEvents('/events', () => 'current', 0, vi.fn(), controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
  expect(transport).not.toHaveBeenCalled();
});

it('ignores late SSE 401 after abort before unauthorized side effects', async () => {
  const reply = deferred<Response>(), unauthorized = vi.fn(), controller = new AbortController();
  vi.stubGlobal('fetch', () => reply.promise);
  const pending = streamEvents('/events', () => 'current', 0, vi.fn(), controller.signal, unauthorized);
  controller.abort(); reply.resolve(revoked());
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  expect(unauthorized).not.toHaveBeenCalled();
});

it('does not clear a new login when the old SSE token receives 401', async () => {
  const reply = deferred<Response>(), controller = new AbortController(); let token = 'old';
  const unauthorized = vi.fn(() => { token = ''; }); vi.stubGlobal('fetch', () => reply.promise);
  const pending = streamEvents('/events', () => token, 0, vi.fn(), controller.signal, unauthorized);
  token = 'new'; reply.resolve(revoked());
  await expect(pending).rejects.toMatchObject({ status: 401 });
  expect(token).toBe('new'); expect(unauthorized).not.toHaveBeenCalled();
});

it('clears the current SSE token on an active 401', async () => {
  let token = 'current'; const unauthorized = vi.fn(() => { token = ''; });
  vi.stubGlobal('fetch', async () => revoked());
  await expect(streamEvents('/events', () => token, 0, vi.fn(), new AbortController().signal, unauthorized)).rejects.toMatchObject({ status: 401 });
  expect(token).toBe(''); expect(unauthorized).toHaveBeenCalledTimes(1);
});

it('D4b scopes same-token HTTP to generation, including late body and chained POST', async () => {
  const body=deferred<any>(),response=ok();response.json=()=>body.promise;
  const controller=new AbortController();let current=true;
  const transport=vi.fn(async()=>response);
  const api=createApi(()=> 'same',transport,vi.fn(),{signal:controller.signal,current:()=>current});
  const pending=api('/late');await Promise.resolve();current=false;body.resolve({code:'OK',data:'old'});
  await expect(pending).rejects.toMatchObject({name:'AbortError'});
  await expect(api('/post','POST')).rejects.toMatchObject({name:'AbortError'});expect(transport).toHaveBeenCalledTimes(1);
});
it('D4b default axios fetch adapter explicitly omits ambient cookies',async()=>{
  const request=vi.spyOn(axios,'request').mockResolvedValue({status:200,headers:{},data:'{"code":"OK","data":1}'});
  await createApi(()=> 'token')('/session');
  expect(request).toHaveBeenCalledWith(expect.objectContaining({adapter:'fetch',withCredentials:false}));
});
it('D4b names authorization denial and IAM unavailability without generic busy text',async()=>{
 for(const [status,code,message] of [[403,'SYS-AUTH-403','没有此操作权限'],[503,'IAM_AUTHORITY_UNAVAILABLE','授权服务暂不可用']] as const){
  await expect(createApi(()=> 'token',async()=>new Response(JSON.stringify({code,message:'internal'}),{status}))('/status')).rejects.toMatchObject({status,code,message:expect.stringContaining(message)});
 }
});
it('D4b aborts a pending reader and releases it even if a custom body ignores fetch signal',async()=>{
 const controller=new AbortController(),cancel=vi.fn();let started!:()=>void;const ready=new Promise<void>(r=>started=r);
 vi.stubGlobal('fetch',async()=>new Response(new ReadableStream({pull(){started();},cancel})));
 const observation=streamEvents('/events',()=> 'token',0,vi.fn(),controller.signal);await ready;controller.abort();await expect(observation).rejects.toMatchObject({name:'AbortError'});expect(cancel).toHaveBeenCalledTimes(1);
});
it.each(['AUTHORITY_EXPIRED','AUTHORITY_DENIED','AUTHORITY_UNAVAILABLE'])('D4b %s reset stops buffered facts and releases reader',async reason=>{
  const cancelled=vi.fn();vi.stubGlobal('fetch',async()=>new Response(new ReadableStream({start(c){c.enqueue(new TextEncoder().encode(`event: stream_reset\ndata: {"reason":"${reason}"}\n\nid: 3\nevent: NODE_STARTED\ndata: {"seq":3}\n\n`));},cancel:cancelled})));
  const events=vi.fn();await expect(streamEvents('/events',()=> 'token',0,events,new AbortController().signal)).rejects.toMatchObject({reason});expect(events).not.toHaveBeenCalled();expect(cancelled).toHaveBeenCalledTimes(1);
});
it('D4b stops after reader read abort and after callback abort within buffered frames',async()=>{
 const controller=new AbortController(),cancel=vi.fn(),events=vi.fn(()=>controller.abort());
 vi.stubGlobal('fetch',async()=>new Response(new ReadableStream({start(c){c.enqueue(new TextEncoder().encode('id: 1\ndata: {"seq":1}\n\nid: 2\ndata: {"seq":2}\n\n'));},cancel})));
 await expect(streamEvents('/events',()=> 'token',0,events,controller.signal)).rejects.toMatchObject({name:'AbortError'});expect(events).toHaveBeenCalledTimes(1);expect(cancel).toHaveBeenCalledTimes(1);
});
