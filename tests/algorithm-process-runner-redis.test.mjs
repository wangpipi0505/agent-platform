import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import * as runner from './smoke-algorithm-processes.mjs';

const source = await fs.readFile(new URL('./smoke-algorithm-processes.mjs', import.meta.url), 'utf8');
const fake = { AGENT_DB_USER: 'fake-db-user', AGENT_DB_PASSWORD: 'fake-db-password', AGENT_MINIO_ENDPOINT: 'http://127.0.0.1:29000',
  AGENT_MINIO_ACCESS_KEY: 'fake-minio-access', AGENT_MINIO_SECRET_KEY: 'fake-minio-secret', AGENT_MINIO_BUCKET: 'fake-bucket',
  AGENT_REDIS_HOST: '127.0.0.1', AGENT_REDIS_PORT: '26379', AGENT_REDIS_PASSWORD: 'fake-redis-secret-unique' };

function actualSystem(env) {
  const start = source.indexOf('const system ='), end = source.indexOf('const java =', start);
  assert(start >= 0 && end > start);
  return new Function('process', source.slice(start, end) + '\nreturn system;')({ env });
}

function actualCommon(local, parent = {}) {
  const start = source.indexOf('const common ='), end = source.indexOf('\n      const skill =', start);
  assert(start >= 0 && end > start);
  const redis = runner.privateRedisSettings ? runner.privateRedisSettings(local) : {};
  return new Function('path', 'local', 'system', 'redis', 'signing', 'master', 'privateDirectory', 'rejectUrl', 'enginePort', 'skillPort', 'fixture', 'scenario',
    source.slice(start, end) + '\nreturn common;')(path, local, actualSystem(parent), redis, 'fake-signing', 'fake-master', 'private-directory',
    'http://127.0.0.1:30001', 30002, 30003, { baseUrl: 'http://127.0.0.1:30004' }, { namespace: 'fake-namespace' });
}

test('actual child configuration explicitly forwards the project Redis endpoint and password', () => {
  const common = actualCommon(fake);
  assert.equal(common.AGENT_REDIS_HOST, fake.AGENT_REDIS_HOST);
  assert.equal(common.AGENT_REDIS_PORT, fake.AGENT_REDIS_PORT);
  assert.equal(common.AGENT_REDIS_PASSWORD, fake.AGENT_REDIS_PASSWORD);
});

test('parent AGENT Spring and Java poison cannot override the explicit project configuration', () => {
  const parent = { SystemRoot: 'C:\\Windows', AGENT_REDIS_HOST: '192.0.2.4', AGENT_REDIS_PORT: '6379', AGENT_REDIS_PASSWORD: 'parent-secret',
    SPRING_DATA_REDIS_PASSWORD: 'spring-secret', JAVA_TOOL_OPTIONS: '-Dpoison=true' };
  const common = actualCommon(fake, parent);
  assert.equal(common.AGENT_REDIS_PASSWORD, fake.AGENT_REDIS_PASSWORD);
  assert.equal(common.AGENT_REDIS_HOST, '127.0.0.1');
  for (const key of ['AGENT_REDIS_PASSWORD', 'SPRING_DATA_REDIS_PASSWORD', 'JAVA_TOOL_OPTIONS']) assert.equal(actualSystem(parent)[key], undefined);
});

test('missing or blank Redis connection configuration is refused before any child creation', () => {
  for (const key of ['AGENT_REDIS_HOST', 'AGENT_REDIS_PORT', 'AGENT_REDIS_PASSWORD']) {
    const local = { ...fake }; delete local[key];
    assert.throws(() => actualCommon(local), /Missing project Redis setting/);
  }
  assert.throws(() => actualCommon({ ...fake, AGENT_REDIS_PASSWORD: '   ' }), /Missing project Redis setting/);
});

test('Redis host and port must be exactly this project endpoint', () => {
  for (const local of [{ ...fake, AGENT_REDIS_HOST: '192.0.2.9' }, { ...fake, AGENT_REDIS_PORT: '6379' }])
    assert.throws(() => actualCommon(local), /Only this project isolated Redis endpoint/);
});

test('actual evidence redaction removes the injected Redis password together with existing secrets', () => {
  const start = source.indexOf('const secrets ='), end = source.indexOf('const errorView =', start);
  assert(start >= 0 && end > start);
  const redact = new Function('local', 'signing', 'master', 'redis', source.slice(start, end) + '\nreturn redact;')(
    fake, 'fake-signing', 'fake-master', runner.privateRedisSettings ? runner.privateRedisSettings(fake) : {});
  const observed = redact('connection failed with ' + fake.AGENT_REDIS_PASSWORD + ' / ' + fake.AGENT_DB_PASSWORD);
  assert.equal(observed, 'connection failed with [REDACTED] / [REDACTED]');
});
