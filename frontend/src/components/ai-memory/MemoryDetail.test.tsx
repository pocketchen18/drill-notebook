import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryDetail } from './MemoryDetail';
import { ApiError } from '../../lib/api';
import type { MemoryItem } from '../../lib/aiMemoryTypes';

/**
 * 记忆详情与编辑（§12 / contracts §3）：编辑必须带<b>取回的那一版</b>，
 * 409 只摊元信息、正文要用户显式取回，密文坏掉的行不许界面猜出正文。
 */

const { apiGet, apiPatch } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPatch: vi.fn() }));

/** ApiError 必须留真身：组件里靠 `instanceof` 判 code，整体替换会把它抹掉。 */
vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>();
  return { ...actual, get: (...args: unknown[]) => apiGet(...args), patch: (...args: unknown[]) => apiPatch(...args) };
});

function item(overrides: Record<string, unknown> = {}): MemoryItem {
  return {
    memoryId: 'm-1',
    version: 1,
    kind: 'PREFERENCE',
    scopeType: 'GLOBAL',
    scopeId: 0,
    status: 'ACTIVE',
    validUntil: null,
    recordedAt: '2026-10-12T00:00:00.000Z',
    origin: 'AUTO',
    manualProtected: false,
    pinned: false,
    validFrom: null,
    observedAt: null,
    lastConfirmedAt: null,
    predicate: 'answer.style',
    value: '先给例子再讲原理',
    corrupt: false,
    sources: [
      { evidenceId: 'e-1', sessionId: 10, turnId: 41, messageId: 201, sessionTitle: '数学错题', quote: '我喜欢先看例子' },
      { evidenceId: 'e-2', sessionId: 10, turnId: 42, messageId: 203, sessionTitle: '数学错题', quote: null, corrupt: true }
    ],
    ...overrides
  } as MemoryItem;
}

function renderDetail(props: { item: MemoryItem; onDelete?: (item: MemoryItem) => void }): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryDetail
        item={props.item}
        onClose={() => undefined}
        onChanged={() => undefined}
        onDelete={props.onDelete ?? (() => undefined)}
      />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  apiGet.mockResolvedValue(item());
  apiPatch.mockResolvedValue(item({ version: 2 }));
});

describe('MemoryDetail', () => {
  it('保存带的是详情里取回的版本，不是列表快照那一版', async () => {
    apiGet.mockResolvedValue(item({ version: 3 }));
    renderDetail({ item: item({ version: 1 }) });

    const area = await screen.findByLabelText('记忆正文');
    await waitFor(() => expect(screen.getByText('记录于 2026-10-12T00:00:00.000Z')).toBeTruthy());
    fireEvent.change(area, { target: { value: '先给反例再给正例' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(apiPatch).toHaveBeenCalledWith('/api/ai/memory/items/m-1', {
      expectedVersion: 3,
      value: '先给反例再给正例'
    }));
  });

  it('版本冲突（409）保留草稿、只显示元信息，取回后才换新基线继续保存', async () => {
    apiGet
      .mockResolvedValueOnce(item({ version: 3 }))
      .mockResolvedValue(item({ version: 5, value: '先给结论再给推导' }));
    apiPatch.mockRejectedValue(new ApiError('MEMORY_VERSION_CONFLICT', '这条记忆已经被改过，请重载后再试', 409, {
      details: {
        memoryId: 'm-1', version: 5, kind: 'PREFERENCE', scopeType: 'GLOBAL', scopeId: 0,
        validUntil: null, recordedAt: '2026-10-13T00:00:00.000Z', status: 'ACTIVE'
      }
    }));

    renderDetail({ item: item({ version: 3 }) });
    const area = await screen.findByLabelText('记忆正文');
    fireEvent.change(area, { target: { value: '先给反例再给正例' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    expect(await screen.findByText(/服务端已有更新（启用）/)).toBeTruthy();
    expect(screen.getByText(/你的草稿基于旧内容/)).toBeTruthy();
    expect(screen.getByText(/冲突详情只带元信息/)).toBeTruthy();
    expect(screen.queryByText('先给结论再给推导')).toBeNull();
    expect((screen.getByLabelText('记忆正文') as HTMLTextAreaElement).value).toBe('先给反例再给正例');

    fireEvent.click(screen.getByRole('button', { name: '取回最新正文' }));
    expect(await screen.findByText(/最新值：先给结论再给推导/)).toBeTruthy();
    expect(screen.getByText(/已改用最新内容作为基线，保存会以你的草稿覆盖当前内容/)).toBeTruthy();

    apiPatch.mockResolvedValue(item({ version: 6, value: '先给反例再给正例' }));
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(apiPatch).toHaveBeenLastCalledWith('/api/ai/memory/items/m-1', {
      expectedVersion: 5,
      value: '先给反例再给正例'
    }));
  });

  it('没改动时不给保存：服务端对空 PATCH 直接 400', async () => {
    renderDetail({ item: item() });
    await screen.findByLabelText('记忆正文');

    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    expect(apiPatch).not.toHaveBeenCalled();
  });

  it('名称默认与列表标题一致，内部谓词不会被当成名称', async () => {
    const row = item({ predicate: 'other', value: '之后每次回答结束之后说一个完毕' });
    apiGet.mockResolvedValue(row);
    renderDetail({ item: row });

    const name = await screen.findByLabelText('记忆名称');
    await waitFor(() => expect((name as HTMLInputElement).value).toBe('每次回答结束之后说一个完毕'));
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
  });

  it('密文坏掉的行不猜正文，也不能保存', async () => {
    const corruptRow = { memoryId: 'm-9', corrupt: true, corruptCode: 'MEMORY_CIPHER_CORRUPT', predicate: undefined, value: undefined } as MemoryItem;
    apiGet.mockResolvedValue({ ...item(), ...corruptRow });
    renderDetail({ item: corruptRow });

    expect(await screen.findByText(/界面不猜正文/)).toBeTruthy();
    expect(screen.getByLabelText('记忆正文')).toBeDisabled();
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    expect(apiPatch).not.toHaveBeenCalled();
  });

  it('来源里读不出的原话标注出来，而不是留空行', async () => {
    renderDetail({ item: item() });

    expect(await screen.findByText('我喜欢先看例子')).toBeTruthy();
    expect(screen.getByText('（原话密文不可用）')).toBeTruthy();
    expect(screen.getAllByText('会话：数学错题')).toHaveLength(2);
  });

  it('删除把详情里取回的那一版交给上层确认', async () => {
    const onDelete = vi.fn();
    renderDetail({ item: item({ version: 3 }), onDelete });
    await screen.findByLabelText('记忆正文');

    fireEvent.click(screen.getByRole('button', { name: '删除' }));

    expect(onDelete).toHaveBeenCalledWith(expect.objectContaining({ memoryId: 'm-1', version: 3 }));
  });
});
