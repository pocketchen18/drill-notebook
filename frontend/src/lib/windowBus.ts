/**
 * 主窗口与 AI 助手窗口之间的通道。两个窗口各有自己的渲染进程和查询缓存，
 * 只通过这里交换约定的几类消息；桌面应用走主进程中转，浏览器调试时退回 BroadcastChannel。
 */
import type { AiPageContext } from '../stores/uiStore';

export type BusMessage =
  | { type: 'page-context'; context: AiPageContext }
  | { type: 'context-request' }
  | { type: 'navigate'; route: string }
  | { type: 'tool-mutation'; row: unknown }
  | { type: 'invalidate'; keys: unknown[][] }
  | { type: 'assistant-activity'; running: number; pending: number };

export interface AssistantWindowStatus { visible: boolean; running: number; pending: number }

const ASSISTANT_HASH = '#/assistant';
const CHANNEL = 'drill-notebook-window-bus';

/** 当前渲染进程是否为助手窗口；由主进程加载入口时的 hash 决定，运行期间不变。 */
export function isAssistantWindow(): boolean {
  try { return window.location.hash.startsWith(ASSISTANT_HASH); } catch { return false; }
}

let channel: BroadcastChannel | null | undefined;
function fallbackChannel(): BroadcastChannel | null {
  if (channel === undefined) {
    try { channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL); } catch { channel = null; }
  }
  return channel;
}

export function publish(message: BusMessage): void {
  try {
    if (window.api?.bus) void window.api.bus.publish(message);
    else fallbackChannel()?.postMessage(message);
  } catch { /* 另一个窗口不在时消息直接丢弃 */ }
}

export function subscribe(listener: (message: BusMessage) => void): () => void {
  const accept = (raw: unknown): void => {
    if (raw && typeof raw === 'object' && typeof (raw as { type?: unknown }).type === 'string') listener(raw as BusMessage);
  };
  if (window.api?.bus) return window.api.bus.subscribe(accept);
  const fallback = fallbackChannel();
  if (!fallback) return () => undefined;
  const onMessage = (event: MessageEvent): void => accept(event.data);
  fallback.addEventListener('message', onMessage);
  return () => fallback.removeEventListener('message', onMessage);
}

/** 打开或唤回助手窗口；重复调用只会聚焦已有窗口。 */
export function openAssistantWindow(): void {
  if (window.api?.assistant) { void window.api.assistant.open(); return; }
  // 浏览器调试：复用同名标签页，等价于单一助手窗口。
  try { window.open(`${window.location.href.replace(/#.*$/, '')}${ASSISTANT_HASH}`, 'drill-notebook-assistant'); } catch { /* ignore */ }
}

/** 收起助手窗口，后台任务继续。 */
export function hideAssistantWindow(): void {
  if (window.api?.assistant) void window.api.assistant.hide();
}

/** 把主窗口提到前面；只在用户点击资料来源或页面标记时调用。 */
export function focusMainWindow(): void {
  if (window.api?.assistant) void window.api.assistant.focusMain();
}

export function onAssistantStatus(listener: (status: AssistantWindowStatus) => void): () => void {
  const api = window.api?.assistant;
  if (!api) return () => undefined;
  void api.getState().then(listener).catch(() => undefined);
  return api.onState(listener);
}
