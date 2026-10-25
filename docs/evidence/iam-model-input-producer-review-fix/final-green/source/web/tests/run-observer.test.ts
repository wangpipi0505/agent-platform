import { describe, it, expect,vi } from 'vitest';
import type { RunEvent } from '../shared/run-protocol';
const fact = (value:{type:string;seq?:string;[key:string]:unknown}):RunEvent=>{const {type,seq,...data}=value;return {type,seq,data,rawData:JSON.stringify(data)};};
import { observeRun } from '../shared/run-observer';
import {streamEvents} from '../debug-panel/events';

describe('authoritative run observation across transient SSE connections',()=>{
  it('D4b fix real reset stream with rejected cancel stops before readStatus or reopen',async()=>{
    let opens=0,reads=0,facts=0,cancels=0;const signal=new AbortController().signal;
    const stream=new ReadableStream({start(c){c.enqueue(new TextEncoder().encode('event: stream_reset\ndata: {"reason":"AUTHORITY_DENIED"}\n\nid: 5\ndata: {"seq":5}\n\n'));},cancel(){cancels++;return Promise.reject(new TypeError('cleanup failed'));}});
    vi.stubGlobal('fetch',async()=>new Response(stream));
    try{
      const outcome=await observeRun({signal,open:async(cursor,emit)=>{opens++;await streamEvents('/events',()=> 'token',cursor,emit,signal);},readStatus:async()=>{reads++;return {status:'SUCCEEDED'};},onEvent:()=>{facts++;},onStatus:()=>{}}).then(result=>({result:result.status}),error=>({error:{name:error.name,reason:error.reason,message:error.message}}));
      expect({outcome,opens,reads,facts,cancels,locked:stream.locked}).toMatchObject({outcome:{error:{reason:'AUTHORITY_DENIED'}},opens:1,reads:0,facts:0,cancels:1,locked:false});
    }finally{vi.unstubAllGlobals();}
  });
  it.each(['AUTHORITY_EXPIRED','AUTHORITY_DENIED','AUTHORITY_UNAVAILABLE'])('D4b stops %s reset without status or reopening',async reason=>{
    let opens=0,reads=0;const facts:any[]=[];
    await expect(observeRun({signal:new AbortController().signal,open:async(_,emit)=>{opens++;emit(fact({type:'stream_reset',reason}));emit(fact({type:'NODE_STARTED',seq:'1'}));},readStatus:async()=>{reads++;return {status:'SUCCEEDED'};},onStatus:()=>{},onEvent:e=>facts.push(e)})).rejects.toMatchObject({reason});
    expect(opens).toBe(1);expect(reads).toBe(0);expect(facts).toEqual([]);
  });
  it('D4b stops named IAM 503 but retains ordinary 503 recovery',async()=>{
    const base={signal:new AbortController().signal,pause:async()=>{},onStatus:()=>{},onEvent:()=>{}};let reads=0;
    await expect(observeRun({...base,open:async()=>{throw Object.assign(Error('iam unavailable'),{status:503,code:'IAM_AUTHORITY_UNAVAILABLE'});},readStatus:async()=>{reads++;return {status:'SUCCEEDED'};}})).rejects.toMatchObject({code:'IAM_AUTHORITY_UNAVAILABLE'});expect(reads).toBe(0);
    const result=await observeRun({...base,open:async()=>{throw Object.assign(Error('ordinary'),{status:503,code:'DEPENDENCY_UNAVAILABLE'});},readStatus:async()=>{reads++;return {status:'SUCCEEDED'};}});expect(result.status).toBe('SUCCEEDED');expect(reads).toBe(1);
  });
  it('keeps the accepted run through EOF and resumes from durable IDs, never chunk counters',async()=>{
    const opened:string[]=[],events:any[]=[],states:any[]=[];let round=0;
    const result=await observeRun({signal:new AbortController().signal,pause:async()=>{},
      open:async(cursor,emit)=>{opened.push(cursor);round++;if(round===1){emit(fact({type:'NODE_STARTED',seq:'7'}));emit(fact({type:'CHUNK',seq:'900',text:'partial'}));}else emit(fact({type:'RUN_FINISHED',seq:'9'}));},
      readStatus:async()=>round===1?{status:'RUNNING',eventsWatermark:'8'}:{status:'SUCCEEDED',eventsWatermark:'9',output:{text:'final'}},
      onEvent:e=>events.push(e),onStatus:s=>states.push(s)});
    expect(opened).toEqual(['0','8']);expect(result.output!.text).toBe('final');
    expect(states.map(s=>s.status)).toEqual(['RUNNING','SUCCEEDED']);expect(events).toHaveLength(3);
  });

  it('applies a reset snapshot before moving the cursor and drops replayed facts',async()=>{
    const order:string[]=[],seen:string[]=[];let round=0;
    await observeRun({signal:new AbortController().signal,pause:async()=>{},
      open:async(cursor,emit)=>{order.push('open:'+cursor);round++;if(round===1)emit(fact({type:'stream_reset',eventsWatermark:'50',reason:'CURSOR_EXPIRED'}));else{emit(fact({type:'NODE_STARTED',seq:'49'}));emit(fact({type:'RUN_FINISHED',seq:'51'}));}},
      readStatus:async()=>{order.push('read');return {status:round===1?'RUNNING':'SUCCEEDED',eventsWatermark:round===1?'50':'51'};},
      onStatus:()=>order.push('snapshot'),onEvent:e=>{if(e.seq)seen.push(e.seq);}});
    expect(order.slice(0,4)).toEqual(['open:0','read','snapshot','open:50']);expect(seen).toEqual(['51']);
  });

  it('uses status while the transient channel fails without reissuing creation',async()=>{
    let round=0;const states:string[]=[];
    const result=await observeRun({signal:new AbortController().signal,pause:async()=>{},
      open:async()=>{round++;throw new TypeError('network unavailable');},
      readStatus:async()=>({status:round<2?'WAITING_APPROVAL':'SUCCEEDED',eventsWatermark:String(round)}),
      onStatus:s=>states.push(s.status),onEvent:()=>{}});
    expect(round).toBe(2);expect(states).toEqual(['WAITING_APPROVAL','SUCCEEDED']);expect(result.status).toBe('SUCCEEDED');
  });

  it('stops on revoked authorization and bounds unavailable status retries',async()=>{
    let reads=0;
    const options={signal:new AbortController().signal,pause:async()=>{},open:async()=>{},onEvent:()=>{},onStatus:()=>{}};
    await expect(observeRun({...options,readStatus:async()=>{reads++;throw Object.assign(new Error('revoked'),{status:401});}})).rejects.toThrow('revoked');
    expect(reads).toBe(1);reads=0;
    await expect(observeRun({...options,maxFailures:3,readStatus:async()=>{reads++;throw new TypeError('offline');}})).rejects.toThrow('offline');
    expect(reads).toBe(3);
  });

  it('aborts a nonterminal observer without marking its backend run cancelled',async()=>{
    const controller=new AbortController();let reads=0;
    await expect(observeRun({signal:controller.signal,open:async()=>{},readStatus:async()=>{reads++;return {status:'RUNNING',eventsWatermark:'1'};},onStatus:()=>{},onEvent:()=>{},pause:async()=>{controller.abort();}})).rejects.toMatchObject({name:'AbortError'});
    expect(reads).toBe(1);
  });

  it('does not reopen before an unavailable authoritative snapshot has been applied', async()=>{
    const order:string[]=[];let reads=0;
    await observeRun({signal:new AbortController().signal,pause:async()=>{},
      open:async cursor=>{order.push('open:'+cursor);},
      readStatus:async()=>{order.push('read');if(++reads===1)throw new TypeError('offline');return {status:'SUCCEEDED',eventsWatermark:'4'};},
      onStatus:()=>{order.push('snapshot');},onEvent:()=>{}});
    expect(order).toEqual(['open:0','read','read','snapshot']);
  });

  it('polls after EVENT_LIMIT without reopening the permanently oversized fact', async()=>{
    let opens=0,reads=0;const delays:number[]=[];
    await observeRun({signal:new AbortController().signal,pause:async ms=>{delays.push(ms);},
      open:async(_,emit)=>{opens++;emit(fact({type:'stream_reset',reason:'EVENT_LIMIT',lastEventId:500}));},
      readStatus:async()=>({status:++reads===3?'SUCCEEDED':'RUNNING',eventsWatermark:String(reads)}),
      onStatus:()=>{},onEvent:()=>{throw Error('control frame delivered as fact');}});
    expect(opens).toBe(1);expect(reads).toBe(3);expect(delays).toEqual([750,750]);
  });

  it.each([400,401,403,404,422])('stops permanent stream HTTP %s without reading status', async status=>{
    let reads=0;
    await expect(observeRun({signal:new AbortController().signal,pause:async()=>{},
      open:async()=>{throw Object.assign(Error('HTTP '+status),{status});},
      readStatus:async()=>{reads++;return {status:'RUNNING'};},onStatus:()=>{},onEvent:()=>{}})).rejects.toThrow('HTTP '+status);
    expect(reads).toBe(0);
  });

  it('aborts a hanging status read and ignores its late response and old stream callback',async()=>{
    const controller=new AbortController();const states:any[]=[],events:any[]=[];
    let resolve!:(value:any)=>void,late!:(event:any)=>void;
    const observation=observeRun({signal:controller.signal,open:async(_,emit)=>{late=emit;},
      readStatus:()=>new Promise<any>(r=>{resolve=r;controller.abort();}),
      onStatus:s=>{states.push(s);},onEvent:e=>{events.push(e);}});
    await expect(observation).rejects.toMatchObject({name:'AbortError'});
    resolve({status:'SUCCEEDED',eventsWatermark:'8'});late(fact({type:'RUN_FINISHED',seq:'8'}));
    await Promise.resolve();expect(states).toEqual([]);expect(events).toEqual([]);
  });
});
