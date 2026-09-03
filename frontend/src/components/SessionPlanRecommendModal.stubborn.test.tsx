import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SessionPlanRecommendModal } from './SessionPlanRecommendModal';
import { addDaysYmd, tomorrowYmd } from '../lib/studyPlan';

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }));

vi.mock('../lib/api', () => ({
  get: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
  post: postMock
}));

const STUBBORN = [
  { resourceType: 'question' as const, resourceId: 11, title: '选择题干 11' },
  { resourceType: 'knowledge_point' as const, resourceId: 5, title: '知识卡片 5' }
];

function openModal(stubborn: typeof STUBBORN | undefined): void {
  render(
    <MemoryRouter>
      <SessionPlanRecommendModal
        visible
        onClose={vi.fn()}
        sessionType="memorize"
        payload={{ reviewAgainIds: [], stubborn }}
      />
    </MemoryRouter>
  );
}

function submitBody(): Record<string, unknown> {
  const call = postMock.mock.calls.find(([path]) => String(path).endsWith('session-apply'));
  return (call?.[1] ?? {}) as Record<string, unknown>;
}

describe('SessionPlanRecommendModal 顽固项加练', () => {
  beforeEach(() => {
    postMock.mockReset();
    postMock.mockImplementation(async (path: string) => {
      if (String(path).endsWith('/recommend')) return { title: '复习计划', candidates: [] };
      return { enroll: { enrolled: 2, alreadyEnrolled: 0, total: 2 }, plan: { createdGroups: 2, createdItems: 4 } };
    });
  });

  afterEach(async () => {
    cleanup();
    // 让 Arco Modal 的淡入淡出计时器在环境销毁前跑完
    await new Promise((resolve) => setTimeout(resolve, 400));
  });

  it('renders the stubborn card pre-checked and keeps the modal usable with no regular candidates', async () => {
    openModal(STUBBORN);
    expect(await screen.findByText('顽固项加练（2）')).toBeInTheDocument();
    expect(screen.queryByText(/本轮没有可安排的内容/)).not.toBeInTheDocument();
    const checkbox = document.querySelector('.stubborn-practice-card input[type="checkbox"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
  });

  it('submits tomorrow + day-after practice groups and enrolls the stubborn ids', async () => {
    openModal(STUBBORN);
    fireEvent.click(await screen.findByText('写入记忆曲线与日历'));
    await waitFor(() => {
      expect(postMock.mock.calls.some(([path]) => String(path).endsWith('session-apply'))).toBe(true);
    });
    const body = submitBody();
    expect(body.enroll).toBe(true);
    expect(body.writePlan).toBe(true);
    expect(body.candidates).toEqual([
      { resourceType: 'question', resourceId: 11, title: '选择题干 11' },
      { resourceType: 'knowledge_point', resourceId: 5, title: '知识卡片 5' }
    ]);
    const groups = body.groups as Array<{ planDate: string; title: string; items: Array<{ resourceId: number }> }>;
    expect(groups.map((group) => group.planDate)).toEqual([tomorrowYmd(), addDaysYmd(tomorrowYmd(), 1)]);
    expect(groups.map((group) => group.items.map((item) => item.resourceId))).toEqual([
      [11, 5],
      [11, 5]
    ]);
    // 没有常规候选时不再产出空条目的手动组
    expect(groups.every((group) => group.title.startsWith('顽固项加练'))).toBe(true);
  });

  it('drops the stubborn groups when unchecked', async () => {
    openModal(STUBBORN);
    fireEvent.click(await screen.findByText('顽固项加练（2）'));
    const checkbox = (await waitFor(() => {
      const input = document.querySelector('.stubborn-practice-card input[type="checkbox"]') as HTMLInputElement;
      expect(input.checked).toBe(false);
      return input;
    })) as HTMLInputElement;
    const okButton = Array.from(document.querySelectorAll('.arco-modal-footer button')).find((button) =>
      button.textContent?.includes('写入')
    ) as HTMLButtonElement | undefined;
    expect(checkbox.checked).toBe(false);
    expect(okButton?.disabled).toBe(true);
    expect(
      postMock.mock.calls.every(([path]) => !String(path).endsWith('session-apply'))
    ).toBe(true);
  });

  it('stays silent when the session produced no stubborn items', async () => {
    openModal(undefined);
    await waitFor(() => expect(postMock).toHaveBeenCalled());
    expect(document.querySelector('.stubborn-practice-card')).toBeNull();
  });
});
