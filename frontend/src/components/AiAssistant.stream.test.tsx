import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AiAssistant } from './AiAssistant';
import { useUiStore } from '../stores/uiStore';
import { ApiError } from '../lib/api';
import { isUuidV7 } from '../lib/uuidV7';
import type { StreamCallbacks } from '../lib/aiStream';
import type { ToolActivity } from '../lib/toolActivity';
import type { AiRecovery } from '../lib/aiRecovery';

const { apiGet, apiPost, streamChatMock } = vi.hoisted(() => ({
  apiGet: vi.fn(), apiPost: vi.fn(), streamChatMock: vi.fn()
}));

vi.mock('../lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/api')>()),
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
/** 抽屉现在读的是 50 条游标分页的 /history，不再是全量 /messages。 */
const historyPage = (messages: Array<Record<string, unknown>>) => ({
  sessionId: 10, messages, historyRevision: messages.length, hasMore: false, limit: 50
});
const historyCalls = (): number => apiGet.mock.calls.filter((call) => String(call[0]).includes('/history')).length;
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

/** v2 的 requestId 由前端生成：断言取消与查状态用的就是发出去的那一个身份。 */
const turnRequestId = (): string => String((streamChatMock.mock.calls[0][1] as { requestId: string }).requestId);

const send = async (text: string): Promise<void> => {
  await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
  fireEvent.change(screen.getByPlaceholderText(/提问/), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
};

/** jsdom 不做布局；模拟可滚动视口，并逐帧驱动持续生成时的自动跟随。 */
const mockChatViewport = () => {
  const element = document.querySelector<HTMLDivElement>('.ai-drawer-chat')!;
  let height = 1600;
  let top = 0;
  Object.defineProperties(element, {
    clientHeight: { configurable: true, value: 400 },
    scrollHeight: { configurable: true, get: () => height },
    scrollTop: {
      configurable: true,
      get: () => top,
      set: (value: number) => { top = Math.max(0, Math.min(value, height - 400)); }
    }
  });
  let frameId = 0;
  const frames = new Map<number, FrameRequestCallback>();
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
    frames.set(++frameId, callback);
    return frameId;
  });
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((id) => { frames.delete(id); });
  return {
    element,
    grow: (pixels: number) => { height += pixels; },
    scrollTo: (position: number) => {
      element.scrollTop = position;
      fireEvent.scroll(element);
    },
    nextFrame: () => {
      const pending = Array.from(frames.values());
      frames.clear();
      act(() => { pending.forEach((callback) => callback(performance.now())); });
    }
  };
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
      if (url.startsWith('/api/ai/sessions/10/history?')) return Promise.resolve(historyPage(serverMessages));
      if (url.startsWith('/api/notebooks/')) return Promise.resolve([]);
      return Promise.resolve({});
    });
    apiPost.mockImplementation(() => Promise.resolve({}));
    streamChatMock.mockImplementation((_path: string, _body: unknown, callbacks: StreamCallbacks) => {
      streamCallbacks = callbacks;
      return Promise.resolve(() => undefined);
    });
  });

  afterEach(() => { vi.restoreAllMocks(); });

  const activity: ToolActivity = { sequence: 1, toolName: 'create_memory', state: 'FINISHED', outcome: 'CREATED',
    count: 1, startedAt: '2026-11-08T01:00:00.000Z', elapsedMs: 30, availability: null, incomplete: false };

  it('恢复状态在原回答显示一次，成功后清除且始终保留下一条草稿', async () => {
    renderAssistant(); await send('请求在原轮次恢复');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    const input = screen.getByPlaceholderText('正在回复，可先输入下一条问题');
    fireEvent.change(input, { target: { value: '恢复过程中写下的草稿' } });
    const value: AiRecovery = { kind: 'request', state: 'waiting', attempt: 0, limit: 4,
      error: 'HTTP 503 · server_error\nfixture provider unavailable', nextRetryAt: Date.now() + 10000 };
    act(() => streamCallbacks!.onMetadata?.({ requestId: turnRequestId(), sessionId: 10, recovery: value }));
    expect(screen.getByText('请求重试 0/4')).toBeVisible();
    expect(screen.getAllByText(/fixture provider unavailable/)).toHaveLength(1);
    expect(screen.queryByText('展开报错')).toBeNull();
    expect(screen.getByRole('button', { name: '停止' })).toBeEnabled();
    act(() => streamCallbacks!.onMetadata?.({ requestId: turnRequestId(), recovery: { ...value, state: 'connecting', attempt: 1, nextRetryAt: null } }));
    expect(screen.getByText('请求重试 1/4')).toBeVisible();
    act(() => {
      streamCallbacks!.onMetadata?.({ requestId: turnRequestId(), recovery: null });
      streamCallbacks!.onText('成功恢复的回答');
      streamCallbacks!.onDone({ requestId: turnRequestId(), reply: '成功恢复的回答', reasoning: '', status: 'COMPLETED', recovery: null, stateDurable: true });
    });
    expect(screen.getByText('成功恢复的回答')).toBeVisible();
    expect(document.querySelector('.ai-recovery')).toBeNull();
    expect(input).toHaveValue('恢复过程中写下的草稿');
    expect(streamChatMock).toHaveBeenCalledOnce();
  });

  it('没有正文的上游失败也保留报错，历史刷新后显示一次', async () => {
    renderAssistant(); await send('上游鉴权失败');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    const value: AiRecovery = { kind: 'request', state: 'failed', attempt: 0, limit: 4,
      error: 'HTTP 401 · invalid_api_key\nfixture provider exact error' };
    act(() => streamCallbacks!.onMetadata?.({ requestId: turnRequestId(), sessionId: 10, recovery: value }));
    serverMessages = [
      { id: 1, role: 'user', content: '上游鉴权失败', requestId: turnRequestId(), turnStatus: 'FAILED' },
      { id: 2, role: 'assistant', content: '', requestId: turnRequestId(), turnStatus: 'FAILED', recovery: value }
    ];
    act(() => streamCallbacks!.onError({ requestId: turnRequestId(), sessionId: 10,
      message: value.error, partial: false, persistenceStatus: 'saved', stateDurable: true, recovery: value }));
    await waitFor(() => expect(document.querySelector('.chat-stream-cursor')).toBeNull());
    expect(screen.getAllByText(/fixture provider exact error/)).toHaveLength(1);
    expect(screen.queryByText('这一轮失败，显示的内容可能不完整')).toBeNull();
    expect(screen.queryByText('展开报错')).toBeNull();
    await act(async () => { useUiStore.setState({ aiOpen: false }); });
    await act(async () => { useUiStore.setState({ aiOpen: true }); });
    await waitFor(() => expect(screen.getAllByText(/fixture provider exact error/)).toHaveLength(1));
    expect(document.querySelectorAll('.chat-message.assistant')).toHaveLength(1);
    expect(streamChatMock).toHaveBeenCalledOnce();
    expect(apiPost.mock.calls.some(([url]) => url === '/api/ai/chat')).toBe(false);
  });

  it('生成期间跨页定位并返回最新，草稿和原轮次流式回答都保留', async () => {
    serverMessages = [
      { id: 1001, role: 'user', content: '最新的历史提问', requestId: 'old-latest' },
      { id: 1002, role: 'assistant', content: '最新的历史答复', requestId: 'old-latest' }
    ];
    const other = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/sessions/10/history?limit=50') return Promise.resolve({ ...historyPage(serverMessages),
        firstRound: 501, totalRounds: serverMessages.length > 2 ? 502 : 501, hasMore: true });
      if (url === '/api/ai/sessions/10/navigation/1') return Promise.resolve({ roundNumber: 1, totalRounds: 502,
        messageId: 1, question: '最早的历史提问', answer: '最早的历史答复', status: 'COMPLETED' });
      if (url === '/api/ai/sessions/10/history?limit=50&afterMessageId=0') return Promise.resolve({
        ...historyPage([{ id: 1, role: 'user', content: '最早的历史提问' }, { id: 2, role: 'assistant', content: '最早的历史答复' }]),
        firstRound: 1, totalRounds: 502, hasOlder: false, hasMore: true });
      return other(url);
    });
    renderAssistant(); await screen.findByText('最新的历史答复');
    await send('正在生成的这一问'); await waitFor(() => expect(streamCallbacks).toBeTruthy());
    const input = screen.getByPlaceholderText('正在回复，可先输入下一条问题');
    fireEvent.change(input, { target: { value: '跳转时保留的草稿' } });
    act(() => streamCallbacks!.onText('回答的前半段'));
    const range = screen.getByRole('slider', { name: '会话内对话跳转' });
    fireEvent.focus(range); fireEvent.keyDown(range, { key: 'Home' });
    await screen.findByText('最早的历史提问');
    expect(screen.getByText('最新的历史答复')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /第 1 .*对话预览/ }));
    await waitFor(() => expect(document.querySelector('[data-conversation-message="1"]')).toHaveClass('is-jump-target'));
    expect(screen.queryByText('最新的历史答复')).toBeNull();
    expect(input).toHaveValue('跳转时保留的草稿');
    expect(screen.queryByText('跳到这轮')).toBeNull();
    serverMessages = [...serverMessages,
      { id: 1003, role: 'user', content: '正在生成的这一问', requestId: turnRequestId() },
      { id: 1004, role: 'assistant', content: '回答的前半段和后半段', requestId: turnRequestId(), turnStatus: 'COMPLETED' }];
    act(() => {
      streamCallbacks!.onText('和后半段');
      streamCallbacks!.onDone({ requestId: turnRequestId(), reply: '回答的前半段和后半段', reasoning: '', status: 'COMPLETED', stateDurable: true });
    });
    expect(screen.getByText('最早的历史答复')).toBeVisible();
    expect(screen.queryByText('回答的前半段和后半段')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /回到最新/ }));
    await screen.findByText('回答的前半段和后半段');
    expect(screen.queryByText('最早的历史答复')).toBeNull();
    expect(input).toHaveValue('跳转时保留的草稿');
    expect(streamChatMock).toHaveBeenCalledOnce();
    expect(apiPost.mock.calls.some(([url]) => url === '/api/ai/chat')).toBe(false);
  });

  it('回复中 Enter 排队、Ctrl+Enter 插话且不取消当前轮，组合输入不提交', async () => {
    renderAssistant(); await send('第一问');
    const input = await screen.findByPlaceholderText('正在回复，可先输入下一条问题');
    fireEvent.change(input, { target: { value: '下一条' } });
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });
    expect(apiPost).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/sessions/10/queue', expect.objectContaining({ mode: 'queue', input: { role: 'user', userText: '下一条' } })));
    await waitFor(() => expect(input).toHaveValue(''));
    fireEvent.change(input, { target: { value: '补充条件' } });
    fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/sessions/10/queue', expect.objectContaining({ mode: 'steer', targetRequestId: turnRequestId() })));
    expect(streamChatMock).toHaveBeenCalledTimes(1);
    expect(apiPost.mock.calls.some((call) => String(call[0]).endsWith('/cancel'))).toBe(false);
  });

  it('入队响应延迟时只清已提交草稿，随后输入的文字保留', async () => {
    let acknowledge!: (value: unknown) => void;
    apiPost.mockImplementation((path: string) => path.endsWith('/queue') ? new Promise((resolve) => { acknowledge = resolve; }) : Promise.resolve({}));
    renderAssistant(); await send('第一问');
    const input = await screen.findByPlaceholderText('正在回复，可先输入下一条问题');
    fireEvent.change(input, { target: { value: '排队消息' } }); fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(acknowledge).toBeTruthy());
    fireEvent.change(input, { target: { value: '正在写的新草稿' } });
    await act(async () => { acknowledge({ status: 'QUEUED' }); });
    expect(input).toHaveValue('正在写的新草稿');
    expect(streamChatMock).toHaveBeenCalledTimes(1);
  });

  it('已自定义 Ctrl+Enter 为发送时仍排队，不被插话快捷键覆盖', async () => {
    const shortcuts = useUiStore.getState().shortcutConfig;
    try {
      act(() => useUiStore.setState({ shortcutConfig: { ...shortcuts, aiSend: ['Ctrl+Enter'] } }));
      renderAssistant(); await send('第一问');
      const input = await screen.findByPlaceholderText('正在回复，可先输入下一条问题');
      fireEvent.change(input, { target: { value: '排队' } }); fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true });
      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/sessions/10/queue', expect.objectContaining({ mode: 'queue' })));
    } finally { act(() => useUiStore.setState({ shortcutConfig: shortcuts })); }
  });

  it('历史达限后在原轮次重试，沿用原输入并保留下一条草稿', async () => {
    serverMessages = [{ id: 1, role: 'user', content: '检查所有记忆', requestId: 'budget-turn' },
      { id: 2, role: 'assistant', content: '已完成部分查询', requestId: 'budget-turn', turnStatus: 'COMPLETED', finishReason: 'budget_stages', toolActivities: [activity] }];
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/turns/budget-turn/input'
      ? Promise.resolve({ requestId: 'budget-turn', sessionId: 10, input: { role: 'user', userText: '检查所有记忆' } }) : base(url));
    renderAssistant();
    const next = await screen.findByRole('button', { name: '重试' });
    expect(screen.getByText(/已达工具阶段上限/)).toBeVisible();
    fireEvent.change(screen.getByPlaceholderText(/提问/), { target: { value: '未发送草稿' } });
    expect(next).toBeEnabled();
    fireEvent.click(next);
    await waitFor(() => expect(streamChatMock).toHaveBeenCalledTimes(1));
    const body = streamChatMock.mock.calls[0][1];
    expect(body.sessionId).toBe(10);
    expect(body.requestId).not.toBe('budget-turn');
    expect(body.retryOf).toBe('budget-turn');
    expect(body.input.userText).toBe('检查所有记忆');
    expect(document.querySelectorAll('.chat-message.user')).toHaveLength(1);
    expect(document.querySelectorAll('.chat-message.assistant')).toHaveLength(1);
    expect(screen.getByPlaceholderText('正在回复，可先输入下一条问题')).toHaveValue('未发送草稿');
    serverMessages = [{ id: 1, role: 'user', content: '检查所有记忆', requestId: body.requestId },
      { id: 2, role: 'assistant', content: '原位置重试完成', requestId: body.requestId, turnStatus: 'COMPLETED' }];
    act(() => streamCallbacks!.onDone({ requestId: body.requestId, reply: '原位置重试完成', reasoning: '', status: 'COMPLETED', stateDurable: true }));
    await screen.findByText('原位置重试完成');
    expect(screen.getByPlaceholderText(/提问/)).toHaveValue('未发送草稿');
    expect(document.querySelectorAll('.chat-message.user')).toHaveLength(1);
  });

  it('失败历史只在助手气泡提示，用户提问保持原文', async () => {
    serverMessages = [
      { id: 1, role: 'user', content: '更新记忆', requestId: 'failed-turn', turnStatus: 'FAILED' },
      { id: 2, role: 'assistant', content: '', requestId: 'failed-turn', turnStatus: 'FAILED', toolActivities: [activity] }
    ];
    renderAssistant();
    await screen.findByText('更新记忆');
    expect(document.querySelector('.chat-message.user .ai-retrieval-notice')).toBeNull();
    expect(screen.getAllByText('这一轮失败，显示的内容可能不完整')).toHaveLength(1);
    expect(document.querySelector('.chat-message.assistant .ai-retrieval-notice')).not.toBeNull();
  });

  it('没有正文时中断也保留工具记录与用户消息，停止加载并禁止重发', async () => {
    renderAssistant();
    await send('记住我的复习偏好');
    await waitFor(() => expect(streamCallbacks?.onToolActivity).toBeTruthy());
    act(() => streamCallbacks!.onToolActivity!({ requestId: turnRequestId(), toolActivities: [activity,
      { ...activity, sequence: 2, toolName: 'update_memory', state: 'RUNNING', outcome: 'UNKNOWN', elapsedMs: null }], phase: 'tools' }));
    fireEvent.click(screen.getByRole('button', { name: '中断生成' }));
    expect(screen.getByRole('button', { name: '中断生成' })).toBeVisible();
    act(() => streamCallbacks!.onDone({ requestId: turnRequestId(), status: 'ABORTED', reply: '', reasoning: '', toolActivities: [activity], stateDurable: true }));
    expect(screen.getByText('记住我的复习偏好')).toBeVisible();
    expect(screen.getByRole('button', { name: /新增记忆 已新增 1 条/ })).toBeVisible();
    expect(screen.getByText(/这一轮已中断/)).toBeVisible();
    expect(document.querySelector('.ai-tool-icon.is-running')).toBeNull();
    expect(streamChatMock).toHaveBeenCalledTimes(1);
    expect(apiPost.mock.calls.some((call) => call[0] === '/api/ai/chat')).toBe(false);
  });

  it('工具更新不拉回底部，历史占位回流保留当前正文和手动折叠选择', async () => {
    renderAssistant();
    await send('检索后继续回答');
    await waitFor(() => expect(streamCallbacks?.onToolActivity).toBeTruthy());
    const viewport = mockChatViewport();
    viewport.scrollTo(1200);
    viewport.scrollTo(500);
    act(() => {
      streamCallbacks!.onText('正在生成的正文');
      streamCallbacks!.onToolActivity!({ requestId: turnRequestId(), toolActivities: [activity], phase: 'answer' });
    });
    const group = screen.getByRole('button', { name: /已新增 1 条.*1 次工具调用/ });
    fireEvent.click(group);
    serverMessages = [
      { id: 101, role: 'user', content: '检索后继续回答', requestId: turnRequestId() },
      { id: 102, role: 'assistant', content: '', requestId: turnRequestId(), turnStatus: 'RUNNING', toolActivities: [activity] }
    ];
    act(() => useUiStore.setState({ aiOpen: false }));
    act(() => useUiStore.setState({ aiOpen: true }));
    await waitFor(() => expect(historyCalls()).toBeGreaterThan(1));
    expect(screen.getByText('正在生成的正文')).toBeVisible();
    expect(document.querySelector('.chat-message.user .ai-retrieval-notice')).toBeNull();
    expect(document.querySelector('.chat-message.assistant .ai-retrieval-notice')).toBeNull();
    expect(screen.getByRole('button', { name: /已新增 1 条.*1 次工具调用/ })).toBe(group);
    expect(group).toHaveAttribute('aria-expanded', 'false');
    viewport.grow(120);
    act(() => streamCallbacks!.onToolActivity!({ requestId: turnRequestId(), toolActivities: [activity], phase: 'answer' }));
    viewport.nextFrame();
    expect(viewport.element.scrollTop).toBe(500);
  });

  it.each([32, 1000])('生成期间向上滚动 %i 像素后保持阅读位置，回到底部才恢复跟随', async (distance) => {
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    const viewport = mockChatViewport();
    const { element } = viewport;

    await send('请给出长回答');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    viewport.nextFrame();
    expect(element.scrollTop).toBe(1200);

    viewport.scrollTo(1200 - distance);
    viewport.nextFrame();
    expect(element.scrollTop).toBe(1200 - distance);
    // 没有位移的重复 scroll 事件不能重新开启跟随。
    fireEvent.scroll(element);
    viewport.grow(200);
    act(() => {
      streamCallbacks!.onReasoning('继续思考');
      streamCallbacks!.onText('继续生成正文');
    });
    expect(element.scrollTop).toBe(1200 - distance);
    viewport.nextFrame();
    viewport.nextFrame();
    expect(element.scrollTop).toBe(1200 - distance);

    viewport.scrollTo(1400);
    viewport.grow(100);
    viewport.nextFrame();
    expect(element.scrollTop).toBe(1500);
    viewport.grow(100);
    act(() => { streamCallbacks!.onText('，还有后续内容'); });
    expect(element.scrollTop).toBe(1600);
  });

  it('在阅读旧消息时开始生成，回到底部仍能恢复逐帧跟随', async () => {
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    const viewport = mockChatViewport();
    viewport.scrollTo(1200);
    viewport.scrollTo(500);

    await send('继续回答');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    viewport.nextFrame();
    expect(viewport.element.scrollTop).toBe(500);

    viewport.scrollTo(1200);
    viewport.grow(200);
    viewport.nextFrame();
    expect(viewport.element.scrollTop).toBe(1400);
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
    // 后端首帧就带 requestId：本地这一对气泡从此有了身份，历史行按它认领，不再靠文本猜
    act(() => { streamCallbacks!.onMetadata?.({ requestId: turnRequestId(), sessionId: 10 }); });
    // 后端在 begin 时已把新的用户消息落库；同时假设服务端历史里还有更早的一条（第三问），
    // 它只可能通过重取最新一页进入列表——用它作为"合并确实执行过"的同步点。
    serverMessages = [
      ...serverMessages,
      { id: 3, role: 'user', content: '第二问', requestId: turnRequestId(), createdAt: '2026-10-12T12:01:00.000Z' },
      { id: 4, role: 'user', content: '第三问', createdAt: '2026-10-12T12:02:00.000Z' }
    ];

    const fetchedBefore = historyCalls();
    // 用户的原始操作路径：关闭侧边栏再打开 → 消息查询重新启用并 refetch
    await act(async () => { useUiStore.setState({ aiOpen: false }); });
    await act(async () => { useUiStore.setState({ aiOpen: true }); });
    await waitFor(() => expect(historyCalls()).toBeGreaterThan(fetchedBefore));
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

  it('停止携带本轮 requestId，等待后端终态且保留已受理提问', async () => {
    serverMessages = [{ id: 1, role: 'user', content: '第一问', createdAt: '2026-10-12T12:00:00.000Z' }];
    renderAssistant();
    await screen.findByText('第一问');

    await send('第二问');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    // v2 的 requestId 由前端生成并在 metadata 里回显：取消用的就是这一个身份
    const requestId = turnRequestId();
    expect(isUuidV7(requestId)).toBe(true);
    act(() => streamCallbacks?.onMetadata?.({ requestId }));
    await waitFor(() => expect(screen.getByRole('button', { name: '中断生成' })).toBeInTheDocument());

    // 取消会退还为回复预留的字节：容量条必须在同一次交互里重读，而不是等到下一轮定稿（6a 复核观察）
    const storageReads = (): number => apiGet.mock.calls.filter((call) => call[0] === '/api/ai/history/storage').length;
    const storageBefore = storageReads();
    fireEvent.click(screen.getByRole('button', { name: '中断生成' }));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith(`/api/ai/turns/${requestId}/cancel`, {}));
    await waitFor(() => expect(storageReads()).toBeGreaterThan(storageBefore));
    act(() => streamCallbacks!.onDone({ requestId, status: 'ABORTED', reply: '', reasoning: '', stateDurable: true }));
    // 后端确认后恢复可发送；已经受理的提问不能从界面抹掉。
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeInTheDocument());
    expect(messageNodes().some((node) => node.textContent?.includes('第二问'))).toBe(true);
  });

  it('已有部分正文后收到 error：绝不自动回退重发，保留已生成内容', async () => {
    serverMessages = [{ id: 1, role: 'user', content: '第一问', createdAt: '2026-10-12T12:00:00.000Z' }];
    renderAssistant();
    await screen.findByText('第一问');

    await send('第二问');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    act(() => streamCallbacks!.onMetadata?.({ requestId: 'turn-11' }));
    act(() => streamCallbacks!.onText('已经生成的半句'));
    act(() => streamCallbacks!.onError({ message: 'AI 响应读取失败：连接被重置' }));

    // 轮次已经真正开始：不得再发第二个请求（重复生成/计费）
    expect(apiPost.mock.calls.some((call) => call[0] === '/api/ai/chat')).toBe(false);
    expect(await screen.findByText('已经生成的半句')).toBeInTheDocument();
    expect(screen.getByText('第二问')).toBeInTheDocument();
    expect(document.querySelector('.chat-stream-cursor')).toBeNull();
    expect(await screen.findByText('AI 响应读取失败：连接被重置')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeInTheDocument());
  });

  it('只有 reasoning 后收到 error（finalize 后失败）：同样不自动回退', async () => {
    serverMessages = [{ id: 1, role: 'user', content: '第一问', createdAt: '2026-10-12T12:00:00.000Z' }];
    renderAssistant();
    await screen.findByText('第一问');

    await send('第二问');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    act(() => streamCallbacks!.onMetadata?.({ requestId: 'turn-12' }));
    act(() => streamCallbacks!.onReasoning('先在草稿里想了几步'));
    act(() => streamCallbacks!.onError({ message: 'AI 服务返回内容为空（模型可能只返回了思考过程，请重试或换模型）' }));

    expect(apiPost.mock.calls.some((call) => call[0] === '/api/ai/chat')).toBe(false);
    expect(await screen.findByText('先在草稿里想了几步')).toBeInTheDocument();
    expect(screen.getByText('第二问')).toBeInTheDocument();
  });

  it('拿到 metadata 但零输出后收到 error：不自动回退，只提示并保留用户消息', async () => {
    serverMessages = [{ id: 1, role: 'user', content: '第一问', createdAt: '2026-10-12T12:00:00.000Z' }];
    renderAssistant();
    await screen.findByText('第一问');

    await send('第二问');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    act(() => streamCallbacks!.onMetadata?.({ requestId: 'turn-13' }));
    act(() => streamCallbacks!.onError({ message: 'AI 服务返回内容为空（模型可能只返回了思考过程，请重试或换模型）' }));

    expect(apiPost.mock.calls.some((call) => call[0] === '/api/ai/chat')).toBe(false);
    expect(screen.getByText('第二问')).toBeInTheDocument();
    expect(screen.queryByText('插入笔记')).toBeNull();
  });

  it('没有 done 就 EOF：查到的终态如实写在气泡上，既不冒充完成也不重发', async () => {
    serverMessages = [{ id: 1, role: 'user', content: '第一问', createdAt: '2026-10-12T12:00:00.000Z' }];
    apiGet.mockImplementation((url: string) => {
      if (url.startsWith('/api/ai/turns/')) {
        return Promise.resolve({ status: 'TRUNCATED', active: false, persistenceStatus: 'saved', stateDurable: true });
      }
      if (url === '/api/ai/config') return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm' } });
      if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
      if (url === '/api/ai/sessions/10/history?limit=50') return Promise.resolve(historyPage(serverMessages));
      return Promise.resolve([]);
    });
    renderAssistant();
    await screen.findByText('第一问');

    await send('第二问');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    act(() => {
      streamCallbacks!.onText('半截答案');
      streamCallbacks!.onNoDone!({ requestId: turnRequestId(), partial: true });
    });

    expect(await screen.findByText('生成中断，已收到的内容不是完整结果')).toBeInTheDocument();
    expect(document.querySelector('.chat-stream-cursor')).toBeNull();
    expect(apiPost.mock.calls.filter((call) => call[0] === '/api/ai/chat')).toHaveLength(0);
  });

  it('早期失败：先确认后端没这一轮，再用同一个 requestId 回退非流式', async () => {
    serverMessages = [{ id: 1, role: 'user', content: '第一问', createdAt: '2026-10-12T12:00:00.000Z' }];
    apiGet.mockImplementation((url: string) => {
      if (url.startsWith('/api/ai/turns/')) return Promise.reject(new ApiError('TURN_NOT_FOUND', '没有这个轮次', 404));
      if (url === '/api/ai/config') return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm' } });
      if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
      if (url === '/api/ai/sessions/10/history?limit=50') return Promise.resolve(historyPage(serverMessages));
      return Promise.resolve([]);
    });
    apiPost.mockImplementation((url: string) => (url === '/api/ai/chat'
      ? Promise.resolve({ reply: 'ANSWER', sessionId: 10 })
      : Promise.resolve({})));
    renderAssistant();
    await screen.findByText('第一问');

    await send('第二问');
    await waitFor(() => expect(streamCallbacks).toBeTruthy());
    const requestId = turnRequestId();
    act(() => streamCallbacks!.onError({ message: '请求失败（404）', partial: false }));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/chat', expect.objectContaining({ requestId })));
    expect(await screen.findByText('ANSWER')).toBeInTheDocument();
    expect(screen.getByText('第二问')).toBeInTheDocument();
  });
});
