import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { RequestContextEntry, RequestContextPage, RequestContextSnapshot, TurnContextRead } from '../../lib/aiContextApi';
import { RequestContextBrowser } from './RequestContextBrowser';

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }));
vi.mock('../../lib/api', async importOriginal => ({
  ...await importOriginal<typeof import('../../lib/api')>(),
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args)
}));

type Category = RequestContextSnapshot['categories'][number];
const group = (kind: string, tokens: number, name: string | null = null): Category => ({
  kind, estimatedTokens: tokens, itemCount: 1,
  items: [{ key: `${kind}-1`, name, estimatedTokens: tokens }]
});
function snapshot(categories: Category[] = [group('SYSTEM', 100), group('TOOL_DEFINITIONS', 400, 'fetch_web'),
  group('TOOL_RESULTS', 500, 'fetch_web')]): RequestContextSnapshot {
  return { schemaVersion: 1, estimatorVersion: 'test-estimator', model: 'test-model', kind: 'CHAT',
    estimatedInputTokens: categories.reduce((sum, item) => sum + item.estimatedTokens, 0),
    contextWindow: 128000, categories, detailsTruncated: false };
}
function entry(ordinal: number, overrides: Partial<RequestContextEntry> = {}): RequestContextEntry {
  return { attemptId: `attempt-${ordinal}`, operationId: 'turn-a', ordinal,
    createdAt: '2026-11-15T12:34:56Z', settledAt: '2026-11-15T12:35:01Z', state: 'SETTLED', usageSource: 'provider',
    inputTokens: 1600, outputTokens: 90, cacheReadTokens: 1000, cacheMissTokens: 600, totalTokens: 1690,
    inputComplete: true, outputComplete: true, cacheReadComplete: true, cacheMissComplete: true, totalComplete: true,
    context: snapshot(), ...overrides };
}
function page(items: RequestContextEntry[], overrides: Partial<RequestContextPage> = {}): RequestContextPage {
  return { items, totalRequests: items.length, hasMore: false, nextBeforeAttemptId: null, limit: 50, ...overrides };
}
function read(requestContexts: RequestContextPage, requestId = 'turn-a'): TurnContextRead {
  return { requestId, sessionId: 7, status: 'COMPLETED', available: true, requestContexts };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

beforeEach(() => vi.clearAllMocks());

describe('RequestContextBrowser', () => {
  it('选择和展开只使用已有摘要，分项估算不按实测总量缩放', () => {
    render(<RequestContextBrowser requestId="turn-a" page={page([entry(1), entry(2)])} />);
    expect(screen.getByRole('combobox', { name: '模型请求' })).toHaveValue('attempt-2');
    expect(screen.getByText('实测输入 1.6k · 输出 90')).toBeTruthy();
    expect(screen.getByText('估算合计 ≈ 1.0k')).toBeTruthy();
    const results = screen.getByText('工具结果').closest('details')!;
    fireEvent.click(results.querySelector('summary')!);
    expect(results.hasAttribute('open')).toBe(true);
    expect(within(results).getByRole('listitem').textContent).toContain('fetch_web');
    expect(within(results).getByRole('listitem').textContent).toContain('≈ 500');
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'attempt-1' } });
    expect(screen.getByRole('button', { name: '对比上次请求' })).toBeDisabled();
    expect(apiGet).not.toHaveBeenCalled();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('选定旧请求后保留选择，选择最新请求时继续跟随新的实际请求', () => {
    const view = render(<RequestContextBrowser requestId="turn-a" page={page([entry(1), entry(2)])} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'attempt-1' } });
    view.rerender(<RequestContextBrowser requestId="turn-a" page={page([entry(2), entry(3)])} />);
    expect(screen.getByRole('combobox')).toHaveValue('attempt-1');
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'attempt-3' } });
    view.rerender(<RequestContextBrowser requestId="turn-a" page={page([entry(2), entry(3), entry(4)])} />);
    expect(screen.getByRole('combobox')).toHaveValue('attempt-4');
  });

  it('对比包括已移除分类，增加和减少按两次请求的估算显示', () => {
    const first = entry(1, { context: snapshot([group('TOOL_DEFINITIONS', 150), group('TOOL_RESULTS', 300)]) });
    const second = entry(2, { context: snapshot([group('TOOL_RESULTS', 500)]) });
    render(<RequestContextBrowser requestId="turn-a" page={page([first, second])} />);
    fireEvent.click(screen.getByRole('button', { name: '对比上次请求' }));
    const removed = screen.getByText('工具定义').closest('details')!;
    expect(within(removed).getByText('−150')).toBeTruthy();
    expect(within(removed).getByText('-1')).toBeTruthy();
    const increased = screen.getByText('工具结果').closest('details')!;
    expect(within(increased).getByText('+200')).toBeTruthy();
    expect(apiGet).not.toHaveBeenCalled();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('有界明细保留分类总数和总量，并按占用排序且能定位消息', () => {
    const tools: Category = { kind: 'TOOL_RESULTS', estimatedTokens: 900, itemCount: 400, items: [
      { key: 'message-1', name: 'fetch_web', estimatedTokens: 100 },
      { key: 'message-9', name: 'search_web', estimatedTokens: 400 },
      { key: 'message-3', name: 'fetch_web', estimatedTokens: 200 }
    ] };
    render(<RequestContextBrowser requestId="turn-a" page={page([entry(1, { context: snapshot([tools]) })])} />);
    const results = screen.getByText('工具结果').closest('details')!;
    fireEvent.click(results.querySelector('summary')!);
    const rows = within(results).getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(rows[0].textContent).toContain('search_web · 消息 10');
    expect(rows[0].textContent).toContain('≈ 400');
    expect(within(results).getByText('400 项')).toBeTruthy();
    expect(within(results).getByText('另 397 项 · ≈ 200，已计入分类合计。')).toBeTruthy();
  });

  it('旧请求没有摘要时不借用最新摘要，缺失和部分实测不会伪造成零', () => {
    render(<RequestContextBrowser requestId="turn-a" page={page([entry(1, {
      context: null, inputTokens: 320, inputComplete: false, outputTokens: null, outputComplete: false
    }), entry(2)])} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'attempt-1' } });
    expect(screen.getByText('实测输入 ≥ 320 · 输出 未回报')).toBeTruthy();
    expect(screen.getByText('这次请求未保存组成明细；实测用量仍按供应商回报展示。')).toBeTruthy();
    expect(screen.queryByText('工具结果')).toBeNull();
    expect(screen.getByRole('button', { name: '对比上次请求' })).toBeDisabled();
    expect(apiGet).not.toHaveBeenCalled();
  });

  it('仅按同一轮游标读取更早请求，分页重叠去重并保留最新页刷新', async () => {
    const latest = page([entry(32), entry(33)], { totalRequests: 33, hasMore: true, nextBeforeAttemptId: 'attempt-32' });
    apiGet.mockResolvedValueOnce(read(page([entry(30), entry(31)], {
      totalRequests: 33, hasMore: true, nextBeforeAttemptId: 'attempt-30'
    }))).mockResolvedValueOnce(read(page([entry(29), entry(30)], { totalRequests: 33 })));
    const view = render(<RequestContextBrowser requestId="turn-a" page={latest} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'older' } });
    await waitFor(() => expect(screen.getByRole('combobox')).toHaveValue('attempt-31'));
    expect(apiGet).toHaveBeenLastCalledWith('/api/ai/turns/turn-a/context?beforeAttemptId=attempt-32');
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'older' } });
    await waitFor(() => expect(screen.getByRole('combobox')).toHaveValue('attempt-30'));
    expect(apiGet).toHaveBeenLastCalledWith('/api/ai/turns/turn-a/context?beforeAttemptId=attempt-30');
    expect(screen.getAllByRole('option')).toHaveLength(5);
    view.rerender(<RequestContextBrowser requestId="turn-a" page={page([entry(33), entry(34)], { totalRequests: 34 })} />);
    expect(screen.getByRole('combobox')).toHaveValue('attempt-30');
    expect(screen.getByText('本轮 34 次请求')).toBeTruthy();
    expect(screen.getAllByRole('option')).toHaveLength(6);
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('轮次切换会销毁分页和对比状态，旧轮迟到响应不能污染新轮', async () => {
    const pending = deferred<TurnContextRead>();
    apiGet.mockReturnValue(pending.promise);
    const view = render(<RequestContextBrowser requestId="turn-a" page={page([entry(2), entry(3)], {
      totalRequests: 80, hasMore: true, nextBeforeAttemptId: 'attempt-2'
    })} />);
    fireEvent.click(screen.getByRole('button', { name: '对比上次请求' }));
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'older' } });
    view.rerender(<RequestContextBrowser requestId="turn-b" page={page([entry(90, { operationId: 'turn-b' })])} />);
    await act(async () => pending.resolve(read(page([entry(1)]))));
    expect(screen.getByRole('combobox')).toHaveValue('attempt-90');
    expect(screen.getAllByRole('option')).toHaveLength(1);
    expect(screen.getByRole('button', { name: '对比上次请求' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByText(/读取失败/)).toBeNull();
  });

  it('分页拒绝或返回其他轮次时保留当前数据，允许重新加载', async () => {
    apiGet.mockResolvedValueOnce(read(page([entry(1)]), 'turn-b'))
      .mockResolvedValueOnce(read(page([entry(1)])));
    render(<RequestContextBrowser requestId="turn-a" page={page([entry(2)], {
      totalRequests: 2, hasMore: true, nextBeforeAttemptId: 'attempt-2'
    })} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'older' } });
    await waitFor(() => expect(screen.getByText(/更早的请求详情读取失败/)).toBeTruthy());
    expect(screen.getByRole('combobox')).toHaveValue('attempt-2');
    expect(screen.getByRole('combobox')).not.toBeDisabled();
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'older' } });
    await waitFor(() => expect(screen.getByRole('combobox')).toHaveValue('attempt-1'));
    expect(screen.queryByText(/读取失败/)).toBeNull();
  });
});
