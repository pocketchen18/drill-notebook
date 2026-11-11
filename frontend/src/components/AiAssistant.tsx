import { useEffect, useMemo, useRef, useState, type CSSProperties, type UIEvent } from 'react';
import { PanelResizer } from './ai-session/PanelResizer';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Input, Message, Select, Space, Typography } from '@arco-design/web-react';
import { ArrowDown, Copy, Download, FilePlus2, FileText, Layers, MoreHorizontal, PanelLeft, Paperclip, Pin, Send, Sparkles, Square, X } from 'lucide-react';
import { SessionNavigation, type SessionNavigationHandle } from './ai-session/SessionNavigation';
import { focusMainWindow, isAssistantWindow, publish } from '../lib/windowBus';
import './ai-window.css';
import { ModelPicker, type ModelChoice } from './ai-session/ModelPicker';
import type { Connections } from './ai-connections/types';
import { ContextMeter, formatTokens } from './ai-session/ContextMeter';
import { useNavigate } from 'react-router-dom';
import { del, get, post, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { AiChatSession, AiConfig, ChatCitation, ChatContentPart, ChatMessage, NotePage, Notebook, Question, KnowledgePoint, TurnResult } from '../lib/types';
import { ChatContextInspector } from './ai-memory/ChatContextInspector';
import { MemoryUsageBadge } from './ai-memory/MemoryUsageBadge';
import { appendMarkdownBlock } from '../lib/aiContext';
import { safeFileName } from '../lib/export';
import { capturePageSnapshot, materialLinkTarget } from '../lib/pageSnapshot';
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
import { describeAccelerators, isShortcutRecording, matchesAny } from '../lib/shortcuts';
import { MarkdownContent } from './markdown/MarkdownRenderer';
import { SessionReferencePreview } from './ai-session/SessionReferencePreview';
import { useUiStore } from '../stores/uiStore';
import { ToolActivityGroup } from './ai-memory/ToolActivityGroup';
import { mergeToolActivities, readToolActivities, settleToolActivities } from '../lib/toolActivity';
import type { ToolActivity } from '../lib/toolActivity';
import { isConfirmedToolMutation, refreshToolMutation } from '../lib/toolMutationEffects';
import { useChatQueue } from '../hooks/useChatQueue';
import { ChatQueue } from './ai-session/ChatQueue';
import { splitReply } from '../lib/chatQueue';

const { TextArea } = Input;
const LS_SIDE_COLLAPSED = 'ai.window.sideCollapsed';
const NARROW_WIDTH = 760;
const readSideCollapsed = (): boolean => { try { return localStorage.getItem(LS_SIDE_COLLAPSED) === '1'; } catch { return false; } };
/** 会话栏与详情栏的宽度可拖动调整，分别记住；窗口变窄时由 CSS 的比例上限兜底。 */
const PANELS = {
  side: { key: 'ai.window.sideWidth', min: 200, max: 480, base: 264, ratio: 0.45 },
  detail: { key: 'ai.window.detailWidth', min: 280, max: 900, base: 320, ratio: 0.65 }
} as const;
type PanelName = keyof typeof PANELS;
const readPanelWidth = (panel: PanelName): number => {
  const config = PANELS[panel];
  try {
    const saved = Number(localStorage.getItem(config.key));
    return Number.isFinite(saved) && saved >= config.min && saved <= config.max ? Math.round(saved) : config.base;
  } catch { return config.base; }
};
const savePanelWidth = (panel: PanelName, width: number): void => {
  try { localStorage.setItem(PANELS[panel].key, String(width)); } catch { /* ignore */ }
};

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

/** 引用块：逐行加「> 」前缀，选中多行也保持可读。 */
export function quoteBlock(text: string): string {
  return text.split(/\r?\n/).map((line) => (line ? `> ${line}` : '>')).join('\n');
}

export function AiAssistant(): JSX.Element {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const fileInput = useRef<HTMLInputElement>(null);
  const aiOpen = useUiStore((state) => state.aiOpen);
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const pageContext = useUiStore((state) => state.pageContext);
  // 发送键跟随设置 → 常规 → 快捷键里的当前绑定
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

  const connectionsQuery = useQuery({ queryKey: ['ai-connections'], queryFn: () => get<Connections>('/api/ai/connections'), enabled: aiOpen });
  // 新会话页还没有数据库记录：选择先留在本地，首条消息创建会话时一并提交。
  const [draftChoice, setDraftChoice] = useState<ModelChoice>({ modelConfigId: null, reasoningEffort: null });
  const draftChoiceRef = useRef(draftChoice);
  draftChoiceRef.current = draftChoice;

  const [sessionId, setSessionId] = useState<number>();
  const [message, setMessage] = useState('');
  // 选中文本后的「复制 / 引用」小浮层：坐标相对消息流容器，滚动、Esc 或选区消失就收起。
  const [textSelection, setTextSelection] = useState<{ x: number; y: number; text: string; below: boolean } | null>(null);
  const streamWrapRef = useRef<HTMLDivElement>(null);
  // 双击标题就地改名：null 表示没在改名；单击延后一拍执行，避免和双击撞在一起。
  const [titleDraft, setTitleDraft] = useState<string | null>(null);
  const titleRenameCancelled = useRef(false);
  const titleClickTimer = useRef<number | undefined>(undefined);
  // 窗口「固定」：让助手浮在其他窗口之上，便于一边看回答一边记笔记（与会话置顶无关）。
  const [fixed, setFixed] = useState(false);
  const [queueSubmitting, setQueueSubmitting] = useState(false);
  const queueSubmittingRef = useRef(false);
  const submittedQueueDrafts = useRef(new Map<number, { message: string; ids: Set<string> }>());
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [targetNotebookId, setTargetNotebookId] = useState<number>();
  const [targetPageId, setTargetPageId] = useState<number>();
  const [pendingInsert, setPendingInsert] = useState<string>();
  const [exporting, setExporting] = useState(false);
  const [sessionReference, setSessionReferenceState] = useState<string>();
  const setSessionReference = (link: string | undefined): void => { setReferenceTitle(''); setSessionReferenceState(link); };
  // 打开中的上下文检查器：面板里可能有逐条对照出来的正文片段，切会话时必须整体销毁（§4.5）
  const [inspectorTarget, setInspectorTarget] = useState<{ requestId: string; snapshot?: TurnResult } | null>(null);
  const selectedSessionRef = useRef(sessionId);
  selectedSessionRef.current = sessionId;
  const [referenceTitle, setReferenceTitle] = useState('');
  // 窗口框架：会话栏可收起；窄窗口（系统分屏后）会话栏改为浮层，详情栏占满。
  const navigation = useRef<SessionNavigationHandle>(null);
  const composeRef = useRef<HTMLDivElement>(null);
  const [narrow, setNarrow] = useState(() => window.innerWidth < NARROW_WIDTH);
  const [sideCollapsed, setSideCollapsed] = useState(readSideCollapsed);
  const [sideOverlay, setSideOverlay] = useState(false);
  const [sideWidth, setSideWidth] = useState(() => readPanelWidth('side'));
  const [detailWidth, setDetailWidth] = useState(() => readPanelWidth('detail'));
  const sideOpen = narrow ? sideOverlay : !sideCollapsed;
  const setSide = (open: boolean): void => {
    if (narrow) { setSideOverlay(open); return; }
    setSideCollapsed(!open);
    try { localStorage.setItem(LS_SIDE_COLLAPSED, open ? '0' : '1'); } catch { /* ignore */ }
  };
  const focusCompose = (): void => { composeRef.current?.querySelector('textarea')?.focus(); };
  useEffect(() => {
    const onResize = (): void => setNarrow(window.innerWidth < NARROW_WIDTH);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  useEffect(() => { if (!narrow) setSideOverlay(false); }, [narrow]);
  useEffect(() => {
    const api = window.api?.assistant;
    if (!api) return;
    return api.onActivated(focusCompose);
  }, []);

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
  // 上翻阅读时出现「回到最新」；其间有新内容到达则带提示点。
  const [away, setAway] = useState(false);
  const [unseen, setUnseen] = useState(false);
  // 往前翻页前的列表高度：用来把视口补回原来看着的那条消息
  const olderAnchorRef = useRef<number | null>(null);
  const draftCache = useRef(new Map<number | 'new', { message: string; attachments: Attachment[]; messages: ChatMessage[];
    target: typeof turnTargetRef.current; sticky: boolean; scroll: number }>());
  const restoreScrollRef = useRef<number | null>(null);
  const observedStatusRef = useRef(new Map<string, string>());
  function selectSession(next: number | undefined): void {
    setSideOverlay(false);
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

  const sessionChoice = sessionsQuery.data?.find((item) => item.id === sessionId);
  const modelChoice: ModelChoice = sessionId === undefined ? draftChoice
    : { modelConfigId: sessionChoice?.modelConfigId ?? null, reasoningEffort: sessionChoice?.reasoningEffort ?? null };
  const activeModel = (() => {
    const id = modelChoice.modelConfigId ?? connectionsQuery.data?.chatModelId ?? null;
    for (const provider of connectionsQuery.data?.providers ?? []) {
      const model = provider.models.find((item) => item.id === id);
      if (model) return { provider, model };
    }
    return undefined;
  })();
  const activeEffort = modelChoice.reasoningEffort && (activeModel?.model.reasoningEfforts ?? []).includes(modelChoice.reasoningEffort)
    ? modelChoice.reasoningEffort : activeModel?.model.defaultReasoningEffort ?? null;
  // 会话自己选了模型时，以那个供应商是否可用为准；否则沿用主模型的配置状态。
  const configured = modelChoice.modelConfigId && activeModel
    ? activeModel.provider.hasKey || activeModel.provider.authMode === 'none'
    : !!(configQuery.data?.hasKey || configQuery.data?.authMode === 'none');
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

  // 运行与待发数量交给主进程：主窗口入口显示后台任务，退出应用前据此确认。
  const runningCount = turn.runtimes.filter((item) => item.status === 'RUNNING').length;
  const pendingCount = turn.runtimes.reduce((sum, item) => sum + (item.queueCount || 0) + (item.status === 'QUEUED' ? 1 : 0), 0);
  const previousRunning = useRef(0);
  useEffect(() => {
    publish({ type: 'assistant-activity', running: runningCount, pending: pendingCount });
    // 有回复结束而窗口不在前台：只闪任务栏按钮。
    if (runningCount < previousRunning.current) void window.api?.assistant?.attention();
    previousRunning.current = runningCount;
  }, [runningCount, pendingCount]);
  // 窗口内快捷键（设置 → 常规 → 快捷键 → AI 助手窗口）。
  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (isShortcutRecording()) return;
      const config = useUiStore.getState().shortcutConfig;
      if (matchesAny(event, config.aiNewSession)) { event.preventDefault(); handleNewSession(); focusCompose(); }
      else if (matchesAny(event, config.aiFindSession)) {
        event.preventDefault(); setSide(true);
        window.requestAnimationFrame(() => navigation.current?.focusSearch());
      } else if (matchesAny(event, config.aiToggleSidebar)) { event.preventDefault(); setSide(!sideOpen); }
      else if (event.key === 'Escape' && !event.defaultPrevented) {
        // 依次关闭浮层、详情栏；都没有时不做任何事，不关闭窗口。
        if (textSelection) setTextSelection(null);
        else if (narrow && sideOverlay) setSideOverlay(false);
        else if (sessionReference) setSessionReference(undefined);
        else if (inspectorTarget) setInspectorTarget(null);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

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
      if (item.role === 'user' && item.requestId && item.page) snapshots.set(`user:${item.requestId}`, item);
    }
    const covered = new Set<string>();
    const server: ChatMessage[] = history.messages.map((row) => {
      if (row.requestId) covered.add(`${row.role}:${row.requestId}`);
      const mirror = row.role === 'assistant' && row.requestId ? snapshots.get(row.requestId) : undefined;
      return {
        id: row.id,
        role: row.role,
        model: row.model ?? mirror?.model,
        reasoningEffort: row.reasoningEffort ?? mirror?.reasoningEffort,
        usage: row.usage,
        page: row.page ?? snapshots.get(`user:${row.requestId}`)?.page,
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
    setSessionReference(undefined);
  }, [sessionId]);

  // 助手窗口自身的「固定」状态由主进程保存；浏览器调试里没有这个接口，按钮也就不出现。
  const fixedApi = typeof window === 'undefined' ? undefined : window.api?.assistant;
  useEffect(() => {
    if (!fixedApi) return;
    let alive = true;
    void fixedApi.getState().then((state) => { if (alive) setFixed(state.fixed); }).catch(() => undefined);
    const off = fixedApi.onState((state) => setFixed(state.fixed));
    return () => { alive = false; off(); };
  }, [fixedApi]);
  useEffect(() => () => window.clearTimeout(titleClickTimer.current), []);
  // 选区被点掉或清空时浮层要跟着收起（点浮层自身时 mousedown 已被阻止，不会走到这里）。
  useEffect(() => {
    if (!textSelection) return;
    const onSelectionChange = (): void => { if (!window.getSelection()?.toString().trim()) setTextSelection(null); };
    document.addEventListener('selectionchange', onSelectionChange);
    return () => document.removeEventListener('selectionchange', onSelectionChange);
  }, [textSelection]);

  // 聊天列表自动滚动：仅当用户接近底部时粘底；用户上滑阅读时尊重其位置。
  const onChatScroll = (event: UIEvent<HTMLDivElement>): void => {
    const el = event.currentTarget;
    setTextSelection(null);   // 浮层按屏幕坐标定位，滚动后不再对应选区
    const distanceFromBottom = el.scrollHeight - el.clientHeight - el.scrollTop;
    // 即使只向上移动了几像素，也视为用户开始阅读旧消息；否则 80px 阈值内的上滑
    // 会继续被流式跟随拉回底部。向下滚回接近底部时再恢复自动跟随。
    if (el.scrollTop < lastScrollTopRef.current) {
      stickyBottomRef.current = false;
    } else if (el.scrollTop > lastScrollTopRef.current) {
      stickyBottomRef.current = distanceFromBottom < 80;
    }
    const nowAway = distanceFromBottom > 160;
    setAway((value) => (value === nowAway ? value : nowAway));
    if (!nowAway) setUnseen(false);
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
    if (!stickyBottomRef.current) {
      if (el.scrollHeight - el.clientHeight - el.scrollTop > 160) setUnseen(true);
      return;
    }
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

  const refreshedMaterialCalls = useRef(new Set<string>());
  function refreshCreatedMaterials(requestId: string, activities: unknown): void {
    for (const row of readToolActivities(activities)) {
      if (!isConfirmedToolMutation(row)) continue;
      const key = `${requestId}:${row.sequence}`;
      if (refreshedMaterialCalls.current.has(key)) continue;
      refreshedMaterialCalls.current.add(key);
      if (refreshedMaterialCalls.current.size > 2400) refreshedMaterialCalls.current.delete(refreshedMaterialCalls.current.values().next().value!);
      refreshToolMutation(queryClient, row);
      // 主窗口有自己的查询缓存与编辑草稿，同一条确认事件转给它处理一次。
      publish({ type: 'tool-mutation', row });
    }
  }

  /** 资料在主窗口定位：助手窗口保持原样，只把主窗口提到前面。非桌面双窗口环境退回本页跳转。 */
  const goToMain = (route: string): void => {
    if (isAssistantWindow()) { publish({ type: 'navigate', route }); focusMainWindow(); return; }
    useUiStore.getState().setAiOpen(false);
    navigate(route);
  };

  const openMaterial = async (link: string): Promise<void> => {
    const target = materialLinkTarget(link);
    if (!target) return;
    try {
      let route: string;
      if (target.type === 'notebook') route = `/notebooks?notebookId=${target.id}`;
      else if (target.type === 'note') route = `/notebooks?pageId=${target.id}`;
      else if (target.type === 'bank') route = `/banks?bankId=${target.id}`;
      else if (target.type === 'knowledge') {
        const point = await get<KnowledgePoint>(`/api/knowledge-points/${target.id}`);
        route = `/knowledge?pointId=${target.id}${point.bankId ? `&bankId=${point.bankId}` : ''}`;
      } else {
        const question = await get<Question>(`/api/questions/${target.id}`);
        route = `/banks?bankId=${question.bankId}&questionId=${target.id}`;
      }
      goToMain(route);
    } catch (error) { Message.error(friendlyMessage(error, '资料不存在或暂时无法打开')); }
  };

  const createSessionMutation = useMutation({
    mutationFn: () => post<AiChatSession>('/api/ai/sessions', { title: '新会话',
      ...(draftChoiceRef.current.modelConfigId || draftChoiceRef.current.reasoningEffort ? draftChoiceRef.current : {}) }),
    onSuccess: (session) => {
      // 只在首条消息发送时创建；先写缓存和选择，下一次渲染后再启动首轮。
      queryClient.setQueryData<AiChatSession[]>(['ai-sessions'], (old) => {
        const list = old ?? [];
        if (list.some((item) => item.id === session.id)) return list;
        return [{ ...session, messageCount: session.messageCount ?? 0 }, ...list];
      });
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] });
      lastExplicitSessionIdRef.current = session.id;
      setDraftChoice({ modelConfigId: null, reasoningEffort: null });
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
  /** 改选只作用于当前会话之后提交的消息；已开始的轮次和已受理的待发消息保持提交时的配置。 */
  const changeModelChoice = async (next: ModelChoice): Promise<void> => {
    if (sessionId === undefined) { setDraftChoice(next); return; }
    try {
      updateSessionCache(await put<AiChatSession>(`/api/ai/sessions/${sessionId}`, next));
    } catch (error) { Message.error(friendlyMessage(error, '模型选择未保存，请重试')); }
  };
  /** 置顶属于会话：每个会话各自记录，置顶的归入会话栏最前的「置顶」分组，先置顶的在上。 */
  const pinSession = async (id: number, pinned: boolean): Promise<void> => {
    updateSessionCache(await put<AiChatSession>(`/api/ai/sessions/${id}`, { pinned }));
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
    if (event.type === 'activity') refreshCreatedMaterials(event.requestId, event.activities);
    else if (event.type === 'snapshot' || event.type === 'status') refreshCreatedMaterials(event.requestId, event.status.toolActivities);
    else if (event.type === 'result' || event.type === 'failure') refreshCreatedMaterials(event.requestId, event.view.toolActivities);
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
    if (!configured) {
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
      pageSnapshot: capturePageSnapshot(useUiStore.getState().pageContext)
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
    // 发送时就标出将使用的模型；服务端历史行到达后以它记录的实际值为准。
    const usedModel = activeModel ? { model: `${activeModel.provider.name} · ${activeModel.model.modelId}`,
      ...(activeEffort ? { reasoningEffort: activeEffort } : {}) } : {};
    turnTargetRef.current = { userKey, assistantKey };
    const snapshot = inputSnapshot.pageSnapshot;
    const boundPage = snapshot && snapshot.kind !== 'none' && snapshot.title ? { page: { title: snapshot.title } } : {};
    setMessages((current) => [...current, { role: 'user', content, displayContent: displayText, _key: userKey, ...boundPage },
      { role: 'assistant', content: '', streaming: true, reasoning: '', _key: assistantKey, ...usedModel }]);
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
      publish({ type: 'invalidate', keys: [['note-page', targetPageId], ['note-page-deep-link', targetPageId], ['note-pages']] });
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

  const currentSession = sessionsQuery.data?.find((item) => item.id === sessionId);
  const runtime = turn.runtimes.find((item) => item.sessionId === sessionId);
  const statusText = streaming ? `正在回复${runtime?.queueCount ? ` · 待发 ${runtime.queueCount}` : ''}`
    : runtime?.paused && runtime.queueCount ? `待发 ${runtime.queueCount} · 已暂停`
      : runtime?.queueCount ? `待发 ${runtime.queueCount}` : '';
  const pageTarget = pageContext.kind !== 'none' && !!pageContext.title;
  // 供应商不回报用量时的退路：最近一轮上下文计划的本地估算。
  const contextEstimate = (() => {
    for (let index = visibleMessages.length - 1; index >= 0; index -= 1) {
      const tokens = visibleMessages[index].turn?.contextUsage?.estimatedInputTokens;
      if (typeof tokens === 'number' && tokens > 0) return { tokens, requestId: visibleMessages[index].requestId };
    }
    return null;
  })();
  const detailOpen = !!inspectorTarget || !!sessionReference;
  const jumpToLatest = (): void => {
    const el = chatContainerRef.current;
    if (!el) return;
    stickyBottomRef.current = true;
    el.scrollTop = el.scrollHeight;
    lastScrollTopRef.current = el.scrollTop;
    setAway(false); setUnseen(false);
  };
  const copyReply = async (value: string): Promise<void> => {
    try { await navigator.clipboard.writeText(value); Message.success('已复制回复'); }
    catch { Message.error('复制失败，请手动选择文本复制'); }
  };
  /** 顶栏标题：单击展开会话栏，双击就地改名。展开时单击没有别的动作，不必等这一拍。 */
  const onTitleClick = (): void => {
    if (sideOpen) return;
    window.clearTimeout(titleClickTimer.current);
    titleClickTimer.current = window.setTimeout(() => setSide(true), 240);
  };
  const openTitleRename = (): void => {
    window.clearTimeout(titleClickTimer.current);
    if (!currentSession || createSessionMutation.isPending) return;
    titleRenameCancelled.current = false;
    setTitleDraft(currentSession.title);
  };
  /** Enter 或失焦保存，Escape 取消；内容没变或为空都不发请求。 */
  const commitTitleRename = (): void => {
    const target = currentSession;
    const next = (titleDraft ?? '').trim();
    const cancelled = titleRenameCancelled.current;
    titleRenameCancelled.current = false;
    setTitleDraft(null);
    if (cancelled || !target || !next || next === target.title) return;
    void renameSession(target.id, next).catch((error) => Message.error(friendlyMessage(error, '会话名未保存，请重试')));
  };
  const cancelTitleRename = (): void => { titleRenameCancelled.current = true; setTitleDraft(null); };
  /** 浮层坐标相对消息流容器：选区上方优先，贴近顶部时放到选区下方。 */
  const readTextSelection = (): void => {
    const wrap = streamWrapRef.current;
    const selection = typeof window === 'undefined' ? null : window.getSelection();
    const text = selection?.toString().trim() ?? '';
    if (!wrap || !selection || selection.isCollapsed || !text || selection.rangeCount === 0) { setTextSelection(null); return; }
    const range = selection.getRangeAt(0);
    if (!wrap.contains(range.commonAncestorContainer)) { setTextSelection(null); return; }
    const rect = range.getBoundingClientRect();
    const base = wrap.getBoundingClientRect();
    const half = 72;   // 浮层大致半宽，避免贴边溢出
    const x = Math.min(Math.max(half, rect.left - base.left + rect.width / 2), Math.max(half, base.width - half));
    const below = rect.top - base.top < 44;
    setTextSelection({ x, y: below ? rect.bottom - base.top + 8 : rect.top - base.top - 8, text, below });
  };
  const copySelected = async (): Promise<void> => {
    const text = textSelection?.text ?? '';
    setTextSelection(null);
    try { await navigator.clipboard.writeText(text); Message.success('已复制'); }
    catch { Message.error('复制失败，请手动选择文本复制'); }
  };
  /** 引用：把选中的文字作为引用块放进输入框，接着写自己的问题。 */
  const quoteSelected = (): void => {
    const text = textSelection?.text;
    setTextSelection(null);
    if (!text) return;
    window.getSelection()?.removeAllRanges();
    setMessage((current) => current.trim() ? `${current.replace(/\s+$/, '')}\n\n${quoteBlock(text)}\n` : `${quoteBlock(text)}\n`);
    focusCompose();
  };
  const lastAssistantKey = [...visibleMessages].reverse().find((item) => item.role === 'assistant')?._key;
  const closeDetail = (): void => { setInspectorTarget(null); setSessionReference(undefined); };

  return (
    <div className={`ai-window ai-assistant${sideOpen ? '' : ' is-side-closed'}${narrow ? ' is-narrow' : ''}${detailOpen ? ' has-detail' : ''}`}
      style={{ '--ai-side-width': `${sideWidth}px`, '--ai-detail-width': `${detailWidth}px` } as CSSProperties}>
      <nav className="ai-window-side" aria-label="会话" hidden={!sideOpen}>
        {narrow ? null : <PanelResizer label="调整会话栏宽度" edge="right" value={sideWidth} min={PANELS.side.min} max={PANELS.side.max}
          maxRatio={PANELS.side.ratio} defaultValue={PANELS.side.base} onChange={setSideWidth} onCommit={(width) => savePanelWidth('side', width)} />}
        <SessionNavigation ref={navigation} docked visible={aiOpen} sessions={sessionsQuery.data ?? []} currentId={sessionId}
          busyId={streaming ? turn.activeSessionId : undefined}
          runtimes={turn.runtimes} reconnecting={turn.reconnecting} onStop={turn.stopSession}
          loading={sessionsQuery.isLoading} error={sessionsQuery.isError} creating={createSessionMutation.isPending}
          exporting={exporting} onRetry={() => { void sessionsQuery.refetch(); }} onCreate={handleNewSession}
          onSelect={selectSession}
          onRename={renameSession} onGenerate={generateSessionTitle}
          onDelete={async (id) => { await deleteSessionMutation.mutateAsync(id); }} onExport={exportSession}
          onPin={pinSession}
          footer={<>
          <div className="ai-window-side-foot">
            <ChatStorageMeter enabled={aiOpen} />
            <ChatHistoryCleanup
              sessionId={sessionId}
              enabled={aiOpen}
              messages={history.messages}
              hasOlder={history.hasOlder}
              isLoadingOlder={history.isLoadingOlder}
              loadOlder={history.loadOlder}
            />
            <button type="button" className="ai-window-link" onClick={() => goToMain('/settings')}>设置 ↗</button>
          </div>
          </>} />
      </nav>
      {narrow && sideOpen ? <div className="ai-window-scrim" onClick={() => setSideOverlay(false)} /> : null}

      <main className="ai-window-main">
        <header className="ai-window-head">
          <button type="button" className="ai-window-icon" aria-label={sideOpen ? '收起会话栏' : '展开会话栏'} aria-expanded={sideOpen}
            title={`会话栏 ${describeAccelerators(useUiStore.getState().shortcutConfig.aiToggleSidebar)}`} onClick={() => setSide(!sideOpen)}><PanelLeft size={17} /></button>
          {fixedApi ? <>
            <button type="button" className={`ai-window-icon${fixed ? ' is-on' : ''}`} aria-label="固定窗口" aria-pressed={fixed}
              title={fixed ? '取消固定：不再浮在其他窗口之上' : '固定：让助手浮在其他窗口之上，便于一边看回答一边记笔记'}
              onClick={() => { const next = !fixed; setFixed(next); void fixedApi.setFixed(next); }}><Pin size={16} /></button>
            <span className="ai-window-divider" aria-hidden="true" />
          </> : null}
          {titleDraft === null ? (
            <button type="button" className="ai-window-title" aria-label="切换会话" disabled={createSessionMutation.isPending}
              title={currentSession ? `${currentSession.title}（双击改名）` : undefined} onClick={onTitleClick} onDoubleClick={openTitleRename}>
              <span>{currentSession?.title ?? (sessionsQuery.isLoading ? '正在加载会话…' : '新会话')}</span></button>
          ) : (
            <input className="ai-window-title-input" aria-label="会话名称" value={titleDraft} autoFocus maxLength={120}
              onFocus={(event) => event.currentTarget.select()}
              onChange={(event) => setTitleDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.nativeEvent.isComposing || event.keyCode === 229) return;
                if (event.key === 'Enter') { event.preventDefault(); commitTitleRename(); }
                else if (event.key === 'Escape') { event.preventDefault(); cancelTitleRename(); }
              }}
              onBlur={commitTitleRename} />
          )}
          {statusText ? <span className={`ai-window-status${streaming ? ' is-running' : ''}`} role="status">{statusText}</span> : null}
          <span className="ai-window-spacer" />
          <button type="button" className="ai-window-icon" aria-label="会话更多操作" title="会话更多操作" disabled={!currentSession || createSessionMutation.isPending}
            onClick={(event) => { if (currentSession) navigation.current?.openMenu(currentSession.id, event.currentTarget); }}><MoreHorizontal size={17} /></button>
        </header>

          <div className="ai-window-stream-wrap" ref={streamWrapRef}>
          <div
            className="ai-drawer-chat ai-window-stream"
            ref={chatContainerRef}
            onScroll={onChatScroll}
            onMouseUp={readTextSelection}
          >
            <div className="ai-window-col">
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
                <div key={item._key ?? `${item.role}-${item.id ?? 'x'}`} className={`chat-message ${item.role}${isAssistant && item._key === lastAssistantKey ? ' is-latest' : ''}`}>
                  {isAssistant && item.model ? <p className="chat-turn-model">{item.model}{item.reasoningEffort ? ` · 思考 ${item.reasoningEffort}` : ''}</p> : null}
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
                    onOpenMaterial={openMaterial}
                    onDisclosure={() => { stickyBottomRef.current = false; }} /> : null}
                  {isAssistant && item.supplements?.some((s) => s.state === 'RECEIVED')
                    ? splitReply(text, item.supplements).map((part) => part.role === 'user'
                      ? <div className="chat-supplement" key={part.key}><small>插话 · 已接收</small><MarkdownContent value={part.text} onSessionLink={setSessionReference} onMaterialLink={openMaterial} />
                        {!!part.attachments?.length && <small>{part.attachments.join(' · ')}</small>}</div>
                      : <MarkdownContent key={part.key} value={part.text} onSessionLink={setSessionReference} onMaterialLink={openMaterial} />)
                    : <MarkdownContent value={text} onSessionLink={setSessionReference} onMaterialLink={openMaterial} />}
                  {!isAssistant && item.page ? (item.page.link
                    ? <button type="button" className="chat-bound-page" title="提交时的业务页面，点击在主窗口查看" onClick={() => { void openMaterial(item.page!.link!); }}><FileText size={12} />{item.page.title}</button>
                    : <span className="chat-bound-page" title="提交时的业务页面"><FileText size={12} />{item.page.title}</span>) : null}
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
                        {text ? <Button type="text" size="mini" icon={<Copy size={14} />} onClick={() => { void copyReply(text); }}>复制</Button> : null}
                        {text ? <Button type="text" size="mini" icon={<FilePlus2 size={14} />} onClick={() => setPendingInsert(text)}>插入笔记</Button> : null}
                        {turnId ? (
                          <Button type="text" size="mini" icon={<Layers size={14} />} onClick={() => setInspectorTarget({ requestId: turnId, snapshot: turnSnapshot ?? undefined })}>
                            本轮上下文
                          </Button>
                        ) : null}
                        {item.usage ? <span className="chat-turn-usage" title={item.usage.requests > 1 ? `本轮 ${item.usage.requests} 次模型请求的累计实测用量` : '本轮实测用量'}>
                          输入 {formatTokens(item.usage.inputTokens)} · 输出 {formatTokens(item.usage.outputTokens)}</span> : null}
                      </div>
                    </>
                  ) : null}
                </div>
              );
            }) : (
              <div className="empty-state ai-empty ai-window-empty">
                <div>
                  <Sparkles size={28} />
                  <p>{sessionId === undefined ? '开始一段新对话' : '当前会话还没有消息。'}</p>
                  <div className="ai-quick-prompts">
                    {quickPrompts.map((item) => (
                      <button key={item.label} type="button" className="ai-chip" disabled={!configured} onClick={() => { setMessage(item.text); focusCompose(); }}>{item.label}</button>
                    ))}
                  </div>
                </div>
              </div>
            )}
            </>)}
            </div>
          </div>
          {textSelection ? (
            <div className={`ai-selection-pop${textSelection.below ? ' is-below' : ''}`} role="toolbar" aria-label="选中文本操作"
              style={{ left: `${textSelection.x}px`, top: `${textSelection.y}px` }} onMouseDown={(event) => event.preventDefault()}>
              <button type="button" onClick={() => void copySelected()}>复制</button>
              <button type="button" onClick={quoteSelected}>引用</button>
            </div>
          ) : null}
          {away ? <button type="button" className="ai-window-latest" onClick={jumpToLatest} aria-label={unseen ? '回到最新，有新内容' : '回到最新'}>
            <ArrowDown size={14} />回到最新{unseen ? <span className="ai-window-latest-dot" aria-hidden="true" /> : null}</button> : null}
          </div>

        <footer className="ai-window-foot">
          <div className="ai-window-col">
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
          {queue.unconfirmed && !queueSubmitting && <div className="chat-queue-uncertain" role="status">上次提交结果待确认
            <Button type="text" size="mini" loading={queueSubmitting} onClick={() => void retryQueueSubmission()}>核对提交</Button>
          </div>}
          <ChatQueue sessionId={sessionId} view={queue.data} runningId={streaming ? turn.requestId : null}
            loadingError={queue.isError} retry={() => void queue.refetch()} action={queue.action} pause={queue.pause} />
          <div ref={composeRef} className={`chat-compose ai-drawer-compose${queue.data?.items?.length || queue.data?.steering?.length ? ' has-queue' : ''}`}>
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
            <button type="button" className={`ai-window-page${pageTarget ? '' : ' is-empty'}`} disabled={!pageTarget}
              title={pageTarget ? '主窗口当前页面，点击在主窗口查看' : '主窗口没有可读取的业务页面'}
              onClick={() => goToMain(pageRoute(pageContext))}>
              <FileText size={13} /><span>{pageTarget ? pageContext.title : '无当前页面'}</span></button>
            <ContextMeter sessionId={sessionId} estimate={contextEstimate} refreshKey={`${turn.requestId ?? ''}:${streaming}:${modelChoice.modelConfigId ?? ''}`}
              onInspect={(requestId) => { setSessionReference(undefined); setInspectorTarget({ requestId }); }} />
            <ModelPicker connections={connectionsQuery.data} value={modelChoice} busy={streaming} disabled={createSessionMutation.isPending}
              onChange={(next) => { void changeModelChoice(next); }} onManage={() => goToMain('/settings?tab=ai')} />
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
              disabled={!configured || createSessionMutation.isPending || queueSubmitting || queue.unconfirmed || (streaming && !message.trim() && !attachments.length)}
              onClick={send}
              aria-label={streaming || queue.data?.items?.length ? '加入队列' : '发送'}
              title={streaming || queue.data?.items?.length ? '加入队列' : '发送'}
            />
          </div>
          {!configured ? <Typography.Text type="secondary" className="ai-drawer-hint">在「设置 → AI 连接」中配置供应商并应用模型后即可使用。</Typography.Text> : null}
          {exporting ? <Typography.Text type="secondary" className="ai-drawer-hint"><Download size={12} style={{ marginRight: 4 }} />正在导出会话…</Typography.Text> : null}
          </div>
        </footer>
      </main>

      {detailOpen ? (
        <aside className="ai-window-detail" aria-label={sessionReference ? '会话记录' : '本轮上下文'}>
          {narrow ? null : <PanelResizer label="调整详情栏宽度" edge="left" value={detailWidth} min={PANELS.detail.min} max={PANELS.detail.max}
            maxRatio={PANELS.detail.ratio} defaultValue={PANELS.detail.base} onChange={setDetailWidth} onCommit={(width) => savePanelWidth('detail', width)} />}
          <header className="ai-window-detail-head">
            <strong>{sessionReference ? referenceTitle || '会话记录' : '本轮上下文'}</strong>
            <button type="button" className="ai-window-icon" aria-label="关闭详情" onClick={closeDetail}><X size={16} /></button>
          </header>
          <div className="ai-window-detail-body">
            {sessionReference ? <SessionReferencePreview key={sessionReference} link={sessionReference} embedded onTitle={setReferenceTitle}
              onClose={() => setSessionReference(undefined)} onSessionLink={setSessionReference} />
              : inspectorTarget ? <ChatContextInspector sessionId={sessionId} requestId={inspectorTarget.requestId}
                turn={inspectorTarget.snapshot} open embedded onClose={() => setInspectorTarget(null)} /> : null}
          </div>
        </aside>
      ) : null}
    </div>
  );
}

/** 页面标记点击后回到主窗口的那一页。 */
function pageRoute(context: { route?: string; notePageId?: number; notebookId?: number; bankId?: number; pointId?: number; questionId?: number }): string {
  if (context.notePageId) return `/notebooks?pageId=${context.notePageId}`;
  if (context.pointId) return `/knowledge?pointId=${context.pointId}${context.bankId ? `&bankId=${context.bankId}` : ''}`;
  if (context.bankId && context.route === '/banks') return `/banks?bankId=${context.bankId}${context.questionId ? `&questionId=${context.questionId}` : ''}`;
  return context.route ?? '/notebooks';
}
