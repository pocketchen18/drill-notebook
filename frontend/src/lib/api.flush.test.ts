import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushRequest } from './api';

afterEach(() => vi.unstubAllGlobals());

describe('keepalive 保存结果', () => {
  it('成功确认后才完成，保留 keepalive 传输', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 204 });
    vi.stubGlobal('fetch', fetch);
    await expect(flushRequest('/api/note-pages/11', { content: '测试草稿' })).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/note-pages/11'), expect.objectContaining({
      method: 'PUT', keepalive: true, body: JSON.stringify({ content: '测试草稿' })
    }));
  });

  it('HTTP 保存失败不能被当作成功确认', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(flushRequest('/api/note-pages/11', {})).rejects.toMatchObject({ code: 'save_failed', status: 500 });
  });

  it('网络异常保留失败状态，交由草稿保护处理', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    await expect(flushRequest('/api/note-pages/11', {})).rejects.toThrow('offline');
  });
});
