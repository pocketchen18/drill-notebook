/**
 * 背知识点会话 × 跨天曲线联动（KMS-*）：
 * SRS 只在首次评分时提交，之后仅当「会/不会」判定翻转才补交一次（forceAdvance），
 * 避免多轮循环把同一张卡在同一天反复推远。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { KnowledgeMemorizeSession } from './KnowledgeMemorizeSession';
import type { KnowledgePoint } from '../../lib/types';

const { completeStudyMock } = vi.hoisted(() => ({ completeStudyMock: vi.fn() }));

vi.mock('../../lib/study', () => ({
  completeStudy: (...args: unknown[]) => completeStudyMock(...args)
}));

vi.mock('../../lib/api', () => ({
  get: vi.fn(() => Promise.resolve({ candidates: [], groups: [] })),
  post: vi.fn(() => Promise.resolve({})),
  put: vi.fn(() => Promise.resolve({})),
  del: vi.fn(() => Promise.resolve({}))
}));

const POINT: KnowledgePoint = {
  id: 1,
  title: '二叉搜索树性质',
  content: '左子树全部小于根，右子树全部大于根。',
  category: '数据结构',
  tags: [],
  questionIds: []
};

async function rate(known: boolean): Promise<void> {
  const reveal = screen.queryByText('显示内容');
  if (reveal) {
    await act(async () => {
      fireEvent.click(reveal);
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  }
  await act(async () => {
    fireEvent.click(screen.getByText(known ? '会' : '不会'));
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

function openSession(): void {
  render(
    <MemoryRouter>
      <KnowledgeMemorizeSession points={[POINT]} questions={[]} ids={[1]} />
    </MemoryRouter>
  );
}

describe('KnowledgeMemorizeSession 跨天评分', () => {
  beforeEach(() => {
    localStorage.removeItem('session.curveConfig');
    completeStudyMock.mockReset();
    completeStudyMock.mockResolvedValue({});
  });

  afterEach(() => {
    localStorage.removeItem('session.curveConfig');
  });

  it('submits once on the first rating', async () => {
    openSession();
    await rate(true);
    expect(completeStudyMock).toHaveBeenCalledTimes(1);
    expect(completeStudyMock.mock.calls[0][0]).toMatchObject({
      resourceType: 'knowledge_point',
      resourceId: 1,
      quality: 4,
      source: 'knowledge'
    });
    expect((completeStudyMock.mock.calls[0][0] as { forceAdvance?: boolean }).forceAdvance).toBeFalsy();
  });

  it('re-submits with forceAdvance only when the judgement flips', async () => {
    openSession();
    await rate(false); // 第 1 轮答错 → 本轮内重现
    await rate(true); // 重现条目改判「会」
    expect(completeStudyMock).toHaveBeenCalledTimes(2);
    const first = completeStudyMock.mock.calls[0][0] as { quality: number; forceAdvance?: boolean };
    const second = completeStudyMock.mock.calls[1][0] as { quality: number; forceAdvance?: boolean };
    expect(first.quality).toBe(0);
    expect(second.quality).toBe(4);
    expect(second.forceAdvance).toBe(true);
  });

  it('stays quiet on repeated identical ratings across rounds', async () => {
    openSession();
    await rate(true); // 连对即过关，后续轮次仍会出场
    await rate(true);
    await rate(true);
    expect(completeStudyMock).toHaveBeenCalledTimes(1);
  });
});
