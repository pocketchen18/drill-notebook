import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { AiAssistant } from './AiAssistant';
import { useUiStore, type AiPageContext } from '../stores/uiStore';

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));

vi.mock('../lib/api', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: vi.fn(),
  del: vi.fn()
}));

const evidenceDir = process.env.DRILL_EVIDENCE_DIR;
const describeEvidence = evidenceDir ? describe : describe.skip;

function resolveFromCwd(relative: string): string {
  const candidates = [
    resolve(process.cwd(), relative),
    resolve(process.cwd(), '..', relative),
    resolve(process.cwd(), 'frontend', relative)
  ];
  const found = candidates.find((p) => existsSync(p));
  if (!found) throw new Error(`cannot resolve ${relative}; tried: ${candidates.join(', ')}`);
  return found;
}

function wrapHtml(bodyInner: string, theme: 'light' | 'dark'): string {
  const arcoCss = readFileSync(resolveFromCwd(join('node_modules', '@arco-design', 'web-react', 'dist', 'css', 'arco.css')), 'utf8');
  const appCss = readFileSync(resolveFromCwd(join('src', 'styles', 'app.css')), 'utf8');
  return `<!doctype html>
<html data-theme="${theme}" lang="zh">
<head>
<meta charset="utf-8" />
<style>${arcoCss}</style>
<style>${appCss}</style>
<style>body { min-height: 640px; background: var(--page-bg); padding: 16px; }
.arco-drawer-mask { display: none !important; }
.arco-drawer-wrapper { position: static !important; display: block !important; }
.arco-drawer { position: static !important; width: 100% !important; max-width: 540px; right: auto !important; }
.arco-drawer-scroll { height: auto !important; overflow: visible !important; }
.ai-fab { display: none !important; }</style>
</head>
<body>${bodyInner}</body>
</html>`;
}

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

const setContext = (context: AiPageContext): void => {
  useUiStore.setState({ aiOpen: true, pageContext: context });
};

const typeAndSend = async (text: string): Promise<void> => {
  await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
  fireEvent.change(screen.getByPlaceholderText(/提问/), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
};

function mockBaseGet(): void {
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/ai/config') return Promise.resolve({ chat: { hasKey: true, provider: 'custom', endpoint: 'mock://x', model: 'm' } });
    if (url === '/api/notebooks') return Promise.resolve([{ id: 7, title: '高等数学' }]);
    if (url === '/api/ai/sessions') return Promise.resolve([{ id: 10, title: '笔记检索演示' }]);
    if (url.startsWith('/api/ai/sessions/') && url.endsWith('/messages')) return Promise.resolve([]);
    if (url.startsWith('/api/notebooks/')) return Promise.resolve([]);
    return Promise.resolve({});
  });
}

describeEvidence('AiAssistant evidence (DRILL_EVIDENCE_DIR)', () => {
  it('task-14-sidebar-citation: 当前笔记本检索并返回引用（light）', async () => {
    localStorage.clear();
    mockBaseGet();
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/chat') {
        return Promise.resolve({
          reply: '根据笔记内容，泰勒公式的核心是用多项式逼近函数……',
          sessionId: 10,
          citations: [
            { corpusType: 'NOTEBOOK', notebookId: 7, pageId: 5, chunkId: 1, title: '泰勒公式', headingPath: '第三章 > 展开', snippet: '泰勒公式用多项式逼近', matchTypes: ['bm25', 'vector'], ftsRank: 1, vectorRank: 2, rrfScore: 0.03 },
            { corpusType: 'NOTEBOOK', notebookId: 7, pageId: 8, chunkId: 4, title: '中值定理', headingPath: '第二章', snippet: '拉格朗日中值定理', matchTypes: ['bm25'], ftsRank: 2, vectorRank: null, rrfScore: 0.02 }
          ]
        });
      }
      return Promise.resolve({});
    });
    setContext({ kind: 'note', title: '泰勒公式', markdown: '# 泰勒公式', notebookId: 7, notePageId: 5 });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    fireEvent.click(screen.getByLabelText('检索笔记'));
    await typeAndSend('讲解泰勒公式的重点');
    await screen.findByText('泰勒公式');
    await screen.findByText('中值定理');
    await waitFor(() => expect(document.querySelector('.ai-citation-chip')).not.toBeNull());

    mkdirSync(evidenceDir as string, { recursive: true });
    writeFileSync(join(evidenceDir as string, '_task14-sidebar-citation.html'), wrapHtml(document.body.innerHTML, 'light'), 'utf8');
  });

  it('task-14-sidebar-degraded: 无向量 BM25 降级 notice 与回复同显（dark）', async () => {
    localStorage.clear();
    mockBaseGet();
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/chat') {
        return Promise.resolve({
          reply: '（仅关键词召回）根据笔记，中值定理包含三类……',
          sessionId: 10,
          retrievalNotice: { code: 'vector-index-unavailable', message: '向量索引不可用，本次仅使用 BM25 关键词检索' },
          citations: [
            { corpusType: 'NOTEBOOK', notebookId: 7, pageId: 8, chunkId: 4, title: '中值定理', headingPath: '第二章', snippet: '拉格朗日中值定理', matchTypes: ['bm25'], ftsRank: 1, vectorRank: null, rrfScore: 0.02 }
          ]
        });
      }
      return Promise.resolve({});
    });
    setContext({ kind: 'quiz', title: '刷题', markdown: '' });
    renderAssistant();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    fireEvent.click(screen.getByLabelText('检索笔记'));
    await typeAndSend('中值定理有哪些');
    await screen.findByText('向量索引不可用，本次仅使用 BM25 关键词检索');
    await screen.findByText('中值定理');

    mkdirSync(evidenceDir as string, { recursive: true });
    writeFileSync(join(evidenceDir as string, '_task14-sidebar-degraded.html'), wrapHtml(document.body.innerHTML, 'dark'), 'utf8');
  });
});
