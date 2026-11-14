import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, expect, it, vi } from 'vitest';
import { WebSearchSettingsPanel } from './WebSearchSettingsPanel';
import { PageContextProvider } from './PageContextProvider';
import { capturePageSnapshot } from '../lib/pageSnapshot';
import { useUiStore } from '../stores/uiStore';
import { ApiError } from '../lib/api';
import type { WebSearchSettingsView, WebSettingsEdit } from '../lib/webSearchSettings';

const { getWeb, saveWeb, testWeb } = vi.hoisted(() => ({ getWeb: vi.fn(), saveWeb: vi.fn(), testWeb: vi.fn() }));
vi.mock('../lib/webSearchSettings', async original => ({ ...await original<typeof import('../lib/webSearchSettings')>(),
  getWebSettings: getWeb, saveWebSettings: saveWeb, testWebSettings: testWeb }));
let view: WebSearchSettingsView;
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><PageContextProvider context={{ kind: 'settings', title: '设置 · 工具',
    route: '/settings?tab=tools', markdown: '# 设置 · 工具' }}><WebSearchSettingsPanel /></PageContextProvider></QueryClientProvider>);
  return client;
}
async function choose(label: '搜索服务' | '网页读取服务', name: string) {
  fireEvent.click(await screen.findByRole('combobox', { name: label }));
  fireEvent.click(await screen.findByRole('option', { name }));
}
beforeEach(() => {
  vi.clearAllMocks(); useUiStore.getState().clearPageContext();
  view = { revision: 2, searchProvider: 'auto', fetchProvider: 'auto', preferNative: false,
    providers: { tavily: { hasKey: false }, tinyfish: { hasKey: false } } };
  getWeb.mockImplementation(async () => view);
  saveWeb.mockImplementation(async (revision: number, changes: WebSettingsEdit) => {
    view = { ...view, revision: revision + 1, searchProvider: changes.searchProvider ?? view.searchProvider,
      fetchProvider: changes.fetchProvider ?? view.fetchProvider, preferNative: changes.preferNative ?? view.preferNative,
      providers: Object.fromEntries(Object.entries(view.providers).map(([name, value]) => {
        const key = changes.keys?.[name as keyof typeof view.providers];
        return [name, { hasKey: key?.clear ? false : key?.apiKey ? true : value.hasKey }];
      })) as WebSearchSettingsView['providers'] };
    return view;
  });
  testWeb.mockResolvedValue({ checks: [{ capability: 'search', status: 'OK', count: 2, webProvider: 'tavily', webRoute: [] },
    { capability: 'fetch', status: 'QUOTA_EXHAUSTED', count: 0, webRoute: [{ provider: 'tavily', status: 'QUOTA_EXHAUSTED', httpStatus: 429 }] }] });
});

it('共用服务只有一个密钥框；原生优先立即保存，取消供应商草稿不撤销已保存开关', async () => {
  mount();
  await choose('搜索服务', 'Tavily'); await choose('网页读取服务', 'Tavily');
  expect(screen.getAllByLabelText('Tavily API Key')).toHaveLength(1);
  const input = screen.getByLabelText('Tavily API Key');
  fireEvent.change(input, { target: { value: 'fixture-key-draft' } });
  expect(saveWeb).not.toHaveBeenCalled();
  const guard = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(guard); expect(guard.defaultPrevented).toBe(true);
  const snapshot = capturePageSnapshot(useUiStore.getState().pageContext);
  expect(snapshot.markdown).toContain('搜索服务：内置自动');
  expect(snapshot.markdown).not.toMatch(/fixture-key-draft|API Key|hasKey|Tavily/);
  fireEvent.click(screen.getByRole('switch', { name: '优先使用模型内置 Web 工具' }));
  await waitFor(() => expect(saveWeb).toHaveBeenCalledWith(2, { preferNative: true }));
  await waitFor(() => expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true'));
  expect(input).toHaveValue('fixture-key-draft');
  expect(screen.getByRole('combobox', { name: '搜索服务' })).toHaveTextContent('Tavily');
  fireEvent.click(screen.getByRole('button', { name: '取消' }));
  expect(screen.queryByLabelText('Tavily API Key')).not.toBeInTheDocument();
  expect(screen.getByRole('combobox', { name: '搜索服务' })).toHaveTextContent('内置自动');
  expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true');
  expect(screen.queryByRole('button', { name: '保存' })).not.toBeInTheDocument();
  const clean = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(clean); expect(clean.defaultPrevented).toBe(false);
});

it('两种服务分别保存，共用已有密钥留空保留，保存后清空明文草稿', async () => {
  view.providers.tavily.hasKey = true;
  const client = mount();
  await choose('搜索服务', 'Tavily'); await choose('网页读取服务', 'TinyFish');
  expect(screen.getByLabelText('Tavily API Key')).toHaveAttribute('type', 'password');
  fireEvent.change(screen.getByLabelText('TinyFish API Key'), { target: { value: 'fixture-tinyfish-key' } });
  fireEvent.click(screen.getByRole('button', { name: '保存' }));
  await waitFor(() => expect(saveWeb).toHaveBeenCalledWith(2, { searchProvider: 'tavily', fetchProvider: 'tinyfish',
    keys: { tinyfish: { apiKey: 'fixture-tinyfish-key' } } }));
  await waitFor(() => expect(screen.queryByRole('button', { name: '保存' })).not.toBeInTheDocument());
  expect(screen.getByLabelText('TinyFish API Key')).toHaveValue('');
  expect(screen.getByLabelText('Tavily API Key')).toHaveValue('');
  expect(JSON.stringify(client.getQueryData(['ai-web-settings']))).not.toContain('fixture-tinyfish-key');
  expect(capturePageSnapshot(useUiStore.getState().pageContext).markdown).toContain('网页读取服务：TinyFish');
  expect(capturePageSnapshot(useUiStore.getState().pageContext).markdown).not.toMatch(/fixture-tinyfish-key|API Key|hasKey/);
});

it('删除已保存密钥必须明确操作，并在使用它的服务切回自动后才能保存', async () => {
  view.searchProvider = 'tavily'; view.providers.tavily.hasKey = true;
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '删除已保存密钥' }));
  fireEvent.click(screen.getByRole('button', { name: '保存' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('删除密钥前');
  expect(saveWeb).not.toHaveBeenCalled();
  await choose('搜索服务', '内置自动（免配置）');
  fireEvent.click(screen.getByRole('button', { name: '保存' }));
  await waitFor(() => expect(saveWeb).toHaveBeenCalledWith(2, { searchProvider: 'auto', fetchProvider: 'auto', keys: { tavily: { clear: true } } }));
  await waitFor(() => expect(screen.queryByLabelText('Tavily API Key')).not.toBeInTheDocument());
});

it('测试连接使用草稿但不保存，显示真实服务失败，不回填密钥', async () => {
  mount(); await choose('搜索服务', 'Tavily');
  fireEvent.change(screen.getByLabelText('Tavily API Key'), { target: { value: 'fixture-test-key' } });
  fireEvent.click(screen.getByRole('button', { name: '测试连接' }));
  await waitFor(() => expect(testWeb).toHaveBeenCalledWith({ searchProvider: 'tavily', fetchProvider: 'auto', keys: { tavily: { apiKey: 'fixture-test-key' } } }));
  await screen.findByText(/搜索：Tavily/);
  expect(screen.getByRole('status')).toHaveTextContent(/额度/);
  expect(screen.getByLabelText('Tavily API Key')).toHaveValue('fixture-test-key');
  expect(saveWeb).not.toHaveBeenCalled();
  expect(capturePageSnapshot(useUiStore.getState().pageContext).markdown).not.toContain('fixture-test-key');
});

it('保存冲突不丢失草稿，读取最新版本后显式再次保存', async () => {
  const client = mount(); await choose('搜索服务', 'TinyFish');
  fireEvent.change(screen.getByLabelText('TinyFish API Key'), { target: { value: 'fixture-conflict-key' } });
  view = { ...view, revision: 7, preferNative: true };
  saveWeb.mockRejectedValueOnce(new ApiError('AI_SETTINGS_CONFLICT', '联网设置已在其他窗口修改', 409));
  fireEvent.click(screen.getByRole('button', { name: '保存' }));
  await screen.findByRole('alert');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存' })).not.toBeDisabled());
  expect(client.getQueryData<WebSearchSettingsView>(['ai-web-settings'])?.revision).toBe(7);
  expect(screen.getByLabelText('TinyFish API Key')).toHaveValue('fixture-conflict-key');
  expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true');
  fireEvent.click(screen.getByRole('button', { name: '保存' }));
  await waitFor(() => expect(saveWeb).toHaveBeenLastCalledWith(7, { searchProvider: 'tinyfish', fetchProvider: 'auto',
    keys: { tinyfish: { apiKey: 'fixture-conflict-key' } } }));
  await waitFor(() => expect(screen.getByLabelText('TinyFish API Key')).toHaveValue(''));
});

it('原生开关保存完成后，较早的 GET 不回滚确认状态', async () => {
  const old = structuredClone(view);
  let resolve!: (value: WebSearchSettingsView) => void;
  getWeb.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['ai-web-settings'], view);
  render(<QueryClientProvider client={client}><WebSearchSettingsPanel /></QueryClientProvider>);
  const control = await screen.findByRole('switch');
  await waitFor(() => expect(control).not.toBeDisabled());
  fireEvent.click(control);
  await waitFor(() => expect(control).toHaveAttribute('aria-checked', 'true'));
  await act(async () => resolve(old));
  await waitFor(() => expect(client.isFetching()).toBe(0));
  expect(control).toHaveAttribute('aria-checked', 'true');
  expect(client.getQueryData<WebSearchSettingsView>(['ai-web-settings'])?.revision).toBe(3);
});
