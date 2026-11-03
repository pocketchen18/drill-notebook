import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AiAssistant } from './AiAssistant';
import { useUiStore, type AiPageContext } from '../stores/uiStore';
import type { ChatHistoryMessage } from '../hooks/useChatHistoryPages';

/**
 * P9c-5 抽屉接入（执行方案 §7.4、§12，contracts §3 尾段）：
 * 真实请求必须是 50 条游标的 /history 而不是全量 /messages；驻留 500 封顶；
 * 主密码只走请求体；锁定、失败终态、读不出、空列表四种界面说法各不同；卸载与切会话都不删数据。
 */

const { apiGet, apiPost, apiDel } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn(), apiDel: vi.fn() }));

vi.mock('../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/api')>();
  return {
    ...actual,
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    del: (...args: unknown[]) => apiDel(...args)
  };
});

const row = (over: Partial<ChatHistoryMessage> & Pick<ChatHistoryMessage, 'id' | 'role' | 'content'>): Record<string, unknown> => ({
  readable: true, turnStatus: 'COMPLETED', createdAt: '2026-10-15T10:00:00Z', ...over
});

const historyPage = (messages: Array<Record<string, unknown>>, hasMore = false, revision = 3) => ({
  sessionId: 10, messages, historyRevision: revision, hasMore, limit: 50
});

const pageOf = (first: number, count: number): Array<Record<string, unknown>> =>
  Array.from({ length: count }, (_, index) => row({ id: first + index, role: index % 2 === 0 ? 'user' : 'assistant', content: `M${first + index}` }));

const setContext = (messages: Array<Record<string, unknown>>, hasMore = false): void => {
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/ai/config') {
      return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm', streaming: false } });
    }
    if (url === '/api/notebooks') return Promise.resolve([{ id: 1, title: 'NB' }]);
    if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }, { id: 11, title: 'S2' }]);
    if (url.startsWith('/api/ai/sessions/10/history')) {
      const before = Number(new URL(`http://x${url}`).searchParams.get('beforeMessageId') ?? Number.NaN);
      if (Number.isNaN(before)) return Promise.resolve(historyPage(messages, hasMore));
      return Promise.resolve(historyPage(pageOf(Math.max(before - 50, 1), 50), true));
    }
    if (url.startsWith('/api/ai/sessions/11/history')) return Promise.resolve(historyPage([], false, 1));
    return Promise.resolve({});
  });
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

const context: AiPageContext = { kind: 'none', title: '无页面上下文', markdown: '' };

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
  useUiStore.setState({ aiOpen: true, pageContext: context });
  setContext([row({ id: 1, role: 'user', content: '第一问' }), row({ id: 2, role: 'assistant', content: '答一' })]);
  apiPost.mockResolvedValue({});
  apiDel.mockResolvedValue(undefined);
});

describe('AiAssistant 历史分页', () => {
  it('真实请求是 50 条游标的 /history，旧的 /messages 不再被调用', async () => {
    renderAssistant();
    await screen.findByText('第一问');
    expect(apiGet).toHaveBeenCalledWith('/api/ai/sessions/10/history?limit=50');
    expect(apiGet.mock.calls.some((call) => String(call[0]).endsWith('/messages'))).toBe(false);
    expect(apiGet.mock.calls.some((call) => String(call[0]).includes('masterPassword'))).toBe(false);
  });

  it('滚到顶部按最早一条 message ID 取上一页，取回的内容排在前面', async () => {
    setContext(pageOf(101, 4), true);
    renderAssistant();
    await screen.findByText('M101');
    const list = document.querySelector('.ai-drawer-chat') as HTMLDivElement;

    await act(async () => { fireEvent.scroll(list); });
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/ai/sessions/10/history?limit=50&beforeMessageId=101'));
    await screen.findByText('M51');
    const nodes = document.querySelectorAll('.chat-message');
    expect(nodes[0].textContent).toContain('M51');
    expect(nodes[nodes.length - 1].textContent).toContain('M104');
  });

  it('「加载更早的消息」按钮给键盘用户，点一次只取一页', async () => {
    setContext(pageOf(101, 2), true);
    renderAssistant();
    await screen.findByRole('button', { name: '加载更早的消息' });
    const before = apiGet.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '加载更早的消息' }));
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(before + 1));
    expect(apiGet).toHaveBeenLastCalledWith('/api/ai/sessions/10/history?limit=50&beforeMessageId=101');
  });

  // 500 条气泡要真的渲染出来才能证明封顶生效，单这一条就约 4 秒，故显式放宽超时。
  it('驻留到 500 条就停：给一行如实说明，不再多发请求', async () => {
    // 每页 50、共 10 页到顶
    setContext(pageOf(451, 50), true);
    renderAssistant();
    await screen.findByText('M451');

    for (let index = 1; index < 10; index += 1) {
      fireEvent.click(screen.getByRole('button', { name: '加载更早的消息' }));
      await waitFor(() => expect(document.querySelectorAll('.chat-message').length).toBe((index + 1) * 50), { timeout: 3000 });
    }
    expect(screen.getByText('已驻留最近 500 条，更早的记录仍在本地库里。')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '加载更早的消息' })).toBeNull();
    const before = apiGet.mock.calls.length;
    fireEvent.scroll(document.querySelector('.ai-drawer-chat') as HTMLDivElement);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(apiGet).toHaveBeenCalledTimes(before);
  }, 20_000);

  it('读不出的历史只留占位，不再要求密码', async () => {
    setContext([row({ id: 1, role: 'user', content: '[加密消息：需要主密码]', readable: false })]);
    renderAssistant();
    await screen.findByText('[加密消息：需要主密码]');
    expect(screen.queryByText('当前会话还没有消息')).toBeNull();
    expect(screen.queryByLabelText('解锁本会话历史的主密码')).toBeNull();
    expect(screen.queryByRole('button', { name: '解锁本会话' })).toBeNull();
  });

  it('可读的历史不摆解锁入口，也不摆「加载更早」', async () => {
    renderAssistant();
    await screen.findByText('第一问');
    expect(screen.queryByLabelText('解锁本会话历史的主密码')).toBeNull();
    expect(screen.queryByRole('button', { name: '加载更早的消息' })).toBeNull();
  });

  it('失败与清理过的轮次带着自己的终态出现，不伪装成正常回答', async () => {
    setContext([
      row({ id: 1, role: 'user', content: '失败的一问', turnStatus: 'FAILED', requestId: 'v7-f' }),
      row({ id: 2, role: 'assistant', content: '', turnStatus: 'FAILED', requestId: 'v7-f' }),
      row({ id: 3, role: 'user', content: '清理过的一问', contentDeleted: true, turnStatus: 'COMPLETED' })
    ]);
    renderAssistant();
    await screen.findByText('失败的一问');
    // 失败轮次的一对消息都带同一终态：不能只标一边，另一边看起来像正常回答
    expect(screen.getAllByText('这一轮失败，显示的内容可能不完整')).toHaveLength(2);
    expect(screen.getByText('这一轮的原文已按你的要求清理，只剩时间与状态')).toBeTruthy();
    expect(screen.queryByText('当前会话还没有消息')).toBeNull();
  });

  it('读取失败时给错误行与重新读取，只重发分页请求', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/config') {
        return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm', streaming: false } });
      }
      if (url === '/api/notebooks') return Promise.resolve([{ id: 1, title: 'NB' }]);
      if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
      if (url.startsWith('/api/ai/sessions/10/history')) return Promise.reject(new Error('库被占用'));
      return Promise.resolve({});
    });
    renderAssistant();
    // friendlyMessage 优先用后端原话，兜底文案才是"会话历史读取失败"
    await screen.findByText('库被占用');
    const before = apiGet.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '重新读取' }));
    await waitFor(() => expect(apiGet.mock.calls.length).toBeGreaterThan(before));
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('卸载与切会话都不删数据', async () => {
    const view = renderAssistant();
    await screen.findByText('第一问');
    view.unmount();
    expect(apiDel).not.toHaveBeenCalled();

    const second = renderAssistant();
    await second.findByText('第一问');
    // 会话选择器切到另一个会话：只换游标请求，不删任何东西
    fireEvent.click(screen.getByLabelText('切换会话'));
    fireEvent.click(await screen.findByText('S2'));
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/ai/sessions/11/history?limit=50'));
    expect(apiDel).not.toHaveBeenCalled();
    second.unmount();
  });
});
