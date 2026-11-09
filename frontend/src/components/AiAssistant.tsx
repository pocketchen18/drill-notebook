import { useEffect, useMemo, useRef, useState, type UIEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Drawer, Input, Message, Select, Space, Tag, Typography } from '@arco-design/web-react';
import { Download, FilePlus2, Layers, Paperclip, Send, Sparkles, Square, X } from 'lucide-react';
import { SessionNavigation } from './ai-session/SessionNavigation';
import { useNavigate } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { AiChatSession, AiConfig, ChatCitation, ChatContentPart, ChatMessage, NotePage, Notebook, RetrievalOptions, TurnResult } from '../lib/types';
import { ChatContextInspector } from './ai-memory/ChatContextInspector';
import { MemoryUsageBadge } from './ai-memory/MemoryUsageBadge';
import { appendMarkdownBlock } from '../lib/aiContext';
import { safeFileName } from '../lib/export';
import { LS_RETRIEVAL_SCOPE, LS_RETRIEVE_NOTES, readBoolPref, readStringPref, writeBoolPref, writeStringPref } from '../lib/sessionPrefs';
import { useAiChatTurn } from '../hooks/useAiChatTurn';
import type { TurnAttachment, TurnEvent, TurnInput } from '../hooks/useAiChatTurn';
import { canContinueTurn, executionStopNotice, historyNotice, outcomeNotice, statusNotice } from '../lib/aiTurnText';
import type { TurnNotice } from '../lib/aiTurnText';
import { historyQueryKey, useChatHistoryPages } from '../hooks/useChatHistoryPages';
import type { ChatHistoryMessage } from '../hooks/useChatHistoryPages';
import { storageQueryKey } from '../lib/aiStorageApi';
import { ChatStorageMeter } from './ai-memory/ChatStorageMeter';
import { ChatHistoryCleanup } from './ai-memory/ChatHistoryCleanup';
import { attachmentLimitError } from '../lib/aiAttachmentLimits';
import type { PendingAttachment } from '../lib/aiAttachmentLimits';
import { describeAccelerator, describeAccelerators, matchesAny } from '../lib/shortcuts';
import { MarkdownContent } from './markdown/MarkdownRenderer';
import { SessionReferencePreview } from './ai-session/SessionReferencePreview';
import { useUiStore } from '../stores/uiStore';
import { ToolActivityGroup } from './ai-memory/ToolActivityGroup';
import { mergeToolActivities, settleToolActivities } from '../lib/toolActivity';
import type { ToolActivity } from '../lib/toolActivity';
import { useChatQueue } from '../hooks/useChatQueue';
import { ChatQueue } from './ai-session/ChatQueue';
import { splitReply } from '../lib/chatQueue';

const { TextArea } = Input;

type AttachmentKind = 'text' | 'image';
interface Attachment {
  id: string;
  name: string;
  kind: AttachmentKind;
  value: string;
  mime: string;
  /** 原始字节数：附件门限（数量/单图/合计）按它算，服务端也用同一个数判 413。 */
  bytes: number;
  /** 文本的码点数：服务端按本轮文本合计判 200k，客户端得留着自己那份数。 */
  codepoints?: number;
}

/** 同一批里两个同名同时间的文件也必须各有各的 key，否则移除一个会连带移除另一个。 */
let attachmentSeq = 0;
const nextAttachmentId = (file: File): string => `${file.name}-${file.lastModified}-${(attachmentSeq += 1)}`;

type PreparedAttachment = { attachment: Attachment } | { warning: string } | { error: string };

function contentText(content: string | ChatContentPart[], displayContent?: string): string {
  if (displayContent) return displayContent;
  if (typeof content === 'string') return content;
  return content.map((part) => (part.type === 'text' ? part.text ?? '' : '[图片附件]')).filter(Boolean).join('\n');
}

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.readAsDataURL(file);
  });
}

function isTextFile(file: File): boolean {
  return file.type.startsWith('text/')
    || /\.(md|markdown|txt|json|csv|log|java|kt|ts|tsx|js|jsx|py|go|rs|sql|xml|yaml|yml|css|html)$/i.test(file.name);
}

export function AiAssistant(): JSX.Element {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const fileInput = useRef<HTMLInputElement>(null);
  const aiOpen = useUiStore((state) => state.aiOpen);
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const pageContext = useUiStore((state) => state.pageContext);
  const aiFabVisible = useUiStore((state) => state.aiFabVisible);
  // 悬浮球提示与发送键跟随设置 → 常规 → 快捷键里的当前绑定
  const aiAccelerator = useUiStore((state) => state.shortcutConfig.toggleAi[0]);
  const sendKeys = useUiStore((state) => state.shortcutConfig.aiSend);
  // 未被「发送」占用的 Enter 组合用于换行
  const newlineHint = !sendKeys.includes('Enter') ? 'Enter 换行' : !sendKeys.includes('Shift+Enter') ? 'Shift+Enter 换行' : '';
  const composePlaceholder = `提问… ${sendKeys.length ? `${describeAccelerators(sendKeys)} 发送` : '点按钮发送'}${newlineHint ? ` · ${newlineHint}` : ''}`;
  const configQuery = useQuery({
    queryKey: ['ai-config'],
    queryFn: async () => {
      const raw = await get<AiConfig>('/api/ai/config');
      // 兼容新旧 API：优先 chat 槽
      const chat = raw.chat ?? raw;
      return { ...raw, hasKey: chat.hasKey, authMode: chat.authMode, provider: chat.provider, endpoint: chat.endpoint, model: chat.model, chat };
    }
  });
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks'), enabled: aiOpen });
  const sessionsQuery = useQuery({
    queryKey: ['ai-sessions'],
    queryFn: () => get<AiChatSession[]>('/api/ai/sessions'),
    refetchInterval: 1500,
    enabled: aiOpen
  });

  const [sessionId, setSessionId] = useState<number>();
  const [message, setMessage] = useState('');
  const [queueSubmitting, setQueueSubmitting] = useState(false);
  const queueSubmittingRef = useRef(false);
  const submittedQueueDrafts = useRef(new Map<number, { message: string; ids: Set<string> }>());
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [usePageContext, setUsePageContext] = useState(true);
  const [retrieveNotes, setRetrieveNotes] = useState(() => readBoolPref(LS_RETRIEVE_NOTES, false));
  const [retrievalScope, setRetrievalScope] = useState<'current' | 'all'>(() => {
    const saved = readStringPref(LS_RETRIEVAL_SCOPE, '');
    if (saved === 'current' || saved === 'all') return saved;
    return useUiStore.getState().pageContext.notebookId !== undefined ? 'current' : 'all';
  });
  const [targetNotebookId, setTargetNotebookId] = useState<number>();
  const [targetPageId, setTargetPageId] = useState<number>();
  const [pendingInsert, setPendingInsert] = useState<string>();
  const [exporting, setExporting] = useState(false);
  const [sessionReference, setSessionReference] = useState<string>();
  // 打开中的上下文检查器：面板里可能有逐条对照出来的正文片段，切会话时必须整体销毁（§4.5）
  const [inspectorTarget, setInspectorTarget] = useState<{ requestId: string; snapshot?: TurnResult } | null>(null);
  const selectedSessionRef = useRef(sessionId);
  selectedSessionRef.current = sessionId;

  // 稳定消息 key：消息创建时分配一次，后续 refetch 也保持稳定，避免列表 remount 造成滚动/输入抖动。
  const messageKeyCounter = useRef(0);
  const nextMessageKey = (): string => {
    messageKeyCounter.current += 1;
    return `m${messageKeyCounter.current}`;
  };
  // undefined 是首次加载，null 是未保存的新会话页；刷新列表不能把草稿页切走。
  const lastExplicitSessionIdRef = useRef<number | null | undefined>(undefined);
  const firstSendPendingRef = useRef(false);
  const pendingFirstTurnRef = useRef<TurnInput | null>(null);
  // 本轮结果落回哪一个气泡：流式先立占位，非流式收到整块结果才追加。
  const turnTargetRef = useRef<{ userKey: string; assistantKey: string | null } | null>(null);
  // 流式期间用户已向上滚动 → 暂停自动滚到底；回到接近底部时自动恢复。
  const chatContainerRef = useRef<HTMLDivElement>(null);
  const stickyBottomRef = useRef(true);
  const lastScrollTopRef = useRef(0);
  // 往前翻页前的列表高度：用来把视口补回原来看着的那条消息
  const olderAnchorRef = useRef<number | null>(null);
  const draftCache = useRef(new Map<number | 'new', { message: string; attachments: Attachment[]; messages: ChatMessage[];
    target: typeof turnTargetRef.current; sticky: boolean; scroll: number }>());
  const restoreScrollRef = useRef<number | null>(null);
  const observedStatusRef = useRef(new Map<string, string>());
  function selectSession(next: number | undefined): void {
    if (firstSendPendingRef.current || next === sessionId) return;
    draftCache.current.set(sessionId ?? 'new', { message, attachments, messages, target: turnTargetRef.current,
      sticky: stickyBottomRef.current, scroll: chatContainerRef.current?.scrollTop ?? 0 });
    const draft = draftCache.current.get(next ?? 'new');
    lastExplicitSessionIdRef.current = next ?? null;
    setSessionId(next); setMessage(draft?.message ?? ''); setAttachments(draft?.attachments ?? []); setMessages(draft?.messages ?? []);
    turnTargetRef.current = draft?.target ?? null;
    stickyBottomRef.current = draft?.sticky ?? true; restoreScrollRef.current = draft?.scroll ?? 0;
    olderAnchorRef.current = null; setPendingInsert(undefined);
  }

  const streamEnabled = (configQuery.data?.chat ?? configQuery.data)?.streaming !== false;
  const turn = useAiChatTurn({ sessionId, streamEnabled, onEvent: onTurnEvent });
  const streaming = turn.busy;
  const queue = useChatQueue(sessionId, aiOpen);
  // 后台会话不会向当前聊天发送事件；按轮次/状态变化同步全局容量，避免每次轮询重读账本。
  const runtimeStorageSignature = turn.runtimes
    .map((item) => `${item.sessionId}:${item.requestId}:${item.status}`).sort().join('|');
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: storageQueryKey });
  }, [queryClient, runtimeStorageSignature]);

  const targetPagesQuery = useQuery({
    queryKey: ['ai-drawer-pages', targetNotebookId],
    queryFn: () => get<NotePage[]>(`/api/notebooks/${targetNotebookId}/pages`),
    enabled: targetNotebookId !== undefined && aiOpen
  });

  const history = useChatHistoryPages(sessionId, aiOpen);

  /** 本轮的引用、降级提示与 done 快照只存在于本地，服务端历史行按 requestId 把它们认领回去。 */
  const visibleMessages = useMemo<ChatMessage[]>(() => {
    const snapshots = new Map<string, ChatMessage>();
    for (const item of messages) {
      if (item.role === 'assistant' && item.requestId) snapshots.set(item.requestId, item);
    }
    const covered = new Set<string>();
    const server: ChatMessage[] = history.messages.map((row) => {
      if (row.requestId) covered.add(`${row.role}:${row.requestId}`);
      const mirror = row.role === 'assistant' && row.requestId ? snapshots.get(row.requestId) : undefined;
      return {
        id: row.id,
        role: row.role,
        content: mirror?.streaming || !row.content ? mirror?.content ?? row.content : row.content,
        reasoning: mirror?.streaming ? mirror.reasoning : row.reasoning ?? mirror?.reasoning,
        supplements: mirror?.streaming ? mirror.supplements : row.supplements ?? mirror?.supplements,
        streaming: mirror?.streaming,
        toolActivities: mirror?.streaming ? mirror.toolActivities
          : settleToolActivities(mergeToolActivities(mirror?.toolActivities, row.toolActivities)),
        toolPhase: mirror?.toolPhase,
        createdAt: row.createdAt,
        requestId: row.requestId ?? undefined,
        citations: mirror?.citations,
        notice: mirror?.streaming ? mirror.notice : historyNotice(row) ?? mirror?.notice,
        turn: mirror?.turn,
        _key: mirror?._key ?? `h${row.id}`
      };
    });
    // 本地只留还没被服务端历史覆盖的那几条（正在跑的一轮、以及刚定稿还没重取回来的一轮）
    const local = messages.filter((item) => !(item.requestId && covered.has(`${item.role}:${item.requestId}`)));
    return [...server, ...local];
  }, [history.messages, messages]);

  useEffect(() => {
    if (!aiOpen) return;
    if (pageContext.notebookId !== undefined) setTargetNotebookId(pageContext.notebookId);
    if (pageContext.notePageId !== undefined) setTargetPageId(pageContext.notePageId);
  }, [aiOpen, pageContext.notebookId, pageContext.notePageId]);

  useEffect(() => {
    if (targetNotebookId === undefined && notebooksQuery.data?.length) setTargetNotebookId(notebooksQuery.data[0].id);
  }, [notebooksQuery.data, targetNotebookId]);

  useEffect(() => {
    if (targetPageId === undefined && targetPagesQuery.data?.length) setTargetPageId(targetPagesQuery.data[0].id);
  }, [targetPageId, targetPagesQuery.data]);

  useEffect(() => {
    if (!sessionsQuery.data || lastExplicitSessionIdRef.current === null || firstSendPendingRef.current) return;
    // 用户最近一次显式选中的会话若仍在列表中（哪怕列表 refetch 重新排过序），不要把它重置到 data[0]。
    if (lastExplicitSessionIdRef.current !== undefined &&
        sessionsQuery.data.some((item) => item.id === lastExplicitSessionIdRef.current)) {
      if (sessionId !== lastExplicitSessionIdRef.current) setSessionId(lastExplicitSessionIdRef.current);
      return;
    }
    // 仅首次进入可恢复最近会话；已选会话消失后回到草稿页，空列表也不补建占位会话。
    if (sessionId === undefined || !sessionsQuery.data.some((item) => item.id === sessionId)) {
      const next = lastExplicitSessionIdRef.current === undefined ? sessionsQuery.data[0]?.id : undefined;
      lastExplicitSessionIdRef.current = next ?? null;
      setSessionId(next);
      setMessages([]);
    }
  }, [sessionId, sessionsQuery.data]);

  // 切会话先销毁检查器：它可能正显示上一轮逐条对照出来的正文片段，不能跨会话留着（§4.5、C08）
  useEffect(() => {
    setInspectorTarget(null);
  }, [sessionId]);

  // 聊天列表自动滚动：仅当用户接近底部时粘底；用户上滑阅读时尊重其位置。
  const onChatScroll = (event: UIEvent<HTMLDivElement>): void => {
    const el = event.currentTarget;
    const distanceFromBottom = el.scrollHeight - el.clientHeight - el.scrollTop;
    // 即使只向上移动了几像素，也视为用户开始阅读旧消息；否则 80px 阈值内的上滑
    // 会继续被流式跟随拉回底部。向下滚回接近底部时再恢复自动跟随。
    if (el.scrollTop < lastScrollTopRef.current) {
      stickyBottomRef.current = false;
    } else if (el.scrollTop > lastScrollTopRef.current) {
      stickyBottomRef.current = distanceFromBottom < 80;
    }
    lastScrollTopRef.current = el.scrollTop;
    // 滚到顶部附近就取更早的一页（§7.4：向上滚动可重新加载），并记住当前高度以便补回位移
    if (el.scrollTop < 60) requestOlder();
  };

  const requestOlder = (): void => {
    if (!history.hasOlder || history.isLoadingOlder || history.residentCapped) return;
    const el = chatContainerRef.current;
    olderAnchorRef.current = el ? el.scrollHeight : null;
    history.loadOlder();
  };

  // 往前插了一页，视口要停在原来那条上，否则每次翻页都被甩到更早的几百条之前
  useEffect(() => {
    const el = chatContainerRef.current;
    const anchor = olderAnchorRef.current;
    if (!el || anchor === null) return;
    olderAnchorRef.current = null;
    el.scrollTop += el.scrollHeight - anchor;
    lastScrollTopRef.current = el.scrollTop;
  }, [history.messages]);
  useEffect(() => {
    const el = chatContainerRef.current;
    if (!el) return;
    if (restoreScrollRef.current !== null && !history.isLoading) {
      el.scrollTop = restoreScrollRef.current; lastScrollTopRef.current = el.scrollTop; restoreScrollRef.current = null;
    }
    if (!stickyBottomRef.current) return;
    el.scrollTop = el.scrollHeight;
    lastScrollTopRef.current = el.scrollTop;
  }, [visibleMessages, history.isLoading]);
  useEffect(() => {
    if (!streaming) return;
    let frame = 0;
    const tick = (): void => {
      const el = chatContainerRef.current;
      // 每帧读取最新状态：用户上滑后暂停跟随，回到底部后在同一轮生成中恢复。
      if (el && stickyBottomRef.current) {
        el.scrollTop = el.scrollHeight;
        lastScrollTopRef.current = el.scrollTop;
      }
      frame = window.requestAnimationFrame(tick);
    };
    frame = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(frame);
  }, [streaming]);

  // 开启笔记检索且当前上下文本身就是笔记页时，不再重复注入整页 markdown（后端会按需检索）。
  const suppressPageMarkdown = retrieveNotes && pageContext.kind === 'note';

  const contextMarkdown = useMemo(
    () => (usePageContext && !suppressPageMarkdown && pageContext.markdown.trim() ? pageContext.markdown.trim() : ''),
    [pageContext.markdown, suppressPageMarkdown, usePageContext]
  );

  // Task 14：进入无当前 notebook 的页面时，current 自动降级为 all，保证绝不发送缺 notebookId 的 current。
  useEffect(() => {
    if (pageContext.notebookId === undefined && retrievalScope === 'current') setRetrievalScope('all');
  }, [pageContext.notebookId, retrievalScope]);

  const onToggleRetrieveNotes = (checked: boolean): void => {
    setRetrieveNotes(checked);
    writeBoolPref(LS_RETRIEVE_NOTES, checked);
    if (checked) {
      // 开启时若无显式持久化选择，按当前上下文推断默认范围：note 页→current，其他→all。
      const saved = readStringPref(LS_RETRIEVAL_SCOPE, '');
      if (saved !== 'current' && saved !== 'all') {
        setRetrievalScope(pageContext.notebookId !== undefined ? 'current' : 'all');
      }
    }
  };

  const onChangeRetrievalScope = (value: 'current' | 'all'): void => {
    setRetrievalScope(value);
    writeStringPref(LS_RETRIEVAL_SCOPE, value);
  };

  // Task 8/14：开启时发送 enabled/scope/notebookId；关闭时完全不携带该字段，保持旧请求契约。
  const retrievalOptions = useMemo<RetrievalOptions | undefined>(() => {
    if (!retrieveNotes) return undefined;
    if (retrievalScope === 'current' && pageContext.notebookId !== undefined) {
      return { enabled: true, scope: 'current', notebookId: pageContext.notebookId };
    }
    return { enabled: true, scope: 'all' };
  }, [pageContext.notebookId, retrievalScope, retrieveNotes]);

  const createSessionMutation = useMutation({
    mutationFn: () => post<AiChatSession>('/api/ai/sessions', { title: '新会话' }),
    onSuccess: (session) => {
      // 只在首条消息发送时创建；先写缓存和选择，下一次渲染后再启动首轮。
      queryClient.setQueryData<AiChatSession[]>(['ai-sessions'], (old) => {
        const list = old ?? [];
        if (list.some((item) => item.id === session.id)) return list;
        return [{ ...session, messageCount: session.messageCount ?? 0 }, ...list];
      });
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      lastExplicitSessionIdRef.current = session.id;
      draftCache.current.delete('new');
      setSessionId(session.id);
    }
  });

  /** 新会话页只保存前端草稿，发送前没有数据库会话。 */
  const handleNewSession = (): void => {
    if (firstSendPendingRef.current || lastExplicitSessionIdRef.current === null) return;
    selectSession(undefined);
    setInspectorTarget(null);
  };

  const updateSessionCache = (session: AiChatSession): void => {
    queryClient.setQueryData<AiChatSession[]>(['ai-sessions'], (old) => (old ?? []).map((item) =>
      item.id === session.id && (session.titleRevision ?? 0) >= (item.titleRevision ?? 0) ? session : item));
    void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
  };
  const renameSession = async (id: number, title: string): Promise<void> => {
    updateSessionCache(await put<AiChatSession>(`/api/ai/sessions/${id}`, { title }));
  };
  const generateSessionTitle = async (session: AiChatSession, overwriteManual: boolean): Promise<void> => {
    updateSessionCache(await post<AiChatSession>(`/api/ai/sessions/${session.id}/title`, {
      expectedRevision: session.titleRevision, overwriteManual
    }));
  };

  const deleteSessionMutation = useMutation({
    mutationFn: (id: number) => del(`/api/ai/sessions/${id}`),
    onSuccess: (_result, deletedId) => {
      if (selectedSessionRef.current === deletedId) handleNewSession();
      queryClient.setQueryData<AiChatSession[]>(['ai-sessions'], (old) => (old ?? []).filter((item) => item.id !== deletedId));
      queryClient.removeQueries({ queryKey: historyQueryKey(deletedId) });
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
    }
  });

  // 抽屉宽度：可拖拽调整并持久化到 localStorage。
  const AI_DRAWER_WIDTH_KEY = 'drill-notebook-ai-drawer-width';
  const AI_DRAWER_DEFAULT_WIDTH = 460;
  const AI_DRAWER_MIN_WIDTH = 360;
  const AI_DRAWER_MAX_WIDTH = 900;
  const loadDrawerWidth = (): number => {
    try {
      const raw = window.localStorage.getItem(AI_DRAWER_WIDTH_KEY);
      if (raw === null) return AI_DRAWER_DEFAULT_WIDTH;
      const parsed = Number(raw);
      if (!Number.isFinite(parsed)) return AI_DRAWER_DEFAULT_WIDTH;
      return Math.min(AI_DRAWER_MAX_WIDTH, Math.max(AI_DRAWER_MIN_WIDTH, Math.round(parsed)));
    } catch {
      return AI_DRAWER_DEFAULT_WIDTH;
    }
  };
  const [drawerWidth, setDrawerWidth] = useState<number>(loadDrawerWidth);
  const resizeDragRef = useRef<{ startX: number; startWidth: number } | null>(null);
  const onResizeStart = (event: ReactPointerEvent<HTMLDivElement>): void => {
    resizeDragRef.current = { startX: event.clientX, startWidth: drawerWidth };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const onResizeMove = (event: ReactPointerEvent<HTMLDivElement>): void => {
    const drag = resizeDragRef.current;
    if (!drag) return;
    if (event.buttons === 0) {
      // pointer capture lost：结束拖拽。
      resizeDragRef.current = null;
      return;
    }
    const next = drag.startWidth + (drag.startX - event.clientX);
    const clamped = Math.min(AI_DRAWER_MAX_WIDTH, Math.max(AI_DRAWER_MIN_WIDTH, Math.round(next)));
    setDrawerWidth(clamped);
  };
  const onResizeEnd = (event: ReactPointerEvent<HTMLDivElement>): void => {
    resizeDragRef.current = null;
    try { event.currentTarget.releasePointerCapture(event.pointerId); } catch { /* noop */ }
    try { window.localStorage.setItem(AI_DRAWER_WIDTH_KEY, String(drawerWidth)); } catch { /* ignore quota */ }
  };
  const onResizeDouble = (): void => {
    setDrawerWidth(AI_DRAWER_DEFAULT_WIDTH);
    try { window.localStorage.setItem(AI_DRAWER_WIDTH_KEY, String(AI_DRAWER_DEFAULT_WIDTH)); } catch { /* ignore quota */ }
  };

  const invalidateSessionLists = (refetchMessages: boolean): void => {
    void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
    // 每轮落库与预留结算都会动容量账本：常驻的那条容量提示要跟着结算走
    void queryClient.invalidateQueries({ queryKey: storageQueryKey });
    // 只重取最新一页：更早的页是已看过的稳定游标，没必要为一轮新对话整串重读
    if (refetchMessages) void history.refreshLatest();
  };

  /** 增量只写回本轮那个占位气泡：列表被 refetch 重排时也不能写到别的气泡上。 */
  const patchAssistant = (patch: (prev: ChatMessage) => Partial<ChatMessage>): void => {
    setMessages((current) => {
      const key = turnTargetRef.current?.assistantKey;
      const last = current[current.length - 1];
      if (!key || !last || last._key !== key || last.role !== 'assistant') return current;
      return [...current.slice(0, -1), { ...last, ...patch(last) }];
    });
  };

  /** 定稿：流式就地改写占位，非流式这时才追加助手气泡。 */
  const commitAssistant = (build: (prev: ChatMessage | null) => Omit<ChatMessage, '_key'>): void => {
    const target = turnTargetRef.current;
    if (!target) return;
    const key = target.assistantKey ?? nextMessageKey();
    turnTargetRef.current = { ...target, assistantKey: key };
    setMessages((current) => {
      const last = current[current.length - 1];
      const merging = last?.role === 'assistant' && last._key === key;
      const message: ChatMessage = { ...build(merging ? last : null), _key: key };
      return merging ? [...current.slice(0, -1), message] : [...current, message];
    });
  };

  /** 失败/状态未知时的收口：有内容就定稿保留，零生成的空占位撤掉，绝不留下转圈的气泡。 */
  const settleOrDropAssistant = (notice?: TurnNotice, activities?: ToolActivity[]): void => {
    const key = turnTargetRef.current?.assistantKey;
    if (!key) return;
    setMessages((current) => {
      const next = [...current];
      const last = next[next.length - 1];
      if (!last || last._key !== key || last.role !== 'assistant') return next;
      const text = typeof last.content === 'string' ? last.content : '';
      const reasoning = typeof last.reasoning === 'string' ? last.reasoning : '';
      const toolActivities = settleToolActivities(mergeToolActivities(last.toolActivities, activities));
      if (!text.trim() && !reasoning.trim() && !toolActivities.length) {
        next.pop();
        turnTargetRef.current = null;
        return next;
      }
      next[next.length - 1] = { ...last, content: text, reasoning: reasoning || undefined, streaming: false, notice, toolActivities };
      return next;
    });
  };

  /**
   * 本轮身份要落到这一对本地气泡上：服务端历史行按 requestId 认领它们，
   * 之后才谈得上"用服务端行替换本地气泡"以及把徽标挂回正确的那一轮。
   */
  const stampTurnIdentity = (requestId: string): void => {
    const target = turnTargetRef.current;
    if (!target) return;
    setMessages((current) => current.map((item) =>
      (item._key === target.userKey || item._key === target.assistantKey ? { ...item, requestId } : item)));
  };

  /** 轮次事件的界面落点：done、查到的终态、失败都只写回本轮那一个气泡（§4.2 统一结果）。 */
  function onTurnEvent(event: TurnEvent): void {
    if (event.type === 'snapshot') {
      const snapshot = event.status;
      if (snapshot.sessionId !== selectedSessionRef.current) return;
      // A waiting receipt is not yet a chat turn; its prompt enters history only when execution starts.
      if (snapshot.status === 'QUEUED' || snapshot.status === 'PAUSED') return;
      if (snapshot.contentDeleted) {
        setMessages((current) => current.filter((item) => item.requestId !== event.requestId));
        return;
      }
      const running = snapshot.active === true;
      setMessages((current) => {
        const next = current.map((item) => item.streaming && item.requestId !== event.requestId
          ? { ...item, streaming: false, toolActivities: settleToolActivities(item.toolActivities) } : item);
        if (snapshot.userText && !next.some((item) => item.role === 'user' && item.requestId === event.requestId)) {
          next.push({ role: 'user', content: snapshot.userText, requestId: event.requestId, _key: `u-${event.requestId}` });
        }
        const index = next.findIndex((item) => item.role === 'assistant' && item.requestId === event.requestId);
        const previous = index < 0 ? undefined : next[index];
        if (!running && !previous && !snapshot.reply && !snapshot.reasoning && !snapshot.toolActivities?.length) return next;
        const item: ChatMessage = { ...previous, role: 'assistant', content: snapshot.reply ?? previous?.content ?? '',
          reasoning: snapshot.reasoning ?? previous?.reasoning, requestId: event.requestId, streaming: running,
          supplements: snapshot.supplements ?? previous?.supplements,
          toolActivities: running ? mergeToolActivities(previous?.toolActivities, snapshot.toolActivities)
            : settleToolActivities(mergeToolActivities(previous?.toolActivities, snapshot.toolActivities)),
          toolPhase: snapshot.phase === 'tools' ? 'tools' : 'answer',
          notice: running ? undefined : snapshot.status === 'COMPLETED'
            ? executionStopNotice(snapshot.finishReason) : statusNotice(snapshot), _key: previous?._key ?? `a-${event.requestId}` };
        if (index < 0) next.push(item); else next[index] = item;
        return next;
      });
      const key = `${sessionId}:${event.requestId}`;
      if (observedStatusRef.current.get(key) !== snapshot.status) {
        observedStatusRef.current.set(key, snapshot.status ?? 'UNKNOWN');
        invalidateSessionLists(true);
      }
      return;
    }
    if (event.type === 'reconciled') {
      // 取消后的确认按原 requestId 回填，不能套用指向新一轮的 turnTargetRef。
      setMessages((current) => current.map((item) => item.role === 'assistant' && item.requestId === event.requestId
        ? { ...item, toolActivities: settleToolActivities(mergeToolActivities(item.toolActivities, event.status.toolActivities)) }
        : item));
      if (event.status.status && event.status.status !== 'RUNNING') invalidateSessionLists(true);
      return;
    }
    stampTurnIdentity(event.requestId);
    switch (event.type) {
      case 'supplements':
        patchAssistant(() => ({ supplements: event.supplements }));
        return;
      case 'begun':
        return;
      case 'activity':
        patchAssistant((prev) => ({ toolActivities: mergeToolActivities(prev.toolActivities, event.activities), toolPhase: event.phase }));
        return;
      case 'text':
        patchAssistant((prev) => ({ content: `${String(prev.content)}${event.delta}` }));
        return;
      case 'reasoning':
        patchAssistant((prev) => ({ reasoning: `${prev.reasoning ?? ''}${event.delta}` }));
        return;
      case 'result': {
        const notice = outcomeNotice(event.view);
        commitAssistant((prev) => ({
          role: 'assistant',
          // reply 为空时保留已流式累积的内容，避免 content 变成 undefined 触发渲染崩溃。
          content: event.reply || (typeof prev?.content === 'string' ? prev.content : ''),
          reasoning: event.reasoning || prev?.reasoning,
          supplements: event.view.supplements ?? prev?.supplements,
          citations: (event.view.citations as ChatCitation[] | undefined) ?? prev?.citations,
          notice: notice ?? prev?.notice,
          // 本轮身份与统一结果快照：徽标和检查器只认 requestId，历史行现在也带它（§4.5）
          requestId: event.requestId,
          turn: { ...event.view, requestId: event.requestId },
          toolActivities: settleToolActivities(mergeToolActivities(prev?.toolActivities, event.view.toolActivities)),
          streaming: false
        }));
        invalidateSessionLists(true);
        return;
      }
      case 'failure':
        // 这一轮真的开始过或已产出内容：保留已生成的部分，把服务端错误如实提示给用户（§7.3、§7.2 第 6 条）。
        Message.error(event.message || '生成中断，请重试');
        settleOrDropAssistant(outcomeNotice(event.view), event.view.toolActivities);
        invalidateSessionLists(false);
        return;
      case 'status': {
        const settled = event.status.status !== undefined && event.status.status !== 'RUNNING';
        // 已经 COMPLETED 就别再说"不完整"：这时交给 refetch 拿服务端那份正文
        const notice = event.status.status === 'COMPLETED' ? executionStopNotice(event.status.finishReason) : statusNotice(event.status);
        settleOrDropAssistant(notice ? { code: notice.code, message: event.message ?? notice.message } : undefined, event.status.toolActivities);
        invalidateSessionLists(settled);
        return;
      }
      case 'unresolved':
        Message.error(event.message);
        settleOrDropAssistant({ code: 'STATUS_UNREADABLE', message: '暂时读不到这一轮的结果' });
        invalidateSessionLists(false);
        return;
    }
  }

  /** 停止当前运行，人工待发内容由服务端暂停保留。 */
  const stopGeneration = (): void => {
    if (sessionId !== undefined) void turn.stopSession(sessionId).catch((error) => Message.error(friendlyMessage(error, '停止未确认，请重试')));
    // 只刷新会话列表计数，保持中断后的列表状态准确。
    // 不要在此 invalidate 该会话的消息——后端落库思考链是异步的，此刻 refetch 会抢在落库前返回空并被缓存。
    void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
    // 取消会退还为回复预留的字节，容量条要跟着结算走（6a 复核留下的那条观察）
    void queryClient.invalidateQueries({ queryKey: storageQueryKey });
    // 等待真实终态；停止按钮不提前撤销已经受理的提问或已生成内容。
  };

  const clearSubmittedQueueDraft = (id: number): void => {
    const submitted = submittedQueueDrafts.current.get(id);
    if (!submitted) return;
    if (selectedSessionRef.current === id) {
      setMessage((current) => current === submitted.message ? '' : current);
      setAttachments((current) => current.filter((item) => !submitted.ids.has(item.id)));
    } else {
      const cached = draftCache.current.get(id);
      if (cached) draftCache.current.set(id, { ...cached,
        message: cached.message === submitted.message ? '' : cached.message,
        attachments: cached.attachments.filter((item) => !submitted.ids.has(item.id)) });
    }
    submittedQueueDrafts.current.delete(id);
  };
  const retryQueueSubmission = async (): Promise<void> => {
    if (queueSubmittingRef.current || sessionId === undefined) return;
    const target = sessionId;
    queueSubmittingRef.current = true; setQueueSubmitting(true);
    try { await queue.retrySubmission(); clearSubmittedQueueDraft(target); }
    catch (error) { Message.error(friendlyMessage(error, '提交结果仍未确认，内容已保留')); }
    finally { queueSubmittingRef.current = false; setQueueSubmitting(false); }
  };
  const submitMessage = async (continuation?: string, mode: 'queue' | 'steer' = 'queue'): Promise<void> => {
    if (firstSendPendingRef.current || queueSubmittingRef.current || queue.unconfirmed) return;
    if (!continuation && !message.trim() && !attachments.length) return;
    if (!(configQuery.data?.hasKey || configQuery.data?.authMode === 'none')) {
      Message.warning('请先在设置中配置 API Key');
      return;
    }
    const text = continuation ?? (message.trim() || '请分析附件内容');
    const displayText = [continuation ?? message.trim(), ...attachments.map((item) => `[附件：${item.name}]`)]
      .filter(Boolean).join('\n\n') || '请分析附件内容';
    const images = attachments.filter((item) => item.kind === 'image');
    const content: string | ChatContentPart[] = images.length
      ? [{ type: 'text', text }, ...images.map((item) => ({ type: 'image_url' as const, image_url: { url: item.value } }))]
      : text;
    // clientId 服务端限 64 码点且只用于本轮关联，不能拿文件名当身份
    const turnAttachments: TurnAttachment[] = attachments.map((item, index) => (item.kind === 'image'
      ? { clientId: `c${index}`, kind: 'image', name: item.name, mimeType: item.mime, dataBase64: item.value }
      : { clientId: `c${index}`, kind: 'text', name: item.name, mimeType: item.mime || 'text/plain', text: item.value }));
    const inputSnapshot: Omit<TurnInput, 'sessionId'> = {
      userText: text,
      ...(turnAttachments.length ? { attachments: turnAttachments } : {}),
      ...(pageContext.kind === 'none' ? {} : {
        pageContext: {
          kind: pageContext.kind,
          pageId: pageContext.notePageId,
          ...(contextMarkdown ? { markdown: contextMarkdown } : {})
        }
      }),
      ...(retrievalOptions ? { retrievalOptions } : {})
    };
    if (sessionId !== undefined && (streaming || queue.data?.items?.length || queue.data?.paused || mode === 'steer')) {
      const submittedSession = sessionId;
      submittedQueueDrafts.current.set(submittedSession, { message, ids: new Set(attachments.map((item) => item.id)) });
      queueSubmittingRef.current = true; setQueueSubmitting(true);
      try {
        await queue.submit({ ...inputSnapshot, sessionId }, mode, mode === 'steer' ? turn.requestId ?? undefined : undefined);
        clearSubmittedQueueDraft(submittedSession);
      } catch (error) { Message.error(friendlyMessage(error, '提交未确认，内容已保留，请重试核对')); }
      finally { queueSubmittingRef.current = false; setQueueSubmitting(false); }
      return;
    }
    let targetSessionId = sessionId;
    const isFirstSend = targetSessionId === undefined;
    if (isFirstSend) {
      // 同步锁防止同一渲染内的双击/连续回车重复创建；失败前保留输入和附件。
      firstSendPendingRef.current = true;
      try {
        targetSessionId = (await createSessionMutation.mutateAsync()).id;
      } catch (error) {
        firstSendPendingRef.current = false;
        Message.error(friendlyMessage(error, '创建会话失败，请重试'));
        return;
      }
    }
    const input: TurnInput = { ...inputSnapshot, sessionId: targetSessionId! };
    const userKey = nextMessageKey();
    const assistantKey = nextMessageKey();
    turnTargetRef.current = { userKey, assistantKey };
    setMessages((current) => [...current, { role: 'user', content, displayContent: displayText, _key: userKey },
      { role: 'assistant', content: '', streaming: true, reasoning: '', _key: assistantKey }]);
    setMessage('');
    setAttachments([]);
    if (isFirstSend) pendingFirstTurnRef.current = input;
    else turn.send(input);
  };
  const send = (): void => { void submitMessage(); };

  useEffect(() => {
    const input = pendingFirstTurnRef.current;
    if (!input || input.sessionId !== sessionId) return;
    // useAiChatTurn 已更新当前会话与回调，再接受首轮的同步/快速事件。
    pendingFirstTurnRef.current = null;
    firstSendPendingRef.current = false;
    turn.send(input);
  });

  const insertMutation = useMutation({
    mutationFn: async (markdown: string) => {
      if (targetPageId === undefined) throw new Error('请选择目标笔记页面');
      const page = await get<NotePage>(`/api/note-pages/${targetPageId}`);
      return put(`/api/note-pages/${targetPageId}`, { content: appendMarkdownBlock(page.content, markdown) });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['note-page', targetPageId] });
      void queryClient.invalidateQueries({ queryKey: ['note-pages'] });
      setPendingInsert(undefined);
      Message.success('已插入到笔记（可编辑 Markdown 块）');
    },
    onError: (error) => Message.error(friendlyMessage(error, '插入笔记失败，请稍后重试'))
  });

  const pendingOf = (items: Attachment[]): PendingAttachment[] => items.map((item) => ({ kind: item.kind, bytes: item.bytes, codepoints: item.codepoints }));

  const prepareAttachment = async (file: File, current: Attachment[]): Promise<PreparedAttachment> => {
    try {
      const kind: AttachmentKind | undefined = file.type.startsWith('image/') ? 'image'
        : isTextFile(file) ? 'text' : undefined;
      if (!kind) return { warning: '目前支持图片和文本类文件' };
      const pending: PendingAttachment = { kind, bytes: file.size };
      // 字节数先挡一道：文本超过 4 倍码点上限就必然超限，不必先读进内存
      const early = attachmentLimitError(pendingOf(current), pending);
      if (early) return { warning: early };
      if (kind === 'image') {
        const value = await readAsDataUrl(file);
        return { attachment: { id: nextAttachmentId(file), name: file.name, kind, value, mime: file.type, bytes: file.size } };
      }
      const value = await file.text();
      const codepoints = Array.from(value).length;
      const late = attachmentLimitError(pendingOf(current), { ...pending, codepoints });
      if (late) return { warning: late };
      return { attachment: { id: nextAttachmentId(file), name: file.name, kind, value, mime: file.type || 'text/plain', bytes: file.size, codepoints } };
    } catch (error) {
      return { error: friendlyMessage(error, '附件读取失败') };
    }
  };

  const addAttachments = async (files: File[]): Promise<void> => {
    // 逐个累积再比较：批量选择时后面的文件要按"已经收下的"重算门限，
    // 不能让每个文件各自拿同一份旧数组去比，否则一次选 10 个就能整批绕过 4 项上限。
    let next = attachments;
    const warned = new Set<string>();
    for (const file of files) {
      const result = await prepareAttachment(file, next);
      if ('attachment' in result) {
        next = [...next, result.attachment];
        setAttachments(next);
        continue;
      }
      if ('warning' in result) {
        if (warned.has(result.warning)) continue;
        warned.add(result.warning);
        Message.warning(result.warning);
      } else {
        Message.error(result.error);
      }
    }
  };

  const exportSession = async (targetId: number, format: 'md' | 'html' | 'json'): Promise<void> => {
    if (!window.api) {
      Message.error('导出功能仅在桌面应用中可用');
      return;
    }
    setExporting(true);
    try {
      const payload = await get<{ title: string; content: string; format: string }>(`/api/ai/sessions/${targetId}/export?format=${format}`);
      const extension = format === 'json' ? 'json' : format;
      const result = await window.api.exportFile.save({
        format: format === 'json' ? 'md' : format,
        suggestedName: `${safeFileName(payload.title)}.${extension}`,
        content: payload.content,
        html: format === 'html' ? payload.content : payload.content
      });
      if (!result.canceled) Message.success(`已导出到 ${result.path ?? '所选位置'}`);
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '导出会话失败');
    } finally {
      setExporting(false);
    }
  };

  const quickPrompts = [
    { label: '讲解当前内容', text: '请用通俗语言讲解当前页面的重点，并给出 3 个记忆要点。' },
    { label: '出练习题', text: '根据当前内容出 3 道选择题（含答案与简短解析），用 Markdown。' },
    { label: '总结成笔记', text: '把当前内容整理成结构化学习笔记（标题+要点+易错点）。' }
  ];

  return (
    <>
      {aiFabVisible ? (
        <button
          type="button"
          className={`ai-fab${aiOpen ? ' is-open' : ''}`}
          onClick={() => setAiOpen(!aiOpen)}
          title={aiAccelerator ? `AI 助手 (${describeAccelerator(aiAccelerator)})` : 'AI 助手'}
          aria-label="打开 AI 助手"
        >
          <Sparkles size={22} />
        </button>
      ) : null}
      <Drawer
        width={drawerWidth}
        title={
          <div className="ai-drawer-title">
            <Sparkles size={16} />
            <span>AI 学习助手</span>
            <Tag size="small" color={configQuery.data?.hasKey || configQuery.data?.authMode === 'none' ? 'green' : 'orange'}>{configQuery.data?.hasKey || configQuery.data?.authMode === 'none' ? '已配置' : '未配置'}</Tag>
          </div>
        }
        visible={aiOpen}
        onCancel={() => setAiOpen(false)}
        footer={null}
        unmountOnExit={false}
        className="ai-drawer"
      >
        <div
          className="ai-drawer-resize-handle"
          role="separator"
          aria-orientation="vertical"
          aria-label="拖动调整 AI 助手宽度（双击重置）"
          title="拖动调整宽度，双击重置"
          onPointerDown={onResizeStart}
          onPointerMove={onResizeMove}
          onPointerUp={onResizeEnd}
          onPointerCancel={onResizeEnd}
          onDoubleClick={onResizeDouble}
        />
        <div className="ai-drawer-body">
          <SessionNavigation visible={aiOpen} sessions={sessionsQuery.data ?? []} currentId={sessionId}
            busyId={streaming ? turn.activeSessionId : undefined}
            runtimes={turn.runtimes} reconnecting={turn.reconnecting} onStop={turn.stopSession}
            loading={sessionsQuery.isLoading} error={sessionsQuery.isError} creating={createSessionMutation.isPending}
            exporting={exporting} onRetry={() => { void sessionsQuery.refetch(); }} onCreate={handleNewSession}
            onSelect={selectSession}
            onRename={renameSession} onGenerate={generateSessionTitle}
            onDelete={async (id) => { await deleteSessionMutation.mutateAsync(id); }} onExport={exportSession} />

          <div className={`ai-context-card${contextMarkdown ? ' has-context' : ''}`}>
            <div className="ai-context-card-top">
              <div>
                <div className="ai-context-kicker">当前上下文</div>
                <div className="ai-context-title">{pageContext.kind === 'none' ? '未绑定页面（可手动提问）' : pageContext.title}</div>
              </div>
              <Space size={8}>
                <label className="ai-context-toggle" title="发送时检索相关笔记片段并返回引用">
                  <input type="checkbox" checked={retrieveNotes} onChange={(event) => onToggleRetrieveNotes(event.target.checked)} />
                  检索笔记
                </label>
                <label className="ai-context-toggle">
                  <input type="checkbox" checked={usePageContext} onChange={(event) => setUsePageContext(event.target.checked)} />
                  使用
                </label>
              </Space>
            </div>
            {retrieveNotes ? (
              <div className="ai-retrieval-scope">
                <Typography.Text type="secondary">检索范围</Typography.Text>
                <Select
                  size="small"
                  value={retrievalScope}
                  onChange={(value) => onChangeRetrievalScope(value as 'current' | 'all')}
                  style={{ width: 150 }}
                  aria-label="检索范围"
                >
                  <Select.Option value="current" disabled={pageContext.notebookId === undefined}>当前笔记本</Select.Option>
                  <Select.Option value="all">全部笔记本</Select.Option>
                </Select>
              </div>
            ) : null}
            {contextMarkdown ? <pre className="ai-context-preview">{contextMarkdown.slice(0, 480)}{contextMarkdown.length > 480 ? '…' : ''}</pre> : <p className="muted">{suppressPageMarkdown ? '已启用笔记检索：不再整页注入，发送时自动检索相关片段并返回引用。' : '在刷题、错题或笔记页打开助手时，会自动带上当前内容。'}</p>}
          </div>

          <div className="ai-quick-prompts">
            {quickPrompts.map((item) => (
              <button key={item.label} type="button" className="ai-chip" disabled={!(configQuery.data?.hasKey || configQuery.data?.authMode === 'none')} onClick={() => {
                setMessage(item.text);
              }}>{item.label}</button>
            ))}
          </div>

          <div
            className="ai-drawer-chat"
            ref={chatContainerRef}
            onScroll={onChatScroll}
          >
            {history.isError ? (
              <div className="empty-state ai-empty">
                <p>{friendlyMessage(history.error, '会话历史读取失败')}</p>
                <Button type="text" size="mini" onClick={history.retry}>重新读取</Button>
              </div>
            ) : history.isLoading ? (
              <div className="empty-state ai-empty"><p>加载会话消息…</p></div>
            ) : (<>
              {history.hasOlder ? (
                <div className="ai-history-older">
                  {history.residentCapped
                    ? <p className="ai-history-older-cap">已驻留最近 500 条，更早的记录仍在本地库里。</p>
                    : (
                      <Button type="text" size="mini" onClick={requestOlder} disabled={history.isLoadingOlder}>
                        {history.isLoadingOlder ? '正在载入更早的消息…' : '加载更早的消息'}
                      </Button>
                    )}
                </div>
              ) : null}
              {visibleMessages.length ? visibleMessages.map((item) => {
              const text = contentText(item.content, item.displayContent);
              const isAssistant = item.role === 'assistant';
              const turnSnapshot = item.turn?.requestId ? item.turn : null;
              const turnId = item.requestId ?? turnSnapshot?.requestId ?? null;
              return (
                <div key={item._key ?? `${item.role}-${item.id ?? 'x'}`} className={`chat-message ${item.role}`}>
                  {isAssistant && item.reasoning ? (
                    <details className="chat-reasoning" open={item.streaming}>
                      <summary>思考过程{item.streaming ? '（生成中…）' : ''}</summary>
                      <div className="chat-reasoning-body">{item.reasoning}</div>
                    </details>
                  ) : null}
                  {isAssistant && item.toolActivities?.length ? <ToolActivityGroup
                    activities={item.toolActivities} active={item.streaming} phase={item.toolPhase}
                    onOpenSession={(link) => {
                      const target = sessionsQuery.data?.find((entry) => entry.sessionLink === link);
                      if (target) selectSession(target.id);
                      else void sessionsQuery.refetch().then(({ data }) => {
                        const entry = data?.find((candidate) => candidate.sessionLink === link);
                        if (entry) selectSession(entry.id); else Message.error('目标会话不存在或已被删除');
                      });
                    }}
                    onDisclosure={() => { stickyBottomRef.current = false; }} /> : null}
                  {isAssistant && item.supplements?.some((s) => s.state === 'RECEIVED')
                    ? splitReply(text, item.supplements).map((part) => part.role === 'user'
                      ? <div className="chat-supplement" key={part.key}><small>插话 · 已接收</small><MarkdownContent value={part.text} onSessionLink={setSessionReference} />
                        {!!part.attachments?.length && <small>{part.attachments.join(' · ')}</small>}</div>
                      : <MarkdownContent key={part.key} value={part.text} onSessionLink={setSessionReference} />)
                    : <MarkdownContent value={text} onSessionLink={setSessionReference} />}
                  {item.streaming && !item.toolActivities?.length ? <span className="chat-stream-cursor" aria-hidden="true" /> : null}
                  {item.notice ? (
                    <div className="ai-retrieval-notice">
                      {item.notice.message ?? '笔记检索暂时不可用，本次回答未使用笔记内容'}
                      {isAssistant && item === visibleMessages.at(-1) && canContinueTurn(item.notice) && <Button size="mini" type="text"
                        disabled={streaming || !!message.trim() || attachments.length > 0}
                        title={message.trim() || attachments.length ? '请先处理输入框中的草稿' : '沿用已有结果，以新一轮预算继续'}
                        onClick={() => submitMessage('请继续完成上一轮尚未完成的任务，依据已有结果继续；已经确认成功的操作不要重复执行，写入结果不确定时先核对。')}>继续</Button>}
                    </div>
                  ) : null}
                  {isAssistant ? (
                    <>
                      {item.citations?.length ? (
                        <div className="ai-citations">
                          <div className="ai-citations-kicker">笔记引用（{item.citations.length}）</div>
                          <div className="ai-citation-list">
                            {item.citations.map((citation, citationIndex) => (
                              <button
                                key={`${citation.pageId}-${citation.chunkId}`}
                                type="button"
                                className="ai-citation-chip"
                                title={citation.snippet}
                                onClick={() => navigate(`/notebooks?pageId=${citation.pageId}`)}
                              >
                                <span className="ai-citation-index">{citationIndex + 1}</span>
                                <span className="ai-citation-title">{citation.title}</span>
                                {citation.headingPath ? <span className="ai-citation-heading">{citation.headingPath}</span> : null}
                              </button>
                            ))}
                          </div>
                        </div>
                      ) : null}
                      {turnSnapshot ? (
                        <MemoryUsageBadge
                          sessionId={sessionId}
                          requestId={turnSnapshot.requestId}
                          provided={turnSnapshot.memoryContext?.provided ?? []}
                          onOpenInspector={() => setInspectorTarget({ requestId: turnSnapshot.requestId, snapshot: turnSnapshot })}
                        />
                      ) : null}
                      <div className="chat-message-actions">
                        {text ? <Button type="text" size="mini" icon={<FilePlus2 size={14} />} onClick={() => setPendingInsert(text)}>插入笔记</Button> : null}
                        {turnId ? (
                          <Button type="text" size="mini" icon={<Layers size={14} />} onClick={() => setInspectorTarget({ requestId: turnId, snapshot: turnSnapshot ?? undefined })}>
                            本轮上下文
                          </Button>
                        ) : null}
                      </div>
                    </>
                  ) : null}
                </div>
              );
            }) : (
              <div className="empty-state ai-empty">
                <div>
                  <Sparkles size={28} />
                  <p>{sessionId === undefined ? '开始一段新对话' : '当前会话还没有消息。可新建多个会话分别保存不同主题的对话。'}</p>
                </div>
              </div>
            )}
            </>)}
          </div>

          {pendingInsert ? (
            <div className="ai-insert-bar">
              <Typography.Text type="secondary">插入到</Typography.Text>
              <Select size="small" placeholder="笔记本" value={targetNotebookId} onChange={(value) => { setTargetNotebookId(Number(value)); setTargetPageId(undefined); }} style={{ width: 120 }}>
                {notebooksQuery.data?.map((notebook) => <Select.Option key={notebook.id} value={notebook.id}>{notebook.title}</Select.Option>)}
              </Select>
              <Select size="small" placeholder="页面" value={targetPageId} onChange={(value) => setTargetPageId(Number(value))} style={{ width: 120 }}>
                {targetPagesQuery.data?.map((page) => <Select.Option key={page.id} value={page.id}>{page.title}</Select.Option>)}
              </Select>
              <Space size={4}>
                <Button size="mini" type="primary" loading={insertMutation.isPending} onClick={() => pendingInsert && insertMutation.mutate(pendingInsert)}>确认插入</Button>
                <Button size="mini" type="text" onClick={() => setPendingInsert(undefined)}>取消</Button>
              </Space>
            </div>
          ) : null}

          {attachments.length ? (
            <div className="attachment-list">
              {attachments.map((item) => (
                <div className="attachment-chip" key={item.id}>
                  {item.kind === 'image' ? <img src={item.value} alt={item.name} /> : <Paperclip size={14} />}
                  <span>{item.name}</span>
                  <Button type="text" size="mini" icon={<X size={14} />} onClick={() => setAttachments((current) => current.filter((entry) => entry.id !== item.id))} aria-label={`移除${item.name}`} />
                </div>
              ))}
            </div>
          ) : null}

          <div className="ai-storage-row">
            <ChatStorageMeter enabled={aiOpen} />
            <ChatHistoryCleanup
              sessionId={sessionId}
              enabled={aiOpen}
              messages={history.messages}
              hasOlder={history.hasOlder}
              isLoadingOlder={history.isLoadingOlder}
              loadOlder={history.loadOlder}
            />
          </div>

          {queue.unconfirmed && !queueSubmitting && <div className="chat-queue-uncertain" role="status">上次提交结果待确认
            <Button type="text" size="mini" loading={queueSubmitting} onClick={() => void retryQueueSubmission()}>核对提交</Button>
          </div>}
          <ChatQueue sessionId={sessionId} view={queue.data} runningId={streaming ? turn.requestId : null}
            loadingError={queue.isError} retry={() => void queue.refetch()} action={queue.action} pause={queue.pause} />
          <div className={`chat-compose ai-drawer-compose${queue.data?.items?.length || queue.data?.steering?.length ? ' has-queue' : ''}`}>
            <TextArea
              autoSize={{ minRows: 2, maxRows: 6 }}
              disabled={createSessionMutation.isPending}
              value={message}
              onChange={setMessage}
              onKeyDown={(event) => {
                // 中文输入法组词阶段的 Enter 用于选字，不发送
                if (event.nativeEvent.isComposing || event.keyCode === 229) return;
                if (matchesAny(event, useUiStore.getState().shortcutConfig.aiSend)) {
                  event.preventDefault();
                  send();
                } else if (streaming && event.key === 'Enter' && event.ctrlKey && !event.shiftKey && !event.altKey && !event.metaKey) {
                  event.preventDefault();
                  if (!queue.data?.editingId) void submitMessage(undefined, 'steer');
                }
              }}
              placeholder={streaming ? '正在回复，可先输入下一条问题' : composePlaceholder}
            />
            <input
              ref={fileInput}
              type="file"
              hidden
              multiple
              accept="image/*,.txt,.md,.markdown,.json,.csv,.log,.java,.kt,.ts,.tsx,.js,.jsx,.py,.go,.rs,.sql,.xml,.yaml,.yml,.css,.html"
              onChange={(event) => {
                const files = Array.from(event.target.files ?? []);
                event.target.value = '';
                void addAttachments(files);
              }}
            />
            <Button type="secondary" icon={<Paperclip size={16} />} disabled={createSessionMutation.isPending} onClick={() => fileInput.current?.click()} aria-label="添加附件" />
            {streaming && <>
              <Button className="ai-compose-steer" type="secondary" title="提交，但不中断模型运行" aria-label="插话"
                disabled={queueSubmitting || queue.unconfirmed || !!queue.data?.editingId || (!message.trim() && !attachments.length)} onClick={() => void submitMessage(undefined, 'steer')}>插话</Button>
              <Button type="secondary" icon={<Square size={14} />} aria-label="中断生成" title="停止回复" onClick={stopGeneration} />
            </>}
            <Button
              type="primary"
              icon={<Send size={16} />}
              loading={createSessionMutation.isPending || queueSubmitting}
              disabled={!(configQuery.data?.hasKey || configQuery.data?.authMode === 'none') || createSessionMutation.isPending || queueSubmitting || queue.unconfirmed || (streaming && !message.trim() && !attachments.length)}
              onClick={send}
              aria-label={streaming || queue.data?.items?.length ? '加入队列' : '发送'}
              title={streaming || queue.data?.items?.length ? '加入队列' : '发送'}
            />
          </div>
          {!(configQuery.data?.hasKey || configQuery.data?.authMode === 'none') ? <Typography.Text type="secondary" className="ai-drawer-hint">在「设置 → AI 连接」中配置供应商并应用模型后即可使用。</Typography.Text> : null}
          {exporting ? <Typography.Text type="secondary" className="ai-drawer-hint"><Download size={12} style={{ marginRight: 4 }} />正在导出会话…</Typography.Text> : null}
        </div>

        {inspectorTarget ? (
          <ChatContextInspector
            sessionId={sessionId}
            requestId={inspectorTarget.requestId}
            turn={inspectorTarget.snapshot}
            open
            onClose={() => setInspectorTarget(null)}
          />
        ) : null}
      </Drawer>
      {sessionReference ? <SessionReferencePreview key={sessionReference} link={sessionReference}
        onClose={() => setSessionReference(undefined)} onSessionLink={setSessionReference} /> : null}
    </>
  );
}
