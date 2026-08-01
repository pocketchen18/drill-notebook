import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { AiAssistant } from './AiAssistant';
import { useUiStore, type AiPageContext } from '../stores/uiStore';

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));

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

const renderAssistant = () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
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

const chatBody = (): { messages: Array<{ role: string; content: unknown }>; retrievalOptions?: unknown } => {
  const call = apiPost.mock.calls.find((item) => item[0] === '/api/ai/chat');
  expect(call, 'expected a /api/ai/chat call').toBeTruthy();
  return call![1];
};

const typeAndSend = async (text: string): Promise<void> => {
  await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
  fireEvent.change(screen.getByPlaceholderText(/提问/), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
  await waitFor(() => expect(apiPost.mock.calls.some((item) => item[0] === '/api/ai/chat')).toBe(true));
};

const toggleRetrieveNotes = (): void => {
  fireEvent.click(screen.getByLabelText('检索笔记'));
};

describe('AiAssistant notebook retrieval (Task 14)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/config') return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm' } });
      if (url === '/api/notebooks') return Promise.resolve([{ id: 1, title: 'NB' }]);
      if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: 'S' }]);
      if (url.startsWith('/api/ai/sessions/') && url.endsWith('/messages')) return Promise.resolve([]);
      if (url.startsWith('/api/notebooks/')) return Promise.resolve([]);
      return Promise.resolve({});
    });
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/chat') return Promise.resolve({ reply: 'ANSWER', sessionId: 10 });
      return Promise.resolve({});
    });
    setContext({ kind: 'none', title: '无页面上下文', markdown: '' });
  });

  it('disabled 回归：不发送 retrievalOptions，仍注入页面上下文 system message', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: 'QUIZ MD' });
    renderAssistant();
    await typeAndSend('你好');
    const body = chatBody();
    expect(body.retrievalOptions).toBeUndefined();
    expect(body.messages[0].role).toBe('system');
    expect(String(body.messages[0].content)).toContain('QUIZ MD');
  });

  it('note 页开启检索：默认 current + notebookId，且抑制整页 markdown system message', async () => {
    setContext({ kind: 'note', title: '页面', markdown: 'NOTE MD', notebookId: 7, notePageId: 5 });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    toggleRetrieveNotes();
    await typeAndSend('你好');
    const body = chatBody();
    expect(body.retrievalOptions).toEqual({ enabled: true, scope: 'current', notebookId: 7 });
    // 开启检索后不再重复注入整页 markdown
    expect(body.messages.every((m) => m.role !== 'system')).toBe(true);
    expect(body.messages[0].role).toBe('user');
  });

  it('无 notebookId 的页面开启检索：默认 all，绝不发送缺 notebookId 的 current', async () => {
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    toggleRetrieveNotes();
    await typeAndSend('你好');
    const body = chatBody();
    expect(body.retrievalOptions).toEqual({ enabled: true, scope: 'all' });
    expect((body.retrievalOptions as { notebookId?: number }).notebookId).toBeUndefined();
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
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    toggleRetrieveNotes();
    await typeAndSend('你好');

    const chip = await screen.findByText('第五页');
    fireEvent.click(chip.closest('button')!);
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/notebooks?pageId=5'));
  });

  it('BM25 降级 notice 与回复同时显示，输入仍可用', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/chat') {
        return Promise.resolve({
          reply: 'ANSWER',
          sessionId: 10,
          retrievalNotice: { code: 'vector-index-unavailable', message: '向量索引未就绪，本次仅关键词召回' },
          citations: [{
            corpusType: 'NOTEBOOK', notebookId: 7, pageId: 5, chunkId: 1,
            title: '第五页', headingPath: '', snippet: '片段', matchTypes: ['bm25']
          }]
        });
      }
      return Promise.resolve({});
    });
    setContext({ kind: 'note', title: '页面', markdown: 'NOTE MD', notebookId: 7, notePageId: 5 });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    toggleRetrieveNotes();
    await typeAndSend('你好');

    expect(await screen.findByText('ANSWER')).toBeInTheDocument();
    expect(screen.getByText('向量索引未就绪，本次仅关键词召回')).toBeInTheDocument();
    expect(screen.getByText('第五页')).toBeInTheDocument();
    // notice 不阻止输入
    expect(screen.getByPlaceholderText(/提问/)).not.toBeDisabled();
  });
});
