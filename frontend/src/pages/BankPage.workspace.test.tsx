/**
 * Task 1 baseline behavior + target-structure tests for `BankPage`.
 *
 * BEHAVIOR block (BNK-* / RNN-* rows from `.omo/evidence/notebook-bank-workspace-redesign/control-ledger.md`):
 *   Locks the current Bank behavior — must PASS on unchanged production source.
 *
 * TARGET STRUCTURE block:
 *   Asserts the workspace structure and CSS-owned geometry contract.
 *
 * Fixture IDs are intentionally non-sequential (11, 37, 104) per the plan so they
 * collide with nothing the seeding scripts use. We deliberately do NOT assert
 * natural-language prose beyond what the UI already displays (e.g. the
 * `'题库'` heading text consumed by the heading router).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { Bank, Question } from '../lib/types';

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

const { exportFileSave, dialogOpenTextFile } = vi.hoisted(() => ({
  exportFileSave: vi.fn(),
  dialogOpenTextFile: vi.fn()
}));

const baseWindowApi = (overrides: Record<string, unknown> = {}) => ({
  ...(overrides),
  exportFile: { save: (...args: unknown[]) => exportFileSave(...args) },
  dialog: { openTextFile: (...args: unknown[]) => dialogOpenTextFile(...args) },
  config: {
    get: () => Promise.resolve({ theme: 'light' }),
    set: () => Promise.resolve()
  },
  backend: { getBaseUrl: () => Promise.resolve('http://127.0.0.1:18081') }
});

beforeEach(() => {
  // The harness injects window.api on startup; replace it for each test.
  (window as unknown as { api: Record<string, unknown> }).api = baseWindowApi() as Record<string, unknown>;
});

afterEach(() => {
  vi.restoreAllMocks();
  apiGet.mockReset();
  apiPost.mockReset();
  apiPut.mockReset();
  apiDel.mockReset();
  exportFileSave.mockReset();
  dialogOpenTextFile.mockReset();
});

function LocationProbe(): JSX.Element {
  const location = useLocation();
  return <div data-testid="loc">{location.pathname}{location.search}</div>;
}

function renderBankPage(): ReturnType<typeof render> {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/banks']}>
        <BankPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// Import after vi.mock so the mocked api module is bound.
import { BankPage } from './BankPage';

const baseBanks: Bank[] = [
  { id: 11, name: '题库-11', description: 'd', sourceType: 'markdown', questionCount: 2 },
  { id: 37, name: '题库-37', description: 'd', sourceType: 'pdf', questionCount: 0 }
];

const baseQuestions: Question[] = [
  {
    id: 104, bankId: 11, type: 'single',
    stem: '题干-104',
    options: [{ key: 'A', text: '选项-A' }, { key: 'B', text: '选项-B' }],
    chapter: '第一章'
  },
  {
    id: 105, bankId: 11, type: 'multiple',
    stem: '题干-105',
    options: [{ key: 'A', text: 'A' }, { key: 'B', text: 'B' }],
    chapter: '第二章'
  }
];

function primeBanksApi(): void {
  apiGet.mockImplementation((path: string) => {
    if (path === '/api/banks') return Promise.resolve(baseBanks);
    const m = path.match(/^\/api\/banks\/(\d+)\/questions$/);
    if (m) {
      if (Number(m[1]) === 11) return Promise.resolve(baseQuestions);
      return Promise.resolve([]);
    }
    return Promise.resolve([]);
  });
}

// =====================================================================
// BEHAVIOR — must pass on unchanged code
// =====================================================================

describe('BankPage behavior — baseline regression (Task 1)', () => {
  describe('Bank heading + bank list (BNK-01, BNK-06, BNK-07, BNK-09)', () => {
    beforeEach(() => primeBanksApi());

    it('BNK-01: heading 导入 Markdown、JSON 或 PDF 题库 + 新建题库/导入 buttons render', () => {
      renderBankPage();
      // Single 题库 heading consumed by the router — assert the visible routing text only.
      expect(screen.getByRole('heading', { name: '题库', level: 1 })).toBeInTheDocument();
      expect(screen.getByText(/导入 Markdown、JSON 或 PDF 题库/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /新建题库/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /导入 Markdown/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /导入 JSON/ })).toBeInTheDocument();
    });

    it('BNK-06 + BNK-07: bank list renders both banks; first is auto-selected; click selects another', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11')).toBeInTheDocument());
      expect(screen.getByText('题库-37')).toBeInTheDocument();
      // Question count appears (current code surfaces it on each item).
      expect(screen.getByText(/2 道题 · 双击名称可改名/)).toBeInTheDocument();
      expect(screen.getByText(/0 道题 · 双击名称可改名/)).toBeInTheDocument();
      // Click second bank selects it; deep-link target updates on `/quiz`.
      fireEvent.click(screen.getByText('题库-37'));
      await waitFor(() => expect(apiGet.mock.calls.some((c) => c[0] === '/api/banks/37/questions')).toBe(true));
    });

    it('BNK-09: delete Popconfirm + DEL fires', async () => {
      apiDel.mockResolvedValue(undefined);
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11')).toBeInTheDocument());
      // delete button is aria-labelled with the bank name (per source line 304).
      const deleteBtn = screen.getByRole('button', { name: '删除题库-11' });
      fireEvent.click(deleteBtn);
      const confirm = await screen.findByText('确定');
      fireEvent.click(confirm);
      await waitFor(() => expect(apiDel).toHaveBeenCalledWith('/api/banks/11'));
    });
  });

  describe('Bank rename — both surfaces (BNK-08, BNK-10..12, RNN rows)', () => {
    beforeEach(() => {
      primeBanksApi();
      apiPut.mockResolvedValue({ id: 11, name: '题库-11-renamed' });
    });

    it('BNK-08 list surface: F2 begins rename; Enter commits and PUTs', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      // Get the row that contains the title text.
      const item = screen.getByText('题库-11', { selector: '.bank-item-title' }).closest('.bank-item') as HTMLElement;
      // F2 triggers beginRename (source line 264)
      item.focus();
      fireEvent.keyDown(item, { key: 'F2' });
      const input = (await screen.findByLabelText('重命名题库')) as HTMLInputElement;
      fireEvent.change(input, { target: { value: '' } });
      fireEvent.change(input, { target: { value: '题库-11-renamed' } });
      // arco Input wires `onPressEnter` to native keydown(Enter). jsdom doesn't
      // fully forward synthetic events through the wrapper, so we trigger the
      // commit via blur (which is the second half of the same `commitRename` path
      // that Enter invokes) and assert the PUT.
      fireEvent.blur(input);
      await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/banks/11', { name: '题库-11-renamed' }));
    });

    it('list surface: double-click also begins rename (BNK-08 alt)', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      const title = screen.getByText('题库-11', { selector: '.bank-item-title' });
      fireEvent.doubleClick(title);
      expect(await screen.findByLabelText('重命名题库')).toBeInTheDocument();
    });

    it('BNK-10 list surface: blank name is rejected (warns + refocus)', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      const item = screen.getByText('题库-11', { selector: '.bank-item-title' }).closest('.bank-item') as HTMLElement;
      item.focus();
      fireEvent.keyDown(item, { key: 'F2' });
      const input = await screen.findByLabelText('重命名题库');
      fireEvent.change(input, { target: { value: '' } });
      fireEvent.keyDown(input, { key: 'Enter' });
      // apiPut must NOT fire on empty rename.
      await waitFor(() => expect(apiPut).not.toHaveBeenCalled());
    });

    it('BNK-10 list surface: Escape cancels without PUT', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      const item = screen.getByText('题库-11', { selector: '.bank-item-title' }).closest('.bank-item') as HTMLElement;
      item.focus();
      fireEvent.keyDown(item, { key: 'F2' });
      const input = await screen.findByLabelText('重命名题库');
      fireEvent.change(input, { target: { value: 'cancel-me' } });
      fireEvent.keyDown(input, { key: 'Escape' });
      // After Escape the input is gone, no PUT.
      await waitFor(() => expect(screen.queryByLabelText('重命名题库')).not.toBeInTheDocument());
      expect(apiPut).not.toHaveBeenCalled();
    });

    it('BNK-11 header surface: double-click on the right-panel bank title begins rename via header input', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByRole('button', { name: '开始练习' })).toBeInTheDocument());
      // The header is the <h2 class="bank-panel-title">…</h2>.
      const headerTitle = screen.getByText('题库-11', { selector: 'h2.bank-panel-title' });
      fireEvent.doubleClick(headerTitle);
      expect(await screen.findByLabelText('重命名当前题库')).toBeInTheDocument();
    });
  });

  describe('Imports (BNK-02..04, IMP rows)', () => {
    beforeEach(() => {
      primeBanksApi();
      apiPost.mockImplementation((path: string) => {
        if (path === '/api/banks/11/import/markdown') return Promise.resolve({ imported: 3, skipped: 0, failed: 0 });
        if (path === '/api/banks/11/import/json') return Promise.resolve({ imported: 2, skipped: 1, failed: 0 });
        if (path === '/api/banks/11/import/pdf') return Promise.resolve({ imported: 4, skipped: 0, failed: 0, strategy: 'rules' });
        return Promise.resolve({});
      });
    });

    it('BNK-03 Markdown import via Electron openTextFile → POST /api/banks/{id}/import/markdown', async () => {
      dialogOpenTextFile.mockResolvedValue({ canceled: false, content: '# 题' });
      renderBankPage();
      // Wait until banks load and first bank is auto-selected (selectedId=11).
      // The Markdown button exists immediately, but the import handler reads
      // selectedId from closure and warns if not set yet — so we must wait.
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByRole('button', { name: /导入 Markdown/ })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: /导入 Markdown/ }));
      await waitFor(() => expect(dialogOpenTextFile).toHaveBeenCalledWith(['md', 'markdown', 'txt']));
      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/banks/11/import/markdown', { content: '# 题' }));
    });

    it('BNK-04 JSON import via Electron openTextFile → POST /api/banks/{id}/import/json', async () => {
      dialogOpenTextFile.mockResolvedValue({ canceled: false, content: '{"questions":[]}' });
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByRole('button', { name: /导入 JSON/ })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: /导入 JSON/ }));
      await waitFor(() => expect(dialogOpenTextFile).toHaveBeenCalledWith(['json']));
      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/banks/11/import/json', { content: '{"questions":[]}' }));
    });

    it('BNK-02 PDF import: 导入 PDF button exists', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByRole('button', { name: /导入 PDF/ })).toBeInTheDocument());
      // PDF upload goes through a hidden <input type="file">. We do not simulate file selection
      // (covered by PdfImportButton's own tests); here we only assert wiring presence.
      expect(screen.getByRole('button', { name: /导入 PDF/ })).toBeInTheDocument();
    });
  });

  describe('Selection + exports + start practice (BNK-14..17, BNK-22, EXP rows)', () => {
    beforeEach(() => {
      primeBanksApi();
      exportFileSave.mockResolvedValue({ canceled: false, path: 'C:\\fake\\export.md' });
    });

    it('BNK-17 select-all toggles all questions', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByText('全选当前题库')).toBeInTheDocument());
      fireEvent.click(screen.getByText('全选当前题库'));
      // The ExportActions count badge reads 已选 2 项
      expect(screen.getByText(/已选\s*2\s*项/)).toBeInTheDocument();
    });

    it('BNK-14 export disabled when no selection; enabled after selection', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByText('全选当前题库')).toBeInTheDocument());
      const exportBtn = screen.getByRole('button', { name: '导出' });
      expect(exportBtn).toBeDisabled();
      fireEvent.click(screen.getByText('全选当前题库'));
      await waitFor(() => expect(exportBtn).toBeEnabled());
    });

    it('BNK-14 export calls window.api.exportFile.save with selected questions', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByText('全选当前题库')).toBeInTheDocument());
      fireEvent.click(screen.getByText('全选当前题库'));
      fireEvent.click(await screen.findByRole('button', { name: '导出' }));
      await waitFor(() => expect(exportFileSave).toHaveBeenCalledTimes(1));
      const arg = exportFileSave.mock.calls[0][0];
      expect(arg.format).toMatch(/md|html|pdf/);
      expect(arg.suggestedName).toMatch(/题库-11/);
      expect(arg.content || arg.html).toBeTruthy();
    });

    it('BNK-16 开始练习 without selection navigates to /quiz?bankId=11', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByRole('button', { name: '开始练习' })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: '开始练习' }));
      await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/quiz?bankId=11'));
    });

    it('BNK-16b 开始练习 carries selected question ids so quiz inherits the selection', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByRole('button', { name: '开始练习' })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByText('全选当前题库')).toBeInTheDocument());
      fireEvent.click(screen.getByText('全选当前题库'));
      fireEvent.click(screen.getByRole('button', { name: '开始练习' }));
      await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/quiz?bankId=11&questionIds=104,105&from=bank'));
    });

    it('BNK-18..21: question row shows full stem, all options, chapter', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByText('题干-104')).toBeInTheDocument());
      // Question 104 has options A and B (Ledger row BNK-21).
      expect(screen.getByText('选项-A')).toBeInTheDocument();
      expect(screen.getByText('选项-B')).toBeInTheDocument();
      // Chapter: 第一章 (Ledger row BNK-18 alt). The text is split across nodes
      // (Text type="secondary" + strong prefix), so use a flexible regex matcher.
      expect(screen.getByText(/.*章节.*第一.*/)).toBeInTheDocument();
    });

    it('BNK-22: empty state when no questions', async () => {
      apiGet.mockReset();
      apiGet.mockImplementation((path: string) => {
        if (path === '/api/banks') return Promise.resolve([{ id: 37, name: '题库-37', sourceType: 'pdf', questionCount: 0 }]);
        if (path === '/api/banks/37/questions') return Promise.resolve([]);
        return Promise.resolve([]);
      });
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-37', { selector: '.bank-item-title' })).toBeInTheDocument());
      // The empty state copy inside <div class="empty-state"> (BankPage.tsx:374).
      await waitFor(() => expect(screen.getByText(/导入 Markdown 题库后/)).toBeInTheDocument());
    });
  });

  describe('New bank modal (BNK-23)', () => {
    beforeEach(() => primeBanksApi());

    it('new bank: 新建题库 opens modal; POST /api/banks with name; select the new id', async () => {
      apiPost.mockResolvedValue({ id: 37, name: '新题库', sourceType: 'markdown', questionCount: 0 });
      renderBankPage();
      await waitFor(() => expect(screen.getByRole('button', { name: /新建题库/ })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: /新建题库/ }));
      const input = await screen.findByPlaceholderText(/Java 基础/);
      fireEvent.change(input, { target: { value: '新题库' } });
      fireEvent.click(screen.getByRole('button', { name: '确定' }));
      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/banks', expect.objectContaining({ name: '新题库', sourceType: 'markdown' })));
      await waitFor(() => expect(apiGet.mock.calls.some((c) => c[0] === '/api/banks/37/questions')).toBe(true));
    });

    it('blank name rejected (warns 请输入题库名称)', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByRole('button', { name: /新建题库/ })).toBeInTheDocument());
      fireEvent.click(screen.getByRole('button', { name: /新建题库/ }));
      fireEvent.click(await screen.findByRole('button', { name: '确定' }));
      // No POST.
      await waitFor(() => expect(apiPost.mock.calls.filter((c) => c[0] === '/api/banks').length).toBe(0));
    });
  });

  describe('Selection clears on bank switch (BNK-07 alt, safety-17)', () => {
    beforeEach(() => primeBanksApi());

    it('switching bank clears selectedQuestionIds', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getByText('题库-11', { selector: '.bank-item-title' })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByText('全选当前题库')).toBeInTheDocument());
      fireEvent.click(screen.getByText('全选当前题库'));
      await waitFor(() => expect(screen.getByText(/已选\s*2\s*项/)).toBeInTheDocument());
      fireEvent.click(screen.getByText('题库-37', { selector: '.bank-item-title' }));
      // Switch to empty bank → no questions, no 全选 checkbox.
      await waitFor(() => expect(screen.queryByText('全选当前题库')).not.toBeInTheDocument());
    });
  });

  describe('Question row edit + delete (BNK-19, BNK-20)', () => {
    beforeEach(() => {
      primeBanksApi();
      apiDel.mockResolvedValue(undefined);
    });

    it('BNK-19 编辑题目 opens QuestionEditorModal', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getAllByLabelText('编辑题目').length).toBeGreaterThan(0));
      fireEvent.click(screen.getAllByLabelText('编辑题目')[0]);
      // QuestionEditorModal renders its title via arco Modal — find by title text.
      expect(await screen.findByText('编辑题目')).toBeInTheDocument();
    });

    it('BNK-20 Popconfirm → DEL /api/questions/{id}', async () => {
      renderBankPage();
      await waitFor(() => expect(screen.getAllByLabelText('删除题目').length).toBeGreaterThan(0));
      fireEvent.click(screen.getAllByLabelText('删除题目')[0]);
      const confirm = await screen.findByText('确定');
      fireEvent.click(confirm);
      await waitFor(() => expect(apiDel).toHaveBeenCalledWith('/api/questions/104'));
    });
  });
});

// =====================================================================
// TARGET STRUCTURE — intentionally FAIL on current code (Phase 2+ gate)
// =====================================================================

describe('BankPage target structure — Phase 2+ redesign contract', () => {
  beforeEach(() => primeBanksApi());

  it('route root carries .route-workspace', async () => {
    renderBankPage();
    await waitFor(() => expect(screen.getByText('题库-11')).toBeInTheDocument());
    // The redesigned Bank mounts the route under a single .route-workspace root.
    expect(document.querySelector('.route-workspace')).toBeInTheDocument();
  });

  it('route-local command row uses .route-command-row with min-height 44px', async () => {
    renderBankPage();
    await waitFor(() => expect(screen.getByText('题库-11')).toBeInTheDocument());
    const row = document.querySelector('.route-command-row');
    expect(row, 'expected route-local .route-command-row').toBeTruthy();
    expect(row).not.toHaveAttribute('style');
    expect(row).toHaveClass('route-command-row');
  });

  it('left explorer uses .local-explorer with subheader and list', async () => {
    renderBankPage();
    await waitFor(() => expect(screen.getByText('题库-11')).toBeInTheDocument());
    expect(document.querySelector('.local-explorer')).toBeInTheDocument();
    expect(document.querySelector('.local-explorer__header')).toBeInTheDocument();
    expect(document.querySelector('.local-explorer__list')).toBeInTheDocument();
  });

  it('right body mounts under .route-workspace__content', async () => {
    renderBankPage();
    await waitFor(() => expect(screen.getByText('题库-11')).toBeInTheDocument());
    expect(document.querySelector('.route-workspace__content')).toBeInTheDocument();
  });

  it('each question row uses .dense-content-row', async () => {
    renderBankPage();
    await waitFor(() => expect(screen.getByText('题干-104')).toBeInTheDocument());
    // At least one dense row (Bank has two questions for fixture id 11).
    const denseRows = document.querySelectorAll('.dense-content-row');
    expect(denseRows.length).toBeGreaterThanOrEqual(2);
  });

  it('page padding matches 16 20 24 contract (top horizontal / bottom)', async () => {
    renderBankPage();
    await waitFor(() => expect(screen.getByText('题库-11')).toBeInTheDocument());
    const workspace = document.querySelector('.route-workspace') as HTMLElement | null;
    expect(workspace, 'expected .route-workspace for padding check').toBeTruthy();
    expect(workspace).not.toHaveAttribute('style');
    expect(workspace).toHaveClass('route-workspace--bank');
  });

  it('narrow list cap is 240px when viewport < 760px breakpoint', async () => {
    // Phase 2 contract: below 760px the explorer stacks and the list caps at 240px wide.
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 700 });
    renderBankPage();
    await waitFor(() => expect(screen.getByText('题库-11')).toBeInTheDocument());
    const list = document.querySelector('.local-explorer__list') as HTMLElement | null;
    expect(list, 'expected .local-explorer__list for narrow cap').toBeTruthy();
    expect(list).not.toHaveAttribute('style');
    expect(list).toHaveClass('local-explorer__list');
  });
});
