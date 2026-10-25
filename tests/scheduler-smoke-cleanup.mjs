// Private to the real scheduler smoke script and its finite, pure Node regression.
export async function cleanupSchedulerSmoke({ lock, children, processRecords, stop, fixture, sockets, writeRecords, failure, closeTimeoutMs = 1000 }) {
  const errors = [];
  const attempt = async (resource, close, timeoutMs) => {
    let timer;
    try {
      const closing = close();
      await (timeoutMs === undefined ? closing : Promise.race([closing, new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(Object.assign(new Error(resource + ' timed out after ' + timeoutMs + 'ms'), { code: 'ETIMEDOUT' })), timeoutMs);
      })]));
    } catch (cause) { errors.push(new Error('Cleanup failed: ' + resource, { cause })); }
    finally { clearTimeout(timer); }
  };
  if (lock && lock.exitCode === null) await attempt('node lock', () => new Promise((resolve, reject) => {
    // end() returns Writable; observe completion and retain the error handler because
    // Node may emit the queued stream error after invoking the end callback.
    lock.stdin.on('error', reject);
    lock.stdin.end('rollback;\n\\q\n', error => error ? reject(error) : resolve());
  }), closeTimeoutMs);
  for (const child of children) {
    await attempt('engine PID ' + child.pid, () => stop({ child, record: processRecords.find(entry => entry.pid === child.pid) }));
  }
  // Initiate peer close before destroying connections; its callback may wait for them.
  const peerClosed = fixture ? attempt('fixture peer', () => new Promise((resolve, reject) => {
    fixture.close(error => error && error.code !== 'ERR_SERVER_NOT_RUNNING' ? reject(error) : resolve());
  }), closeTimeoutMs) : Promise.resolve();
  let socketIndex = 0;
  for (const socket of sockets) await attempt('fixture socket ' + socketIndex++, () => socket.destroy());
  // Persist final stop results even while peer shutdown is still settling.
  await attempt('process records', writeRecords);
  await peerClosed;
  if (errors.length) {
    throw new AggregateError(failure ? [failure.error, ...errors] : errors,
      'Scheduler smoke cleanup failed', failure ? { cause: failure.error } : undefined);
  }
  if (failure) throw failure.error;
}
