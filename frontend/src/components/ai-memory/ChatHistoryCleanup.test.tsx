import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ComponentProps } from 'react';
import { ChatHistoryCleanup } from './ChatHistoryCleanup';
import { ApiError } from '../../lib/api';
import { isUuidV7 } from '../../lib/uuidV7';
import { historyQueryKey, type ChatHistoryMessage } from '../../hooks/useChatHistoryPages';
import { storageQueryKey, type ChatCleanupPreview, type ChatCleanupResult } from '../../lib/aiStorageApi';

/**
 * 历史清理弹窗（contracts §3 的 `/history/cleanup/*`，执行方案 §7.4.5/§7.4.6）：
 * 边界只取已加载的完整轮次、服务端给的事实全摊开、勾选"无法恢复"才能执行、
 * 一份预览一旦被打回就作废并只能重新预览（界面不静默重试）。
 */

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));

vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return {
    ...actual,
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args)
  };
});

/** 12 轮完整对话（每轮一问一答，ID 连续）；legacy 行单独补。 */
const twelveTurns = (): ChatHistoryMessage[] => {
  const rows: ChatHistoryMessage[] = [];
  for (let turn = 1; turn <= 12; turn += 1) {
    const userId = (turn - 1) * 2 + 1;
    rows.push({ id: userId, role: 'user', content: `问${turn}`, turnId: turn, turnStatus: 'COMPLETED' });
    rows.push({ id: userId + 1, role: 'assistant', content: `答${turn}`, turnId: turn, turnStatus: 'COMPLETED' });
  }
  return rows;
};

const preview: ChatCleanupPreview = {
  previewId: 'tok.one',
  sessionId: 10,
  beforeOrAtMessageId: 4,
  maxTurns: 200,
  turnCount: 12,
  messageCount: 26,
  legacyCount: 3,
  incompleteLegacyCount: 1,
  excludedMessageCount: 1,
  olderTurnCount: 4,
  messageIds: [1, 2, 3, 4],
  turnRequestIds: ['r1', 'r2'],
  incompleteLegacyMessageIds: [77],
  affectedEvidence: 3,
  affectedProposals: 2,
  autoFactsRemoved: 1,
  protectedFactsKept: 1,
  historyBytes: 2048,
  attachmentBytes: 0,
  historyRevision: 9,
  expiresAt: '2026-10-20T10:05:00.000Z'
};

const result: ChatCleanupResult = {
  sessionId: 10,
  cleanedTurns: 12,
  cleanedLegacyRows: 3,
  messageCount: 26,
  releasedHistoryBytes: 2048,
  releasedAttachmentBytes: 0,
  evidenceDeleted: 3,
  proposalsDeleted: 2,
  autoFactsRemoved: 1,
  protectedFactsKept: 1,
  checkpointsInvalidated: 1,
  jobsSuperseded: 0,
  historyRevision: 10,
  storage: {
    historyBytes: 0, reservedHistoryBytes: 0, usedHistoryBytes: 0, historyLimitBytes: 268435456,
    attachmentBytes: 0, attachmentLimitBytes: 268435456, historyWarning: false, attachmentWarning: false, revision: 4
  }
};

const renderCleanup = (props: Partial<ComponentProps<typeof ChatHistoryCleanup>> = {}) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  const reset = vi.spyOn(client, 'resetQueries');
  render(
    <QueryClientProvider client={client}>
      <ChatHistoryCleanup
        sessionId={10}
        enabled
        messages={twelveTurns()}
        hasOlder={false}
        isLoadingOlder={false}
        loadOlder={() => undefined}
        {...props}
      />
    </QueryClientProvider>
  );
  return { invalidate, reset };
};

const openDialog = (): void => {
  fireEvent.click(screen.getByRole('button', { name: '清理历史' }));
};

const previewButton = (name = '查看将清理的内容') => screen.getByRole('button', { name });
const okButton = () => screen.getByRole('button', { name: '确认清理' });
const ackBox = () => screen.getByRole('checkbox');

beforeEach(() => {
  vi.clearAllMocks();
  apiPost.mockImplementation((url: string) =>
    String(url).endsWith('/preview') ? Promise.resolve(preview) : Promise.resolve(result));
});

describe('ChatHistoryCleanup', () => {
  it('完整轮次不够 N+1 时禁用预览，并说清还差什么（不猜边界、不发请求）', async () => {
    const loadOlder = vi.fn();
    renderCleanup({
      messages: twelveTurns().slice(0, 6),
      hasOlder: true,
      loadOlder
    });
    openDialog();

    expect(await screen.findByText(/至少要看到 11 轮才能确定边界/)).toBeTruthy();
    expect(previewButton()).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '加载更多历史' }));
    expect(loadOlder).toHaveBeenCalledTimes(1);
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('预览把服务端事实全摊开：范围、残缺行 ID、未纳入的批次、受影响记忆', async () => {
    renderCleanup();
    openDialog();
    fireEvent.click(previewButton());

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/history/cleanup/preview', {
      sessionId: 10,
      // 12 轮里保留最近 10 轮 → 边界是第 11 新的那一轮（turn 2）的末条消息
      beforeOrAtMessageId: 4,
      maxTurns: 200
    }));

    expect(await screen.findByText('清理 12 轮对话 · 26 条消息 · 释放 2 KB')).toBeTruthy();
    expect(screen.getByText('另有 3 条迁移前的旧消息，其中 1 条凑不成一问一答（ID 77）')).toBeTruthy();
    expect(screen.getByText('1 条不完整的旧记录不纳入本次清理')).toBeTruthy();
    expect(screen.getByText('还有 4 轮更早的没纳入，清理完这批可以再来一次')).toBeTruthy();
    expect(screen.getByText(
      '3 条原话证据一起删除；2 条建议随原话一起删除；1 条自动记忆失去唯一来源会消失；1 条受保护记忆只断来源、内容保留'
    )).toBeTruthy();
    expect(screen.getByText(/预览有效至/)).toBeTruthy();
  });

  it('不勾"无法恢复"就不能执行；勾了之后提交的是预览钉住的身份', async () => {
    const { invalidate, reset } = renderCleanup();
    openDialog();
    fireEvent.click(previewButton());
    await screen.findByText(/清理 12 轮对话/);
    expect(okButton()).toBeDisabled();

    fireEvent.click(ackBox());
    await waitFor(() => expect(okButton()).toBeEnabled());
    fireEvent.click(okButton());

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/history/cleanup', expect.objectContaining({
      previewId: 'tok.one',
      expectedHistoryRevision: 9,
      confirm: true
    })));
    const body = apiPost.mock.calls.find((call) => call[0] === '/api/ai/history/cleanup')?.[1] as { operationId: string };
    expect(isUuidV7(body.operationId)).toBe(true);
    // 清理之后账本、历史游标、会话列表都要重取：旧页不能拼回已删的内容
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: storageQueryKey }));
    expect(reset).toHaveBeenCalledWith({ queryKey: historyQueryKey(10) });
  });

  it('预览过期：作废预览、只留一句"重新预览"，绝不静默重试', async () => {
    apiPost.mockImplementation((url: string) => {
      if (String(url).endsWith('/preview')) return Promise.resolve(preview);
      return Promise.reject(new ApiError('CLEANUP_PREVIEW_EXPIRED', '预览已过期，请重新预览', 410));
    });
    const { invalidate } = renderCleanup();
    openDialog();
    fireEvent.click(previewButton());
    await screen.findByText(/清理 12 轮对话/);
    fireEvent.click(ackBox());
    fireEvent.click(okButton());

    expect(await screen.findByText('预览已过期，请重新预览')).toBeTruthy();
    // 预览已被服务端打回：事实区撤掉、确认键重新禁用、按钮回到"预览"，不能拿旧范围再点一次
    await waitFor(() => expect(okButton()).toBeDisabled());
    expect(screen.queryByText(/清理 12 轮对话/)).toBeNull();
    expect(previewButton()).toBeTruthy();
    expect(apiPost.mock.calls.filter((call) => call[0] === '/api/ai/history/cleanup')).toHaveLength(1);
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: storageQueryKey });
  });

  it('会话还有一轮在生成：一句人话说清，且预览同样作废', async () => {
    apiPost.mockImplementation((url: string) => {
      if (String(url).endsWith('/preview')) return Promise.resolve(preview);
      return Promise.reject(new ApiError('CLEANUP_SESSION_RUNNING', '该会话还有正在进行的轮次', 409));
    });
    renderCleanup();
    openDialog();
    fireEvent.click(previewButton());
    await screen.findByText(/清理 12 轮对话/);
    fireEvent.click(ackBox());
    fireEvent.click(okButton());

    expect(await screen.findByText('这个会话还有一轮在生成，先结束或取消再清理')).toBeTruthy();
    await waitFor(() => expect(okButton()).toBeDisabled());
    expect(screen.queryByText(/清理 12 轮对话/)).toBeNull();
  });

  it('没有完整历史时禁用并明说"没有更早的可以清理"', async () => {
    renderCleanup({ messages: twelveTurns().slice(0, 2), hasOlder: false });
    openDialog();
    expect(await screen.findByText(/没有更早的可以清理/)).toBeTruthy();
    expect(previewButton()).toBeDisabled();
  });
});
