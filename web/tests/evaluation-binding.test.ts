import {expect,it} from 'vitest';
import {evaluationEvidenceLabel,matchesBoundEvaluation} from '../admin/src/evaluation-observer';

it('shows exact or semantic selection only for the server-bound original report without rewriting it',()=>{
  const report=Object.freeze({evalReportId:'er-original',appCode:'app',draftRev:3,candidateSha256:'a'.repeat(64),status:'ACCEPTED'});
  const exact={matchedBy:'SHA256',evalReportId:'er-original',draftRev:3,candidateSha256:'a'.repeat(64),evaluatedCandidateSha256:'a'.repeat(64)};
  expect(matchesBoundEvaluation(report,exact,{appCode:'app',draftRev:3,candidateSha256:'a'.repeat(64)})).toBe(true);
  expect(evaluationEvidenceLabel(exact)).toMatch(/精确匹配/);
  const semantic={...exact,matchedBy:'SEMANTIC_DIGEST',candidateSha256:'b'.repeat(64)};
  const target={appCode:'app',draftRev:3,candidateSha256:'b'.repeat(64)};
  expect(matchesBoundEvaluation(report,semantic,target)).toBe(true);
  expect(evaluationEvidenceLabel(semantic)).toMatch(/复用原报告.*未重新评测/);
  for(const changed of [{evalReportId:'other'},{evaluatedCandidateSha256:'c'.repeat(64)},{matchedBy:'SHA256'},{matchedBy:'UNKNOWN'}])
    expect(matchesBoundEvaluation(report,{...semantic,...changed},target)).toBe(false);
  expect(matchesBoundEvaluation(report,semantic,{...target,appCode:'foreign'})).toBe(false);
  expect(evaluationEvidenceLabel({})).toBe('匹配方式未记录');
  expect(report.candidateSha256).toBe('a'.repeat(64));expect('matchedBy' in report).toBe(false);
});
