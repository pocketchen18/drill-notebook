import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_PLAN_SPAN_DAYS,
  addDaysYmd,
  collectTodoKnowledgePointIds,
  collectTodoKnowledgePointIdsFromGroups,
  collectTodoQuestionIds,
  collectTodoQuestionIdsFromGroups,
  diffDaysInclusive,
  distributeItemsAcrossWindow,
  formatPlanWindowLabel,
  formatYmd,
  monthRange,
  planKnowledgePath,
  planQuizPath,
  planStudyPath,
  resolvePlanWindow,
  todayYmd,
  tomorrowYmd,
  truncateTitle
} from './studyPlan';

describe('study plan date helpers', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('formats local calendar dates as YYYY-MM-DD with zero padding', () => {
    expect(formatYmd(new Date(2026, 0, 5))).toBe('2026-01-05');
    expect(formatYmd(new Date(2026, 11, 31))).toBe('2026-12-31');
  });

  it('returns today and tomorrow in local YYYY-MM-DD', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 18, 15, 30, 0));
    expect(todayYmd()).toBe('2026-07-18');
    expect(tomorrowYmd()).toBe('2026-07-19');
  });

  it('rolls tomorrow across month and year boundaries', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 11, 31, 10, 0, 0));
    expect(todayYmd()).toBe('2026-12-31');
    expect(tomorrowYmd()).toBe('2027-01-01');
  });

  it('returns inclusive first and last day of the month', () => {
    expect(monthRange(2026, 0)).toEqual({ from: '2026-01-01', to: '2026-01-31' });
    expect(monthRange(2026, 1)).toEqual({ from: '2026-02-01', to: '2026-02-28' });
    expect(monthRange(2024, 1)).toEqual({ from: '2024-02-01', to: '2024-02-29' });
    expect(monthRange(2026, 11)).toEqual({ from: '2026-12-01', to: '2026-12-31' });
  });
});

describe('plan window helpers', () => {
  it('addDaysYmd adds calendar days', () => {
    expect(addDaysYmd('2026-07-20', 4)).toBe('2026-07-24');
    expect(addDaysYmd('2026-07-30', 3)).toBe('2026-08-02');
  });

  it('diffDaysInclusive is inclusive', () => {
    expect(diffDaysInclusive('2026-07-20', '2026-07-26')).toBe(7);
    expect(diffDaysInclusive('2026-07-20', '2026-07-20')).toBe(1);
  });

  it('resolvePlanWindow defaults to 5 days without end', () => {
    const w = resolvePlanWindow('2026-07-20');
    expect(w.spanDays).toBe(DEFAULT_PLAN_SPAN_DAYS);
    expect(w.endDate).toBe('2026-07-24');
    expect(w.endDateProvided).toBe(false);
  });

  it('resolvePlanWindow uses full user end without clamping (20 days)', () => {
    const w = resolvePlanWindow('2026-07-01', '2026-07-20');
    expect(w.spanDays).toBe(20);
    expect(w.endDate).toBe('2026-07-20');
    expect(w.endDateProvided).toBe(true);
  });

  it('resolvePlanWindow rejects end before start', () => {
    expect(() => resolvePlanWindow('2026-07-20', '2026-07-19')).toThrow();
  });

  it('formatPlanWindowLabel covers both modes', () => {
    expect(formatPlanWindowLabel(resolvePlanWindow('2026-07-20', '2026-07-26'))).toContain('共 7 天');
    expect(formatPlanWindowLabel(resolvePlanWindow('2026-07-20'))).toContain('未设终止日');
  });
});

describe('distributeItemsAcrossWindow', () => {
  it('puts all items on one day when start equals end', () => {
    const groups = distributeItemsAcrossWindow(
      [
        { resourceType: 'question', resourceId: 1, title: 'a' },
        { resourceType: 'question', resourceId: 2, title: 'b' }
      ],
      '2026-07-20',
      '2026-07-20',
      { title: '手动' }
    );
    expect(groups).toHaveLength(1);
    expect(groups[0].planDate).toBe('2026-07-20');
    expect(groups[0].items).toHaveLength(2);
    expect(groups[0].title).toBe('手动');
  });

  it('spreads items round-robin across multi-day window', () => {
    const groups = distributeItemsAcrossWindow(
      [
        { resourceType: 'question', resourceId: 1, title: 'a' },
        { resourceType: 'question', resourceId: 2, title: 'b' },
        { resourceType: 'question', resourceId: 3, title: 'c' },
        { resourceType: 'question', resourceId: 4, title: 'd' },
        { resourceType: 'question', resourceId: 5, title: 'e' }
      ],
      '2026-07-20',
      '2026-07-22',
      { title: '复习' }
    );
    expect(groups).toHaveLength(3);
    expect(groups.map((g) => g.planDate)).toEqual(['2026-07-20', '2026-07-21', '2026-07-22']);
    expect(groups[0].items.map((i) => i.resourceId)).toEqual([1, 4]);
    expect(groups[1].items.map((i) => i.resourceId)).toEqual([2, 5]);
    expect(groups[2].items.map((i) => i.resourceId)).toEqual([3]);
    expect(groups[0].title).toContain('1/3');
  });
});

describe('truncateTitle', () => {
  it('returns short text unchanged and trims whitespace', () => {
    expect(truncateTitle('短标题')).toBe('短标题');
    expect(truncateTitle('  hello  ')).toBe('hello');
  });

  it('cuts to max length without ellipsis (default 80)', () => {
    const long = 'a'.repeat(100);
    expect(truncateTitle(long)).toBe('a'.repeat(80));
    expect(truncateTitle(long, 10)).toBe('a'.repeat(10));
  });

  it('treats empty and blank as empty string', () => {
    expect(truncateTitle('')).toBe('');
    expect(truncateTitle('   ')).toBe('');
  });
});

describe('planStudyPath', () => {
  it('builds quiz path for questions', () => {
    expect(planStudyPath({ resourceType: 'question', resourceId: 5, id: 10 })).toBe(
      '/quiz?questionIds=5&planItemId=10'
    );
  });

  it('builds knowledge path for knowledge points', () => {
    expect(planStudyPath({ resourceType: 'knowledge_point', resourceId: 7, id: 11 })).toBe(
      '/knowledge?pointIds=7&planItemId=11'
    );
  });

  it('builds notebooks path for note pages', () => {
    expect(planStudyPath({ resourceType: 'note_page', resourceId: 9, id: 12 })).toBe(
      '/notebooks?pageId=9&planItemId=12'
    );
  });

  it('appends planGroupId when groupId is provided', () => {
    expect(planStudyPath({ resourceType: 'question', resourceId: 5, id: 10 }, 3)).toBe(
      '/quiz?questionIds=5&planItemId=10&planGroupId=3'
    );
    expect(planStudyPath({ resourceType: 'knowledge_point', resourceId: 7, id: 11 }, 4)).toBe(
      '/knowledge?pointIds=7&planItemId=11&planGroupId=4'
    );
  });

  it('appends planDate when provided', () => {
    expect(planStudyPath({ resourceType: 'question', resourceId: 5, id: 10 }, 3, '2026-07-19')).toBe(
      '/quiz?questionIds=5&planItemId=10&planGroupId=3&planDate=2026-07-19'
    );
  });
});

describe('collectTodoQuestionIds / planQuizPath', () => {
  it('collects distinct todo questions and skips non-questions, done, and missing', () => {
    const ids = collectTodoQuestionIds([
      { resourceType: 'question', resourceId: 1, status: 'todo' },
      { resourceType: 'question', resourceId: 1, status: 'todo' },
      { resourceType: 'question', resourceId: 2, status: 'done' },
      { resourceType: 'question', resourceId: 3, status: 'todo', resourceMissing: true },
      { resourceType: 'knowledge_point', resourceId: 9, status: 'todo' },
      { resourceType: 'question', resourceId: 4, status: 'todo' }
    ]);
    expect(ids).toEqual([1, 4]);
  });

  it('flattens groups for day-level collection', () => {
    const ids = collectTodoQuestionIdsFromGroups([
      {
        items: [
          { id: 1, groupId: 1, planDate: '2026-07-19', resourceType: 'question', resourceId: 10, title: 'a', status: 'todo' },
          { id: 2, groupId: 1, planDate: '2026-07-19', resourceType: 'note_page', resourceId: 1, title: 'n', status: 'todo' }
        ]
      },
      {
        items: [
          { id: 3, groupId: 2, planDate: '2026-07-19', resourceType: 'question', resourceId: 11, title: 'b', status: 'todo' }
        ]
      }
    ] as never);
    expect(ids).toEqual([10, 11]);
  });

  it('builds quiz path with comma-separated ids', () => {
    expect(planQuizPath([5, 6], { planGroupId: 3, planDate: '2026-07-19' })).toBe(
      '/quiz?questionIds=5%2C6&planGroupId=3&planDate=2026-07-19'
    );
  });

  it('throws when no question ids', () => {
    expect(() => planQuizPath([])).toThrow('没有可刷的题目');
  });
});

describe('collectTodoKnowledgePointIds / planKnowledgePath', () => {
  it('collects distinct todo knowledge points', () => {
    const ids = collectTodoKnowledgePointIds([
      { resourceType: 'knowledge_point', resourceId: 1, status: 'todo' },
      { resourceType: 'knowledge_point', resourceId: 1, status: 'todo' },
      { resourceType: 'knowledge_point', resourceId: 2, status: 'done' },
      { resourceType: 'question', resourceId: 9, status: 'todo' },
      { resourceType: 'knowledge_point', resourceId: 3, status: 'todo', resourceMissing: true },
      { resourceType: 'knowledge_point', resourceId: 4, status: 'todo' }
    ]);
    expect(ids).toEqual([1, 4]);
  });

  it('flattens groups for day-level collection', () => {
    const ids = collectTodoKnowledgePointIdsFromGroups([
      {
        items: [
          {
            id: 1,
            groupId: 1,
            planDate: '2026-07-19',
            resourceType: 'knowledge_point',
            resourceId: 10,
            title: 'a',
            status: 'todo'
          }
        ]
      },
      {
        items: [
          {
            id: 2,
            groupId: 2,
            planDate: '2026-07-19',
            resourceType: 'knowledge_point',
            resourceId: 11,
            title: 'b',
            status: 'todo'
          }
        ]
      }
    ] as never);
    expect(ids).toEqual([10, 11]);
  });

  it('builds knowledge path with comma-separated ids', () => {
    expect(planKnowledgePath([5, 6], { planGroupId: 3, planDate: '2026-07-19' })).toBe(
      '/knowledge?pointIds=5%2C6&planGroupId=3&planDate=2026-07-19'
    );
  });

  it('throws when no point ids', () => {
    expect(() => planKnowledgePath([])).toThrow('没有可背的知识点');
  });
});
