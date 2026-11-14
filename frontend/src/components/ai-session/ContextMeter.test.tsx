import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ContextMeter, formatTokens } from './ContextMeter';

const { apiGet, readContext } = vi.hoisted(() => ({ apiGet: vi.fn(), readContext: vi.fn() }));
vi.mock('../../lib/api', async (original) => ({ ...await original<typeof import('../../lib/api')>(), get: apiGet }));
vi.mock('../../lib/aiContextApi', async (original) => ({ ...await original<typeof import('../../lib/aiContextApi')>(), readTurnContext: readContext }));

const audit = (extra: Record<string, unknown> = {}) => ({ requestId: 'r1', sessionId: 5, status: 'COMPLETED', available: true, audit: {
  schemaVersion: 1, planId: 'p', dispatchState: 'sent', estimatedInputTokens: 40000, inputBudget: 96000, estimatorVersion: 'v', selected: [],
  regions: [{ kind: 'RECENT_TURNS', estimatedTokens: 31000, itemCount: 6 }, { kind: 'MEMORY', estimatedTokens: 1100, itemCount: 2 },
    { kind: 'SYSTEM', estimatedTokens: 900, itemCount: 1 }, { kind: 'USER_TEXT', estimatedTokens: 200, itemCount: 1 }],
  omissions: [], scan: {}, summary: null, resumeState: null, auditTruncated: false, ...extra } });
function mount(props: Partial<Parameters<typeof ContextMeter>[0]> = {}) {
  const onInspect = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><ContextMeter sessionId={5} onInspect={onInspect} {...props} /></QueryClientProvider>);
  return { onInspect, view, client };
}
const cumulative = (input: number, hit: number, output: number) => ({ requests: 6, inputTokens: input, cacheReadTokens: hit,
  cacheMissTokens: input - hit, outputTokens: output, totalTokens: input + output, inputComplete: true,
  outputComplete: true, cacheReadComplete: true, cacheMissComplete: true, totalComplete: true });
const current = (tokens: number, requestId = 'r1') => ({ tokens, requestId, estimated: true, complete: true,
  contextGeneration: 1, historyRevision: 2, active: false, estimatorVersion: 'context-estimate-v1', summaryRevision: null });
beforeEach(() => { vi.clearAllMocks(); readContext.mockResolvedValue(audit()); });

describe('上下文占用', () => {
  it('用当前会话全局上下文计算窗口占用，累计用量与最近请求都不会顶替它', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'deepseek-reasoner', contextWindow: 128000, outputReserve: 8192,
      latest: { requestId: 'r1', inputTokens: 52400, contextWindow: 128000, model: 'DeepSeek · deepseek-reasoner',
        cacheReadTokens: 40000, outputTokens: 1250 }, current: current(64000), cumulative: cumulative(154800, 120000, 12500) });
    const { onInspect } = mount();
    const trigger = await screen.findByRole('button', { name: '上下文占用 50%（估算）' });
    expect(apiGet).toHaveBeenCalledWith('/api/ai/sessions/5/context-usage');
    expect(readContext).not.toHaveBeenCalled();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: '上下文占用' });
    expect(dialog.querySelector('.context-meter-headline')?.textContent).toBe('上下文总量 64.0k / 128k');
    // 模型名只出现一次；窗口已在第一行，输出预留不再显示
    expect(dialog.querySelector('.context-meter-foot .context-meter-meta')?.textContent).toBe('deepseek-reasoner');
    expect(dialog.querySelector('.context-meter-body .context-meter-meta')).toBeNull();
    expect(dialog.querySelector('.context-meter-foot')?.textContent).toBe('deepseek-reasoner查看上下文详情');
    expect(within(dialog).queryByText(/预留|窗口/)).toBeNull();
    const rows = [...dialog.querySelectorAll('.context-meter-rows > div')].map((row) => row.textContent);
    expect(rows).toEqual(['输入总量155k', '缓存命中120k · 命中率 78%', '缓存未命中34.8k', '输出12.5k']);
    expect(dialog.querySelector('.context-meter-rows')?.getAttribute('title')).toBe('会话累计输入 155k · 总 Token 167k');
    // 分项估算、工具误差这些细节不再出现在浮层里，也不为它们多发一次请求
    expect(readContext).not.toHaveBeenCalled();
    expect(within(dialog).queryByText('历史对话与摘要')).toBeNull();
    expect(within(dialog).queryByText(/工具定义、工具结果/)).toBeNull();
    expect(within(dialog).queryByText('不含多次工具往返的累计用量')).toBeNull();
    expect(within(dialog).queryByText(/接近模型窗口上限/)).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: '查看上下文详情' }));
    expect(onInspect).toHaveBeenCalledWith('r1');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('供应商未回报时仍可显示全局上下文估算，不编造计费用量', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 32000, outputReserve: 4096, latest: null, current: current(8000, 'r2') });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 25%（估算）' }));
    expect(document.querySelector('.context-meter-headline')?.textContent).toBe('上下文总量 8.0k / 32.0k');
    expect(screen.queryByText(/供应商没有回报用量/)).toBeNull();
    // 没有实测值时不编造命中与输出
    expect([...document.querySelectorAll('.context-meter-rows dd')].map((node) => node.textContent)).toEqual(['未回报', '未回报', '未回报', '未回报']);
  });

  it('供应商只回报输入时缓存与输出标为未回报，全局占用独立增长', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 100000, outputReserve: null,
      latest: { requestId: 'r1', inputTokens: 20000, contextWindow: 100000, model: 'm', cacheReadTokens: null, outputTokens: null }, current: current(23000),
      cumulative: { ...cumulative(20000, 0, 0), cacheReadTokens: null, cacheMissTokens: null, outputTokens: null,
        cacheReadComplete: false, cacheMissComplete: false, outputComplete: false, totalComplete: false, totalTokens: null } });
    const view = mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 23%（估算）' }));
    expect([...document.querySelectorAll('.context-meter-rows dd')].map((node) => node.textContent)).toEqual(['20.0k', '未回报', '未回报', '未回报']);
    expect(view.onInspect).not.toHaveBeenCalled();
  });

  it('全部命中缓存时未命中为 0', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 100000, outputReserve: null,
      latest: { requestId: 'r1', inputTokens: 20000, contextWindow: 100000, model: 'm', cacheReadTokens: 26000, outputTokens: 0 },
      current: current(20000), cumulative: cumulative(20000, 20000, 0) });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 20%（估算）' }));
    expect([...document.querySelectorAll('.context-meter-rows dd')].map((node) => node.textContent)).toEqual(['20.0k', '20.0k · 命中率 100%', '0', '0']);
  });

  it('缺失部分用量时显示已回报下界，不用不完整分母计算命中率', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 100000,
      latest: { requestId: 'r1', inputTokens: 20000, contextWindow: 100000 },
      current: current(20000), cumulative: { ...cumulative(30000, 12000, 1000), inputComplete: false, cacheReadComplete: false,
        cacheMissComplete: false, outputComplete: false, totalComplete: false } });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 20%（估算）' }));
    expect([...document.querySelectorAll('.context-meter-rows dd')].map((node) => node.textContent))
      .toEqual(['≥ 30.0k', '≥ 12.0k', '≥ 18.0k', '≥ 1.0k']);
    expect(screen.queryByText(/命中率/)).toBeNull();
  });

  it('清理当前上下文后仍能查看会话已发生的累计用量', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 100000, latest: null, current: current(500, ''),
      cumulative: cumulative(60000, 40000, 3000) });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 1%（估算）' }));
    expect([...document.querySelectorAll('.context-meter-rows dd')].map((node) => node.textContent))
      .toEqual(['60.0k', '40.0k · 命中率 67%', '20.0k', '3.0k']);
  });

  it('打开面板重新读取已结算的工具请求，并保持窗口占用与累计独立', async () => {
    const response = { sessionId: 5, model: 'm', contextWindow: 100000,
      latest: { requestId: 'r1', inputTokens: 20000, contextWindow: 100000 }, current: current(24000), cumulative: cumulative(30000, 12000, 1000) };
    apiGet.mockResolvedValueOnce(response).mockResolvedValue({ ...response, cumulative: cumulative(60000, 30000, 2000) });
    mount();
    const trigger = await screen.findByRole('button', { name: '上下文占用 24%（估算）' });
    fireEvent.click(trigger);
    await waitFor(() => expect(document.querySelector('.context-meter-rows')?.textContent)
      .toBe('输入总量60.0k缓存命中30.0k · 命中率 50%缓存未命中30.0k输出2.0k'));
    expect(trigger.textContent).toBe('24%');
  });

  it('切换会话关闭旧卡片，迟到响应不会替换目标会话的数据', async () => {
    let finishOld!: (value: unknown) => void;
    apiGet.mockImplementation((url: string) => url.includes('/5/') ? new Promise(resolve => { finishOld = resolve; })
      : Promise.resolve({ sessionId: 6, model: 'm', contextWindow: 100000,
        latest: { requestId: 'r6', inputTokens: 10000, contextWindow: 100000 }, current: current(12000, 'r6'), cumulative: cumulative(40000, 10000, 400) }));
    const { view, client, onInspect } = mount();
    view.rerender(<QueryClientProvider client={client}><ContextMeter sessionId={6} onInspect={onInspect} /></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 12%（估算）' }));
    finishOld({ sessionId: 5, model: '旧', contextWindow: 100000, latest: { inputTokens: 99000 }, current: current(99000) });
    await waitFor(() => expect(document.querySelector('.context-meter-rows')?.textContent)
      .toBe('输入总量40.0k缓存命中10.0k · 命中率 25%缓存未命中30.0k输出400'));
    expect(screen.getByRole('button', { name: '上下文占用 12%（估算）' })).toBeInTheDocument();
  });

  it('没有全局数据时显示占位，不拿最近请求实测替代', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 32000, outputReserve: 4096, current: null,
      latest: { inputTokens: 16000 } });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用：尚无数据' }));
    expect(screen.getByText('还没有可用的占用数据')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '查看上下文详情' })).toBeNull();
  });

  it('高占用有提示并按当前模型窗口换算；摘要与裁剪的说明留给上下文详情', async () => {
    readContext.mockResolvedValue(audit({ summary: { revision: 2, throughTurnId: 9, hasGaps: true, coverageRanges: [] },
      omissions: [{ reason: 'BUDGET', kind: 'RECENT_TURNS', count: 3 }] }));
    apiGet.mockResolvedValue({ sessionId: 5, model: '小窗口模型', contextWindow: 60000, outputReserve: 4096,
      latest: { requestId: 'r1', inputTokens: 52400, contextWindow: 128000, model: 'x' }, current: current(52400) });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 87%（估算）' }));
    expect(screen.queryByText(/该请求发出时使用的模型窗口/)).toBeNull();
    expect(screen.getByText(/接近模型窗口上限/)).toBeInTheDocument();
    expect(screen.queryByText(/已用摘要代替/)).toBeNull();
    expect(screen.queryByText(/没有送入模型/)).toBeNull();
    expect(readContext).not.toHaveBeenCalled();
  });

  it('超出当前窗口时保留实际百分比，图形封顶但不会把占用数据截到窗口上限', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 32000, current: current(40000), latest: null });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 125%（估算）' }));
    expect(document.querySelector('.context-meter-headline')?.textContent).toBe('上下文总量 40.0k / 32.0k');
    expect(document.querySelector('.context-meter-bar > span')).toHaveStyle({ width: '100%' });
  });

  it('旧内容估算不完整时明确显示下界，不以最新请求或累计输入补足', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 100000,
      current: { ...current(22000), complete: false }, latest: { inputTokens: 40000 }, cumulative: cumulative(200000, 150000, 10000) });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用至少 22%（估算）' }));
    expect(document.querySelector('.context-meter-headline')?.textContent).toBe('上下文总量 ≥ 22.0k / 100k');
    expect(screen.getByText(/显示已知占用下界/)).toBeInTheDocument();
  });

  it('回复增长时卡片未打开也会刷新圆环占用，缓存命中率不会顶替它', async () => {
    const response = { sessionId: 5, model: 'm', contextWindow: 32000, latest: null,
      current: current(8000), cumulative: cumulative(100000, 90000, 1000) };
    apiGet.mockResolvedValueOnce(response).mockResolvedValue({ ...response, current: current(16000) });
    mount({ streaming: true });
    await screen.findByRole('button', { name: '上下文占用 25%（估算）' });
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(screen.getByRole('button', { name: '上下文占用 50%（估算）' })).toBeInTheDocument(), { timeout: 3000 });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('新会话页或模型不可用时不显示；数字格式紧凑', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: null, contextWindow: null, outputReserve: null, latest: null });
    const client = new QueryClient();
    const { container } = render(<QueryClientProvider client={client}><ContextMeter onInspect={vi.fn()} /></QueryClientProvider>);
    expect(container).toBeEmptyDOMElement();
    expect(apiGet).not.toHaveBeenCalled();
    expect([formatTokens(950), formatTokens(1200), formatTokens(128000)]).toEqual(['950', '1.2k', '128k']);
  });
});
