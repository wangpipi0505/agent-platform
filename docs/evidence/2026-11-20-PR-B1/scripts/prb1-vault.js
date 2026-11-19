/* PR-B1 服务端密钥处理（node16，CommonJS；密钥值绝不打印）。
 * 用法：
 *   node prb1-vault.js inspect /keys/<id>.secret            # 只输出 tenantId 与 value 形状
 *   node prb1-vault.js reencrypt /keys/<id>.secret <outdir> # 解密后以 tenantId=demo 重加密新文件
 *   node prb1-vault.js export /keys/<id>.secret <outfile>   # 解密 value 写 600 权限文件（服务器内部用）
 * 主密钥从 env AGENT_MASTER_KEY 读取（调用方从 e1-model 容器注入）。
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const [, , cmd, file, arg] = process.argv;
const master = process.env.AGENT_MASTER_KEY || '';
if (master.length < 32) { console.error('AGENT_MASTER_KEY missing'); process.exit(1); }
const key = crypto.createHash('sha256').update(master, 'utf8').digest();
const record = fs.readFileSync(file);
if (record.length < 29) { console.error('record too short'); process.exit(1); }
const nonce = record.slice(0, 12);
const data = record.slice(12);
const decipher = crypto.createDecipheriv('aes-256-gcm', key, nonce);
decipher.setAuthTag(data.slice(data.length - 16));
let plain;
try { plain = Buffer.concat([decipher.update(data.slice(0, data.length - 16)), decipher.final()]).toString('utf8'); }
catch (e) { console.error('DECRYPT_FAIL ' + e.message); process.exit(2); }
const payload = JSON.parse(plain);
const value = String(payload.value || '');

function shape(v) { return 'len=' + v.length + ' prefix=' + (v.length ? v.charCodeAt(0) < 128 ? 'ascii' : 'nonascii' : 'empty'); }

if (cmd === 'inspect') {
  console.log(JSON.stringify({ tenantId: payload.tenantId, valueShape: shape(value), looksLikeSk: value.startsWith('sk-') }));
} else if (cmd === 'reencrypt') {
  const id = require('crypto').randomUUID();
  const n2 = crypto.randomBytes(12);
  const c2 = crypto.createCipheriv('aes-256-gcm', key, n2);
  const body = c2.update(Buffer.from(JSON.stringify({ tenantId: 'demo', value }), 'utf8'));
  c2.final();
  const enc = Buffer.concat([body, c2.getAuthTag()]); // Java GCM: ciphertext||tag(16)
  const out = path.join(arg, id + '.secret');
  fs.writeFileSync(out, Buffer.concat([n2, enc]), { mode: 0o600 });
  console.log(out.replace(/^.*\/secrets-e1\//, 'vault-file:secrets-e1/') + ' tenantId=demo written');
} else if (cmd === 'export') {
  fs.writeFileSync(arg, value, { mode: 0o600 });
  console.log('exported to ' + arg + ' (mode 600, value not printed)');
} else {
  console.error('unknown cmd'); process.exit(1);
}
