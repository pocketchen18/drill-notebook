import { get, post } from './api';
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

/** Merged plan todo + SRS due item from GET /api/study/today */
export type TodayQueueItem = {
  id: string;
  kind: 'plan' | 'due' | 'plan_and_due';
  resourceType: string;
  resourceId: number;
  title: string;
  planItemId?: number;
  scheduleId?: number;
  due?: boolean;
  overdue?: boolean;
  srsStatus?: string;
  /** SRS next_review day (YYYY-MM-DD), when known. */
  nextReview?: string | null;
  /** Newly enrolled card (first review). */
  isNew?: boolean;
  groupId?: number;
  groupTitle?: string;
  note?: string | null;
};

export type TodayQueueResponse = {
  date: string;
  items: TodayQueueItem[];
  stats: Record<string, number>;
};

export type CompleteStudyBody = {
  resourceType: string;
  resourceId: number;
  quality?: number;
  isCorrect?: boolean;
  responseTime?: number;
  source?: string;
  planDate?: string;
  planItemId?: number;
  /** Prefer this schedule when present (queue due rows). */
  scheduleId?: number;
  configId?: number;
  /** Skip same-day extra short-circuit; second correct also advances SRS. */
  forceAdvance?: boolean;
};

export type CompleteStudySrsResult = {
  logId?: number;
  scheduleId?: number;
  nextReview?: string;
  status?: string;
  ef?: number;
  interval?: number;
  repetitions?: number;
  extra?: boolean;
};

export type CompleteStudyResult = {
  srs?: CompleteStudySrsResult | null;
  planItem?: unknown;
  extraPractice?: boolean;
  skippedSrs?: string;
};

export function fetchToday(date?: string) {
  const q = date ? `?date=${encodeURIComponent(date)}` : '';
  return get<TodayQueueResponse>(`/api/study/today${q}`);
}

export function completeStudy(body: CompleteStudyBody) {
  return post<CompleteStudyResult>('/api/study/complete', body);
}
