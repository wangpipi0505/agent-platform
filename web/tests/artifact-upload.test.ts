// R08（SCN-005）：共享上传函数只接受顶层artifactId回包或明确OK信封；错误形状一律失败。
import { afterEach, expect, it, vi } from 'vitest';
import { uploadArtifact } from '../shared/artifact-upload';

const file = new File([new Uint8Array([1, 2, 3])], 'input.csv', { type: 'text/csv' });
afterEach(() => vi.unstubAllGlobals());

it('顶层artifactId回包（规范形状）成功返回真实ID', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(
    JSON.stringify({ artifactId: 'art_top', bytes: 3, sha256: 'a'.repeat(64) }),
    { status: 200, headers: { 'Content-Type': 'application/json' } })));
  await expect(uploadArtifact('t', file)).resolves.toBe('art_top');
});

it('明确OK信封兼容取data.artifactId', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(
    JSON.stringify({ code: 'OK', message: '', data: { artifactId: 'art_env' } }),
    { status: 200 })));
  await expect(uploadArtifact('t', file)).resolves.toBe('art_env');
});

it('失败HTTP与错误信封不得当成功', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(
    JSON.stringify({ code: 'ARTIFACT_REJECTED', message: '超过大小上限' }),
    { status: 413 })));
  await expect(uploadArtifact('t', file)).rejects.toThrow('超过大小上限');
  vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 500 })));
  await expect(uploadArtifact('t', file)).rejects.toThrow('文件上传失败 (500)');
});

it('顶层非信封缺artifactId、信封缺ID、非字符串ID都失败', async () => {
  for (const payload of [{ bytes: 3 }, { code: 'OK', data: {} }, { artifactId: 36 }]) {
    const fetcher = vi.fn(async () => new Response(JSON.stringify(payload), { status: 200 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(uploadArtifact('t', file)).rejects.toThrow(/文件上传失败/u);
    expect(fetcher).toHaveBeenCalledTimes(1);
  }
});
