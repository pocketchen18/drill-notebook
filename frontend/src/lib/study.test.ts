import { describe, expect, it } from 'vitest';
import { filterQuestions, moveId, shuffleIds } from './study';
import type { Question } from './types';

const questions: Question[] = [
  { id: 1, bankId: 1, type: 'single', stem: 'Java 集合', options: [], chapter: '基础', tags: ['集合'] },
  { id: 2, bankId: 1, type: 'multiple', stem: '线程安全', options: [], chapter: '并发', tags: ['线程', '安全'] }
];

describe('study planning', () => {
  it('filters by type, chapter, knowledge tag, and search', () => {
    expect(filterQuestions(questions, { search: '线程', types: ['multiple'], chapters: ['并发'], tags: ['安全'] }).map((item) => item.id)).toEqual([2]);
  });

  it('moves selected items while preserving the rest', () => {
    expect(moveId([1, 2, 3], 2, -1)).toEqual([2, 1, 3]);
    expect(moveId([1, 2, 3], 1, -1)).toEqual([1, 2, 3]);
  });

  it('supports deterministic custom shuffle', () => {
    expect(shuffleIds([1, 2, 3], () => 0)).toEqual([2, 3, 1]);
  });
});
