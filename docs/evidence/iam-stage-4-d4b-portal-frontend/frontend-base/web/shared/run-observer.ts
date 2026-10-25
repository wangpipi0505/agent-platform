import { terminal } from '../debug-panel/events';

type RunStatus = { status: string; eventsWatermark?: number; [key: string]: any };
type Options<T extends RunStatus> = {
  signal: AbortSignal;
  open: (cursor: number, emit: (event: any) => void) => Promise<void>;
  readStatus: () => Promise<T>;
  onEvent: (event: any) => void;
  onStatus: (snapshot: T) => unknown;
  onConnection?: (state: 'connected' | 'reconnecting') => void;
  pause?: (milliseconds: number) => Promise<void>;
  maxFailures?: number;
};

const permanent = (error: any) =>
  error?.status >= 400 && error.status < 500 && ![408, 425, 429].includes(error.status);

// Observe an already accepted run. Only an applied PG snapshot or a consumed fact
// advances the cursor; ephemeral chunks and reset hints never acknowledge facts.
export async function observeRun<T extends RunStatus>(options: Options<T>): Promise<T> {
  const { signal } = options;
  let cursor = 0;
  let failures = 0;
  let snapshotOnly = false;
  let awaitingSnapshot = false;
  const check = () => {
    if (signal.aborted) throw new DOMException('观察已停止', 'AbortError');
  };
  const pending = async <V>(work: () => Promise<V>): Promise<V> => {
    check();
    let abort!: () => void;
    const stopped = new Promise<never>((_, reject) => {
      abort = () => reject(new DOMException('观察已停止', 'AbortError'));
      signal.addEventListener('abort', abort, { once: true });
    });
    try { return await Promise.race([work(), stopped]); }
    finally { signal.removeEventListener('abort', abort); }
  };
  const pause = (milliseconds: number) => pending(() => options.pause
    ? options.pause(milliseconds)
    : new Promise<void>((resolve) => {
      const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
      const timer = setTimeout(finish, milliseconds);
      signal.addEventListener('abort', finish, { once: true });
    }));
  while (true) {
    check();
    if (!snapshotOnly && !awaitingSnapshot) {
      let accepting = true;
      try {
        await pending(() => options.open(cursor, event => {
          if (!accepting || signal.aborted) return;
          if (event.type === 'stream_reset') {
            // An oversized durable event cannot become replayable by reconnecting.
            // Poll snapshots until terminal instead of repeatedly requesting it.
            if (event.reason === 'EVENT_LIMIT') snapshotOnly = true;
            return;
          }
          if (event.type === 'stream_status') return;
          const chunk = event.type === 'CHUNK' || event.type === 'chunk';
          if (!chunk) {
            if (!Number.isSafeInteger(event.seq) || event.seq <= cursor) return;
          }
          options.onEvent(event);
          if (!chunk) cursor = event.seq;
        }));
      } catch (error) {
        check();
        if (permanent(error)) throw error;
      } finally { accepting = false; }
    }
    awaitingSnapshot = true;
    options.onConnection?.('reconnecting');
    let snapshot: T;
    try {
      snapshot = await pending(options.readStatus);
    } catch (error) {
      check();
      if (permanent(error) || ++failures >= (options.maxFailures ?? 5)) throw error;
      await pause(Math.min(8000, 750 * 2 ** (failures - 1)));
      continue;
    }
    check();
    await options.onStatus(snapshot);
    check();
    awaitingSnapshot = false;
    if (Number.isSafeInteger(snapshot.eventsWatermark) && snapshot.eventsWatermark! >= 0)
      cursor = snapshot.eventsWatermark!;
    failures = 0;
    if (terminal(snapshot.status)) return snapshot;
    await pause(750);
    options.onConnection?.('connected');
  }
}
