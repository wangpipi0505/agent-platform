import { describe, it, expect, vi } from 'vitest';
import { observeEvaluation, evaluationSummary } from '../admin/src/evaluation-observer';
const frozen = { id:'er_one', appCode:'app_a', draftRev:3, candidateSha256:'sha_a', createdBy:'owner', status:'RUNNING', results:[{id:'one',status:'PENDING',passed:false,safety:true}] };
describe('frozen evaluation observer', () => {
  it('counts dependency errors as failed safety cases without calling waiting cases failures',()=>{
    expect(evaluationSummary({results:[{id:'error',safety:true,passed:false,error:'unavailable',attribution:'DEPENDENCY_FAILURE'},{id:'waiting',safety:true,status:'WAITING_EXTERNAL',passed:false}]})).toMatchObject({passed:0,failed:1,pending:1,safetyFailed:1});
  });
  it('serially resumes the same report through multiple pending states to accepted', async()=>{
    let resumes=0,active=0,max=0;const seen:any[]=[];
    const api=vi.fn(async(path:string)=>{active++;max=Math.max(max,active);await Promise.resolve();active--;return path.endsWith('/resume')?{...frozen,status:++resumes===3?'ACCEPTED':'RUNNING'}:frozen;});
    await observeEvaluation(frozen,{api,signal:new AbortController().signal,canResume:()=>true,publish:r=>seen.push(r),notice:()=>{},sleep:async()=>{}});
    expect(resumes).toBe(3);expect(max).toBe(1);expect(api.mock.calls.every(([p])=>p.startsWith('/admin/evaluations/er_one'))).toBe(true);expect(seen.at(-1).status).toBe('ACCEPTED');
  });
  it('keeps waiting approval unfinished and never calls approval or creates another report',async()=>{
    const controller=new AbortController();const waiting={...frozen,results:[{id:'one',status:'WAITING_APPROVAL',passed:false,safety:true}]};const paths:string[]=[];
    await observeEvaluation(waiting,{api:async p=>{paths.push(p);return waiting;},signal:controller.signal,canResume:()=>true,publish:()=>{},notice:()=>{},sleep:async()=>{controller.abort();}});
    expect(paths).toEqual([]);expect(evaluationSummary(waiting)).toMatchObject({passed:0,failed:0,pending:1,safetyFailed:0});
  });
  it('stops on ERROR until explicit retry and preserves the original report identity',async()=>{
    const api=vi.fn(async()=>({...frozen,status:'ACCEPTED'}));const options={api,signal:new AbortController().signal,canResume:()=>true,publish:()=>{},notice:vi.fn(),sleep:async()=>{}};
    await observeEvaluation({...frozen,status:'ERROR'},options);expect(api).not.toHaveBeenCalled();
    await observeEvaluation({...frozen,status:'ERROR'},{...options,resumeError:true});expect(api).toHaveBeenCalledExactlyOnceWith('/admin/evaluations/er_one/resume','POST',undefined,options.signal);
  });
  it.each([400,401,403,404,409])('never retries a %s response',async status=>{
    const api=vi.fn(async()=>{throw Object.assign(Error('denied'),{status});});const notice=vi.fn();
    await observeEvaluation(frozen,{api,signal:new AbortController().signal,canResume:()=>true,publish:()=>{},notice,sleep:async()=>{}});expect(api).toHaveBeenCalledTimes(1);expect(notice.mock.calls.at(-1)?.[0]).toMatchObject({retryable:false,phase:'stopped'});
  });
  it('bounds network retry attempts and backoff without a second create request',async()=>{
    const waits:number[]=[];const api=vi.fn(async()=>{throw Error('offline');});const notice=vi.fn();
    await observeEvaluation(frozen,{api,signal:new AbortController().signal,canResume:()=>true,publish:()=>{},notice,sleep:async ms=>{waits.push(ms);}});
    expect(api).toHaveBeenCalledTimes(4);expect(waits).toEqual([1000,2000,4000,8000]);expect(notice.mock.calls.at(-1)?.[0]).toMatchObject({phase:'paused',retryable:true});
  });
  it('discards a delayed result after leave without canceling the business run',async()=>{
    let release!:(r:any)=>void;const controller=new AbortController(),publish=vi.fn();const api=vi.fn(()=>new Promise(r=>release=r));
    const task=observeEvaluation(frozen,{api,signal:controller.signal,canResume:()=>true,publish,notice:()=>{},sleep:async()=>{}});await Promise.resolve();controller.abort();release({...frozen,status:'ACCEPTED'});await task;
    expect(api).toHaveBeenCalledTimes(1);expect(publish).not.toHaveBeenCalled();
  });
  it('rejects a report from another candidate without publishing or resuming it',async()=>{
    const publish=vi.fn(),api=vi.fn(async()=>({...frozen,candidateSha256:'sha_b'})),notice=vi.fn();
    await observeEvaluation(frozen,{api,signal:new AbortController().signal,canResume:()=>true,publish,notice,sleep:async()=>{}});expect(publish).not.toHaveBeenCalled();expect(api).toHaveBeenCalledTimes(1);expect(notice.mock.calls.at(-1)?.[0].retryable).toBe(false);
  });
  it('stops if an arriving response changes the frozen report owner',async()=>{
    const publish=vi.fn(),api=vi.fn(async()=>({...frozen,createdBy:'other'})),notice=vi.fn();
    await observeEvaluation(frozen,{api,signal:new AbortController().signal,canResume:()=>true,publish,notice,sleep:async()=>{}});expect(publish).not.toHaveBeenCalled();expect(api).toHaveBeenCalledTimes(1);expect(notice.mock.calls.at(-1)?.[0].retryable).toBe(false);
  });
});
