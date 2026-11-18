/** M5-3 E2E 本地模型桩（OpenAI 兼容最小面）：GET /v1/models + POST /v1/chat/completions。
 * 用途：承接 model-gateway 供应商连通性探测（/v1/models 清单 + 一次单 token 出话）与
 * engine 经 model-gateway 的真实会话/规划出话。确定性回复、零外部依赖；仅监听 127.0.0.1。
 * stdin 关闭（父进程退出）或 SIGTERM 时自退，不留后台进程。
 */
import http from 'node:http';

const port = Number(process.argv[2] || process.env.PORT || 18485);
const server = http.createServer((req, res) => {
  const finish = (status, payload) => {
    const body = JSON.stringify(payload);
    res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
    res.end(body);
  };
  if (req.method === 'GET' && req.url.endsWith('/models')) {
    return finish(200, { object: 'list', data: [{ id: 'demo-model', object: 'model' }] });
  }
  if (req.method === 'POST' && req.url.endsWith('/chat/completions')) {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      let content = '桩模型回复：任务输入已受理（确定性本地桩出话）。';
      try {
        const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        const user = (parsed.messages || []).filter((m) => m.role === 'user').pop();
        if (user && user.content) content = '桩模型回复：' + String(user.content).slice(0, 200);
      } catch { /* 保底确定性文本 */ }
      finish(200, {
        id: 'chatcmpl-e2e-stub', object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: 'demo-model',
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 16, completion_tokens: 8, total_tokens: 24 },
      });
    });
    return;
  }
  finish(404, { error: { message: 'stub: no route ' + req.url } });
});
server.listen(port, '127.0.0.1', () => process.stdout.write(JSON.stringify({ kind: 'model-stub-ready', port }) + '\n'));
process.stdin.on('close', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
process.on('SIGINT', () => process.exit(0));
