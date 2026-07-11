export function normalizeAnswer(value: string | string[]): string {
  const values = Array.isArray(value) ? value : value.split(',');
  return [...new Set(values.map((item) => item.trim().toUpperCase()).filter(Boolean))].sort().join(',');
}

export function isAnswerCorrect(type: 'single' | 'multiple', answer: string, expected: string): boolean {
  if (type === 'single') return answer.trim().toUpperCase() === expected.trim().toUpperCase();
  return normalizeAnswer(answer) === normalizeAnswer(expected);
}
