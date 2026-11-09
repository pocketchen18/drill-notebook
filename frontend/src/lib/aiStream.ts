import { ApiError } from './api';
import { readToolActivities } from './toolActivity';
import type { ToolActivity, ToolPhase } from './toolActivity';

/** SSE 流式聊天客户端：解析后端 /api/ai/chat/stream 的 text|reasoning|done|error 事件。 */

/** §4.2 的"统一结果"里与本轮上下文有关的几块，done 与 metadata 共用同一份字段集。 */
export interface MemoryReference {
  memoryId: string;
  version: number;
}

export interface MemoryContextSnapshot {
  provided: MemoryReference[];
  estimatedTokens?: number;
  vaultRevision?: number | null;
}

export interface ContextUsageSnapshot {
  estimated?: boolean;
  inputBudget?: number;
  estimatedInputTokens?: number;
  recentTurnsIncluded?: number;
  summaryThroughTurnId?: number | null;
  summaryHasGaps?: boolean;
  notices?: string[];
}

/** done 里的 contextAudit 只是摘要入口；完整账本走 GET /turns/{requestId}/context。 */
export interface ContextAuditSummary {
  schemaVersion?: number;
  planId?: string;
  dispatchState?: string;
  available?: boolean;
  reason?: string;
}

export interface UsageSnapshot {
  source?: string;
  inputTokens?: number | null;
  outputTokens?: number | null;
  cacheReadTokens?: number | null;
  cacheWriteTokens?: number | null;
}

export interface ChatResultView {
  supplements?: import('./chatQueue').ChatSupplement[];
  finishReason?: string;
  toolActivities?: ToolActivity[];
  protocolVersion?: number;
  requestId?: string;
  sessionId?: number;
  status?: string;
  stateDurable?: boolean;
  persistenceStatus?: string;
  replayed?: boolean;
  citations?: unknown[];
  memoryContext?: MemoryContextSnapshot;
  contextUsage?: ContextUsageSnapshot;
  contextAudit?: ContextAuditSummary;
  usage?: UsageSnapshot;
  /** 只有 metadata 帧在顶层带这两键（done 把它们放进 contextAudit），计划换了要靠它们对比。 */
  planId?: string;
  dispatchState?: string;
}

export interface StreamDonePayload extends ChatResultView {
  reply: string;
  /** 思考链全量正文（后端 done 事件可在 data 中携带 reasoning 字段）。 */
  reasoning: string;
}

/** metadata 帧携带的是"本轮到目前为止的计划态"，可能与 done 的最终 planId 不同（§4.2）。 */
export interface StreamMetadata extends ChatResultView {
  requestId: string;
}

export interface StreamErrorInfo {
  toolActivities?: ToolActivity[];
  message: string;
  requestId?: string;
  sessionId?: number;
  code?: string;
  /** 后端是否已经产出过增量：产出过的轮次不能自动重发（§7.3）。 */
  partial?: boolean;
  persistenceStatus?: string;
  stateDurable?: boolean;
}

export interface StreamCallbacks {
  onToolActivity?: (event: { requestId: string; toolActivities: ToolActivity[]; phase: ToolPhase }) => void;
  onText: (delta: string) => void;
  onReasoning: (delta: string) => void;
  /** 发送前的元数据（requestId + 计划态）：拿到后前端才能在"停止"时调用取消接口。 */
  onMetadata?: (metadata: StreamMetadata) => void;
  onDone: (payload: StreamDonePayload) => void;
  onError: (failure: StreamErrorInfo) => void;
  /**
   * 连接正常读完却没有 done/error 帧（上游被截、进程被杀、后端没写完）。
   * §4.2："无 done 就 EOF 查状态"——调用方要用 requestId 去查轮次终态，不能把占位当完成。
   */
  onNoDone?: (info: { requestId?: string; partial: boolean }) => void;
}

const RESULT_VIEW_KEYS: Array<keyof ChatResultView> = [
  'protocolVersion', 'requestId', 'sessionId', 'status', 'stateDurable', 'persistenceStatus', 'replayed',
  'citations', 'memoryContext', 'contextUsage', 'contextAudit', 'usage', 'planId', 'dispatchState', 'finishReason', 'supplements'
];

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? value as Record<string, unknown> : null;
}

/** 统一结果的透传：只挑认识的键，不认识的一律忽略（§4.2"未知事件兼容忽略"）。 */
function resultView(payload: Record<string, unknown> | null): ChatResultView {
  const view: Record<string, unknown> = {};
  if (payload) {
    for (const key of RESULT_VIEW_KEYS) {
      if (payload[key] !== undefined) view[key] = payload[key];
    }
    if (Array.isArray(payload.toolActivities)) view.toolActivities = readToolActivities(payload.toolActivities);
  }
  return view as ChatResultView;
}

function textField(payload: Record<string, unknown> | null, name: string): string {
  const value = payload?.[name];
  return value === undefined || value === null ? '' : String(value);
}

/**
 * 发起流式聊天。返回一个 abort 函数；调用后会中止 fetch（正在进行的读取抛 AbortError，但不会触发 onError 回调）。
 * 事件帧格式：event:<name>\ndata:<json>\n\n
 */
export async function streamChat(path: string, body: unknown, callbacks: StreamCallbacks): Promise<() => void> {
  const controller = new AbortController();
  let aborted = false;

  const run = async (): Promise<void> => {
    const base = (window.api && await window.api.backend.getBaseUrl().catch(() => 'http://127.0.0.1:18081')) || 'http://127.0.0.1:18081';
    let response: Response;
    try {
      response = await fetch(`${base}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(body),
        signal: controller.signal
      });
    } catch (error) {
      if (aborted) return;
      callbacks.onError({ message: error instanceof Error ? error.message : '连接失败', partial: false });
      return;
    }
    if (!response.ok || !response.body) {
      let message = `请求失败（${response.status}）`;
      let code: string | undefined;
      try {
        const payload = await response.json();
        if (payload && typeof payload === 'object' && 'message' in payload && typeof payload.message === 'string') {
          message = payload.message;
        }
        if (payload && typeof payload === 'object' && 'code' in payload && typeof payload.code === 'string') {
          code = payload.code;
        }
      } catch {
        // 非 JSON 错误体，用默认文案
      }
      // 连不上或状态不对：这一轮压根没开始产出，partial 一律 false
      callbacks.onError({ message, code, partial: false });
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    // 本轮身份 / 是否产出过增量 / 是否已收口：EOF 时既没 done 也没 error 就要靠它们去查轮次状态（§4.2）
    let lastRequestId: string | undefined;
    let produced = false;
    let settled = false;
    const handleFrame = (eventName: string, data: string): void => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(data);
      } catch {
        parsed = undefined;
      }
      const payload = asRecord(parsed);
      const text = textField(payload, 'text');
      if (eventName === 'text' || eventName === 'reasoning' || eventName === 'tool_activity') produced = true;
      else if (eventName === 'done' || eventName === 'error') settled = true;
      if (eventName === 'text') callbacks.onText(text);
      else if (eventName === 'reasoning') callbacks.onReasoning(text);
      else if (eventName === 'metadata') {
        // 没有 requestId 的 metadata 只是计划态，不构成"这一轮的身份"，不能拿它去调取消接口
        const requestId = textField(payload, 'requestId');
        if (requestId) {
          lastRequestId = requestId;
          callbacks.onMetadata?.({ ...resultView(payload), requestId });
        }
      } else if (eventName === 'tool_activity') {
        const requestId = textField(payload, 'requestId');
        if (requestId) {
          lastRequestId = requestId;
          callbacks.onToolActivity?.({ requestId, toolActivities: readToolActivities(payload?.toolActivities),
            phase: payload?.phase === 'answer' ? 'answer' : 'tools' });
        }
      } else if (eventName === 'done') {
        callbacks.onDone({ ...resultView(payload), reply: textField(payload, 'reply'),
          reasoning: textField(payload, 'reasoning') });
      } else if (eventName === 'error') {
        const view = resultView(payload);
        const code = textField(payload, 'code');
        callbacks.onError({
          message: textField(payload, 'message') || '流式请求失败',
          requestId: view.requestId,
          sessionId: view.sessionId,
          code: code || undefined,
          partial: payload?.partial === true,
          persistenceStatus: view.persistenceStatus,
          stateDurable: view.stateDurable,
          toolActivities: view.toolActivities
        });
      }
    };
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let separator = buffer.indexOf('\n\n');
        while (separator >= 0) {
          const frame = buffer.slice(0, separator);
          buffer = buffer.slice(separator + 2);
          let eventName = '';
          const dataLines: string[] = [];
          for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) eventName = line.slice(6).trim();
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
          }
          if (dataLines.length) handleFrame(eventName, dataLines.join('\n'));
          separator = buffer.indexOf('\n\n');
        }
      }
    } catch (error) {
      if (!aborted) {
        // 读到一半断了：这一轮已经产出过多少要照实说，调用方据此决定能不能自动回退（§7.3）
        settled = true;
        callbacks.onError({ message: error instanceof Error ? error.message : '流式连接中断',
          requestId: lastRequestId, partial: produced });
      }
    } finally {
      // 没有 done 也没有 error 就到 EOF：不能把占位当完成，交给调用方去查这一轮的终态（§4.2）
      if (!aborted && !settled) callbacks.onNoDone?.({ requestId: lastRequestId, partial: produced });
    }
  };

  void run();
  return () => {
    aborted = true;
    controller.abort();
  };
}

export { ApiError };
