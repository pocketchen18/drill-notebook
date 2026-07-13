import type { QuestionType } from './types';

export function normalizeAnswer(value: string | string[]): string {
  const values = Array.isArray(value) ? value : value.split(',');
  return [...new Set(values.map((item) => item.trim().toUpperCase()).filter(Boolean))].sort().join(',');
}

export function isAnswerCorrect(type: QuestionType, answer: string, expected: string): boolean {
  if (type === 'single') return answer.trim().toUpperCase() === expected.trim().toUpperCase();
  if (type === 'multiple') return normalizeAnswer(answer) === normalizeAnswer(expected);
  if (type === 'fill') return normalizeFillAnswer(answer) === normalizeFillAnswer(expected);
  if (type === 'true_false') return normalizeTrueFalse(answer) === normalizeTrueFalse(expected);
  return false;
}

export function normalizeFillAnswer(value: string): string { return value.normalize('NFKC').trim().replace(/\s+/g, ' ').toLocaleLowerCase(); }
export function normalizeTrueFalse(value: string): 'true' | 'false' | undefined {
  const normalized = normalizeFillAnswer(value);
  if (['true', 't', '1', '对', '是', '正确'].includes(normalized)) return 'true';
  if (['false', 'f', '0', '错', '否', '错误'].includes(normalized)) return 'false';
  return undefined;
}

export const QUESTION_TYPE_LABELS: Record<QuestionType, string> = {
  single: '单选',
  multiple: '多选',
  fill: '填空',
  true_false: '判断',
  essay: '解答'
};

export const QUESTION_TYPE_COLORS: Record<QuestionType, string> = {
  single: 'arcoblue',
  multiple: 'purple',
  fill: 'orange',
  true_false: 'cyan',
  essay: 'green'
};

export function questionTypeLabel(type: QuestionType): string {
  return QUESTION_TYPE_LABELS[type] ?? type;
}

export function questionTypeColor(type: QuestionType): string {
  return QUESTION_TYPE_COLORS[type] ?? 'arcoblue';
}
