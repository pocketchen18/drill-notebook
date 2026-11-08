import { StrictMode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { VerificationDialog } from './ModelVerification';
import { DiscoveryDialog } from './ConnectionDialogs';
import type { ConnectionProvider, ModelVerification, VerificationResponse } from './types';

const { read, request } = vi.hoisted(() => ({ read: vi.fn(), request: vi.fn() }));
vi.mock('../../lib/api', () => ({ get: (...args: unknown[]) => read(...args), apiRequest: (...args: unknown[]) => request(...args) }));
const provider: ConnectionProvider = { id: 'p', name: '测试连接', endpoint: 'https://example.invalid/v1', apiFormat: 'chat_completions', authMode: 'none', hasKey: false, needsPassword: false, models: [] };
const check = (passed: boolean) => ({ status: passed ? 'passed' as const : 'failed' as const, message: passed ? '通过' : '本次请求失败', elapsedMs: 5 });
const result = (text = true, image = true, tools = true): ModelVerification => ({ text: check(text), image: check(image), tools: check(tools), checkedAt: '2026-11-07T00:00:00Z' });
function dialog(verify: ReturnType<typeof vi.fn>, password = false, ids = ['alpha', 'beta']) {
  const close = vi.fn(), refresh = vi.fn(), busy = vi.fn();
  const view = render(<StrictMode><VerificationDialog provider={{ ...provider, needsPassword: password }} models={ids.map(modelId => ({ modelId }))} verify={verify} close={close} onBusy={busy} refresh={refresh} /></StrictMode>);
  return { ...view, close, refresh, busy };
}
describe('automatic model verification', () => {
  beforeEach(() => { vi.clearAllMocks(); read.mockResolvedValue([]); request.mockResolvedValue({ cancelled: true }); });
  it('automatically verifies once in StrictMode and retries only failed additions', async () => {
    const verify = vi.fn().mockResolvedValueOnce({ added: true, verification: result(true, false, true) })
      .mockResolvedValueOnce({ added: false, verification: result(false, false, false) }).mockResolvedValue({ added: true, verification: result() });
    dialog(verify);
    await screen.findByRole('button', { name: '重试未添加项' });
    expect(verify).toHaveBeenCalledTimes(2);
    expect(screen.getByText('工具调用已自动开启。')).toBeInTheDocument();
    expect(screen.getByText('已添加')).toBeInTheDocument();
    expect(screen.getByText('未添加')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '重试未添加项' }));
    await waitFor(() => expect(screen.getAllByText('已添加')).toHaveLength(2));
    expect(verify.mock.calls.map(call => call[0].modelId)).toEqual(['alpha', 'beta', 'beta']);
  });
  it('cancel waits for server acknowledgment before aborting and ignores late completion', async () => {
    const finishes: ((value: VerificationResponse) => void)[] = [], acknowledgments: ((value: unknown) => void)[] = [];
    request.mockImplementation(() => new Promise(resolve => { acknowledgments.push(resolve); }));
    const verify = vi.fn().mockImplementation(() => new Promise(resolve => { finishes.push(resolve); }));
    const { refresh } = dialog(verify, false, ['alpha', 'beta', 'gamma', 'delta']);
    fireEvent.click(await screen.findByRole('button', { name: '取消剩余验证' }));
    expect(request).toHaveBeenCalledTimes(3);
    expect(request.mock.calls[0][0]).toMatch(/\/tests\/.+\/cancel$/);
    expect(verify.mock.calls.every(call => !call[3].aborted)).toBe(true);
    await act(async () => { acknowledgments[0]({ cancelled: true }); });
    expect(verify.mock.calls[0][3].aborted).toBe(true);
    expect(verify.mock.calls[1][3].aborted).toBe(false);
    await act(async () => { acknowledgments.slice(1).forEach(done => done({ cancelled: true })); });
    expect(verify.mock.calls.every(call => call[3].aborted)).toBe(true);
    await act(async () => { finishes.forEach(done => done({ added: true, verification: result() })); });
    await waitFor(() => expect(screen.getAllByText('已取消')).toHaveLength(4));
    expect(verify).toHaveBeenCalledTimes(3); expect(refresh).toHaveBeenCalled();
    expect(screen.queryByText('已添加')).not.toBeInTheDocument();
  });
  it('failed cancellation keeps current request visible until it ends', async () => {
    const finishes: ((value: VerificationResponse) => void)[] = [];
    request.mockRejectedValue(new Error('offline'));
    const verify = vi.fn().mockImplementation(() => new Promise(resolve => { finishes.push(resolve); }));
    dialog(verify);
    fireEvent.click(await screen.findByRole('button', { name: '取消剩余验证' }));
    await screen.findByText('取消请求未送达，正在等待当前验证结束。');
    expect(verify.mock.calls[0][3].aborted).toBe(false);
    await act(async () => { finishes.forEach(done => done({ added: true, verification: result() })); });
    expect(verify).toHaveBeenCalledTimes(2);
    await screen.findByRole('button', { name: '重试未添加项' });
  });
  it('keeps three workers busy and preserves row order when responses finish out of order', async () => {
    const finishes = new Map<string, (value: VerificationResponse) => void>();
    const verify = vi.fn().mockImplementation((model: { modelId: string }) => new Promise(resolve => finishes.set(model.modelId, resolve)));
    dialog(verify, false, ['alpha', 'beta', 'gamma', 'delta', 'epsilon']);
    await waitFor(() => expect(verify).toHaveBeenCalledTimes(3));
    expect(screen.getAllByText('验证中…')).toHaveLength(3);
    expect(screen.getAllByText('待验证')).toHaveLength(2);
    await act(async () => { finishes.get('beta')!({ added: true }); });
    expect(verify).toHaveBeenCalledTimes(4);
    expect(verify.mock.calls[3][0].modelId).toBe('delta');
    await act(async () => { finishes.get('gamma')!({ added: false }); });
    expect(verify).toHaveBeenCalledTimes(5);
    await act(async () => { ['alpha', 'delta', 'epsilon'].forEach(id => finishes.get(id)!({ added: true })); });
    expect(screen.getAllByText('已添加')).toHaveLength(4);
    expect([...document.querySelectorAll('.connection-check-heading strong')].map(node => node.textContent)).toEqual(['alpha', 'beta', 'gamma', 'delta', 'epsilon']);
  });
  it('password protected connections wait for a password without sending requests', async () => {
    const verify = vi.fn().mockResolvedValue({ added: true, verification: result() });
    dialog(verify, true);
    expect(screen.getByRole('button', { name: '开始验证' })).toBeDisabled();
    expect(verify).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('此连接主密码'), { target: { value: 'test-only' } });
    fireEvent.click(screen.getByRole('button', { name: '开始验证' }));
    await waitFor(() => expect(verify).toHaveBeenCalledTimes(2));
    expect(verify.mock.calls[0][1]).toBe('test-only');
  });
  it('failed discovery allows manual IDs in the same dialog and fetches once', async () => {
    request.mockRejectedValue(new Error('列表不可用'));
    const add = vi.fn();
    render(<StrictMode><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><DiscoveryDialog provider={provider} close={vi.fn()} add={add} busy={false} /></QueryClientProvider></StrictMode>);
    await screen.findByText('列表不可用'); expect(request).toHaveBeenCalledOnce();
    fireEvent.change(screen.getByLabelText('手动模型 ID'), { target: { value: 'private-model' } });
    fireEvent.click(screen.getByRole('button', { name: '加入待选' }));
    fireEvent.click(screen.getByRole('button', { name: '验证并添加（1）' }));
    expect(add).toHaveBeenCalledWith(['private-model'], '');
  });
});
