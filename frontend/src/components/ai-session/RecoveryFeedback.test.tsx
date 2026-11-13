import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { RecoveryFeedback } from './RecoveryFeedback';
import { readRecovery } from '../../lib/aiRecovery';
import type { AiRecovery } from '../../lib/aiRecovery';

const value: AiRecovery = { kind: 'stream', state: 'waiting', attempt: 2, limit: 5,
  error: 'stream_disconnected\nerror decoding response body', nextRetryAt: 20_000 };
afterEach(() => vi.useRealTimers());
describe('失败重连反馈', () => {
  it('直接显示一次具体错误与真实计数，停止调用原停止入口', () => {
    vi.useFakeTimers(); vi.setSystemTime(19_000);
    const stop = vi.fn(); render(<RecoveryFeedback value={value} active onStop={stop} />);
    expect(screen.getByRole('status')).toHaveTextContent('正在重新连接 2/5');
    expect(screen.getByText('1.0 秒后重试')).toBeVisible();
    expect(screen.getAllByText(/error decoding response body/)).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /展开报错|收起报错|重新生成/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '停止' })); expect(stop).toHaveBeenCalledOnce();
  });
  it('持续断网等待无虚假上限，停止及耗尽仍保留报错', () => {
    const { rerender } = render(<RecoveryFeedback value={{ ...value, kind: 'network', limit: null }} active onStop={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent('等待网络恢复 · 已重试 2 次');
    rerender(<RecoveryFeedback value={{ ...value, state: 'stopped', nextRetryAt: null }} onStop={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent('已停止重新连接 · 已重试 2 次');
    expect(screen.getByText(/error decoding response body/)).toBeVisible();
    expect(screen.queryByRole('button')).toBeNull();
    expect(readRecovery({ ...value, attempt: -1 })).toBeUndefined(); expect(readRecovery(null)).toBeNull();
  });
});
