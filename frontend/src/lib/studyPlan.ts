import type { StudyPlanGroup, StudyPlanItem } from './types';

export function formatYmd(d: Date): string {
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export const DEFAULT_PLAN_SPAN_DAYS = 5;

export interface PlanWindow {
  startDate: string;
  endDate: string;
  spanDays: number;
  defaultSpanDays: number;
  endDateProvided: boolean;
  message: string | null;
}

function parseYmd(ymd: string): Date {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd.trim());
  if (!m) throw new Error('日期格式无效');
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
}

export function addDaysYmd(ymd: string, days: number): string {
  const d = parseYmd(ymd);
  d.setDate(d.getDate() + days);
  return formatYmd(d);
}

export function diffDaysInclusive(startYmd: string, endYmd: string): number {
  const a = parseYmd(startYmd).getTime();
  const b = parseYmd(endYmd).getTime();
  if (b < a) throw new Error('终止日不能早于起始日');
  return Math.floor((b - a) / 86400000) + 1;
}

/** endYmd empty/undefined → default span from start */
export function resolvePlanWindow(startYmd: string, endYmd?: string | null): PlanWindow {
  parseYmd(startYmd); // validate
  const endRaw = endYmd?.trim() || '';
  if (!endRaw) {
    const endDate = addDaysYmd(startYmd, DEFAULT_PLAN_SPAN_DAYS - 1);
    return {
      startDate: startYmd,
      endDate,
      spanDays: DEFAULT_PLAN_SPAN_DAYS,
      defaultSpanDays: DEFAULT_PLAN_SPAN_DAYS,
      endDateProvided: false,
      message: null
    };
  }
  const spanDays = diffDaysInclusive(startYmd, endRaw);
  return {
    startDate: startYmd,
    endDate: endRaw,
    spanDays,
    defaultSpanDays: DEFAULT_PLAN_SPAN_DAYS,
    endDateProvided: true,
    message: null
  };
}

export function formatPlanWindowLabel(w: PlanWindow): string {
  if (w.endDateProvided) {
    return `排期窗口：${w.startDate} ～ ${w.endDate}（共 ${w.spanDays} 天）`;
  }
  return `未设终止日，将使用默认窗口：${w.startDate} ～ ${w.endDate}（共 ${w.spanDays} 天）。需要更长或更短请填写终止日。`;
}

export type ManualPlanItem = {
  resourceType: 'question' | 'knowledge_point' | 'note_page' | string;
  resourceId: number;
  title: string;
};

export type ManualScheduleGroup = {
  planDate: string;
  title: string;
  note?: string;
  items: ManualPlanItem[];
};

/**
 * Spread items evenly across [start, end] inclusive (round-robin by day index).
 * Used when user schedules manually with a date range (no AI).
 */
export function distributeItemsAcrossWindow(
  items: ManualPlanItem[],
  startYmd: string,
  endYmd: string,
  options?: { title?: string; note?: string }
): ManualScheduleGroup[] {
  if (!items.length) return [];
  const span = diffDaysInclusive(startYmd, endYmd);
  const buckets: ManualPlanItem[][] = Array.from({ length: span }, () => []);
  items.forEach((item, index) => {
    buckets[index % span].push(item);
  });
  const baseTitle = options?.title?.trim() || '手动安排';
  const note = options?.note?.trim() || undefined;
  const groups: ManualScheduleGroup[] = [];
  for (let offset = 0; offset < span; offset++) {
    const dayItems = buckets[offset];
    if (!dayItems.length) continue;
    groups.push({
      planDate: addDaysYmd(startYmd, offset),
      title: span === 1 ? baseTitle : `${baseTitle}（${offset + 1}/${span}）`,
      note,
      items: dayItems
    });
  }
  return groups;
}

export function todayYmd(): string {
  return formatYmd(new Date());
}

export function tomorrowYmd(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return formatYmd(d);
}

export function truncateTitle(text: string, max = 80): string {
  const trimmed = text.trim();
  if (trimmed.length <= max) return trimmed;
  return trimmed.slice(0, max);
}

export function monthRange(year: number, monthIndex0: number): { from: string; to: string } {
  const from = formatYmd(new Date(year, monthIndex0, 1));
  const to = formatYmd(new Date(year, monthIndex0 + 1, 0));
  return { from, to };
}

type CollectablePlanItem = Pick<
  StudyPlanItem,
  'resourceType' | 'resourceId' | 'status' | 'resourceMissing'
>;

function collectTodoResourceIds(
  items: CollectablePlanItem[],
  resourceType: StudyPlanItem['resourceType'],
  options?: { includeDone?: boolean; includeMissing?: boolean }
): number[] {
  const includeDone = options?.includeDone === true;
  const includeMissing = options?.includeMissing === true;
  const seen = new Set<number>();
  const ids: number[] = [];
  for (const item of items) {
    if (item.resourceType !== resourceType) continue;
    if (!includeDone && item.status !== 'todo') continue;
    if (!includeMissing && item.resourceMissing) continue;
    if (seen.has(item.resourceId)) continue;
    seen.add(item.resourceId);
    ids.push(item.resourceId);
  }
  return ids;
}

/** Collect distinct question resource ids from plan items (todo only by default). */
export function collectTodoQuestionIds(
  items: CollectablePlanItem[],
  options?: { includeDone?: boolean; includeMissing?: boolean }
): number[] {
  return collectTodoResourceIds(items, 'question', options);
}

export function collectTodoQuestionIdsFromGroups(
  groups: Array<Pick<StudyPlanGroup, 'items'>>,
  options?: { includeDone?: boolean; includeMissing?: boolean }
): number[] {
  const flat = groups.flatMap((group) => group.items ?? []);
  return collectTodoQuestionIds(flat, options);
}

/** Collect distinct knowledge-point ids from plan items (todo only by default). */
export function collectTodoKnowledgePointIds(
  items: CollectablePlanItem[],
  options?: { includeDone?: boolean; includeMissing?: boolean }
): number[] {
  return collectTodoResourceIds(items, 'knowledge_point', options);
}

export function collectTodoKnowledgePointIdsFromGroups(
  groups: Array<Pick<StudyPlanGroup, 'items'>>,
  options?: { includeDone?: boolean; includeMissing?: boolean }
): number[] {
  const flat = groups.flatMap((group) => group.items ?? []);
  return collectTodoKnowledgePointIds(flat, options);
}

/** Collect distinct note-page ids from plan items (todo only by default). */
export function collectTodoNotePageIds(
  items: CollectablePlanItem[],
  options?: { includeDone?: boolean; includeMissing?: boolean }
): number[] {
  return collectTodoResourceIds(items, 'note_page', options);
}

export function collectTodoNotePageIdsFromGroups(
  groups: Array<Pick<StudyPlanGroup, 'items'>>,
  options?: { includeDone?: boolean; includeMissing?: boolean }
): number[] {
  const flat = groups.flatMap((group) => group.items ?? []);
  return collectTodoNotePageIds(flat, options);
}

function uniquePositiveIds(ids: number[]): number[] {
  return [...new Set(ids.filter((id) => Number.isFinite(id) && id > 0))];
}

export function planQuizPath(
  questionIds: number[],
  options?: { planGroupId?: number; planDate?: string }
): string {
  const unique = uniquePositiveIds(questionIds);
  if (!unique.length) {
    throw new Error('没有可刷的题目');
  }
  const params = new URLSearchParams();
  params.set('questionIds', unique.join(','));
  if (options?.planGroupId != null) {
    params.set('planGroupId', String(options.planGroupId));
  }
  if (options?.planDate) {
    params.set('planDate', options.planDate);
  }
  return `/quiz?${params.toString()}`;
}

export function planKnowledgePath(
  pointIds: number[],
  options?: { planGroupId?: number; planDate?: string }
): string {
  const unique = uniquePositiveIds(pointIds);
  if (!unique.length) {
    throw new Error('没有可背的知识点');
  }
  const params = new URLSearchParams();
  params.set('pointIds', unique.join(','));
  if (options?.planGroupId != null) {
    params.set('planGroupId', String(options.planGroupId));
  }
  if (options?.planDate) {
    params.set('planDate', options.planDate);
  }
  return `/knowledge?${params.toString()}`;
}

/** Open notebook on the first page; optional multi ids kept for messaging only. */
export function planNotePath(
  pageIds: number[],
  options?: { planGroupId?: number; planDate?: string; planItemId?: number }
): string {
  const unique = uniquePositiveIds(pageIds);
  if (!unique.length) {
    throw new Error('没有可复习的笔记');
  }
  const params = new URLSearchParams();
  params.set('pageId', String(unique[0]));
  if (unique.length > 1) {
    params.set('pageIds', unique.join(','));
  }
  if (options?.planItemId != null) {
    params.set('planItemId', String(options.planItemId));
  }
  if (options?.planGroupId != null) {
    params.set('planGroupId', String(options.planGroupId));
  }
  if (options?.planDate) {
    params.set('planDate', options.planDate);
  }
  return `/notebooks?${params.toString()}`;
}

export function planStudyPath(
  item: Pick<StudyPlanItem, 'resourceType' | 'resourceId' | 'id'>,
  groupId?: number,
  planDate?: string
): string {
  const params = new URLSearchParams();
  if (item.resourceType === 'question') {
    params.set('questionIds', String(item.resourceId));
  } else if (item.resourceType === 'knowledge_point') {
    params.set('pointIds', String(item.resourceId));
  } else {
    params.set('pageId', String(item.resourceId));
  }
  params.set('planItemId', String(item.id));
  if (groupId != null) {
    params.set('planGroupId', String(groupId));
  }
  if (planDate) {
    params.set('planDate', planDate);
  }
  const base =
    item.resourceType === 'question'
      ? '/quiz'
      : item.resourceType === 'knowledge_point'
        ? '/knowledge'
        : '/notebooks';
  return `${base}?${params.toString()}`;
}
