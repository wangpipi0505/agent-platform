export function applicationQuery(filters: Record<string, string>) {
  const query = new URLSearchParams();
  for (const [key, apiKey] of Object.entries({keyword:'q',code:'code',domain:'domain',bizCategory:'bizCategory',tags:'tag',status:'status'})) {
    if (filters[key]?.trim()) query.set(apiKey, filters[key].trim());
  }
  return query.toString();
}
export function copyApplicationBody(value: Record<string, unknown>) {
  if (typeof value.code !== 'string' || !value.code.trim() || typeof value.name !== 'string' || !value.name.trim()) throw Error('请填写新应用的编码和名称');
  return {code:value.code.trim(),name:value.name.trim()};
}
export type StatisticsFilters = {from:string;to:string;appCode?:string;bizSystemCode?:string;callKind?:string;page?:number;pageSize?:number};
function utcDate(value:string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw Error('请选择 UTC 起止日期');
  const date = new Date(value+'T00:00:00Z');
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0,10)!==value) throw Error('日期无效');
  return date.toISOString();
}
function selection(value:StatisticsFilters) {
  const from=utcDate(value.from), to=utcDate(value.to), days=(Date.parse(to)-Date.parse(from))/86400000;
  if (days<=0 || days>366) throw Error('结束日期须晚于开始日期，查询窗口最长 366 天');
  if (value.callKind && !['FORMAL','DEBUG','EVALUATION'].includes(value.callKind)) throw Error('运行用途无效');
  return {from,to,groupBy:'day',...(value.appCode?.trim()?{appCode:value.appCode.trim()}:{}),...(value.bizSystemCode?.trim()?{bizSystemCode:value.bizSystemCode.trim()}:{}),...(value.callKind?{callKind:value.callKind}:{})};
}
export function statisticsQuery(value:StatisticsFilters) {
  const page=value.page??1, pageSize=value.pageSize??20;
  if (!Number.isInteger(page)||page<1||page>2000||!Number.isInteger(pageSize)||pageSize<1||pageSize>100) throw Error('分页范围无效');
  return new URLSearchParams({...selection(value),page:String(page),pageSize:String(pageSize)}).toString();
}
export function rebuildBody(value:StatisticsFilters,requestId:string) { return {...selection(value),requestId}; }
export function tokenLabel(model:any) { return model?.totalTokens==null || model?.usageMissingCalls>0 ? '待计量' : String(model.totalTokens); }
export function statisticsPages(report:any,pageSize:number) { return Math.max(1,Math.ceil(Math.max(report.groupsTotal??0,report.skillGroupsTotal??0)/pageSize)); }
export function failureTarget(href:string) {
  const match=/^\/admin\/(statistics\/)?runs\/([A-Za-z0-9_-]+)$/.exec(href);
  if (!match) throw Error('失败记录链接无效，请刷新统计');
  return {kind:match[1]?'metadata':'owner',runId:match[2]};
}
export function diffValue(change:Record<string,unknown>,field:'before'|'after') {
  if (!Object.hasOwn(change,field)) return '—';
  return typeof change[field]==='string'?String(change[field]):JSON.stringify(change[field]);
}
export function percentage(value:unknown) { return value==null?'—':(Number(value)*100).toFixed(1)+'%'; }
export function milliseconds(value:unknown) { return value==null?'—':Number(value).toLocaleString('zh-CN')+' ms'; }
export function defaultStatisticsWindow() { const to=new Date();to.setUTCHours(0,0,0,0);to.setUTCDate(to.getUTCDate()+1);const from=new Date(to);from.setUTCDate(from.getUTCDate()-30);return {from:from.toISOString().slice(0,10),to:to.toISOString().slice(0,10)}; }
