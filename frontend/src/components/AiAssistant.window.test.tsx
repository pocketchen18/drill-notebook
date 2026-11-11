import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { AiAssistant, quoteBlock } from './AiAssistant';
import { SessionNavigation, sessionGroup } from './ai-session/SessionNavigation';
import { useUiStore } from '../stores/uiStore';
import { defaultShortcutConfig } from '../lib/shortcuts';
import type { AiChatSession } from '../lib/types';
import { ApiError } from '../lib/api';
import type { OriginalTurnInput } from '../hooks/useAiChatTurn';

/** v0.8 助手窗口框架：会话栏、绑定页面、实际模型与用量、回到最新、窗口快捷键与详情栏。 */
const { apiGet, apiPost, apiPut, streamChatMock } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), streamChatMock: vi.fn() }));
vi.mock('../lib/aiStream', () => ({ streamChat: (...args: unknown[]) => streamChatMock(...args) }));
vi.mock('../lib/api', async (original) => ({ ...await original<typeof import('../lib/api')>(),
  get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args), put: (...args: unknown[]) => apiPut(...args) }));

const day = 86400000;
const iso = (offset: number): string => new Date(Date.now() - offset).toISOString();
const sessions: AiChatSession[] = [
  { id: 10, title: '导数与连续性', updatedAt: iso(60000), messageCount: 4, sessionLink: 'drill-notebook://session/' + 'a'.repeat(32), modelConfigId: null, reasoningEffort: null },
  { id: 11, title: '积分换元法', updatedAt: iso(3 * day), messageCount: 2, sessionLink: 'drill-notebook://session/' + 'b'.repeat(32) },
  { id: 12, title: '英语长难句', updatedAt: iso(40 * day), messageCount: 6, sessionLink: 'drill-notebook://session/' + 'c'.repeat(32) }
];
const history = { sessionId: 10, historyRevision: 3, hasMore: false, limit: 50, messages: [
  { id: 1, role: 'user', content: '讲解当前这页', requestId: 'r1', readable: true, turnStatus: 'COMPLETED', page: { title: '笔记 · 导数的定义', link: 'drill-notebook://material/note/7' } },
  { id: 2, role: 'assistant', content: '可导一定连续。', requestId: 'r1', readable: true, turnStatus: 'COMPLETED',
    model: 'DeepSeek · deepseek-reasoner', reasoningEffort: 'high', usage: { requests: 3, inputTokens: 18400, outputTokens: 1200 } },
  { id: 3, role: 'user', content: '再举个例子', requestId: 'r2', readable: true, turnStatus: 'COMPLETED' },
  { id: 4, role: 'assistant', content: '例如绝对值函数。', requestId: 'r2', readable: true, turnStatus: 'COMPLETED' }
] };

function Location(): JSX.Element { const location = useLocation(); return <span data-testid="loc">{location.pathname + location.search}</span>; }
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter><AiAssistant /><Location /></MemoryRouter></QueryClientProvider>);
}
beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear();
  streamChatMock.mockResolvedValue(() => undefined);
  Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true });
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockResolvedValue(undefined) }, configurable: true });
  useUiStore.setState({ aiOpen: true, shortcutConfig: defaultShortcutConfig(),
    pageContext: { kind: 'note', title: '笔记 · 极限', markdown: '正文', route: '/notebooks', notePageId: 9 } });
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/ai/config') return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm', streaming: false } });
    if (url === '/api/ai/sessions') return Promise.resolve(sessions);
    if (url.startsWith('/api/ai/sessions/10/history')) return Promise.resolve(history);
    // 其它会话没有消息：给一页空历史，避免切换后读到不完整的响应
    if (/^\/api\/ai\/sessions\/\d+\/history/.test(url)) return Promise.resolve({ sessionId: 0, historyRevision: 1, hasMore: false, limit: 50, messages: [] });
    if (url === '/api/ai/sessions/10/context-usage') return Promise.resolve({ sessionId: 10, model: 'm', contextWindow: 128000, outputReserve: 8192,
      latest: { requestId: 'r2', inputTokens: 1200, contextWindow: 128000, model: 'm' } });
    if (url === '/api/ai/sessions/runtime?sessionId=10' || url === '/api/ai/sessions/runtime') return Promise.resolve({ sessions: [] });
    if (url === '/api/ai/connections') return Promise.resolve({ revision: 1, chatModelId: 'm1', importModelId: null, providers: [{ id: 'p', name: 'DeepSeek',
      apiFormat: 'chat_completions', endpoint: 'x', authMode: 'api_key', hasKey: true, needsPassword: false, models: [
        { id: 'm1', modelId: 'deepseek-chat', name: '', group: '', streaming: true, tools: true, contextWindow: 128000, maxOutputTokens: 8000 },
        { id: 'm2', modelId: 'deepseek-reasoner', name: '', group: '', streaming: true, tools: true, contextWindow: 128000, maxOutputTokens: 8000, reasoningEfforts: ['low', 'high'] }] }] });
    return Promise.resolve({});
  });
});

describe('会话栏', () => {
  it('按今天 / 近 7 天 / 更早分组，查找只筛标题，没有命中有说明', async () => {
    expect([sessionGroup(iso(0)), sessionGroup(iso(3 * day)), sessionGroup(iso(40 * day)), sessionGroup(undefined)]).toEqual(['今天', '近 7 天', '更早', '更早']);
    const props = { visible: true, sessions, currentId: 10, loading: false, error: false, creating: false, exporting: false, onRetry: vi.fn(),
      onCreate: vi.fn(), onSelect: vi.fn(), onRename: vi.fn(), onGenerate: vi.fn(), onDelete: vi.fn(), onExport: vi.fn() };
    render(<SessionNavigation {...props} docked footer={<span>底部区域</span>} />);
    expect(screen.getAllByRole('presentation').map((node) => node.textContent)).toEqual(['今天', '近 7 天', '更早']);
    expect(screen.queryByRole('button', { name: '切换会话' })).toBeNull();
    expect(screen.getByText('底部区域')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('查找会话'), { target: { value: '积分' } });
    expect(screen.getAllByRole('presentation').map((node) => node.textContent)).toEqual(['近 7 天']);
    expect(screen.queryByText('导数与连续性')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /^积分换元法 2 条消息/ }));
    await waitFor(() => expect(props.onSelect).toHaveBeenCalledWith(11));
    fireEvent.change(screen.getByLabelText('查找会话'), { target: { value: '没有这个' } });
    expect(screen.getByText('没有匹配的会话')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }));
    await waitFor(() => expect(props.onCreate).toHaveBeenCalledTimes(1));
  });
});

describe('助手窗口框架', () => {
  it('提问下显示提交时的页面，回答显示实际模型、档位与累计用量，可复制', async () => {
    mount();
    expect(await screen.findByText('可导一定连续。')).toBeInTheDocument();
    expect(screen.getByText('DeepSeek · deepseek-reasoner · 思考 high')).toBeInTheDocument();
    const usage = screen.getByText('输入 18.4k · 输出 1.2k');
    expect(usage).toHaveAttribute('title', '本轮 3 次模型请求的累计实测用量');
    expect(screen.getAllByRole('button', { name: '复制' })).toHaveLength(2);
    fireEvent.click(screen.getAllByRole('button', { name: '复制' })[0]);
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('可导一定连续。');
    // 历史记录的页面来自提交时刻，不随主窗口当前页面变化
    const bound = screen.getByRole('button', { name: '笔记 · 导数的定义' });
    expect(screen.getByRole('button', { name: /笔记 · 极限/ })).toHaveClass('ai-window-page');
    fireEvent.click(bound);
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/notebooks?pageId=7'));
    expect(document.querySelectorAll('.chat-bound-page')).toHaveLength(1);
    expect(document.querySelector('.chat-message.assistant.is-latest')?.textContent).toContain('例如绝对值函数。');
  });

  it('窗口快捷键新建、查找和收起会话栏，Esc 先关详情栏；改绑后立即生效', async () => {
    mount();
    await screen.findByText('可导一定连续。');
    const side = screen.getByRole('navigation', { name: '会话' });
    fireEvent.keyDown(window, { key: 'b', ctrlKey: true });
    expect(side).not.toBeVisible();
    expect(localStorage.getItem('ai.window.sideCollapsed')).toBe('1');
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    expect(side).toBeVisible();
    await waitFor(() => expect(screen.getByLabelText('查找会话')).toHaveFocus());
    fireEvent.click(screen.getByRole('button', { name: /上下文占用/ }));
    fireEvent.click(screen.getByRole('button', { name: '查看本轮上下文' }));
    expect(screen.getByRole('complementary', { name: '本轮上下文' })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('complementary')).toBeNull();
    fireEvent.keyDown(window, { key: 'n', ctrlKey: true });
    expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('新会话');
    expect(screen.getByText('开始一段新对话')).toBeInTheDocument();
    act(() => useUiStore.getState().setShortcutConfig({ ...defaultShortcutConfig(), aiToggleSidebar: ['Alt+S'] }));
    fireEvent.keyDown(window, { key: 'b', ctrlKey: true });
    expect(side).toBeVisible();
    fireEvent.keyDown(window, { key: 's', altKey: true });
    expect(side).not.toBeVisible();
  });

  it('上翻后出现回到最新，点击回到底部；窄窗口会话栏为浮层并在选择后收起', async () => {
    Object.defineProperty(window, 'innerWidth', { value: 480, configurable: true });
    mount();
    await screen.findByText('可导一定连续。');
    // 隐藏的区域没有可访问名称，按结构取。
    const hiddenSide = (): Element => document.querySelector('.ai-window-side')!;
    expect(hiddenSide()).not.toBeVisible();
    const stream = document.querySelector<HTMLDivElement>('.ai-window-stream')!;
    Object.defineProperties(stream, { scrollHeight: { value: 2000, configurable: true }, clientHeight: { value: 400, configurable: true } });
    stream.scrollTop = 900; fireEvent.scroll(stream);
    stream.scrollTop = 300; fireEvent.scroll(stream);
    const latest = screen.getByRole('button', { name: '回到最新' });
    fireEvent.click(latest);
    expect(stream.scrollTop).toBe(2000);
    expect(screen.queryByRole('button', { name: /回到最新/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '展开会话栏' }));
    const side = screen.getByRole('navigation', { name: '会话' });
    expect(side).toBeVisible();
    fireEvent.click(within(side).getByRole('button', { name: /^积分换元法 2 条消息/ }));
    await waitFor(() => expect(hiddenSide()).not.toBeVisible());
  });

  it('在输入区为当前会话改选模型，只提交该会话的选择', async () => {
    // 保存后服务端的会话列表也带上新的选择。
    let saved = sessions;
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/sessions' ? Promise.resolve(saved) : base(url));
    apiPut.mockImplementation(async () => { saved = [{ ...sessions[0], modelConfigId: 'm2', reasoningEffort: null }, ...sessions.slice(1)]; return saved[0]; });
    mount();
    await screen.findByText('可导一定连续。');
    fireEvent.click(await screen.findByRole('button', { name: '模型：deepseek-chat' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /deepseek-reasoner/ }));
    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/sessions/10', { modelConfigId: 'm2', reasoningEffort: null }));
    expect(await screen.findByRole('button', { name: '模型：deepseek-reasoner' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '思考档位：模型默认' })).toBeInTheDocument();
  });

  it('置顶的会话归入最前的置顶分组，先置顶的在上；图钉与菜单都能置顶和取消', async () => {
    const props = { visible: true, currentId: 10, loading: false, error: false, creating: false, exporting: false, onRetry: vi.fn(),
      onCreate: vi.fn(), onSelect: vi.fn(), onRename: vi.fn(), onGenerate: vi.fn(), onDelete: vi.fn(), onExport: vi.fn(),
      onPin: vi.fn().mockResolvedValue(undefined) };
    const pinned = [sessions[0], { ...sessions[1], pinnedAt: '2026-11-11T09:00:00Z' }, { ...sessions[2], pinnedAt: '2026-11-11T08:00:00Z' }];
    const view = render(<SessionNavigation {...props} sessions={pinned} docked />);
    expect(screen.getAllByRole('presentation').map((node) => node.textContent)).toEqual(['置顶', '今天']);
    const order = [...view.container.querySelectorAll('.session-nav-name')].map((node) => node.textContent);
    expect(order).toEqual(['英语长难句', '积分换元法', '导数与连续性']);
    const unpin = screen.getByRole('button', { name: '取消置顶：英语长难句' });
    expect(unpin).toHaveAttribute('aria-pressed', 'true');
    expect(unpin).toHaveClass('is-pinned');
    fireEvent.click(unpin);
    await waitFor(() => expect(props.onPin).toHaveBeenCalledWith(12, false));
    await waitFor(() => expect(unpin).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', { name: '置顶：导数与连续性' }));
    await waitFor(() => expect(props.onPin).toHaveBeenLastCalledWith(10, true));
    expect(props.onSelect).not.toHaveBeenCalled();
    // 右键菜单提供同一个动作，文字随当前状态变化
    fireEvent.contextMenu(screen.getByRole('button', { name: /^积分换元法 2 条消息/ }), { clientX: 120, clientY: 160 });
    const menuPin = await screen.findByRole('menuitem', { name: '取消置顶' });
    // 菜单里的图标与行内图钉同一套（倾斜），不与顶栏的「固定」混淆
    expect(menuPin.querySelector('svg')).toHaveClass('session-nav-pin-icon');
    fireEvent.click(menuPin);
    await waitFor(() => expect(props.onPin).toHaveBeenLastCalledWith(11, false));
    // 查找时置顶分组只在有命中时出现
    fireEvent.change(screen.getByLabelText('查找会话'), { target: { value: '导数' } });
    expect(screen.getAllByRole('presentation').map((node) => node.textContent)).toEqual(['今天']);
  });

  it('不提供置顶能力时不显示图钉；置顶失败有提示且不改变列表', async () => {
    const base = { visible: true, sessions, currentId: 10, loading: false, error: false, creating: false, exporting: false, onRetry: vi.fn(),
      onCreate: vi.fn(), onSelect: vi.fn(), onRename: vi.fn(), onGenerate: vi.fn(), onDelete: vi.fn(), onExport: vi.fn() };
    const view = render(<SessionNavigation {...base} docked />);
    expect(screen.queryByRole('button', { name: /^置顶：/ })).toBeNull();
    view.unmount();
    const onPin = vi.fn().mockRejectedValue(new Error('置顶未保存'));
    render(<SessionNavigation {...base} docked onPin={onPin} />);
    fireEvent.click(screen.getByRole('button', { name: '置顶：积分换元法' }));
    expect(await screen.findByText('置顶未保存')).toBeInTheDocument();
    expect(screen.getAllByRole('presentation').map((node) => node.textContent)).toEqual(['今天', '近 7 天', '更早']);
  });
});

describe('重新生成回答', () => {
  const original: OriginalTurnInput = { requestId: 'r1', sessionId: 10, input: { userText: '讲解当前这页', attachments: [
    { clientId: 'c0', kind: 'text', name: '原笔记.md', mimeType: 'text/markdown', text: '原附件正文' },
    { clientId: 'c1', kind: 'image', name: '原截图.png', mimeType: 'image/png', dataBase64: 'AAAA' }
  ] } };

  it('圆形箭头重发原提问与附件，沿用轮次回执，保留原回答和输入草稿', async () => {
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/turns/r1/input' ? Promise.resolve(original) : base(url));
    mount();
    await screen.findByText('可导一定连续。');
    const button = screen.getAllByRole('button', { name: '重新生成' })[0];
    expect(button).toHaveAttribute('title', '重新生成');
    expect(button.textContent).toBe('');
    expect(button.querySelector('svg')).toHaveClass('lucide-rotate-cw');
    expect(screen.queryByRole('button', { name: '本轮上下文' })).toBeNull();
    const input = screen.getByPlaceholderText(/提问…/);
    fireEvent.change(input, { target: { value: '接着问的草稿' } });
    const draft = new File(['草稿附件正文'], '草稿附件.txt', { type: 'text/plain' });
    Object.defineProperty(draft, 'text', { value: async () => '草稿附件正文' });
    fireEvent.change(document.querySelector('input[type="file"]')!, { target: { files: [draft] } });
    await screen.findByText('草稿附件.txt');
    fireEvent.click(button);
    await waitFor(() => expect(streamChatMock).toHaveBeenCalledTimes(1));
    const body = streamChatMock.mock.calls[0][1];
    expect(body).toMatchObject({ protocolVersion: 2, sessionId: 10, retryOf: 'r1', input: original.input,
      pageSnapshot: { title: '笔记 · 极限' } });
    expect(body.requestId).not.toBe('r1');
    expect(input).toHaveValue('接着问的草稿');
    expect(screen.getByText('草稿附件.txt')).toBeInTheDocument();
    expect(screen.getByText('可导一定连续。')).toBeInTheDocument();
    expect(document.querySelectorAll('.chat-message.assistant')).toHaveLength(3);
  });

  it('连续点击只读取和发送一次，准备与生成期间不能另开一轮', async () => {
    let resolveOriginal!: (value: OriginalTurnInput) => void;
    const pending = new Promise<OriginalTurnInput>((resolve) => { resolveOriginal = resolve; });
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/turns/r1/input' ? pending : base(url));
    mount();
    await screen.findByText('可导一定连续。');
    const button = screen.getAllByRole('button', { name: '重新生成' })[0];
    fireEvent.click(button);
    fireEvent.click(button);
    expect(apiGet.mock.calls.filter(([url]) => url === '/api/ai/turns/r1/input')).toHaveLength(1);
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled();
    await act(async () => resolveOriginal(original));
    await waitFor(() => expect(streamChatMock).toHaveBeenCalledTimes(1));
    for (const action of screen.getAllByRole('button', { name: '重新生成' })) expect(action).toBeDisabled();
    fireEvent.click(button);
    expect(streamChatMock).toHaveBeenCalledTimes(1);
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('准备期间切换会话，迟到的原输入不会发送到任何会话', async () => {
    let resolveOriginal!: (value: OriginalTurnInput) => void;
    const pending = new Promise<OriginalTurnInput>((resolve) => { resolveOriginal = resolve; });
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/turns/r1/input' ? pending : base(url));
    mount();
    await screen.findByText('可导一定连续。');
    fireEvent.click(screen.getAllByRole('button', { name: '重新生成' })[0]);
    fireEvent.click(screen.getByRole('button', { name: /^积分换元法 2 条消息/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('积分换元法'));
    await act(async () => resolveOriginal(original));
    expect(streamChatMock).not.toHaveBeenCalled();
    expect(screen.queryByText('可导一定连续。')).toBeNull();
    expect(screen.getByRole('button', { name: '发送' })).toBeEnabled();
  });

  it('原附件无法读取时显示原因，保留历史且不发送缺附件的问题', async () => {
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/turns/r1/input'
      ? Promise.reject(new ApiError('RETRY_INPUT_UNAVAILABLE', '原提问或附件无法完整读取，无法重新生成', 409)) : base(url));
    mount();
    await screen.findByText('可导一定连续。');
    fireEvent.click(screen.getAllByRole('button', { name: '重新生成' })[0]);
    await screen.findByText('原提问或附件无法完整读取，无法重新生成');
    expect(streamChatMock).not.toHaveBeenCalled();
    expect(document.querySelectorAll('.chat-message.assistant')).toHaveLength(2);
    expect(screen.getByText('可导一定连续。')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '重新生成' })[0]).toBeEnabled();
  });

  it('当前会话正在后台回复时，旧回答上的重新生成禁用', async () => {
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url.startsWith('/api/ai/sessions/runtime')
      ? Promise.resolve({ sessions: [{ sessionId: 10, requestId: 'running', status: 'RUNNING', queueCount: 0 }] }) : base(url));
    mount();
    await screen.findByText('可导一定连续。');
    await waitFor(() => expect(screen.getAllByRole('button', { name: '重新生成' })[0]).toBeDisabled());
    fireEvent.click(screen.getAllByRole('button', { name: '重新生成' })[0]);
    expect(apiGet.mock.calls.some(([url]) => String(url).endsWith('/input'))).toBe(false);
    expect(streamChatMock).not.toHaveBeenCalled();
  });
});

describe('面板宽度', () => {
  it('会话栏与详情栏可用分隔条调整并各自记住；浏览器调试里没有固定按钮', async () => {
    localStorage.setItem('ai.window.sideWidth', '300');
    mount();
    await screen.findByText('可导一定连续。');
    const root = document.querySelector<HTMLElement>('.ai-window')!;
    expect(root.style.getPropertyValue('--ai-side-width')).toBe('300px');
    const head = document.querySelector('.ai-window-head')!;
    // 顶栏不放会话置顶；「固定」只在桌面应用里出现（见下方用例）
    expect(within(head as HTMLElement).queryByRole('button', { name: /置顶|固定/ })).toBeNull();
    const side = screen.getByRole('separator', { name: '调整会话栏宽度' });
    expect(side).toHaveAttribute('aria-valuenow', '300');
    fireEvent.keyDown(side, { key: 'ArrowRight' });
    expect(root.style.getPropertyValue('--ai-side-width')).toBe('316px');
    expect(localStorage.getItem('ai.window.sideWidth')).toBe('316');
    fireEvent.keyDown(side, { key: 'ArrowLeft' });
    fireEvent.keyDown(side, { key: 'ArrowLeft' });
    expect(localStorage.getItem('ai.window.sideWidth')).toBe('284');
    for (let index = 0; index < 20; index += 1) fireEvent.keyDown(side, { key: 'ArrowLeft' });
    expect(localStorage.getItem('ai.window.sideWidth')).toBe('200');
    fireEvent.doubleClick(side);
    expect(root.style.getPropertyValue('--ai-side-width')).toBe('264px');
    expect(localStorage.getItem('ai.window.sideWidth')).toBe('264');

    expect(screen.queryByRole('separator', { name: '调整详情栏宽度' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /上下文占用/ }));
    fireEvent.click(screen.getByRole('button', { name: '查看本轮上下文' }));
    const detail = screen.getByRole('separator', { name: '调整详情栏宽度' });
    // 详情栏的分隔条在左侧：向左是变宽
    fireEvent.keyDown(detail, { key: 'ArrowLeft' });
    expect(root.style.getPropertyValue('--ai-detail-width')).toBe('336px');
    expect(localStorage.getItem('ai.window.detailWidth')).toBe('336');
    expect(localStorage.getItem('ai.window.sideWidth')).toBe('264');
  });

  it('拖动分隔条时指针离开分隔条也跟手，按抬手位置定宽度', async () => {
    mount();
    await screen.findByText('可导一定连续。');
    const root = document.querySelector<HTMLElement>('.ai-window')!;
    const side = screen.getByRole('separator', { name: '调整会话栏宽度' });
    fireEvent.pointerDown(side, { button: 0, clientX: 300 });
    expect(side).toHaveClass('is-dragging');
    // 拖动过程中的中间位置照常生效
    fireEvent.pointerMove(window, { clientX: 340 });
    expect(root.style.getPropertyValue('--ai-side-width')).toBe('304px');
    // 最后一次移动丢了也不怕：抬手的位置才是最终宽度
    fireEvent.pointerUp(window, { clientX: 370 });
    expect(root.style.getPropertyValue('--ai-side-width')).toBe('334px');
    expect(localStorage.getItem('ai.window.sideWidth')).toBe('334');
    expect(side).not.toHaveClass('is-dragging');
    // 拖动结束后不再跟手
    fireEvent.pointerMove(window, { clientX: 500 });
    expect(root.style.getPropertyValue('--ai-side-width')).toBe('334px');
    // 取消（如指针被系统拿走）保留最后一次移动的位置，不用取消时的坐标
    fireEvent.pointerDown(side, { button: 0, clientX: 300 });
    fireEvent.pointerMove(window, { clientX: 320 });
    fireEvent.pointerCancel(window, { clientX: 900 });
    expect(root.style.getPropertyValue('--ai-side-width')).toBe('354px');
    expect(localStorage.getItem('ai.window.sideWidth')).toBe('354');
  });

  it('记住的宽度无效时回到默认；窄窗口下不提供分隔条', async () => {
    localStorage.setItem('ai.window.sideWidth', '9999');
    localStorage.setItem('ai.window.detailWidth', 'abc');
    Object.defineProperty(window, 'innerWidth', { value: 480, configurable: true });
    mount();
    await screen.findByText('可导一定连续。');
    const root = document.querySelector<HTMLElement>('.ai-window')!;
    expect(root.style.getPropertyValue('--ai-side-width')).toBe('264px');
    expect(root.style.getPropertyValue('--ai-detail-width')).toBe('320px');
    fireEvent.click(screen.getByRole('button', { name: '展开会话栏' }));
    expect(screen.queryByRole('separator')).toBeNull();
  });
});

describe('固定、改名与引用', () => {
  it('固定是窗口级开关：读取并跟随主进程状态，点击后交给主进程，不影响会话置顶', async () => {
    let listener: ((state: { visible: boolean; running: number; pending: number; fixed: boolean }) => void) | undefined;
    const setFixed = vi.fn().mockResolvedValue(undefined);
    const api = { assistant: { getState: vi.fn().mockResolvedValue({ visible: true, running: 0, pending: 0, fixed: true }), setFixed,
      attention: vi.fn().mockResolvedValue(undefined), onActivated: vi.fn(() => () => undefined),
      onState: vi.fn((next: typeof listener) => { listener = next; return () => { listener = undefined; }; }) } };
    Object.defineProperty(window, 'api', { value: api, configurable: true });
    try {
      mount();
      await screen.findByText('可导一定连续。');
      const head = within(document.querySelector<HTMLElement>('.ai-window-head')!);
      const button = head.getByRole('button', { name: '固定窗口' });
      await waitFor(() => expect(button).toHaveAttribute('aria-pressed', 'true'));
      expect(button).toHaveClass('is-on');
      fireEvent.click(button);
      expect(setFixed).toHaveBeenCalledWith(false);
      expect(button).toHaveAttribute('aria-pressed', 'false');
      // 主进程回报的状态为准
      act(() => listener?.({ visible: true, running: 0, pending: 0, fixed: true }));
      expect(button).toHaveAttribute('aria-pressed', 'true');
      expect(apiPut).not.toHaveBeenCalled();
      expect(head.queryByRole('button', { name: /置顶/ })).toBeNull();
    } finally { Reflect.deleteProperty(window, 'api'); }
  });

  it('双击标题就地改名：Enter 或失焦保存，Esc 取消，没改动或为空不提交', async () => {
    apiPut.mockImplementation(async (_url: string, body: { title: string }) => ({ ...sessions[0], title: body.title }));
    mount();
    await screen.findByText('可导一定连续。');
    const title = (): HTMLElement => screen.getByRole('button', { name: '切换会话' });
    fireEvent.doubleClick(title());
    const input = screen.getByRole('textbox', { name: '会话名称' }) as HTMLInputElement;
    expect(input).toHaveValue('导数与连续性');
    expect(input).toHaveFocus();
    // 进入改名时整段选中，直接输入即可替换
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, '导数与连续性'.length]);
    fireEvent.change(input, { target: { value: '  极限与导数  ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/sessions/10', { title: '极限与导数' }));
    expect(screen.queryByRole('textbox', { name: '会话名称' })).toBeNull();
    apiPut.mockClear();
    // Esc 取消
    fireEvent.doubleClick(title());
    fireEvent.change(screen.getByRole('textbox', { name: '会话名称' }), { target: { value: '不要这个' } });
    fireEvent.keyDown(screen.getByRole('textbox', { name: '会话名称' }), { key: 'Escape' });
    expect(screen.queryByRole('textbox', { name: '会话名称' })).toBeNull();
    // 清空后失焦、内容没变都不提交
    fireEvent.doubleClick(title());
    fireEvent.change(screen.getByRole('textbox', { name: '会话名称' }), { target: { value: '   ' } });
    fireEvent.blur(screen.getByRole('textbox', { name: '会话名称' }));
    fireEvent.doubleClick(title());
    fireEvent.blur(screen.getByRole('textbox', { name: '会话名称' }));
    expect(apiPut).not.toHaveBeenCalled();
    // 失焦保存
    fireEvent.doubleClick(title());
    fireEvent.change(screen.getByRole('textbox', { name: '会话名称' }), { target: { value: '失焦保存' } });
    fireEvent.blur(screen.getByRole('textbox', { name: '会话名称' }));
    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/sessions/10', { title: '失焦保存' }));
  });

  it('新会话页没有可改的名字，双击不进入改名', async () => {
    mount();
    await screen.findByText('可导一定连续。');
    fireEvent.keyDown(window, { key: 'n', ctrlKey: true });
    fireEvent.doubleClick(screen.getByRole('button', { name: '切换会话' }));
    expect(screen.queryByRole('textbox', { name: '会话名称' })).toBeNull();
  });

  it('选中回答里的文字后出现复制与引用；引用以引用块写入输入框并保留已有草稿', async () => {
    expect(quoteBlock('第一行\n\n第三行')).toBe('> 第一行\n>\n> 第三行');
    mount();
    const reply = await screen.findByText('可导一定连续。');
    const stream = document.querySelector<HTMLElement>('.ai-window-stream')!;
    const select = (node: Node, start: number, end: number): void => {
      const range = document.createRange();
      range.setStart(node.firstChild!, start); range.setEnd(node.firstChild!, end);
      // jsdom 不做排版：给选区一个固定位置
      range.getBoundingClientRect = () => ({ left: 200, top: 300, right: 260, bottom: 318, width: 60, height: 18, x: 200, y: 300, toJSON: () => ({}) });
      const selection = window.getSelection()!;
      selection.removeAllRanges(); selection.addRange(range);
    };
    expect(screen.queryByRole('toolbar', { name: '选中文本操作' })).toBeNull();
    select(reply, 0, 4);
    fireEvent.mouseUp(stream);
    const bar = screen.getByRole('toolbar', { name: '选中文本操作' });
    fireEvent.click(within(bar).getByRole('button', { name: '复制' }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('可导一定');
    expect(screen.queryByRole('toolbar', { name: '选中文本操作' })).toBeNull();

    const input = screen.getByPlaceholderText(/提问…/) as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: '已有草稿' } });
    select(reply, 0, 4);
    fireEvent.mouseUp(stream);
    fireEvent.click(within(screen.getByRole('toolbar', { name: '选中文本操作' })).getByRole('button', { name: '引用' }));
    expect(input).toHaveValue('已有草稿\n\n> 可导一定\n');
    expect(screen.queryByRole('toolbar', { name: '选中文本操作' })).toBeNull();
    expect(input).toHaveFocus();

    // 没有选中内容、Esc、滚动都会收起浮层
    select(reply, 0, 4);
    fireEvent.mouseUp(stream);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('toolbar', { name: '选中文本操作' })).toBeNull();
    select(reply, 0, 4);
    fireEvent.mouseUp(stream);
    fireEvent.scroll(stream);
    expect(screen.queryByRole('toolbar', { name: '选中文本操作' })).toBeNull();
    window.getSelection()!.removeAllRanges();
    fireEvent.mouseUp(stream);
    expect(screen.queryByRole('toolbar', { name: '选中文本操作' })).toBeNull();
  });
});
