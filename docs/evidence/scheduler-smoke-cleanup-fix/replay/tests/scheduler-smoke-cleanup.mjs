// Private to the real scheduler smoke script and its finite, pure Node regression.
export async function cleanupSchedulerSmoke({ lock, children, processRecords, stop, fixture, sockets, writeRecords, failure }) {
  const errors = [];
  const attempt = async (resource, close) => {
    try { await close(); } catch (cause) { errors.push(new Error('Cleanup failed: ' + resource, { cause })); }
  };
  if (lock && lock.exitCode === null) await attempt('node lock', () => lock.stdin.end('rollback;\n\\q\n'));
  for (const child of children) {
    await attempt('engine PID ' + child.pid, () => stop({ child, record: processRecords.find(entry => entry.pid === child.pid) }));
  }
  // Initiate peer close before destroying connections; its callback may wait for them.
  const peerClosed = fixture ? attempt('fixture peer', () => new Promise((resolve, reject) => {
    fixture.close(error => error && error.code !== 'ERR_SERVER_NOT_RUNNING' ? reject(error) : resolve());
  })) : Promise.resolve();
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
