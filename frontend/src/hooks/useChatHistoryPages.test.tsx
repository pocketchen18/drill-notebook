import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { HISTORY_PAGE_SIZE, MAX_RESIDENT_PAGES, useChatHistoryPages } from './useChatHistoryPages';
import type { ChatHistoryMessage, ChatHistoryPage } from './useChatHistoryPages';

/**
 * 历史分页（执行方案 §7.4、§12）：每页 50、message ID 游标往前、驻留 500 封顶、
 * 只重取最新一页而不是一串，切会话与卸载都不发 DELETE。
 */

const { apiGet, apiDel } = vi.hoisted(() => ({ apiGet: vi.fn(), apiDel: vi.fn() }));

vi.mock('../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/api')>();
  return { ...actual, get: (...args: unknown[]) => apiGet(...args), del: (...args: unknown[]) => apiDel(...args) };
});

function row(id: number, over: Partial<ChatHistoryMessage> = {}): ChatHistoryMessage {
  return {
    id,
    role: id % 2 === 0 ? 'assistant' : 'user',
    content: `M${id}`,
    requestId: id % 2 === 0 ? `v7-${id}` : null,
    readable: true,
    ...over
  };
}

function page(messages: ChatHistoryMessage[], hasMore: boolean): ChatHistoryPage {
  return { sessionId: 7, messages, historyRevision: messages.length, hasMore, limit: HISTORY_PAGE_SIZE };
}

/** 页内按 message ID 升序（服务端就是这个顺序），页首才是下一页的游标。 */
const ascending = (first: number) =>
  Array.from({ length: HISTORY_PAGE_SIZE }, (_, index) => row(first + index));

/** client 必须跨渲染稳定：每渲染新建会把缓存丢掉，请求数就测不准了。 */
function harness(sessionId: number | undefined) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) =>
    <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const view = renderHook(() => useChatHistoryPages(sessionId), { wrapper });
  return { ...view, client };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  // resetAllMocks 而不是 clearAllMocks：once 队列与 mockImplementation 都会跨用例残留，
  // 上一格的 50 条实现漏进下一格就会让"两条"断言看到三条消息
  vi.resetAllMocks();
  apiGet.mockResolvedValue(page([row(20), row(21)], false));
  apiDel.mockResolvedValue(undefined);
});

describe('useChatHistoryPages', () => {
  it('第一页按 50 条请求 /history，旧的全量 /messages 不再被调用', async () => {
    const { result } = harness(7);
    await waitFor(() => expect(result.current.messages).toHaveLength(2));
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(apiGet.mock.calls[0][0]).toBe('/api/ai/sessions/7/history?limit=50');
    expect(apiGet.mock.calls.some((call) => String(call[0]).includes('/messages'))).toBe(false);
  });

  it('没选中会话时一个请求都不发', async () => {
    const { result } = harness(undefined);
    await act(settle);
    expect(apiGet).not.toHaveBeenCalled();
    expect(result.current.messages).toEqual([]);
  });

  it('用页首 message ID 做游标往前翻，更早的页排在前面', async () => {
    apiGet
      .mockResolvedValueOnce(page([row(101), row(102), row(103), row(104)], true))
      .mockResolvedValueOnce(page([row(97), row(98), row(99), row(100)], true));
    const { result } = harness(7);
    await waitFor(() => expect(result.current.messages).toHaveLength(4));

    act(() => result.current.loadOlder());
    await waitFor(() => expect(result.current.messages).toHaveLength(8));
    expect(apiGet).toHaveBeenLastCalledWith('/api/ai/sessions/7/history?limit=50&beforeMessageId=101');
    expect(result.current.messages.map((item) => item.id)).toEqual([97, 98, 99, 100, 101, 102, 103, 104]);
  });

  it('hasMore=false 时不再多发一次请求', async () => {
    const { result } = harness(7);
    await waitFor(() => expect(result.current.hasOlder).toBe(false));
    act(() => result.current.loadOlder());
    await act(settle);
    expect(apiGet).toHaveBeenCalledTimes(1);
  });

  it('驻留到 500 条就封顶，更早的记录不再继续加载（数据一条都没少）', async () => {
    apiGet.mockImplementation((url: string) => {
      const before = Number(new URL(`http://x${url}`).searchParams.get('beforeMessageId') ?? '10000');
      return Promise.resolve(page(ascending(before - HISTORY_PAGE_SIZE), true));
    });
    const { result } = harness(7);
    await waitFor(() => expect(result.current.messages).toHaveLength(HISTORY_PAGE_SIZE));

    for (let index = 1; index < MAX_RESIDENT_PAGES; index += 1) {
      act(() => result.current.loadOlder());
      await waitFor(() => expect(result.current.messages).toHaveLength((index + 1) * HISTORY_PAGE_SIZE));
    }
    expect(result.current.messages).toHaveLength(MAX_RESIDENT_PAGES * HISTORY_PAGE_SIZE);
    expect(result.current.residentCapped).toBe(true);
    expect(result.current.hasOlder).toBe(true);

    const requests = apiGet.mock.calls.length;
    act(() => result.current.loadOlder());
    await act(settle);
    expect(apiGet).toHaveBeenCalledTimes(requests);
  });

  it('重取最新一页只发一次请求，更早的页不动', async () => {
    apiGet
      .mockResolvedValueOnce(page([row(101), row(102)], true))
      .mockResolvedValueOnce(page([row(97), row(98)], false))
      // 最新一页是"最近 50 条"，重取回来仍带着旧尾巴，只是多了刚落地的那条
      .mockResolvedValueOnce(page([row(101), row(102), row(103)], true));
    const { result, client } = harness(7);
    await waitFor(() => expect(result.current.messages).toHaveLength(2));
    act(() => result.current.loadOlder());
    await waitFor(() => expect(result.current.messages).toHaveLength(4));
    const before = apiGet.mock.calls.length;

    await act(async () => { await result.current.refreshLatest(); });
    expect(apiGet).toHaveBeenCalledTimes(before + 1);
    expect(apiGet).toHaveBeenLastCalledWith('/api/ai/sessions/7/history?limit=50');
    // 缓存已经换成新尾巴；订阅者要下一拍才把新数据投影到 hook 返回值
    expect(client.getQueryData<{ pages: ChatHistoryPage[] }>(['ai-session-history', 7])
      ?.pages[0].messages.map((item) => item.id)).toEqual([101, 102, 103]);
    await waitFor(() => expect(result.current.messages.map((item) => item.id))
      .toEqual([97, 98, 101, 102, 103]));
  });

  it('最新一页与旧页重叠时按 message ID 收敛，同一条不显示两遍', async () => {
    apiGet
      .mockResolvedValueOnce(page([row(101), row(102)], true))
      // 旧页里混进了 101（服务端竞态下可能重叠）
      .mockResolvedValueOnce(page([row(97), row(101)], false))
      .mockResolvedValueOnce(page([row(101), row(102), row(103)], true));
    const { result } = harness(7);
    await waitFor(() => expect(result.current.messages).toHaveLength(2));
    act(() => result.current.loadOlder());
    await waitFor(() => expect(result.current.messages).toHaveLength(3));
    expect(result.current.messages.map((item) => item.id)).toEqual([97, 101, 102]);

    await act(async () => { await result.current.refreshLatest(); });
    await waitFor(() => expect(result.current.messages.map((item) => item.id)).toEqual([97, 101, 102, 103]));
    expect(new Set(result.current.messages.map((item) => item.id)).size).toBe(4);
  });

  it('还没加载过时不替查询造第一页', async () => {
    const { result } = harness(undefined);
    await act(async () => { await result.current.refreshLatest(); });
    expect(apiGet).not.toHaveBeenCalled();
  });

  it('卸载与切会话都不发 DELETE，归档不等于删除', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) =>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const first = renderHook(() => useChatHistoryPages(7), { wrapper });
    await waitFor(() => expect(first.result.current.messages).toHaveLength(2));
    first.unmount();
    const second = renderHook(() => useChatHistoryPages(8), { wrapper });
    await waitFor(() => expect(second.result.current.messages).toHaveLength(2));
    expect(apiDel).not.toHaveBeenCalled();
    expect(apiGet.mock.calls.some((call) => String(call[0]).startsWith('/api/ai/sessions/8/history'))).toBe(true);
    // 卸载只释放界面，不删数据：缓存里那两条仍在，重开该会话不必等第二次请求
    expect(client.getQueryData(['ai-session-history', 7])).toBeDefined();
    second.unmount();
  });

  it('页内消息带着轮次身份与状态，界面才敢挂徽标', async () => {
    apiGet.mockResolvedValue(page([
      row(30, { requestId: 'v7-30', turnStatus: 'COMPLETED' }),
      row(31, { requestId: 'v7-30', turnStatus: 'COMPLETED' }),
      row(32, { requestId: null, legacy: true, turnStatus: null })
    ], false));
    const { result } = harness(7);
    await waitFor(() => expect(result.current.messages).toHaveLength(3));
    expect(result.current.messages[1].requestId).toBe('v7-30');
    expect(result.current.messages[2].requestId).toBeNull();
    expect(result.current.messages[2].legacy).toBe(true);
  });
});
