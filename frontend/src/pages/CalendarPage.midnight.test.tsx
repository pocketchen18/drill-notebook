/**
 * 日历「今天」实时性（CAL-MD-*）：锁定跨零点行为。
 * - 今天高亮与选中队列跟随真实日期前进
 * - 手动停留在其他日期时不被跨零点跳转打断
 * - 记忆里翻到的上月视图同样不被零点拽回
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { CalendarPage } from './CalendarPage';
import { LS_VIEW_STATE } from '../lib/viewState';

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }));

vi.mock('../lib/api', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn()
}));

async function flush(ms = 0): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

/** 走到次日 00:00 之后，并让 useToday 的 60 秒轮询触发一次。 */
async function crossMidnight(): Promise<void> {
  act(() => {
    vi.setSystemTime(new Date(2026, 8, 4, 0, 0, 30));
  });
  await flush(60_000);
}

function renderPage(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/calendar']}>
        <CalendarPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const todayCell = (): string | undefined =>
  document.querySelector('.calendar-day-cell.today')?.getAttribute('aria-label') ?? undefined;

const selectedCell = (): string | undefined =>
  document.querySelector('.calendar-day-cell.selected')?.getAttribute('aria-label') ?? undefined;

const monthLabel = (): string => document.querySelector('.calendar-month-title')?.textContent ?? '';

function dayColumnTitle(): string {
  return Array.from(document.querySelectorAll('h2'))
    .map((h) => h.textContent ?? '')
    .find((text) => text.startsWith('自主安排 · ')) ?? '';
}

describe('CalendarPage 跨零点', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 3, 23, 59, 10));
    apiGet.mockReset();
    apiGet.mockImplementation((path: string) => {
      if (String(path).startsWith('/api/review/calendar-stats')) {
        return Promise.resolve({ from: '2026-09-01', to: '2026-09-30', realToday: '2026-09-03', due: [], overdue: [] });
      }
      if (String(path).startsWith('/api/study/today')) {
        return Promise.resolve({ date: '2026-09-03', items: [], stats: {} });
      }
      return Promise.resolve({ from: '2026-09-01', to: '2026-09-30', days: [] });
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('highlights today and loads its queue on open', async () => {
    renderPage();
    await flush();
    expect(todayCell()).toBe('2026-09-03');
    expect(selectedCell()).toBe('2026-09-03');
    expect(dayColumnTitle()).toBe('自主安排 · 2026-09-03');
  });

  it('follows the new day once midnight passes while viewing today', async () => {
    renderPage();
    await flush();
    const callsBefore = apiGet.mock.calls.length;
    await crossMidnight();
    expect(todayCell()).toBe('2026-09-04');
    expect(selectedCell()).toBe('2026-09-04');
    expect(dayColumnTitle()).toBe('自主安排 · 2026-09-04');
    // 计划 + 到期统计 + 今日队列都按新日期重新拉取
    expect(apiGet.mock.calls.length).toBeGreaterThan(callsBefore);
  });

  it('keeps a manually selected date when midnight passes', async () => {
    renderPage();
    await flush();
    fireEvent.click(document.querySelector('button[aria-label="2026-09-10"]') as Element);
    await flush();
    expect(selectedCell()).toBe('2026-09-10');
    await crossMidnight();
    expect(selectedCell()).toBe('2026-09-10');
    expect(todayCell()).toBe('2026-09-04');
    expect(dayColumnTitle()).toBe('自主安排 · 2026-09-10');
  });

  it('keeps the remembered month view when midnight passes', async () => {
    localStorage.setItem(LS_VIEW_STATE, JSON.stringify({ version: 1, pages: { calendar: { viewYear: 2026, viewMonth: 7 } } }));
    renderPage();
    await flush();
    expect(monthLabel()).toBe('2026年8月');
    // 8 月网格里没有 9-03，所以没有高亮/选中格，但右侧仍是选中的今天
    expect(todayCell()).toBeUndefined();
    expect(selectedCell()).toBeUndefined();
    expect(dayColumnTitle()).toBe('自主安排 · 2026-09-03');
    await crossMidnight();
    // 视图停在昨天记忆里的上月＝用户在看别处：数据刷新，但不被拽回新的今天
    expect(monthLabel()).toBe('2026年8月');
    expect(dayColumnTitle()).toBe('自主安排 · 2026-09-03');
  });
});
