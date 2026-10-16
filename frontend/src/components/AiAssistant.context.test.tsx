import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AiAssistant } from './AiAssistant';
import { useUiStore, type AiPageContext } from '../stores/uiStore';
import { isUuidV7 } from '../lib/uuidV7';

/**
 * P9c-4 在界面上的接线（执行方案 §12、contracts §4.5、验收 C08/H12）：
 * 徽标计数取自本轮 done 快照，点开才读一次已保存账本；这条路径上绝不发聊天请求，
 * 消息被 refetch 升级时身份快照要跟着留下，历史加载的消息在本片还没有 requestId 所以不给徽标。
 */

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));

/** ApiError 必须留真身：徽标与检查器靠 `instanceof` 分辨"需要解锁"和"读不出来"。 */
vi.mock('../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/api')>();
  return { ...actual, get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args) };
});

const TURN_ID = '01981234-5678-7000-8000-000000000001';

/** 徽标与检查器都只能挂在"本轮自己的身份"上：请求发出去的 id 与服务端回显的是同一个。 */
const chatRequestId = (): string => {
  const call = apiPost.mock.calls.find((item) => item[0] === '/api/ai/chat');
  return String((call?.[1] as { requestId?: string } | undefined)?.requestId ?? '');
};

const contextAudit = {
  schemaVersion: 1,
  planId: 'plan-7',
  dispatchState: 'sent',
  estimatedInputTokens: 1200,
  inputBudget: 32000,
  estimatorVersion: 'est-2',
  selected: [
    { sourceKey: 'mem:a@2', kind: 'MEMORY', id: 'mem-a', version: '2', sourceTurnId: null },
    { sourceKey: 'mem:b@1', kind: 'MEMORY', id: 'mem-b', version: '1', sourceTurnId: null }
  ],
  regions: [{ kind: 'MEMORY', estimatedTokens: 120, itemCount: 2 }],
  omissions: [{ reason: 'LOCKED', kind: 'IMAGE', count: 1 }],
  scan: { firstTurnId: 3, lastTurnId: 8, scannedTurns: 6, hasOlderUnscanned: true },
  summary: null,
  resumeState: null,
  auditTruncated: false
};

const setContext = (context: AiPageContext): void => {
  useUiStore.setState({ aiOpen: true, pageContext: context });
};

const renderAssistant = () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AiAssistant />
      </MemoryRouter>
    </QueryClientProvider>
  );
};

/** 走确定的非流式通道：一次 POST /chat 就拿到带四块字段的统一结果。 */
const sendOnce = async (): Promise<void> => {
  await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
  fireEvent.change(screen.getByPlaceholderText(/提问/), { target: { value: '你好' } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
  await waitFor(() => expect(screen.getByText('ANSWER')).toBeTruthy());
};

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  setContext({ kind: 'none', title: '无页面上下文', markdown: '' });
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/ai/config') {
      return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm', streaming: false } });
    }
    if (url === '/api/notebooks') return Promise.resolve([{ id: 1, title: 'NB' }]);
    if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
    if (url.startsWith('/api/ai/sessions/') && url.endsWith('/messages')) return Promise.resolve([]);
    if (url.endsWith('/context')) {
      return Promise.resolve({ requestId: TURN_ID, sessionId: 10, status: 'COMPLETED', available: true, planId: 'plan-7', dispatchState: 'sent', audit: contextAudit });
    }
    return Promise.resolve({});
  });
  apiPost.mockImplementation((url: string) => {
    if (url === '/api/ai/chat') {
      return Promise.resolve({
        protocolVersion: 2,
        // 故意与本轮发出去的 id 不同：徽标与检查器必须挂在本地那一个 UUIDv7 上，不是回显里的字符串
        requestId: TURN_ID,
        sessionId: 10,
        status: 'COMPLETED',
        stateDurable: true,
        persistenceStatus: 'saved',
        replayed: false,
        reply: 'ANSWER',
        citations: [],
        memoryContext: { provided: [{ memoryId: 'mem-a', version: 2 }, { memoryId: 'mem-b', version: 1 }], estimatedTokens: 120, vaultRevision: 4 },
        contextUsage: { estimated: true, inputBudget: 32000, estimatedInputTokens: 1200, notices: [] },
        contextAudit: { schemaVersion: 1, planId: 'plan-7', dispatchState: 'sent', available: true },
        usage: { source: 'upstream', inputTokens: 1500, outputTokens: 80, cacheReadTokens: 0, cacheWriteTokens: 0 }
      });
    }
    if (url.endsWith('/context/preview')) {
      return Promise.resolve([
        { sourceKey: 'mem:a@2', kind: 'MEMORY', id: 'mem-a', version: '2', available: true, text: '常驻杭州', truncated: false }
      ]);
    }
    return Promise.resolve({});
  });
});

describe('AiAssistant 记忆徽标与上下文检查器', () => {
  it('计数来自本轮 done，徽标出现前没有额外请求', async () => {
    renderAssistant();
    const before = apiGet.mock.calls.length + apiPost.mock.calls.length;
    await sendOnce();
    expect(screen.getByRole('button', { name: '本轮提供 2 条记忆' })).toBeTruthy();
    // 除了配置/会话/消息与那一次聊天，没有为徽标多发请求
    expect(apiGet.mock.calls.filter((call) => String(call[0]).includes('/context'))).toHaveLength(0);
    expect(apiPost.mock.calls.filter((call) => String(call[0]).includes('/context'))).toHaveLength(0);
    expect(apiGet.mock.calls.length + apiPost.mock.calls.length).toBeGreaterThan(before);
  });

  it('点开徽标只读一次账本，绝不重发聊天', async () => {
    renderAssistant();
    await sendOnce();
    fireEvent.click(screen.getByRole('button', { name: '本轮提供 2 条记忆' }));
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith(`/api/ai/turns/${chatRequestId()}/context`));
    // 挂的是本轮自己发出去的 UUIDv7，不是服务端回显里另一个 id，也不是上一轮的
    expect(isUuidV7(chatRequestId())).toBe(true);
    expect(apiGet.mock.calls.some((call) => String(call[0]) === `/api/ai/turns/${TURN_ID}/context`)).toBe(false);
    expect(apiGet.mock.calls.filter((call) => String(call[0]).endsWith('/context'))).toHaveLength(1);
    expect(apiPost.mock.calls.filter((call) => String(call[0]) === '/api/ai/chat')).toHaveLength(1);
    expect(screen.getByText('mem-a')).toBeTruthy();
    expect(screen.getByText('mem-b')).toBeTruthy();
    expect(screen.queryByText('常驻杭州')).toBeNull();
  });

  it('对照来源要点一次才取一条，正文随即显示', async () => {
    renderAssistant();
    await sendOnce();
    fireEvent.click(screen.getByRole('button', { name: '本轮提供 2 条记忆' }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(2));
    expect(apiPost.mock.calls.filter((call) => String(call[0]).endsWith('/context/preview'))).toHaveLength(0);
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    await waitFor(() => expect(screen.getByText('常驻杭州')).toBeTruthy());
    const preview = apiPost.mock.calls.find((call) => String(call[0]).endsWith('/context/preview'));
    expect(preview?.[0]).toBe(`/api/ai/turns/${chatRequestId()}/context/preview`);
    expect(preview?.[1]).toEqual({ sourceKeys: ['mem:a@2'] });
  });

  it('消息被 refetch 升级后徽标还在：本轮身份不能因为刷新就丢', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/config') {
        return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm', streaming: false } });
      }
      if (url === '/api/notebooks') return Promise.resolve([{ id: 1, title: 'NB' }]);
      if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
      if (url.startsWith('/api/ai/sessions/') && url.endsWith('/messages')) {
        return Promise.resolve([{ id: 55, role: 'assistant', content: 'ANSWER', reasoning: null, createdAt: '2026-10-16T10:00:00Z' }]);
      }
      if (url.endsWith('/context')) {
        return Promise.resolve({ requestId: TURN_ID, sessionId: 10, status: 'COMPLETED', available: true, audit: contextAudit });
      }
      return Promise.resolve({});
    });
    renderAssistant();
    await sendOnce();
    await waitFor(() => expect(screen.getByRole('button', { name: '本轮提供 2 条记忆' })).toBeTruthy());
    // 服务端那份同正文消息升级时保留了本地 turn：徽标不该跟着消失
    expect(screen.getAllByRole('button', { name: '本轮提供 2 条记忆' })).toHaveLength(1);
  });

  it('历史加载的消息没有轮次身份，本片不硬造徽标', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/config') {
        return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm', streaming: false } });
      }
      if (url === '/api/notebooks') return Promise.resolve([{ id: 1, title: 'NB' }]);
      if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
      if (url.startsWith('/api/ai/sessions/') && url.endsWith('/messages')) {
        return Promise.resolve([{ id: 51, role: 'assistant', content: 'OLD ANSWER', reasoning: null, createdAt: '2026-10-15T10:00:00Z' }]);
      }
      return Promise.resolve({});
    });
    renderAssistant();
    await waitFor(() => expect(screen.getByText('OLD ANSWER')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /本轮提供/ })).toBeNull();
    expect(screen.queryByRole('button', { name: '本轮上下文' })).toBeNull();
  });

  it('动作区的「本轮上下文」打开检查器，仍然只读账本', async () => {
    renderAssistant();
    await sendOnce();
    fireEvent.click(screen.getByRole('button', { name: '本轮上下文' }));
    await waitFor(() => expect(screen.getByText('计划与发送态')).toBeTruthy());
    expect(screen.getByText('已发送给模型')).toBeTruthy();
    expect(apiPost.mock.calls.filter((call) => String(call[0]) === '/api/ai/chat')).toHaveLength(1);
  });

  it('检查器里的省略计数与未扫描标记照账本说话', async () => {
    renderAssistant();
    await sendOnce();
    fireEvent.click(screen.getByRole('button', { name: '本轮上下文' }));
    await waitFor(() => expect(screen.getByText('更早历史未扫描')).toBeTruthy());
    expect(screen.getByText('未解锁，未提供')).toBeTruthy();
    expect(screen.getByText('1 项')).toBeTruthy();
    expect(screen.getByText('没有可用的续接摘要，较早历史按原文进窗口。')).toBeTruthy();
  });
});
