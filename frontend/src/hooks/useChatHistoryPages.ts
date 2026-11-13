import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import type { InfiniteData } from '@tanstack/react-query';
import { get } from '../lib/api';

/**
 * 历史分页（执行方案 §7.4、§12，contracts §3 尾段）：每页 50 条按 message ID 游标往前翻，
 * 前端最多驻留 500 条；读的是 `/sessions/{id}/history`，主密码不进 URL 也不进 query。
 *
 * <p>这一层只读：卸载、切会话、翻页都不会发 DELETE，也不触发提取/摘要/重放。归档只是列表里隐藏，
 * 消息行仍在库里，所以这里也绝不因为"归档了"就清掉数据。</p>
 */

/** 服务端一页里的一条消息（失败轮次与 legacy 都如实带状态，展示资格不等于注入资格）。 */
export interface ChatHistoryMessage {
  recovery?: import('../lib/aiRecovery').AiRecovery | null;
  roundNumber?: number;
  supplements?: import('../lib/chatQueue').ChatSupplement[];
  finishReason?: string;
  toolActivities?: import('../lib/toolActivity').ToolActivity[];
  id: number;
  role: 'user' | 'assistant' | 'system';
  content: string;
  page?: { title: string; link?: string };
  model?: string;
  reasoningEffort?: string;
  usage?: { requests: number; inputTokens: number; outputTokens: number };
  reasoning?: string;
  createdAt?: string;
  turnId?: number | null;
  /** 本轮幂等身份：徽标与上下文检查器只认它；legacy 行为 null。 */
  requestId?: string | null;
  turnRole?: string | null;
  turnStatus?: string | null;
  contentDeleted?: boolean;
  legacy?: boolean;
  readable?: boolean;
}

export interface ChatHistoryPage {
  totalRounds?: number;
  firstRound?: number;
  hasOlder?: boolean;
  sessionId: number;
  messages: ChatHistoryMessage[];
  historyRevision: number;
  hasMore: boolean;
  limit: number;
}

/** 与后端 `ChatHistoryService.MAX_PAGE_SIZE` 同值：超过它服务端会自己压回 50。 */
export const HISTORY_PAGE_SIZE = 50;
/** §7.4：前端内存最多驻留 500 条，到顶就停，不靠删库腾地方。 */
export const MAX_RESIDENT_MESSAGES = 500;
export const MAX_RESIDENT_PAGES = Math.floor(MAX_RESIDENT_MESSAGES / HISTORY_PAGE_SIZE);

export const historyQueryKey = (sessionId: number | undefined) =>
  ['ai-session-history', sessionId ?? 'none'] as const;

type HistoryCursor = number | { after: number } | undefined;
function pageUrl(sessionId: number, cursor?: HistoryCursor): string {
  const query = new URLSearchParams({ limit: String(HISTORY_PAGE_SIZE) });
  if (typeof cursor === 'number') query.set('beforeMessageId', String(cursor));
  else if (cursor) query.set('afterMessageId', String(cursor.after));
  return `/api/ai/sessions/${sessionId}/history?${query.toString()}`;
}

export interface ChatHistoryPages {
  totalRounds?: number;
  focused: boolean;
  focusMessage: (messageId: number) => Promise<void>;
  returnToLatest: () => Promise<void>;
  /** 按时间升序的已驻留消息（旧页在前，最新页在后），按 message ID 去重。 */
  messages: ChatHistoryMessage[];
  historyRevision: number;
  /** 服务端说还有更早的，且还没到 500 条驻留上限。 */
  hasOlder: boolean;
  /** 已到驻留上限：更早的记录仍在库里，只是本页不再继续加载。 */
  residentCapped: boolean;
  isLoading: boolean;
  isError: boolean;
  isLoadingOlder: boolean;
  error: unknown;
  loadOlder: () => void;
  /** 首次读取失败后的显式重试：仍只走只读分页接口，不重发任何一轮聊天。 */
  retry: () => void;
  /** 只用一次请求替换最新一页，更早的页保持不动（不整串重取）。 */
  refreshLatest: () => Promise<void>;
}

export function useChatHistoryPages(sessionId: number | undefined, enabled = true): ChatHistoryPages {
  const client = useQueryClient();
  const reading = useRef({ sessionId, sequence: 0, pending: false });
  if (reading.current.sessionId !== sessionId) {
    reading.current = { sessionId, sequence: reading.current.sequence + 1, pending: false };
  }
  useEffect(() => () => {
    reading.current.sequence += 1;
    reading.current.pending = false;
  }, [sessionId]);
  const query = useInfiniteQuery<ChatHistoryPage, Error, InfiniteData<ChatHistoryPage, HistoryCursor>,
    ReturnType<typeof historyQueryKey>, HistoryCursor>({
    queryKey: historyQueryKey(sessionId),
    queryFn: ({ pageParam }) => get<ChatHistoryPage>(pageUrl(sessionId as number, pageParam)),
    initialPageParam: undefined,
    // 每页内部按 ID 升序，所以页首就是这一页最早的一条，正好当下一版的 beforeMessageId
    getNextPageParam: (last) => ((last.hasOlder ?? last.hasMore) && last.messages.length > 0 ? last.messages[0].id : undefined),
    enabled: enabled && sessionId !== undefined,
    // 切会话必须拿服务端真实内容：留缓存会让"中断时抢在落库前的空结果"被复用（沿用旧查询的口径）
    staleTime: 0
  });
  const { fetchNextPage, isFetchingNextPage, isPending, isError, error, refetch } = query;

  const pages = query.data?.pages ?? [];
  const messages = useMemo(() => {
    const seen = new Set<number>();
    const ordered: ChatHistoryMessage[] = [];
    for (let index = pages.length - 1; index >= 0; index -= 1) {
      let roundNumber = pages[index].firstRound;
      for (const row of pages[index].messages) {
        const round = row.role === 'user' && roundNumber !== undefined ? roundNumber++ : row.roundNumber;
        // 最新一页被单独重取过就可能与旧页重叠，按 message ID 收敛
        if (seen.has(row.id)) continue;
        seen.add(row.id);
        ordered.push(round === undefined ? row : { ...row, roundNumber: round });
      }
    }
    return ordered;
  }, [pages]);

  const residentCapped = pages.length >= MAX_RESIDENT_PAGES;
  const hasOlder = pages.length > 0 && (pages[pages.length - 1].hasOlder ?? pages[pages.length - 1].hasMore);
  const focused = typeof query.data?.pageParams[0] === 'object';

  const loadOlder = useCallback(() => {
    // 到驻留上限就停：更深的浏览不是靠无限堆内存换的，数据一条都没少
    if (!hasOlder || residentCapped || isFetchingNextPage) return;
    void fetchNextPage();
  }, [hasOlder, residentCapped, isFetchingNextPage, fetchNextPage]);

  const refreshLatest = useCallback(async () => {
    if (sessionId === undefined || reading.current.pending) return;
    const sequence = reading.current.sequence;
    const cached = client.getQueryData<InfiniteData<ChatHistoryPage, HistoryCursor>>(
      historyQueryKey(sessionId));
    if (!cached) return; // 还没加载过：交给查询自己的首次取数，不在这里造第一页
    if (typeof cached.pageParams[0] === 'object') return; // 阅读旧窗口时不被定稿刷新拉回最新。
    const latest = await get<ChatHistoryPage>(pageUrl(sessionId, undefined));
    if (reading.current.sessionId !== sessionId || reading.current.sequence !== sequence) return;
    client.setQueryData<InfiniteData<ChatHistoryPage, HistoryCursor>>(historyQueryKey(sessionId),
      (current) => (current && typeof current.pageParams[0] !== 'object'
        ? { ...current, pages: [latest, ...current.pages.slice(1)] } : current));
  }, [sessionId, client]);

  const focusMessage = useCallback(async (messageId: number) => {
    if (sessionId === undefined || !Number.isSafeInteger(messageId) || messageId <= 0) return;
    const sequence = ++reading.current.sequence;
    reading.current.pending = true;
    const current = () => reading.current.sessionId === sessionId && reading.current.sequence === sequence;
    const cursor = { after: messageId - 1 };
    try {
      await client.cancelQueries({ queryKey: historyQueryKey(sessionId), exact: true });
      if (!current()) return;
      const target = await get<ChatHistoryPage>(pageUrl(sessionId, cursor));
      if (!current()) return;
      if (!target.messages.some(row => row.id === messageId && row.role === 'user')) throw new Error('这轮对话已清理，请重新选择');
      client.setQueryData<InfiniteData<ChatHistoryPage, HistoryCursor>>(historyQueryKey(sessionId), { pages: [target], pageParams: [cursor] });
    } finally { if (current()) reading.current.pending = false; }
  }, [sessionId, client]);
  const returnToLatest = useCallback(async () => {
    if (sessionId === undefined) return;
    const sequence = ++reading.current.sequence;
    reading.current.pending = true;
    const current = () => reading.current.sessionId === sessionId && reading.current.sequence === sequence;
    try {
      await client.cancelQueries({ queryKey: historyQueryKey(sessionId), exact: true });
      if (!current()) return;
      const latest = await get<ChatHistoryPage>(pageUrl(sessionId));
      if (!current()) return;
      client.setQueryData<InfiniteData<ChatHistoryPage, HistoryCursor>>(historyQueryKey(sessionId), { pages: [latest], pageParams: [undefined] });
    } finally { if (current()) reading.current.pending = false; }
  }, [sessionId, client]);

  return {
    totalRounds: pages[0]?.totalRounds,
    focused,
    focusMessage,
    returnToLatest,
    messages,
    historyRevision: pages[0]?.historyRevision ?? 0,
    hasOlder,
    residentCapped,
    isLoading: enabled && sessionId !== undefined && isPending,
    isError,
    isLoadingOlder: isFetchingNextPage,
    error,
    loadOlder,
    retry: () => { void refetch(); },
    refreshLatest
  };
}
