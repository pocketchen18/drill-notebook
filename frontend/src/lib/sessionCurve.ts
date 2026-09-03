/**
 * 会话内记忆曲线：背诵会话中「多轮循环 + 错题重复」的队列逻辑。
 *
 * 机制（短周期记忆曲线，与跨天 SRS review_schedule 相互独立）：
 * - 循环轮数 loops：选中的条目每轮按既定顺序出场一次，共循环 loops 轮；
 * - 答错（不会）→ 按策略 strategy 追加一个重现条目（百词斩式）：
 *   - group（默认）：每轮按 groupSize 题分组，错题集中到本组末尾重现（组末复习）；
 *   - tail：插入当前轮末尾；
 *   - gap：延迟 gap 个条目后重现（不超过本轮）；
 *   额外重现达到 maxRepeats（0 = 不限）仍未过关 → 标记放弃，不再额外重复（后续轮次的基线出场仍保留）；
 * - 连续答对 passStreak 次 → 过关；开启 skipPassed 后，过关条目会从后续轮次中移除；
 * - 次轮顺序 nextRoundOrder：进入下一轮时保持原序 / 上轮未过关者排前（错题优先）/ 随机。
 *
 * loops = 1 时退化为旧版单轮行为；刷题（QuizPage）固定单轮 + gap 策略。
 */

/** 错题重现插入策略：group = 本组末尾（百词斩组末复习）；tail = 本轮末尾；gap = 延迟重现。 */
export type WrongRepeatStrategy = 'group' | 'tail' | 'gap';
/** 进入下一轮时的出场顺序策略。 */
export type NextRoundOrder = 'original' | 'wrongFirst' | 'random';

export interface SessionCurveConfig {
  /** 总开关：是否启用多轮循环与错题重现 */
  enabled: boolean;
  /** 循环轮数：选中条目整体循环出现几遍（1-10） */
  loops: number;
  /** 错题重现插入策略 */
  strategy: WrongRepeatStrategy;
  /** strategy='group' 时：每轮分组大小，错题在本组末尾集中重现 */
  groupSize: number;
  /** strategy='gap' 时：答错后隔多少个条目再重现 */
  gap: number;
  /** 单条目最大额外重现次数（基线轮次之外的追加）；0 表示不限制（直到会为止） */
  maxRepeats: number;
  /** 需要连续答对多少次才算过关 */
  passStreak: number;
  /** 过关后从后续轮次中移除该条目（默认关闭：保证每轮都完整循环） */
  skipPassed: boolean;
  /** 下一轮出场顺序：原序 / 上轮未过关优先 / 随机 */
  nextRoundOrder: NextRoundOrder;
}

export const DEFAULT_SESSION_CURVE_CONFIG: SessionCurveConfig = {
  enabled: true,
  loops: 3,
  strategy: 'group',
  groupSize: 10,
  gap: 3,
  maxRepeats: 3,
  passStreak: 1,
  skipPassed: false,
  nextRoundOrder: 'original'
};

/** 背诵设置弹窗中的预设方案。 */
export const SESSION_CURVE_PRESETS: Array<{ key: string; name: string; description: string; config: SessionCurveConfig }> = [
  { key: 'light', name: '快速过一遍', description: '只循环 1 轮，错题延迟重现', config: { ...DEFAULT_SESSION_CURVE_CONFIG, loops: 1, strategy: 'gap', nextRoundOrder: 'original' } },
  { key: 'standard', name: '推荐 · 三轮循环', description: '循环 3 轮，每 10 题一组、错题组末重现', config: { ...DEFAULT_SESSION_CURVE_CONFIG } },
  { key: 'baicizhan', name: '百词斩式', description: '循环 3 轮，组末复习 + 次轮错题优先', config: { ...DEFAULT_SESSION_CURVE_CONFIG, nextRoundOrder: 'wrongFirst' } },
  { key: 'intensive', name: '强化 · 五轮循环', description: '循环 5 轮，额外重复上限放宽', config: { ...DEFAULT_SESSION_CURVE_CONFIG, loops: 5, maxRepeats: 5, nextRoundOrder: 'wrongFirst' } }
];

const LS_SESSION_CURVE = 'session.curveConfig';

function clampInt(value: unknown, fallback: number, min: number, max: number): number {
  const num = typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : fallback;
  return Math.min(max, Math.max(min, num));
}

export function normalizeSessionCurveConfig(raw: Partial<SessionCurveConfig> | null | undefined): SessionCurveConfig {
  return {
    enabled: typeof raw?.enabled === 'boolean' ? raw.enabled : DEFAULT_SESSION_CURVE_CONFIG.enabled,
    loops: clampInt(raw?.loops, DEFAULT_SESSION_CURVE_CONFIG.loops, 1, 10),
    strategy: raw?.strategy === 'gap' || raw?.strategy === 'tail' || raw?.strategy === 'group' ? raw.strategy : DEFAULT_SESSION_CURVE_CONFIG.strategy,
    groupSize: clampInt(raw?.groupSize, DEFAULT_SESSION_CURVE_CONFIG.groupSize, 2, 100),
    gap: clampInt(raw?.gap, DEFAULT_SESSION_CURVE_CONFIG.gap, 1, 50),
    maxRepeats: clampInt(raw?.maxRepeats, DEFAULT_SESSION_CURVE_CONFIG.maxRepeats, 0, 999),
    passStreak: clampInt(raw?.passStreak, DEFAULT_SESSION_CURVE_CONFIG.passStreak, 1, 10),
    skipPassed: typeof raw?.skipPassed === 'boolean' ? raw.skipPassed : DEFAULT_SESSION_CURVE_CONFIG.skipPassed,
    nextRoundOrder: raw?.nextRoundOrder === 'wrongFirst' || raw?.nextRoundOrder === 'random' || raw?.nextRoundOrder === 'original' ? raw.nextRoundOrder : DEFAULT_SESSION_CURVE_CONFIG.nextRoundOrder
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
  /** 本轮内的额外重现序号；0 = 基线出场 */
  attempt: number;
  /** 所属循环轮次（0 起） */
  round: number;
}

/** 单个资源在当前会话中的曲线状态。 */
export interface CurveItemState {
  /** 当前连续答对次数 */
  streak: number;
  /** 已额外重现次数（基线轮次之外） */
  repeats: number;
  /** 已终结（过关或放弃） */
  done: boolean;
  /** 达到最大重复次数仍未过关 */
  abandoned: boolean;
  /** 最近一次作答是否为答错；用于次轮「错题优先」排序 */
  lastRoundWrong: boolean;
  /** 最近一次作答对应的队列条目 id；UI 据此判断「当前这条是否已评过分」，同资源后续轮次仍可重新评分 */
  lastRatedEntryId?: string;
}

export function emptyCurveState(): CurveItemState {
  return { streak: 0, repeats: 0, done: false, abandoned: false, lastRoundWrong: false };
}

let entrySeq = 0;

function makeEntryId(resourceId: number, attempt: number): string {
  entrySeq += 1;
  return `e-${resourceId}-${attempt}-${entrySeq}`;
}

export function buildCurveQueue(resourceIds: number[], loops = 1): CurveEntry[] {
  const rounds = Math.max(1, Math.round(loops) || 1);
  const entries: CurveEntry[] = [];
  for (let round = 0; round < rounds; round += 1) {
    for (const resourceId of resourceIds) {
      entries.push({ entryId: makeEntryId(resourceId, round), resourceId, attempt: 0, round });
    }
  }
  return entries;
}

/** 配置的一句话摘要，用于背诵入口按钮展示。 */
export function describeSessionCurveConfig(config: SessionCurveConfig): string {
  if (!config.enabled) return '单轮顺序 · 已关闭错题重现';
  const strategy = config.strategy === 'group'
    ? `错题插本组末尾（${config.groupSize} 题/组）`
    : config.strategy === 'tail'
      ? '错题插本轮末尾'
      : `错题隔 ${config.gap} 题重现`;
  const cap = config.maxRepeats === 0 ? '额外重复不限' : `额外重复≤${config.maxRepeats}次`;
  const order = config.nextRoundOrder === 'wrongFirst' ? ' · 次轮错题优先' : config.nextRoundOrder === 'random' ? ' · 次轮随机' : '';
  if (config.loops <= 1) return `单轮 · ${strategy} · ${cap}`;
  return `循环 ${config.loops} 轮 · ${strategy} · ${cap}${order}`;
}

export interface CurveAnswerResult {
  entries: CurveEntry[];
  states: Record<number, CurveItemState>;
  /** 该条目是否被重新排入队列 */
  requeued: boolean;
}

/** 找到 entries 中 round 轮（起点 afterIndex 之后）的条目块区间 [start, end)。 */
function findRoundBlock(entries: CurveEntry[], round: number, afterIndex: number): { start: number; end: number } | null {
  const start = entries.findIndex((entry, i) => i > afterIndex && entry.round === round);
  if (start === -1) return null;
  let end = start;
  while (end < entries.length && entries[end].round === round) end += 1;
  return { start, end };
}

/** 按配置重排下一轮的出场顺序（原序 / 错题优先 / 随机）。 */
function applyNextRoundOrder(entries: CurveEntry[], finishedRound: number, states: Record<number, CurveItemState>, config: SessionCurveConfig): CurveEntry[] {
  if (config.nextRoundOrder === 'original') return entries;
  const block = findRoundBlock(entries, finishedRound + 1, -1);
  if (!block) return entries;
  const slice = entries.slice(block.start, block.end);
  if (config.nextRoundOrder === 'random') {
    for (let i = slice.length - 1; i > 0; i -= 1) {
      const j = Math.floor(Math.random() * (i + 1));
      [slice[i], slice[j]] = [slice[j], slice[i]];
    }
  } else {
    // wrongFirst：上一轮最后答错（未过关）的条目排前，保持其余相对顺序（稳定分区）
    const wrong = slice.filter((entry) => states[entry.resourceId]?.lastRoundWrong);
    const rest = slice.filter((entry) => !states[entry.resourceId]?.lastRoundWrong);
    slice.length = 0;
    slice.push(...wrong, ...rest);
  }
  return [...entries.slice(0, block.start), ...slice, ...entries.slice(block.end)];
}

/**
 * 对当前条目作答后推进队列。
 *
 * @param entries 当前出场队列（按轮非降序排列）
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
  state.lastRatedEntryId = current.entryId;
  const nextStates = { ...states, [resourceId]: state };
  const passStreak = Math.max(1, config.passStreak);
  const gap = Math.max(1, config.gap);

  if (!config.enabled) {
    state.done = true;
    return { entries, states: nextStates, requeued: false };
  }

  // 本轮末尾 = 下一轮首个条目的位置；错题重现只插本轮内，
  // 最后一轮时即为整队末尾，等价于「插入背诵清单末尾」。
  const nextRoundIndex = entries.findIndex((entry, i) => i > currentIndex && entry.round > current.round);
  const roundEnd = nextRoundIndex === -1 ? entries.length : nextRoundIndex;

  let requeue = false;
  if (correct) {
    state.streak += 1;
    state.lastRoundWrong = false;
    if (state.streak >= passStreak) {
      state.done = true;
      if (config.skipPassed) {
        // 过关：从当前位置之后的队列（含后续轮次）中移除该条目
        const pruned = entries.filter((entry, i) => i <= currentIndex || entry.resourceId !== resourceId);
        return { entries: pruned, states: nextStates, requeued: false };
      }
    } else {
      // 连对次数不足，在本轮内继续排入巩固
      requeue = config.maxRepeats === 0 || state.repeats < config.maxRepeats;
      if (!requeue) state.done = true;
    }
  } else {
    state.streak = 0;
    state.lastRoundWrong = true;
    if (config.maxRepeats > 0 && state.repeats >= config.maxRepeats) {
      // 额外重现次数用尽仍未答对：放弃额外重复（后续轮次基线出场仍保留）
      state.done = true;
      state.abandoned = true;
    } else {
      requeue = true;
    }
  }

  if (!requeue) {
    // 本轮已无剩余条目时，按配置排好下一轮顺序再返回
    const roundHasMore = entries.some((entry, i) => i > currentIndex && entry.round === current.round);
    const ordered = roundHasMore ? entries : applyNextRoundOrder(entries, current.round, nextStates, config);
    return { entries: ordered, states: nextStates, requeued: false };
  }

  state.repeats += 1;
  const nextEntry: CurveEntry = {
    entryId: makeEntryId(resourceId, current.attempt + 1),
    resourceId,
    attempt: current.attempt + 1,
    round: current.round
  };
  let insertAt: number;
  if (config.strategy === 'gap') {
    // 隔 gap 个条目后重现，不超过本轮末尾
    insertAt = Math.min(currentIndex + 1 + gap, roundEnd);
  } else if (config.strategy === 'group') {
    // 组末复习：定位当前条目所属分组（按本轮基线出场顺序编号），插到本组末尾
    const baselineIdxs: number[] = [];
    for (let i = 0; i < entries.length; i += 1) {
      if (entries[i].round === current.round && entries[i].attempt === 0) baselineIdxs.push(i);
    }
    const position = baselineIdxs.filter((idx) => idx <= currentIndex).length - 1;
    const groupSize = Math.max(2, config.groupSize);
    const nextGroupBaseline = (Math.floor(Math.max(0, position) / groupSize) + 1) * groupSize;
    insertAt = nextGroupBaseline < baselineIdxs.length ? baselineIdxs[nextGroupBaseline] : roundEnd;
    insertAt = Math.max(insertAt, currentIndex + 1);
  } else {
    // tail：插入本轮末尾
    insertAt = roundEnd;
  }
  const nextEntries = [...entries.slice(0, insertAt), nextEntry, ...entries.slice(insertAt)];
  // 插入重现条目后本轮仍有内容（含刚插入的这条）时不预排；真正离开本轮时才按配置排下一轮
  const roundHasMore = nextEntries.some((entry, i) => i > currentIndex && entry.round === current.round);
  const ordered = roundHasMore ? nextEntries : applyNextRoundOrder(nextEntries, current.round, nextStates, config);
  return { entries: ordered, states: nextStates, requeued: true };
}

/** 顽固项判定阈值：额外重复达到该次数仍未稳定过关。 */
export const STUBBORN_REPEAT_THRESHOLD = 2;

/**
 * 从会话曲线状态中提取「顽固项」：已放弃（重复用尽仍未记住）或额外重复 ≥ 阈值的条目。
 * 对应 Anki leech / 百词斩顽固错词，用于会话结束后的跨天加练排程。
 */
export function deriveStubbornIds(states: Record<number, CurveItemState>): number[] {
  return Object.entries(states)
    .filter(([, state]) => state.abandoned || state.repeats >= STUBBORN_REPEAT_THRESHOLD)
    .map(([id]) => Number(id))
    .filter((id) => Number.isFinite(id));
}
