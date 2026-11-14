import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ChatContextInspector } from './ChatContextInspector';
import { ApiError } from '../../lib/api';
import type { ContextSource, TurnContextAudit, TurnContextRead } from '../../lib/aiContextApi';
import type { TurnResult } from '../../lib/types';
import type { SessionContextUsage } from '../ai-session/ContextMeter';

/**
 * 上下文检查器（contracts §4.5、执行方案 §9.4）：只读已保存账本，
 * 说"提供给模型什么"而不是"模型注意了什么"；预览必须逐条点，关闭即销毁。
 */

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));

vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return { ...actual, get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args) };
});

const SOURCES: ContextSource[] = [
  { sourceKey: 'mem:a@2', kind: 'MEMORY', id: 'mem-a', version: '2', sourceTurnId: null },
  { sourceKey: 'rag:88#hash', kind: 'NOTEBOOK_RAG', id: '88', version: '88@hash', sourceTurnId: null },
  { sourceKey: 'img:9', kind: 'IMAGE', id: '9', version: '1', sourceTurnId: 12 }
];

function audit(overrides: Partial<TurnContextAudit> = {}): TurnContextAudit {
  return {
    schemaVersion: 1,
    planId: 'plan-9',
    dispatchState: 'sent',
    estimatedInputTokens: 1200,
    inputBudget: 32000,
    estimatorVersion: 'est-2',
    selected: SOURCES,
    regions: [{ kind: 'MEMORY', estimatedTokens: 120, itemCount: 1 }, { kind: 'USER_TEXT', estimatedTokens: 30, itemCount: 1 }],
    omissions: [],
    scan: { firstTurnId: 10, lastTurnId: 14, scannedTurns: 5, hasOlderUnscanned: false },
    summary: null,
    resumeState: null,
    auditTruncated: false,
    ...overrides
  };
}

function read(auditValue: TurnContextAudit | null, reason?: string): TurnContextRead {
  return auditValue
    ? { requestId: 'v7-1', sessionId: 7, status: 'COMPLETED', available: true, planId: auditValue.planId, dispatchState: auditValue.dispatchState, audit: auditValue }
    : { requestId: 'v7-1', sessionId: 7, status: 'COMPLETED', available: false, reason };
}

const TURN: TurnResult = {
  requestId: 'v7-1',
  usage: { source: 'upstream', inputTokens: 1500, outputTokens: null, cacheReadTokens: 0, cacheWriteTokens: 0 }
};

const CURRENT: SessionContextUsage = {
  sessionId: 7, model: 'current-model', contextWindow: 32000, outputReserve: 4096, latest: null,
  current: { tokens: 8000, requestId: 'latest-turn', estimated: true, complete: true, contextGeneration: 1,
    historyRevision: 3, active: false, estimatorVersion: 'context-estimate-v1', summaryRevision: null,
    categories: [{ kind: 'SYSTEM', tokens: 1000 }, { kind: 'USER', tokens: 3000 }, { kind: 'ASSISTANT', tokens: 4000 }] }
};

function frame(props: { open: boolean; turn?: TurnResult; onClose?: () => void; requestId?: string }): ReactNode {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <ChatContextInspector sessionId={7} requestId={props.requestId ?? 'v7-1'} turn={props.turn ?? TURN}
                            open={props.open} onClose={props.onClose ?? (() => undefined)} />
    </QueryClientProvider>
  );
}

function renderInspector(props: { open?: boolean; turn?: TurnResult } = {}) {
  return render(frame({ open: props.open ?? true, turn: props.turn }));
}

beforeEach(() => {
  vi.clearAllMocks();
  apiGet.mockResolvedValue(read(audit()));
  apiPost.mockResolvedValue([{ sourceKey: 'mem:a@2', kind: 'MEMORY', id: 'mem-a', version: '2', available: true, text: '常驻杭州', truncated: false }]);
});

describe('ChatContextInspector', () => {
  it('会话状态那一行不给「对照来源」，省略类别也不许露出英文（D164）', async () => {
    apiGet.mockResolvedValue(read(audit({
      selected: [
        ...SOURCES.slice(0, 1),
        { sourceKey: 'state:r1', kind: 'RESUME_STATE', id: 'r1', version: '1', sourceTurnId: null }
      ],
      omissions: [{ reason: 'OUTSIDE_SCAN_WINDOW', kind: 'HISTORY', count: null }]
    })));
    renderInspector();

    // 只有 MEMORY 那一行有按钮：会话状态本来就没有可逐条对照的原文，给按钮只是空期待
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(1));
    expect(screen.getByText('会话状态')).toBeTruthy();
    expect(screen.getByText('会话状态无法逐条对照')).toBeTruthy();
    expect(screen.getByText('更早历史')).toBeTruthy();
    expect(screen.queryByText('HISTORY')).toBeNull();
  });

  it('打开只读会话占用和请求账本，不碰聊天也不自动预览', async () => {
    renderInspector();
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(2));
    expect(apiGet).toHaveBeenCalledWith('/api/ai/turns/v7-1/context');
    expect(apiGet).toHaveBeenCalledWith('/api/ai/sessions/7/context-usage');
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('关闭时不读账本，也不留面板', () => {
    renderInspector({ open: false });
    expect(apiGet).not.toHaveBeenCalled();
    expect(screen.queryByText('计划与发送态')).toBeNull();
  });

  it('发送态只有三种事实，不声称模型确实读过', async () => {
    const cases: [string, string][] = [['sent', '已发送给模型'], ['not_sent', '尚未发送给模型'], ['unknown', '发送状态未知']];
    for (const [state, text] of cases) {
      vi.clearAllMocks();
      apiGet.mockResolvedValue(read(audit({ dispatchState: state })));
      const view = renderInspector();
      await waitFor(() => expect(screen.getByText(text)).toBeTruthy());
      expect(screen.queryByText(/模型确实|模型注意力是/)).toBeNull();
      view.unmount();
    }
  });

  it('账本没保存或读不出时说清楚，不给空壳面板', async () => {
    apiGet.mockResolvedValue(read(null, 'LEGACY_TURN_WITHOUT_CONTEXT_PLAN'));
    renderInspector();
    await waitFor(() => expect(screen.getByText('本轮未保存上下文记录（这一轮早于上下文账本）')).toBeTruthy());
    expect(screen.queryByText('区域估算')).toBeNull();
  });

  it('历史账本不可用时仍展示当前会话卡片，弹窗标题是上下文', async () => {
    apiGet.mockImplementation((path: string) => Promise.resolve(path.endsWith('/context-usage')
      ? CURRENT : read(null, 'LEGACY_TURN_WITHOUT_CONTEXT_PLAN')));
    renderInspector();
    const card = screen.getByRole('region', { name: '当前上下文' });
    await waitFor(() => expect(within(card).getByText('8.0k')).toBeTruthy());
    expect(screen.getByRole('dialog')).toHaveAccessibleName('上下文');
    expect(screen.queryByText('本轮上下文')).toBeNull();
    expect(screen.getByText('本轮未保存上下文记录（这一轮早于上下文账本）')).toBeTruthy();
  });

  it('需要解锁的 423 与"没有记录"是两句话', async () => {
    apiGet.mockRejectedValue(new ApiError('CHAT_PASSWORD_REQUIRED', '需要主密码或记忆库解锁', 423));
    renderInspector();
    await waitFor(() => expect(screen.getByText('需要主密码或记忆库解锁')).toBeTruthy());
    expect(screen.queryByText(/本轮未保存上下文记录/)).toBeNull();
  });

  it('范围与摘要缺口按账本如实描述，未扫描不能说成已处理', async () => {
    apiGet.mockResolvedValue(read(audit({
      scan: { firstTurnId: 10, lastTurnId: 14, scannedTurns: 5, hasOlderUnscanned: true },
      summary: { revision: 3, throughTurnId: 9, hasGaps: true, coverageRanges: [
        { firstTurnId: 1, lastTurnId: 4 }, { firstTurnId: 6, lastTurnId: 9 }
      ] }
    })));
    renderInspector();
    await waitFor(() => expect(screen.getByText('本轮扫描 5 轮')).toBeTruthy());
    expect(screen.getByText('更早历史未扫描')).toBeTruthy();
    expect(screen.getByText('续接摘要 v3 覆盖到 turnId 9')).toBeTruthy();
    expect(screen.getByText('有缺口')).toBeTruthy();
    expect(screen.getByText('1–4、6–9')).toBeTruthy();
  });

  it('没有摘要时说没有可用摘要，不假装覆盖过', async () => {
    renderInspector();
    await waitFor(() => expect(screen.getByText('没有可用的续接摘要，较早历史按原文进窗口。')).toBeTruthy());
    expect(screen.queryByText(/续接摘要 v\d/)).toBeNull();
  });

  it('省略计数为 null 时只能说数量未知', async () => {
    apiGet.mockResolvedValue(read(audit({
      omissions: [
        { reason: 'OUTSIDE_SCAN_WINDOW', kind: null, count: null },
        { reason: 'BUDGET', kind: 'NOTEBOOK_RAG', count: 3 }
      ]
    })));
    renderInspector();
    await waitFor(() => expect(screen.getByText('数量未知（更早未扫描）')).toBeTruthy());
    expect(screen.getByText('全部类别')).toBeTruthy();
    expect(screen.getByText('在扫描窗口之外')).toBeTruthy();
    expect(screen.getByText('3 项')).toBeTruthy();
    expect(screen.getByText('超出预算被裁掉')).toBeTruthy();
    expect(screen.queryByText('0 项')).toBeNull();
  });

  it('区域估算标明是估算而非厂商计数', async () => {
    renderInspector();
    await waitFor(() => expect(screen.getByText('估算器 est-2，非厂商计数')).toBeTruthy());
    expect(screen.getByText('本轮提问')).toBeTruthy();
    expect(screen.getByText('估算 1200 / 预算 32000 tokens')).toBeTruthy();
  });

  it('逐条对照来源：一次只问一个键，占位原因不冒充正文', async () => {
    renderInspector();
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(3));
    expect(apiPost).not.toHaveBeenCalled();
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    await waitFor(() => expect(screen.getByText('常驻杭州')).toBeTruthy());
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPost.mock.calls[0][0]).toBe('/api/ai/turns/v7-1/context/preview');
    expect(apiPost.mock.calls[0][1]).toEqual({ sourceKeys: ['mem:a@2'] });

    apiPost.mockResolvedValue([{ sourceKey: 'rag:88#hash', kind: 'NOTEBOOK_RAG', id: '88', version: '88@hash', available: false, reason: 'OUTSIDE_SCOPE' }]);
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[1]);
    await waitFor(() => expect(screen.getByText('已不在当前笔记本作用域内')).toBeTruthy());
    expect(apiPost).toHaveBeenCalledTimes(2);
  });

  it('截断标记要说出来，不让半截正文看起来像全文', async () => {
    apiPost.mockResolvedValue([{ sourceKey: 'mem:a@2', kind: 'MEMORY', id: 'mem-a', version: '2', available: true, text: '常驻杭州', truncated: true }]);
    renderInspector();
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(3));
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    await waitFor(() => expect(screen.getByText('常驻杭州…（已截断）')).toBeTruthy());
  });

  it('图片只说没有文字预览，不编造正文', async () => {
    apiPost.mockResolvedValue([{ sourceKey: 'img:9', kind: 'IMAGE', id: '9', version: '1', available: true, text: null, note: 'IMAGE_WITHOUT_TEXT_PREVIEW' }]);
    renderInspector();
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(3));
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[2]);
    await waitFor(() => expect(screen.getByText('图片不提供文字预览')).toBeTruthy());
  });

  it('不再展示旧 done 的单次用量或本轮累计块', async () => {
    renderInspector();
    await waitFor(() => expect(screen.getByText('计划与发送态')).toBeTruthy());
    expect(screen.queryByText('来源 upstream')).toBeNull();
    expect(screen.queryByText('输入 1500')).toBeNull();
    expect(screen.queryByText('本轮累计用量')).toBeNull();
    expect(screen.getByRole('region', { name: '当前上下文' })).toBeTruthy();
  });

  it('没有用量快照时整段不出现', async () => {
    renderInspector({ turn: { requestId: 'v7-1' } });
    await waitFor(() => expect(screen.getByText('计划与发送态')).toBeTruthy());
    expect(screen.queryByText('用量')).toBeNull();
  });

  it('对照失败时撤下上一次取出的正文，不把旧结果留在原地冒充当前', async () => {
    renderInspector();
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(3));
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    await waitFor(() => expect(screen.getByText('常驻杭州')).toBeTruthy());

    apiPost.mockRejectedValue(new ApiError('CONTEXT_SOURCE_UNKNOWN', '来源键不属于本轮上下文', 400));
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    await waitFor(() => expect(screen.getByText('来源键不属于本轮上下文')).toBeTruthy());
    expect(screen.queryByText('常驻杭州')).toBeNull();
  });

  it('关闭再打开会丢掉上一次对照出来的正文', async () => {
    const view = renderInspector();
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(3));
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    await waitFor(() => expect(screen.getByText('常驻杭州')).toBeTruthy());

    view.rerender(frame({ open: false }));
    expect(screen.queryByText('常驻杭州')).toBeNull();
    view.rerender(frame({ open: true }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(3));
    expect(screen.queryByText('常驻杭州')).toBeNull();
  });

  it('账本被裁短时如实标注，已发过的来源不能消失', async () => {
    apiGet.mockResolvedValue(read(audit({ auditTruncated: true })));
    renderInspector();
    await waitFor(() => expect(screen.getByText('账本过长，部分诊断明细未保存。')).toBeTruthy());
    expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(3);
  });

  it('当前卡片位于请求浏览器上方，历史用量不顶替当前占用也不重复显示累计', async () => {
    const history = {
      ...read(audit()), usage: {
      requests: 2, inputTokens: 300, outputTokens: 50, cacheReadTokens: 150, cacheMissTokens: 150,
      totalTokens: 350, inputComplete: true, outputComplete: true, cacheReadComplete: true,
      cacheMissComplete: true, totalComplete: true
    }, requestContexts: { totalRequests: 2, hasMore: false, nextBeforeAttemptId: null, limit: 50, items: [{
      attemptId: 'attempt-2', operationId: 'v7-1', ordinal: 2, createdAt: '2026-11-15T12:00:00Z',
      settledAt: null, state: 'SETTLED', usageSource: 'provider', inputTokens: 200, outputTokens: 30,
      cacheReadTokens: 120, cacheMissTokens: 80, totalTokens: 230, inputComplete: true, outputComplete: true,
      cacheReadComplete: true, cacheMissComplete: true, totalComplete: true, context: null
    }] } };
    apiGet.mockImplementation((path: string) => Promise.resolve(path.endsWith('/context-usage') ? CURRENT : history));
    renderInspector();
    await waitFor(() => expect(screen.getByRole('region', { name: '上下文浏览器' })).toBeTruthy());
    const current = screen.getByRole('region', { name: '当前上下文' });
    await waitFor(() => expect(within(current).getByText('8.0k')).toBeTruthy());
    expect(within(current).getByText('25%')).toBeTruthy();
    const regions = screen.getAllByRole('region');
    expect(regions.indexOf(current)).toBeLessThan(regions.indexOf(screen.getByRole('region', { name: '上下文浏览器' })));
    expect(screen.queryByText('本轮累计用量')).toBeNull();
    expect(screen.queryByText('总 Token 350')).toBeNull();
    expect(screen.queryByText('输入 300')).toBeNull();
    expect(screen.queryByText('输入 1500')).toBeNull();
    expect(screen.queryByText('区域估算')).toBeNull();
    expect(screen.getByText('来源与省略').closest('details')?.hasAttribute('open')).toBe(false);
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('切换轮次后忽略旧来源预览的迟到正文', async () => {
    let resolve!: (value: unknown) => void;
    apiPost.mockReturnValue(new Promise(done => { resolve = done; }));
    const view = renderInspector();
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(3));
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    apiGet.mockResolvedValue({ ...read(audit()), requestId: 'v7-2' });
    view.rerender(frame({ open: true, requestId: 'v7-2' }));
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/ai/turns/v7-2/context'));
    await act(async () => resolve([{ sourceKey: 'mem:a@2', kind: 'MEMORY', id: 'mem-a', version: '2',
      available: true, text: '旧轮迟到的正文' }]));
    expect(screen.queryByText('旧轮迟到的正文')).toBeNull();
    expect(screen.queryByText('对照中…')).toBeNull();
  });
});
