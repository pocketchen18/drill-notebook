import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, expect, it, vi } from 'vitest';
import { WebSearchButton } from './WebSearchButton';
import { toolGroups } from '../../lib/aiToolSettings';
import type { AiToolSettingsView } from '../../lib/aiToolSettings';
import { ApiError } from '../../lib/api';

const { getSettings, saveSettings, getWeb, notify } = vi.hoisted(() => ({ getSettings: vi.fn(), saveSettings: vi.fn(), getWeb: vi.fn(), notify: vi.fn() }));
vi.mock('@arco-design/web-react', async original => {
  const arco = await original<typeof import('@arco-design/web-react')>();
  return { ...arco, Message: { ...arco.Message, error: notify } };
});
vi.mock('../../lib/aiToolSettings', async original => ({ ...await original<typeof import('../../lib/aiToolSettings')>(),
  getToolSettings: getSettings, saveToolSettings: saveSettings }));
vi.mock('../../lib/webSearchSettings', async original => ({ ...await original<typeof import('../../lib/webSearchSettings')>(), getWebSettings: getWeb }));
let view: AiToolSettingsView;
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['ai-tool-settings'], view);
  render(<QueryClientProvider client={client}><WebSearchButton /></QueryClientProvider>);
  return client;
}
beforeEach(() => {
  vi.clearAllMocks();
  view = { revision: 3, tools: toolGroups.flatMap(group => group.tools.map(name => ({ name, group: group.id,
    enabled: group.id !== 'delete', defaultEnabled: group.id !== 'delete' }))) };
  getSettings.mockImplementation(async () => view);
  getWeb.mockResolvedValue({ revision: 0, searchProvider: 'auto', fetchProvider: 'auto', preferNative: false,
    providers: { tavily: { hasKey: false }, tinyfish: { hasKey: false } } });
  saveSettings.mockImplementation(async (revision, enabled) => {
    view = { revision: revision + 1, tools: view.tools.map(tool => ({ ...tool, enabled: enabled[tool.name] ?? tool.enabled })) };
    return view;
  });
});

it.each([[true, true, false], [false, false, true], [true, false, true], [false, true, true]])(
  '搜索 %s、读取 %s 时一次保存两项权限为 %s，保留其它工具', async (search, fetch, next) => {
    view.tools = view.tools.map(tool => ({ ...tool, enabled: tool.name === 'search_web' ? search : tool.name === 'fetch_web' ? fetch : tool.enabled }));
    const client = mount();
    const button = await screen.findByRole('button', { name: /^联网/ });
    expect(button).toHaveAttribute('aria-pressed', search !== fetch ? 'mixed' : String(search));
    fireEvent.click(button);
    await waitFor(() => expect(saveSettings).toHaveBeenCalledWith(3, { search_web: next, fetch_web: next }));
    await waitFor(() => expect(screen.getByRole('button', { name: /^联网/ })).toHaveAttribute('aria-pressed', String(next)));
    expect(client.getQueryData<AiToolSettingsView>(['ai-tool-settings'])?.tools.find(tool => tool.name === 'delete_session')?.enabled).toBe(false);
  }
);

it('保存中阻止重复点击，冲突保留当前状态并读取新 revision 后允许重试', async () => {
  view.tools = view.tools.map(tool => ({ ...tool, enabled: tool.group === 'web' ? false : tool.enabled }));
  let reject!: (reason: Error) => void;
  saveSettings.mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail; }));
  const client = mount();
  const button = await screen.findByRole('button', { name: /^联网已关闭/ });
  await waitFor(() => expect(client.isFetching()).toBe(0));
  fireEvent.click(button); fireEvent.click(button);
  expect(screen.getByRole('button')).toBeDisabled(); expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'false');
  expect(saveSettings).toHaveBeenCalledTimes(1);
  view = { ...view, revision: 8 };
  await act(async () => reject(new ApiError('AI_SETTINGS_CONFLICT', '设置已在其他窗口修改', 409)));
  await waitFor(() => expect(notify).toHaveBeenCalledWith('设置已在其他窗口修改'));
  await waitFor(() => expect(screen.getByRole('button', { name: /^联网/ })).not.toBeDisabled());
  expect(screen.getByRole('button', { name: /^联网/ })).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(screen.getByRole('button', { name: /^联网/ }));
  await waitFor(() => expect(saveSettings).toHaveBeenLastCalledWith(8, { search_web: true, fetch_web: true }));
  await waitFor(() => expect(screen.getByRole('button', { name: /^联网/ })).toHaveAttribute('aria-pressed', 'true'));
});

it('保存前发起的旧 GET 完成后不覆盖新状态', async () => {
  const old = structuredClone(view);
  let resolve!: (value: AiToolSettingsView) => void;
  getSettings.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
  const client = mount();
  await waitFor(() => expect(getSettings).toHaveBeenCalledTimes(1));
  const button = screen.getByRole('button', { name: /^联网已开启/ });
  fireEvent.click(button);
  await waitFor(() => expect(screen.getByRole('button', { name: /^联网/ })).toHaveAttribute('aria-pressed', 'false'));
  await act(async () => resolve(old));
  await waitFor(() => expect(client.isFetching()).toBe(0));
  expect(client.getQueryData<AiToolSettingsView>(['ai-tool-settings'])?.revision).toBe(4);
  expect(screen.getByRole('button', { name: /^联网/ })).toHaveAttribute('aria-pressed', 'false');
});

it('无法读取配置时不冒充默认开启', async () => {
  getSettings.mockRejectedValue(new Error('offline'));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><WebSearchButton /></QueryClientProvider>);
  const button = await screen.findByRole('button', { name: /暂不可读取/ });
  expect(button).toBeDisabled(); expect(button).toHaveAttribute('aria-pressed', 'false');
  expect(saveSettings).not.toHaveBeenCalled();
});

it('旧接口返回缺少工具列表时仍可显示禁用入口，不提交权限', async () => {
  getSettings.mockResolvedValue({});
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><WebSearchButton /></QueryClientProvider>);
  const button = await screen.findByRole('button', { name: /暂不可读取/ });
  expect(button).toBeDisabled(); expect(button).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(button);
  expect(saveSettings).not.toHaveBeenCalled();
});
