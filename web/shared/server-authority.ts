import { object } from './semantic-configuration';
import type { EvidenceRef, GoalOutcomeLike, ServerOutcome } from './semantic-runtime';
export interface SemanticReceiptView {
  callId: string;
  server: ServerOutcome;
  release: string;
  observedAt: string;
  facts: unknown[];
}
export interface RunAuthority {
  goals: GoalOutcomeLike[];
  receipts: SemanticReceiptView[];
  error: string;
}
export const emptyAuthority = (): RunAuthority => ({
  goals: [],
  receipts: [],
  error: ''
});
function identifier(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? String(value) : undefined;
}
function evidence(value: unknown): EvidenceRef | undefined {
  const ref = object(value);
  const callId = identifier(ref.callId);
  return ref.evidenceKind === 'call' && callId ? {
    evidenceKind: 'call',
    callId,
    ...(typeof ref.runId === 'string' ? {
      runId: ref.runId
    } : {})
  } : undefined;
}
/** 仅消费独立服务端字段；output/模型文本中的同名键一概不作为权威事实。 */
export function readRunAuthority(runId: string, snapshot: unknown, semantic: unknown): RunAuthority {
  const run = object(snapshot);
  const response = object(semantic);
  const view = emptyAuthority();
  if (run.runId !== runId)
    throw Error('目标回执与当前运行不一致');
  if (Array.isArray(run.goals))
    view.goals = run.goals.map(object).filter(item => typeof item.goalId === 'string').map(item => ({
      goalId: String(item.goalId),
      outcomeState: [
        'ACHIEVED',
        'NOT_ACHIEVED',
        'PENDING',
        'FAILED',
        'UNKNOWN'
      ].includes(String(item.outcomeState)) ? item.outcomeState as GoalOutcomeLike['outcomeState'] : 'UNKNOWN',
      readbackRef: evidence(item.readbackRef) ?? null,
      nextStep: typeof item.nextStep === 'string' ? item.nextStep : undefined,
      detail: object(item.detail)
    }));
  if (semantic === undefined)
    return view;
  if (response.runId !== runId)
    throw Error('语义回执与当前运行不一致');
  for (const [key, accepted] of [['results', true], ['rejected', false]] as const) {
    if (!Array.isArray(response[key]))
      throw Error('语义回执列表不完整');
    for (const raw of response[key]) {
      const item = object(raw);
      const record = object(item.record);
      const release = object(record.releaseRef);
      const callId = identifier(item.callId);
      if (!callId)
        continue;
      const valid = accepted && item.valid === true && record.profile === 'read-only' && record.schemaVersion === '1.0';
      const state = !valid ? 'UNKNOWN' : record.status === 'OK' ? 'SUCCEEDED' : record.status === 'ERROR' ? 'FAILED' : 'UNKNOWN';
      const errors = Array.isArray(record.errors) ? record.errors.map(object).map(error => String(error.message ?? error.code ?? '无法核对')).join('；') : '';
      view.receipts.push({
        callId,
        server: {
          state,
          evidence: valid ? [{
              evidenceKind: 'call',
            callId,
            runId
            }] : [],
          message: !valid ? '语义回执未通过服务端校验' : errors || String(record.status ?? 'UNKNOWN')
        },
        release: valid ? `${String(release.spaceCode ?? '')} / ${String(release.version ?? '')}` : '未确认',
        observedAt: valid ? JSON.stringify(record.timeliness ?? {}) : '未确认',
        facts: valid && Array.isArray(object(record.factSet).items) ? object(record.factSet).items as unknown[] : []
      });
    }
  }
  return view;
}
