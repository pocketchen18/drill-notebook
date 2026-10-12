import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AiAssistant } from './AiAssistant';
import { useUiStore } from '../stores/uiStore';
import type { StreamCallbacks } from '../lib/aiStream';

const { apiGet, apiPost, streamChatMock } = vi.hoisted(() => ({
  apiGet: vi.fn(), apiPost: vi.fn(), streamChatMock: vi.fn()
}));

vi.mock('../lib/api', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: vi.fn(),
  del: vi.fn()
}));

vi.mock('../lib/aiStream', () => ({
  streamChat: (...args: unknown[]) => streamChatMock(...args)
}));

/** 服务端已落库的消息：测试中按需变化，模拟"新一轮的用户消息在 begin 时已入库"。 */
let serverMessages: Array<Record<string, unknown>> = [];
let streamCallbacks: StreamCallbacks | null = null;

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

const messageNodes = (): Element[] => Array.from(document.querySelectorAll('.chat-message'));

const send = async (text: string): Promise<void> => {
  await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
  fireEvent.change(screen.getByPlaceholderText(/提问/), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
};

describe('AiAssistant 流式会话状态', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    serverMessages = [];
    streamCallbacks = null;
    useUiStore.setState({ aiOpen: true, pageContext: { kind: 'none', title: '无页面上下文', markdown: '' } });
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/config') return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm' } });
      if (url === '/api/notebooks') return Promise.resolve([{ id: 1, title: 'NB' }]);
      if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
      if (url === '/api/ai/sessions/10/messages') return Promise.resolve([...serverMessages]);
      if (url.startsWith('/api/notebooks/')) return Promise.resolve([]);
      return Promise.resolve({});
    });
    apiPost.mockImplementation(() => Promise.resolve({}));
    streamChatMock.mockImplementation((_path: string, _body: unknown, callbacks: StreamCallbacks) => {
      streamCallbacks = callbacks;
      return Promise.resolve(() => undefined);
    });
  });

  it('关闭再打开侧边栏触发的 refetch 不得把流式中的本地尾巴顶到最前', async () => {
    serverMessages = [
      { id: 1, role: 'user', content: '第一问', createdAt: '2026-10-12T12:00:00.000Z' },
      { id: 2, role: 'assistant', content: '答一', createdAt: '2026-10-12T12:00:01.000Z' }
    ];
    renderAssistant();
    await screen.findByText('第一问');

    await send('第二问');
    await waitFor(() => expect(streamCallbacks?.onText).toBeTruthy());
    // 后端在 begin 时已把新的用户消息落库；同时假设服务端历史里还有更早的一条（第三问），
    // 它只可能通过 refetch 的合并进入本地列表——用它作为"合并确实执行过"的同步点。
    serverMessages = [
      ...serverMessages,
      { id: 3, role: 'user', content: '第二问', createdAt: '2026-10-12T12:01:00.000Z' },
      { id: 4, role: 'user', content: '第三问', createdAt: '2026-10-12T12:02:00.000Z' }
    ];

    const fetchedBefore = apiGet.mock.calls.filter((call) => call[0] === '/api/ai/sessions/10/messages').length;
    // 用户的原始操作路径：关闭侧边栏再打开 → 消息查询重新启用并 refetch
    await act(async () => { useUiStore.setState({ aiOpen: false }); });
    await act(async () => { useUiStore.setState({ aiOpen: true }); });
    await waitFor(() => expect(apiGet.mock.calls.filter((call) => call[0] === '/api/ai/sessions/10/messages').length)
      .toBeGreaterThan(fetchedBefore));
    // 合并完成的标志：新出现的历史消息进入列表
    await screen.findByText('第三问');

    // 顺序必须保持：服务端历史在前，本地未落库的流式占位在最后
    const nodes = messageNodes();
    expect(nodes).toHaveLength(5);
    expect(nodes[0].textContent).toContain('第一问');
    expect(nodes[1].textContent).toContain('答一');
    expect(nodes[2].textContent).toContain('第二问');
    expect(nodes[3].textContent).toContain('第三问');
    expect(nodes[4].querySelector('.chat-stream-cursor')).not.toBeNull();
  });

  it('停止必须携带 metadata 里的 requestId 调取消接口，让后端立刻释放会话', async () => {
    serverMessages = [{ id: 1, role: 'user', content: '第一问', createdAt: '2026-10-12T12:00:00.000Z' }];
    renderAssistant();
    await screen.findByText('第一问');

    await send('第二问');
    // 后端在开始外发前先送出 metadata（requestId）
    act(() => streamCallbacks?.onMetadata?.({ requestId: 'turn-9' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '中断生成' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '中断生成' }));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/turns/turn-9/cancel', {}));
    // 停止后界面恢复可发送：占位与未送达的用户消息一起回滚
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeInTheDocument());
    expect(messageNodes().some((node) => node.textContent?.includes('第二问'))).toBe(false);
  });
});
