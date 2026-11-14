import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { AiAssistant, quoteBlock } from './AiAssistant';
import { SessionNavigation, sessionGroup } from './ai-session/SessionNavigation';
import { useUiStore } from '../stores/uiStore';
import { defaultShortcutConfig } from '../lib/shortcuts';
import type { AiChatSession } from '../lib/types';
import { ApiError } from '../lib/api';
import type { OriginalTurnInput } from '../hooks/useAiChatTurn';
import type { AssistantDraftDocument } from '../lib/assistantDrafts';
import type { Connections } from './ai-connections/types';
import { prepareForRestart } from '../lib/updatePreparation';
import type { StreamCallbacks } from '../lib/aiStream';

/** v0.8 助手窗口框架：会话栏、绑定页面、实际模型与用量、回到最新、窗口快捷键与详情栏。 */
const { apiGet, apiPost, apiPut, streamChatMock } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), streamChatMock: vi.fn() }));
vi.mock('../lib/aiStream', () => ({ streamChat: (...args: unknown[]) => streamChatMock(...args) }));
vi.mock('../lib/api', async (original) => ({ ...await original<typeof import('../lib/api')>(),
  get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args), put: (...args: unknown[]) => apiPut(...args) }));

const day = 86400000;
const iso = (offset: number): string => new Date(Date.now() - offset).toISOString();
const sessions: AiChatSession[] = [
  { id: 10, title: '导数与连续性', updatedAt: iso(60000), messageCount: 4, sessionLink: 'drill-notebook://session/' + 'a'.repeat(32), modelConfigId: 'm1', reasoningEffort: null, modelChoiceRevision: 1 },
  { id: 11, title: '积分换元法', updatedAt: iso(3 * day), messageCount: 2, sessionLink: 'drill-notebook://session/' + 'b'.repeat(32), modelConfigId: 'm1', reasoningEffort: null, modelChoiceRevision: 1 },
  { id: 12, title: '英语长难句', updatedAt: iso(40 * day), messageCount: 6, sessionLink: 'drill-notebook://session/' + 'c'.repeat(32), modelConfigId: 'm1', reasoningEffort: null, modelChoiceRevision: 1 }
];
const history = { sessionId: 10, historyRevision: 3, hasMore: false, limit: 50, totalRounds: 2, firstRound: 1, messages: [
  { id: 1, role: 'user', content: '讲解当前这页', requestId: 'r1', readable: true, turnStatus: 'COMPLETED', page: { title: '笔记 · 导数的定义', link: 'drill-notebook://material/note/7' } },
  { id: 2, role: 'assistant', content: '可导一定连续。', requestId: 'r1', readable: true, turnStatus: 'COMPLETED',
    model: 'DeepSeek · deepseek-reasoner', reasoningEffort: 'high', usage: { requests: 3, inputTokens: 18400, outputTokens: 1200 } },
  { id: 3, role: 'user', content: '再举个例子', requestId: 'r2', readable: true, turnStatus: 'COMPLETED' },
  { id: 4, role: 'assistant', content: '例如绝对值函数。', requestId: 'r2', readable: true, turnStatus: 'COMPLETED' }
] };

const connections = (): Connections => ({ revision: 1, chatModelId: 'm1', chatReasoningEffort: null, importModelId: null, providers: [{ id: 'p', name: 'DeepSeek',
  apiFormat: 'chat_completions', endpoint: 'x', authMode: 'api_key', hasKey: true, needsPassword: false, models: [
    { id: 'm1', modelId: 'deepseek-chat', name: '', group: '', streaming: false, tools: true, contextWindow: 128000, maxOutputTokens: 8000 },
    { id: 'm2', modelId: 'deepseek-reasoner', name: '', group: '', streaming: true, tools: true, contextWindow: 128000, maxOutputTokens: 8000, reasoningEfforts: ['low', 'high'] }] }] });

function Location(): JSX.Element { const location = useLocation(); return <span data-testid="loc">{location.pathname + location.search}</span>; }
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter><AiAssistant /><Location /></MemoryRouter></QueryClientProvider>);
}
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: Error) => void } {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  return { promise: new Promise<T>((next, fail) => { resolve = next; reject = fail; }), resolve: value => resolve(value), reject: error => reject(error) };
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
    if (url === '/api/ai/connections') return Promise.resolve(connections());
    return Promise.resolve({});
  });
});

describe('更新时的便携助手草稿', () => {
  const readDrafts = vi.fn();
  const saveDrafts = vi.fn();
  const composeInput = (): HTMLTextAreaElement => document.querySelector<HTMLTextAreaElement>('.ai-drawer-compose textarea')!;
  const saved = (): AssistantDraftDocument => ({ format: 1, selectedSessionId: null,
    choice: { modelConfigId: 'm2', reasoningEffort: 'high' }, titleDraft: null,
    drafts: [
      { sessionId: null, message: '新会话未发送的内容', scroll: 0,
        attachments: [{ id: 'text-1', name: '问题.txt', kind: 'text', value: '附件全文', mime: 'text/plain', bytes: 12, codepoints: 4 }] },
      { sessionId: 10, message: '导数会话草稿', attachments: [], scroll: 240 },
      { sessionId: 11, message: '积分会话草稿', attachments: [], scroll: 120 }
    ] });
  beforeEach(() => {
    readDrafts.mockResolvedValue(null); saveDrafts.mockResolvedValue(undefined);
    Object.defineProperty(window, 'api', { value: { update: { readAssistantDrafts: readDrafts, saveAssistantDrafts: saveDrafts } }, configurable: true });
  });
  afterEach(async () => { cleanup(); await Promise.resolve(); Reflect.deleteProperty(window, 'api'); });

  it('读取完成前禁用输入与快捷提示，恢复实际选中会话、文字和图片', async () => {
    const pending = deferred<AssistantDraftDocument>(); readDrafts.mockReturnValue(pending.promise);
    mount();
    const input = composeInput();
    expect(input).toBeDisabled();
    expect(screen.getByRole('button', { name: '添加附件' })).toBeDisabled();
    expect(await screen.findByRole('button', { name: '讲解当前内容' })).toBeDisabled();
    await expect(prepareForRestart(() => 0)).rejects.toThrow('尚未读取成功');
    expect(saveDrafts).not.toHaveBeenCalled();
    const document = saved(); document.selectedSessionId = 11;
    document.drafts[2].attachments = [{ id: 'image-1', name: '图.png', kind: 'image', value: 'data:image/png;base64,aGVsbG8=', mime: 'image/png', bytes: 5 }];
    await act(async () => pending.resolve(document));
    await waitFor(() => expect(input).toBeEnabled());
    expect(input).toHaveValue('积分会话草稿');
    expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('积分换元法');
    expect(screen.getByAltText('图.png')).toHaveAttribute('src', 'data:image/png;base64,aGVsbG8=');
    await prepareForRestart(() => 0);
    expect(saveDrafts.mock.calls.at(-1)?.[0]).toMatchObject({ selectedSessionId: 11, drafts: expect.arrayContaining([
      expect.objectContaining({ sessionId: 11, message: '积分会话草稿', attachments: document.drafts[2].attachments })
    ]) });
  });

  it('恢复草稿不覆盖数据库中的模型选择，切换会话保留各自草稿，更新前保存全部', async () => {
    readDrafts.mockResolvedValue(saved()); mount();
    const input = composeInput();
    await waitFor(() => expect(input).toHaveValue('新会话未发送的内容'));
    expect(screen.getByRole('button', { name: '模型：deepseek-chat' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('新会话');
    expect(screen.getByText('问题.txt')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^导数与连续性 4 条消息/ }));
    await waitFor(() => expect(input).toHaveValue('导数会话草稿'));
    fireEvent.change(input, { target: { value: '继续写的导数草稿' } });
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }));
    await waitFor(() => expect(input).toHaveValue('新会话未发送的内容'));
    await prepareForRestart(() => 0);
    expect(saveDrafts.mock.calls.at(-1)?.[0]).toMatchObject({ selectedSessionId: null, choice: { modelConfigId: 'm1', reasoningEffort: null },
      drafts: expect.arrayContaining([expect.objectContaining({ sessionId: 10, message: '继续写的导数草稿' }),
        expect.objectContaining({ sessionId: null, message: '新会话未发送的内容', attachments: saved().drafts[0].attachments })]) });
    expect(apiPost).not.toHaveBeenCalledWith('/api/ai/sessions', expect.anything());
  });

  it('保存串行执行，更新退出必须等待最后一份草稿落盘', async () => {
    const firstSave = deferred<void>(); saveDrafts.mockReturnValueOnce(firstSave.promise);
    readDrafts.mockResolvedValue(saved()); mount();
    const input = composeInput();
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '第一份内容' } });
    let completed = false;
    const first = prepareForRestart(() => 0).then(() => { completed = true; });
    await waitFor(() => expect(saveDrafts).toHaveBeenCalledTimes(1));
    expect(completed).toBe(false);
    fireEvent.change(input, { target: { value: '最后一份内容' } });
    const last = prepareForRestart(() => 0);
    await act(async () => { await Promise.resolve(); });
    expect(saveDrafts).toHaveBeenCalledTimes(1);
    await act(async () => { firstSave.resolve(undefined); await Promise.all([first, last]); });
    expect(saveDrafts.mock.calls.at(-1)?.[0].drafts).toEqual(expect.arrayContaining([
      expect.objectContaining({ sessionId: null, message: '最后一份内容' })
    ]));
  });

  it('损坏的草稿文件保留并阻止退出，重试读取后才允许编辑', async () => {
    const damaged = saved(); damaged.drafts.push(damaged.drafts[0]); readDrafts.mockResolvedValue(damaged);
    mount();
    expect(await screen.findByRole('alert')).toHaveTextContent('会话标识重复');
    expect(composeInput()).toBeDisabled();
    await expect(prepareForRestart(() => 0)).rejects.toThrow('尚未读取成功');
    expect(saveDrafts).not.toHaveBeenCalled();
    readDrafts.mockResolvedValue(saved()); fireEvent.click(screen.getByRole('button', { name: '重试' }));
    await waitFor(() => expect(composeInput()).toBeEnabled());
    expect(composeInput()).toHaveValue('新会话未发送的内容');
  });

  it('保存失败保留界面文字并阻止更新，重试成功保存原草稿', async () => {
    readDrafts.mockResolvedValue(saved()); saveDrafts.mockRejectedValue(new Error('磁盘写入失败')); mount();
    const input = composeInput();
    await waitFor(() => expect(input).toBeEnabled());
    fireEvent.change(input, { target: { value: '不能丢失的最新内容' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('磁盘写入失败');
    await expect(prepareForRestart(() => 0)).rejects.toThrow('磁盘写入失败');
    expect(input).toHaveValue('不能丢失的最新内容');
    saveDrafts.mockResolvedValue(undefined); fireEvent.click(screen.getByRole('button', { name: '重试' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    await prepareForRestart(() => 0);
    expect(saveDrafts.mock.calls.at(-1)?.[0].drafts).toEqual(expect.arrayContaining([
      expect.objectContaining({ sessionId: null, message: '不能丢失的最新内容' })
    ]));
  });

  it('附件读取期间阻止退出，读取结束后连同正文一起保存', async () => {
    readDrafts.mockResolvedValue(saved()); mount();
    await waitFor(() => expect(composeInput()).toBeEnabled());
    const reading = deferred<string>();
    const file = new File(['附件正文'], '慢速.txt', { type: 'text/plain' });
    Object.defineProperty(file, 'text', { value: () => reading.promise });
    fireEvent.change(document.querySelector('input[type="file"]')!, { target: { files: [file] } });
    await expect(prepareForRestart(() => 0)).rejects.toThrow('文件操作待确认');
    await act(async () => reading.resolve('附件正文'));
    await screen.findByText('慢速.txt');
    await prepareForRestart(() => 0);
    expect(saveDrafts.mock.calls.at(-1)?.[0].drafts).toEqual(expect.arrayContaining([expect.objectContaining({ sessionId: null,
      attachments: expect.arrayContaining([expect.objectContaining({ name: '慢速.txt', value: '附件正文' })]) })]));
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
  it('回答中的来源链接交给默认浏览器，保留当前会话和未发送草稿', async () => {
    const url = 'https://www.nvidia.com/en-us/about-nvidia/leadership/';
    const openExternal = vi.fn().mockResolvedValue(undefined);
    const originalGet = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((target: string) => target.startsWith('/api/ai/sessions/10/history')
      ? Promise.resolve({ ...history, messages: history.messages.map(item => item.id === 2
        ? { ...item, content: `来源：[NVIDIA Management Team](${url})` } : item) })
      : originalGet(target));
    Object.defineProperty(window, 'api', { value: { shell: { openExternal } }, configurable: true });
    try {
      mount();
      const link = await screen.findByRole('link', { name: 'NVIDIA Management Team' });
      const input = document.querySelector<HTMLTextAreaElement>('.ai-drawer-compose textarea')!;
      fireEvent.change(input, { target: { value: '我还没有发送的追问' } });
      expect(link).toHaveAttribute('title', url);
      fireEvent.click(link);
      expect(openExternal).toHaveBeenCalledExactlyOnceWith(url);
      expect(input).toHaveValue('我还没有发送的追问');
      expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('导数与连续性');
      expect(screen.getByTestId('loc')).toHaveTextContent(/^\/$/);
      expect(streamChatMock).not.toHaveBeenCalled();
    } finally { cleanup(); Reflect.deleteProperty(window, 'api'); }
  });

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

  it('每个会话独立保存模型和供应商，新会话继承最近选择，重启恢复各自选择', async () => {
    let saved = connections();
    saved.providers.push({ ...saved.providers[0], id: 'p2', name: '另一供应商', models: [{ ...saved.providers[0].models[0], id: 'm3', modelId: 'other-chat' }] });
    let storedSessions = sessions.map(session => ({ ...session }));
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/connections' ? Promise.resolve(saved)
      : url === '/api/ai/sessions' ? Promise.resolve(storedSessions) : base(url));
    apiPut.mockImplementation(async (_url: string, choice: { sessionId?: number; modelConfigId: string; reasoningEffort: string | null }) => {
      saved = { ...saved, revision: saved.revision + 1, chatModelId: choice.modelConfigId, chatReasoningEffort: choice.reasoningEffort };
      if (choice.sessionId === undefined) return saved;
      storedSessions = storedSessions.map(session => session.id === choice.sessionId ? { ...session,
        modelConfigId: choice.modelConfigId, reasoningEffort: choice.reasoningEffort, modelChoiceRevision: session.modelChoiceRevision! + 1 } : session);
      const session = storedSessions.find(item => item.id === choice.sessionId)!;
      return { ...saved, sessionChoice: { sessionId: session.id, modelConfigId: session.modelConfigId,
        reasoningEffort: session.reasoningEffort, revision: session.modelChoiceRevision } };
    });
    const view = mount();
    await screen.findByText('可导一定连续。');
    fireEvent.click(await screen.findByRole('button', { name: '模型：deepseek-chat' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /deepseek-reasoner/ }));
    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/connections/chat-model', { sessionId: 10, modelConfigId: 'm2', reasoningEffort: null, revision: 1 }));
    expect(await screen.findByRole('button', { name: '模型：deepseek-reasoner' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '思考档位：模型默认' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '思考档位：模型默认' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'high' }));
    await waitFor(() => expect(apiPut).toHaveBeenLastCalledWith('/api/ai/connections/chat-model', { sessionId: 10, modelConfigId: 'm2', reasoningEffort: 'high', revision: 2 }));
    await waitFor(() => expect(screen.getByRole('button', { name: '思考档位：high' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /^积分换元法 2 条消息/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('积分换元法'));
    expect(screen.getByRole('button', { name: '模型：deepseek-chat' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '模型：deepseek-reasoner' })).toBeEnabled());
    expect(screen.getByRole('button', { name: '思考档位：high' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '模型：deepseek-reasoner' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /other-chat/ }));
    await waitFor(() => expect(apiPut).toHaveBeenLastCalledWith('/api/ai/connections/chat-model', { modelConfigId: 'm3', reasoningEffort: null, revision: 3 }));
    await waitFor(() => expect(screen.getByRole('button', { name: '模型：other-chat' })).toBeEnabled());
    expect(apiPut.mock.calls.every(call => call[0] === '/api/ai/connections/chat-model')).toBe(true);
    expect(apiPost).not.toHaveBeenCalledWith('/api/ai/sessions', expect.anything());
    view.unmount(); mount();
    expect(await screen.findByRole('button', { name: '模型：deepseek-reasoner' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '思考档位：high' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '模型：other-chat' })).toBeEnabled());
  });

  it('换模型保存期间切到另一会话，迟到结果只更新原会话', async () => {
    const pending = deferred<Connections>(); apiPut.mockReturnValueOnce(pending.promise);
    mount(); await screen.findByText('可导一定连续。');
    fireEvent.click(screen.getByRole('button', { name: '模型：deepseek-chat' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /deepseek-reasoner/ }));
    fireEvent.click(screen.getByRole('button', { name: /^积分换元法 2 条消息/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '切换会话' })).toHaveTextContent('积分换元法'));
    await act(async () => pending.resolve({ ...connections(), revision: 2, chatModelId: 'm2',
      sessionChoice: { sessionId: 10, modelConfigId: 'm2', reasoningEffort: null, revision: 2 } }));
    await waitFor(() => expect(screen.getByRole('button', { name: '模型：deepseek-chat' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /^导数与连续性 4 条消息/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '模型：deepseek-reasoner' })).toBeEnabled());
  });

  it('选择保存期间不提交消息，失败保留原模型和输入，重试成功后再生效', async () => {
    let saved = connections();
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/connections' ? Promise.resolve(saved) : base(url));
    const pending = deferred<Connections>(); apiPut.mockReturnValueOnce(pending.promise);
    mount(); await screen.findByText('可导一定连续。');
    const input = document.querySelector<HTMLTextAreaElement>('.ai-drawer-compose textarea')!;
    fireEvent.change(input, { target: { value: '还没发送的提问' } });
    fireEvent.click(screen.getByRole('button', { name: '模型：deepseek-chat' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /deepseek-reasoner/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeDisabled());
    expect(screen.getByRole('button', { name: '模型：deepseek-chat' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(apiPost).not.toHaveBeenCalled();
    // 由服务端返回保存失败，模型不先行改变。
    await act(async () => pending.reject(new Error('模型选择未保存')));
    await waitFor(() => expect(screen.getByRole('button', { name: '模型：deepseek-chat' })).toBeEnabled());
    expect(input).toHaveValue('还没发送的提问');
    saved = { ...saved, revision: 2, chatModelId: 'm2' }; apiPut.mockResolvedValue(saved);
    fireEvent.click(screen.getByRole('button', { name: '模型：deepseek-chat' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /deepseek-reasoner/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '模型：deepseek-reasoner' })).toBeEnabled());
    expect(input).toHaveValue('还没发送的提问');
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

  it('圆形箭头在较早的原回答位置重试，沿用完整原输入，提问数量与草稿不变', async () => {
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
    expect(screen.queryByText('可导一定连续。')).toBeNull();
    expect(screen.getByText('例如绝对值函数。')).toBeInTheDocument();
    expect(document.querySelectorAll('.chat-message.user')).toHaveLength(2);
    expect(document.querySelectorAll('.chat-message.assistant')).toHaveLength(2);
    const callbacks = streamChatMock.mock.calls[0][2] as StreamCallbacks;
    act(() => { callbacks.onMetadata?.({ requestId: body.requestId }); callbacks.onText('原位置的新回答'); });
    const rows = [...document.querySelectorAll('.chat-message')];
    expect(rows.map(row => row.getAttribute('data-conversation-message'))).toEqual(['1', '2', '3', '4']);
    expect(rows[1]).toHaveTextContent('原位置的新回答');
    expect(rows[3]).toHaveTextContent('例如绝对值函数。');
    await act(async () => callbacks.onDone({ requestId: body.requestId, sessionId: 10, reply: '原位置的新回答', reasoning: '', status: 'COMPLETED', stateDurable: true, persistenceStatus: 'saved' }));
    expect(document.querySelectorAll('.chat-message.user')).toHaveLength(2);
    expect(document.querySelectorAll('.chat-message.assistant')).toHaveLength(2);
    expect(screen.queryByText(/请继续完成上一轮尚未完成的任务/)).toBeNull();
  });

  it('重试尚未开始就被拒绝时保留原回答，之后仍能重试原提问', async () => {
    const base = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/ai/turns/r1/input' ? Promise.resolve(original)
      : url.startsWith('/api/ai/turns/') ? Promise.reject(new ApiError('TURN_NOT_FOUND', '本次重试尚未开始', 404)) : base(url));
    apiPost.mockRejectedValue(new ApiError('HISTORY_STORAGE_FULL', '历史容量不足', 409));
    mount(); await screen.findByText('可导一定连续。');
    fireEvent.click(screen.getAllByRole('button', { name: '重新生成' })[0]);
    await waitFor(() => expect(streamChatMock).toHaveBeenCalledTimes(1));
    const callbacks = streamChatMock.mock.calls[0][2] as StreamCallbacks;
    await act(async () => callbacks.onError({ message: '历史容量不足', partial: false }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: '重新生成' })[0]).toBeEnabled());
    expect(screen.getByText('可导一定连续。')).toBeInTheDocument();
    expect(document.querySelectorAll('.chat-message.user')).toHaveLength(2);
    expect(document.querySelectorAll('.chat-message.assistant')).toHaveLength(2);
    fireEvent.click(screen.getAllByRole('button', { name: '重新生成' })[0]);
    await waitFor(() => expect(streamChatMock).toHaveBeenCalledTimes(2));
    expect(streamChatMock.mock.calls[1][1]).toMatchObject({ retryOf: 'r1', input: original.input });
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
    fireEvent.focus(screen.getByRole('slider', { name: '会话内对话跳转' }));
    await screen.findByRole('button', { name: /对话预览/ });
    select(reply, 0, 4);
    fireEvent.mouseUp(stream);
    const bar = screen.getByRole('toolbar', { name: '选中文本操作' });
    expect(screen.queryByRole('button', { name: /对话预览/ })).toBeNull();
    expect(screen.queryByRole('slider', { name: '会话内对话跳转' })).toBeNull();
    fireEvent.click(within(bar).getByRole('button', { name: '复制' }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('可导一定');
    expect(screen.queryByRole('toolbar', { name: '选中文本操作' })).toBeNull();
    expect(screen.getByRole('slider', { name: '会话内对话跳转' })).toBeVisible();

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
