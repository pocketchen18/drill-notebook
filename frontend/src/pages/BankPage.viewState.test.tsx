/**
 * 题库页界面状态记忆（BKV-*）：启动时恢复上次选择的题库与该库勾选，
 * 记忆中的题库被删掉时回落第一个题库。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { Bank, Question } from '../lib/types';
import { LS_VIEW_STATE } from '../lib/viewState';

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

// Import after vi.mock so the mocked api module is bound.
import { BankPage } from './BankPage';

const banks: Bank[] = [
  { id: 11, name: '题库-11', description: 'd', sourceType: 'markdown', questionCount: 2 },
  { id: 37, name: '题库-37', description: 'd', sourceType: 'pdf', questionCount: 1 }
];

const questionsByBank: Record<number, Question[]> = {
  11: [
    { id: 104, bankId: 11, type: 'single', stem: '题干-104', options: [], chapter: '第一章' },
    { id: 105, bankId: 11, type: 'single', stem: '题干-105', options: [], chapter: '第二章' }
  ],
  37: [{ id: 200, bankId: 37, type: 'single', stem: '题干-200', options: [], chapter: '第三章' }]
};

function primeApi(): void {
  apiGet.mockImplementation((path: string) => {
    if (path === '/api/banks') return Promise.resolve(banks);
    const match = /^\/api\/banks\/(\d+)\/questions$/.exec(path);
    if (match) return Promise.resolve(questionsByBank[Number(match[1])] ?? []);
    return Promise.resolve([]);
  });
}

function renderPage(): void {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/banks']}>
        <BankPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

/** Arco Checkbox 把 aria-label 留在外层 <label> 上，因此按行结构定位勾选框。 */
function checkboxFor(stem: string): HTMLInputElement {
  const row = Array.from(document.querySelectorAll('.question-row')).find((el) => (el.textContent ?? '').includes(stem));
  const input = row?.querySelector('.selection-line input[type="checkbox"]') as HTMLInputElement | null;
  if (!input) throw new Error(`找不到题目勾选框：${stem}`);
  return input;
}

describe('BankPage 界面状态记忆', () => {
  beforeEach(() => {
    (window as unknown as { api: Record<string, unknown> }).api = {
      exportFile: { save: vi.fn() },
      dialog: { openTextFile: vi.fn() },
      config: { get: () => Promise.resolve({ theme: 'light' }), set: () => Promise.resolve() },
      backend: { getBaseUrl: () => Promise.resolve('http://127.0.0.1:18081') }
    };
    apiGet.mockReset();
    primeApi();
  });

  afterEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    apiPut.mockReset();
    apiDel.mockReset();
  });

  it('restores the last bank and its partial selection', async () => {
    localStorage.setItem(LS_VIEW_STATE, JSON.stringify({
      version: 1,
      pages: { banks: { selectedId: 11, selection: { lastId: 11, byId: { 11: { ids: [105] } } } } }
    }));
    renderPage();
    await waitFor(() => expect(screen.getByText('题干-105')).toBeInTheDocument());
    expect(checkboxFor('题干-104').checked).toBe(false);
    expect(checkboxFor('题干-105').checked).toBe(true);
  });

  it('expands the whole-bank sentinel', async () => {
    localStorage.setItem(LS_VIEW_STATE, JSON.stringify({
      version: 1,
      pages: { banks: { selectedId: 11, selection: { byId: { 11: { all: true } } } } }
    }));
    renderPage();
    await waitFor(() => expect(screen.getByText('题干-104')).toBeInTheDocument());
    expect(checkboxFor('题干-104').checked).toBe(true);
    expect(checkboxFor('题干-105').checked).toBe(true);
  });

  it('falls back to the first bank when the remembered one was deleted', async () => {
    localStorage.setItem(LS_VIEW_STATE, JSON.stringify({
      version: 1,
      pages: { banks: { selectedId: 999, selection: { byId: { 999: { ids: [1] } } } } }
    }));
    renderPage();
    await waitFor(() => expect(screen.getByText('题干-104')).toBeInTheDocument());
    expect(checkboxFor('题干-104').checked).toBe(false);
    expect(checkboxFor('题干-105').checked).toBe(false);
  });

  it('starts with nothing checked when nothing is remembered', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('题干-104')).toBeInTheDocument());
    expect(checkboxFor('题干-104').checked).toBe(false);
    expect(checkboxFor('题干-105').checked).toBe(false);
  });
});
