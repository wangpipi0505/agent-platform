import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { assertAttemptScope } from './algorithm-process-attempt-scope.mjs';

// Reuse the archived v4 rows and the existing test's explicitly synthetic before-node enrichment.
const input = new URL('../docs/evidence/algorithm-process-runner-attempt-scope/inputs/', import.meta.url);
const read = async name => JSON.parse(await fs.readFile(new URL(name, input), 'utf8'));
const archived = await read('v4-ack-loss.json'), pg = (await read('algorithm-process-live-v4-readonly.json')).data;
const fixture = await read('v4-ack-fixture-final.json');
const source = await fs.readFile(new URL('./algorithm-process-attempt-scope.test.mjs', import.meta.url), 'utf8');
const start = source.indexOf('function sample()'), end = source.indexOf("test('actual v4", start);
assert(start >= 0 && end > start);
const sample = new Function('archived', 'pg', 'fixture', source.slice(start, end) + '\nreturn sample;')(archived, pg, fixture);

function withTimes(times) {
  const scenario = sample(), view = scenario.after;
  const rows = [view.terminalNotes[0], view.events.find(event => event.type === 'EXTERNAL_READY'),
    view.events.find(event => event.type === 'NODE_STARTED' && event.payload.attemptNo === 2),
    view.events.find(event => event.type === 'LOGIC_RESULT_COMMITTED' && event.payload.nodeInstanceId === view.node.id)];
  rows.forEach((row, index) => { row.created_at = times[index]; });
  return () => assertAttemptScope('ack-loss', scenario, view, fixture);
}
const local = fraction => '2026-10-20T11:23:56.' + fraction + '+08:00';

for (const [name, fractions] of [
  ['note after ready', ['064001', '064000', '064002', '064003']],
  ['ready after start', ['064000', '064002', '064001', '064003']],
  ['start after commit', ['064000', '064001', '064003', '064002']],
]) test('same-millisecond microsecond inversion rejects ' + name, () => {
  const times = fractions.map(local);
  assert.equal(new Set(times.map(Date.parse)).size, 1, 'The old millisecond parser collapses these four distinct times');
  assert.throws(withTimes(times), /Terminal note precedes local recovery/);
});

for (const [name, fractions] of [
  ['strict adjacent microseconds', ['064000', '064001', '064002', '064003']],
  ['all equal', ['064001', '064001', '064001', '064001']],
  ['variable PG fractional precision', ['064', '064000', '064001', '06401']],
  ['across millisecond boundary', ['064998', '064999', '065000', '065001']],
]) test('legal PG precision accepts ' + name, () => {
  assert.doesNotThrow(withTimes(fractions.map(local)));
});

test('equal instants in different timezones retain microseconds', () => {
  assert.doesNotThrow(withTimes(['2026-10-20T11:23:56.064001+08:00', '2026-10-20T03:23:56.064001Z',
    '2026-10-19T23:23:56.064001-04:00', '2026-10-20T05:53:56.064001+02:30']));
});

test('ascending instants across timezones retain microseconds', () => {
  assert.doesNotThrow(withTimes(['2026-10-20T11:23:56.064000+08:00', '2026-10-20T03:23:56.064001Z',
    '2026-10-19T23:23:56.064002-04:00', '2026-10-20T05:53:56.064003+02:30']));
});

test('timezone-equivalent same-millisecond inversion is rejected', () => {
  assert.throws(withTimes(['2026-10-20T11:23:56.064002+08:00', '2026-10-20T03:23:56.064001Z',
    '2026-10-19T23:23:56.064003-04:00', '2026-10-20T05:53:56.064004+02:30']), /Terminal note precedes local recovery/);
});

test('whole-second and fractional zero equality is legal', () => {
  assert.doesNotThrow(withTimes(['2026-10-20T11:23:56+08:00', '2026-10-20T03:23:56.0Z',
    '2026-10-19T23:23:56.000000-04:00', '2026-10-20T05:53:56.000+02:30']));
});
