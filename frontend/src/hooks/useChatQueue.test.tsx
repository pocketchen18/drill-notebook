import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useChatQueue } from './useChatQueue';
import { ApiError } from '../lib/api';
import { capturePageSnapshot } from '../lib/pageSnapshot';
const { apiPost, apiGet } = vi.hoisted(() => ({ apiPost: vi.fn(), apiGet: vi.fn() }));
vi.mock('../lib/api', async (original) => ({ ...(await original<typeof import('../lib/api')>()), post: apiPost, get: apiGet }));
beforeEach(() => { vi.clearAllMocks(); apiGet.mockResolvedValue({ items: [], steering: [], paused: false }); });
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderHook(({ id }) => useChatQueue(id), { initialProps: { id: 1 },
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> });
}
describe('人工队列提交身份', () => {
  it.each(['queue', 'steer'] as const)('页面快照随 %s 冻结，重试仍携带原内容', async (mode) => {
    apiPost.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue({ status: 'RECEIVED' });
    const hook = setup();
    const pageSnapshot = capturePageSnapshot({ kind: 'note', title: '第一页', notePageId: 1, markdown: '发送时正文', selectedIds: [1] });
    await act(async () => { await expect(hook.result.current.submit({ sessionId: 1, userText: '这页', pageSnapshot }, mode, 'running')).rejects.toThrow(); });
    pageSnapshot.markdown = '后来的页面'; pageSnapshot.selectedIds!.push(2);
    await act(async () => { await hook.result.current.retrySubmission(); });
    expect(apiPost.mock.calls[1][1].pageSnapshot).toMatchObject({ markdown: '发送时正文', pageId: 1, selectedIds: [1] });
    expect(apiPost.mock.calls[0][1]).toEqual(apiPost.mock.calls[1][1]);
  });
  it('响应丢失后阻止新内容，核对复用原请求和插话目标', async () => {
    apiPost.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue({ status: 'RECEIVED' });
    const hook = setup();
    await act(async () => { await expect(hook.result.current.submit({ sessionId: 1, userText: '补充' }, 'steer', 'running')).rejects.toThrow('offline'); });
    expect(hook.result.current.unconfirmed).toBe(true);
    await act(async () => { await expect(hook.result.current.submit({ sessionId: 1, userText: '新草稿' }, 'queue')).rejects.toThrow('核对'); });
    expect(apiPost).toHaveBeenCalledTimes(1);
    await act(async () => { await hook.result.current.retrySubmission(); });
    expect(apiPost.mock.calls[1]).toEqual(apiPost.mock.calls[0]);
    expect(apiPost.mock.calls[1][1]).toMatchObject({ mode: 'steer', targetRequestId: 'running', input: { userText: '补充' } });
    expect(hook.result.current.unconfirmed).toBe(false);
  });
  it('结果未知按会话隔离，明确拒绝允许修改后重新提交', async () => {
    apiPost.mockRejectedValueOnce(new TypeError('offline')).mockRejectedValueOnce(new ApiError('INPUT_EMPTY', '空消息', 409)).mockResolvedValue({});
    const hook = setup();
    await act(async () => { await expect(hook.result.current.submit({ sessionId: 1, userText: 'A' }, 'queue')).rejects.toThrow(); });
    hook.rerender({ id: 2 }); expect(hook.result.current.unconfirmed).toBe(false);
    await act(async () => { await expect(hook.result.current.submit({ sessionId: 2, userText: 'B' }, 'queue')).rejects.toThrow(); });
    expect(hook.result.current.unconfirmed).toBe(false);
    await act(async () => { await hook.result.current.submit({ sessionId: 2, userText: '修改后' }, 'queue'); });
    expect(apiPost.mock.calls[2][1].requestId).not.toBe(apiPost.mock.calls[1][1].requestId);
    hook.rerender({ id: 1 }); expect(hook.result.current.unconfirmed).toBe(true);
  });
});
