import { useEffect, useMemo, useRef, useState, type UIEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Drawer, Dropdown, Input, Message, Modal, Select, Space, Tag, Typography } from '@arco-design/web-react';
import type { RefInputType } from '@arco-design/web-react/es/Input/interface';
import { Download, FilePlus2, MoreHorizontal, Paperclip, Plus, Send, Sparkles, Square, Trash2, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { AiChatSession, AiConfig, ChatCitation, ChatContentPart, ChatMessage, NotePage, Notebook, RetrievalNotice, RetrievalOptions } from '../lib/types';

/** GET /api/ai/sessions/{id}/messages 的服务端响应元素：使用 retrievalNotice 字段名，回到前端时映射为 notice。 */
interface ServerChatMessage extends Omit<ChatMessage, 'notice'> {
  retrievalNotice?: RetrievalNotice;
}
import { appendMarkdownBlock } from '../lib/aiContext';
import { safeFileName } from '../lib/export';
import { LS_RETRIEVAL_SCOPE, LS_RETRIEVE_NOTES, readBoolPref, readStringPref, writeBoolPref, writeStringPref } from '../lib/sessionPrefs';
import { streamChat } from '../lib/aiStream';
import { MarkdownContent } from './markdown/MarkdownRenderer';
import { useUiStore } from '../stores/uiStore';

const { TextArea } = Input;

type AttachmentKind = 'text' | 'image';
interface Attachment {
  id: string;
  name: string;
  kind: AttachmentKind;
  value: string;
  mime: string;
}

interface ChatRequest {
  content: string | ChatContentPart[];
  displayContent: string;
}

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
  const toggleAi = useUiStore((state) => state.toggleAi);
  const pageContext = useUiStore((state) => state.pageContext);
  const configQuery = useQuery({
    queryKey: ['ai-config'],
    queryFn: async () => {
      const raw = await get<AiConfig>('/api/ai/config');
      // 兼容新旧 API：优先 chat 槽
      const chat = raw.chat ?? raw;
      return { ...raw, hasKey: chat.hasKey, provider: chat.provider, endpoint: chat.endpoint, model: chat.model, chat };
    }
  });
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks'), enabled: aiOpen });
  const sessionsQuery = useQuery({
    queryKey: ['ai-sessions'],
    queryFn: () => get<AiChatSession[]>('/api/ai/sessions'),
    enabled: aiOpen
  });

  const [sessionId, setSessionId] = useState<number>();
  const [message, setMessage] = useState('');
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
  const [renaming, setRenaming] = useState(false);
  const [renameDraft, setRenameDraft] = useState('');
  const renameInputRef = useRef<RefInputType>(null);

  // 稳定消息 key：消息创建时分配一次，后续 refetch 也保持稳定，避免列表 remount 造成滚动/输入抖动。
  const messageKeyCounter = useRef(0);
  const nextMessageKey = (): string => {
    messageKeyCounter.current += 1;
    return `m${messageKeyCounter.current}`;
  };
  // 标记用户最近一次显式选中的会话 id，避免 sessions effect 在 refetch 时把用户切走。
  const lastExplicitSessionIdRef = useRef<number | undefined>(undefined);
  // 流式 → 非流式回退时，chatMutation.onSuccess 复用同一 userKey 保持稳定。
  const pendingFallbackUserKeyRef = useRef<string | null>(null);
  // 流式期间用户已向上滚动 → 暂停自动滚到底；回到接近底部时自动恢复。
  const chatContainerRef = useRef<HTMLDivElement>(null);
  const stickyBottomRef = useRef(true);
  const lastScrollTopRef = useRef(0);

  const chatAbortRef = useRef<(() => void) | null>(null);
  const [streaming, setStreaming] = useState(false);

  const targetPagesQuery = useQuery({
    queryKey: ['ai-drawer-pages', targetNotebookId],
    queryFn: () => get<NotePage[]>(`/api/notebooks/${targetNotebookId}/pages`),
    enabled: targetNotebookId !== undefined && aiOpen
  });

  const messagesQuery = useQuery({
    queryKey: ['ai-session-messages', sessionId],
    queryFn: () => get<ServerChatMessage[]>(`/api/ai/sessions/${sessionId}/messages`),
    enabled: aiOpen && sessionId !== undefined
  });

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
    if (!sessionsQuery.data?.length) return;
    // 用户最近一次显式选中的会话若仍在列表中（哪怕列表 refetch 重新排过序），不要把它重置到 data[0]。
    if (lastExplicitSessionIdRef.current !== undefined &&
        sessionsQuery.data.some((item) => item.id === lastExplicitSessionIdRef.current)) {
      if (sessionId !== lastExplicitSessionIdRef.current) setSessionId(lastExplicitSessionIdRef.current);
      return;
    }
    // 仅在「当前没有选中会话」或「选中的会话已不存在（被删除）」时回退到 data[0]，避免新建会话后被立刻切走。
    if (sessionId === undefined || !sessionsQuery.data.some((item) => item.id === sessionId)) {
      setSessionId(sessionsQuery.data[0].id);
    }
  }, [sessionId, sessionsQuery.data]);

  useEffect(() => {
    if (!messagesQuery.data) return;
    // 合并本地与服务端消息：
    // 1) 给没有 _key 的本地消息分配稳定 key；
    // 2) 遍历服务端消息：按 role+content 找本地匹配项，存在则「升级」为带 id/reasoning/citations 的版本并保留 _key；
    //    没有匹配但角色为 assistant 且本地存在流式占位（空内容 + streaming）时，替换占位为服务端版本；
    // 3) 保留未被消费的本地消息（如网络尚未到达的「user 已发 / 流式进行中」消息）。
    setMessages((local) => {
      type Keyed = ChatMessage & { _key: string };
      const localWithKeys: Keyed[] = local.map((m) => m._key ? (m as Keyed) : { ...m, _key: nextMessageKey() });
      const consumed = new Set<string>();
      const serverMessages: ChatMessage[] = messagesQuery.data.map((item, index) => {
        const contentText = typeof item.content === 'string' ? item.content : '';
        const sameContentMatch = localWithKeys.find(
          (m) => !consumed.has(m._key) && m.role === item.role && typeof m.content === 'string' && m.content === contentText
        );
        if (sameContentMatch) {
          consumed.add(sameContentMatch._key);
          return {
            ...sameContentMatch,
            id: item.id,
            content: item.content,
            displayContent: contentText,
            createdAt: item.createdAt,
            reasoning: item.reasoning,
            citations: item.citations,
            notice: item.retrievalNotice
          };
        }
        // 流式占位 → 服务端真实消息的替换：
        // - 内容完全相同（少见，正常是 sameContentMatch 已匹配）；
        // - 本地占位是空内容 + streaming 标志；
        // - 本地占位的累积文本是服务端文本的前缀（流式中、增量逐字符到达，最后一次 refetch 几乎一致）；
        // - 服务端文本以本地累积结尾（可能服务端做了轻微 trim/规范化）。
        if (item.role === 'assistant' && contentText) {
          const placeholder = localWithKeys.find(
            (m) => !consumed.has(m._key) && m.role === 'assistant' && m.streaming && (
              typeof m.content !== 'string' || m.content === '' ||
              (typeof m.content === 'string' && contentText.startsWith(m.content)) ||
              (typeof m.content === 'string' && m.content.startsWith(contentText))
            )
          );
          if (placeholder) {
            consumed.add(placeholder._key);
            return {
              ...placeholder,
              id: item.id,
              content: item.content,
              displayContent: contentText,
              createdAt: item.createdAt,
              reasoning: item.reasoning,
              citations: item.citations,
              notice: item.retrievalNotice,
              streaming: false
            };
          }
        }
        return {
          id: item.id,
          role: item.role,
          content: item.content,
          displayContent: contentText,
          createdAt: item.createdAt,
          reasoning: item.reasoning,
          citations: item.citations,
          notice: item.retrievalNotice,
          _key: `s${item.id ?? `idx-${index}`}`
        };
      });
      const pending = localWithKeys.filter((m) => !consumed.has(m._key));
      return [...pending, ...serverMessages];
    });
  }, [messagesQuery.data]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'j') {
        event.preventDefault();
        toggleAi();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [toggleAi]);

  // 聊天列表自动滚动：仅当用户接近底部时粘底；用户上滑阅读时尊重其位置。
  const onChatScroll = (event: UIEvent<HTMLDivElement>): void => {
    const el = event.currentTarget;
    const distanceFromBottom = el.scrollHeight - el.clientHeight - el.scrollTop;
    stickyBottomRef.current = distanceFromBottom < 80;
    lastScrollTopRef.current = el.scrollTop;
  };
  useEffect(() => {
    const el = chatContainerRef.current;
    if (!el) return;
    if (!stickyBottomRef.current) return;
    el.scrollTop = el.scrollHeight;
  }, [messages]);
  useEffect(() => {
    if (!streaming) return;
    if (!stickyBottomRef.current) return;
    let frame = 0;
    const tick = (): void => {
      const el = chatContainerRef.current;
      if (el) el.scrollTop = el.scrollHeight;
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
      // 乐观写入缓存：让新会话立即出现在列表中，避免 refetch 完成前旧列表触发
      // 会话 effect 把选中会话切回旧值（表现为「第一次点新建不跳转」）。
      queryClient.setQueryData<AiChatSession[]>(['ai-sessions'], (old) => {
        const list = old ?? [];
        if (list.some((item) => item.id === session.id)) return list;
        return [{ ...session, messageCount: session.messageCount ?? 0 }, ...list];
      });
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      lastExplicitSessionIdRef.current = session.id;
      setSessionId(session.id);
      setMessages([]);
    },
    onError: (error) => Message.error(error.message)
  });

  /** 新建/复用空白会话：列表中已存在 messageCount===0 的会话则复用，否则创建。 */
  const handleNewSession = (): void => {
    if (createSessionMutation.isPending) return;
    const sessions = sessionsQuery.data ?? [];
    const blank = sessions.find((item) => !item.archived && (item.messageCount ?? 0) === 0);
    if (blank) {
      lastExplicitSessionIdRef.current = blank.id;
      setSessionId(blank.id);
      setMessages([]);
      return;
    }
    createSessionMutation.mutate();
  };

  const renameSessionMutation = useMutation({
    mutationFn: ({ id, title }: { id: number; title: string }) => put<AiChatSession>(`/api/ai/sessions/${id}`, { title }),
    onSuccess: (session) => {
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      setSessionId(session.id);
      setRenaming(false);
      Message.success('会话已重命名');
    },
    onError: (error) => Message.error(error.message)
  });

  const currentSession = sessionsQuery.data?.find((item) => item.id === sessionId);

  useEffect(() => {
    if (!renaming) return;
    const timer = window.setTimeout(() => {
      const input = renameInputRef.current?.dom;
      if (!input) return;
      input.focus();
      input.select();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [renaming]);

  const beginRename = (): void => {
    if (sessionId === undefined || renameSessionMutation.isPending) return;
    setRenameDraft(currentSession?.title ?? '');
    setRenaming(true);
  };

  const cancelRename = (): void => {
    setRenaming(false);
    setRenameDraft('');
  };

  const commitRename = (): void => {
    if (sessionId === undefined || renameSessionMutation.isPending) return;
    const title = renameDraft.trim();
    if (!title) {
      Message.warning('标题不能为空');
      const input = renameInputRef.current?.dom;
      input?.focus();
      input?.select();
      return;
    }
    if (title === (currentSession?.title ?? '')) {
      cancelRename();
      return;
    }
    renameSessionMutation.mutate({ id: sessionId, title });
  };

  const deleteSessionMutation = useMutation({
    mutationFn: (id: number) => del(`/api/ai/sessions/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      lastExplicitSessionIdRef.current = undefined;
      setSessionId(undefined);
      setMessages([]);
      Message.success('会话已删除');
    },
    onError: (error) => Message.error(error.message)
  });

  // 删除确认使用 Arco Modal 而非 window.confirm：
  // Electron 渲染进程的原生同步对话框关闭后 webContents 键盘焦点可能不恢复（鼠标正常、键盘失效，
  // 窗口失焦/复焦后才自愈）——这正是「删除会话后输入框无法输入、切软件切回又好了」的偶发根因。
  const [deleteConfirmSession, setDeleteConfirmSession] = useState<AiChatSession | null>(null);

  const chatMutation = useMutation({
    mutationFn: (request: ChatRequest) => post<{ reply: string; sessionId: number; citations?: ChatCitation[]; retrievalNotice?: RetrievalNotice }>('/api/ai/chat', {
      sessionId,
      messages: [
        ...(contextMarkdown
          ? [{ role: 'system' as const, content: `你是学习助手。请结合以下当前页面上下文回答，必要时用 Markdown 与 LaTeX。\n\n${contextMarkdown}` }]
          : []),
        ...messages.map(({ role, content }) => ({ role, content })),
        { role: 'user', content: request.content }
      ],
      ...(retrievalOptions ? { retrievalOptions } : {})
    }),
    onSuccess: (result, request) => {
      if (result.sessionId && result.sessionId !== sessionId) {
        setSessionId(result.sessionId);
        lastExplicitSessionIdRef.current = result.sessionId;
      }
      // 为本地追加的消息分配稳定 _key：等价的流式失败回退路径也会复用 userKey。
      const userKey = pendingFallbackUserKeyRef.current ?? nextMessageKey();
      const assistantKey = nextMessageKey();
      pendingFallbackUserKeyRef.current = null;
      setMessages((current) => [
        ...current,
        { role: 'user', content: request.content, displayContent: request.displayContent, _key: userKey },
        { role: 'assistant', content: result.reply, citations: result.citations, notice: result.retrievalNotice, _key: assistantKey }
      ]);
      setMessage('');
      setAttachments([]);
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      void queryClient.invalidateQueries({ queryKey: ['ai-session-messages', result.sessionId ?? sessionId] });
    },
    onError: (error) => {
      pendingFallbackUserKeyRef.current = null;
      Message.error(friendlyMessage(error, 'AI 对话失败，请稍后重试'));
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

  /** 流式发送：优先走 /api/ai/chat/stream（主模型 streaming 开启时），失败自动回退非流式。 */
  const sendStreaming = (request: ChatRequest): void => {
    // 二次防护：上层 send() 已做并发检查，此处只对极端情况（abort 中）做最后一道保险。
    if (streaming || chatMutation.isPending) return;
    const userKey = nextMessageKey();
    const assistantKey = nextMessageKey();
    // 会话快照：流式回调一律校验会话未变，防止流式期间切换会话后增量写进新会话的消息。
    const streamingSessionId = sessionId;
    const patchSessionMessage = (updater: (prev: ChatMessage) => ChatMessage | null): void => {
      setMessages((current) => {
        if (sessionId !== streamingSessionId) return current;
        const next = [...current];
        const last = next[next.length - 1];
        if (last && last.role === 'assistant') {
          const resolved = updater(last);
          if (resolved) next[next.length - 1] = resolved;
        }
        return next;
      });
    };
    const userMessage: ChatMessage = { role: 'user', content: request.content, displayContent: request.displayContent, _key: userKey };
    const placeholder: ChatMessage = { role: 'assistant', content: '', streaming: true, reasoning: '', _key: assistantKey };
    setMessages((current) => {
      if (sessionId !== streamingSessionId) return current;
      return [...current, userMessage, placeholder];
    });
    setMessage('');
    setAttachments([]);
    setStreaming(true);
    const payload = {
      sessionId,
      messages: [
        ...(contextMarkdown
          ? [{ role: 'system' as const, content: `你是学习助手。请结合以下当前页面上下文回答，必要时用 Markdown 与 LaTeX。\n\n${contextMarkdown}` }]
          : []),
        ...messages.map(({ role, content }) => ({ role, content })),
        { role: 'user', content: request.content }
      ],
      ...(retrievalOptions ? { retrievalOptions } : {})
    };
    let aborted = false;
    const finishStream = (): void => {
      // 任何出口都要：清状态 + 清 abort 句柄，避免 onError/重复 done 再次进入分支造成重复占位。
      setStreaming(false);
      chatAbortRef.current = null;
    };
    // 手动中断：中止 fetch（后端 emitter.send 失败 → 停止读上游）。
    // 已有部分正文 → 定稿占位（后端照常落库部分内容）；零正文（思考阶段）→ 回滚占位与 user 消息。
    // 两个历史缺陷（勿回退）：
    // ① 不能用 streaming state 做重入守卫——此函数在 setStreaming(true) 同一渲染周期内创建，
    //    闭包捕获的 streaming 恒为 false，会让中断按钮永远空转（死按钮根因之一）。aborted 标志已足够防重入。
    // ② streamChat 返回的 fetch abort 函数是异步到达的，必须经 abortFetch 捕获并在中断时调用，
    //    否则只停了前端渲染、SSE 连接与后端上游生成照旧（死按钮根因之二）。
    let abortFetch: (() => void) | null = null;
    const stopStreaming = (): void => {
      if (aborted) return;
      aborted = true;
      abortFetch?.();
      chatAbortRef.current = null;
      setStreaming(false);
      if (sessionId !== streamingSessionId) return;
      setMessages((current) => {
        const next = [...current];
        const last = next[next.length - 1];
        if (!(last && last.role === 'assistant' && last.streaming)) return next;
        const text = typeof last.content === 'string' ? last.content : '';
        const reasoning = typeof last.reasoning === 'string' ? last.reasoning : '';
        if (text.trim() || reasoning.trim()) {
          // 思考链也算已生成内容：正文或思考链任一非空都定稿保留，绝不静默丢弃。
          next[next.length - 1] = { ...last, content: text, reasoning: reasoning || undefined, streaming: false };
        } else {
          // 真正零生成（刚开始连接）：回滚占位与 user 消息，方便立即重发。
          next.pop();
          const prev = next[next.length - 1];
          if (prev && prev.role === 'user' && prev._key === userKey) next.pop();
        }
        return next;
      });
    };
    chatAbortRef.current = stopStreaming;
    void streamChat('/api/ai/chat/stream', payload, {
      onText: (delta) => {
        if (aborted) return;
        patchSessionMessage((prev: ChatMessage) => ({ ...prev, content: String(prev.content) + delta }));
      },
      onReasoning: (delta) => {
        if (aborted) return;
        patchSessionMessage((prev: ChatMessage) => ({ ...prev, reasoning: (prev.reasoning ?? '') + delta }));
      },
      onDone: ({ reply }) => {
        if (aborted) return;
        aborted = true;
        // 本地已通过 onReasoning 增量累积 reasoning，done 事件携带的 reasoning 与之等价；保留本地版本即可。
        patchSessionMessage((prev: ChatMessage) => ({
          ...prev,
          // reply 为空时保留已流式累积的内容，避免 content 变成 undefined 触发渲染崩溃。
          content: reply || (typeof prev.content === 'string' ? prev.content : ''),
          reasoning: prev.reasoning || undefined,
          streaming: false
        }));
        finishStream();
        void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
        void queryClient.invalidateQueries({ queryKey: ['ai-session-messages', streamingSessionId] });
      },
      onError: (streamError) => {
        if (aborted) return;
        aborted = true;
        finishStream();
        // 流式失败（未配置/后端不支持等）：移除占位与刚插入的 user 消息，回退非流式重发一次。
        setMessages((current) => {
          if (sessionId !== streamingSessionId) return current;
          const next = [...current];
          const last = next[next.length - 1];
          if (last && last.role === 'assistant' && last.streaming) next.pop();
          return next;
        });
        setMessages((current) => {
          if (sessionId !== streamingSessionId) return current;
          // 移除刚插入的 user 消息（由 chatMutation.onSuccess 重新追加）
          const next = [...current];
          if (next.length && next[next.length - 1].role === 'user' && next[next.length - 1]._key === userKey) next.pop();
          return next;
        });
        // 让 chatMutation 复用同一个 userKey，保持消息 key 跨回退稳定。
        pendingFallbackUserKeyRef.current = userKey;
        chatMutation.mutate(request);
      }
    }).then((abort) => {
      // streamChat 的返回值（fetch abort）异步到达；中断时调用它真正掐断 SSE 连接。
      if (aborted) abort();
      else abortFetch = abort;
    });
  };

  const send = (): void => {
    // 流式进行中：发送键已变为中断键，由 onClick 侧处理。
    if (streaming || chatMutation.isPending) return;
    if (!message.trim() && !attachments.length) return;
    if (!configQuery.data?.hasKey) {
      Message.warning('请先在设置中配置 API Key');
      return;
    }
    if (sessionId === undefined) {
      Message.warning('请先选择或新建会话');
      return;
    }
    const textParts = [
      message.trim(),
      ...attachments.filter((item) => item.kind === 'text').map((item) => `[文件：${item.name}]\n${item.value}`)
    ].filter(Boolean);
    const text = textParts.join('\n\n') || '请分析附件内容';
    const displayText = [message.trim(), ...attachments.map((item) => `[附件：${item.name}]`)].filter(Boolean).join('\n\n') || '请分析附件内容';
    const images = attachments.filter((item) => item.kind === 'image');
    const content: string | ChatContentPart[] = images.length
      ? [{ type: 'text', text }, ...images.map((item) => ({ type: 'image_url' as const, image_url: { url: item.value } }))]
      : text;
    const request: ChatRequest = { content, displayContent: displayText };
    const slot = configQuery.data?.chat ?? configQuery.data;
    if (slot?.streaming !== false) sendStreaming(request);
    else chatMutation.mutate(request);
  };

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

  const loadAttachment = async (file: File): Promise<void> => {
    try {
      if (file.size > 8 * 1024 * 1024) {
        Message.warning('单个附件不能超过 8 MB');
        return;
      }
      if (file.type.startsWith('image/')) {
        const value = await readAsDataUrl(file);
        setAttachments((current) => [...current, { id: `${file.name}-${file.lastModified}`, name: file.name, kind: 'image', value, mime: file.type }]);
        return;
      }
      if (!isTextFile(file)) {
        Message.warning('目前支持图片和文本类文件');
        return;
      }
      const value = (await file.text()).slice(0, 200_000);
      setAttachments((current) => [...current, { id: `${file.name}-${file.lastModified}`, name: file.name, kind: 'text', value, mime: file.type || 'text/plain' }]);
    } catch (error) {
      Message.error(friendlyMessage(error, '附件读取失败'));
    }
  };

  const exportSession = async (format: 'md' | 'html' | 'json'): Promise<void> => {
    if (sessionId === undefined) {
      Message.warning('请先选择会话');
      return;
    }
    if (!window.api) {
      Message.error('导出功能仅在桌面应用中可用');
      return;
    }
    setExporting(true);
    try {
      const payload = await get<{ title: string; content: string; format: string }>(`/api/ai/sessions/${sessionId}/export?format=${format}`);
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
      <button type="button" className={`ai-fab${aiOpen ? ' is-open' : ''}`} onClick={() => setAiOpen(!aiOpen)} title="AI 助手 (Ctrl+J)" aria-label="打开 AI 助手">
        <Sparkles size={22} />
      </button>
      <Drawer
        width={drawerWidth}
        title={
          <div className="ai-drawer-title">
            <Sparkles size={16} />
            <span>AI 学习助手</span>
            <Tag size="small" color={configQuery.data?.hasKey ? 'green' : 'orange'}>{configQuery.data?.hasKey ? '已配置' : '未配置'}</Tag>
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
          <div className="ai-session-bar">
            {renaming ? (
              <Input
                ref={renameInputRef}
                size="small"
                className="ai-session-title-input"
                value={renameDraft}
                disabled={renameSessionMutation.isPending}
                onChange={setRenameDraft}
                onBlur={() => commitRename()}
                onPressEnter={(event) => {
                  event.preventDefault();
                  commitRename();
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') {
                    event.preventDefault();
                    cancelRename();
                  }
                }}
                aria-label="重命名会话"
              />
            ) : (
              <button
                type="button"
                className="ai-session-title"
                title="双击重命名"
                disabled={sessionId === undefined}
                onDoubleClick={(event) => {
                  event.preventDefault();
                  beginRename();
                }}
              >
                <span className="ai-session-title-text">{currentSession?.title ?? (sessionsQuery.isLoading ? '加载会话…' : '选择会话')}</span>
                {currentSession?.messageCount ? <span className="ai-session-title-count">{currentSession.messageCount}</span> : null}
              </button>
            )}
            <Select
              size="small"
              placeholder="切换"
              value={sessionId}
              onChange={(value) => {
                setRenaming(false);
                const next = Number(value);
                lastExplicitSessionIdRef.current = next;
                setSessionId(next);
                setMessages([]);
              }}
              style={{ width: 108 }}
              loading={sessionsQuery.isLoading}
              aria-label="切换会话"
            >
              {sessionsQuery.data?.map((session) => (
                <Select.Option key={session.id} value={session.id}>
                  {session.title}{session.messageCount ? `（${session.messageCount}）` : ''}
                </Select.Option>
              ))}
            </Select>
            <Button size="small" type="outline" icon={<Plus size={14} />} loading={createSessionMutation.isPending} onClick={handleNewSession}>新建</Button>
            <Dropdown
              droplist={
                <div className="ai-session-menu">
                  <button type="button" disabled={exporting || sessionId === undefined} onClick={() => void exportSession('md')}>导出 Markdown</button>
                  <button type="button" disabled={exporting || sessionId === undefined} onClick={() => void exportSession('html')}>导出 HTML</button>
                  <button type="button" disabled={exporting || sessionId === undefined} onClick={() => void exportSession('json')}>导出 JSON</button>
                  <button
                    type="button"
                    className="danger"
                    disabled={deleteSessionMutation.isPending || !sessionId || (sessionsQuery.data?.length ?? 0) <= 1}
                    onClick={() => {
                      if (sessionId === undefined) return;
                      const target = sessionsQuery.data?.find((session) => session.id === sessionId) ?? null;
                      setDeleteConfirmSession(target);
                    }}
                  >
                    <Trash2 size={13} /> 删除会话
                  </button>
                </div>
              }
              position="br"
            >
              <Button size="small" type="text" icon={<MoreHorizontal size={16} />} aria-label="会话更多操作" />
            </Dropdown>
          </div>

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
              <button key={item.label} type="button" className="ai-chip" disabled={!configQuery.data?.hasKey} onClick={() => {
                setMessage(item.text);
              }}>{item.label}</button>
            ))}
          </div>

          <div
            className="ai-drawer-chat"
            ref={chatContainerRef}
            onScroll={onChatScroll}
          >
            {messagesQuery.isLoading ? (
              <div className="empty-state ai-empty"><p>加载会话消息…</p></div>
            ) : messages.length ? messages.map((item) => {
              const text = contentText(item.content, item.displayContent);
              const isAssistant = item.role === 'assistant';
              return (
                <div key={item._key ?? `${item.role}-${item.id ?? 'x'}`} className={`chat-message ${item.role}`}>
                  {isAssistant && item.reasoning ? (
                    <details className="chat-reasoning" open={item.streaming}>
                      <summary>思考过程{item.streaming ? '（生成中…）' : ''}</summary>
                      <div className="chat-reasoning-body">{item.reasoning}</div>
                    </details>
                  ) : null}
                  <MarkdownContent value={text} />
                  {item.streaming ? <span className="chat-stream-cursor" aria-hidden="true" /> : null}
                  {isAssistant ? (
                    <>
                      {item.notice ? (
                        <div className="ai-retrieval-notice">
                          {item.notice.message ?? '笔记检索暂时不可用，本次回答未使用笔记内容'}
                        </div>
                      ) : null}
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
                      <div className="chat-message-actions">
                        <Button type="text" size="mini" icon={<FilePlus2 size={14} />} onClick={() => setPendingInsert(text)}>插入笔记</Button>
                      </div>
                    </>
                  ) : null}
                </div>
              );
            }) : (
              <div className="empty-state ai-empty">
                <div>
                  <Sparkles size={28} />
                  <p>当前会话还没有消息。可新建多个会话分别保存不同主题的对话。</p>
                </div>
              </div>
            )}
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

          <div className="chat-compose ai-drawer-compose">
            <TextArea
              autoSize={{ minRows: 2, maxRows: 6 }}
              value={message}
              onChange={setMessage}
              onPressEnter={(event) => {
                if (!event.shiftKey) {
                  event.preventDefault();
                  send();
                }
              }}
              placeholder="提问… Enter 发送 · Shift+Enter 换行"
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
                void Promise.all(files.map(loadAttachment));
              }}
            />
            <Button type="secondary" icon={<Paperclip size={16} />} onClick={() => fileInput.current?.click()} aria-label="添加附件" />
            <Button
              type="primary"
              icon={streaming || chatMutation.isPending ? <Square size={14} /> : <Send size={16} />}
              loading={chatMutation.isPending && !streaming}
              disabled={!streaming && (!configQuery.data?.hasKey || sessionId === undefined)}
              onClick={() => {
                if (streaming) {
                  if (chatAbortRef.current) chatAbortRef.current();
                  else if (chatMutation.isPending) {
                    // 非流式请求挂起中：无 abort 句柄可调用，交给后端超时；按钮态会自行恢复。
                  }
                  return;
                }
                send();
              }}
              aria-label={streaming ? '中断生成' : '发送'}
              title={streaming ? '中断生成' : '发送'}
            />
          </div>
          {!configQuery.data?.hasKey ? <Typography.Text type="secondary" className="ai-drawer-hint">在「设置」中配置 Endpoint 与 API Key 后即可使用。</Typography.Text> : null}
          {exporting ? <Typography.Text type="secondary" className="ai-drawer-hint"><Download size={12} style={{ marginRight: 4 }} />正在导出会话…</Typography.Text> : null}
        </div>
        <Modal
          title="删除会话"
          visible={deleteConfirmSession !== null}
          onCancel={() => setDeleteConfirmSession(null)}
          onOk={() => {
            if (deleteConfirmSession) deleteSessionMutation.mutate(deleteConfirmSession.id);
            setDeleteConfirmSession(null);
          }}
          okText="删除"
          okButtonProps={{ status: 'danger' }}
          cancelText="取消"
          confirmLoading={deleteSessionMutation.isPending}
          autoFocus={false}
          focusLock
          unmountOnExit
        >
          <Typography.Text>删除会话「{deleteConfirmSession?.title ?? ''}」？消息将一并删除，且不可恢复。</Typography.Text>
        </Modal>
      </Drawer>
    </>
  );
}
