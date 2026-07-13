import type { Question } from './types';

export interface QuestionFilters {
  search: string;
  types: string[];
  chapters: string[];
  tags: string[];
}

export function filterQuestions(questions: Question[], filters: QuestionFilters): Question[] {
  const search = filters.search.trim().toLowerCase();
  return questions.filter((question) => {
    if (filters.types.length && !filters.types.includes(question.type)) return false;
    if (filters.chapters.length && (!question.chapter || !filters.chapters.includes(question.chapter))) return false;
    if (filters.tags.length && !question.tags?.some((tag) => filters.tags.includes(tag))) return false;
    if (search && !`${question.stem} ${question.chapter ?? ''} ${(question.tags ?? []).join(' ')}`.toLowerCase().includes(search)) return false;
    return true;
  });
}

export function moveId(ids: number[], id: number, direction: -1 | 1): number[] {
  const index = ids.indexOf(id);
  const target = index + direction;
  if (index < 0 || target < 0 || target >= ids.length) return ids;
  const result = [...ids];
  [result[index], result[target]] = [result[target], result[index]];
  return result;
}

export function shuffleIds(ids: number[], random: () => number = Math.random): number[] {
  const result = [...ids];
  for (let index = result.length - 1; index > 0; index -= 1) {
    const target = Math.floor(random() * (index + 1));
    [result[index], result[target]] = [result[target], result[index]];
  }
  return result;
}
