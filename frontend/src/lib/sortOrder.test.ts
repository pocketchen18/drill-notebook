import { describe, expect, it } from 'vitest';
import { alignOrder, reorderIds } from './sortOrder';

describe('reorderIds', () => {
  it('把 active 移动到 over 的原位置（arrayMove 语义）', () => {
    expect(reorderIds([1, 2, 3, 4], 1, 3)).toEqual([2, 3, 1, 4]);
    expect(reorderIds([1, 2, 3, 4], 4, 1)).toEqual([4, 1, 2, 3]);
  });

  it('任一 id 不存在或相等时原样返回', () => {
    expect(reorderIds([1, 2, 3], 1, 1)).toEqual([1, 2, 3]);
    expect(reorderIds([1, 2, 3], 9, 2)).toEqual([1, 2, 3]);
    expect(reorderIds([1, 2, 3], 2, 9)).toEqual([1, 2, 3]);
  });

  it('不修改原数组', () => {
    const ids = [1, 2, 3];
    reorderIds(ids, 3, 1);
    expect(ids).toEqual([1, 2, 3]);
  });
});

describe('alignOrder', () => {
  const points = [{ id: 1 }, { id: 2 }, { id: 3 }];

  it('剔除已不存在的 id，末尾补齐漏掉的 id', () => {
    expect(alignOrder([3, 99, 1], points)).toEqual([3, 1, 2]);
  });

  it('空顺序时退化为数据源原始顺序', () => {
    expect(alignOrder([], points)).toEqual([1, 2, 3]);
  });

  it('顺序完整时保持不变', () => {
    expect(alignOrder([2, 3, 1], points)).toEqual([2, 3, 1]);
  });
});
