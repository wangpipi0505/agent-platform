export const pendingCase = (status: string) => ['PENDING','CREATED','READY','RUNNING','WAITING_EXTERNAL','WAITING_APPROVAL','WAITING_STEP'].includes(status);
export function evaluationSummary(report: any) {
  const rows = report?.results || [];
  const passed = (r:any) => r.status === 'SUCCEEDED' && r.passed === true;
  const failed = (r:any) => !pendingCase(r.status) && (!!r.error || ['SUCCEEDED','FAILED','CANCELLED','ERROR'].includes(r.status)) && !passed(r);
  return { total: rows.length, passed: rows.filter(passed).length, failed: rows.filter(failed).length,
    pending: rows.filter((r:any)=>pendingCase(r.status)).length, safety:rows.filter((r:any)=>r.safety).length,
    safetyPassed:rows.filter((r:any)=>r.safety&&passed(r)).length, safetyFailed:rows.filter((r:any)=>r.safety&&failed(r)).length };
}
export function evaluationLabel(report:any) {
  if(report?.status==='ACCEPTED')return '评测已通过';
  if(report?.status==='REJECTED')return '评测未通过';
  if(report?.status==='ERROR')return '评测执行出错';
  const rows=report?.results||[];
  if(rows.some((r:any)=>r.status==='WAITING_APPROVAL'))return '等待人工确认';
  if(rows.some((r:any)=>r.status==='WAITING_STEP'))return '等待单步放行';
  if(rows.some((r:any)=>r.status==='WAITING_EXTERNAL'))return '等待外部任务';
  if(report?.status==='PENDING'||rows.some((r:any)=>r.status==='PENDING'))return '评测排队中';
  return '评测进行中';
}
type Notice = {phase:'observing'|'recovering'|'paused'|'stopped'|'finished';message:string;retryable:boolean};
type Options = {api:(path:string,method?:string,body?:any,signal?:AbortSignal)=>Promise<any>;signal:AbortSignal;canResume:()=>boolean;publish:(report:any)=>void;notice:(notice:Notice)=>void;resumeError?:boolean;sleep?:(ms:number)=>Promise<void>};
function pause(ms:number,signal:AbortSignal) {
  return new Promise<void>((resolve,reject)=>{
    signal.throwIfAborted();const abort=()=>{clearTimeout(timer);reject(new DOMException('Aborted','AbortError'));};
    const timer=setTimeout(()=>{signal.removeEventListener('abort',abort);resolve();},ms);signal.addEventListener('abort',abort,{once:true});
  });
}
export async function observeEvaluation(initial:any, options:Options) {
  const {api,signal,publish,canResume}=options;
  let report=initial,failures=0,cycles=0,explicit=options.resumeError===true;
  const id=initial.evalReportId||initial.id;
  const notice=(phase:Notice['phase'],message:string,retryable=false)=>{if(!signal.aborted)options.notice({phase,message,retryable});};
  function validate(value:any) {
    if(!id || (value.evalReportId||value.id)!==id || value.appCode!==initial.appCode || value.draftRev!==initial.draftRev || value.candidateSha256!==initial.candidateSha256 || value.createdBy!==initial.createdBy)
      throw Object.assign(Error('报告标识或冻结候选不匹配，已停止观察。'),{status:409});
    return value;
  }
  while(!signal.aborted) {
    if(['ACCEPTED','REJECTED'].includes(report.status)){notice('finished','评测已完成；发布前请重新预检绑定报告。');return;}
    if(report.status==='ERROR'&&!explicit){notice('paused','原报告执行出错。确认依赖恢复后，可重试此报告。',true);return;}
    if(!canResume()){notice('stopped','仅原发起者且具备编辑权限时可恢复评测。当前仅查看报告。');return;}
    try {
      if(!explicit) {
        const delay=failures?Math.min(15000,1000*2**failures):report.results?.some((r:any)=>['WAITING_APPROVAL','WAITING_STEP','WAITING_EXTERNAL'].includes(r.status))?3000:Math.min(5000,1000+cycles*1000);
        await (options.sleep?options.sleep(delay):pause(delay,signal));signal.throwIfAborted();
        report=validate(await api('/admin/evaluations/'+encodeURIComponent(id),'GET',undefined,signal));
        if(signal.aborted)return;publish(report);
      }
      if(['ACCEPTED','REJECTED'].includes(report.status))continue;
      if(report.status==='ERROR'&&!explicit)continue;
      if(!canResume()){notice('stopped','当前身份不能恢复此报告。');return;}
      report=validate(await api('/admin/evaluations/'+encodeURIComponent(id)+'/resume','POST',undefined,signal));
      if(signal.aborted)return;publish(report);failures=0;cycles++;explicit=false;
      notice('observing','正在观察原报告；离开页面不会取消评测。');
    } catch(e:any) {
      if(signal.aborted||e.name==='AbortError')return;
      explicit=false;
      if(e.status>=400&&e.status<500){notice('stopped',e.status===401?'登录已失效，已停止观察。':e.status===403?'没有读取或恢复此报告的权限，已停止观察。':e.message);return;}
      if(++failures>=4){notice('paused','连接恢复失败，已暂停观察；原报告保留，可重新连接。',true);return;}
      notice('recovering','连接暂时中断，正在退避重试原报告。');
    }
  }
}
