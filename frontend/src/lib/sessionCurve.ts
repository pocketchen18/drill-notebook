/**
 * 会话内记忆曲线：练习会话中答错条目按短间隔延迟重现的队列逻辑。
 *
 * 机制：
 * - 答错 → 该条目延迟 gap 个条目后重新插入队列（剩余不足则排到队尾）；
 * - 达到最大重复次数（maxRepeats，0 = 不限制）仍未过关 → 标记放弃，不再重现；
 * - 连续答对 passStreak 次 → 过关移出队列。
 *
 * 与跨天 SRS（review_schedule）相互独立：这里只影响当前会话内条目出现顺序。
 */

export interface SessionCurveConfig {
  /** 总开关：是否启用会话内延迟重现 */
  enabled: boolean;
  /** 答错后隔多少个条目再重现 */
  gap: number;
  /** 单条目最大重现次数；0 表示不限制（直到会为止） */
  maxRepeats: number;
  /** 需要连续答对多少次才算过关 */
  passStreak: number;
}

export const DEFAULT_SESSION_CURVE_CONFIG: SessionCurveConfig = {
  enabled: true,
  gap: 3,
  maxRepeats: 3,
  passStreak: 1
};

const LS_SESSION_CURVE = 'session.curveConfig';

function clampInt(value: unknown, fallback: number, min: number, max: number): number {
  const num = typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : fallback;
  return Math.min(max, Math.max(min, num));
}

export function normalizeSessionCurveConfig(raw: Partial<SessionCurveConfig> | null | undefined): SessionCurveConfig {
  return {
    enabled: typeof raw?.enabled === 'boolean' ? raw.enabled : DEFAULT_SESSION_CURVE_CONFIG.enabled,
    gap: clampInt(raw?.gap, DEFAULT_SESSION_CURVE_CONFIG.gap, 1, 50),
    maxRepeats: clampInt(raw?.maxRepeats, DEFAULT_SESSION_CURVE_CONFIG.maxRepeats, 0, 999),
    passStreak: clampInt(raw?.passStreak, DEFAULT_SESSION_CURVE_CONFIG.passStreak, 1, 10)
  };
}

export function readSessionCurveConfig(): SessionCurveConfig {
  try {
    const raw = localStorage.getItem(LS_SESSION_CURVE);
    if (!raw) return { ...DEFAULT_SESSION_CURVE_CONFIG };
    return normalizeSessionCurveConfig(JSON.parse(raw) as Partial<SessionCurveConfig>);
  } catch {
    return { ...DEFAULT_SESSION_CURVE_CONFIG };
  }
}

export function writeSessionCurveConfig(config: SessionCurveConfig): void {
  try {
    localStorage.setItem(LS_SESSION_CURVE, JSON.stringify(config));
  } catch {
    /* ignore quota / private mode */
  }
}

/** 队列中的一个出场条目；同一资源重现时会产生新条目。 */
export interface CurveEntry {
  entryId: string;
  resourceId: number;
  /** 第几遍出场；0 = 首遍 */
  attempt: number;
}

/** 单个资源在当前会话中的曲线状态。 */
export interface CurveItemState {
  /** 当前连续答对次数 */
  streak: number;
  /** 已重现次数 */
  repeats: number;
  /** 已终结（过关或放弃） */
  done: boolean;
  /** 达到最大重复次数仍未过关 */
  abandoned: boolean;
}

export function emptyCurveState(): CurveItemState {
  return { streak: 0, repeats: 0, done: false, abandoned: false };
}

let entrySeq = 0;

function makeEntryId(resourceId: number, attempt: number): string {
  entrySeq += 1;
  return `e-${resourceId}-${attempt}-${entrySeq}`;
}

export function buildCurveQueue(resourceIds: number[]): CurveEntry[] {
  return resourceIds.map((resourceId) => ({ entryId: makeEntryId(resourceId, 0), resourceId, attempt: 0 }));
}

export interface CurveAnswerResult {
  entries: CurveEntry[];
  states: Record<number, CurveItemState>;
  /** 该条目是否被重新排入队列 */
  requeued: boolean;
}

/**
 * 对当前条目作答后推进队列。
 *
 * @param entries 当前出场队列
 * @param currentIndex 刚作答的条目下标
 * @param correct 是否判定为对
 * @param states 各资源的曲线状态
 * @param config 会话曲线配置
 */
export function applyCurveAnswer(
  entries: CurveEntry[],
  currentIndex: number,
  correct: boolean,
  states: Record<number, CurveItemState>,
  config: SessionCurveConfig
): CurveAnswerResult {
  const current = entries[currentIndex];
  if (!current) return { entries, states, requeued: false };
  const resourceId = current.resourceId;
  const state: CurveItemState = { ...(states[resourceId] ?? emptyCurveState()) };
  const nextStates = { ...states, [resourceId]: state };
  const passStreak = Math.max(1, config.passStreak);
  const gap = Math.max(1, config.gap);

  if (!config.enabled) {
    state.done = true;
    return { entries, states: nextStates, requeued: false };
  }

  let requeue = false;
  if (correct) {
    state.streak += 1;
    if (state.streak >= passStreak) {
      state.done = true;
    } else {
      // 连对次数不足，继续排入队列巩固
      requeue = config.maxRepeats === 0 || state.repeats < config.maxRepeats;
      if (!requeue) state.done = true;
    }
  } else {
    state.streak = 0;
    if (config.maxRepeats > 0 && state.repeats >= config.maxRepeats) {
      // 重现次数用尽仍未答对：放弃，不再重现
      state.done = true;
      state.abandoned = true;
    } else {
      requeue = true;
    }
  }

  if (!requeue) return { entries, states: nextStates, requeued: false };

  state.repeats += 1;
  const nextEntry: CurveEntry = {
    entryId: makeEntryId(resourceId, current.attempt + 1),
    resourceId,
    attempt: current.attempt + 1
  };
  // 隔 gap 个条目后重现；剩余不足时直接排到队尾。
  const insertAt = Math.min(currentIndex + 1 + gap, entries.length);
  const nextEntries = [...entries.slice(0, insertAt), nextEntry, ...entries.slice(insertAt)];
  return { entries: nextEntries, states: nextStates, requeued: true };
}
