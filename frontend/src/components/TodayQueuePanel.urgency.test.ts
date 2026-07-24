import { describe, expect, it } from 'vitest';
import { resolveUrgencyKind } from './TodayQueuePanel';
import type { TodayQueueItem } from '../lib/study';

const TODAY = '2026-07-21';
const FUTURE = '2026-07-25';

function item(partial: Partial<TodayQueueItem> & Pick<TodayQueueItem, 'id'>): TodayQueueItem {
  return {
    kind: 'due',
    resourceType: 'question',
    resourceId: 1,
    title: 't',
    due: true,
    overdue: false,
    ...partial
  };
}

describe('resolveUrgencyKind', () => {
  it('marks real-today overdue as 逾期', () => {
    expect(
      resolveUrgencyKind(
        item({ id: '1', overdue: true, nextReview: '2026-07-18' }),
        TODAY,
        TODAY
      )
    ).toBe('overdue');
  });

  it('marks real-today due (not overdue) as 到期', () => {
    expect(
      resolveUrgencyKind(item({ id: '1', nextReview: TODAY }), TODAY, TODAY)
    ).toBe('due');
  });

  it('on a future day, items whose next_review is that day are 到期 not 提前', () => {
    expect(
      resolveUrgencyKind(item({ id: '1', nextReview: FUTURE }), FUTURE, TODAY)
    ).toBe('due');
  });

  it('on a future day, items due earlier than that day but not overdue today are 提前', () => {
    // next_review is 2026-07-22 — after real today, before selected future day
    expect(
      resolveUrgencyKind(
        item({ id: '1', nextReview: '2026-07-22' }),
        FUTURE,
        TODAY
      )
    ).toBe('backlog');
  });

  it('on a future day without nextReview, prefers 到期 over 提前', () => {
    expect(resolveUrgencyKind(item({ id: '1' }), FUTURE, TODAY)).toBe('due');
  });

  it('pure plan rows have no urgency tag', () => {
    expect(
      resolveUrgencyKind(
        item({
          id: 'p1',
          kind: 'plan',
          due: false,
          planItemId: 9
        }),
        FUTURE,
        TODAY
      )
    ).toBeNull();
  });
});
