import { get, post, put, del } from './api';
import type { Question, KnowledgePoint } from './types';

export interface SpacedRepetitionConfig {
  id: number;
  name: string;
  isDefault: boolean;
  intervals: Record<string, number>;
  initialEf: number;
  minimumEf: number;
  maxIntervalDays: number;
  wrongStrategy: 'reset' | 'reduce_half' | 'reduce_quarter' | 'fixed';
  wrongFixedDays: number;
  dailyNewLimit: number;
  dailyReviewLimit: number;
  priorityMode: 'due_first' | 'random' | 'worst_first' | 'mixed';
  createdAt?: string;
}

export interface ReviewSchedule {
  id: number;
  itemType: 'question' | 'knowledge_point';
  itemId: number;
  configId?: number;
  ef: number;
  interval: number;
  repetitions: number;
  nextReview?: string;
  lastReview?: string;
  lastQuality?: number;
  totalReviews: number;
  totalWrong: number;
  streakCorrect: number;
  status: 'new' | 'learning' | 'review' | 'mastered';
  createdAt?: string;
  updatedAt?: string;
}

export interface ReviewLog {
  id: number;
  scheduleId: number;
  quality: number;
  responseTime?: number;
  scheduledInterval?: number;
  actualInterval?: number;
  source: string;
  reviewedAt?: string;
}

export interface DueItem {
  id: number;
  itemType: string;
  itemId: number;
  configId?: number;
  ef: number;
  interval: number;
  repetitions: number;
  nextReview?: string;
  lastReview?: string;
  lastQuality?: number;
  totalReviews: number;
  totalWrong: number;
  streakCorrect: number;
  status: string;
  question?: Question;
  knowledgePoint?: KnowledgePoint;
}

export interface ReviewStats {
  totalEnrolled: number;
  newCount: number;
  learningCount: number;
  reviewCount: number;
  masteredCount: number;
  dueToday: number;
  newToday: number;
  dailyStats: Array<{ review_date: string; total: number; passed: number }>;
}

export interface ScheduleDetail {
  enrolled: boolean;
  id?: number;
  itemType?: string;
  itemId?: number;
  ef?: number;
  interval?: number;
  repetitions?: number;
  nextReview?: string;
  lastReview?: string;
  lastQuality?: number;
  totalReviews?: number;
  totalWrong?: number;
  streakCorrect?: number;
  status?: string;
  recentLogs?: ReviewLog[];
}

// ===================== API =====================

export function listConfigs() {
  return get<SpacedRepetitionConfig[]>('/api/review/configs');
}

export function getConfig(id: number) {
  return get<SpacedRepetitionConfig>(`/api/review/configs/${id}`);
}

export function createConfig(config: Partial<SpacedRepetitionConfig>) {
  return post<{ id: number; ok: boolean }>('/api/review/configs', config);
}

export function updateConfig(id: number, config: Partial<SpacedRepetitionConfig>) {
  return put<{ ok: boolean }>(`/api/review/configs/${id}`, config);
}

export function deleteConfig(id: number) {
  return del<{ ok: boolean }>(`/api/review/configs/${id}`);
}

export function enrollItems(itemType: string, itemIds: number[], configId?: number) {
  return post<Array<{ itemId: number; scheduleId: number; status: string }>>(
    '/api/review/enroll', { itemType, itemIds, configId }
  );
}

export function unenrollItems(itemType: string, itemIds: number[]) {
  return post<{ ok: boolean }>('/api/review/unenroll', { itemType, itemIds });
}

export function getDueItems(params?: {
  type?: string;
  configId?: number;
  newLimit?: number;
  reviewLimit?: number;
  priority?: string;
}) {
  const search = new URLSearchParams();
  if (params?.type) search.set('type', params.type);
  if (params?.configId) search.set('configId', String(params.configId));
  if (params?.newLimit) search.set('newLimit', String(params.newLimit));
  if (params?.reviewLimit) search.set('reviewLimit', String(params.reviewLimit));
  if (params?.priority) search.set('priority', params.priority);
  return get<DueItem[]>(`/api/review/due?${search.toString()}`);
}

export function submitReview(scheduleId: number, quality: number, responseTime?: number, source?: string) {
  return post<{
    logId: number;
    scheduleId: number;
    ef: number;
    interval: number;
    repetitions: number;
    nextReview: string;
    status: string;
  }>('/api/review/submit', { scheduleId, quality, responseTime, source });
}

export function getReviewStats(type?: string, configId?: number) {
  const search = new URLSearchParams();
  if (type) search.set('type', type);
  if (configId) search.set('configId', String(configId));
  return get<ReviewStats>(`/api/review/stats?${search.toString()}`);
}

export function getScheduleDetail(itemType: string, itemId: number) {
  return get<ScheduleDetail>(`/api/review/schedule/${itemType}/${itemId}`);
}

export function resetSchedule(itemType: string, itemId: number) {
  return post<{ ok: boolean }>(`/api/review/reset/${itemType}/${itemId}`, {});
}

// ===================== 辅助 =====================

export const QUALITY_LABELS: Record<number, string> = {
  0: '完全忘了',
  2: '困难',
  3: '一般',
  4: '简单',
  5: '太简单了',
};

export const STATUS_LABELS: Record<string, string> = {
  new: '新加入',
  learning: '学习中',
  review: '复习中',
  mastered: '已掌握',
};

export function statusColor(status: string): string {
  switch (status) {
    case 'new': return 'gray';
    case 'learning': return 'orangered';
    case 'review': return 'arcoblue';
    case 'mastered': return 'green';
    default: return 'gray';
  }
}

export function dueStatusColor(schedule: DueItem): string {
  if (schedule.status === 'new') return 'gray';
  if (!schedule.nextReview) return 'gray';
  const next = new Date(schedule.nextReview.replace(' ', 'T'));
  const now = new Date();
  if (next < now) return 'red';
  const diffMs = next.getTime() - now.getTime();
  if (diffMs < 24 * 60 * 60 * 1000) return 'orange';
  return 'green';
}
