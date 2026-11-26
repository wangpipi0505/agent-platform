import { describe, expect, it, vi } from 'vitest';
import { readTranscript, followTranscript } from '../shared/portal-conversation';
import type { RunEvent } from '../shared/run-protocol';

const event = (type: string, seq: string, data: unknown): RunEvent => ({ type, seq, data, rawData: '' });
describe('门户会话投影与续流', () => {
  it('恢复历史输入和回答，忽略系统消息', () => {
    const state = readTranscript({eventWatermark: 9, messages: [
      {seq: 1, role: 'system', content: {content: 'private'}},
      {seq: 2, role: 'user', content: {content: {message: '问题'}}},
      {seq: 8, role: 'assistant', content: {content: '回答'}}]});
    expect(state.messages.map(m => m.text)).toEqual(['问题', '回答']);
    expect(state.cursor).toBe('9');
  });
  it('断开后用最后应用的序号重连，重复回答不显示两次', async () => {
    const state = readTranscript({eventWatermark: 4, messages: []});
    const signal = new AbortController();
    const opened: string[] = [];
    const result = followTranscript(state, signal.signal, async (cursor, emit) => {
      opened.push(cursor);
      emit(event('assistant/message', '6', {content: '唯一回答'}));
      if (opened.length === 2) {
        emit(event('turn/end', '7', {status: 'SUCCEEDED'}));
        signal.abort();
      }
    }, async () => {});
    await result;
    expect(opened).toEqual(['4', '6']);
    expect(state.messages.map(m => m.text)).toEqual(['唯一回答']);
    expect(state.cursor).toBe('7');
  });
  it('权限拒绝后立即停止，不发起下一次请求', async () => {
    const open = vi.fn().mockRejectedValue(Object.assign(Error('denied'), {status:403}));
    await expect(followTranscript(readTranscript({eventWatermark:0,messages:[]}),
      new AbortController().signal, open, async () => {})).rejects.toThrow('denied');
    expect(open).toHaveBeenCalledTimes(1);
  });
});
