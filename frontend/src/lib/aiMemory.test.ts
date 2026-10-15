import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './api';
import {
  deleteMemoryItem,
  editMemoryItem,
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
});
