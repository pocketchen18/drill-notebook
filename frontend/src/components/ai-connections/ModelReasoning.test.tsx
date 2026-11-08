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
  it('new models only fill preset fields after explicit selection, not an exact ID match', async () => {
    const { save } = editor();
    expect(screen.getByLabelText('控制方式')).toHaveValue('effort');
    const input = screen.getByLabelText('模型 ID');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'openai/gpt-5.2' } });
    const list = await screen.findByRole('listbox', { name: '预设模型' });
    expect(screen.getByLabelText('模型名称')).toHaveValue('');
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('');
    fireEvent.click(within(list).getByText('openai/gpt-5.2'));
    expect(screen.getByLabelText('模型名称')).toHaveValue('GPT-5.2');
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('400000');
    expect(screen.getByRole('switch', { name: '允许工具调用' })).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(screen.getByRole('button', { name: '验证并添加模型' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][2]).toMatchObject({ modelId: 'openai/gpt-5.2', defaultReasoningEffort: '', reasoningControl: 'effort', reasoningEfforts: preset.reasoningEfforts });
  });
  it('late preset data never writes draft fields, including untouched or empty fields', async () => {
    let resolve!: (value: ModelPreset[]) => void;
    read.mockReturnValue(new Promise<ModelPreset[]>(done => { resolve = done; }));
    editor();
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'gpt-5.2' } });
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '我的名称' } });
    await act(async () => { resolve([preset]); });
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('');
    expect(screen.getByLabelText('最大输出 Token')).toHaveValue('');
    expect(screen.getByRole('button', { name: '思考等级 未设置' })).toBeInTheDocument();
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
  it.each(['pending', 'failed'])('saving with %s presets submits the complete draft, without server autofill', async state => {
    read.mockImplementation(() => state === 'pending' ? new Promise(() => {}) : Promise.reject(new Error('offline')));
    const { save } = editor();
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'gpt-5.2' } });
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '自定义名称' } });
    if (state === 'failed') await screen.findByText(/预设资料加载失败/);
    fireEvent.click(screen.getByRole('button', { name: '验证并添加模型' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][2]).toMatchObject({ modelId: 'gpt-5.2', name: '自定义名称' });
    expect(save.mock.calls[0][2]).toMatchObject({ contextWindow: null, maxInputTokens: null, maxOutputTokens: null,
      reasoningEfforts: [], defaultReasoningEffort: '', thinkingBudgetTokens: null, reasoningControl: 'effort' });
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
    expect(await screen.findByText('未匹配预设，可手动配置')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: /gpt-5.2 已匹配预设/ }));
    fireEvent.click(screen.getByRole('button', { name: '验证并添加（1）' }));
    await waitFor(() => expect(add).toHaveBeenCalledWith(['gpt-5.2'], ''));
  });
  it('saved IDs change independently of every saved field, and only explicit selection replaces preset fields', async () => {
    const check = { status: 'passed' as const, message: '', elapsedMs: 1 };
    const { save } = editor({ id: 'stable', modelId: 'old-model', name: 'Old', group: '常用', tools: true, streaming: false,
      preset, contextWindow: 12000, maxInputTokens: 11000, maxOutputTokens: 1000, reasoningEfforts: ['high'], defaultReasoningEffort: 'high',
      verification: { text: check, image: check, tools: check, checkedAt: '2026-11-08T00:00:00Z' } });
    const input = screen.getByLabelText('模型 ID');
    expect(input).not.toHaveAttribute('readonly');
    fireEvent.focus(input);
    for (const id of ['old-mode', '', 'gpt-5.2', 'old-model', 'unknown']) {
      fireEvent.change(input, { target: { value: id } });
      expect(screen.getByLabelText('模型名称')).toHaveValue('Old');
      expect(screen.getByLabelText('分组名称')).toHaveValue('常用');
      expect(screen.getByLabelText('上下文窗口')).toHaveValue('12000');
      expect(screen.getByLabelText('最大输入 Token')).toHaveValue('11000');
      expect(screen.getByLabelText('最大输出 Token')).toHaveValue('1000');
      expect(screen.getByRole('button', { name: '思考等级 high' })).toBeInTheDocument();
      expect(screen.getByText('默认：high')).toBeInTheDocument();
      expect(screen.getByRole('switch', { name: '允许工具调用' })).toHaveAttribute('aria-checked', 'true');
      expect(screen.getByRole('switch', { name: '流式输出' })).toHaveAttribute('aria-checked', 'false');
    }
    expect(screen.getByText(/参考预设：GPT-5.2/)).toBeInTheDocument();
    expect(screen.queryByText('最近验证结果')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    expect(save.mock.calls[0][2]).toMatchObject({ modelId: 'unknown', name: 'Old', group: '常用', tools: true, streaming: false,
      contextWindow: 12000, maxInputTokens: 11000, maxOutputTokens: 1000, reasoningEfforts: ['high'], defaultReasoningEffort: 'high' });
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'gpt-5' } });
    await screen.findByRole('listbox', { name: '预设模型' });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
    expect(input).toHaveValue('gpt-5.2');
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('400000');
    expect(screen.getByLabelText('模型名称')).toHaveValue('GPT-5.2');
    expect(screen.getByText('默认：自动（服务默认）')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: '允许工具调用' })).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2));
    expect(save.mock.calls[1]).toEqual(['/models/stable', 'PUT', expect.objectContaining({ modelId: 'gpt-5.2', presetId: 'gpt-5.2', group: '常用', streaming: false, tools: true, defaultReasoningEffort: '', reasoningEfforts: preset.reasoningEfforts })]);
  });
  it('clicking a preset applies it explicitly, while escape and IME Enter do not select or leave', async () => {
    const { save, leave } = editor();
    const input = screen.getByLabelText('模型 ID');
    fireEvent.focus(input);
    const list = await screen.findByRole('listbox', { name: '预设模型' });
    expect(within(list).getByText('gpt-5.2')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: 'openai/gpt' } });
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 13, isComposing: true });
    expect(input).toHaveValue('openai/gpt');
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '手动名称' } });
    fireEvent.click(within(list).getByText('openai/gpt-5.2'));
    expect(input).toHaveValue('openai/gpt-5.2');
    expect(screen.getByLabelText('模型名称')).toHaveValue('GPT-5.2');
    fireEvent.focus(input);
    fireEvent.keyDown(input, { key: 'Escape', keyCode: 27 });
    expect(leave).not.toHaveBeenCalled(); expect(save).not.toHaveBeenCalled();
  });
  it('manual discovery selects a filtered preset and keeps the upstream alias', async () => {
    request.mockResolvedValue([]);
    const add = vi.fn();
    wrap(<DiscoveryDialog provider={provider} close={vi.fn()} add={add} busy={false} />);
    const input = screen.getByLabelText('手动模型 ID');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'openai/gpt' } });
    const list = await screen.findByRole('listbox', { name: '预设模型' });
    fireEvent.click(within(list).getByText('openai/gpt-5.2'));
    expect(input).toHaveValue('openai/gpt-5.2');
    expect(screen.getByText(/添加时自动填充设置/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '加入待选' }));
    fireEvent.click(screen.getByRole('button', { name: '验证并添加（1）' }));
    expect(add).toHaveBeenCalledWith(['openai/gpt-5.2'], '');
  });
  it.each(['click', 'Enter'])('%s selection survives custom IDs and other exact matches when adding a model', async selection => {
    const other: ModelPreset = { ...preset, id: 'other-model', name: 'Other', aliases: [], contextWindow: 200000, maxOutputTokens: 16000, reasoningEfforts: ['low'] };
    read.mockResolvedValue([preset, other]);
    const { save } = editor();
    const input = screen.getByLabelText('模型 ID');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'gpt-5' } });
    const list = await screen.findByRole('listbox', { name: '预设模型' });
    if (selection === 'click') fireEvent.click(within(list).getByText('gpt-5.2'));
    else fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
    for (const modelId of ['', 'other-model', 'gateway/custom-gpt']) {
      fireEvent.change(input, { target: { value: modelId } });
      expect(screen.getByLabelText('模型名称')).toHaveValue('GPT-5.2');
      expect(screen.getByLabelText('上下文窗口')).toHaveValue('400000');
      expect(screen.getByLabelText('最大输出 Token')).toHaveValue('128000');
      expect(screen.getByText('参考预设：GPT-5.2')).toBeInTheDocument();
    }
    fireEvent.click(screen.getByRole('button', { name: '验证并添加模型' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][2]).toMatchObject({ modelId: 'gateway/custom-gpt', name: 'GPT-5.2', contextWindow: 400000, maxOutputTokens: 128000,
      presetId: 'gpt-5.2', reasoningControl: 'effort', reasoningEfforts: preset.reasoningEfforts, defaultReasoningEffort: '', tools: false });
  });
  it('a saved model keeps the selected preset and manual edits when returning to its original ID until another preset is selected', async () => {
    const other: ModelPreset = { ...preset, id: 'other-model', name: 'Other', aliases: [], contextWindow: 200000, maxOutputTokens: 16000, reasoningEfforts: ['low'] };
    read.mockResolvedValue([preset, other]);
    const { save } = editor({ id: 'stable', modelId: 'saved', name: 'Old', group: '常用', streaming: false, tools: true, contextWindow: 1000, reasoningEfforts: ['low'] });
    const input = screen.getByLabelText('模型 ID');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'gpt-5' } });
    await screen.findByRole('listbox', { name: '预设模型' });
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
    fireEvent.change(input, { target: { value: 'saved' } });
    expect(screen.getByLabelText('模型名称')).toHaveValue('GPT-5.2');
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('400000');
    expect(screen.getByLabelText('最大输出 Token')).toHaveValue('128000');
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '手工名称' } });
    fireEvent.change(screen.getByLabelText('上下文窗口'), { target: { value: '600000' } });
    fireEvent.change(screen.getByLabelText('最大输入 Token'), { target: { value: '300000' } });
    fireEvent.click(screen.getByRole('button', { name: '思考等级 none, low → xhigh' }));
    fireEvent.change(screen.getByLabelText('默认等级'), { target: { value: 'high' } });
    fireEvent.keyDown(screen.getByLabelText('搜索思考等级'), { key: 'Escape' });
    fireEvent.change(input, { target: { value: 'custom-id' } });
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0]).toEqual(['/models/stable', 'PUT', expect.objectContaining({ modelId: 'custom-id', name: '手工名称', group: '常用', streaming: false,
      contextWindow: 600000, maxInputTokens: 300000, maxOutputTokens: 128000, reasoningEfforts: preset.reasoningEfforts, defaultReasoningEffort: 'high', tools: true })]);
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'other-model' } });
    expect(screen.getByLabelText('模型名称')).toHaveValue('手工名称');
    fireEvent.click(within(await screen.findByRole('listbox', { name: '预设模型' })).getByText('other-model'));
    expect(screen.getByLabelText('模型名称')).toHaveValue('Other');
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('200000');
    expect(screen.getByLabelText('最大输入 Token')).toHaveValue('');
    expect(screen.getByLabelText('最大输出 Token')).toHaveValue('16000');
    expect(screen.getByRole('button', { name: '思考等级 low' })).toBeInTheDocument();
    expect(screen.getByText('默认：自动（服务默认）')).toBeInTheDocument();
    expect(screen.getByText('参考预设：Other')).toBeInTheDocument();
  });
  it('late presets after an ID edit preserve manual fields, and arrow keys select another candidate', async () => {
    let resolve!: (value: ModelPreset[]) => void;
    read.mockReturnValue(new Promise<ModelPreset[]>(done => { resolve = done; }));
    editor({ id: 'm', modelId: 'old', name: '旧名', group: '', streaming: true, tools: true, contextWindow: 1000 });
    const input = screen.getByLabelText('模型 ID');
    fireEvent.change(input, { target: { value: 'gpt-5.2' } });
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '手工名称' } });
    fireEvent.change(screen.getByLabelText('上下文窗口'), { target: { value: '600000' } });
    await act(async () => { resolve([preset]); });
    expect(screen.getByLabelText('最大输出 Token')).toHaveValue('');
    expect(screen.getByLabelText('模型名称')).toHaveValue('手工名称');
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('600000');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'gpt-5' } });
    await screen.findByRole('listbox', { name: '预设模型' });
    fireEvent.keyDown(input, { key: 'ArrowDown', keyCode: 40 });
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(input).toHaveValue('openai/gpt-5.2');
    expect(screen.getByLabelText('模型名称')).toHaveValue('GPT-5.2');
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('400000');
  });
  it('changing back to the saved ID preserves settings, and duplicate IDs cannot be saved', async () => {
    const model: ConnectionModel = { id: 'a', modelId: 'saved', name: '原名称', group: '', streaming: false, tools: true, contextWindow: 1234 };
    const save = vi.fn();
    wrap(<ModelEditor provider={{ ...provider, models: [model, { ...model, id: 'b', modelId: 'duplicate' }] }} model={model} save={save} onState={vi.fn()} leave={vi.fn()} done={vi.fn()} busy={false} />);
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'unknown' } });
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'saved' } });
    expect(screen.getByLabelText('上下文窗口')).toHaveValue('1234');
    expect(screen.getByLabelText('模型名称')).toHaveValue('原名称');
    expect(screen.getByRole('switch', { name: '允许工具调用' })).toHaveAttribute('aria-checked', 'true');
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'duplicate' } });
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    expect(screen.getByRole('alert')).toHaveTextContent('此供应商已添加相同模型 ID');
    expect(save).not.toHaveBeenCalled();
  });
  it.each(['new', 'saved'])('all unsaved settings survive ID edits for a %s model without selecting any preset', async kind => {
    const model: ConnectionModel = { id: 'stable', modelId: 'gemini-3.8-flash-high', name: 'Gemini 3.8 Flash', group: '常用', streaming: false, tools: false,
      contextWindow: 100000, maxInputTokens: 80000, maxOutputTokens: 10000, reasoningControl: 'effort', reasoningEfforts: ['low', 'medium', 'high'], defaultReasoningEffort: 'high' };
    const { save } = editor(kind === 'saved' ? model : undefined);
    const input = screen.getByLabelText('模型 ID');
    fireEvent.change(input, { target: { value: model.modelId } });
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '未保存名称' } });
    fireEvent.change(screen.getByLabelText('分组名称'), { target: { value: '未保存分组' } });
    fireEvent.change(screen.getByLabelText('上下文窗口'), { target: { value: '500000' } });
    fireEvent.change(screen.getByLabelText('最大输入 Token'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('最大输出 Token'), { target: { value: '2000' } });
    fireEvent.click(screen.getByRole('switch', { name: '流式输出' }));
    if (kind === 'saved') fireEvent.click(screen.getByRole('switch', { name: '允许工具调用' }));
    fireEvent.click(screen.getByRole('button', { name: /^思考等级 / }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'xhigh' }));
    fireEvent.change(screen.getByLabelText('默认等级'), { target: { value: 'xhigh' } });
    fireEvent.keyDown(screen.getByLabelText('搜索思考等级'), { key: 'Escape' });
    const name = kind === 'saved' ? '保存模型设置' : '验证并添加模型';
    // 保存故意失败，当前草稿继续保留；比较后续每次提交只有 ID 变化。
    fireEvent.click(screen.getByRole('button', { name }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    const baseline = save.mock.calls[0][2];
    for (const [index, id] of ['gemini-3.8-flash-hig', 'gpt-5.2', model.modelId, 'gateway/custom'].entries()) {
      fireEvent.change(input, { target: { value: '' } });
      fireEvent.change(input, { target: { value: id } });
      expect(screen.getByLabelText('模型名称')).toHaveValue('未保存名称');
      expect(screen.getByLabelText('最大输入 Token')).toHaveValue('');
      expect(screen.getByText('默认：xhigh')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name }));
      await waitFor(() => expect(save).toHaveBeenCalledTimes(index + 2));
      expect(save.mock.calls[index + 1][2]).toEqual({ ...baseline, modelId: id });
    }
  });
  it.each(['pending', 'failed'])('editing a saved ID with %s presets retains every saved value in the payload', async state => {
    read.mockImplementation(() => state === 'pending' ? new Promise(() => {}) : Promise.reject(new Error('offline')));
    const model: ConnectionModel = { id: 'stable', modelId: 'saved', name: 'Saved', group: '常用', tools: true, streaming: false,
      contextWindow: 123000, maxInputTokens: 120000, maxOutputTokens: 3000, reasoningControl: 'effort', reasoningEfforts: ['high'], defaultReasoningEffort: 'high' };
    const { save } = editor(model);
    if (state === 'failed') await screen.findByText(/预设资料加载失败/);
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'gpt-5.2' } });
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    const { id: _id, ...fields } = model;
    expect(save.mock.calls[0][2]).toEqual({ ...fields, modelId: 'gpt-5.2', thinkingBudgetTokens: null });
  });
  it('editing the ID preserves a saved or unsaved thinking budget and control mode', async () => {
    const save = vi.fn().mockResolvedValue(false);
    const model: ConnectionModel = { id: 'stable', modelId: 'budget-model', name: 'Budget', group: '', streaming: true, tools: true,
      contextWindow: 100000, maxOutputTokens: 10000, reasoningControl: 'budget', reasoningEfforts: [], thinkingBudgetTokens: 2048 };
    wrap(<ModelEditor provider={{ ...provider, apiFormat: 'anthropic' }} model={model} save={save} onState={vi.fn()} leave={vi.fn()} done={vi.fn()} busy={false} />);
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'custom-budget' } });
    expect(screen.getByLabelText('控制方式')).toHaveValue('budget');
    expect(screen.getByLabelText('思考 Token 预算')).toHaveValue('2048');
    fireEvent.change(screen.getByLabelText('思考 Token 预算'), { target: { value: '4096' } });
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'budget-model' } });
    expect(screen.getByLabelText('思考 Token 预算')).toHaveValue('4096');
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][2]).toMatchObject({ modelId: 'budget-model', reasoningControl: 'budget', thinkingBudgetTokens: 4096, tools: true });
  });
});
