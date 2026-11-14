import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { CurrentContextCard, CurrentContextCardView } from './CurrentContextCard';
import { ContextMeter, type SessionContextUsage } from '../ai-session/ContextMeter';

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }));
vi.mock('../../lib/api', () => ({ get: apiGet }));

const usage = (overrides: Partial<SessionContextUsage> = {}): SessionContextUsage => ({
  sessionId: 7, model: 'current-model', contextWindow: 32000, outputReserve: 4096,
  current: { tokens: 8000, requestId: 'latest-turn', estimated: true, complete: true, contextGeneration: 1,
    historyRevision: 3, active: false, estimatorVersion: 'context-estimate-v1', summaryRevision: null,
    categories: [{ kind: 'SYSTEM', tokens: 1000 }, { kind: 'USER', tokens: 3000 }, { kind: 'TOOL_RESULTS', tokens: 4000 }] },
  latest: { requestId: 'old-request', inputTokens: 1000, contextWindow: 32000, model: 'old-model', cacheReadTokens: 500, outputTokens: 100 },
  cumulative: { requests: 10, inputTokens: 90000, outputTokens: 10000, cacheReadTokens: 80000,
    cacheMissTokens: 10000, totalTokens: 100000, inputComplete: true, outputComplete: true,
    cacheReadComplete: true, cacheMissComplete: true, totalComplete: true },
  ...overrides
});
const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });
beforeEach(() => { vi.clearAllMocks(); });

describe('当前上下文卡片', () => {
  it('占用、当前模型和分类只来自 current，进度条按窗口表示占用', () => {
    const { container } = render(<CurrentContextCardView data={usage()} />);
    expect(screen.getByText('8.0k')).toBeTruthy();
    expect(screen.getByText('25%')).toBeTruthy();
    expect(screen.getByText('current-model')).toBeTruthy();
    expect(screen.queryByText('old-model')).toBeNull();
    expect(screen.queryByText('缓存命中')).toBeNull();
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '25');
    const widths = [...container.querySelectorAll('.current-context-bar > span')].map(span => (span as HTMLElement).style.width);
    expect(widths).toEqual(['3.125%', '9.375%', '12.5%']);
    expect([...container.querySelectorAll('dd')].map(row => row.textContent)).toEqual(['≈ 1.0k13%', '≈ 3.0k38%', '≈ 4.0k50%']);
  });

  it('未回 current 或分类不匹配时不借用 latest 和累计数字补全', () => {
    const view = render(<CurrentContextCardView data={usage({ current: null })} />);
    expect(screen.getByText('当前上下文暂不可用')).toBeTruthy();
    expect(screen.queryByRole('meter')).toBeNull();
    view.rerender(<CurrentContextCardView data={usage({ current: { ...usage().current!, categories: [{ kind: 'SYSTEM', tokens: 1 }] } })} />);
    expect(screen.getByText('8.0k')).toBeTruthy();
    expect(screen.getByText('当前占用未提供分类明细')).toBeTruthy();
    expect(screen.queryByText('系统提示词')).toBeNull();
  });

  it('下界、窗口未知和超过窗口保持实际数值，图形封顶', () => {
    const view = render(<CurrentContextCardView data={usage({ contextWindow: 4000,
      current: { ...usage().current!, complete: false } })} />);
    expect(screen.getByText('≥ 8.0k')).toBeTruthy();
    expect(screen.getByText('≥ 200%')).toBeTruthy();
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '100');
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuetext', '至少 200%');
    expect(screen.getByText(/显示已知占用下界/)).toBeTruthy();
    view.rerender(<CurrentContextCardView data={usage({ contextWindow: null })} />);
    expect(screen.getByText('/ 窗口未知')).toBeTruthy();
    expect(screen.getByText('8.0k')).toBeTruthy();
    expect(screen.queryByText('25%')).toBeNull();
  });

  it('总量为零不会出现 NaN 或伪造的分类', () => {
    const { container } = render(<CurrentContextCardView data={usage({ current: { ...usage().current!, tokens: 0, categories: [] } })} />);
    expect(screen.getByText('0%')).toBeTruthy();
    expect(container.textContent).not.toContain('NaN');
    expect(container.querySelector('.current-context-categories')).toBeNull();
  });

  it('详情打开时刷新当前会话，与圆环共享同一请求快照', async () => {
    apiGet.mockResolvedValue(usage());
    const queryClient = client();
    render(<QueryClientProvider client={queryClient}>
      <ContextMeter sessionId={7} refreshKey="same" onInspect={vi.fn()} />
      <CurrentContextCard sessionId={7} refreshKey="same" />
    </QueryClientProvider>);
    await screen.findByText('8.0k');
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '上下文占用 25%（估算）' })).toBeTruthy();
    const grown = usage({ current: { ...usage().current!, tokens: 16000,
      categories: [{ kind: 'SYSTEM', tokens: 1000 }, { kind: 'ASSISTANT', tokens: 15000 }] } });
    apiGet.mockResolvedValue(grown);
    await waitFor(() => expect(screen.getByText('16.0k')).toBeTruthy(), { timeout: 3000 });
    expect(screen.getByRole('button', { name: '上下文占用 50%（估算）' })).toBeTruthy();
  });

  it('切换会话后迟到响应不能覆盖目标会话', async () => {
    let resolve!: (value: SessionContextUsage) => void;
    apiGet.mockImplementation((path: string) => path.includes('/7/')
      ? new Promise(done => { resolve = done; }) : Promise.resolve(usage({ sessionId: 8, model: 'new-session-model' })));
    const queryClient = client();
    const frame = (sessionId: number) => <QueryClientProvider client={queryClient}><CurrentContextCard sessionId={sessionId} /></QueryClientProvider>;
    const view = render(frame(7));
    expect(screen.getByText('正在读取当前上下文…')).toBeTruthy();
    view.rerender(frame(8));
    await screen.findByText('new-session-model');
    await act(async () => resolve(usage()));
    expect(screen.queryByText('current-model')).toBeNull();
    expect(within(screen.getByRole('region', { name: '当前上下文' })).getByText('new-session-model')).toBeTruthy();
  });
});
