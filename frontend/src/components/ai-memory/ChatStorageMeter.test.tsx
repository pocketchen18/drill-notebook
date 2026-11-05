import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { ChatStorageMeter } from './ChatStorageMeter';
import type { ChatStorageSnapshot } from '../../lib/aiStorageApi';

/**
 * 容量条（执行方案 §12「另设容量提示」，contracts §2.7、§7.4）：
 * 它只读账本，一次 GET；三档说法各不相同，读不出不等于空，也不许蹭后台预算的词。
 */

const { apiGet, apiPost, apiDel } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn(), apiDel: vi.fn() }));

vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return {
    ...actual,
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    del: (...args: unknown[]) => apiDel(...args)
  };
});

const snapshot = (over: Partial<ChatStorageSnapshot> = {}): ChatStorageSnapshot => ({
  historyBytes: 1024,
  reservedHistoryBytes: 0,
  usedHistoryBytes: 1024,
  historyLimitBytes: 268435456,
  attachmentBytes: 0,
  attachmentLimitBytes: 268435456,
  historyWarning: false,
  attachmentWarning: false,
  revision: 3,
  ...over
});

const renderMeter = (props: { enabled?: boolean } = {}) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ChatStorageMeter {...props} />
    </QueryClientProvider>
  );
};

beforeEach(() => {
  vi.resetAllMocks();
  apiGet.mockResolvedValue(snapshot());
  apiPost.mockResolvedValue({});
  apiDel.mockResolvedValue(undefined);
});

describe('ChatStorageMeter', () => {
  it('挂载只读一次容量账本，走的是 /history/storage 而不是别的端口', async () => {
    renderMeter();
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/ai/history/storage'));
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(apiPost).not.toHaveBeenCalled();
    expect(apiDel).not.toHaveBeenCalled();
    expect(await screen.findByText('本地存储 1 KB / 256 MB')).toBeTruthy();
  });

  it('正常、接近上限、已达上限是三句不同的话', async () => {
    const { unmount } = renderMeter();
    expect((await screen.findByText(/本地存储/)).textContent).toBe('本地存储 1 KB / 256 MB');
    unmount();

    apiGet.mockResolvedValue(snapshot({ usedHistoryBytes: 230686720, historyBytes: 230686720, historyWarning: true }));
    const warning = renderMeter();
    expect((await warning.findByText(/本地存储/)).textContent)
      .toBe('本地存储 220 MB / 256 MB · 接近上限，可清理历史');
    warning.unmount();

    apiGet.mockResolvedValue(snapshot({ usedHistoryBytes: 268435456, historyBytes: 268435456, historyWarning: true }));
    const full = renderMeter();
    expect((await full.findByText(/本地存储/)).textContent)
      .toBe('本地存储 256 MB / 256 MB · 已达上限，先清理才能继续发');
  });

  it('预留也要报出来：used 是存量加预留，比例按同一个数算', async () => {
    apiGet.mockResolvedValue(snapshot({ historyBytes: 134217728, reservedHistoryBytes: 2097152, usedHistoryBytes: 136314880 }));
    renderMeter();
    const bar = await screen.findByRole('progressbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('51');
    expect(bar.getAttribute('aria-label')).toBe('本地存储 130 MB / 256 MB');
  });

  it('附件预警补一句，不预警时一个字都不加', async () => {
    apiGet.mockResolvedValue(snapshot({ attachmentWarning: true }));
    const view = renderMeter();
    expect((await view.findByText(/本地存储/)).textContent)
      .toBe('本地存储 1 KB / 256 MB · 附件也接近上限');
  });

  it('读不出是一条独立说法，不空着也不装成没占', async () => {
    apiGet.mockRejectedValue(new Error('库被占用'));
    renderMeter();
    expect(await screen.findByText('存储容量读不出')).toBeTruthy();
  });

  it('没读回来之前不摆任何容量说法', async () => {
    let release: (value: ChatStorageSnapshot) => void = () => {};
    apiGet.mockReturnValue(new Promise((resolve) => { release = resolve; }));
    const { container } = renderMeter();
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1));
    expect(container.textContent).toBe('');

    release(snapshot());
    expect((await screen.findByText(/本地存储/)).textContent).toBe('本地存储 1 KB / 256 MB');
  });

  it('抽屉关着时一个请求都不发', async () => {
    renderMeter({ enabled: false });
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(apiGet).not.toHaveBeenCalled();
  });
});
