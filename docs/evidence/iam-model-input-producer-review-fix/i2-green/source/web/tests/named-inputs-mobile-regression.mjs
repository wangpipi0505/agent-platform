import fs from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// Replay the frozen 23 component checks, changing only their artifact filenames.
// Historical fixture reports and screenshots must not be overwritten by this CSS repair.
const scratch = path.join(import.meta.dirname, '.named-inputs-mobile-recheck.mjs');
try {
  for (const source of ['named-inputs-browser.mjs', 'named-evaluation-browser.mjs']) {
    const text = await fs.readFile(path.join(import.meta.dirname, source), 'utf8');
    await fs.writeFile(scratch, text.replaceAll('docs/evidence/frontend-test-inputs-', 'docs/evidence/frontend-test-inputs-mobile-recheck-'));
    const run = spawnSync(process.execPath, [scratch, 'regression'], { cwd: path.resolve(import.meta.dirname, '../..'), stdio: 'inherit' });
    if (run.error) throw run.error;
    if (run.status !== 0) throw Error(`${source} failed with exit ${run.status}`);
  }
} finally {
  await fs.rm(scratch, { force: true });
}
