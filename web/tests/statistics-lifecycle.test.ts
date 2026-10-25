import { describe, expect, it } from 'vitest';
import { applicationQuery, copyApplicationBody, statisticsQuery, rebuildBody, tokenLabel, statisticsPages, failureTarget, diffValue } from '../shared/statistics-lifecycle';

describe('statistics and lifecycle boundaries', () => {
  it('sends all application filters to the server including explicit archived', () => {
    const q = new URLSearchParams(applicationQuery({keyword:'A&B',code:'app',domain:'d',bizCategory:'b',tags:'t',status:'ARCHIVED'}));
    expect(Object.fromEntries(q)).toEqual({q:'A&B',code:'app',domain:'d',bizCategory:'b',tag:'t',status:'ARCHIVED'});
    expect(applicationQuery({status:''})).not.toContain('status');
  });
  it('copies only code/name and rejects missing fields', () => {
    expect(copyApplicationBody({code:'new_app',name:' 新应用 ',config:{secret:'bad'},status:'PUBLISHED'})).toEqual({code:'new_app',name:'新应用'});
    expect(() => copyApplicationBody({code:'',name:'x'})).toThrow();
  });
  it('requires explicit UTC dates and valid half-open windows and pagination', () => {
    expect(() => statisticsQuery({from:'2026-10-20',to:'2026-10-19',page:1,pageSize:20})).toThrow();
    expect(() => statisticsQuery({from:'2026-10-01',to:'2026-10-20',page:0,pageSize:20})).toThrow();
    const q = new URLSearchParams(statisticsQuery({from:'2026-10-01',to:'2026-10-20',appCode:'a&b',page:2,pageSize:20}));
    expect(q.get('from')).toBe('2026-10-01T00:00:00.000Z');
    expect(q.get('appCode')).toBe('a&b');
    expect(q.get('page')).toBe('2');
  });
  it('rebuild never sends aggregates, pagination, or identity supplied by a client', () => {
    expect(rebuildBody({from:'2026-10-01',to:'2026-10-20',page:2,pageSize:20,total:9,tenantId:'fake'} as any,'stats_1')).toEqual({from:'2026-10-01T00:00:00.000Z',to:'2026-10-20T00:00:00.000Z',groupBy:'day',requestId:'stats_1'});
  });
  it('keeps missing tokens unknown and uses independent server group totals', () => {
    expect(tokenLabel({totalTokens:null,knownTokens:12,usageMissingCalls:1})).toBe('待计量');
    expect(tokenLabel({totalTokens:0,calls:0})).toBe('0');
    expect(tokenLabel({})).toBe('待计量');
    expect(statisticsPages({groupsTotal:3,skillGroupsTotal:41},20)).toBe(3);
  });
  it('maps admin failure metadata to an API-backed dialog and rejects foreign links', () => {
    expect(failureTarget('/admin/statistics/runs/r_1')).toEqual({kind:'metadata',runId:'r_1'});
    expect(failureTarget('/admin/runs/r_2')).toEqual({kind:'owner',runId:'r_2'});
    expect(() => failureTarget('https://evil/admin/runs/r_1')).toThrow();
  });
  it('distinguishes removed/missing values from explicit null in version diffs', () => {
    expect(diffValue({operation:'ADD',after:null},'before')).toBe('—');
    expect(diffValue({operation:'ADD',after:null},'after')).toBe('null');
    expect(diffValue({operation:'REMOVE',before:false},'before')).toBe('false');
  });
});
