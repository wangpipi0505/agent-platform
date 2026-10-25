import { afterEach, expect, it, vi } from 'vitest';
import { cursor, decodeRunStatus, after, eventText, type RunEvent } from '../shared/run-protocol';
import { createApi } from '../shared/api';
import { parseFrames, streamEvents, applyEvent, type EventState } from '../debug-panel/events';
import { observeRun } from '../shared/run-observer';
afterEach(()=>vi.restoreAllMocks());
const status=(token:string)=>`{"code":"OK","data":{"status":"RUNNING","eventsWatermark":${token}}}`;
it.each([
 {name:'empty text falls through to delta',data:{text:'',delta:'visible delta'},expected:'visible delta'},
 {name:'empty text and delta fall through to content',data:{text:'',delta:'',content:'visible content'},expected:'visible content'},
 {name:'empty delta falls through to content',data:{delta:'',content:'visible content'},expected:'visible content'},
 {name:'non-string text is skipped',data:{text:true,delta:'visible delta'},expected:'visible delta'},
 {name:'non-string text and delta are skipped',data:{text:{},delta:42,content:'visible content'},expected:'visible content'},
 {name:'all non-string fields are unusable',data:{text:null,delta:[],content:false},expected:undefined},
 {name:'all empty fields are unusable',data:{text:'',delta:'',content:''},expected:undefined},
 {name:'missing fields are unusable',data:{},expected:undefined},
 {name:'non-record payload is unusable',data:null,expected:undefined},
 {name:'text whitespace is preserved before delta',data:{text:' \t\n ',delta:'later'},expected:' \t\n '},
 {name:'delta whitespace is preserved before content',data:{text:'',delta:' \t ',content:'later'},expected:' \t '},
 {name:'nonempty text retains priority',data:{text:'first',delta:'later',content:'last'},expected:'first'},
])('CHUNK string fallback: $name',({data,expected})=>expect(eventText(data)).toBe(expected));
it.each(['0','9007199254740991','9007199254740992','9007199254740993','9007199254740994','9223372036854775807'])('decodes exact native numeric status token %s',token=>{
 const envelope=decodeRunStatus(status(token));expect(envelope.data.eventsWatermark).toBe(token);expect(()=>JSON.stringify(envelope)).not.toThrow();
});
it.each(['-1','-0','1.0','1e0','1e30','9223372036854775808','"1"','null','true','[]','{}'])('rejects invalid status token %s',token=>expect(()=>decodeRunStatus(status(token))).toThrow('游标无效'));
it('selects final holder and native duplicate surviving semantics without changing nested names',()=>{
 const e=decodeRunStatus('{"code":"OK","data":{"eventsWatermark":1e3},"data":{"status":"RUNNING","nested":{"data":{"eventsWatermark":42}},"eventsWatermark":1e8,"eventsWatermark":9007199254740993}}');
 expect(e.data.eventsWatermark).toBe('9007199254740993');expect(e.data.nested.data.eventsWatermark).toBe(42);
 expect(()=>decodeRunStatus('{"code":"OK","data":{"status":"RUNNING","eventsWatermark":2,"eventsWatermark":"3"}}')).toThrow('游标无效');
 expect(()=>decodeRunStatus('{"code":"OK","data":{"status":"RUNNING","eventsWatermark":2},"data":null}')).toThrow('游标无效');
 expect(()=>decodeRunStatus('{"code":"OK","data":{"status":"RUNNING"}}')).toThrow('游标无效');
});
it('C2c backfill selects exact final row holder tokens and preserves unknown payload without rawData',()=>{
 const text='{"code":"OK","data":{"status":"RUNNING","eventsWatermark":1},"data":{"status":"RUNNING","eventsWatermark":9223372036854775807,"events":[{"seq":1e3}],"events":[{"seq":1e8,"seq":9007199254740993,"type":"NODE_STARTED","payload":null,"created_at":"synthetic"},{"seq":9007199254740994,"payload":{"seq":9007199254740993,"events":[{"seq":9007199254740993}],"eventsWatermark":9007199254740993}},{"seq":9223372036854775807,"payload":[null," 中 "]}]}}';
 const e=decodeRunStatus(text);expect(e.data.events.map((row:any)=>row.seq)).toEqual(['9007199254740993','9007199254740994','9223372036854775807']);
 expect(e.data.events[0]).toEqual({seq:'9007199254740993',type:'NODE_STARTED',payload:null,created_at:'synthetic'});
 expect(e.data.events[1].payload.seq).toBe(9007199254740992);expect(e.data.events[1].payload.events[0].seq).toBe(9007199254740992);expect(e.data.events[1].payload.eventsWatermark).toBe(9007199254740992);
 for(const row of e.data.events)expect(Object.hasOwn(row,'rawData')).toBe(false);expect(()=>JSON.stringify(e)).not.toThrow();
 expect(Object.hasOwn(decodeRunStatus(status('0')).data,'events')).toBe(false);
});
it.each(['null','{}','"events"','[null]','[[]]','[1]','[{}]','[{"seq":"1"}]','[{"seq":-1}]','[{"seq":1.0}]','[{"seq":1e0}]','[{"seq":9223372036854775808}]','[{"seq":1,"seq":null}]'])('C2c malformed backfill %s is a recoverable protocol error',events=>{
 expect(()=>decodeRunStatus(`{"code":"OK","data":{"status":"RUNNING","eventsWatermark":0,"events":${events}}}`)).toThrow('游标无效');
});
it('C2c arbitrary frame names cannot acquire inherited node status values',()=>{
 const state={watermark:'0',events:[] as RunEvent[],nodes:{}};
 expect(applyEvent(state,{type:'constructor',seq:'1',data:{nodeId:'node'},rawData:'{"nodeId":"node"}'}).nodes).toEqual({});
});
it('unsupported native context is recoverable and does not mask error envelopes or active 401',async()=>{
 const native=JSON.parse;vi.spyOn(JSON,'parse').mockImplementation((text,reviver)=>native(text,reviver?function(key,value){return reviver.call(this,key,value);}:undefined));
 expect(()=>decodeRunStatus(status('0'))).toThrow('升级 Chrome 或 Edge');
 expect(decodeRunStatus('{"code":"AUTH","message":"denied","data":null}')).toEqual({code:'AUTH',message:'denied',data:null});
 const unauthorized=vi.fn();const api=createApi(()=> 'test',async()=>new Response('{"code":"AUTH","message":"denied","data":null}',{status:401}),unauthorized);
 await expect(api('/status','GET',undefined,undefined,decodeRunStatus)).rejects.toMatchObject({status:401,message:'denied'});expect(unauthorized).toHaveBeenCalledOnce();
});
it('preserves one optional ASCII space, multiline payload and 32 codepoint names over 64 bytes',()=>{
 const name='界'.repeat(32);expect(new TextEncoder().encode(name).length).toBeGreaterThan(64);
 expect(parseFrames(`event: ${name}\r\ndata:  {"x":\r\ndata: 1} \r\n\r\nevent:   \ndata: null\n\n`).frames).toEqual([{id:'',event:name,data:' {"x":\n1} '},{id:'',event:'  ',data:'null'}]);
});
it('accepts only canonical string cursors and compares adjacent long values without Number',()=>{
 for(const value of [0,1n,'','01','+1',' 1','1 ','-1','1.0','1e0','9223372036854775808'])expect(cursor(value)).toBe(false);
 expect(after('9007199254740993','9007199254740992')).toBe(true);expect(after('9223372036854775807','9007199254740993')).toBe(true);
});
it('keeps exact frame ids through durable replay, reset status and terminal; control numeric hints never advance',async()=>{
 const opened:string[]=[],seen:RunEvent[]=[];let round=0;
 const event=(type:string,seq:string|undefined,data:unknown=null):RunEvent=>({type,seq,data,rawData:JSON.stringify(data)});
 const result=await observeRun({signal:new AbortController().signal,pause:async()=>{},open:async(cursor,emit)=>{opened.push(cursor);round++;if(round===1){emit(event('NODE_STARTED','9007199254740992'));emit(event('CHUNK','9223372036854775807'));emit(event('stream_status',undefined,{eventsWatermark:9223372036854776000}));emit(event('stream_reset',undefined,{eventsWatermark:9223372036854776000}));}else{emit(event('NODE_STARTED','9007199254740993'));emit(event('RUN_FINISHED','9223372036854775807'));}},readStatus:async()=>({status:round===1?'RUNNING':'SUCCEEDED',eventsWatermark:round===1?'9007199254740993':'9223372036854775807'}),onStatus:()=>{},onEvent:e=>seen.push(e)});
 expect(opened).toEqual(['0','9007199254740993']);expect(seen.map(e=>e.type)).toEqual(['NODE_STARTED','CHUNK','RUN_FINISHED']);expect(result.eventsWatermark).toBe('9223372036854775807');expect(()=>JSON.stringify({opened,seen,result})).not.toThrow();
});
it('unknown payload cannot manufacture business node fields and chunks never advance durable state',()=>{
 let state:EventState={watermark:'0',events:[],nodes:{}};
 state=applyEvent(state,{type:'NODE_STARTED',seq:'9007199254740993',data:null,rawData:'null'});
 expect(state.watermark).toBe('9007199254740993');expect(state.nodes).toEqual({});
 expect(applyEvent(state,{type:'chunk',seq:'9223372036854775807',data:[],rawData:'[]'})).toBe(state);
});
it('does not use payload.seq as cursor when frame id is absent or malformed',async()=>{
 const events:RunEvent[]=[];vi.stubGlobal('fetch',async()=>new Response('event: NODE_STARTED\ndata: {"seq":9007199254740993}\n\nid: 01\ndata: null\n\nid: 9223372036854775808\ndata: []\n\n'));
 try{await streamEvents('/events',()=> 'test','0',e=>events.push(e),new AbortController().signal);expect(events.map(e=>e.seq)).toEqual([undefined,undefined,undefined]);}finally{vi.unstubAllGlobals();}
});
