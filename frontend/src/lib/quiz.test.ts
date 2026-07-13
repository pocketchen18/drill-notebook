import { describe, expect, it } from 'vitest';
import { isAnswerCorrect, normalizeAnswer, questionTypeLabel, questionTypeColor } from './quiz';

describe('quiz grading helpers', () => {
  it('normalizes multi-select answers as an order-independent set', () => {
    expect(normalizeAnswer('c, a, c')).toBe('A,C');
    expect(isAnswerCorrect('multiple', 'c,a', 'A,C')).toBe(true);
  });

  it('grades single choice case-insensitively', () => {
    expect(isAnswerCorrect('single', ' b ', 'B')).toBe(true);
    expect(isAnswerCorrect('single', 'A', 'B')).toBe(false);
  });

  it('grades true_false case-insensitively', () => {
    expect(isAnswerCorrect('true_false', 'true', 'TRUE')).toBe(true);
    expect(isAnswerCorrect('true_false', 'FALSE', 'false')).toBe(true);
    expect(isAnswerCorrect('true_false', 'true', 'false')).toBe(false);
  });

  it('grades fill case-insensitively', () => {
    expect(isAnswerCorrect('fill', 'Hello', 'hello')).toBe(true);
    expect(isAnswerCorrect('fill', 'world', 'earth')).toBe(false);
  });

  it('does not deterministically grade essay', () => {
    expect(isAnswerCorrect('essay', 'any', 'any')).toBe(false);
  });

  it('provides labels and colors for all five types', () => {
    expect(questionTypeLabel('single')).toBe('单选');
    expect(questionTypeLabel('multiple')).toBe('多选');
    expect(questionTypeLabel('fill')).toBe('填空');
    expect(questionTypeLabel('true_false')).toBe('判断');
    expect(questionTypeLabel('essay')).toBe('解答');
    expect(questionTypeColor('single')).toBe('arcoblue');
    expect(questionTypeColor('multiple')).toBe('purple');
    expect(questionTypeColor('fill')).toBe('orange');
    expect(questionTypeColor('true_false')).toBe('cyan');
    expect(questionTypeColor('essay')).toBe('green');
  });
});
