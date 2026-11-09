export interface ChatSupplement {
  requestId: string;
  state: string;
  userText: string;
  replyOffset: number;
  attachments?: string[];
}
export interface QueueItem {
  requestId: string;
  revision: number;
  state: string;
  source: 'human' | 'tool';
  userText: string;
  sequence: number;
  targetRequestId?: string;
  attachments?: { clientId: string; name: string; kind: string }[];
  context?: string;
  error?: string;
}
export interface ChatQueueView {
  sessionId: number;
  items: QueueItem[];
  steering: QueueItem[];
  paused: boolean;
  editingId: string | null;
  itemRevision?: number;
}
export const queueKey = (id?: number) => ['ai-chat-queue', id] as const;
export function splitReply(text: string, supplements: ChatSupplement[]): { role: 'assistant' | 'user'; text: string; key: string; attachments?: string[] }[] {
  const parts: ReturnType<typeof splitReply> = [];
  let offset = 0;
  for (const item of supplements.filter((s) => s.state === 'RECEIVED')) {
    const end = Math.max(offset, Math.min(text.length, item.replyOffset));
    if (end > offset) parts.push({ role: 'assistant', text: text.slice(offset, end), key: `a-${item.requestId}` });
    parts.push({ role: 'user', text: item.userText, key: item.requestId, attachments: item.attachments });
    offset = end;
  }
  if (offset < text.length) parts.push({ role: 'assistant', text: text.slice(offset), key: 'tail' });
  return parts;
}
