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
  render(<QueryClientProvider client={client}><ContextMeter sessionId={5} onInspect={onInspect} {...props} /></QueryClientProvider>);
  return { onInspect };
}
beforeEach(() => { vi.clearAllMocks(); readContext.mockResolvedValue(audit()); });

describe('上下文占用', () => {
  it('只显示输入（缓存命中 / 未命中）与输出，分项估算留给本轮上下文', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'deepseek-reasoner', contextWindow: 128000, outputReserve: 8192,
      latest: { requestId: 'r1', inputTokens: 52400, contextWindow: 128000, model: 'DeepSeek · deepseek-reasoner',
        cacheReadTokens: 40000, outputTokens: 1250 } });
    const { onInspect } = mount();
    const trigger = await screen.findByRole('button', { name: '上下文占用 41%（实测）' });
    expect(apiGet).toHaveBeenCalledWith('/api/ai/sessions/5/context-usage');
    expect(readContext).not.toHaveBeenCalled();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: '上下文占用' });
    expect(dialog.querySelector('.context-meter-headline')?.textContent).toBe('输入 52.4k / 128k');
    // 模型名只出现一次；窗口已在第一行，输出预留不再显示
    expect(dialog.querySelector('.context-meter-meta')?.textContent).toBe('deepseek-reasoner');
    expect(within(dialog).queryByText(/预留|窗口/)).toBeNull();
    const rows = [...dialog.querySelectorAll('.context-meter-rows > div')].map((row) => row.textContent);
    expect(rows).toEqual(['缓存命中40.0k · 占输入 76%', '缓存未命中12.4k', '输出1.3k']);
    // 分项估算、工具误差这些细节不再出现在浮层里，也不为它们多发一次请求
    expect(readContext).not.toHaveBeenCalled();
    expect(within(dialog).queryByText('历史对话与摘要')).toBeNull();
    expect(within(dialog).queryByText(/工具定义、工具结果/)).toBeNull();
    expect(within(dialog).getByText('不含多次工具往返的累计用量')).toBeInTheDocument();
    expect(within(dialog).queryByText(/接近模型窗口上限/)).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: '查看本轮上下文' }));
    expect(onInspect).toHaveBeenCalledWith('r1');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('供应商未回报时标明估算；没有任何数据时不给数字', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 32000, outputReserve: 4096, latest: null });
    mount({ estimate: { tokens: 8000, requestId: 'r2' } });
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 25%（估算）' }));
    expect(document.querySelector('.context-meter-headline')?.textContent).toBe('输入（估算） 8.0k / 32.0k');
    expect(screen.getByText(/供应商没有回报用量/)).toBeInTheDocument();
    // 没有实测值时不编造命中与输出
    expect([...document.querySelectorAll('.context-meter-rows dd')].map((node) => node.textContent)).toEqual(['未回报', '未回报', '未回报']);
  });

  it('供应商只回报输入时，缓存与输出标为未回报；命中量不会超过输入', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 100000, outputReserve: null,
      latest: { requestId: 'r1', inputTokens: 20000, contextWindow: 100000, model: 'm', cacheReadTokens: null, outputTokens: null } });
    const view = mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 20%（实测）' }));
    expect([...document.querySelectorAll('.context-meter-rows dd')].map((node) => node.textContent)).toEqual(['未回报', '未回报', '未回报']);
    expect(view.onInspect).not.toHaveBeenCalled();
  });

  it('全部命中缓存时未命中为 0', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 100000, outputReserve: null,
      latest: { requestId: 'r1', inputTokens: 20000, contextWindow: 100000, model: 'm', cacheReadTokens: 26000, outputTokens: 0 } });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 20%（实测）' }));
    expect([...document.querySelectorAll('.context-meter-rows dd')].map((node) => node.textContent)).toEqual(['20.0k · 占输入 100%', '0', '0']);
  });

  it('没有数据时显示占位；接近上限、已摘要或被裁剪时如实提示；切换模型后按新窗口计算', async () => {
    apiGet.mockResolvedValue({ sessionId: 5, model: 'm', contextWindow: 32000, outputReserve: 4096, latest: null });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用：尚无数据' }));
    expect(screen.getByText('还没有可用的占用数据')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '查看本轮上下文' })).toBeNull();
  });

  it('高占用有提示并按当前模型窗口换算；摘要与裁剪的说明留给本轮上下文', async () => {
    readContext.mockResolvedValue(audit({ summary: { revision: 2, throughTurnId: 9, hasGaps: true, coverageRanges: [] },
      omissions: [{ reason: 'BUDGET', kind: 'RECENT_TURNS', count: 3 }] }));
    apiGet.mockResolvedValue({ sessionId: 5, model: '小窗口模型', contextWindow: 60000, outputReserve: 4096,
      latest: { requestId: 'r1', inputTokens: 52400, contextWindow: 128000, model: 'x' } });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上下文占用 87%（实测）' }));
    expect(screen.getByText(/该请求发出时使用的模型窗口为 128k/)).toBeInTheDocument();
    expect(screen.getByText(/接近模型窗口上限/)).toBeInTheDocument();
    expect(screen.queryByText(/已用摘要代替/)).toBeNull();
    expect(screen.queryByText(/没有送入模型/)).toBeNull();
    expect(readContext).not.toHaveBeenCalled();
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
