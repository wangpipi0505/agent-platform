import { expect, it } from 'vitest';
import { buildEnvironmentConfirmation, isGateRejection, summarizeGateState, type PublishPrecheck } from '../admin/src/publish-gate';

it('environment failures never offer the ordinary ADMIN quality waiver', () => {
  for (const code of ['EVALUATION_ENVIRONMENT_BLOCKED', 'EVALUATION_ENVIRONMENT_REQUIRED', 'EVALUATION_ENVIRONMENT_CONFIRMATION_REQUIRED']) {
    const error: unknown = { status: 409, code, message: '来源环境拒绝', traceId: 'ex17-trace' };
    expect(isGateRejection(error)).toBe(true);
    if (!isGateRejection(error)) throw new Error('environment error must be classified');
    const view = summarizeGateState({ rejection: error, isAdmin: true });
    expect(view.canApplyWaiver).toBe(false);
    expect(view.applyHint).toMatch(/普通.*豁免/);
    expect(view.blocked?.traceId).toBe('ex17-trace');
  }
});

it('binds an explicit demo decision only to the current non-production server precheck and evidence', () => {
  const report: PublishPrecheck = { precheckId: 'pc-one', evalReportId: 'er-one', draftRev: 1,
    candidateSha256: 'a'.repeat(64), passed: true, result: 'PASS', gateMode: 'BLOCKING',
    publicationTarget: 'NON_PRODUCTION', evaluationEnvironment: 'DEMO', environmentConfirmationRequired: true,
    environmentEvidenceSha256: 'b'.repeat(64) };
  const confirmed = { precheckId: 'pc-one', evalReportId: 'er-one', evidenceSha256: 'b'.repeat(64) };
  expect(buildEnvironmentConfirmation(report, confirmed)).toEqual({ confirmed: true, ...confirmed });
  expect(() => buildEnvironmentConfirmation(report, null)).toThrow(/显式确认/);
  for (const changed of [{ publicationTarget: 'PRODUCTION' }, { evaluationEnvironment: 'UNKNOWN' },
    { environmentConfirmationRequired: false }, { evaluationEnvironment: 'UNKNOWN', environmentConfirmationRequired: false },
    { precheckId: 'pc-two' }, { evalReportId: 'er-two' },
    { environmentEvidenceSha256: 'c'.repeat(64) }])
    expect(() => buildEnvironmentConfirmation({ ...report, ...changed } as PublishPrecheck, confirmed)).toThrow();
  expect(buildEnvironmentConfirmation({ ...report, evaluationEnvironment: 'REAL', environmentConfirmationRequired: false }, confirmed)).toBeUndefined();
});
