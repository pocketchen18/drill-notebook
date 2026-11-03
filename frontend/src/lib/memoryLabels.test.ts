import { describe, expect, it } from 'vitest';

import { memoryDisplayTitle } from './memoryLabels';

describe('memoryDisplayTitle', () => {
  it('uses the content and drops memory-request wording instead of showing "other"', () => {
    expect(memoryDisplayTitle({ predicate: 'other', value: '我常住杭州，请记住' })).toBe('常住杭州');
  });

  it('drops a leading subject from a short memory', () => {
    expect(memoryDisplayTitle({ predicate: 'study.background', value: '用户的名字是tibo' }))
      .toBe('名字是tibo');
  });

  it('drops a leading time word from an instruction', () => {
    expect(memoryDisplayTitle({ predicate: 'other', value: '之后每次回答结束之后说一个完毕' }))
      .toBe('每次回答结束之后说一个完毕');
  });

  it('keeps a name the user explicitly set', () => {
    expect(memoryDisplayTitle({ predicate: '回答风格', value: '每次回答结束说一个完毕' }))
      .toBe('回答风格');
  });

  it('treats a generic category name as internal and falls back to content', () => {
    expect(memoryDisplayTitle({ predicate: '偏好', value: '我常住杭州' })).toBe('常住杭州');
  });

  it('uses the first sentence and caps long content', () => {
    expect(memoryDisplayTitle({
      predicate: 'other',
      value: '这是一个很长的记忆内容，包含很多细节。第二句话不应该出现在标题里。'
    })).toBe('这是一个很长的记忆内容，包含很多细节');
    expect(memoryDisplayTitle({ predicate: 'other', value: '一'.repeat(40) }))
      .toBe(`${'一'.repeat(30)}…`);
  });

  it('handles empty and corrupt memories', () => {
    expect(memoryDisplayTitle({ predicate: 'other', value: '   ' })).toBe('未命名记忆');
    expect(memoryDisplayTitle({ predicate: 'other', value: '内容', corrupt: true }))
      .toBe('密文不可用的记忆');
  });
});
