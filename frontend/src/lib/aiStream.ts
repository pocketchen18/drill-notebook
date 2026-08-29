import { ApiError } from './api';

/** SSE 流式聊天客户端：解析后端 /api/ai/chat/stream 的 text|reasoning|done|error 事件。 */

export interface StreamDonePayload {
  reply: string;
  /** 思考链全量正文（后端 done 事件可在 data 中携带 reasoning 字段）。 */
  reasoning: string;
}

export interface StreamCallbacks {
  onText: (delta: string) => void;
  onReasoning: (delta: string) => void;
  onDone: (payload: StreamDonePayload) => void;
  onError: (message: string) => void;
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
      callbacks.onError(error instanceof Error ? error.message : '连接失败');
      return;
    }
    if (!response.ok || !response.body) {
      let message = `请求失败（${response.status}）`;
      try {
        const payload = await response.json();
        if (payload && typeof payload === 'object' && 'message' in payload && typeof payload.message === 'string') {
          message = payload.message;
        }
      } catch {
        // 非 JSON 错误体，用默认文案
      }
      callbacks.onError(message);
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    const handleFrame = (eventName: string, data: string): void => {
      let payload: unknown;
      try {
        payload = JSON.parse(data);
      } catch {
        payload = undefined;
      }
      const text = payload && typeof payload === 'object' && 'text' in payload ? String((payload as { text: unknown }).text) : '';
      if (eventName === 'text') callbacks.onText(text);
      else if (eventName === 'reasoning') callbacks.onReasoning(text);
      else if (eventName === 'done') {
        const reply = payload && typeof payload === 'object' && 'reply' in payload ? String((payload as { reply: unknown }).reply) : '';
        const reasoning = payload && typeof payload === 'object' && 'reasoning' in payload ? String((payload as { reasoning: unknown }).reasoning) : '';
        callbacks.onDone({ reply, reasoning });
      } else if (eventName === 'error') {
        const message = payload && typeof payload === 'object' && 'message' in payload ? String((payload as { message: unknown }).message) : '流式请求失败';
        callbacks.onError(message);
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
        callbacks.onError(error instanceof Error ? error.message : '流式连接中断');
      }
    }
  };

  void run();
  return () => {
    aborted = true;
    controller.abort();
  };
}

export { ApiError };
