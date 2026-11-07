import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiConnectionsPanel, type SettingsLeaveGuard } from './AiConnectionsPanel';
import { type Connections } from './types';

const { read, request } = vi.hoisted(() => ({ read: vi.fn(), request: vi.fn() }));
vi.mock('../../lib/api', () => ({ get: (...args: unknown[]) => read(...args), apiRequest: (...args: unknown[]) => request(...args) }));
const fixture = (): Connections => ({ revision: 1, chatModelId: 'a', importModelId: null, providers: [
  { id: 'p', name: '学习网关', endpoint: 'https://example.invalid/v1', apiFormat: 'chat_completions', authMode: 'none', hasKey: false, needsPassword: false,
    models: [{ id: 'a', modelId: 'chat-a', name: '通用对话', group: '常用', streaming: true, tools: false }, { id: 'b', modelId: 'chat-b', name: '深度分析', group: '', streaming: false, tools: true }] },
  { id: 'q', name: '备用网关', endpoint: 'https://backup.invalid/v1', apiFormat: 'chat_completions', authMode: 'none', hasKey: false, needsPassword: false, models: [] }
] });
function mount(registerGuard?: (guard: SettingsLeaveGuard | null) => void,
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })) {
  return render(<QueryClientProvider client={client}><AiConnectionsPanel registerGuard={registerGuard} /></QueryClientProvider>);
}
describe('AI connection workflow', () => {
  afterEach(() => vi.unstubAllGlobals());
  beforeEach(() => { vi.clearAllMocks(); read.mockResolvedValue(fixture()); request.mockResolvedValue(fixture()); });
  it('saving a supplier automatically fetches once and selection verifies before adding', async () => {
    let state = fixture();
    const added = { ...state.providers[1], id: 'new', name: '新连接', endpoint: 'https://new.invalid/v1' };
    read.mockImplementation((path: string) => Promise.resolve(path.endsWith('/presets') ? [] : state));
    request.mockImplementation(async (path: string) => {
      if (path.endsWith('/discover')) return ['new-model'];
      if (path.endsWith('/verify-add')) { state = { ...state, revision: 3, providers: state.providers.map(p => p.id === 'new' ? { ...p, models: [{ id: 'new-model-id', modelId: 'new-model', name: '', group: '', tools: true, streaming: true }] } : p) }; return { added: true, connections: state }; }
      state = { ...state, revision: 2, providers: [...state.providers, added] }; return state;
    });
    mount(); fireEvent.click(await screen.findByRole('button', { name: '添加供应商' }));
    fireEvent.change(screen.getByLabelText('供应商名称'), { target: { value: '新连接' } });
    fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://new.invalid/v1' } });
    fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'test-only' } });
    fireEvent.click(screen.getByRole('button', { name: '保存连接' }));
    const option = await screen.findByRole('checkbox', { name: /new-model/ });
    expect(request.mock.calls.filter(call => call[0].endsWith('/discover'))).toHaveLength(1);
    expect(request.mock.calls.some(call => call[0].endsWith('/verify-add'))).toBe(false);
    fireEvent.click(option); fireEvent.click(screen.getByRole('button', { name: '验证并添加（1）' }));
    await screen.findByText('已添加');
    const verification = request.mock.calls.find(call => call[0].endsWith('/verify-add'))!;
    expect(JSON.parse(verification[1].body)).toMatchObject({ revision: 2, model: { modelId: 'new-model' } });
    fireEvent.click(screen.getByRole('button', { name: '完成' }));
    await screen.findByRole('button', { name: '设置：new-model' });
    expect(state.chatModelId).toBe('a');
  });
  it('save-and-leave on a new model waits for verification then continues navigation', async () => {
    let state = fixture();
    read.mockImplementation((path: string) => Promise.resolve(path.endsWith('/presets') ? [] : state));
    request.mockImplementation(async () => {
      state = { ...state, revision: 2, providers: state.providers.map(p => p.id === 'p' ? { ...p, models: [...p.models, { id: 'fresh', modelId: 'fresh', name: '', group: '', tools: false, streaming: true }] } : p) };
      return { added: true, connections: state };
    });
    mount(); fireEvent.click(await screen.findByRole('button', { name: '添加模型' }));
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'fresh' } });
    fireEvent.click(screen.getByRole('button', { name: /备用网关/ }));
    fireEvent.click(await screen.findByRole('button', { name: '保存并继续' }));
    await screen.findByText('已添加');
    expect(screen.queryByText('有未保存的修改')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '完成' }));
    await screen.findByRole('heading', { name: '备用网关' });
    expect(request).toHaveBeenCalledOnce();
  });
  it('browsing and saving model settings does not apply; switches are independent', async () => {
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '设置：深度分析' }));
    expect(screen.getByLabelText('模型 ID')).toHaveAttribute('readonly');
    expect(screen.getByRole('switch', { name: '流式输出' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('switch', { name: '允许工具调用' })).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(screen.getByRole('switch', { name: '流式输出' }));
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '修改名称' } });
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    expect(request.mock.calls[0][0]).toBe('/api/ai/connections/models/b');
    expect(JSON.parse(request.mock.calls[0][1].body)).toMatchObject({ revision: 1, tools: true, streaming: true, name: '修改名称' });
    expect(screen.getByText('学习网关 / 通用对话')).toBeInTheDocument();
  });
  it('apply waits for server success and a failed switch keeps current binding', async () => {
    request.mockRejectedValue(new Error('保存失败'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    client.setQueryData(['ai-memory-settings'], { enabled: true, revision: 1 });
    mount(undefined, client);
    fireEvent.click(await screen.findByRole('button', { name: '应用' }));
    await screen.findByText('保存失败');
    expect(screen.getByText('学习网关 / 通用对话')).toBeInTheDocument();
    expect(client.getQueryState(['ai-memory-settings'])?.isInvalidated).toBe(false);
    expect(JSON.parse(request.mock.calls[0][1].body)).toMatchObject({ purpose: 'chat', modelId: 'b' });
    request.mockResolvedValue({ ...fixture(), revision: 2, chatModelId: 'b' });
    fireEvent.click(screen.getByRole('button', { name: '应用' }));
    await screen.findByText('学习网关 / 深度分析');
    expect(client.getQueryState(['ai-memory-settings'])?.isInvalidated).toBe(true);
  });
  it('dirty supplier switch supports keep editing, failed save and discard', async () => {
    request.mockRejectedValue(new Error('配置已变更'));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '设置：深度分析' }));
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '我的草稿' } });
    fireEvent.click(screen.getByRole('button', { name: /备用网关/ }));
    fireEvent.click(await screen.findByRole('button', { name: '继续编辑' }));
    expect(screen.getByDisplayValue('我的草稿')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /备用网关/ }));
    fireEvent.click(await screen.findByRole('button', { name: '保存并继续' }));
    await waitFor(() => expect(request).toHaveBeenCalled());
    expect(screen.getByDisplayValue('我的草稿')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '放弃修改' }));
    await screen.findByRole('heading', { name: '备用网关' });
  });
  it('invalid token values stay in editor and never reach backend', async () => {
    mount(); fireEvent.click(await screen.findByRole('button', { name: '设置：深度分析' }));
    fireEvent.click(screen.getByText('上下文与输出上限（可选）'));
    fireEvent.change(screen.getByLabelText('上下文窗口'), { target: { value: '100' } });
    fireEvent.change(screen.getByLabelText('最大输出 Token'), { target: { value: '101' } });
    fireEvent.click(screen.getByRole('button', { name: '保存模型设置' }));
    await screen.findByText('输入与输出上限分别不得大于上下文窗口');
    expect(request).not.toHaveBeenCalled();
  });
  it('Escape preserves dirty model settings until discard is selected', async () => {
    mount(); fireEvent.click(await screen.findByRole('button', { name: '设置：深度分析' }));
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '未保存名称' } });
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(await screen.findByRole('button', { name: '继续编辑' }));
    expect(screen.getByDisplayValue('未保存名称')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(await screen.findByRole('button', { name: '放弃修改' }));
    await screen.findByRole('button', { name: '设置：深度分析' });
    expect(request).not.toHaveBeenCalled();
  });
  it('load failure has retry and does not render a blank configuration', async () => {
    read.mockRejectedValue(new Error('读取失败'));
    mount(); await screen.findByText('读取失败');
    expect(screen.queryByRole('button', { name: '添加供应商' })).not.toBeInTheDocument();
    read.mockResolvedValue({ ...fixture(), providers: [], chatModelId: null });
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    await screen.findByRole('heading', { name: '添加你的第一个供应商' });
  });
  it('registered settings tab guard preserves draft until user decides', async () => {
    let guard: SettingsLeaveGuard | null = null;
    mount(value => { guard = value; });
    fireEvent.click(await screen.findByRole('button', { name: '供应商更多操作' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '编辑连接' }));
    expect(screen.getByRole('heading', { name: '编辑连接' })).toHaveFocus();
    expect(screen.queryByRole('button', { name: '设置：深度分析' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '获取模型' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '返回模型列表' }));
    await screen.findByRole('button', { name: '设置：深度分析' });
    await waitFor(() => expect(screen.getByRole('button', { name: '供应商更多操作' })).toHaveFocus());
    fireEvent.click(screen.getByRole('button', { name: '供应商更多操作' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '编辑连接' }));
    fireEvent.change(screen.getByLabelText('供应商名称'), { target: { value: '新名称' } });
    const next = vi.fn(); act(() => { (guard as SettingsLeaveGuard | null)?.(next); });
    fireEvent.click(await screen.findByRole('button', { name: '放弃修改' }));
    expect(next).toHaveBeenCalledOnce();
  });
  it('route navigation uses the same guard including browser back', async () => {
    // jsdom 的 AbortSignal 与 Node fetch 不同 realm；本例无 loader，仅移除测试路由 Request 的 signal。
    const NativeRequest = globalThis.Request;
    vi.stubGlobal('Request', class extends NativeRequest {
      constructor(input: RequestInfo | URL, init?: RequestInit) { super(input, { ...init, signal: undefined }); }
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter([{ path: '*', element: <Routes><Route path="/settings" element={<AiConnectionsPanel />} /><Route path="/other" element={<p>其他页面</p>} /></Routes> }], { initialEntries: ['/other', '/settings'], initialIndex: 1 });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: '设置：深度分析' }));
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: '新名称' } });
    act(() => { void router.navigate(-1); });
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '继续编辑' }));
    expect(screen.getByDisplayValue('新名称')).toBeInTheDocument();
    act(() => { void router.navigate('/other'); });
    fireEvent.click(await screen.findByRole('button', { name: '放弃修改' }));
    await screen.findByText('其他页面');
  });
});
