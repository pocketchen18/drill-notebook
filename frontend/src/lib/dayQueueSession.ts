import type { TodayQueueItem } from './study';
import { planKnowledgePath, planNotePath, planQuizPath } from './studyPlan';

const STORAGE_KEY = 'drill.dayQueueSession';

export type DayQueueStep =
  | { kind: 'quiz'; questionIds: number[]; fromCurve?: boolean }
  | { kind: 'knowledge'; pointIds: number[] }
  | { kind: 'note'; pageIds: number[] };

export type DayQueueSession = {
  date: string;
  steps: DayQueueStep[];
  stepIndex: number;
};

function uniqueOrdered(ids: number[]): number[] {
  const seen = new Set<number>();
  const out: number[] = [];
  for (const id of ids) {
    if (!Number.isFinite(id) || id <= 0 || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

function isCurveItem(item: TodayQueueItem): boolean {
  return (
    item.kind === 'due' ||
    item.kind === 'plan_and_due' ||
    item.due === true ||
    item.overdue === true ||
    item.scheduleId != null ||
    item.srsStatus === 'new' ||
    item.isNew === true
  );
}

/** Build steps preserving first-seen order within each resource type. */
export function buildDayQueueSteps(items: TodayQueueItem[]): DayQueueStep[] {
  // Curve questions first (SRS), then pure plan questions, then KP / notes.
  const curveQuestions: number[] = [];
  const planQuestions: number[] = [];
  const points: number[] = [];
  const notes: number[] = [];
  const seenCurve = new Set<number>();
  const seenPlan = new Set<number>();
  const seenK = new Set<number>();
  const seenN = new Set<number>();

  for (const item of items) {
    const id = item.resourceId;
    if (!Number.isFinite(id) || id <= 0) continue;
    if (item.resourceType === 'question') {
      if (isCurveItem(item)) {
        if (!seenCurve.has(id)) {
          seenCurve.add(id);
          curveQuestions.push(id);
        }
      } else if (!seenPlan.has(id) && !seenCurve.has(id)) {
        seenPlan.add(id);
        planQuestions.push(id);
      }
    } else if (item.resourceType === 'knowledge_point') {
      if (!seenK.has(id)) {
        seenK.add(id);
        points.push(id);
      }
    } else if (item.resourceType === 'note_page') {
      if (!seenN.has(id)) {
        seenN.add(id);
        notes.push(id);
      }
    }
  }

  const steps: DayQueueStep[] = [];
  if (curveQuestions.length) {
    steps.push({ kind: 'quiz', questionIds: uniqueOrdered(curveQuestions), fromCurve: true });
  }
  if (planQuestions.length) {
    steps.push({ kind: 'quiz', questionIds: uniqueOrdered(planQuestions), fromCurve: false });
  }
  if (points.length) steps.push({ kind: 'knowledge', pointIds: uniqueOrdered(points) });
  if (notes.length) steps.push({ kind: 'note', pageIds: uniqueOrdered(notes) });
  return steps;
}

export function pathForDayQueueStep(step: DayQueueStep, date: string): string {
  if (step.kind === 'quiz') {
    const base = planQuizPath(step.questionIds, { planDate: date });
    const params = new URLSearchParams(base.split('?')[1] ?? '');
    params.set('autoStart', '1');
    params.set('dayQueue', '1');
    if (step.fromCurve) params.set('fromQueue', '1');
    return `/quiz?${params.toString()}`;
  }
  if (step.kind === 'knowledge') {
    const base = planKnowledgePath(step.pointIds, { planDate: date });
    return `${base}&dayQueue=1&fromQueue=1`;
  }
  const base = planNotePath(step.pageIds, { planDate: date });
  return `${base}&dayQueue=1`;
}

export function readDayQueueSession(): DayQueueSession | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as DayQueueSession;
    if (!parsed?.date || !Array.isArray(parsed.steps) || !parsed.steps.length) return null;
    if (parsed.stepIndex < 0 || parsed.stepIndex >= parsed.steps.length) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function writeDayQueueSession(session: DayQueueSession): void {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function clearDayQueueSession(): void {
  sessionStorage.removeItem(STORAGE_KEY);
}

/** Create session and return path for first step. */
export function beginDayQueueSession(date: string, items: TodayQueueItem[]): string {
  const steps = buildDayQueueSteps(items);
  if (!steps.length) {
    throw new Error('队列为空，没有可学习的内容');
  }
  const session: DayQueueSession = { date, steps, stepIndex: 0 };
  writeDayQueueSession(session);
  return pathForDayQueueStep(steps[0], date);
}

/**
 * After finishing current step, advance and return next path.
 * Returns null when all steps done (and clears storage).
 */
export function advanceDayQueueSession(): string | null {
  const session = readDayQueueSession();
  if (!session) return null;
  const nextIndex = session.stepIndex + 1;
  if (nextIndex >= session.steps.length) {
    clearDayQueueSession();
    return null;
  }
  const next: DayQueueSession = { ...session, stepIndex: nextIndex };
  writeDayQueueSession(next);
  return pathForDayQueueStep(next.steps[nextIndex], next.date);
}

export function dayQueueProgressLabel(session: DayQueueSession): string {
  const total = session.steps.length;
  const current = session.stepIndex + 1;
  const step = session.steps[session.stepIndex];
  const kindLabel =
    step?.kind === 'quiz'
      ? step.fromCurve
        ? '刷题（记忆曲线）'
        : '刷题'
      : step?.kind === 'knowledge'
        ? '背知识点'
        : '笔记';
  return `今日任务 ${current}/${total} · ${kindLabel} · ${session.date}`;
}
