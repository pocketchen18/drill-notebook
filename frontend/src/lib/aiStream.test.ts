import { describe, it, expect, vi } from 'vitest';
import { streamChat } from './aiStream';

/** 事件帧按 UTF-8 逐帧回放：内容全 ASCII，charCodeAt 即字节。 */
const frame = (text: string): Uint8Array => new Uint8Array([...text].map((ch) => ch.charCodeAt(0)));

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

const run = async (frames: string[], callbacks: {
  onMetadata?: (payload: { requestId: string }) => void;
  onText?: (delta: string) => void;
}): Promise<string | null> => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, body: bodyOf(frames) })));
  let failure: string | null = null;
  await new Promise<void>((resolve) => {
    void streamChat('/api/ai/chat/stream', {}, {
      onText: (delta) => callbacks.onText?.(delta),
      onReasoning: () => undefined,
      onMetadata: callbacks.onMetadata,
      onDone: () => resolve(),
      onError: (message) => {
        failure = message;
        resolve();
      }
    });
  });
  vi.unstubAllGlobals();
  return failure;
};

describe('aiStream metadata 事件', () => {
  it('把 metadata 的 requestId 交给 onMetadata，且不影响其他事件', async () => {
    const seen: string[] = [];
    let requestId: string | null = null;
    const failure = await run([
      'event:metadata\ndata:{"requestId":"r-1","sessionId":10,"status":"prepared"}\n\n',
      'event:text\ndata:{"text":"hi"}\n\n',
      'event:done\ndata:{"reply":"hi"}\n\n'
    ], {
      onText: (delta) => seen.push(delta),
      onMetadata: ({ requestId: id }) => {
        requestId = id;
      }
    });

    expect(failure).toBeNull();
    expect(requestId).toBe('r-1');
    expect(seen).toEqual(['hi']);
  });

  it('没有 requestId 的 metadata 不触发 onMetadata（v2 计划态事件不误报）', async () => {
    let calls = 0;
    const failure = await run(['event:metadata\ndata:{"planId":"p-1"}\n\n', 'event:done\ndata:{"reply":""}\n\n'], {
      onMetadata: () => {
        calls += 1;
      }
    });

    expect(failure).toBeNull();
    expect(calls).toBe(0);
  });
});
