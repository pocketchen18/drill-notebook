import { useEffect, useRef, useState } from 'react';
import { ApiError, get, post } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import { streamChat } from '../lib/aiStream';
import type { ChatResultView, StreamErrorInfo } from '../lib/aiStream';
import { generateUuidV7 } from '../lib/uuidV7';
import type { RetrievalOptions } from '../lib/types';
import { readToolActivities } from '../lib/toolActivity';
import type { ToolActivity, ToolPhase } from '../lib/toolActivity';

/**
 * 聊天轮次的唯一出口（执行方案 §12、contracts §4.1/§4.2/§4.5）。
 *
 * <p>一次逻辑发送只有一个 requestId：流式、非流式回退、取消、查状态共用同一份请求体与同一个身份，
 * 服务端按它做幂等，前端不再有"重发一次变成两轮"的空间。</p>
 */

/** v2 附件项：文本走 text，图片走去掉 data: 前缀的 base64（contracts §4.3）。 */
export interface TurnAttachment {
  clientId: string;
  kind: 'text' | 'image';
  name: string;
  mimeType: string;
  text?: string;
  dataBase64?: string;
}

export interface TurnPageContext {
  kind: string;
  pageId?: number;
  markdown?: string;
}

export interface TurnInput {
  sessionId: number;
  userText: string;
  attachments?: TurnAttachment[];
  pageContext?: TurnPageContext;
  retrievalOptions?: RetrievalOptions;
}

/** GET /turns/{requestId} 的可见事实（§4.5）：界面只能拿这些字段描述这一轮。 */
export interface TurnStatusView {
  toolActivities?: ToolActivity[];
  requestId?: string;
  sessionId?: number;
  status?: string;
  active?: boolean;
  stateDurable?: boolean;
  persistenceStatus?: string;
  finishReason?: string;
  contentDeleted?: boolean;
}

export type TurnEvent =
  | { type: 'activity'; requestId: string; activities: ToolActivity[]; phase: ToolPhase }
  | { type: 'reconciled'; requestId: string; status: TurnStatusView }
  | { type: 'begun'; requestId: string }
  | { type: 'text'; requestId: string; delta: string }
  | { type: 'reasoning'; requestId: string; delta: string }
  | { type: 'result'; requestId: string; reply: string; reasoning: string; view: ChatResultView }
  | { type: 'failure'; requestId: string; message: string; view: ChatResultView }
  // 连接没了但后端有这一轮：界面按服务端终态如实显示，不许当完成
  | { type: 'status'; requestId: string; status: TurnStatusView; message?: string }
  // 连状态都读不到（存储降级到底）：只能说"这一轮结果未知"
  | { type: 'unresolved'; requestId: string; message: string };

interface ActiveTurn {
  requestId: string;
  sessionId: number;
  body: Record<string, unknown>;
  /** 收到过 metadata 帧：这一轮确定已在后端登记。 */
  begun: boolean;
  /** 收到过任何 text/reasoning：已产出内容，绝不能自动重发。 */
  produced: boolean;
  toolObserved: boolean;
  /**
   * 是否已经用过那唯一一次"确认没开始 → 交给非流式补完"的回退。
   * `SESSION_BUSY` 与 `TURN_NOT_FOUND` 会稳定同时成立（会话被另一轮的活跃句柄占住时，
   * 409 是"会话已有一轮正在进行"，而我们这个新 ID 在库里必然查无），
   * 没有这枚标志位就是 POST↔GET 互递归的请求风暴。
   */
  fallbackTried: boolean;
  abort: (() => void) | null;
}

/** 状态读不到时的退避节奏；耗尽后如实报未知，不猜终态。 */
const STATUS_BACKOFF_MS = [400, 1200, 3000];

/** 请求体只在这里拼一次：流式与非流式回退复用同一份，才撞得到服务端的同一身份上。 */
function buildTurnBody(requestId: string, input: TurnInput): Record<string, unknown> {
  const attachments = (input.attachments ?? []).map((item) => ({
    ...item,
    ...(item.kind === 'image' && item.dataBase64
      ? { dataBase64: item.dataBase64.replace(/^data:[^,]*,/, '') }
      : {})
  }));
  const pageContext = input.pageContext;
  return {
    protocolVersion: 2,
    requestId,
    sessionId: input.sessionId,
    input: { role: 'user', userText: input.userText, ...(attachments.length ? { attachments } : {}) },
    ...(pageContext
      ? {
        pageContext: {
          kind: pageContext.kind,
          ...(pageContext.pageId !== undefined ? { pageId: pageContext.pageId } : {}),
          ...(pageContext.markdown ? { markdown: pageContext.markdown } : {})
        }
      }
      : {}),
    ...(input.retrievalOptions ? { retrievalOptions: input.retrievalOptions } : {}),
    memoryOptions: { use: true }
  };
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

export interface UseAiChatTurnOptions {
  /** 当前会话：切走后这一轮的回调一律丢弃（后端照常收尾，界面不再往新会话里写）。 */
  sessionId?: number;
  streamEnabled: boolean;
  onEvent: (event: TurnEvent) => void;
}

export function useAiChatTurn({ sessionId, onEvent }: UseAiChatTurnOptions): {
  send: (input: TurnInput) => void;
  stop: () => void;
  busy: boolean;
  requestId: string | null;
  activeSessionId?: number;
} {
  const activeRef = useRef<ActiveTurn | null>(null);
  const sessionRef = useRef(sessionId);
  const emitRef = useRef(onEvent);
  const mountedRef = useRef(true);
  const [busy, setBusy] = useState(false);
  const [requestId, setRequestId] = useState<string | null>(null);

  useEffect(() => {
    sessionRef.current = sessionId;
  }, [sessionId]);
  useEffect(() => {
    emitRef.current = onEvent;
  }, [onEvent]);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; activeRef.current?.abort?.(); };
  }, []);

  const isCurrent = (turn: ActiveTurn): boolean => activeRef.current === turn;

  // 切会话后不再把旧轮的增量投进新会话的列表；终态清理照旧执行。
  const emit = (turn: ActiveTurn, event: TurnEvent): void => {
    if (!isCurrent(turn) || turn.sessionId !== sessionRef.current) return;
    emitRef.current(event);
  };

  const finish = (turn: ActiveTurn): void => {
    if (!isCurrent(turn)) return;
    activeRef.current = null;
    setBusy(false);
    setRequestId(null);
  };

  /** 非流式通道：与流式共用同一个 requestId 与同一份请求体。 */
  const runPlain = async (turn: ActiveTurn, note?: string): Promise<void> => {
    try {
      const view = await post<ChatResultView & { reply?: string; reasoning?: string }>('/api/ai/chat', turn.body);
      emit(turn, {
        type: 'result',
        requestId: turn.requestId,
        reply: view.reply ?? '',
        reasoning: view.reasoning ?? '',
        view
      });
      finish(turn);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'SESSION_BUSY') {
        // 同一身份仍在进行：这不是第二次请求，去查它的终态（其它 409 是身份冲突，如实报错）
        await lookupStatus(turn, error.message);
        return;
      }
      const message = friendlyMessage(error, 'AI 对话失败，请稍后重试');
      emit(turn, {
        type: 'failure',
        requestId: turn.requestId,
        message: note ? `${note}；备用通道也不通：${message}` : message,
        view: {}
      });
      finish(turn);
    }
  };

  /** 查这一轮的终态：读不到就按节奏退避，耗尽后如实报未知（§4.2"无 done 就 EOF 查状态"）。 */
  async function lookupStatus(turn: ActiveTurn, message?: string): Promise<void> {
    let lastError = '';
    for (const delay of [0, ...STATUS_BACKOFF_MS]) {
      if (delay > 0) await wait(delay);
      if (!isCurrent(turn)) return;
      try {
        const status = await get<TurnStatusView>(`/api/ai/turns/${turn.requestId}`);
        if (!isCurrent(turn)) return;
        emit(turn, {
          type: 'status',
          requestId: turn.requestId,
          status,
          message: status.status === 'RUNNING' && status.active === false
            ? '这一轮已经中断，没有收到结果'
            : message
        });
        finish(turn);
        if (status.status === 'RUNNING' && status.active) void reconcile(turn);
        return;
      } catch (error) {
        if (error instanceof ApiError && error.code === 'TURN_NOT_FOUND') {
          if (turn.fallbackTried || turn.begun || turn.produced) {
            // 回退额度用完了还是"查无此轮 + 会话忙"：那就是别人占着这个会话，如实报，绝不再试第二次
            emit(turn, { type: 'failure', requestId: turn.requestId, message: message || '该会话已有一轮正在进行，请稍后再试', view: {} });
            finish(turn);
            return;
          }
          // 查无此轮：后端确定没开始，可以把同一个 requestId 交给非流式通道补完（只补这一次）
          turn.fallbackTried = true;
          await runPlain(turn, message);
          return;
        }
        lastError = error instanceof Error ? error.message : '轮次状态暂时读不到';
      }
    }
    emit(turn, { type: 'unresolved', requestId: turn.requestId, message: lastError || '轮次状态暂时读不到' });
    finish(turn);
  }

  async function runStream(turn: ActiveTurn): Promise<void> {
    const abort = await streamChat('/api/ai/chat/stream', turn.body, {
      onText: (delta) => {
        turn.produced = true;
        emit(turn, { type: 'text', requestId: turn.requestId, delta });
      },
      onReasoning: (delta) => {
        turn.produced = true;
        emit(turn, { type: 'reasoning', requestId: turn.requestId, delta });
      },
      onMetadata: () => {
        turn.begun = true;
        emit(turn, { type: 'begun', requestId: turn.requestId });
      },
      onToolActivity: (event) => {
        if (event.requestId !== turn.requestId) return;
        turn.begun = true;
        turn.produced = true;
        turn.toolObserved = true;
        emit(turn, { type: 'activity', requestId: turn.requestId, activities: event.toolActivities, phase: event.phase });
      },
      onDone: (payload) => {
        emit(turn, {
          type: 'result',
          requestId: turn.requestId,
          reply: payload.reply,
          reasoning: payload.reasoning,
          view: payload
        });
        finish(turn);
      },
      onError: (failure: StreamErrorInfo) => {
        if (turn.begun || turn.produced || failure.partial) {
          // 这一轮真的开始过：error 帧已带 persistenceStatus/stateDurable，如实报，绝不重发
          emit(turn, {
            type: 'failure',
            requestId: turn.requestId,
            message: failure.message,
            view: {
              requestId: failure.requestId,
              sessionId: failure.sessionId,
              stateDurable: failure.stateDurable,
              persistenceStatus: failure.persistenceStatus,
              toolActivities: failure.toolActivities
            }
          });
          finish(turn);
          if (turn.toolObserved) void reconcile(turn);
          return;
        }
        void lookupStatus(turn, failure.message);
      },
      onNoDone: (info) => {
        void lookupStatus(turn, info.partial
          ? '生成中断，已收到的内容不是完整结果'
          : '这一轮没有收到结束帧，正在向服务端确认结果');
      }
    });
    if (isCurrent(turn)) turn.abort = abort;
    else abort();
  }

  const send = (input: TurnInput): void => {
    if (activeRef.current) return;
    const id = generateUuidV7(Date.now());
    const turn: ActiveTurn = {
      requestId: id,
      sessionId: input.sessionId,
      body: buildTurnBody(id, input),
      begun: false,
      produced: false,
      toolObserved: false,
      fallbackTried: false,
      abort: null
    };
    activeRef.current = turn;
    setBusy(true);
    setRequestId(id);
    // 正文是否分段由模型配置决定；状态事件始终使用 SSE，关闭正文流式仍能看到真实工具进度。
    void runStream(turn);
  };

  /** 中断后只读同一轮：确认在途写入的结果，不重新发送，也不干扰用户后来开始的新一轮。 */
  async function reconcile(turn: ActiveTurn): Promise<void> {
    for (const delay of [0, ...STATUS_BACKOFF_MS]) {
      if (delay) await wait(delay);
      if (!mountedRef.current || sessionRef.current !== turn.sessionId) return;
      try {
        const status = await get<TurnStatusView>(`/api/ai/turns/${turn.requestId}`);
        if (!mountedRef.current || sessionRef.current !== turn.sessionId) return;
        emitRef.current({ type: 'reconciled', requestId: turn.requestId,
          status: { ...status, toolActivities: readToolActivities(status.toolActivities) } });
        if (status.status !== 'RUNNING' || status.active === false) return;
      } catch { /* 仍未知：保留界面的未确认状态，绝不重放工具。 */ }
    }
  }

  const stop = (): void => {
    const turn = activeRef.current;
    if (!turn) return;
    turn.abort?.();
    // 只断前端连接时，后端要等下一次写失败或总期限才察觉；带着本轮身份主动取消，会话立刻可用。
    void post(`/api/ai/turns/${turn.requestId}/cancel`, {}).catch(() => undefined).then(() => reconcile(turn));
    activeRef.current = null;
    setBusy(false);
    setRequestId(null);
  };

  return { send, stop, busy, requestId, activeSessionId: activeRef.current?.sessionId };
}
