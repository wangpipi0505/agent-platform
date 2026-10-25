// Private to the real scheduler smoke script and its finite, pure Node regression.
export async function cleanupSchedulerSmoke({ lock, children, processRecords, stop, fixture, sockets, writeRecords }) {
  if (lock && lock.exitCode === null) lock.stdin.end('rollback;\n\\q\n');
  for (const child of children) { const record = processRecords.find(entry => entry.pid === child.pid); await stop({ child, record }); }
  fixture.close(); for (const socket of sockets) socket.destroy();
  await writeRecords();
}
