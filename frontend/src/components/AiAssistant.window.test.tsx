import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { AiAssistant } from './AiAssistant';
import { SessionNavigation, sessionGroup } from './ai-session/SessionNavigation';
import { useUiStore } from '../stores/uiStore';
import { defaultShortcutConfig } from '../lib/shortcuts';
import type { AiChatSession } from '../lib/types';

/** v0.8 助手窗口框架：会话栏、绑定页面、实际模型与用量、回到最新、窗口快捷键与详情栏。 */
const { apiGet, apiPost, apiPut } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn() }));
vi.mock('../lib/aiStream', () => ({ streamChat: vi.fn() }));
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
  Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true });
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockResolvedValue(undefined) }, configurable: true });
  useUiStore.setState({ aiOpen: true, shortcutConfig: defaultShortcutConfig(),
    pageContext: { kind: 'note', title: '笔记 · 极限', markdown: '正文', route: '/notebooks', notePageId: 9 } });
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/ai/config') return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm', streaming: false } });
    if (url === '/api/ai/sessions') return Promise.resolve(sessions);
    if (url.startsWith('/api/ai/sessions/10/history')) return Promise.resolve(history);
    if (url === '/api/ai/sessions/10/context-usage') return Promise.resolve({ sessionId: 10, model: 'm', contextWindow: 128000, outputReserve: 8192, latest: null });
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
    fireEvent.click(screen.getAllByRole('button', { name: '本轮上下文' })[0]);
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
});
