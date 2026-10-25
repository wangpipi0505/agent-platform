import { it, expect, vi } from "vitest";
import { streamEvents } from "../debug-panel/events";
import { decodeRunStatus } from '../shared/run-protocol';
import { createApi } from "../shared/api";
it('C2c accepts arbitrary JSON with exact rawData and actual SSE type', async()=>{
  const raw=['null','[1," 中 "]','"  spaced  "','9007199254740993',' {"type":"forged","seq":77,"text":"\\u4e2d"} '];
  const bytes=new TextEncoder().encode(raw.map((data,i)=>`id: ${9007199254740993n+BigInt(i)}\r\nevent: 实际事件\r\ndata: ${data}\r\n\r\n`).join(''));
  vi.stubGlobal('fetch',async()=>new Response(new ReadableStream({start(c){for(let i=0;i<bytes.length;i++)c.enqueue(bytes.slice(i,i+1));c.close();}})));
  try {const events:any[]=[];await streamEvents('/events',()=> 'test', '0',e=>events.push(e),new AbortController().signal);
    expect(events.map(e=>e.rawData)).toEqual(raw);expect(events.map(e=>e.data)).toEqual(raw.map(x=>JSON.parse(x)));
    expect(events.map(e=>e.type)).toEqual(raw.map(()=> '实际事件'));expect(events.map(e=>e.seq)).toEqual(raw.map((_,i)=>String(9007199254740993n+BigInt(i))));
  }finally{vi.unstubAllGlobals();}
});
it('C2c status keeps the original numeric long token',async()=>{
  const api=createApi(()=> 'test',async()=>new Response('{"code":"OK","data":{"status":"RUNNING","eventsWatermark":9007199254740993}}'));
  expect((await api('/v1/runs/r_precision','GET',undefined,undefined,decodeRunStatus)).eventsWatermark).toBe('9007199254740993');
});
it("SSE refuses an absent token before network access", async () => {
  const transport = vi.fn();
  vi.stubGlobal("fetch", transport);
  await expect(
    streamEvents(
      "/events",
      () => "", '0',
      () => {},
      new AbortController().signal,
    ),
  ).rejects.toThrow("请先登录");
  expect(transport).not.toHaveBeenCalled();
  vi.unstubAllGlobals();
});
it("authenticated streaming carries replay cursor and decodes fragmented UTF8", async () => {
  const encoder = new TextEncoder();
  const bytes = encoder.encode(
    'id: 8\nevent: CHUNK\ndata: {"text":"中文"}\n\n',
  );
  const transport = vi.fn(
    async () =>
      new Response(
        new ReadableStream({
          start(c) {
            c.enqueue(bytes.slice(0, 42));
            c.enqueue(bytes.slice(42));
            c.close();
          },
        }),
      ),
  );
  vi.stubGlobal("fetch", transport);
  const events: any[] = [];
  await streamEvents(
    "/events",
    () => "secret",
    '7',
    (e) => events.push(e),
    new AbortController().signal,
  );
  expect((transport.mock.calls as any)[0]?.[1]).toMatchObject({
    headers: { Authorization: "Bearer secret", "Last-Event-ID": "7" },
  });
  expect(events).toEqual([{data:{text:"中文"},rawData:'{"text":"中文"}',type:"CHUNK",seq:"8"}]);
  vi.unstubAllGlobals();
});
it("API refuses application error responses and preserves trace", async () => {
  const api = createApi(
    () => "t",
    async () =>
      new Response(
        JSON.stringify({
          code: "BIZ-REV",
          message: "草稿已变更",
          traceId: "trace-1",
        }),
        { status: 412 },
      ),
  );
  await expect(api("/draft", "PUT", {})).rejects.toMatchObject({
    message: "草稿已变更",
    status: 412,
    traceId: "trace-1",
  });
});
