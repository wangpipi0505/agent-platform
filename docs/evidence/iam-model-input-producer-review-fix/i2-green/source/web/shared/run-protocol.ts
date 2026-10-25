export type RunEvent = { type: string; seq?: string; data: unknown; rawData: string };
export type SnapshotEvent = { seq: string; type?: unknown; payload?: unknown; created_at?: unknown; [key: string]: unknown };
export type RunStatus = { status: string; eventsWatermark: string; events?: SnapshotEvent[]; [key: string]: any };
export const record = (value: unknown): Record<string, unknown> | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
export function cursor(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value)
    && (value.length < 19 || (value.length === 19 && value <= '9223372036854775807'));
}
export const after = (value: string, previous: string) =>
  value.length > previous.length || (value.length === previous.length && value > previous);
export const protocolFailure = (error: any) => ['RUN_PROTOCOL_ERROR', 'STATUS_CONTEXT_UNSUPPORTED'].includes(error?.code);
const invalid = () => Object.assign(Error('运行状态游标无效，请刷新后重新连接'), {code:'RUN_PROTOCOL_ERROR'});

// Native reviver visits only surviving keys. Select the token by the final
// envelope.data holder identity; nested and discarded fields remain untouched.
export function decodeRunStatus(text: string): any {
  const tokens = new WeakMap<object, string | undefined>();
  const sequences = new WeakMap<object, string | undefined>();
  const parse = JSON.parse as (text: string, reviver: (this: any, key: string, value: any, context?: {source?: string}) => any) => any;
  const envelope = parse(text, function(key, value, context) {
    if (key === 'eventsWatermark') tokens.set(this, typeof value === 'number' ? context?.source : undefined);
    if (key === 'seq') sequences.set(this, typeof value === 'number' ? context?.source : undefined);
    return value;
  });
  if (record(envelope)?.code !== 'OK') return envelope;
  const data = record(envelope.data);
  if (!data || typeof data.status !== 'string') throw invalid();
  const exact = (value: unknown, source: string | undefined) => {
    if (typeof value !== 'number') throw invalid();
    if (source === undefined) throw Object.assign(Error('当前浏览器无法精确读取运行游标，请升级 Chrome 或 Edge 后重新连接'), {code:'STATUS_CONTEXT_UNSUPPORTED'});
    if (!cursor(source)) throw invalid();
    return source;
  };
  const result: Record<string, unknown> = {...data, eventsWatermark:exact(data.eventsWatermark, tokens.get(data))};
  if (Object.hasOwn(data, 'events')) {
    if (!Array.isArray(data.events)) throw invalid();
    result.events = data.events.map(row => {
      const fields = record(row);
      if (!fields) throw invalid();
      return {...fields, seq:exact(fields.seq, sequences.get(fields))};
    });
  }
  return {...envelope, data:result};
}
export const eventText = (data: unknown) => {
  const fields = record(data);
  return [fields?.text, fields?.delta, fields?.content].find(value => typeof value === 'string' && value.length > 0) as string | undefined;
};
