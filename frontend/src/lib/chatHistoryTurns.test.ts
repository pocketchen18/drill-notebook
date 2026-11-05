import { describe, it, expect } from 'vitest';
import { completeLoadedTurns, keepBoundary } from './chatHistoryTurns';
import type { ChatHistoryMessage } from '../hooks/useChatHistoryPages';

/**
 * "保留最近 N 轮"的边界必须从**真实已加载的完整轮次**里取（用户裁决：不够就禁用，不猜边界）。
 * 这里钉三件事：最旧一组可能是半轮、legacy 行不算轮次、N+1 那条形状。
 */

const msg = (id: number, turnId?: number): ChatHistoryMessage => ({
  id,
  role: id % 2 === 0 ? 'assistant' : 'user',
  content: `m${id}`,
  ...(turnId === undefined ? { turnId: null } : { turnId })
});

describe('已加载轮次', () => {
  it('还有更早页时，最旧那一组可能是半轮，必须丢掉', () => {
    const messages = [msg(1, 1), msg(2, 1), msg(3, 2), msg(4, 2)];
    expect(completeLoadedTurns(messages, true).map((turn) => turn.turnId)).toEqual([2]);
    expect(completeLoadedTurns(messages, false).map((turn) => turn.turnId)).toEqual([1, 2]);
  });

  it('legacy 展示行没有轮次身份，不参与边界', () => {
    expect(completeLoadedTurns([msg(1), msg(2, 1)], false)).toEqual([{ turnId: 1, lastMessageId: 2 }]);
  });

  it('一轮的末条取已加载的最大 ID，中间夹了 legacy 行也不能把边界算小', () => {
    expect(completeLoadedTurns([msg(1, 1), msg(2), msg(3, 1)], false)).toEqual([
      { turnId: 1, lastMessageId: 3 }
    ]);
  });
});

describe('保留 N 轮的边界', () => {
  const turns = [
    { turnId: 1, lastMessageId: 2 },
    { turnId: 2, lastMessageId: 4 },
    { turnId: 3, lastMessageId: 6 }
  ];

  it('边界是第 N+1 新的那一轮的末条', () => {
    expect(keepBoundary(turns, 1)).toBe(4);
    expect(keepBoundary(turns, 2)).toBe(2);
  });

  it('完整轮次不够 N+1 时没有边界（界面据此禁用，不猜）', () => {
    expect(keepBoundary(turns, 3)).toBeUndefined();
    expect(keepBoundary([], 1)).toBeUndefined();
  });

  it('只接受正整数：0、负数、小数一律没有边界', () => {
    expect(keepBoundary(turns, 0)).toBeUndefined();
    expect(keepBoundary(turns, -1)).toBeUndefined();
    expect(keepBoundary(turns, 1.5)).toBeUndefined();
  });

  it('保留轮数没有批次上限：N 大于 200 只是"保留更多"，边界仍是第 N+1 轮', () => {
    const many = Array.from({ length: 301 }, (_, index) => ({
      turnId: index + 1,
      lastMessageId: (index + 1) * 2
    }));
    expect(keepBoundary(many, 300)).toBe(2);
    expect(keepBoundary(many, 301)).toBeUndefined();
  });
});
