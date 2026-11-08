import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { ApiError } from '../lib/api';
import type { StreamCallbacks, StreamDonePayload } from '../lib/aiStream';
import { isUuidV7 } from '../lib/uuidV7';
import { useAiChatTurn } from './useAiChatTurn';
import type { TurnEvent } from './useAiChatTurn';

/**
 * 轮次状态机（执行方案 §12、§7.3；contracts §4.1/§4.2/§4.5）：
 * 一次逻辑发送只有一个 requestId，流式、回退、取消、查状态共用它；
 * 已产出内容后绝不自动重发，没有 done 就 EOF 时按服务端终态如实交代。
 */

const { apiGet, apiPost, streamChatMock } = vi.hoisted(() => ({
  apiGet: vi.fn(), apiPost: vi.fn(), streamChatMock: vi.fn()
}));

vi.mock('../lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/api')>()),
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args)
}));

vi.mock('../lib/aiStream', () => ({
  streamChat: (...args: unknown[]) => streamChatMock(...args)
}));

let callbacks: StreamCallbacks | null = null;
let events: TurnEvent[] = [];

const donePayload = (reply: string): StreamDonePayload => ({ reply, reasoning: '', protocolVersion: 2 });

const renderTurn = (initial: { sessionId?: number; streamEnabled: boolean } = { sessionId: 10, streamEnabled: true }) => {
  callbacks = null;
  events = [];
  return renderHook(
    (props: { sessionId?: number; streamEnabled: boolean }) => useAiChatTurn({
      sessionId: props.sessionId,
      streamEnabled: props.streamEnabled,
      onEvent: (event) => {
        events.push(event);
      }
    }),
    { initialProps: initial }
  );
};

const streamBody = (): Record<string, unknown> => streamChatMock.mock.calls[0][1] as Record<string, unknown>;
const turnId = (): string => String(streamBody().requestId);

beforeEach(() => {
  vi.clearAllMocks();
  apiGet.mockResolvedValue({});
  apiPost.mockImplementation((url: string) => (url === '/api/ai/chat'
    ? Promise.resolve({ protocolVersion: 2, reply: 'ANSWER', sessionId: 10, stateDurable: true, persistenceStatus: 'saved' })
    : Promise.resolve({})));
  streamChatMock.mockImplementation((_path: string, _body: unknown, hooks: StreamCallbacks) => {
    callbacks = hooks;
    return Promise.resolve(() => undefined);
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useAiChatTurn 请求体', () => {
  it('v2 单轮输入：不拼 system、不回传历史，页面正文走 pageContext', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({
        sessionId: 10,
        userText: '解释二分查找',
        pageContext: { kind: 'note', pageId: 5, markdown: '页面正文' },
        retrievalOptions: { enabled: true, scope: 'current', notebookId: 7 },
        attachments: [{
          clientId: 'c1', kind: 'image', name: '截图.png', mimeType: 'image/png',
          dataBase64: 'data:image/png;base64,AAAA'
        }]
      });
    });

    expect(streamChatMock).toHaveBeenCalledTimes(1);
    const body = streamBody();
    expect(body.protocolVersion).toBe(2);
    expect(body.messages).toBeUndefined();
    expect(body.input).toEqual({
      role: 'user',
      userText: '解释二分查找',
      attachments: [{ clientId: 'c1', kind: 'image', name: '截图.png', mimeType: 'image/png', dataBase64: 'AAAA' }]
    });
    expect(body.pageContext).toEqual({ kind: 'note', pageId: 5, markdown: '页面正文' });
    expect(body.retrievalOptions).toEqual({ enabled: true, scope: 'current', notebookId: 7 });
    expect(body.memoryOptions).toEqual({ use: true });
    expect(isUuidV7(turnId())).toBe(true);
    expect(turn.result.current.requestId).toBe(turnId());
  });

  it('没带页面上下文与检索时不塞空对象，请求开关只在需要时出现', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '你好' });
    });

    const body = streamBody();
    expect(body.pageContext).toBeUndefined();
    expect(body.retrievalOptions).toBeUndefined();
  });

  it('进行中再点发送不产生第二个身份（一次逻辑发送只有一个 requestId）', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '第一下' });
    });
    const id = turnId();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '第二下' });
    });

    expect(streamChatMock).toHaveBeenCalledTimes(1);
    expect(turnId()).toBe(id);
  });

  it('关掉流式也走同一份 v2 请求体，不再拼旧 messages 数组', async () => {
    const turn = renderTurn({ sessionId: 10, streamEnabled: false });
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '直接问' });
    });

    expect(streamChatMock).toHaveBeenCalledWith('/api/ai/chat/stream', expect.objectContaining({
      protocolVersion: 2,
      input: { role: 'user', userText: '直接问' }
    }), expect.any(Object));
    expect(apiPost).not.toHaveBeenCalled();
    act(() => callbacks!.onDone(donePayload('ANSWER')));
    expect(events.at(-1)).toMatchObject({ type: 'result', reply: 'ANSWER' });
  });
});

describe('useAiChatTurn 停止与取消', () => {
  it('停止后只读确认工具结果，迟到确认不会结束后来开始的新一轮', async () => {
    vi.useFakeTimers();
    const turn = renderTurn();
    const activity = { sequence: 1, toolName: 'create_memory' as const, state: 'RUNNING' as const,
      outcome: 'UNKNOWN', startedAt: '2026-11-08T01:00:00.000Z', elapsedMs: null, count: 0, availability: null, incomplete: false };
    await act(async () => { turn.result.current.send({ sessionId: 10, userText: '记住' }); });
    const oldId = turnId();
    apiGet.mockResolvedValueOnce({ requestId: oldId, status: 'RUNNING', active: true, toolActivities: [activity] })
      .mockResolvedValueOnce({ requestId: oldId, status: 'ABORTED', active: false,
        toolActivities: [{ ...activity, state: 'FINISHED', outcome: 'CREATED', elapsedMs: 60, count: 1 }] });
    await act(async () => {
      callbacks!.onToolActivity!({ requestId: oldId, phase: 'tools', toolActivities: [activity] });
      turn.result.current.stop();
      await Promise.resolve();
    });
    await act(async () => { turn.result.current.send({ sessionId: 10, userText: '下一轮' }); });
    await act(async () => { await vi.advanceTimersByTimeAsync(450); });
    expect(turn.result.current.busy).toBe(true);
    expect(turn.result.current.requestId).not.toBe(oldId);
    expect(events.filter((event) => event.type === 'reconciled').at(-1)).toMatchObject({
      requestId: oldId, status: { toolActivities: [{ state: 'FINISHED', outcome: 'CREATED' }] }
    });
    expect(apiPost.mock.calls.filter((call) => call[0] === '/api/ai/chat')).toHaveLength(0);
    expect(streamChatMock).toHaveBeenCalledTimes(2);
  });
  it('停止掐断连接并带着本轮 requestId 调取消接口', async () => {
    const abort = vi.fn();
    streamChatMock.mockImplementation(() => Promise.resolve(abort));
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '太长不要了' });
      await Promise.resolve();
    });
    const id = turnId();

    act(() => turn.result.current.stop());

    expect(abort).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith(`/api/ai/turns/${id}/cancel`, {}));
    expect(turn.result.current.busy).toBe(false);
  });

  it('取消接口失败也不影响界面收口（前端断开已成事实）', async () => {
    streamChatMock.mockImplementation(() => Promise.resolve(vi.fn()));
    apiPost.mockRejectedValue(new ApiError('TURN_NOT_FOUND', '没有这个轮次', 404));
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '还没登记就停了' });
      await Promise.resolve();
    });

    act(() => turn.result.current.stop());

    expect(turn.result.current.busy).toBe(false);
    expect(events).toHaveLength(0);
  });
});

describe('useAiChatTurn 失败出口', () => {
  it('确定没开始的早失败：查状态确认查无此轮后，用同一个 requestId 走非流式补完', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '补完我' });
    });
    const id = turnId();
    apiGet.mockRejectedValue(new ApiError('TURN_NOT_FOUND', '没有这个轮次', 404));

    await act(async () => {
      callbacks!.onError({ message: '请求失败（404）', partial: false });
      await Promise.resolve();
    });

    expect(apiGet).toHaveBeenCalledWith(`/api/ai/turns/${id}`);
    expect(apiPost).toHaveBeenCalledWith('/api/ai/chat', expect.objectContaining({ requestId: id }));
    expect(events.at(-1)).toMatchObject({ type: 'result', reply: 'ANSWER' });
  });

  it('已经产出内容后报错：绝不发第二个请求，也不再查状态，只如实提示', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '半句就断了' });
    });

    act(() => {
      callbacks!.onMetadata!({ requestId: turnId() });
      callbacks!.onText('已经生成的半句');
      callbacks!.onError({ message: 'AI 响应读取失败：连接被重置', partial: true, stateDurable: false, persistenceStatus: 'failed' });
    });

    expect(apiPost.mock.calls.filter((call) => call[0] === '/api/ai/chat')).toHaveLength(0);
    expect(apiGet).not.toHaveBeenCalled();
    expect(events.at(-1)).toMatchObject({
      type: 'failure',
      message: 'AI 响应读取失败：连接被重置',
      view: { stateDurable: false, persistenceStatus: 'failed' }
    });
    expect(turn.result.current.busy).toBe(false);
  });

  it('会话被占住（SESSION_BUSY × TURN_NOT_FOUND 互相成立）：补完只有一次，不陷入请求风暴', async () => {
    const macrotask = (): Promise<void> => new Promise((resolve) => {
      window.setTimeout(resolve, 0);
    });
    let chatPosts = 0;
    let turnGets = 0;
    apiPost.mockImplementation((url: string) => {
      if (url !== '/api/ai/chat') return Promise.resolve({});
      chatPosts += 1;
      return macrotask().then(() => Promise.reject(new ApiError('SESSION_BUSY', '该会话已有一轮正在进行', 409)));
    });
    apiGet.mockImplementation((url: string) => {
      if (url.startsWith('/api/ai/turns/')) {
        turnGets += 1;
        return macrotask().then(() => Promise.reject(new ApiError('TURN_NOT_FOUND', '没有这个轮次', 404)));
      }
      return Promise.resolve({});
    });
    const turn = renderTurn({ sessionId: 10, streamEnabled: false });

    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '被占住的会话' });
      callbacks!.onError({ message: '该会话已有一轮正在进行', code: 'SESSION_BUSY' });
      await new Promise((resolve) => {
        window.setTimeout(resolve, 150);
      });
    });

    expect(chatPosts).toBe(1);
    expect(turnGets).toBe(2);
    expect(events.at(-1)).toMatchObject({ type: 'failure', message: '该会话已有一轮正在进行' });
    expect(turn.result.current.busy).toBe(false);
  });

  it('流式入口的同一场景：回退额度算一次，POST 只有一次', async () => {
    const turn = renderTurn();
    apiPost.mockImplementation((url: string) => (url === '/api/ai/chat'
      ? Promise.reject(new ApiError('SESSION_BUSY', '该会话已有一轮正在进行', 409))
      : Promise.resolve({})));
    apiGet.mockRejectedValue(new ApiError('TURN_NOT_FOUND', '没有这个轮次', 404));

    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '断了又忙' });
    });
    await act(async () => {
      callbacks!.onError({ message: '请求失败（404）', partial: false });
      await Promise.resolve();
    });

    expect(apiPost.mock.calls.filter((call) => call[0] === '/api/ai/chat')).toHaveLength(1);
    expect(events.at(-1)).toMatchObject({ type: 'failure', message: '该会话已有一轮正在进行' });
  });

  it('非流式撞上同一身份仍在进行：改查终态，不报成失败也不重发', async () => {
    const turn = renderTurn({ sessionId: 10, streamEnabled: false });
    apiPost.mockRejectedValue(new ApiError('SESSION_BUSY', '相同 requestId 的轮次仍在进行', 409));
    apiGet.mockResolvedValue({ status: 'RUNNING', active: true });

    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '重放我' });
      callbacks!.onError({ message: '相同 requestId 的轮次仍在进行', code: 'SESSION_BUSY' });
      await Promise.resolve();
    });

    expect(apiGet).toHaveBeenCalled();
    expect(events.find((event) => event.type === 'status')).toMatchObject({ type: 'status', status: { status: 'RUNNING' } });
  });
});

describe('useAiChatTurn 无 done 的 EOF', () => {
  it('连接读完没有 done：查到的终态交给界面，不冒充完成', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '读到一半没了' });
    });
    apiGet.mockResolvedValue({ status: 'TRUNCATED', active: false, persistenceStatus: 'saved', stateDurable: true });

    await act(async () => {
      callbacks!.onNoDone!({ requestId: turnId(), partial: true });
      await Promise.resolve();
    });

    expect(apiGet).toHaveBeenCalledWith(`/api/ai/turns/${turnId()}`);
    expect(events.at(-1)).toMatchObject({
      type: 'status',
      status: { status: 'TRUNCATED' },
      message: '生成中断，已收到的内容不是完整结果'
    });
    expect(turn.result.current.busy).toBe(false);
  });

  it('RUNNING 但句柄已不在 = 孤儿轮次，界面要说"没有结果"', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '孤儿' });
    });
    apiGet.mockResolvedValue({ status: 'RUNNING', active: false });

    await act(async () => {
      callbacks!.onNoDone!({ requestId: turnId(), partial: false });
      await Promise.resolve();
    });

    expect(events.at(-1)).toMatchObject({
      type: 'status',
      status: { status: 'RUNNING', active: false },
      message: '这一轮已经中断，没有收到结果'
    });
  });

  it('存储降级读不到状态时按节奏退避，耗尽后如实报未知', async () => {
    vi.useFakeTimers();
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '存储坏了' });
    });
    apiGet.mockRejectedValue(new ApiError('STORAGE_UNAVAILABLE', '轮次状态暂时读不到', 503));

    await act(async () => {
      callbacks!.onNoDone!({ requestId: turnId(), partial: false });
      await Promise.resolve();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });

    expect(apiGet).toHaveBeenCalledTimes(4);
    expect(events.at(-1)).toMatchObject({ type: 'unresolved', message: '轮次状态暂时读不到' });
    expect(turn.result.current.busy).toBe(false);
  });
});

describe('useAiChatTurn 会话隔离', () => {
  it('切到别的会话后，旧轮的增量与结果都不再进界面，但状态照旧收干净', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '旧会话的话' });
    });

    turn.rerender({ sessionId: 11, streamEnabled: true });

    act(() => callbacks!.onText('late'));
    await act(async () => {
      callbacks!.onDone(donePayload('late answer'));
      await Promise.resolve();
    });

    expect(events).toHaveLength(0);
    expect(turn.result.current.busy).toBe(false);
    expect(turn.result.current.requestId).toBeNull();
  });

  it('done 把统一结果整块交给界面（徽标与检查器不必再发第二次请求）', async () => {
    const turn = renderTurn();
    await act(async () => {
      turn.result.current.send({ sessionId: 10, userText: '正常一问' });
    });

    await act(async () => {
      callbacks!.onDone({
        ...donePayload('答复'),
        reasoning: '想了一步',
        requestId: turnId(),
        sessionId: 10,
        status: 'COMPLETED',
        stateDurable: true,
        persistenceStatus: 'saved',
        memoryContext: { provided: [{ memoryId: 'm-1', version: 2 }] }
      });
      await Promise.resolve();
    });

    expect(events.at(-1)).toMatchObject({
      type: 'result',
      reply: '答复',
      reasoning: '想了一步',
      view: { status: 'COMPLETED', memoryContext: { provided: [{ memoryId: 'm-1', version: 2 }] } }
    });
    expect(turn.result.current.busy).toBe(false);
  });
});
