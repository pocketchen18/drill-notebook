import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiRuntimeSettingsPanel } from './AiRuntimeSettingsPanel';
import { validateRuntimeSettings } from '../lib/aiRuntimeSettings';

const { getSettings, saveSettings } = vi.hoisted(() => ({ getSettings: vi.fn(), saveSettings: vi.fn() }));
vi.mock('../lib/aiRuntimeSettings', async original => ({ ...await original<typeof import('../lib/aiRuntimeSettings')>(),
  getRuntimeSettings: getSettings, saveRuntimeSettings: saveSettings }));
const defaults = { maxToolStages: 50, maxToolExecutions: 100, turnTimeoutMinutes: 30, requestTimeoutMinutes: 15,
  maxOutputTokens: 128000, requestOutputTokens: 16384, finalOutputTokens: 8192, repeatedToolLimit: 3 };
const view = { revision: 4, values: { ...defaults, maxToolStages: 70 }, defaults };
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><AiRuntimeSettingsPanel /></QueryClientProvider>);
  return client;
}
beforeEach(() => { vi.clearAllMocks(); getSettings.mockResolvedValue(view);
  saveSettings.mockImplementation(async (revision, values) => ({ revision: revision + 1, values, defaults })); });

describe('全局 AI 调用预算', () => {
  it('切回持续等待后隐藏的无效次数不会锁住保存', async () => {
    mount();
    const mode = await screen.findByLabelText('断网等待');
    fireEvent.change(mode, { target: { value: 'limited' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: '断网重试次数' }), { target: { value: '' } });
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    fireEvent.change(mode, { target: { value: 'persistent' } });
    expect(screen.queryByRole('spinbutton', { name: '断网重试次数' })).toBeNull();
    expect(screen.getByRole('button', { name: '保存' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(saveSettings).toHaveBeenCalledWith(4, { ...view.values,
      retry: { httpRetries: 4, streamRetries: 5, networkPersistent: true, networkRetries: 5 } }));
  });
  it('重试默认与 Codex 基线一致，有限上限可禁用且校验后保存', async () => {
    mount();
    const request = await screen.findByRole('spinbutton', { name: '请求重试次数' });
    expect(request).toHaveValue(4); expect(screen.getByRole('spinbutton', { name: '回答重连次数' })).toHaveValue(5);
    expect(screen.queryByRole('spinbutton', { name: '断网重试次数' })).toBeNull();
    fireEvent.change(request, { target: { value: '0' } });
    fireEvent.change(screen.getByLabelText('断网等待'), { target: { value: 'limited' } });
    const network = screen.getByRole('spinbutton', { name: '断网重试次数' });
    fireEvent.change(network, { target: { value: '1.5' } });
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    fireEvent.change(network, { target: { value: '8' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(saveSettings).toHaveBeenCalledWith(4, { ...view.values,
      retry: { httpRetries: 0, streamRetries: 5, networkPersistent: false, networkRetries: 8 } }));
  });
  it('修改独立全局设置，恢复默认只改草稿，保存后才生效', async () => {
    mount();
    const input = await screen.findByRole('spinbutton', { name: '工具阶段上限' });
    expect(input).toHaveValue(70);
    fireEvent.change(input, { target: { value: '90' } });
    expect(saveSettings).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '恢复默认' }));
    expect(input).toHaveValue(50);
    expect(saveSettings).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(saveSettings).toHaveBeenCalledWith(4, defaults));
    await waitFor(() => expect(screen.getByRole('button', { name: '保存' })).toBeDisabled());
  });
  it('取消回到已保存值，失败保留草稿与原版本，重试后保存', async () => {
    saveSettings.mockRejectedValueOnce(new Error('暂时无法保存'));
    mount();
    const input = await screen.findByRole('spinbutton', { name: '工具阶段上限' });
    fireEvent.change(input, { target: { value: '99' } });
    fireEvent.click(screen.getByRole('button', { name: '取消修改' }));
    expect(input).toHaveValue(70);
    fireEvent.change(input, { target: { value: '80' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法保存');
    expect(input).toHaveValue(80);
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(saveSettings).toHaveBeenCalledTimes(2));
    expect(saveSettings.mock.calls[1]).toEqual([4, { ...view.values, maxToolStages: 80 }]);
  });
  it('空值、小数、时间关系和预留关系阻止保存', async () => {
    mount();
    const input = await screen.findByRole('spinbutton', { name: '工具阶段上限' });
    fireEvent.change(input, { target: { value: '' } });
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    expect(validateRuntimeSettings({ ...defaults, maxToolStages: 2.5, requestTimeoutMinutes: 31, finalOutputTokens: 128000 }))
      .toMatchObject({ maxToolStages: expect.any(String), requestTimeoutMinutes: expect.any(String), finalOutputTokens: expect.any(String) });
  });
});
