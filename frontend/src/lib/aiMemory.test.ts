import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './api';
import {
  clearAllMemories,
  deleteMemoryItem,
  editMemoryItem,
  listMemoryProposals,
  reviewMemoryCandidate,
  reviewMemoryProposal,
  searchMemoryItems,
  unlockMemoryVault,
  updateMemorySettings
} from './aiMemoryApi';

/**
 * 记忆 REST 的线上形态（contracts §3）。这里钉的是"前端到底发出去什么、错误里能拿到什么"：
 * 关键词与密码只进请求体、编辑走 PATCH、删除走带 body 的 DELETE、
 * 409 的 details 必须活着回到界面（§12 要拿它跟用户草稿对照）。
 */

interface Call {
  url: string;
  method: string;
  body?: string;
}

const FALLBACK_ORIGIN = 'http://127.0.0.1:18081';

let calls: Call[] = [];

function respond(status: number, payload?: unknown): void {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (input: string, init: RequestInit = {}) => {
    calls.push({
      url: String(input).replace(FALLBACK_ORIGIN, ''),
      method: String(init.method ?? 'GET'),
      body: typeof init.body === 'string' ? init.body : undefined
    });
    return {
      ok: status >= 200 && status < 300,
      status,
      text: async () => (payload === undefined ? '' : JSON.stringify(payload))
    };
  }));
}

function body(index: number): Record<string, unknown> {
  return JSON.parse(calls[index].body ?? '{}') as Record<string, unknown>;
}

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe('记忆 REST 的请求形状', () => {
  it('搜索关键词留在请求体里，URL 不带用户原话', async () => {
    respond(200, { items: [], limit: 20, status: 'ACTIVE', hasMore: false, vaultRevision: 3 });

    await searchMemoryItems({ status: 'ACTIVE', query: '雅思口语', limit: 20, cursor: 'abc' });

    expect(calls[0].url).toBe('/api/ai/memory/search');
    expect(calls[0].method).toBe('POST');
    expect(body(0)).toEqual({ status: 'ACTIVE', query: '雅思口语', limit: 20, cursor: 'abc' });
  });

  it('编辑用 PATCH，删除用带请求体的 DELETE', async () => {
    respond(200, { memoryId: 'm/1', version: 2, status: 'DISABLED' });

    await editMemoryItem('m/1', { expectedVersion: 1, status: 'DISABLED' });

    expect(calls[0].url).toBe('/api/ai/memory/items/m%2F1');
    expect(calls[0].method).toBe('PATCH');
    expect(body(0).expectedVersion).toBe(1);

    respond(204);
    await deleteMemoryItem('m/1', 2, true, 'op-1');

    expect(calls[0].method).toBe('DELETE');
    expect(calls[0].url).toBe('/api/ai/memory/items/m%2F1');
    expect(calls[0].url).not.toContain('expectedVersion');
    expect(body(0)).toEqual({ expectedVersion: 2, forget: true, operationId: 'op-1' });
  });

  it('主密码只进请求体，不进 URL', async () => {
    respond(200, { initialized: true, unlocked: true, sessionUnlockedCount: 0 });

    await unlockMemoryVault('s3cr3t-master');

    expect(calls[0].url).toBe('/api/ai/memory/vault/unlock');
    expect(calls[0].url).not.toContain('s3cr3t');
    expect(body(0).password).toBe('s3cr3t-master');
  });

  it('设置写入带上刚读到的 revision', async () => {
    respond(200, { revision: 8 });

    await updateMemorySettings(7, { autoExtract: true });

    expect(calls[0].method).toBe('PUT');
    expect(body(0)).toEqual({ revision: 7, autoExtract: true });
  });

  it('409 的 code、retryable 与 details 都回到调用方', async () => {
    respond(409, {
      code: 'MEMORY_VERSION_CONFLICT',
      message: '这条记忆已经被改过，请重载后再试',
      retryable: false,
      details: { memoryId: 'm-1', version: 5, status: 'ACTIVE' }
    });

    const error = (await editMemoryItem('m-1', { expectedVersion: 1, value: '新的' })
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('MEMORY_VERSION_CONFLICT');
    expect(error.status).toBe(409);
    expect(error.retryable).toBe(false);
    expect(error.details).toMatchObject({ version: 5 });
  });

  it('204 不回正文，界面按"没有返回体"处理', async () => {
    respond(204);

    await expect(deleteMemoryItem('m-1', 1, false)).resolves.toBeUndefined();
  });

  it('建议列表的 URL 只带非内容筛选条件，游标原样编码', async () => {
    respond(200, { proposals: [], limit: 20, hasMore: false, vaultEpoch: 3, vaultRevision: 2 });

    await listMemoryProposals({ scopeType: 'NOTEBOOK', scopeId: 7, limit: 20, cursor: '2026-10-12T00:00:00Z|p-1' });

    expect(calls[0].method).toBe('GET');
    expect(calls[0].url).toBe('/api/ai/memory/proposals?scopeType=NOTEBOOK&scopeId=7&limit=20&cursor=2026-10-12T00%3A00%3A00Z%7Cp-1');
    expect(calls[0].url).not.toContain('query');
    // GET 不带请求体：正文与关键词根本没有可以藏身的地方
    expect(calls[0].body).toBeUndefined();
  });

  it('不按作用域筛选时，URL 里不留裸的 scopeId', async () => {
    respond(200, { proposals: [], limit: 10, hasMore: false, vaultEpoch: 3, vaultRevision: 2 });

    await listMemoryProposals({ scopeId: 7, limit: 10 });

    expect(calls[0].url).toBe('/api/ai/memory/proposals?limit=10');
  });

  it('审阅两条路由都把版本与点名目标放进请求体', async () => {
    respond(200, { memoryId: 'm-1', version: 3, status: 'ACTIVE', changed: true });

    await reviewMemoryProposal('p/1', {
      expectedVersion: 3, decision: 'accept', expectedTargetVersion: 2, operationId: 'op-1'
    });

    expect(calls[0].method).toBe('POST');
    expect(calls[0].url).toBe('/api/ai/memory/proposals/p%2F1/review');
    expect(body(0)).toEqual({
      expectedVersion: 3, decision: 'accept', expectedTargetVersion: 2, operationId: 'op-1'
    });

    respond(200, { memoryId: 'm-9', version: 5, status: 'SUPERSEDED', changed: true });
    await reviewMemoryCandidate('c-1', {
      expectedVersion: 1, decision: 'replace', targetMemoryId: 'm-9', expectedTargetVersion: 4, operationId: 'op-2'
    });

    expect(calls[0].url).toBe('/api/ai/memory/items/c-1/review');
    expect(body(0)).toEqual({
      expectedVersion: 1, decision: 'replace', targetMemoryId: 'm-9', expectedTargetVersion: 4, operationId: 'op-2'
    });
  });

  it('清空只发 confirm、epoch 与幂等键；202 表示还没清完但不算失败', async () => {
    respond(202, {
      cleared: false, remaining: true, epoch: 6, revision: 9, memoryEnabled: false, autoExtract: false,
      scanWatermarksAdvanced: 2, purged: { facts: 2000, evidence: 0, proposals: 0, suppressions: 0, jobs: 0, contexts: 0 }
    });

    const result = await clearAllMemories(5, 'op-1');

    expect(calls[0].url).toBe('/api/ai/memory/clear');
    expect(body(0)).toEqual({ confirm: true, expectedVaultEpoch: 5, operationId: 'op-1' });
    expect(result.cleared).toBe(false);
    expect(result.epoch).toBe(6);
  });
});
