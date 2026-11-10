import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiToolSettingsPanel } from './AiToolSettingsPanel';
import { deletionTools } from '../lib/aiToolSettings';
import type { AiToolSettingsView } from '../lib/aiToolSettings';

const { getSettings, saveSettings } = vi.hoisted(() => ({ getSettings: vi.fn(), saveSettings: vi.fn() }));
vi.mock('../lib/aiToolSettings', async original => ({ ...await original<typeof import('../lib/aiToolSettings')>(),
  getToolSettings: getSettings, saveToolSettings: saveSettings }));
let view: AiToolSettingsView;
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['ai-runtime-settings'], { untouched: true });
  render(<QueryClientProvider client={client}><AiToolSettingsPanel /></QueryClientProvider>);
  return client;
}
beforeEach(() => {
  vi.clearAllMocks();
  view = { revision: 0, tools: deletionTools.map(tool => ({ name: tool.name, group: 'delete', enabled: false, defaultEnabled: false })) };
  getSettings.mockImplementation(async () => view);
  saveSettings.mockImplementation(async (revision, enabled) => {
    view = { revision: revision + 1, tools: view.tools.map(tool => ({ ...tool, enabled: enabled[tool.name] ?? tool.enabled })) };
    return view;
  });
});

it('四项默认关闭，单项即存，恢复默认只关闭删除开关', async () => {
  const client = mount();
  await screen.findByRole('switch', { name: '删除记忆' });
  expect(screen.getAllByRole('switch')).toHaveLength(4);
  for (const control of screen.getAllByRole('switch')) expect(control).toHaveAttribute('aria-checked', 'false');
  expect(screen.getByRole('button', { name: '恢复默认' })).toBeDisabled();
  fireEvent.click(screen.getByRole('switch', { name: '删除学习资料' }));
  await waitFor(() => expect(screen.getByRole('switch', { name: '删除学习资料' })).toHaveAttribute('aria-checked', 'true'));
  expect(saveSettings).toHaveBeenCalledWith(0, { delete_material: true });
  fireEvent.click(screen.getByRole('button', { name: '恢复默认' }));
  await waitFor(() => expect(saveSettings).toHaveBeenLastCalledWith(1, {
    delete_memory: false, delete_session: false, delete_material: false, remove_wrong_question: false
  }));
  await waitFor(() => expect(screen.getByRole('button', { name: '恢复默认' })).toBeDisabled());
  expect(client.getQueryData(['ai-runtime-settings'])).toEqual({ untouched: true });
});

it('保存中禁用开关，失败不显示已开启，冲突重新载入后可以再选', async () => {
  let reject!: (reason: Error) => void;
  saveSettings.mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail; }));
  mount();
  fireEvent.click(await screen.findByRole('switch', { name: '删除会话' }));
  expect(screen.getByRole('switch', { name: '删除会话' })).toHaveAttribute('aria-checked', 'false');
  for (const control of screen.getAllByRole('switch')) expect(control).toBeDisabled();
  view = { ...view, revision: 5 };
  reject(new Error('设置已在其他窗口修改，请重新载入后保存'));
  expect(await screen.findByRole('alert')).toHaveTextContent('其他窗口');
  await waitFor(() => expect(screen.getByRole('switch', { name: '删除会话' })).not.toBeDisabled());
  fireEvent.click(screen.getByRole('switch', { name: '删除会话' }));
  await waitFor(() => expect(saveSettings).toHaveBeenLastCalledWith(5, { delete_session: true }));
});

it('读取失败不假装成功加载默认项，允许重试', async () => {
  getSettings.mockRejectedValueOnce(new Error('offline'));
  mount();
  expect(await screen.findByRole('alert')).toHaveTextContent('读取失败');
  expect(screen.queryByRole('switch')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '重试' }));
  expect(await screen.findByRole('switch', { name: '删除记忆' })).toHaveAttribute('aria-checked', 'false');
});
