import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryUsageBadge } from './MemoryUsageBadge';
import { ApiError } from '../../lib/api';
import type { TurnContextAudit, TurnContextRead } from '../../lib/aiContextApi';
import type { MemoryReference } from '../../lib/aiStream';

/**
 * 记忆徽标（执行方案 §12、contracts §4.5）：默认只说条数，点开列当时的 ID/版本；
 * 正文要逐条显式对照，被删/改版/锁定时只给占位，绝不自动批量预览。
 */

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));

/** ApiError 必须留真身：组件靠 `instanceof` 取 code/message，整体替换会把它抹掉。 */
vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return { ...actual, get: (...args: unknown[]) => apiGet(...args), post: (...args: unknown[]) => apiPost(...args) };
});

const PROVIDED: MemoryReference[] = [
  { memoryId: 'mem-answer-style', version: 3 },
  { memoryId: 'mem-city', version: 1 }
];

function auditWith(selected: TurnContextAudit['selected']): TurnContextAudit {
  return {
    schemaVersion: 1,
    planId: 'plan-1',
    dispatchState: 'sent',
    estimatedInputTokens: 900,
    inputBudget: 32000,
    estimatorVersion: 'est-1',
    selected,
    regions: [{ kind: 'MEMORY', estimatedTokens: 120, itemCount: selected.length }],
    omissions: [],
    scan: { firstTurnId: 10, lastTurnId: 12, scannedTurns: 3, hasOlderUnscanned: false },
    summary: null,
    resumeState: null,
    auditTruncated: false
  };
}

function readWith(audit: TurnContextAudit | null, reason?: string): TurnContextRead {
  return audit
    ? { requestId: 'v7-1', sessionId: 7, status: 'COMPLETED', available: true, planId: audit.planId, dispatchState: audit.dispatchState, audit }
    : { requestId: 'v7-1', sessionId: 7, status: 'COMPLETED', available: false, reason };
}

const MEMORY_ROWS = [
  { sourceKey: 'mem:mem-answer-style@3', kind: 'MEMORY', id: 'mem-answer-style', version: '3', sourceTurnId: null },
  { sourceKey: 'mem:mem-city@1', kind: 'MEMORY', id: 'mem-city', version: '1', sourceTurnId: null }
];

function renderBadge(props: Partial<{ requestId: string; provided: MemoryReference[]; onOpenInspector: () => void }> = {}): { unmount: () => void } {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryUsageBadge sessionId={7} requestId={props.requestId ?? 'v7-1'} provided={props.provided ?? PROVIDED}
                        onOpenInspector={props.onOpenInspector} />
    </QueryClientProvider>
  );
}

function openBadge(name = '本轮提供 2 条记忆'): void {
  fireEvent.click(screen.getByRole('button', { name }));
}

beforeEach(() => {
  vi.clearAllMocks();
  apiGet.mockResolvedValue(readWith(auditWith(MEMORY_ROWS)));
  apiPost.mockResolvedValue([{ sourceKey: 'mem:mem-answer-style@3', kind: 'MEMORY', id: 'mem-answer-style', version: '3', available: true, text: '先给例子再讲原理', truncated: false }]);
});

describe('MemoryUsageBadge', () => {
  it('没有提供记忆时不出现徽标', () => {
    renderBadge({ provided: [] });
    expect(screen.queryByRole('button', { name: /本轮提供/ })).toBeNull();
    expect(apiGet).not.toHaveBeenCalled();
  });

  it('打开前不发任何请求，打开后只读一次已保存的账本', async () => {
    renderBadge();
    expect(screen.getByText('本轮提供 2 条记忆')).toBeTruthy();
    expect(apiGet).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '本轮提供 2 条记忆' }));
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1));
    expect(apiGet.mock.calls[0][0]).toBe('/api/ai/turns/v7-1/context');
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('ID 与版本不依赖账本读取：读得慢也要当场显示', async () => {
    apiGet.mockReturnValue(new Promise(() => undefined));
    renderBadge();
    fireEvent.click(screen.getByRole('button', { name: '本轮提供 2 条记忆' }));
    expect(screen.getByText('mem-answer-style')).toBeTruthy();
    expect(screen.getByText('v3')).toBeTruthy();
    expect(screen.getByText('v1')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '对照来源' })).toBeNull();
  });

  it('正文只由逐条显式对照取出，一次只问一个来源键', async () => {
    renderBadge();
    fireEvent.click(screen.getByRole('button', { name: '本轮提供 2 条记忆' }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(2));
    expect(screen.queryByText('先给例子再讲原理')).toBeNull();

    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    await waitFor(() => expect(screen.getByText('先给例子再讲原理')).toBeTruthy());
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPost.mock.calls[0][0]).toBe('/api/ai/turns/v7-1/context/preview');
    expect(apiPost.mock.calls[0][1]).toEqual({ sourceKeys: ['mem:mem-answer-style@3'] });
  });

  it('被删、改版、锁定三种占位文案各不相同，不与正文混在一行', async () => {
    const cases: [string, string][] = [
      ['SOURCE_DELETED', '已删除'],
      ['SOURCE_CHANGED', '来源已改版'],
      ['LOCKED', '记忆库或会话未解锁，无法对照来源']
    ];
    for (const [reason, text] of cases) {
      vi.clearAllMocks();
      apiGet.mockResolvedValue(readWith(auditWith(MEMORY_ROWS)));
      apiPost.mockResolvedValue([{ sourceKey: 'mem:mem-answer-style@3', kind: 'MEMORY', id: 'mem-answer-style', version: '3', available: false, reason }]);
      const view = renderBadge();
      openBadge();
      await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(2));
      fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
      await waitFor(() => expect(screen.getByText(text)).toBeTruthy());
      expect(screen.queryByText('先给例子再讲原理')).toBeNull();
      // 已删除的记忆不能从旧快照里"复活"成可读正文，但 ID/版本仍要如实列出来
      expect(screen.getByText('mem-answer-style')).toBeTruthy();
      view.unmount();
    }
  });

  it('账本没保存时如实说没有，不给对照入口', async () => {
    apiGet.mockResolvedValue(readWith(null, 'NO_AUDIT_SAVED'));
    renderBadge();
    fireEvent.click(screen.getByRole('button', { name: '本轮提供 2 条记忆' }));
    await waitFor(() => expect(screen.getAllByText('本轮未保存上下文记录')).toHaveLength(2));
    expect(screen.queryByRole('button', { name: '对照来源' })).toBeNull();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('需要解锁时把后端的原话显示出来，不写成"没有记忆"', async () => {
    apiGet.mockRejectedValue(new ApiError('CHAT_PASSWORD_REQUIRED', '需要主密码或记忆库解锁', 423));
    renderBadge();
    fireEvent.click(screen.getByRole('button', { name: '本轮提供 2 条记忆' }));
    await waitFor(() => expect(screen.getByText('需要主密码或记忆库解锁')).toBeTruthy());
    expect(screen.queryByText('本轮未保存上下文记录')).toBeNull();
  });

  it('从徽标能跳到检查器，且这条路径上没有聊天请求', async () => {
    const onOpenInspector = vi.fn();
    renderBadge({ onOpenInspector });
    fireEvent.click(screen.getByRole('button', { name: '本轮提供 2 条记忆' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '查看本轮上下文' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '查看本轮上下文' }));
    expect(onOpenInspector).toHaveBeenCalledTimes(1);
    for (const call of [...apiGet.mock.calls, ...apiPost.mock.calls]) {
      expect(String(call[0])).toContain('/context');
    }
  });

  it('关闭徽标后重新打开，上一次对照出来的正文不再残留', async () => {
    renderBadge();
    const toggle = screen.getByRole('button', { name: '本轮提供 2 条记忆' });
    fireEvent.click(toggle);
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(2));
    fireEvent.click(screen.getAllByRole('button', { name: '对照来源' })[0]);
    await waitFor(() => expect(screen.getByText('先给例子再讲原理')).toBeTruthy());

    fireEvent.click(toggle);
    fireEvent.click(toggle);
    await waitFor(() => expect(screen.getAllByRole('button', { name: '对照来源' })).toHaveLength(2));
    expect(screen.queryByText('先给例子再讲原理')).toBeNull();
  });
});
