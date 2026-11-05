import { describe, expect, it, vi } from 'vitest';
import { streamChat } from './aiStream';
import type { StreamCallbacks } from './aiStream';

/**
 * SSE 帧解析（contracts §4.2）：done 与统一结果同结构、metadata 只在带 requestId 时构成轮次身份、
 * error 带 code/partial/persistence，以及"没有 done 就 EOF"这条必须能被调用方察觉。
 */

/** 事件帧按 UTF-8 编码逐帧回放，中文正文与真实字节流一致。 */
const frame = (text: string): Uint8Array => new TextEncoder().encode(text);

const bodyOf = (frames: string[]) => {
  let index = 0;
  return {
    getReader: () => ({
      read: async () => (index < frames.length
        ? { done: false, value: frame(frames[index++]) }
        : { done: true, value: undefined })
    })
  };
};

interface Captured {
  deltas: string[];
  metadata: unknown[];
  done?: Record<string, unknown>;
  failure?: Record<string, unknown>;
  noDone?: { requestId?: string; partial: boolean };
}

const run = async (frames: string[], hooks: Partial<StreamCallbacks> = {}): Promise<Captured> => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, body: bodyOf(frames) })));
  const captured: Captured = { deltas: [], metadata: [] };
  await new Promise<void>((resolve) => {
    void streamChat('/api/ai/chat/stream', {}, {
      onText: (delta) => {
        captured.deltas.push(delta);
        hooks.onText?.(delta);
      },
      onReasoning: hooks.onReasoning ?? (() => undefined),
      onMetadata: (metadata) => {
        captured.metadata.push(metadata);
        hooks.onMetadata?.(metadata);
      },
      onDone: (payload) => {
        captured.done = payload as unknown as Record<string, unknown>;
        resolve();
      },
      onError: (failure) => {
        captured.failure = failure as unknown as Record<string, unknown>;
        resolve();
      },
      onNoDone: (info) => {
        captured.noDone = info;
        resolve();
      }
    });
  });
  vi.unstubAllGlobals();
  return captured;
};

describe('aiStream metadata 事件', () => {
  it('把 metadata 的 requestId 交给 onMetadata，且不影响其他事件', async () => {
    const captured = await run([
      'event:metadata\ndata:{"requestId":"r-1","sessionId":10,"status":"prepared"}\n\n',
      'event:text\ndata:{"text":"hi"}\n\n',
      'event:done\ndata:{"reply":"hi"}\n\n'
    ]);

    expect(captured.failure).toBeUndefined();
    expect(captured.metadata).toEqual([
      { requestId: 'r-1', sessionId: 10, status: 'prepared' }
    ]);
    expect(captured.deltas).toEqual(['hi']);
  });

  it('没有 requestId 的 metadata 不触发 onMetadata（计划态不是轮次身份）', async () => {
    const captured = await run(['event:metadata\ndata:{"planId":"p-1"}\n\n', 'event:done\ndata:{"reply":""}\n\n']);

    expect(captured.failure).toBeUndefined();
    expect(captured.metadata).toHaveLength(0);
  });

  it('v2 的计划态 metadata 与 requestId 一起来：停止按钮要有身份才能取消', async () => {
    const captured = await run([
      'event:metadata\ndata:{"requestId":"r-9","planId":"p-9","dispatchState":"not_sent",'
        + '"memoryContext":{"provided":[{"memoryId":"m-1","version":2}]},"contextUsage":{"inputBudget":8000}}\n\n',
      'event:done\ndata:{"reply":"ok","requestId":"r-9"}\n\n'
    ]);

    expect(captured.metadata).toEqual([{
      requestId: 'r-9',
      planId: 'p-9',
      dispatchState: 'not_sent',
      memoryContext: { provided: [{ memoryId: 'm-1', version: 2 }] },
      contextUsage: { inputBudget: 8000 }
    }]);
  });
});

describe('aiStream done 事件', () => {
  it('done 带统一结果的字段集，徽标与检查器不必再发第二次请求', async () => {
    const captured = await run([
      'event:done\ndata:{"protocolVersion":2,"requestId":"r-1","sessionId":10,"status":"COMPLETED",'
        + '"stateDurable":true,"persistenceStatus":"saved","replayed":false,"reply":"hi",'
        + '"memoryContext":{"provided":[{"memoryId":"m-1","version":2}],"estimatedTokens":48,"vaultRevision":17},'
        + '"contextUsage":{"estimated":true,"inputBudget":8000},"contextAudit":{"planId":"p-1","dispatchState":"sent",'
        + '"available":true},"usage":{"source":"provider","inputTokens":1640,"cacheWriteTokens":null}}\n\n'
    ]);

    expect(captured.done).toMatchObject({
      reply: 'hi',
      protocolVersion: 2,
      requestId: 'r-1',
      sessionId: 10,
      status: 'COMPLETED',
      stateDurable: true,
      persistenceStatus: 'saved',
      replayed: false,
      memoryContext: { provided: [{ memoryId: 'm-1', version: 2 }], vaultRevision: 17 },
      contextAudit: { planId: 'p-1', dispatchState: 'sent' },
      usage: { inputTokens: 1640, cacheWriteTokens: null }
    });
  });

  it('done 带非空 reasoning 时整段透传，思考链不只来自流式增量', async () => {
    const captured = await run(['event:done\ndata:{"reply":"hi","reasoning":"先想到二分查找"}\n\n']);

    expect(captured.done).toMatchObject({ reply: 'hi', reasoning: '先想到二分查找' });
  });

  it('不认识的后端字段一律忽略，不塞进结果视图', async () => {
    const captured = await run(['event:done\ndata:{"reply":"hi","brandNewThing":{"a":1}}\n\n']);

    expect(captured.done).toEqual({ reply: 'hi', reasoning: '' });
  });
});

describe('aiStream error 事件与 EOF', () => {
  it('error 帧带 code/partial 与收尾事实，前端才知道这轮能不能自动回退', async () => {
    const captured = await run([
      'event:metadata\ndata:{"requestId":"r-2"}\n\n',
      'event:text\ndata:{"text":"part"}\n\n',
      'event:error\ndata:{"message":"upstream died","requestId":"r-2","sessionId":10,"code":"MEMORY_LOCKED",'
        + '"partial":true,"persistenceStatus":"failed","stateDurable":false}\n\n'
    ]);

    expect(captured.failure).toMatchObject({
      message: 'upstream died',
      requestId: 'r-2',
      sessionId: 10,
      code: 'MEMORY_LOCKED',
      partial: true,
      persistenceStatus: 'failed',
      stateDurable: false
    });
  });

  it('没有 done 就 EOF：交给调用方去查这一轮的终态，不能当完成', async () => {
    const captured = await run([
      'event:metadata\ndata:{"requestId":"r-3"}\n\n',
      'event:text\ndata:{"text":"half"}\n\n'
    ]);

    expect(captured.done).toBeUndefined();
    expect(captured.noDone).toEqual({ requestId: 'r-3', partial: true });
  });

  it('done 之后的 EOF 不再补发 onNoDone：收口只算一次', async () => {
    const captured = await run([
      'event:metadata\ndata:{"requestId":"r-5"}\n\n',
      'event:done\ndata:{"reply":"hi","requestId":"r-5"}\n\n'
    ]);
    // done 先到就 resolve，读取循环还要走到 EOF 才执行 finally；一个宏任务保证它已跑完
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(captured.done).toMatchObject({ reply: 'hi' });
    expect(captured.noDone).toBeUndefined();
  });

  it('error 帧之后的 EOF 同样不再补发 onNoDone', async () => {
    const captured = await run([
      'event:metadata\ndata:{"requestId":"r-6"}\n\n',
      'event:error\ndata:{"message":"断了","requestId":"r-6"}\n\n'
    ]);
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(captured.failure).toMatchObject({ requestId: 'r-6' });
    expect(captured.noDone).toBeUndefined();
  });

  it('读取中途抛错也算 EOF 之外的失败，partial 反映已经产出的内容', async () => {
    const frames = ['event:metadata\ndata:{"requestId":"r-4"}\n\n', 'event:text\ndata:{"text":"half"}\n\n'];
    const reader = {
      read: async (): Promise<unknown> => {
        if (frames.length === 0) throw new Error('stream cut');
        return { done: false, value: frame(frames.shift() as string) };
      }
    };
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, body: { getReader: () => reader } })));
    let failure: Record<string, unknown> | undefined;
    await new Promise<void>((resolve) => {
      void streamChat('/api/ai/chat/stream', {}, {
        onText: () => undefined,
        onReasoning: () => undefined,
        onDone: () => resolve(),
        onError: (error) => {
          failure = error as unknown as Record<string, unknown>;
          resolve();
        }
      });
    });
    vi.unstubAllGlobals();

    expect(failure).toMatchObject({ message: 'stream cut', requestId: 'r-4', partial: true });
  });
});
