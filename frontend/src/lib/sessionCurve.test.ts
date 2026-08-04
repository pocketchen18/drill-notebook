import { describe, expect, it } from 'vitest';
import {
  applyCurveAnswer,
  buildCurveQueue,
  DEFAULT_SESSION_CURVE_CONFIG,
  normalizeSessionCurveConfig
} from './sessionCurve';
import type { CurveItemState, SessionCurveConfig } from './sessionCurve';

const CONFIG: SessionCurveConfig = { ...DEFAULT_SESSION_CURVE_CONFIG };

const ids = (entries: { resourceId: number }[]): number[] => entries.map((entry) => entry.resourceId);

describe('sessionCurve', () => {
  it('builds an initial queue with one entry per resource', () => {
    const queue = buildCurveQueue([1, 2, 3]);
    expect(ids(queue)).toEqual([1, 2, 3]);
    expect(queue.every((entry) => entry.attempt === 0)).toBe(true);
    expect(new Set(queue.map((entry) => entry.entryId)).size).toBe(3);
  });

  it('requeues a wrong answer gap items later', () => {
    const queue = buildCurveQueue([1, 2, 3, 4, 5, 6]);
    const result = applyCurveAnswer(queue, 0, false, {}, CONFIG);
    expect(result.requeued).toBe(true);
    // 隔 3 题后重现：1 后面依次是 2、3、4，然后才是重现的 1
    expect(ids(result.entries)).toEqual([1, 2, 3, 4, 1, 5, 6]);
    expect(result.states[1].repeats).toBe(1);
    expect(result.states[1].abandoned).toBe(false);
  });

  it('clamps the requeue position to the tail when fewer items remain', () => {
    const queue = buildCurveQueue([1, 2]);
    const result = applyCurveAnswer(queue, 0, false, {}, CONFIG);
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

  it('abandons an item after maxRepeats wrong answers', () => {
    const config: SessionCurveConfig = { ...CONFIG, maxRepeats: 2, gap: 1 };
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
    // 第三次答错 → 重现次数用尽，放弃
    repeatIndex = result.entries.findIndex((entry) => entry.resourceId === 1 && entry.attempt === 2);
    result = applyCurveAnswer(result.entries, repeatIndex, false, result.states, config);
    expect(result.requeued).toBe(false);
    expect(result.states[1].abandoned).toBe(true);
    expect(result.states[1].done).toBe(true);
    queue = result.entries;
    states = result.states;
    expect(ids(queue)).toEqual([1, 2, 1, 1]);
  });

  it('never abandons in unlimited mode (maxRepeats = 0)', () => {
    const config: SessionCurveConfig = { ...CONFIG, maxRepeats: 0, gap: 1 };
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
    const config: SessionCurveConfig = { ...CONFIG, passStreak: 2, gap: 1 };
    // 第一次答对：连对 1 次不足，重新排入巩固
    let result = applyCurveAnswer(buildCurveQueue([1, 2]), 0, true, {}, config);
    expect(result.requeued).toBe(true);
    expect(result.states[1].done).toBe(false);
    // 重现后再答对：连对 2 次过关
    const repeatIndex = result.entries.findIndex((entry) => entry.resourceId === 1 && entry.attempt === 1);
    result = applyCurveAnswer(result.entries, repeatIndex, true, result.states, config);
    expect(result.requeued).toBe(false);
    expect(result.states[1].done).toBe(true);
  });

  it('resets the streak after a wrong answer', () => {
    const config: SessionCurveConfig = { ...CONFIG, passStreak: 2, gap: 1 };
    let result = applyCurveAnswer(buildCurveQueue([1]), 0, true, {}, config);
    expect(result.states[1].streak).toBe(1);
    result = applyCurveAnswer(result.entries, 1, false, result.states, config);
    expect(result.states[1].streak).toBe(0);
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
    const normalized = normalizeSessionCurveConfig({ gap: 999, maxRepeats: -5, passStreak: 'x' as unknown as number });
    expect(normalized.enabled).toBe(true);
    expect(normalized.gap).toBe(50);
    expect(normalized.maxRepeats).toBe(0);
    expect(normalized.passStreak).toBe(1);
    expect(normalizeSessionCurveConfig(null)).toEqual(DEFAULT_SESSION_CURVE_CONFIG);
  });
});
