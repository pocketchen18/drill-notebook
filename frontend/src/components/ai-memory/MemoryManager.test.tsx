import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryManager } from './MemoryManager';
import { ApiError } from '../../lib/api';

/**
 * 记忆管理面板的状态与写路径（§14 P9：Loading / 空 / 错误 / 锁定 / 删除）。
 * 这里 mock 的是 HTTP 层，请求形状由 lib/aiMemory.test.ts 单独钉。
 */

const { apiGet, apiPost, apiPut, apiPatch, apiDel, apiDelJson } = vi.hoisted(() => ({
  apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiPatch: vi.fn(), apiDel: vi.fn(), apiDelJson: vi.fn()
}));

/** ApiError 必须留真身：组件里靠 `instanceof` 判 code，整体替换会把它抹掉。 */
vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return {
    ...actual,
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    put: (...args: unknown[]) => apiPut(...args),
    patch: (...args: unknown[]) => apiPatch(...args),
    del: (...args: unknown[]) => apiDel(...args),
    delJson: (...args: unknown[]) => apiDelJson(...args)
  };
});

function settings(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    revision: 4,
    enabled: true,
    autoExtract: false,
    compressSession: false,
    autoApplyLowRisk: true,
    policyVersion: 'memory-policy-v1',
    maintenanceModelMode: 'chat',
    dailyCallLimit: 20,
    dailyTokenLimit: 30000,
    budgetTimezone: 'UTC',
    memoryHistoryFloor: 0,
    consents: {},
    limits: { activeFacts: 2000, pendingFacts: 500 },
    initialized: true,
    unlocked: true,
    sessionUnlockedCount: 0,
    mode: 'fingerprint',
    vaultRevision: 2,
    ...overrides
  };
}

function item(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    memoryId: 'm-1',
    version: 1,
    kind: 'PREFERENCE',
    scopeType: 'GLOBAL',
    scopeId: 0,
    status: 'ACTIVE',
    validUntil: null,
    recordedAt: '2026-10-12T00:00:00.000Z',
    origin: 'MANUAL',
    manualProtected: true,
    pinned: false,
    validFrom: null,
    observedAt: null,
    lastConfirmedAt: null,
    predicate: 'answer.style',
    value: '先给例子再讲原理',
    corrupt: false,
    ...overrides
  };
}

/** 建议列表的空页形状：面板未确认时也带 epoch/revision 回来。 */
function proposalPage(proposals: Array<Record<string, unknown>>): Record<string, unknown> {
  return { proposals, limit: 20, hasMore: false, vaultEpoch: 3, vaultRevision: 2 };
}

function proposalGetCount(): number {
  return apiGet.mock.calls.filter((call) => String(call[0]).startsWith('/api/ai/memory/proposals')).length;
}

function renderPanel(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryManager />
    </QueryClientProvider>
  );
}

function modal(): HTMLElement {
  const node = document.querySelector('.arco-modal') as HTMLElement;
  expect(node).toBeTruthy();
  return node;
}

/** 已经发出去的列表 /search 请求体，按调用顺序；游标分页只看这份。
 * 审阅区也走 /search（status=PENDING），翻页计数不能把它算进来。 */
function searchBodies(): Array<{ cursor?: string }> {
  return apiPost.mock.calls
    .filter((call) => call[0] === '/api/ai/memory/search'
      && (call[1] as { status?: string }).status !== 'PENDING')
    .map((call) => call[1] as { cursor?: string });
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  apiGet.mockImplementation((url: string) => {
    if (url === '/api/ai/memory/settings') return Promise.resolve(settings());
    if (url === '/api/notebooks') return Promise.resolve([{ id: 7, title: '高等数学' }]);
    if (url.startsWith('/api/ai/memory/proposals')) return Promise.resolve(proposalPage([]));
    return Promise.resolve({});
  });
  apiPost.mockImplementation((url: string, body?: { status?: string }) => {
    if (url === '/api/ai/memory/search') {
      // 服务端按 status 过滤：面板要的是 PENDING 那一堆，列表要的是 ACTIVE
      const status = body?.status ?? 'ACTIVE';
      return Promise.resolve({
        items: status === 'PENDING' ? [] : [item()],
        limit: 20,
        status,
        hasMore: false,
        vaultRevision: 2
      });
    }
    return Promise.resolve({});
  });
  apiPut.mockResolvedValue({ revision: 5 });
  apiPatch.mockResolvedValue(item({ version: 2 }));
  apiDel.mockResolvedValue(undefined);
  apiDelJson.mockResolvedValue(undefined);
});

describe('MemoryManager', () => {
  it('锁定时不请求正文，也不说成"没有记忆"', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/settings') return Promise.resolve(settings({ unlocked: false }));
      if (url === '/api/notebooks') return Promise.resolve([]);
      return Promise.resolve({});
    });

    renderPanel();

    expect(await screen.findByText(/解锁后才能看到正文/)).toBeTruthy();
    expect(screen.queryByText('这个筛选下没有记忆')).toBeNull();
    await waitFor(() => expect(apiPost).not.toHaveBeenCalled());
  });

  it('还没有任何记忆时给出 §12 的首次空态与两个入口', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/search') {
        return Promise.resolve({ items: [], limit: 20, status: 'ACTIVE', hasMore: false, vaultRevision: 2 });
      }
      return Promise.resolve({});
    });

    renderPanel();

    expect(await screen.findByText('记住你明确表达的偏好和目标，供以后对话参考。')).toBeTruthy();
    expect(screen.getByRole('button', { name: '开启自动记忆' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '开启自动记忆' }));
    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/api/ai/memory/settings', {
      revision: 4,
      autoExtract: true,
      enabled: true
    }));
  });

  it('列表在读取中给轻量 Loading，不是一片空白也不是"没有记忆"', async () => {
    let release: () => void = () => undefined;
    apiPost.mockImplementation((url: string) => url === '/api/ai/memory/search'
      ? new Promise<unknown>((resolve) => { release = () => resolve({ items: [], limit: 20, status: 'ACTIVE', hasMore: false, vaultRevision: 2 }); })
      : Promise.resolve({}));

    renderPanel();

    expect(await screen.findByRole('status', { name: '正在读取记忆' })).toBeTruthy();
    expect(screen.queryByText('这个筛选下没有记忆')).toBeNull();

    release();
    expect(await screen.findByText('记住你明确表达的偏好和目标，供以后对话参考。')).toBeTruthy();
  });

  it('删除要先确认，并把 expectedVersion 放在请求体里', async () => {
    renderPanel();

    fireEvent.click(await screen.findByRole('button', { name: '删除' }));
    fireEvent.click(within(modal()).getByRole('button', { name: '删除' }));

    await waitFor(() => expect(apiDelJson).toHaveBeenCalledWith('/api/ai/memory/items/m-1', {
      expectedVersion: 1,
      forget: false,
      operationId: expect.any(String)
    }));
  });

  it('列表读失败时把后端原因如实显示，而不是留一片空白', async () => {
    apiPost.mockImplementation(() => Promise.reject(new ApiError('MEMORY_LOCKED', '记忆库未解锁或已锁定，需要重新解锁', 423)));

    renderPanel();

    expect(await screen.findByText('记忆库未解锁或已锁定，需要重新解锁')).toBeTruthy();
  });

  it('后端没带 message 时按 code 兜底文案，仍然说清原因', async () => {
    apiPost.mockImplementation(() => Promise.reject(new ApiError('MEMORY_LOCKED', '', 423)));

    renderPanel();

    expect(await screen.findByText('记忆库已锁定，解锁后才能查看或修改')).toBeTruthy();
  });

  it('手动添加走 POST /items，缺键名时由服务端拒绝并如实报出', async () => {
    renderPanel();
    await screen.findByText('先给例子再讲原理');

    fireEvent.click(screen.getByRole('button', { name: '手动添加' }));
    const form = modal();
    fireEvent.change(within(form).getByPlaceholderText('例如 answer.style'), { target: { value: 'answer.style' } });
    fireEvent.change(within(form).getByPlaceholderText('要记住的一句话'), { target: { value: '多用图表' } });
    fireEvent.click(within(form).getByRole('button', { name: '保存' }));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/items', expect.objectContaining({
      kind: 'PREFERENCE',
      scopeType: 'GLOBAL',
      predicate: 'answer.style',
      value: '多用图表'
    })));
  });

  it('待确认的行要标出来，不能和生效的混成一堆', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/search') {
        return Promise.resolve({
          items: [item({ status: 'PENDING' })], limit: 20, status: 'PENDING', hasMore: false, vaultRevision: 2
        });
      }
      return Promise.resolve({});
    });

    renderPanel();

    expect(await screen.findByText('待确认')).toBeTruthy();
  });

  it('下一页把服务端游标放进请求体，上一页退回本地游标栈那一页', async () => {
    apiPost.mockImplementation((url: string, payload: unknown) => {
      if (url !== '/api/ai/memory/search') return Promise.resolve({});
      const body = payload as { cursor?: string; status?: string };
      if (body.status === 'PENDING') {
        return Promise.resolve({ items: [], limit: 20, status: 'PENDING', hasMore: false, vaultRevision: 2 });
      }
      const first = !body.cursor;
      return Promise.resolve({
        items: [item({ memoryId: first ? 'm-1' : 'm-2', value: first ? '第一页的一条' : '第二页的一条' })],
        limit: 20,
        status: 'ACTIVE',
        hasMore: first,
        nextCursor: first ? 'cursor-1' : undefined,
        vaultRevision: 2
      });
    });

    renderPanel();
    expect(await screen.findByText('第一页的一条')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '记忆列表下一页' }));
    expect(await screen.findByText('第二页的一条')).toBeTruthy();
    await waitFor(() => expect(searchBodies().length).toBe(2));
    expect(searchBodies()[1]).toMatchObject({ cursor: 'cursor-1' });

    fireEvent.click(screen.getByRole('button', { name: '记忆列表上一页' }));
    expect(await screen.findByText('第一页的一条')).toBeTruthy();
    // 退回的是游标栈的上一格：那一页重新请求时不带 cursor，不是把服务端游标反着用
    await waitFor(() => expect(searchBodies().length).toBe(3));
    expect(searchBodies()[2].cursor).toBeUndefined();
  });

  it('服务端判定像凭据时先要求确认，不静默写入', async () => {
    apiPost.mockImplementation((url: string, payload: unknown) => {
      if (url === '/api/ai/memory/search') {
        return Promise.resolve({ items: [], limit: 20, status: 'ACTIVE', hasMore: false, vaultRevision: 2 });
      }
      if (url === '/api/ai/memory/items' && !(payload as { sensitiveConfirmed?: boolean }).sensitiveConfirmed) {
        return Promise.reject(new ApiError('MEMORY_SENSITIVE_CONFIRMATION_REQUIRED', '内容像凭据，需显式确认', 409));
      }
      return Promise.resolve(item());
    });

    renderPanel();
    await screen.findByText(/记住你明确表达的偏好/);

    fireEvent.click(screen.getAllByRole('button', { name: '手动添加' })[0]);
    const form = modal();
    fireEvent.change(within(form).getByPlaceholderText('例如 answer.style'), { target: { value: 'api.key' } });
    fireEvent.change(within(form).getByPlaceholderText('要记住的一句话'), { target: { value: 'sk-live-abc' } });
    fireEvent.click(within(form).getByRole('button', { name: '保存' }));

    expect(await within(form).findByText(/像凭据/)).toBeTruthy();
    expect(await within(form).findByRole('button', { name: '仍要写入' })).toBeTruthy();

    fireEvent.click(within(form).getByRole('button', { name: '仍要写入' }));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/items',
      expect.objectContaining({ sensitiveConfirmed: true })));
  });

  it('清空要先确认，确认后只把 confirm 与读到的 epoch 放进请求体', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/settings') return Promise.resolve(settings({ epoch: 5 }));
      if (url === '/api/notebooks') return Promise.resolve([]);
      if (url.startsWith('/api/ai/memory/proposals')) return Promise.resolve(proposalPage([]));
      return Promise.resolve({});
    });
    apiPost.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/search') {
        return Promise.resolve({ items: [item()], limit: 20, status: 'ACTIVE', hasMore: false, vaultRevision: 2 });
      }
      if (url === '/api/ai/memory/clear') {
        return Promise.resolve({ cleared: true, epoch: 6, revision: 9, memoryEnabled: false, autoExtract: false,
          scanWatermarksAdvanced: 1, purged: { facts: 4, evidence: 2, proposals: 1, suppressions: 1, jobs: 0, contexts: 1 } });
      }
      return Promise.resolve({});
    });

    renderPanel();
    await screen.findByText('先给例子再讲原理');
    const proposalsBefore = proposalGetCount();

    fireEvent.click(screen.getByRole('button', { name: '清空全部记忆' }));
    const form = modal();
    // §11.3：清空不承诺擦掉旧备份，这句话必须在确认之前就看见，而不是删完才弹 toast
    expect(within(form).getByText(/旧备份不受影响/)).toBeTruthy();
    expect(apiPost).not.toHaveBeenCalledWith('/api/ai/memory/clear', expect.anything());

    fireEvent.click(within(form).getByRole('button', { name: '确认清空' }));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/api/ai/memory/clear',
      expect.objectContaining({ confirm: true, expectedVaultEpoch: 5 })));
    // 清空作废的不只是列表：审阅区那两堆也得重新读，否则删掉的候选还挂在卡片上
    await waitFor(() => expect(proposalGetCount()).toBeGreaterThan(proposalsBefore));
  });

  it('202 表示还在清：拿返回的 epoch 续接下一趟，不当成失败', async () => {
    const passes: Array<Record<string, unknown>> = [];
    apiGet.mockImplementation((url: string) => {
      if (url === '/api/ai/memory/settings') return Promise.resolve(settings({ epoch: 5 }));
      if (url === '/api/notebooks') return Promise.resolve([]);
      if (url.startsWith('/api/ai/memory/proposals')) return Promise.resolve(proposalPage([]));
      return Promise.resolve({});
    });
    apiPost.mockImplementation((url: string, body?: Record<string, unknown>) => {
      if (url === '/api/ai/memory/search') {
        return Promise.resolve({ items: [item()], limit: 20, status: 'ACTIVE', hasMore: false, vaultRevision: 2 });
      }
      if (url === '/api/ai/memory/clear') {
        passes.push(body ?? {});
        const first = passes.length === 1;
        return Promise.resolve({ cleared: !first, remaining: first, epoch: 6,
          revision: 9, memoryEnabled: false, autoExtract: false, scanWatermarksAdvanced: 1,
          purged: { facts: first ? 1 : 3, evidence: 0, proposals: 0, suppressions: 0, jobs: 0, contexts: 0 } });
      }
      return Promise.resolve({});
    });

    renderPanel();
    await screen.findByText('先给例子再讲原理');

    fireEvent.click(screen.getByRole('button', { name: '清空全部记忆' }));
    fireEvent.click(within(modal()).getByRole('button', { name: '确认清空' }));

    await waitFor(() => expect(passes.length).toBe(2));
    expect(passes[0].expectedVaultEpoch).toBe(5);
    expect(passes[1].expectedVaultEpoch, '续接用的是响应里的新 epoch，不是手里那一版').toBe(6);
  });
});
