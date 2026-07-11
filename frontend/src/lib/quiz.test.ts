import { describe, expect, it } from 'vitest';
import { isAnswerCorrect, normalizeAnswer } from './quiz';

describe('quiz grading helpers', () => {
  it('normalizes multi-select answers as an order-independent set', () => {
    expect(normalizeAnswer('c, a, c')).toBe('A,C');
    expect(isAnswerCorrect('multiple', 'c,a', 'A,C')).toBe(true);
  });

  it('grades single choice case-insensitively', () => {
    expect(isAnswerCorrect('single', ' b ', 'B')).toBe(true);
    expect(isAnswerCorrect('single', 'A', 'B')).toBe(false);
  });
});
