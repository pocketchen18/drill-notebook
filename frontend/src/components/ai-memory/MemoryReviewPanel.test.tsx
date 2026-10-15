import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ApiError } from '../../lib/api';
import { MemoryReviewPanel } from './MemoryReviewPanel';

/**
 * 待确认审阅（§12 / §4.4）。这里钉的是界面上的四条纪律：
 * 卡片必须摊开"新值 / 当前值 / 原话 / 作用域 / 为什么"；「分别保留」只跟服务端的 multiValued 走；
 * 未解锁不请求也不能说成"没有待确认"；409 换代要行内摊出原因并给显式重新读取。
 */

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));

/** ApiError 必须留真身：面板靠 `instanceof ApiError` 读 code 决定给不给"重新读取"。 */
vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return { ...actual, get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args) };
});

const NOTEBOOKS = [{ value: 7, label: '高等数学' }];

function proposal(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    proposalId: 'p-1',
    version: 3,
    status: 'PENDING',
    operation: 'REPLACE_PROPOSAL',
    targetMemoryId: 'm-1',
    targetVersion: 2,
    sessionId: 11,
    sourceTurnId: 22,
    createdAt: '2026-10-12T00:00:00.000Z',
    expiresAt: '2026-11-12T00:00:00.000Z',
    corrupt: false,
    scopeType: 'GLOBAL',
    scopeId: 0,
    predicate: 'answer.style',
    newValue: '先给结论再展开',
    reviewReason: 'NEWER_AUTO_PREFERENCE',
    reviewReasonText: '同一件事换了说法，用哪一条由你定',
    multiValued: false,
    quote: '以后回答先给结论',
    target: {
      memoryId: 'm-1', version: 2, kind: 'PREFERENCE', scopeType: 'GLOBAL', scopeId: 0,
      status: 'ACTIVE', validUntil: null, recordedAt: '2026-10-01T00:00:00.000Z',
      value: '先给例子再讲原理', corrupt: false
    },
    ...overrides
  };
}

function candidate(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    memoryId: 'c-1',
    version: 1,
    kind: 'GOAL',
    scopeType: 'NOTEBOOK',
    scopeId: 7,
    status: 'PENDING',
    validUntil: null,
    recordedAt: '2026-10-12T00:00:00.000Z',
    origin: 'AUTO',
    manualProtected: false,
    pinned: false,
    validFrom: null,
    observedAt: null,
    lastConfirmedAt: null,
    predicate: 'study.goal',
    value: '每周复盘三次',
    corrupt: false,
    multiValued: true,
    reviewReason: 'SAME_FAMILY_DIFFERENT_VALUE',
    reviewReasonText: '同一类目标出现了第二种说法，可以各留一条',
    proposedAgainstMemoryId: 'm-9',
    proposedAgainstVersion: 4,
    sources: [{ evidenceId: 'e-1', sessionId: 11, turnId: 22, messageId: 33, quote: '以后每周复盘三次' }],
    ...overrides
  };
}

function activeItem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ...candidate(),
    memoryId: 'm-9',
    version: 4,
    status: 'ACTIVE',
    value: '旧的说法',
    manualProtected: true,
    origin: 'MANUAL',
    ...overrides
  };
}

function proposalPage(proposals: Array<Record<string, unknown>>): Record<string, unknown> {
  return { proposals, limit: 20, hasMore: false, vaultEpoch: 3, vaultRevision: 2 };
}

function itemPage(items: Array<Record<string, unknown>>, status: string): Record<string, unknown> {
  return { items, limit: 20, status, hasMore: false, vaultRevision: 2 };
}

function reviewResult(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { memoryId: 'm-1', version: 3, status: 'ACTIVE', changed: true, ...overrides };
}

function renderPanel(enabled = true): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryReviewPanel enabled={enabled} notebooks={NOTEBOOKS} />
    </QueryClientProvider>
  );
}

function modal(): HTMLElement {
  const node = document.querySelector('.arco-modal') as HTMLElement;
  expect(node).toBeTruthy();
  return node;
}

/** 已发出的建议审阅请求体（按顺序）。 */
function proposalReviewBodies(): Array<Record<string, unknown>> {
  return apiPost.mock.calls
    .filter((call) => String(call[0]).endsWith('/proposals/p-1/review'))
    .map((call) => call[1] as Record<string, unknown>);
}

function candidateReviewBodies(): Array<Record<string, unknown>> {
  return apiPost.mock.calls
    .filter((call) => String(call[0]).endsWith('/items/c-1/review'))
    .map((call) => call[1] as Record<string, unknown>);
}

function proposalGetCount(): number {
  return apiGet.mock.calls.filter((call) => String(call[0]).startsWith('/api/ai/memory/proposals')).length;
}

/** /search 都按 status 分流：这份序列能看出替换选择器有没有被懒加载。 */
function searchStatuses(): string[] {
  return apiPost.mock.calls
    .filter((call) => call[0] === '/api/ai/memory/search')
    .map((call) => (call[1] as { status?: string }).status ?? 'ACTIVE');
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  apiGet.mockImplementation((url: string) => {
    if (String(url).startsWith('/api/ai/memory/proposals')) return Promise.resolve(proposalPage([proposal()]));
    return Promise.resolve({});
  });
  apiPost.mockImplementation((url: string, body?: { status?: string }) => {
    if (url === '/api/ai/memory/search') {
      // 同一端点三种用途：候选堆（PENDING）、列表（ACTIVE）、替换选择器（ACTIVE）
      if (body?.status === 'PENDING') return Promise.resolve(itemPage([candidate()], 'PENDING'));
      return Promise.resolve(itemPage([activeItem()], 'ACTIVE'));
    }
    return Promise.resolve(reviewResult());
  });
});

describe('MemoryReviewPanel', () => {
  it('卡片把新值、当前值、原话、作用域和原因一次摊开', async () => {
    renderPanel();

    const card = await screen.findByText('建议换一种说法');
    const body = card.closest('.ai-memory-review-card') as HTMLElement;
    expect(within(body).getByText('先给结论再展开')).toBeTruthy();
    expect(within(body).getByText('先给例子再讲原理')).toBeTruthy();
    expect(within(body).getByText('以后回答先给结论')).toBeTruthy();
    expect(within(body).getByText('全局')).toBeTruthy();
    expect(within(body).getByText('同一件事换了说法，用哪一条由你定')).toBeTruthy();
    // 候选走的是笔记本作用域，作用域要显出笔记本名而不是裸 id
    const candidateCard = await screen.findByText('新的待确认记忆');
    expect(within(candidateCard.closest('.ai-memory-review-card') as HTMLElement)
      .getByText('笔记本：高等数学')).toBeTruthy();
  });

  it('未解锁时不请求建议，也不说成"没有待确认"', async () => {
    renderPanel(false);

    expect(await screen.findByText(/这不是「没有待确认」/)).toBeTruthy();
    expect(screen.queryByText('暂时没有需要你确认的记忆改动')).toBeNull();
    await waitFor(() => expect(apiGet).not.toHaveBeenCalled());
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('「分别保留」只给服务端认定多值的那条，前端不复刻判定表', async () => {
    renderPanel();

    const acceptButtons = await screen.findAllByRole('button', { name: '采用新值' });
    const proposalCard = acceptButtons[0].closest('.ai-memory-review-card') as HTMLElement;
    const keepBoth = within(proposalCard).getByRole('button', { name: '分别保留' });
    expect(keepBoth).toBeDisabled();
    expect(keepBoth).toHaveAttribute('title', '这一类是单值偏好，不能同时留两条');

    const candidateCard = acceptButtons[1].closest('.ai-memory-review-card') as HTMLElement;
    fireEvent.click(within(candidateCard).getByRole('button', { name: '分别保留' }));
    await waitFor(() => expect(candidateReviewBodies().length).toBe(1));
    expect(candidateReviewBodies()[0]).toMatchObject({ decision: 'keep_both', expectedVersion: 1 });
  });

  it('采纳带上看到的当前值版本，拒绝不动事实所以不带', async () => {
    renderPanel();

    const acceptButtons = await screen.findAllByRole('button', { name: '采用新值' });
    fireEvent.click(acceptButtons[0]);
    await waitFor(() => expect(proposalReviewBodies().length).toBe(1));
    expect(proposalReviewBodies()[0]).toMatchObject({
      decision: 'accept',
      expectedVersion: 3,
      expectedTargetVersion: 2,
      operationId: expect.any(String)
    });

    fireEvent.click(acceptButtons[1]);
    await waitFor(() => expect(candidateReviewBodies().length).toBe(1));
    expect(candidateReviewBodies()[0]).toMatchObject({ decision: 'accept', expectedVersion: 1 });
  });

  it('保留当前走 reject，服务端据此记下抑制而不是删掉原话', async () => {
    renderPanel();

    fireEvent.click((await screen.findAllByRole('button', { name: '保留当前' }))[0]);
    await waitFor(() => expect(proposalReviewBodies().length).toBe(1));
    expect(proposalReviewBodies()[0]).toMatchObject({ decision: 'reject' });
    expect(proposalReviewBodies()[0].expectedTargetVersion).toBeUndefined();
  });

  it('换代 409 时行内摊出服务端原因并给显式重新读取，不静默重试', async () => {
    apiPost.mockImplementation((url: string, body?: { status?: string }) => {
      if (url === '/api/ai/memory/search') {
        return Promise.resolve(body?.status === 'PENDING' ? itemPage([], 'PENDING') : itemPage([], 'ACTIVE'));
      }
      return Promise.reject(new ApiError('MEMORY_VERSION_CONFLICT', '这条已经被改过了，请重新读取', 409));
    });

    renderPanel();
    const before = proposalGetCount();
    fireEvent.click((await screen.findAllByRole('button', { name: '采用新值' }))[0]);

    expect(await screen.findByText('这条已经被改过了，请重新读取')).toBeTruthy();
    expect(proposalReviewBodies()).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: '重新读取' }));
    await waitFor(() => expect(proposalGetCount()).toBeGreaterThan(before));
    // 重新读取只重取列表，不会替用户把刚才那一下再发一遍
    expect(proposalReviewBodies()).toHaveLength(1);
  });

  it('替换只发人点名的旧目标与它的版本', async () => {
    renderPanel();

    const candidateCard = (await screen.findByText('新的待确认记忆'))
      .closest('.ai-memory-review-card') as HTMLElement;
    // 弹窗没开之前，只有候选那一堆会被读取
    expect(searchStatuses()).toEqual(['PENDING']);
    fireEvent.click(within(candidateCard).getByRole('button', { name: '替换某条旧记忆…' }));

    const form = modal();
    // 服务端把这条候选顶下来的旧记忆点了名（proposedAgainst*），选择器要预选它
    const selected = await within(form).findByText('旧的说法 · v4');
    expect(selected).toHaveClass('arco-select-view-value');

    const trigger = form.querySelector('.arco-select') as HTMLElement;
    fireEvent.click(trigger);
    const option = await waitFor(() => document.querySelector('.arco-select-option'));
    fireEvent.click(option as HTMLElement);
    fireEvent.click(within(form).getByRole('button', { name: '替换' }));

    await waitFor(() => expect(candidateReviewBodies().length).toBe(1));
    expect(candidateReviewBodies()[0]).toMatchObject({
      decision: 'replace',
      expectedVersion: 1,
      targetMemoryId: 'm-9',
      expectedTargetVersion: 4
    });
  });

  it('预选的旧目标已经不生效时，替换按钮先禁着，不给人点了没反应', async () => {
    apiPost.mockImplementation((url: string, body?: { status?: string }) => {
      if (url === '/api/ai/memory/search') {
        const status = body?.status ?? 'ACTIVE';
        // 候选还在，但它顶下来的那条已经不在生效列表里
        return Promise.resolve(itemPage(status === 'PENDING' ? [candidate()] : [], status));
      }
      return Promise.resolve(reviewResult());
    });

    renderPanel();
    const card = (await screen.findByText('新的待确认记忆')).closest('.ai-memory-review-card') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: '替换某条旧记忆…' }));

    expect(await within(modal()).findByRole('button', { name: '替换' })).toBeDisabled();
  });

  it('读不出来正文的建议也要列出，并说明怎么处置', async () => {
    apiGet.mockImplementation((url: string) => {
      if (String(url).startsWith('/api/ai/memory/proposals')) {
        return Promise.resolve(proposalPage([proposal({
          corrupt: true, corruptCode: 'MEMORY_DECRYPT_FAILED', newValue: undefined,
          quote: null, target: null
        })]));
      }
      return Promise.resolve({});
    });

    renderPanel();
    expect(await screen.findByText('密文不可用')).toBeTruthy();
    expect(await screen.findByText(/这条建议的密文读不出来/)).toBeTruthy();
    // 正文缺席时用占位说明，不能显示成空白让人以为没有变化
    expect(screen.getByText('（已经没有生效版本）')).toBeTruthy();

    // 载荷解不开时服务端每个决定都会拒，按钮按下去只能换来一条错误，所以不给点
    const card = (await screen.findByText('建议换一种说法')).closest('.ai-memory-review-card') as HTMLElement;
    expect(within(card).getByRole('button', { name: '采用新值' })).toBeDisabled();
    expect(within(card).getByRole('button', { name: '保留当前' })).toBeDisabled();
    expect(within(card).getByRole('button', { name: '分别保留' })).toBeDisabled();
  });

  it('读取中给 role=status 活区，屏幕阅读器能播报', async () => {
    const release: Array<() => void> = [];
    apiGet.mockImplementation(() => new Promise<unknown>((resolve) => {
      release.push(() => resolve(proposalPage([])));
    }));
    apiPost.mockImplementation(() => new Promise<unknown>((resolve) => {
      release.push(() => resolve(itemPage([], 'PENDING')));
    }));

    renderPanel();

    expect(await screen.findByRole('status', { name: '正在读取待确认内容' })).toBeTruthy();
    expect(screen.queryByText('暂时没有需要你确认的记忆改动')).toBeNull();
    release.forEach((fn) => fn());
    expect(await screen.findByText('暂时没有需要你确认的记忆改动')).toBeTruthy();
  });

  it('服务端说"已经处理过了"时提示重读，不报成功', async () => {
    apiPost.mockImplementation((url: string, body?: { status?: string }) => {
      if (url === '/api/ai/memory/search') {
        return Promise.resolve(body?.status === 'PENDING' ? itemPage([candidate()], 'PENDING') : itemPage([], 'ACTIVE'));
      }
      return Promise.resolve(reviewResult({ changed: false, replayed: true }));
    });

    renderPanel();
    fireEvent.click((await screen.findAllByRole('button', { name: '采用新值' }))[1]);

    expect(await screen.findByText('这一条已经处理过了，列表已重新读取')).toBeTruthy();
    expect(screen.queryByText('已按你的选择更新记忆')).toBeNull();
  });
});
