import { describe, expect, it } from 'vitest';
import { buildDayQueueSteps } from './dayQueueSession';
import type { TodayQueueItem } from './study';

function item(
  partial: Pick<TodayQueueItem, 'id' | 'resourceType' | 'resourceId'> &
    Partial<TodayQueueItem>
): TodayQueueItem {
  return {
    kind: 'plan',
    title: '',
    ...partial
  };
}

describe('buildDayQueueSteps', () => {
  it('groups plan questions as quiz without fromCurve', () => {
    const steps = buildDayQueueSteps([
      item({ id: 'a', resourceType: 'question', resourceId: 1, kind: 'plan' }),
      item({ id: 'b', resourceType: 'knowledge_point', resourceId: 10 }),
      item({ id: 'c', resourceType: 'question', resourceId: 2, kind: 'plan' }),
      item({ id: 'd', resourceType: 'question', resourceId: 1, kind: 'plan' }),
      item({ id: 'e', resourceType: 'note_page', resourceId: 100 }),
      item({ id: 'f', resourceType: 'knowledge_point', resourceId: 11 })
    ]);
    expect(steps).toEqual([
      { kind: 'quiz', questionIds: [1, 2], fromCurve: false },
      { kind: 'knowledge', pointIds: [10, 11] },
      { kind: 'note', pageIds: [100] }
    ]);
  });

  it('routes curve questions to quiz with fromCurve for right/wrong SRS', () => {
    const steps = buildDayQueueSteps([
      item({ id: 'a', resourceType: 'question', resourceId: 1, kind: 'due', due: true, scheduleId: 9 }),
      item({ id: 'b', resourceType: 'question', resourceId: 2, kind: 'plan' })
    ]);
    expect(steps).toEqual([
      { kind: 'quiz', questionIds: [1], fromCurve: true },
      { kind: 'quiz', questionIds: [2], fromCurve: false }
    ]);
  });

  it('returns empty when no valid items', () => {
    expect(buildDayQueueSteps([])).toEqual([]);
  });
});
