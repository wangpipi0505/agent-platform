import { after, cursor, record, type RunEvent } from './run-protocol';

type Message = { seq: string; role: 'user' | 'assistant'; text: string };
type Transcript = { cursor: string; messages: Message[]; failure: string };
type OpenStream = (lastId: string, receive: (event: RunEvent) => void) => Promise<void>;

function message(seq: string, role: unknown, payload: unknown): Message | undefined {
  if (role !== 'user' && role !== 'assistant') return;
  const content = record(payload)?.content ?? payload;
  const text = typeof content === 'string' ? content : record(content)?.message ?? record(content)?.text;
  if (typeof text === 'string') return { seq, role, text };
}

export function readTranscript(snapshot: unknown): Transcript {
  const view = record(snapshot);
  const mark = view?.eventWatermark;
  if (!Number.isSafeInteger(mark) || Number(mark) < 0 || !Array.isArray(view?.messages))
    throw Error('会话快照缺少可用的事件游标或消息');
  const messages: Message[] = [];
  for (const row of view.messages) {
    const item = message(String(row.seq), row.role, row.content);
    if (item) messages.push(item);
  }
  return {cursor: String(mark), messages, failure: ''};
}

/** 事件只在应用成功后推进水位。连接丢失只重开 GET，不重发用户轮次。 */
export async function followTranscript(state: Transcript, signal: AbortSignal, open: OpenStream,
  pause = () => new Promise<void>(resolve => setTimeout(resolve, 800))) {
  let failures = 0;
  while (!signal.aborted) {
    const start = state.cursor;
    try {
      await open(start, event => {
        if (signal.aborted || !cursor(event.seq) || !after(event.seq, state.cursor)) return;
        const data = record(event.data);
        const item = message(event.seq, event.type === 'user/message' ? 'user'
          : event.type === 'assistant/message' ? 'assistant' : undefined, data);
        if (item) state.messages.push(item);
        if (event.type === 'turn/end') state.failure = data?.status === 'FAILED' || data?.reason === 'error'
          ? '本轮运行失败，请核对运行记录。' : '';
        state.cursor = event.seq;
      });
    } catch (error) {
      if (signal.aborted) return;
      const status = Number(record(error)?.status);
      if (status && status < 500) throw error;
      if (failures >= 2) throw error;
    }
    if (signal.aborted) return;
    failures = start === state.cursor ? failures + 1 : 0;
    if (failures >= 3) throw Error('会话流已断开，请重新连接');
    await pause();
  }
}
