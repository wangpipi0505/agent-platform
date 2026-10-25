import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

for (const mode of ['ps-rejection', 'double-failure', 'fast-rejection', 'all-close-errors', 'close-never-settles']) {
  test('actual async test cleanup: ' + mode, async () => {
    const { stdout, stderr } = await promisify(execFile)(process.execPath,
      [fileURLToPath(new URL('./algorithm-process-runner-async-cleanup-probe.mjs', import.meta.url)), mode],
      { timeout: 2000, maxBuffer: 16384, windowsHide: true });
    assert.equal(stderr, '', 'No unhandled rejection or unsettled-await warning');
    const result = JSON.parse(stdout);
    assert.deepEqual(result.closeCalls, ['closeAllConnections', 'close']);
    assert.deepEqual(result.unhandled, []);
    if (mode !== 'close-never-settles') assert.equal(result.originalPsRetained, true);
    if (mode === 'double-failure') { assert.equal(result.originalHttpRetained, true); assert.equal(result.httpIsPrimary, true); }
    if (mode === 'all-close-errors') { assert.equal(result.destroyErrorRetained, true); assert.equal(result.closeErrorRetained, true); }
    if (mode === 'close-never-settles') assert(result.errors.some(error => error.code === 'ETIMEDOUT'));
  });
}
