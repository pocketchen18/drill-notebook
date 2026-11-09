import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, get, post } from '../lib/api';
import { buildTurnBody, type TurnInput } from './useAiChatTurn';
import { generateUuidV7 } from '../lib/uuidV7';
import { queueKey, type ChatQueueView, type QueueItem } from '../lib/chatQueue';

export function useChatQueue(sessionId?: number, enabled = true) {
  const client = useQueryClient();
  const unresolved = useRef(new Map<number, Record<string, unknown>>());
  const [, render] = useState(0);
  const query = useQuery({ queryKey: queueKey(sessionId),
    queryFn: () => get<ChatQueueView>(`/api/ai/sessions/${sessionId}/queue`),
    enabled: enabled && sessionId !== undefined, refetchInterval: 900, staleTime: 0 });
  const refresh = (id: number) => client.invalidateQueries({ queryKey: queueKey(id) });
  const deliver = async (id: number, body: Record<string, unknown>) => {
    try {
      const result = await post<{ requestId: string; status: string }>(`/api/ai/sessions/${id}/queue`, body);
      unresolved.current.delete(id); render((n) => n + 1);
      void refresh(id);
      return result;
    } catch (error) {
      // A definite rejection can be corrected; a lost response must reuse the accepted identity.
      if (error instanceof ApiError && error.status >= 400 && error.status < 500) unresolved.current.delete(id);
      render((n) => n + 1);
      throw error;
    }
  };
  const submit = async (input: TurnInput, mode: 'queue' | 'steer', targetRequestId?: string) => {
    if (unresolved.current.has(input.sessionId)) throw new Error('请先核对上一次提交结果');
    const body = { ...buildTurnBody(generateUuidV7(Date.now()), input), mode, targetRequestId };
    unresolved.current.set(input.sessionId, body);
    return deliver(input.sessionId, body);
  };
  const retrySubmission = async () => {
    const body = sessionId === undefined ? undefined : unresolved.current.get(sessionId);
    if (!body || sessionId === undefined) return;
    return deliver(sessionId, body);
  };
  const action = async (item: QueueItem, action: string, extra: Record<string, unknown> = {}) => {
    const id = sessionId!;
    await client.cancelQueries({ queryKey: queueKey(id) });
    try {
      const view = await post<ChatQueueView>(`/api/ai/sessions/${id}/queue/${item.requestId}/action`, { action, revision: item.revision, ...extra });
      client.setQueryData(queueKey(id), view);
      return view;
    } finally { void refresh(id); }
  };
  const pause = async (paused: boolean) => {
    const id = sessionId!;
    await client.cancelQueries({ queryKey: queueKey(id) });
    const view = await post<ChatQueueView>(`/api/ai/sessions/${id}/queue/pause`, { paused });
    client.setQueryData(queueKey(id), view);
  };
  return { ...query, submit, action, pause, refresh, retrySubmission, unconfirmed: sessionId !== undefined && unresolved.current.has(sessionId) };
}
