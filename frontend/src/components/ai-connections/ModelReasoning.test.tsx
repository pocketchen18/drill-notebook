import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ModelEditor } from './ConnectionEditors';
import { DiscoveryDialog } from './ConnectionDialogs';
import type { ConnectionModel, ConnectionProvider, ModelPreset } from './types';

const { read, request } = vi.hoisted(() => ({ read: vi.fn(), request: vi.fn() }));
vi.mock('../../lib/api', () => ({ get: (...args: unknown[]) => read(...args), apiRequest: (...args: unknown[]) => request(...args) }));
const preset: ModelPreset = { id: 'gpt-5.2', name: 'GPT-5.2', vendor: 'openai', apiFormat: 'chat_completions', aliases: ['openai/gpt-5.2'], contextWindow: 400000, maxOutputTokens: 128000,
  reasoningControl: 'effort', reasoningEfforts: ['none', 'low', 'medium', 'high', 'xhigh'] };
const provider: ConnectionProvider = { id: 'p', name: 'Gateway', endpoint: 'https://example.invalid/v1', apiFormat: 'chat_completions', authMode: 'none', hasKey: false, needsPassword: false, models: [] };
function wrap(element: React.ReactNode) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{element}</QueryClientProvider>);
}
function editor(model?: ConnectionModel) {
  const save = vi.fn().mockResolvedValue(false), leave = vi.fn();
  wrap(<ModelEditor provider={provider} model={model} save={save} onState={vi.fn()} leave={leave} done={vi.fn()} busy={false} />);
  return { save, leave };
}
describe('model presets and reasoning settings', () => {
  beforeEach(() => { vi.clearAllMocks(); read.mockResolvedValue([preset]); });
  it('new models default to effort and matched metadata fills without changing permission', async () => {
    const { save } = editor();
    expect(screen.getByLabelText('控制方式')).toHaveValue('effort');
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'openai/gpt-5.2' } });
    await waitFor(() => expect(screen.getByLabelText('模型名称')).toHaveValue('GPT-5.2'));
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('400000');
    expect(screen.getByRole('switch', { name: '允许工具调用' })).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(screen.getByRole('button', { name: '验证并添加模型' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][2]).toMatchObject({ modelId: 'openai/gpt-5.2', defaultReasoningEffort: '', reasoningControl: 'effort', reasoningEfforts: preset.reasoningEfforts });
  });
  it('late preset data never overwrites a manually edited field', async () => {
    let resolve!: (value: ModelPreset[]) => void;
    read.mockReturnValue(new Promise<ModelPreset[]>(done => { resolve = done; }));
    editor();
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'gpt-5.2' } });
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '我的名称' } });
    await act(async () => { resolve([preset]); });
    await waitFor(() => expect(screen.getByLabelText('上下文窗口')).toHaveValue('400000'));
    expect(screen.getByLabelText('模型名称')).toHaveValue('我的名称');
  });
  it('search preserves selections, gaps are accurate, deleting default falls back and Escape only closes popup', async () => {
    const model: ConnectionModel = { id: 'm', modelId: 'custom', name: 'Model', group: '', streaming: true, tools: false,
      reasoningControl: 'effort', reasoningEfforts: ['low', 'medium', 'high', 'xhigh', 'max'] };
    const { leave, save } = editor(model);
    fireEvent.click(screen.getByRole('button', { name: '思考等级 low → max' }));
    const dialog = screen.getByRole('dialog', { name: '配置思考等级' });
    expect(within(dialog).queryByLabelText('ultra')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('默认等级'), { target: { value: 'high' } });
    fireEvent.change(screen.getByLabelText('搜索思考等级'), { target: { value: 'high' } });
    expect(within(dialog).queryByRole('checkbox', { name: 'low' })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'high' }));
    expect(screen.getByLabelText('默认等级')).toHaveValue('');
    expect(screen.getByText('已移除默认等级，改用服务默认。')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('搜索思考等级'), { target: { value: '' } });
    expect(within(dialog).getByRole('checkbox', { name: 'low' })).toBeChecked();
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'high' }));
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'medium' }));
    expect(screen.getByRole('button', { name: '思考等级 low, high → max' })).toBeInTheDocument();
    fireEvent.keyDown(screen.getByLabelText('搜索思考等级'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(leave).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '思考等级 low, high → max' })).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][2].reasoningEfforts).toEqual(['low', 'high', 'xhigh', 'max']);
    expect(screen.getByRole('button', { name: '思考等级 low, high → max' })).toBeInTheDocument();
  });
  it.each(['pending', 'failed'])('saving with %s presets delegates untouched defaults to the server', async state => {
    read.mockImplementation(() => state === 'pending' ? new Promise(() => {}) : Promise.reject(new Error('offline')));
    const { save } = editor();
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'gpt-5.2' } });
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '自定义名称' } });
    if (state === 'failed') await screen.findByText(/预设资料加载失败/);
    fireEvent.click(screen.getByRole('button', { name: '验证并添加模型' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][2]).toMatchObject({ modelId: 'gpt-5.2', name: '自定义名称' });
    for (const field of ['contextWindow', 'maxOutputTokens', 'reasoningEfforts', 'reasoningControl']) expect(save.mock.calls[0][2]).not.toHaveProperty(field);
  });
  it('an unsupported saved effort can be removed after a protocol change', async () => {
    const model: ConnectionModel = { id: 'm', modelId: 'custom', name: 'Model', group: '', streaming: true, tools: false,
      reasoningControl: 'effort', reasoningEfforts: ['none', 'high'], defaultReasoningEffort: 'none' };
    const save = vi.fn().mockResolvedValue(false);
    wrap(<ModelEditor provider={{ ...provider, apiFormat: 'anthropic' }} model={model} save={save} onState={vi.fn()} leave={vi.fn()} done={vi.fn()} busy={false} />);
    fireEvent.click(screen.getByRole('button', { name: '思考等级 none, high' }));
    const option = screen.getByRole('checkbox', { name: /none/ });
    expect(option).toBeEnabled(); fireEvent.click(option);
    expect(option).toBeDisabled(); expect(screen.getByLabelText('默认等级')).toHaveValue('');
    fireEvent.keyDown(screen.getByLabelText('搜索思考等级'), { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][2]).toMatchObject({ reasoningEfforts: ['high'], defaultReasoningEffort: '' });
  });
  it('existing settings stay unchanged until restore is explicitly used', async () => {
    editor({ id: 'm', modelId: 'gpt-5.2', name: 'Custom', group: '', streaming: false, tools: true, contextWindow: 20000, maxOutputTokens: 1000, reasoningEfforts: ['low'], defaultReasoningEffort: 'low' });
    await screen.findByText(/参考预设：GPT-5.2/);
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('20000');
    expect(screen.getByRole('button', { name: '思考等级 low' })).toBeInTheDocument();
    fireEvent.click(screen.getByText('高级兼容设置'));
    fireEvent.click(screen.getByRole('button', { name: '恢复预设资料' }));
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('400000');
    expect(screen.getByLabelText('模型名称')).toHaveValue('Custom');
    expect(screen.getByText('默认：low')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: '流式输出' })).toHaveAttribute('aria-checked', 'false');
  });
  it('discovery shows matched metadata but only submits the selected upstream IDs', async () => {
    request.mockResolvedValue(['gpt-5.2', 'gpt-5.2-private']);
    const add = vi.fn().mockResolvedValue(true);
    wrap(<DiscoveryDialog provider={provider} close={vi.fn()} add={add} busy={false} />);
    await screen.findByText(/已匹配预设 · GPT-5.2/);
    expect(screen.getByText('未匹配预设，可手动配置')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: /gpt-5.2 已匹配预设/ }));
    fireEvent.click(screen.getByRole('button', { name: '验证并添加（1）' }));
    await waitFor(() => expect(add).toHaveBeenCalledWith(['gpt-5.2'], ''));
  });
});
