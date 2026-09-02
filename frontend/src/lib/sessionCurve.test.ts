import { describe, expect, it } from 'vitest';
import {
  applyCurveAnswer,
  buildCurveQueue,
  DEFAULT_SESSION_CURVE_CONFIG,
  describeSessionCurveConfig,
  normalizeSessionCurveConfig
} from './sessionCurve';
import type { CurveItemState, SessionCurveConfig } from './sessionCurve';

const CONFIG: SessionCurveConfig = { ...DEFAULT_SESSION_CURVE_CONFIG };
/** 旧版单轮行为：gap 策略（刷题模式固定使用） */
const GAP_CONFIG: SessionCurveConfig = { ...CONFIG, strategy: 'gap' };

const ids = (entries: { resourceId: number }[]): number[] => entries.map((entry) => entry.resourceId);

describe('sessionCurve', () => {
  it('builds an initial single-round queue with one entry per resource', () => {
    const queue = buildCurveQueue([1, 2, 3]);
    expect(ids(queue)).toEqual([1, 2, 3]);
    expect(queue.every((entry) => entry.attempt === 0 && entry.round === 0)).toBe(true);
    expect(new Set(queue.map((entry) => entry.entryId)).size).toBe(3);
  });

  it('builds a multi-round queue when loops > 1', () => {
    const queue = buildCurveQueue([1, 2], 3);
    expect(ids(queue)).toEqual([1, 2, 1, 2, 1, 2]);
    expect(queue.map((entry) => entry.round)).toEqual([0, 0, 1, 1, 2, 2]);
  });

  it('requeues a wrong answer gap items later (gap strategy)', () => {
    const queue = buildCurveQueue([1, 2, 3, 4, 5, 6]);
    const result = applyCurveAnswer(queue, 0, false, {}, GAP_CONFIG);
    expect(result.requeued).toBe(true);
    // 隔 3 题后重现：1 后面依次是 2、3、4，然后才是重现的 1
    expect(ids(result.entries)).toEqual([1, 2, 3, 4, 1, 5, 6]);
    expect(result.states[1].repeats).toBe(1);
    expect(result.states[1].abandoned).toBe(false);
  });

  it('keeps gap requeue inside the current round', () => {
    // 两轮 × [1,2]：第 0 轮答错时，重现条目不得越过第 1 轮起点
    const queue = buildCurveQueue([1, 2], 2);
    const result = applyCurveAnswer(queue, 0, false, {}, { ...GAP_CONFIG, gap: 10 });
    expect(ids(result.entries)).toEqual([1, 2, 1, 1, 2]);
    expect(result.entries[2].round).toBe(0);
    expect(result.entries[3].round).toBe(1);
  });

  it('tail strategy inserts the repeat at the end of the current round', () => {
    const queue = buildCurveQueue([1, 2, 3], 2);
    const result = applyCurveAnswer(queue, 0, false, {}, { ...CONFIG, strategy: 'tail' });
    // 本轮末尾 = 下一轮开始之前
    expect(ids(result.entries)).toEqual([1, 2, 3, 1, 1, 2, 3]);
    expect(result.entries[3].round).toBe(0);
    expect(result.entries[3].attempt).toBe(1);
  });

  it('group strategy (default) inserts the repeat at the end of the current group', () => {
    const queue = buildCurveQueue([1, 2, 3, 4], 1);
    const config: SessionCurveConfig = { ...CONFIG, strategy: 'group', groupSize: 2 };
    const result = applyCurveAnswer(queue, 0, false, {}, config);
    // 组 {1,2} 的末尾 = 基线第 3 题之前
    expect(ids(result.entries)).toEqual([1, 2, 1, 3, 4]);
    // 组 {3,4} 的末尾 = 本轮（整队）末尾
    const second = applyCurveAnswer(result.entries, 3, false, result.states, config);
    expect(ids(second.entries)).toEqual([1, 2, 1, 3, 4, 3]);
  });

  it('clamps the requeue position to the tail when fewer items remain', () => {
    const queue = buildCurveQueue([1, 2]);
    const result = applyCurveAnswer(queue, 0, false, {}, GAP_CONFIG);
    expect(ids(result.entries)).toEqual([1, 2, 1]);
  });

  it('passes an item after one correct answer by default', () => {
    const queue = buildCurveQueue([1, 2]);
    const result = applyCurveAnswer(queue, 0, true, {}, CONFIG);
    expect(result.requeued).toBe(false);
    expect(ids(result.entries)).toEqual([1, 2]);
    expect(result.states[1].done).toBe(true);
    expect(result.states[1].abandoned).toBe(false);
  });

  it('records lastRatedEntryId so each round of the same item can be rated again', () => {
    const queue = buildCurveQueue([1], 2);
    const first = applyCurveAnswer(queue, 0, true, {}, CONFIG);
    expect(first.states[1].lastRatedEntryId).toBe(queue[0].entryId);
    expect(first.states[1].lastRatedEntryId).not.toBe(queue[1].entryId);
  });

  it('abandons an item after maxRepeats wrong answers', () => {
    const config: SessionCurveConfig = { ...GAP_CONFIG, maxRepeats: 2, gap: 1 };
    let queue = buildCurveQueue([1, 2]);
    let states: Record<number, CurveItemState> = {};
    // 第 1 次答错 → 重现（repeats=1）
    let result = applyCurveAnswer(queue, 0, false, states, config);
    expect(result.requeued).toBe(true);
    // 重现后再答错 → 重现（repeats=2，达到上限）
    let repeatIndex = result.entries.findIndex((entry) => entry.resourceId === 1 && entry.attempt === 1);
    result = applyCurveAnswer(result.entries, repeatIndex, false, result.states, config);
    expect(result.requeued).toBe(true);
    expect(result.states[1].repeats).toBe(2);
    // 第三次答错 → 额外重现次数用尽，放弃（不再插入）
    repeatIndex = result.entries.findIndex((entry) => entry.resourceId === 1 && entry.attempt === 2);
    result = applyCurveAnswer(result.entries, repeatIndex, false, result.states, config);
    expect(result.requeued).toBe(false);
    expect(result.states[1].abandoned).toBe(true);
    expect(result.states[1].done).toBe(true);
    queue = result.entries;
    expect(ids(queue)).toEqual([1, 2, 1, 1]);
  });

  it('never abandons in unlimited mode (maxRepeats = 0)', () => {
    const config: SessionCurveConfig = { ...GAP_CONFIG, maxRepeats: 0, gap: 1 };
    let entries = buildCurveQueue([1]);
    let states: Record<number, CurveItemState> = {};
    for (let i = 0; i < 10; i += 1) {
      const result = applyCurveAnswer(entries, 0, false, states, config);
      expect(result.requeued).toBe(true);
      entries = result.entries;
      states = result.states;
    }
    expect(states[1].abandoned).toBe(false);
    expect(states[1].repeats).toBe(10);
  });

  it('requires passStreak consecutive correct answers to pass', () => {
    const config: SessionCurveConfig = { ...GAP_CONFIG, passStreak: 2, gap: 1 };
    let result = applyCurveAnswer(buildCurveQueue([1, 2]), 0, true, {}, config);
    expect(result.requeued).toBe(true);
    expect(result.states[1].done).toBe(false);
    const repeatIndex = result.entries.findIndex((entry) => entry.resourceId === 1 && entry.attempt === 1);
    result = applyCurveAnswer(result.entries, repeatIndex, true, result.states, config);
    expect(result.requeued).toBe(false);
    expect(result.states[1].done).toBe(true);
  });

  it('resets the streak after a wrong answer', () => {
    const config: SessionCurveConfig = { ...GAP_CONFIG, passStreak: 2, gap: 1 };
    let result = applyCurveAnswer(buildCurveQueue([1]), 0, true, {}, config);
    expect(result.states[1].streak).toBe(1);
    result = applyCurveAnswer(result.entries, 1, false, result.states, config);
    expect(result.states[1].streak).toBe(0);
  });

  it('prunes future entries when skipPassed is on', () => {
    const config: SessionCurveConfig = { ...CONFIG, skipPassed: true };
    const queue = buildCurveQueue([1, 2], 2);
    const result = applyCurveAnswer(queue, 0, true, {}, config);
    // 第 1 题过关：后续轮次中的 1 被移除，2 保留
    expect(ids(result.entries)).toEqual([1, 2, 2]);
    expect(result.states[1].done).toBe(true);
  });

  it('orders the next round wrong-first when configured', () => {
    const config: SessionCurveConfig = { ...CONFIG, strategy: 'tail', maxRepeats: 1, nextRoundOrder: 'wrongFirst' };
    let entries = buildCurveQueue([1, 2, 3], 2);
    let states: Record<number, CurveItemState> = {};
    // 轮 0：1 对、2 错（重现一次后再错 → 放弃）、3 对
    let result = applyCurveAnswer(entries, 0, true, states, config);
    states = result.states;
    result = applyCurveAnswer(result.entries, 1, false, states, config);
    states = result.states;
    result = applyCurveAnswer(result.entries, 2, true, states, config);
    states = result.states;
    // 本轮最后一个条目：2 的重现答错 → 放弃，触发次轮重排
    const lastRoundIndex = result.entries.findIndex((entry, i) => entry.resourceId === 2 && entry.round === 0 && entry.attempt === 1);
    result = applyCurveAnswer(result.entries, lastRoundIndex, false, result.states, config);
    expect(result.states[2].abandoned).toBe(true);
    entries = result.entries;
    // 次轮块：2（上轮最后答错）排最前
    const nextRoundIds = entries.filter((entry) => entry.round === 1).map((entry) => entry.resourceId);
    expect(nextRoundIds).toEqual([2, 1, 3]);
  });

  it('does nothing when disabled', () => {
    const config: SessionCurveConfig = { ...CONFIG, enabled: false };
    const queue = buildCurveQueue([1, 2]);
    const result = applyCurveAnswer(queue, 0, false, {}, config);
    expect(result.requeued).toBe(false);
    expect(ids(result.entries)).toEqual([1, 2]);
    expect(result.states[1].done).toBe(true);
  });

  it('normalizes config with clamping and fallbacks', () => {
    const normalized = normalizeSessionCurveConfig({ gap: 999, maxRepeats: -5, passStreak: 'x' as unknown as number, loops: 0, strategy: 'nope' as SessionCurveConfig['strategy'], groupSize: 1, nextRoundOrder: 'weird' as SessionCurveConfig['nextRoundOrder'] });
    expect(normalized.enabled).toBe(true);
    expect(normalized.loops).toBe(1); // 0 会被限制到最小值 1
    expect(normalized.strategy).toBe('group');
    expect(normalized.groupSize).toBe(2);
    expect(normalized.gap).toBe(50);
    expect(normalized.maxRepeats).toBe(0);
    expect(normalized.passStreak).toBe(1);
    expect(normalized.nextRoundOrder).toBe('original');
    expect(normalizeSessionCurveConfig(null)).toEqual(DEFAULT_SESSION_CURVE_CONFIG);
  });

  it('describes the config in one line', () => {
    expect(describeSessionCurveConfig(CONFIG)).toContain('循环 3 轮');
    expect(describeSessionCurveConfig({ ...CONFIG, enabled: false })).toContain('关闭');
    expect(describeSessionCurveConfig({ ...CONFIG, loops: 1 })).toContain('单轮');
  });
});
