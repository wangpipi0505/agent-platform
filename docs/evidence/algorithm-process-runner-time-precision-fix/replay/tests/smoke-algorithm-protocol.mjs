import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline';

// Test-only finite API fixture for the root's later isolated engine/skill process proof.
// This file never launches, stops, retries or adopts any engine/skill process.
export async function startAlgorithmFixture({ namespace, expectedBearer, port = 0, mode = 'complete', stdout = '{"value":"ok"}', maxMs = 300000 }) {
  if (!/^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?$/.test(namespace ?? '') || namespace.length > 63) throw Error('Explicit test namespace required');
  if (!Number.isInteger(port) || port < 0 || port > 65535 || !Number.isInteger(maxMs) || maxMs < 1000 || maxMs > 300000) throw Error('Finite fixture bounds required');
  const modes = new Set(['complete', 'running', 'failed', 'ack-loss']);
  if (!modes.has(mode) || Buffer.byteLength(stdout) > 8192) throw Error('Unsupported finite fixture scenario');
  // Root supplies a fake per-fixture test credential. Never discover a production credential or
  // silently treat unauthenticated reception as evidence of the SecretVault -> SDK header path.
  if (typeof expectedBearer !== 'string' || !/^[A-Za-z0-9._~-]{8,256}$/.test(expectedBearer)) throw Error('Explicit fake fixture bearer required');
  let job, closed = false;
  let resolveClosed;
  const done = new Promise(resolve => { resolveClosed = resolve; });
  const counters = { posts: 0, jobGets: 0, podLists: 0, logGets: 0, denied: 0, authRejected: 0 };
  const routes = [];
  const jobPath = `/apis/batch/v1/namespaces/${namespace}/jobs`;
  const podPath = `/api/v1/namespaces/${namespace}/pods`;
  const observed = () => ({ ...job, status: mode === 'running' ? { active: 1 } : {
    conditions: [{ type: mode === 'failed' ? 'Failed' : 'Complete', status: 'True' }],
    succeeded: mode === 'failed' ? 0 : 1,
  } });
  const reply = (response, code, value) => {
    const body = typeof value === 'string' ? value : JSON.stringify(value);
    if (Buffer.byteLength(body) > 16384) throw Error('Finite response limit');
    response.writeHead(code, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
    response.end(body);
  };
  const server = http.createServer(async (request, response) => {
    try {
      if (request.headers.authorization !== `Bearer ${expectedBearer}`) {
        counters.authRejected++; reply(response, 401, {}); return;
      }
      const url = new URL(request.url, 'http://127.0.0.1');
      if (routes.length >= 200) return reply(response, 429, {});
      routes.push(`${request.method} ${url.pathname}`);
      if (request.method === 'POST' && url.pathname === jobPath) {
        counters.posts++;
        let size = 0; const chunks = [];
        for await (const chunk of request) {
          size += chunk.length;
          if (size > 65536) { request.destroy(); return; }
          chunks.push(chunk);
        }
        const incoming = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (job) return reply(response, 409, {});
        if (!/^alg-[a-f0-9]{40}$/.test(incoming?.metadata?.name ?? '') || incoming?.metadata?.namespace !== namespace
            || !/^[a-f0-9]{64}$/.test(incoming?.metadata?.annotations?.['agent-platform.cn/identity-sha256'] ?? '')) return reply(response, 400, {});
        job = { ...incoming, metadata: { ...incoming.metadata, uid: randomUUID() } };
        if (mode === 'ack-loss') { response.destroy(); return; }
        return reply(response, 201, observed());
      }
      if (request.method === 'GET' && url.pathname.startsWith(`${jobPath}/`) && /^alg-[a-f0-9]{40}$/.test(url.pathname.slice(jobPath.length + 1))) {
        counters.jobGets++;
        return !job || url.pathname !== `${jobPath}/${job.metadata.name}` ? reply(response, 404, {}) : reply(response, 200, observed());
      }
      if (request.method === 'GET' && url.pathname === podPath) {
        counters.podLists++;
        if (!job || url.searchParams.get('labelSelector') !== `job-name=${job.metadata.name}` || url.searchParams.get('watch') !== 'false' || url.searchParams.get('limit') !== '2') return reply(response, 400, {});
        return reply(response, 200, { apiVersion: 'v1', kind: 'PodList', metadata: {}, items: [{
          metadata: { name: 'algorithm-proof-pod', namespace, uid: 'algorithm-proof-pod-uid', ownerReferences: [{ apiVersion: 'batch/v1', kind: 'Job', name: job.metadata.name, uid: job.metadata.uid, controller: true }] },
          status: { containerStatuses: [{ name: 'algorithm', image: job.spec.template.spec.containers[0].image, imageID: 'sha256:fixture', ready: false, restartCount: 0, state: { terminated: { exitCode: 0 } } }] },
        }] });
      }
      if (request.method === 'GET' && url.pathname === `${podPath}/algorithm-proof-pod/log`) {
        counters.logGets++;
        if (!job || url.searchParams.get('container') !== 'algorithm' || url.searchParams.get('follow') !== 'false'
            || url.searchParams.has('limitBytes') || url.searchParams.has('tailLines')) return reply(response, 400, {});
        return reply(response, 200, stdout);
      }
      counters.denied++; reply(response, 400, {});
    } catch { if (!response.headersSent) reply(response, 400, {}); else response.destroy(); }
  });
  server.requestTimeout = 1500; server.headersTimeout = 1500; server.maxRequestsPerSocket = 32;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  const snapshot = () => ({ ...counters, mode, jobName: job?.metadata.name ?? null, jobUid: job?.metadata.uid ?? null, routes: [...routes],
    evidenceLevel: 'finite-api-reception-only', clusterOrContainerExecution: false, logicalEngineCommitVerified: false });
  const close = async () => {
    if (closed) return; closed = true; clearTimeout(timer); server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    resolveClosed();
  };
  const timer = setTimeout(() => { void close(); }, maxMs);
  return { baseUrl: `http://127.0.0.1:${server.address().port}`, pid: process.pid, snapshot, close, done,
    setMode(next) { if (!modes.has(next)) throw Error('Unsupported fixture mode'); mode = next; },
    assertSinglePost() { if (counters.posts !== 1 || counters.denied !== 0 || counters.authRejected !== 0) throw Error('Expected exactly one authenticated POST reception and no denied routes'); return snapshot(); },
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (!process.argv.includes('--fixture')) {
    process.stdout.write('Test fixture only. Use --fixture with ALGORITHM_FIXTURE_NAMESPACE, fake ALGORITHM_FIXTURE_BEARER and optional ALGORITHM_FIXTURE_PORT/MODE. Root owns all engine/skill processes.\n');
  } else {
    const fixture = await startAlgorithmFixture({ namespace: process.env.ALGORITHM_FIXTURE_NAMESPACE,
      expectedBearer: process.env.ALGORITHM_FIXTURE_BEARER,
      port: Number(process.env.ALGORITHM_FIXTURE_PORT ?? 0), mode: process.env.ALGORITHM_FIXTURE_MODE ?? 'complete' });
    process.stdout.write(`${JSON.stringify({ kind: 'fixture-ready', pid: process.pid, baseUrl: fixture.baseUrl, ownership: 'this-runner-fixture-only' })}\n`);
    const input = createInterface({ input: process.stdin });
    fixture.done.then(() => input.close());
    input.on('line', async line => {
      try {
        if (line.length > 256) throw Error('Finite control line required');
        const command = JSON.parse(line);
        if (command.mode) fixture.setMode(command.mode);
        if (command.assertSinglePost) fixture.assertSinglePost();
        process.stdout.write(`${JSON.stringify({ kind: 'fixture-counts', ...fixture.snapshot() })}\n`);
        if (command.finish) { await fixture.close(); input.close(); }
      } catch { process.exitCode = 1; await fixture.close(); input.close(); }
    });
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await fixture.close(); input.close(); });
  }
}
