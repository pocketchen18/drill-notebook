import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SessionReferencePreview } from './SessionReferencePreview';
import { MarkdownContent } from '../markdown/MarkdownRenderer';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../../lib/api', () => ({ get }));
const link = 'drill-notebook://session/0123456789abcdef0123456789abcdef';
const show = () => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
  <SessionReferencePreview link={link} onClose={vi.fn()} onSessionLink={vi.fn()} />
</QueryClientProvider>);

beforeEach(() => { vi.resetAllMocks(); });
describe('会话来源只读预览', () => {
  it('引用点击交给应用处理，不导航到系统协议', () => {
    const open = vi.fn();
    render(<MarkdownContent value={`[旧会话](${link})`} onSessionLink={open} />);
    fireEvent.click(screen.getByRole('link', { name: '旧会话' }));
    expect(open).toHaveBeenCalledWith(link);
  });
  it('解析归档来源并按原历史接口分页，保留失败状态', async () => {
    get.mockImplementation(async (url: string) => {
      if (url === '/api/ai/sessions?includeArchived=true') return [{ id: 42, title: '旧会话', sessionLink: link, archived: true }];
      return { sessionId: 42, historyRevision: 2, hasMore: !url.includes('beforeMessageId'), limit: 50,
        messages: url.includes('beforeMessageId') ? [{ id: 1, role: 'user', content: '更早的提问' }]
          : [{ id: 2, role: 'assistant', content: '旧回答', turnStatus: 'FAILED' }] };
    });
    show();
    expect(await screen.findByText('旧回答')).toBeVisible();
    expect(screen.getByText('回复失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '加载更早的消息' }));
    expect(await screen.findByText('更早的提问')).toBeVisible();
    expect(get).toHaveBeenCalledWith('/api/ai/sessions/42/history?limit=50&beforeMessageId=2');
  });
  it('删除或不存在的来源不回落到另一个会话，可重试', async () => {
    get.mockResolvedValue([{ id: 99, title: '同名会话', sessionLink: 'different' }]);
    show();
    expect(await screen.findByText('会话不存在或已被删除')).toBeVisible();
    expect(get).toHaveBeenCalledTimes(1);
    get.mockImplementation(async (url: string) => url.includes('?includeArchived')
      ? [{ id: 42, title: '恢复可见', sessionLink: link }]
      : { sessionId: 42, messages: [], historyRevision: 0, hasMore: false });
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    await waitFor(() => expect(screen.getByText('此会话暂无已保存的消息。')).toBeVisible());
  });
});
