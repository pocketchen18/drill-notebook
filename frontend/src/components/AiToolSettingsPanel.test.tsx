import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiToolSettingsPanel } from './AiToolSettingsPanel';
import { toolGroups } from '../lib/aiToolSettings';
import type { AiToolSettingsView } from '../lib/aiToolSettings';
import { TOOL_NAMES } from '../lib/toolActivity';

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
  view = { revision: 0, tools: toolGroups.flatMap(group => group.tools.map(name => ({
    name, group: group.id, enabled: group.id !== 'delete', defaultEnabled: group.id !== 'delete'
  }))) };
  getSettings.mockImplementation(async () => view);
  saveSettings.mockImplementation(async (revision, enabled) => {
    view = { revision: revision + 1, tools: view.tools.map(tool => ({ ...tool, enabled: enabled[tool.name] ?? tool.enabled })) };
    return view;
  });
});

it('全部内置工具分组展示，默认开启 16 项、关闭 4 项删除，恢复默认使用各项默认值', async () => {
  const client = mount();
  await screen.findByRole('switch', { name: '删除记忆' });
  expect(screen.getAllByRole('switch')).toHaveLength(20);
  expect(new Set(view.tools.map(tool => tool.name))).toEqual(new Set(Object.keys(TOOL_NAMES).filter(name => name !== 'unknown_tool')));
  expect(screen.getAllByRole('switch', { checked: true })).toHaveLength(16);
  for (const control of within(screen.getByRole('region', { name: '删除' })).getAllByRole('switch'))
    expect(control).toHaveAttribute('aria-checked', 'false');
  expect(within(screen.getByRole('region', { name: '学习资料' })).getAllByRole('switch')).toHaveLength(8);
  expect(within(screen.getByRole('region', { name: '记忆' })).getAllByRole('switch')).toHaveLength(4);
  expect(within(screen.getByRole('region', { name: '会话' })).getAllByRole('switch')).toHaveLength(4);
  expect(screen.getByRole('button', { name: '恢复默认' })).toBeDisabled();
  fireEvent.click(screen.getByRole('switch', { name: '删除学习资料' }));
  await waitFor(() => expect(screen.getByRole('switch', { name: '删除学习资料' })).toHaveAttribute('aria-checked', 'true'));
  expect(saveSettings).toHaveBeenCalledWith(0, { delete_material: true });
  fireEvent.click(screen.getByRole('switch', { name: '读取笔记' }));
  await waitFor(() => expect(screen.getByRole('switch', { name: '读取笔记' })).toHaveAttribute('aria-checked', 'false'));
  expect(saveSettings).toHaveBeenLastCalledWith(1, { read_notebook: false });
  expect(screen.getByRole('switch', { name: '删除学习资料' })).toHaveAttribute('aria-checked', 'true');
  fireEvent.click(screen.getByRole('button', { name: '恢复默认' }));
  await waitFor(() => expect(saveSettings).toHaveBeenLastCalledWith(2,
    Object.fromEntries(view.tools.map(tool => [tool.name, tool.defaultEnabled]))));
  await waitFor(() => expect(screen.getByRole('button', { name: '恢复默认' })).toBeDisabled());
  expect(screen.getAllByRole('switch', { checked: true })).toHaveLength(16);
  expect(client.getQueryData(['ai-runtime-settings'])).toEqual({ untouched: true });
});

it('重新读取保留普通工具的关闭与删除工具的开启，不以默认值覆盖', async () => {
  view = { revision: 8, tools: view.tools.map(tool => ({ ...tool,
    enabled: tool.name === 'delete_session' ? true : tool.name === 'send_message' ? false : tool.enabled
  })) };
  mount();
  expect(await screen.findByRole('switch', { name: '发送消息' })).toHaveAttribute('aria-checked', 'false');
  expect(screen.getByRole('switch', { name: '删除会话' })).toHaveAttribute('aria-checked', 'true');
  expect(screen.getByRole('switch', { name: '读取会话' })).toHaveAttribute('aria-checked', 'true');
  expect(saveSettings).not.toHaveBeenCalled();
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
