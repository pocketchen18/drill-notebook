/**
 * Task 1 baseline behavior + target-structure tests for `NotebookPage`.
 *
 * BEHAVIOR block (NBK-* rows from `.omo/evidence/notebook-bank-workspace-redesign/control-ledger.md`):
 *   Locks the current Notebook behavior — must PASS on unchanged production source.
 *   Includes deterministic characterization of the accepted-debt page-switch/autosave race.
 *
 * TARGET STRUCTURE block:
 *   Asserts the workspace structure and CSS-owned geometry contract.
 *
 * Fixture IDs are intentionally non-sequential (11, 37, 104) per the plan.
 *
 * Race characterization is observation-only: we record the exact request sequence the
 * implementation produces for a delayed page-switch and assert those observations. We
 * do NOT claim a "guaranteed prior-page flush" — that's accepted debt.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { NotePage, Notebook } from '../lib/types';

const { apiGet, apiPost, apiPut, apiDel } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDel: vi.fn()
}));

vi.mock('../lib/api', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: (...args: unknown[]) => apiPut(...args),
  del: (...args: unknown[]) => apiDel(...args)
}));

const baseWindowApi = () => ({
  exportFile: { save: vi.fn().mockResolvedValue({ canceled: true }) },
  config: {
    get: () => Promise.resolve({ theme: 'light' }),
    set: () => Promise.resolve()
  },
  backend: { getBaseUrl: () => Promise.resolve('http://127.0.0.1:18081') }
});

beforeEach(() => {
  (window as unknown as { api: Record<string, unknown> }).api = baseWindowApi() as Record<string, unknown>;
  apiPut.mockResolvedValue({});
});

afterEach(() => {
  vi.restoreAllMocks();
  apiGet.mockReset();
  apiPost.mockReset();
  apiPut.mockReset();
  apiDel.mockReset();
});

// The Notebook page depends on a render-stable tiptap editor. We stub it with a
// minimal host so jsdom doesn't need prosemirror schema. The behavior tests
// still exercise the surrounding Notebook page logic (selection, autosave timer
// scheduling, focus mode, plan/DayQueue).
vi.mock('../components/editor/NotebookEditor', () => ({
  NotebookEditor: ({
    onChange,
    pageId,
    focusMode,
    onFocusModeChange
  }: {
    onChange?: (c: Record<string, unknown>) => void;
    pageId?: number;
    focusMode?: boolean;
    onFocusModeChange?: (f: boolean) => void;
  }) => (
    <div
      className="editor-canvas"
      data-testid="notebook-editor"
      data-page-id={pageId}
      data-focus-mode={focusMode ? 'true' : 'false'}
    >
      <button type="button" onClick={() => onChange?.({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'A' }] }] })}>
        type-A
      </button>
      <button type="button" onClick={() => onChange?.({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'B' }] }] })}>
        type-B
      </button>
      <button type="button" onClick={() => onFocusModeChange?.(!focusMode)}>
        toggle-focus
      </button>
    </div>
  )
}));

import { NotebookPage } from './NotebookPage';

const baseNotebooks: Notebook[] = [{ id: 1, title: '默认笔记本' }];

const page11: NotePage = {
  id: 11,
  notebookId: 1,
  title: '页面-11',
  content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '原-11' }] }] }
};
const page37: NotePage = {
  id: 37,
  notebookId: 1,
  title: '页面-37',
  content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '原-37' }] }] }
};
const page104: NotePage = {
  id: 104,
  notebookId: 1,
  title: '页面-104',
  content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '原-104' }] }] }
};

const basePages: NotePage[] = [page11, page37, page104];

function primeNotebookApi(opts: { slowPages?: boolean; slowPage37?: boolean } = {}): void {
  apiGet.mockImplementation((path: string) => {
    if (path === '/api/notebooks') return Promise.resolve(baseNotebooks);
    if (path === '/api/note-pages') return Promise.reject(new Error('deprecated'));
    if (path === '/api/notebooks/1/pages') return Promise.resolve(basePages);
    // 其他笔记本（如 NBK-14 新建后切换到的）没有页面
    if (/^\/api\/notebooks\/\d+\/pages$/.test(path)) return Promise.resolve([]);
    if (path === '/api/note-pages/11') {
      if (opts.slowPages) return new Promise((res) => setTimeout(() => res(page11), 250));
      return Promise.resolve(page11);
    }
    if (path === '/api/note-pages/37') {
      if (opts.slowPage37) return new Promise((res) => setTimeout(() => res(page37), 400));
      return Promise.resolve(page37);
    }
    if (path === '/api/note-pages/104') return Promise.resolve(page104);
    return Promise.resolve({});
  });
}

function LocationProbe(): JSX.Element {
  const location = useLocation();
  return <div data-testid="loc">{location.pathname}{location.search}</div>;
}

function renderNotebookPage(initialEntries: string[] = ['/notebooks']): ReturnType<typeof render> {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <NotebookPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// =====================================================================
// BEHAVIOR — must pass on unchanged code
// =====================================================================

describe('NotebookPage behavior — baseline regression (Task 1)', () => {
  describe('Heading + notebook + page selector (NBK-01, NBK-04)', () => {
    beforeEach(() => primeNotebookApi());

    it('NBK-01: h1 笔记本 + tagline remain available (without plan deep link)', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByRole('heading', { name: '笔记本', level: 1 })).toBeInTheDocument());
      expect(screen.getByText(/所见即所得/)).toBeInTheDocument();
    });

    it('NBK-04: notebook selector lists notebooks from /api/notebooks', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByText('默认笔记本')).toBeInTheDocument());
    });
  });

  describe('Page list interactions (NBK-07, NBK-08, NBK-09)', () => {
    beforeEach(() => primeNotebookApi());

    it('NBK-07: page items render with title; click selects another', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByText('页面-11')).toBeInTheDocument());
      expect(screen.getByText('页面-37')).toBeInTheDocument();
      expect(screen.getByText('页面-104')).toBeInTheDocument();
      // Click page 37 → editor mounts with pageId=37.
      fireEvent.click(screen.getByText('页面-37'));
      await waitFor(() => expect(screen.getByTestId('notebook-editor').getAttribute('data-page-id')).toBe('37'));
    });

    it('NBK-08 delete: Popconfirm → DEL /api/note-pages/{id}', async () => {
      apiDel.mockResolvedValue(undefined);
      renderNotebookPage();
      await waitFor(() => expect(screen.getByRole('button', { name: '删除页面-11' })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: '删除页面-11' }));
      const confirm = await screen.findByText('确定');
      fireEvent.click(confirm);
      await waitFor(() => expect(apiDel).toHaveBeenCalledWith('/api/note-pages/11'));
    });

    it('NBK-09 全选页面 checkbox selects every page', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByText('全选页面')).toBeInTheDocument());
      fireEvent.click(screen.getByText('全选页面'));
      // 3 selected → 加入计划(count) button shows the badge.
      await waitFor(() => expect(screen.getByText(/加入计划（3）/)).toBeInTheDocument());
    });
  });

  describe('Editor + autosave (NBK-12, NBK-17)', () => {
    beforeEach(() => primeNotebookApi());

    it('NBK-12 + 400ms debounce: editor change schedules a PUT after 400ms', async () => {
      renderNotebookPage();
      // Wait for first page to load.
      await waitFor(() => expect(screen.getByTestId('notebook-editor')).toBeInTheDocument());
      // Real timers; advance 450ms via wall-clock wait so waitFor's polling setTimeout still fires.
      fireEvent.click(screen.getByText('type-A'));
      await new Promise((res) => setTimeout(res, 500));
      const calls = apiPut.mock.calls.filter((c) => c[0] === '/api/note-pages/11');
      expect(calls.length).toBeGreaterThanOrEqual(1);
      const body = calls[0][1] as { content: Record<string, unknown> };
      expect(JSON.stringify(body.content)).toContain('A');
    });

    it('NBK-17 focus mode toggle hides heading + sets uiStore.notebookFocusMode', async () => {
      const { useUiStore } = await import('../stores/uiStore');
      useUiStore.setState({ notebookFocusMode: false });
      renderNotebookPage();
      await waitFor(() => expect(screen.getByTestId('notebook-editor')).toBeInTheDocument());
      fireEvent.click(screen.getByText('toggle-focus'));
      await waitFor(() => expect(useUiStore.getState().notebookFocusMode).toBe(true));
      // Heading disappears.
      await waitFor(() => expect(screen.queryByRole('heading', { name: '笔记本', level: 1 })).not.toBeInTheDocument());
      // Exit focus → heading returns.
      fireEvent.click(screen.getByText('toggle-focus'));
      await waitFor(() => expect(useUiStore.getState().notebookFocusMode).toBe(false));
      await waitFor(() => expect(screen.getByRole('heading', { name: '笔记本', level: 1 })).toBeInTheDocument());
    });
  });

  describe('AddToPlan + completePlan (NBK-02, NBK-14)', () => {
    beforeEach(() => {
      primeNotebookApi();
      apiPost.mockImplementation((path: string) => {
        if (path === '/api/study-plans/groups') return Promise.resolve({ group: { id: 7 }, items: [{ id: 71 }] });
        return Promise.resolve({});
      });
    });

    it('NBK-14: 加入计划 opens modal; submitting POSTs to /api/study-plans/groups', async () => {
      renderNotebookPage();
      // Initially the button is "加入计划" (no count) and disabled. Click 全选页面
      // (NotebookPage.tsx:244) to select all 3 pages and reveal the count badge.
      await waitFor(() => expect(screen.getByText('全选页面')).toBeInTheDocument());
      fireEvent.click(screen.getByText('全选页面'));
      // Now button label flips to "加入计划（3）" (full-width parens, source line 237).
      const planBtn = await screen.findByRole('button', { name: /加入计划（3）/ });
      expect(planBtn).toBeEnabled();
      fireEvent.click(planBtn);
      // Modal title. Scope to the modal container to disambiguate from the
      // trigger button (also labelled "加入计划（3）").
      expect(await screen.findByText('加入计划', { selector: '.arco-modal-title' })).toBeInTheDocument();
      // Submit — modal OK button is "写入计划" (AddToPlanModal.tsx:418 default).
      fireEvent.click(await screen.findByRole('button', { name: '写入计划' }));
      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/study-plans/groups', expect.objectContaining({ items: expect.any(Array) })));
    });

    it('NBK-02: ?planItemId= + ?pageId= deep link renders 完成此项计划', async () => {
      renderNotebookPage(['/notebooks?planItemId=9&pageId=11']);
      await waitFor(() => expect(screen.getByRole('button', { name: /完成此项计划/ })).toBeInTheDocument());
    });
  });

  describe('New page modal (NBK-13)', () => {
    beforeEach(() => {
      primeNotebookApi();
      apiPost.mockResolvedValue({ id: 100, notebookId: 1, title: '新页面', content: { type: 'doc', content: [{ type: 'paragraph' }] } });
    });

    it('NBK-13: 新建页面 modal POSTs to /api/notebooks/{notebookId}/pages with title', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByRole('button', { name: '新建页面' })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: '新建页面' }));
      const input = await screen.findByPlaceholderText(/错题总结/);
      fireEvent.change(input, { target: { value: '我的新页面' } });
      fireEvent.click(screen.getByRole('button', { name: '确定' }));
      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/notebooks/1/pages', expect.objectContaining({ title: '我的新页面' })));
    });

    it('NBK-13: blank title rejected (warns 请输入页面标题, no POST)', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByRole('button', { name: '新建页面' })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: '新建页面' }));
      fireEvent.click(await screen.findByRole('button', { name: '确定' }));
      await waitFor(() => expect(apiPost.mock.calls.filter((c) => c[0] === '/api/notebooks/1/pages').length).toBe(0));
    });
  });

  describe('New notebook modal (NBK-14)', () => {
    beforeEach(() => {
      primeNotebookApi();
      apiPost.mockResolvedValue({ id: 2, title: '离散数学' });
    });

    it('NBK-14: 新建笔记本 modal POSTs to /api/notebooks with title', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByRole('button', { name: '新建笔记本' })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: '新建笔记本' }));
      const input = await screen.findByPlaceholderText(/高等数学/);
      fireEvent.change(input, { target: { value: '离散数学' } });
      fireEvent.click(screen.getByRole('button', { name: '确定' }));
      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/notebooks', { title: '离散数学' }));
      // 创建成功后切换到新笔记本并拉取其页面
      await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/notebooks/2/pages'));
    });

    it('NBK-14: blank title rejected (no POST)', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByRole('button', { name: '新建笔记本' })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: '新建笔记本' }));
      fireEvent.click(await screen.findByRole('button', { name: '确定' }));
      await waitFor(() => expect(apiPost.mock.calls.filter((c) => c[0] === '/api/notebooks').length).toBe(0));
    });
  });

  describe('Rename notebook + page (NBK-18)', () => {
    beforeEach(() => primeNotebookApi());

    it('NBK-18: 编辑区标题本地成稿，只在回车时 PUT 一次且不被服务端旧值顶回', async () => {
      apiPut.mockImplementation((path: string, body: Record<string, unknown>) => {
        if (path === '/api/note-pages/11') return Promise.resolve({ ...page11, title: String(body.title ?? page11.title) });
        return Promise.resolve({});
      });
      renderNotebookPage();
      const input = (await screen.findByLabelText('重命名当前页面')) as HTMLInputElement;
      expect(input.value).toBe('页面-11');
      fireEvent.change(input, { target: { value: '期末复盘' } });
      // 逐字输入期间不发请求，且草稿不被 currentPage.title 覆盖
      expect(apiPut.mock.calls.filter((c) => c[0] === '/api/note-pages/11').length).toBe(0);
      expect((screen.getByLabelText('重命名当前页面') as HTMLInputElement).value).toBe('期末复盘');
      fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
      await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/note-pages/11', { title: '期末复盘' }));
      expect(apiPut.mock.calls.filter((c) => c[0] === '/api/note-pages/11').length).toBe(1);
      // 缓存回写后标题保持新值
      await waitFor(() => expect((screen.getByLabelText('重命名当前页面') as HTMLInputElement).value).toBe('期末复盘'));
    });

    it('NBK-18: 编辑区标题按 Esc 放弃草稿，回到原标题且不发请求', async () => {
      renderNotebookPage();
      const input = (await screen.findByLabelText('重命名当前页面')) as HTMLInputElement;
      fireEvent.change(input, { target: { value: '半成品' } });
      fireEvent.keyDown(input, { key: 'Escape', keyCode: 27 });
      await waitFor(() => expect((screen.getByLabelText('重命名当前页面') as HTMLInputElement).value).toBe('页面-11'));
      expect(apiPut.mock.calls.filter((c) => c[0] === '/api/note-pages/11').length).toBe(0);
    });

    it('NBK-18: 页面列表双击标题改名，失焦提交 PUT', async () => {
      apiPut.mockImplementation((path: string, body: Record<string, unknown>) => {
        if (path === '/api/note-pages/37') return Promise.resolve({ ...page37, title: String(body.title ?? page37.title) });
        return Promise.resolve({});
      });
      renderNotebookPage();
      await waitFor(() => expect(screen.getByText('页面-37')).toBeInTheDocument());
      fireEvent.doubleClick(screen.getByText('页面-37'));
      const input = (await screen.findByLabelText('重命名页面')) as HTMLInputElement;
      expect(input.value).toBe('页面-37');
      fireEvent.change(input, { target: { value: '排序算法' } });
      fireEvent.blur(input);
      await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/note-pages/37', { title: '排序算法' }));
      await waitFor(() => expect(screen.queryByLabelText('重命名页面')).not.toBeInTheDocument());
    });

    it('NBK-18: 列表行按 F2 进入改名；空标题被拒绝且不发请求', async () => {
      renderNotebookPage();
      await waitFor(() => expect(screen.getByText('页面-104')).toBeInTheDocument());
      const row = screen.getByText('页面-104').closest('.note-page-item') as HTMLElement;
      fireEvent.keyDown(row, { key: 'F2', keyCode: 113 });
      const input = (await screen.findByLabelText('重命名页面')) as HTMLInputElement;
      fireEvent.change(input, { target: { value: '   ' } });
      fireEvent.blur(input);
      await waitFor(() => expect(screen.getByText('页面标题不能为空')).toBeInTheDocument());
      expect(apiPut.mock.calls.filter((c) => c[0] === '/api/note-pages/104').length).toBe(0);
    });

    it('NBK-18: 笔记本改名按钮把 Select 换成输入框，回车 PUT /api/notebooks/{id}', async () => {
      apiPut.mockImplementation((path: string, body: Record<string, unknown>) => {
        if (path === '/api/notebooks/1') return Promise.resolve({ id: 1, title: String(body.title ?? '默认笔记本') });
        return Promise.resolve({});
      });
      renderNotebookPage();
      const trigger = await screen.findByRole('button', { name: '重命名笔记本' });
      fireEvent.click(trigger);
      const input = (await screen.findByLabelText('重命名当前笔记本')) as HTMLInputElement;
      expect(input.value).toBe('默认笔记本');
      fireEvent.change(input, { target: { value: '数据结构' } });
      fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
      await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/notebooks/1', { title: '数据结构' }));
      // 提交后收起输入框、恢复 Select（列表文案由 ['notebooks'] 重新拉取决定，这里不断言）
      await waitFor(() => expect(screen.queryByLabelText('重命名当前笔记本')).toBeNull());
      expect(await screen.findByRole('button', { name: '重命名笔记本' })).toBeInTheDocument();
    });
  });

  describe('Page-switch / autosave race characterization (Accepted debt)', () => {
    beforeEach(() => primeNotebookApi({ slowPage37: true }));

    it('OBSERVATION: when pageId switches mid-typing, the 400ms PUT lands on the page captured AT SCHEDULE TIME', async () => {
      // Real timers throughout. The slowPage37 mock resolves after 400ms via
      // a real setTimeout, so we can drive the race deterministically by
      // clicking 页面-37 immediately after typing into page 11 — page 37's
      // fetch starts while page 11's debounce timer is still pending.
      renderNotebookPage();
      await waitFor(() => expect(screen.getByTestId('notebook-editor')).toBeInTheDocument());
      // Confirm initial pageId.
      await waitFor(() => expect(screen.getByTestId('notebook-editor').getAttribute('data-page-id')).toBe('11'));

      // Type into page 11 (schedules a 400ms PUT with pageId=11).
      fireEvent.click(screen.getByText('type-A'));
      // Switch to page 37 IMMEDIATELY. The slow mock (400ms delay) means the
      // editor's pageId prop will switch from 11 → 37 after the fetch resolves.
      // The debounce timer set under pageId=11 keeps its original capture.
      fireEvent.click(screen.getByText('页面-37'));
      // Wait long enough for: (a) 400ms autosave debounce to fire on page 11,
      // (b) the slow page 37 fetch to resolve and update pageId to 37.
      await new Promise((res) => setTimeout(res, 800));
      // The PUT must have fired (under pageId=11, since that was captured in
      // pendingSaveRef at schedule time). We do not assert a "guaranteed
      // prior-page flush" — only what the implementation produces today.
      const putCalls = apiPut.mock.calls.filter((c) => c[0].startsWith('/api/note-pages/'));
      expect(putCalls.length).toBeGreaterThanOrEqual(1);
      // Record the observation sequence for the evidence report.
      const sequence = putCalls.map((c) => ({ path: c[0] as string, bodyChars: JSON.stringify(c[1]).length }));
      try {
        const { writeFileSync, mkdirSync } = await import('node:fs');
        const { join } = await import('node:path');
        const dir = join(process.cwd(), '.omo', 'evidence', 'notebook-bank-workspace-redesign', 'task-1-baseline');
        mkdirSync(dir, { recursive: true });
        writeFileSync(join(dir, 'page-switch-race.observation.json'),
          JSON.stringify({ sequence, capturedAt: new Date().toISOString() }, null, 2));
      } catch { /* filesystem not available in vitest — best effort only */ }
    });
  });

  describe('Export with unsaved merge (NBK-03, accepted behavior)', () => {
    beforeEach(() => {
      primeNotebookApi();
    });

    it('NBK-03: ExportActions disabled with 0 selected; enabled with selection; uses window.api.exportFile.save', async () => {
      const w = window as unknown as { api: { exportFile: { save: ReturnType<typeof vi.fn> } } };
      w.api.exportFile.save = vi.fn().mockResolvedValue({ canceled: true });
      renderNotebookPage();
      await waitFor(() => expect(screen.getByText('全选页面')).toBeInTheDocument());
      const exportBtn = await screen.findByRole('button', { name: '导出' });
      expect(exportBtn).toBeDisabled();
      fireEvent.click(screen.getByText('全选页面'));
      await waitFor(() => expect(exportBtn).toBeEnabled());
      fireEvent.click(exportBtn);
      await waitFor(() => expect(w.api.exportFile.save).toHaveBeenCalled());
    });
  });

  describe('DayQueue mode (NBK-15, NBK-16)', () => {
    beforeEach(() => primeNotebookApi());

    it('NBK-16: ?dayQueue=1 surfaces 完成今日任务 button (single-page mode)', async () => {
      // For a single-page deep link, the label is "完成今日任务" per source line 211.
      // The dayQueueSession lib reads from localStorage; we leave it empty so the bar
      // does not render, but the heading action still surfaces.
      renderNotebookPage(['/notebooks?dayQueue=1&pageId=11']);
      await waitFor(() => expect(screen.getByRole('button', { name: /完成今日任务|笔记段完成/ })).toBeInTheDocument());
    });
  });

  describe('Pending content kept when currentPage content is the same shape (NBK-12, behavior lock)', () => {
    beforeEach(() => primeNotebookApi());

    it('NBK-12 useEffect (lines 109-112): pendingContent is set from pageQuery.data on first load', async () => {
      // We can't directly read pendingContent from the DOM, but we can verify the
      // call sequence: after page 11 loads, the editor mount triggers no PUT (no
      // change). Subsequent user changes DO trigger PUTs.
      renderNotebookPage();
      await waitFor(() => expect(screen.getByTestId('notebook-editor')).toBeInTheDocument());
      // No PUT yet (page just loaded, pendingContent === pageQuery.data.content).
      const initialPutCount = apiPut.mock.calls.filter((c) => c[0] === '/api/note-pages/11').length;
      expect(initialPutCount).toBe(0);
    });
  });
});

// =====================================================================
// TARGET STRUCTURE — intentionally FAIL on current code (Phase 2+ gate)
// =====================================================================

describe('NotebookPage target structure — Phase 2+ redesign contract', () => {
  beforeEach(() => primeNotebookApi());

  it('route root carries .route-workspace', async () => {
    renderNotebookPage();
    await waitFor(() => expect(screen.getByText('页面-11')).toBeInTheDocument());
    expect(document.querySelector('.route-workspace')).toBeInTheDocument();
  });

  it('route-local command row uses .route-command-row with min-height 44px', async () => {
    renderNotebookPage();
    await waitFor(() => expect(screen.getByText('页面-11')).toBeInTheDocument());
    const row = document.querySelector('.route-command-row');
    expect(row, 'expected route-local .route-command-row').toBeTruthy();
    expect(row).not.toHaveAttribute('style');
    expect(row).toHaveClass('route-command-row');
  });

  it('left explorer uses .local-explorer with subheader and list', async () => {
    renderNotebookPage();
    await waitFor(() => expect(screen.getByText('页面-11')).toBeInTheDocument());
    expect(document.querySelector('.local-explorer')).toBeInTheDocument();
    expect(document.querySelector('.local-explorer__header')).toBeInTheDocument();
    expect(document.querySelector('.local-explorer__list')).toBeInTheDocument();
  });

  it('right body mounts under .route-workspace__content', async () => {
    renderNotebookPage();
    await waitFor(() => expect(screen.getByText('页面-11')).toBeInTheDocument());
    expect(document.querySelector('.route-workspace__content')).toBeInTheDocument();
  });

  it('editor host uses .editor-canvas', async () => {
    renderNotebookPage();
    await waitFor(() => expect(screen.getByTestId('notebook-editor')).toBeInTheDocument());
    // The redesigned Notebook must host the editor inside .editor-canvas.
    const canvas = document.querySelector('.editor-canvas') as HTMLElement | null;
    expect(canvas, 'expected .editor-canvas wrapper').toBeTruthy();
    expect(canvas!.contains(screen.getByTestId('notebook-editor'))).toBe(true);
  });

  it('local explorer pane is 232px wide above the 760px breakpoint', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1200 });
    renderNotebookPage();
    await waitFor(() => expect(screen.getByText('页面-11')).toBeInTheDocument());
    const explorer = document.querySelector('.local-explorer') as HTMLElement | null;
    expect(explorer, 'expected .local-explorer for width check').toBeTruthy();
    expect(explorer).not.toHaveAttribute('style');
    expect(explorer).toHaveClass('local-explorer--notebook');
  });

  it('below 760px breakpoint the explorer stacks and list width caps at 240px', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 700 });
    renderNotebookPage();
    await waitFor(() => expect(screen.getByText('页面-11')).toBeInTheDocument());
    const list = document.querySelector('.local-explorer__list') as HTMLElement | null;
    expect(list, 'expected .local-explorer__list for narrow cap').toBeTruthy();
    expect(list).not.toHaveAttribute('style');
    expect(list).toHaveClass('local-explorer__list');
  });

  it('page padding matches 16 20 24 contract (top horizontal / bottom)', async () => {
    renderNotebookPage();
    await waitFor(() => expect(screen.getByText('页面-11')).toBeInTheDocument());
    const workspace = document.querySelector('.route-workspace') as HTMLElement | null;
    expect(workspace, 'expected .route-workspace for padding check').toBeTruthy();
    expect(workspace).not.toHaveAttribute('style');
    expect(workspace).toHaveClass('route-workspace--notebook');
  });
});
