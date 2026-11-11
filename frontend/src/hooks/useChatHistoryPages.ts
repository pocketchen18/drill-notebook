import { useCallback, useMemo } from 'react';
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

function pageUrl(sessionId: number, beforeMessageId?: number): string {
  const query = new URLSearchParams({ limit: String(HISTORY_PAGE_SIZE) });
  if (beforeMessageId !== undefined) query.set('beforeMessageId', String(beforeMessageId));
  return `/api/ai/sessions/${sessionId}/history?${query.toString()}`;
}

export interface ChatHistoryPages {
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
  const query = useInfiniteQuery<ChatHistoryPage, Error, InfiniteData<ChatHistoryPage, number | undefined>,
    ReturnType<typeof historyQueryKey>, number | undefined>({
    queryKey: historyQueryKey(sessionId),
    queryFn: ({ pageParam }) => get<ChatHistoryPage>(pageUrl(sessionId as number, pageParam)),
    initialPageParam: undefined,
    // 每页内部按 ID 升序，所以页首就是这一页最早的一条，正好当下一版的 beforeMessageId
    getNextPageParam: (last) => (last.hasMore && last.messages.length > 0 ? last.messages[0].id : undefined),
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
      for (const row of pages[index].messages) {
        // 最新一页被单独重取过就可能与旧页重叠，按 message ID 收敛
        if (seen.has(row.id)) continue;
        seen.add(row.id);
        ordered.push(row);
      }
    }
    return ordered;
  }, [pages]);

  const residentCapped = pages.length >= MAX_RESIDENT_PAGES;
  const hasOlder = pages.length > 0 && pages[pages.length - 1].hasMore;

  const loadOlder = useCallback(() => {
    // 到驻留上限就停：更深的浏览不是靠无限堆内存换的，数据一条都没少
    if (!hasOlder || residentCapped || isFetchingNextPage) return;
    void fetchNextPage();
  }, [hasOlder, residentCapped, isFetchingNextPage, fetchNextPage]);

  const refreshLatest = useCallback(async () => {
    if (sessionId === undefined) return;
    const cached = client.getQueryData<InfiniteData<ChatHistoryPage, number | undefined>>(
      historyQueryKey(sessionId));
    if (!cached) return; // 还没加载过：交给查询自己的首次取数，不在这里造第一页
    const latest = await get<ChatHistoryPage>(pageUrl(sessionId, undefined));
    client.setQueryData<InfiniteData<ChatHistoryPage, number | undefined>>(historyQueryKey(sessionId),
      (current) => (current ? { ...current, pages: [latest, ...current.pages.slice(1)] } : current));
  }, [sessionId, client]);

  return {
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
