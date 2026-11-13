import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { CalendarPage } from './CalendarPage';
import { useUiStore } from '../stores/uiStore';
import { capturePageSnapshot } from '../lib/pageSnapshot';

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }));
vi.mock('../lib/api', async importOriginal => ({ ...await importOriginal<object>(), get: apiGet }));
vi.mock('../lib/useToday', () => ({ useToday: () => '2026-11-14' }));
vi.mock('../lib/studyPlan', async importOriginal => ({ ...await importOriginal<object>(), todayYmd: () => '2026-11-14' }));

const group = (date: string, title: string) => ({ id: 1, planDate: date, title, source: 'manual', doneCount: 1, totalCount: 2,
  note: '先复习再练习', items: [
    { id: 1, groupId: 1, planDate: date, resourceType: 'knowledge_point', resourceId: 3, title: '极限知识点', status: 'todo' },
    { id: 2, groupId: 1, planDate: date, resourceType: 'note_page', resourceId: 4, title: '函数笔记', status: 'done' }
  ] });

beforeEach(() => {
  useUiStore.getState().clearPageContext();
  apiGet.mockReset();
  apiGet.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/study-plans?')) return { days: [
      { date: '2026-11-14', groups: [group('2026-11-14', '周末复习')] },
      { date: '2026-11-15', groups: [group('2026-11-15', '周日安排')] }
    ] };
    if (path.startsWith('/api/study-plans/day?')) return { days: [] };
    if (path.startsWith('/api/review/calendar-stats')) return { due: [{ due_date: '2026-11-14', due_count: 2 }],
      overdue: [{ due_date: '2026-11-14', overdue_count: 1 }], realToday: '2026-11-14' };
    if (path.startsWith('/api/study/today')) {
      const date = new URLSearchParams(path.split('?')[1]).get('date');
      return { date, items: date === '2026-11-14' ? [
        { key: 'question:5', kind: 'due', resourceType: 'question', resourceId: 5, title: '曲线复习题', due: true, overdue: true, scheduleId: 2 },
        { key: 'note_page:6', kind: 'plan', resourceType: 'note_page', resourceId: 6, title: '队列计划笔记', planItemId: 3, groupTitle: '周末复习' }
      ] : [], stats: { planTodo: 1, dueIncluded: 1 } };
    }
    throw new Error('Unexpected fixture request: ' + path);
  });
});

function renderPage(date?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/calendar' + (date ? '?date=' + date : '')]}><CalendarPage /></MemoryRouter></QueryClientProvider>);
}

describe('日历页当前页面适配', () => {
  it('包含月历、选中日期、完成状态和合并学习队列', async () => {
    renderPage();
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('曲线复习题'));
    const snapshot = capturePageSnapshot(useUiStore.getState().pageContext);
    expect(snapshot).toMatchObject({ kind: 'calendar', title: '日历 · 2026-11-14', route: '/calendar?date=2026-11-14', isDraft: false });
    for (const text of ['2026年11月', '今天：2026-11-14', '选中日期：2026-11-14', '周末复习', '极限知识点；待完成',
      '函数笔记；已完成', '记忆曲线到期 2 项', '逾期未复习 1 项', '队列计划笔记', '曲线复习题；记忆曲线；逾期']) expect(snapshot.markdown).toContain(text);
  });

  it('选中另一日后更新内容并清理旧队列，已发送快照保持原日期', async () => {
    renderPage();
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('曲线复习题'));
    const submitted = capturePageSnapshot(useUiStore.getState().pageContext);
    fireEvent.click(screen.getByRole('button', { name: '2026-11-15' }));
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('自主安排 · 2026-11-15'));
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).not.toContain('曲线复习题'));
    expect(useUiStore.getState().pageContext.title).toBe('日历 · 2026-11-15');
    expect(useUiStore.getState().pageContext.markdown).toContain('周日安排');
    expect(submitted.markdown).toContain('曲线复习题');
    expect(submitted.route).toBe('/calendar?date=2026-11-14');
  });

  it('切换月份与队列来源筛选时，只登记当前视图和当前可见队列', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('radio', { name: /记忆曲线 1/ }));
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('来源筛选：记忆曲线'));
    expect(useUiStore.getState().pageContext.markdown).not.toContain('队列计划笔记');
    fireEvent.click(screen.getByRole('button', { name: '上一月' }));
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('月份：2026年10月'));
    expect(useUiStore.getState().pageContext.markdown).not.toContain('2026-11-14：计划');
    expect(useUiStore.getState().pageContext.markdown).toContain('选中日期：2026-11-14');
  });

  it('日期深链与空态可以读取', async () => {
    renderPage('2026-12-01');
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('这一天还没有自主安排的计划分组'));
    expect(useUiStore.getState().pageContext.markdown).toContain('选中日期：2026-12-01');
    expect(useUiStore.getState().pageContext.kind).toBe('calendar');
  });

  it('加载失败在快照中明确显示，保持日历页面定位', async () => {
    apiGet.mockRejectedValue(new Error('fixture unavailable'));
    renderPage();
    await waitFor(() => expect(useUiStore.getState().pageContext.markdown).toContain('月历计划加载失败'));
    expect(useUiStore.getState().pageContext.markdown).toContain('学习队列加载失败');
    expect(useUiStore.getState().pageContext.markdown).toContain('当天计划加载失败');
    expect(useUiStore.getState().pageContext.markdown).not.toContain('这一天还没有自主安排的计划分组');
    expect(useUiStore.getState().pageContext.title).toBe('日历 · 2026-11-14');
  });
});
