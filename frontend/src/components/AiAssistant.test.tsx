import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { AiAssistant } from './AiAssistant';
import { useUiStore, type AiPageContext } from '../stores/uiStore';
import { isUuidV7 } from '../lib/uuidV7';
import { LS_RETRIEVE_NOTES } from '../lib/sessionPrefs';
import type { StreamCallbacks, StreamDonePayload } from '../lib/aiStream';

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));

// 此组验证输入/结果渲染，复用既有响应 fixture；SSE 生命周期由 stream 专项覆盖。
vi.mock('../lib/aiStream', () => ({
  streamChat: async (_path: string, body: unknown, callbacks: StreamCallbacks) => {
    void apiPost('/api/ai/chat', body).then((view: StreamDonePayload) => callbacks.onDone({ ...view, reasoning: view.reasoning ?? '' }));
    return () => undefined;
  }
}));

vi.mock('../lib/api', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: vi.fn(),
  del: vi.fn()
}));

function LocationProbe(): JSX.Element {
  const location = useLocation();
  return <div data-testid="loc">{location.pathname}{location.search}</div>;
}

const renderAssistant = (queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) => {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AiAssistant />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>
  );
};

const setContext = (context: AiPageContext): void => {
  useUiStore.setState({ aiOpen: true, pageContext: context });
};

interface V2Body {
  protocolVersion: number;
  requestId: string;
  sessionId: number;
  messages?: unknown;
  input: { role: string; userText: string };
  pageContext?: { kind: string; pageId?: number; markdown?: string };
  pageSnapshot?: { kind: string; pageId?: number; markdown?: string; notebookId?: number; capturedAt?: string };
  retrievalOptions?: { enabled: boolean; scope: string; notebookId?: number };
}

const chatBody = (): V2Body => {
  const call = apiPost.mock.calls.find((item) => item[0] === '/api/ai/chat');
  expect(call, 'expected a /api/ai/chat call').toBeTruthy();
  return call![1] as V2Body;
};

const typeAndSend = async (text: string): Promise<void> => {
  await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
  fireEvent.change(screen.getByPlaceholderText(/提问/), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
  await waitFor(() => expect(apiPost.mock.calls.some((item) => item[0] === '/api/ai/chat')).toBe(true));
};

describe('AiAssistant v2 请求组装（按需读取页面）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    apiGet.mockImplementation((url: string) => {
      // 正文非流式仍使用事件通道，这里仅考察请求体与最终响应渲染。
      if (url === '/api/ai/config') return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm', streaming: false } });
      if (url === '/api/notebooks') return Promise.resolve([{ id: 1, title: 'NB' }]);
      if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
      if (url.startsWith('/api/ai/sessions/') && url.includes('/history')) {
        return Promise.resolve({ sessionId: 10, messages: [], historyRevision: 0, hasMore: false, limit: 50 });
      }
      if (url.startsWith('/api/notebooks/')) return Promise.resolve([]);
      return Promise.resolve({});
    });
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/chat') return Promise.resolve({ reply: 'ANSWER', sessionId: 10 });
      return Promise.resolve({});
    });
    setContext({ kind: 'none', title: '无页面上下文', markdown: '' });
  });

  it('页面正文只放本地快照，不走自动注入字段', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: 'QUIZ MD' });
    renderAssistant();
    await typeAndSend('你好');
    const body = chatBody();
    expect(body.protocolVersion).toBe(2);
    expect(body.retrievalOptions).toBeUndefined();
    // v2 前端禁止再拼"你是学习助手"system，也不回传历史（contracts §4.1）
    expect(body.messages).toBeUndefined();
    expect(body.input).toEqual({ role: 'user', userText: '你好' });
    expect(body.pageContext).toBeUndefined();
    expect(body.pageSnapshot).toMatchObject({ kind: 'quiz', markdown: 'QUIZ MD' });
    expect(Number.isFinite(Date.parse(body.pageSnapshot!.capturedAt!))).toBe(true);
  });

  it('旧检索偏好不再注入资料，笔记快照保留正文和目标', async () => {
    setContext({ kind: 'note', title: '页面', markdown: 'NOTE MD', notebookId: 7, notePageId: 5 });
    localStorage.setItem(LS_RETRIEVE_NOTES, 'true');
    renderAssistant();
    await typeAndSend('你好');
    const body = chatBody();
    expect(body.retrievalOptions).toBeUndefined();
    expect(body.pageContext).toBeUndefined();
    expect(body.pageSnapshot).toMatchObject({ kind: 'note', pageId: 5, notebookId: 7, markdown: 'NOTE MD' });
  });

  it('空页面不会自动检索全库或恢复旧注入字段', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    localStorage.setItem(LS_RETRIEVE_NOTES, 'true');
    renderAssistant();
    await typeAndSend('你好');
    const body = chatBody();
    expect(body.retrievalOptions).toBeUndefined();
    expect(body.pageContext).toBeUndefined();
    expect(body.pageSnapshot).toMatchObject({ kind: 'quiz', markdown: '' });
  });

  it('每个逻辑请求带一个 UUIDv7 身份，服务端据此做幂等', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    renderAssistant();
    await typeAndSend('你好');

    expect(isUuidV7(chatBody().requestId)).toBe(true);
  });

  it('附件走 v2 独立通道：正文不再内联文件内容，data URL 前缀剥掉，clientId 短到服务端能收', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());

    const longName = '第三章-拉格朗日中值定理-课堂笔记整理版本-含板书与课后习题详解-2026-10-12-修订二.md';
    const textContent = "# 中值定理\nf(b)-f(a)=f'(c)(b-a)";
    // jsdom 的 File 没有 .text()（Chromium 有），测试里补这一端能力，被测代码不动
    const originalText = File.prototype.text;
    Object.defineProperty(File.prototype, 'text', { configurable: true, value: () => Promise.resolve(textContent) });
    const textFile = new File(['# 中值定理'], longName, { type: 'text/markdown' });
    const pngBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    const imageFile = new File([pngBytes], '截图.png', { type: 'image/png' });
    const input = document.querySelector<HTMLInputElement>('input[type=file]');
    expect(input).not.toBeNull();
    fireEvent.change(input as HTMLInputElement, { target: { files: [textFile, imageFile] } });

    await screen.findByText(longName);
    await screen.findByText('截图.png');
    fireEvent.change(screen.getByPlaceholderText(/提问/), { target: { value: '一起看' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(apiPost.mock.calls.some((item) => item[0] === '/api/ai/chat')).toBe(true));

    const body = chatBody();
    expect(body.input.userText).toBe('一起看');
    const attachments = (body.input as { attachments?: unknown[] }).attachments;
    expect(attachments).toHaveLength(2);
    expect(attachments![0]).toEqual({
      clientId: 'c0', kind: 'text', name: longName, mimeType: 'text/markdown',
      text: "# 中值定理\nf(b)-f(a)=f'(c)(b-a)"
    });
    const image = attachments![1] as { clientId: string; kind: string; dataBase64?: string; text?: string };
    expect(image.clientId).toBe('c1');
    expect(image.kind).toBe('image');
    expect(image.text).toBeUndefined();
    expect(image.dataBase64).toBe('iVBORw0KGgo=');
  });

  it('一次选 5 个文件也只收 4 个：逐项按已收下的重算门限，拒绝理由只说一次', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());

    Object.defineProperty(File.prototype, 'text', { configurable: true, value: () => Promise.resolve('内容') });
    const files = Array.from({ length: 5 }, (_, index) => new File(['内容'], `笔记-${index + 1}.md`, { type: 'text/markdown' }));
    const input = document.querySelector<HTMLInputElement>('input[type=file]');
    fireEvent.change(input as HTMLInputElement, { target: { files } });

    await screen.findByText('笔记-4.md');
    expect(document.querySelectorAll('.attachment-chip')).toHaveLength(4);
    expect(screen.queryByText('笔记-5.md')).toBeNull();
    expect(await screen.findAllByText('一次最多 4 个附件')).toHaveLength(1);
  });

  it('同名同时间的两个文件各有各的 key：移除一个不连带另一个', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());

    Object.defineProperty(File.prototype, 'text', { configurable: true, value: () => Promise.resolve('内容') });
    const files = [0, 1].map(() => new File(['内容'], '同一份.md', { type: 'text/markdown', lastModified: 123 }));
    const input = document.querySelector<HTMLInputElement>('input[type=file]');
    fireEvent.change(input as HTMLInputElement, { target: { files } });

    await waitFor(() => expect(document.querySelectorAll('.attachment-chip')).toHaveLength(2));
    fireEvent.click(screen.getAllByRole('button', { name: '移除同一份.md' })[0]);
    await waitFor(() => expect(document.querySelectorAll('.attachment-chip')).toHaveLength(1));
  });

  it('文本门限按本轮合计算：两段单独都合法，加起来超了就收不下第二段', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());

    const big = 'a'.repeat(150_000);
    Object.defineProperty(File.prototype, 'text', {
      configurable: true,
      value: function (this: File) { return Promise.resolve(this.name === '第一段.md' ? big : 'b'.repeat(50_001)); }
    });
    const files = [new File(['x'], '第一段.md', { type: 'text/markdown' }), new File(['y'], '第二段.md', { type: 'text/markdown' })];
    const input = document.querySelector<HTMLInputElement>('input[type=file]');
    fireEvent.change(input as HTMLInputElement, { target: { files } });

    await screen.findByText('第一段.md');
    expect(screen.queryByText('第二段.md')).toBeNull();
    expect(await screen.findByText('文本附件合计不能超过 20 万字')).toBeTruthy();
  });

  it.each([
    ['note', '/notebooks?pageId=12'],
    ['notebook', '/notebooks?notebookId=12'],
    ['bank', '/banks?bankId=12'],
    ['knowledge', '/knowledge?pointId=12&bankId=7'],
    ['question', '/banks?bankId=7&questionId=12'],
    ['wrong', '/banks?bankId=7&questionId=12']
  ])('工具返回的 %s 来源可打开普通资料页面', async (type, route) => {
    const originalGet = apiGet.getMockImplementation()!;
    apiGet.mockImplementation((url: string) => url === '/api/questions/12' || url === '/api/knowledge-points/12'
      ? Promise.resolve({ id: 12, bankId: 7 }) : originalGet(url));
    apiPost.mockImplementation((url: string) => Promise.resolve(url === '/api/ai/chat'
      ? { reply: `[资料来源](drill-notebook://material/${type}/12)`, sessionId: 10 } : {}));
    renderAssistant();
    await typeAndSend('打开资料');
    fireEvent.click(await screen.findByRole('link', { name: '资料来源' }));
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe(route));
    expect(useUiStore.getState().aiOpen).toBe(false);
  });

  it('资料保存活动刷新目录并提供导航，同时保留现有编辑草稿', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(['note-pages', 7], []);
    client.setQueryData(['note-page', 12], { content: '尚未保存的草稿' });
    apiPost.mockImplementation((url: string) => Promise.resolve(url === '/api/ai/chat' ? {
      reply: '已保存', sessionId: 10, toolActivities: [{ sequence: 1, toolName: 'create_material', state: 'FINISHED', outcome: 'CREATED',
        count: 1, sourceLink: 'drill-notebook://material/note/12', incomplete: false }]
    } : {}));
    renderAssistant(client);
    await typeAndSend('保存为一页新笔记');
    fireEvent.click(await screen.findByRole('button', { name: /已保存学习资料/ }));
    fireEvent.click(screen.getByRole('button', { name: /新增学习资料 已保存/ }));
    await waitFor(() => expect(client.getQueryState(['note-pages', 7])?.isInvalidated).toBe(true));
    expect(client.getQueryState(['note-page', 12])?.isInvalidated).toBe(false);
    expect(client.getQueryData(['note-page', 12])).toEqual({ content: '尚未保存的草稿' });
    fireEvent.click(screen.getByRole('button', { name: '查看资料 ↗' }));
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/notebooks?pageId=12'));
  });

  it('citation 点击深链到 /notebooks?pageId=', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/chat') {
        return Promise.resolve({
          reply: 'ANSWER',
          sessionId: 10,
          citations: [{
            corpusType: 'NOTEBOOK', notebookId: 7, pageId: 5, chunkId: 1,
            title: '第五页', headingPath: '第一章 > 要点', snippet: '片段内容',
            matchTypes: ['bm25'], ftsRank: 1, vectorRank: null, rrfScore: 0.01
          }]
        });
      }
      return Promise.resolve({});
    });
    setContext({ kind: 'note', title: '页面', markdown: 'NOTE MD', notebookId: 7, notePageId: 5 });
    localStorage.setItem(LS_RETRIEVE_NOTES, 'true');
    renderAssistant();
    await typeAndSend('你好');

    const chip = await screen.findByText('第五页');
    fireEvent.click(chip.closest('button')!);
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/notebooks?pageId=5'));
  });

  it('检索降级提示与回复同时显示，输入仍可用', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/chat') {
        return Promise.resolve({
          reply: 'ANSWER',
          sessionId: 10,
          // v2 的降级只剩码，文案由界面按契约码翻出（沿用旧的检索降级说法）
          contextUsage: { notices: ['RETRIEVAL_UNAVAILABLE'] },
          citations: [{
            corpusType: 'NOTEBOOK', notebookId: 7, pageId: 5, chunkId: 1,
            title: '第五页', headingPath: '', snippet: '片段', matchTypes: ['bm25']
          }]
        });
      }
      return Promise.resolve({});
    });
    setContext({ kind: 'note', title: '页面', markdown: 'NOTE MD', notebookId: 7, notePageId: 5 });
    localStorage.setItem(LS_RETRIEVE_NOTES, 'true');
    renderAssistant();
    await typeAndSend('你好');

    expect(await screen.findByText('ANSWER')).toBeInTheDocument();
    // 同一屏有笔记引用，降级文案就不能说"未使用笔记内容"
    expect(screen.getByText('笔记检索降级：本次只用了关键词召回结果')).toBeInTheDocument();
    expect(screen.getByText('第五页')).toBeInTheDocument();
    // notice 不阻止输入
    expect(screen.getByPlaceholderText(/提问/)).not.toBeDisabled();
  });

  it('非流式回复落库失败时明确显示未保存', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/chat') {
        return Promise.resolve({
          reply: 'ANSWER',
          sessionId: 10,
          stateDurable: false,
          persistenceStatus: 'failed'
        });
      }
      return Promise.resolve({});
    });
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    renderAssistant();
    await typeAndSend('你好');

    expect(await screen.findByText('ANSWER')).toBeInTheDocument();
    expect(screen.getByText('这一轮没有正常落库，历史记录可能不完整')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/提问/)).not.toBeDisabled();
  });
});
