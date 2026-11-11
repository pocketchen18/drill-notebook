import { forwardRef, Fragment, useEffect, useImperativeHandle, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Message, Modal } from '@arco-design/web-react';
import { ArrowLeft, Check, ChevronDown, ChevronRight, Clock, Copy, Download, Link, LoaderCircle, MoreHorizontal, Pencil, Pin, Plus, Search, Sparkles, Square, Trash2, TriangleAlert } from 'lucide-react';
import { isSessionRunning, type SessionRuntime } from '../../hooks/useAiChatTurn';
import type { AiChatSession } from '../../lib/types';
import './session-navigation.css';

interface Props {
  visible: boolean;
  sessions: AiChatSession[];
  currentId?: number;
  busyId?: number;
  runtimes?: SessionRuntime[];
  reconnecting?: boolean;
  onStop?: (id: number) => Promise<void>;
  loading: boolean;
  error: boolean;
  creating: boolean;
  exporting: boolean;
  onRetry: () => void;
  onSelect: (id: number) => void;
  onCreate: () => void;
  onRename: (id: number, title: string) => Promise<void>;
  onGenerate: (session: AiChatSession, overwriteManual: boolean) => Promise<void>;
  onDelete: (id: number) => Promise<void>;
  onExport: (id: number, format: 'md' | 'html' | 'json') => Promise<void>;
  /** 置顶或取消置顶一个会话；不提供时不显示图钉。 */
  onPin?: (id: number, pinned: boolean) => Promise<void>;
  /** 停靠模式：列表常驻在助手窗口的会话栏里，带新建与标题查找；不再有顶部的切换入口。 */
  docked?: boolean;
  /** 停靠模式下列表底部的附加区域（存储占用、清理、设置）。 */
  footer?: ReactNode;
}
export interface SessionNavigationHandle {
  /** 从对话区顶栏对当前会话打开同一份菜单。 */
  openMenu: (sessionId: number, origin: HTMLElement) => void;
  focusSearch: () => void;
}
type MenuTarget = { id: number; x: number; y: number; origin: HTMLElement };
type Edit = { id: number; original: string; draft: string; error?: string };
const errorText = (error: unknown): string => error instanceof Error ? error.message : '操作失败，请重试';
const clamp = (value: number, max: number): number => Math.max(8, Math.min(value, max));

const parseTime = (value?: string): number => {
  if (!value) return NaN;
  return new Date(value.includes('T') ? value : `${value.replace(' ', 'T')}Z`).getTime();
};
/** 会话栏按最近活动分组；组内保持列表原有顺序。 */
export function sessionGroup(updatedAt: string | undefined, now = Date.now()): '今天' | '近 7 天' | '更早' {
  const time = parseTime(updatedAt);
  if (Number.isNaN(time)) return '更早';
  const startOfToday = new Date(now); startOfToday.setHours(0, 0, 0, 0);
  if (time >= startOfToday.getTime()) return '今天';
  return time >= startOfToday.getTime() - 6 * 86400000 ? '近 7 天' : '更早';
}

function recentTime(value?: string): string {
  if (!value) return '尚无对话';
  const parsed = new Date(value.includes('T') ? value : `${value.replace(' ', 'T')}Z`);
  if (Number.isNaN(parsed.getTime())) return '';
  const minutes = (Date.now() - parsed.getTime()) / 60000;
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${Math.floor(minutes)} 分钟前`;
  return parsed.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** 菜单始终持有目标 ID；没有任何动作通过“当前会话”隐式决定目标。 */
export const SessionNavigation = forwardRef<SessionNavigationHandle, Props>(function SessionNavigation(props, handle): JSX.Element {
  const { sessions, currentId, visible, docked = false } = props;
  const [query, setQuery] = useState('');
  const search = useRef<HTMLInputElement>(null);
  const needle = query.trim().toLowerCase();
  // 置顶的会话排在最前，先置顶的在上；其余保持列表原有顺序。
  const pinnedFirst = sessions.filter((s) => s.pinnedAt)
    .sort((a, b) => (a.pinnedAt! < b.pinnedAt! ? -1 : a.pinnedAt! > b.pinnedAt! ? 1 : a.id - b.id));
  const ordered = pinnedFirst.length ? [...pinnedFirst, ...sessions.filter((s) => !s.pinnedAt)] : sessions;
  const shown = docked && needle ? ordered.filter((s) => s.title.toLowerCase().includes(needle)) : ordered;
  const groupOf = (s: AiChatSession): string => s.pinnedAt ? '置顶' : sessionGroup(s.updatedAt);
  const [pinning, setPinning] = useState<number | null>(null);
  const togglePin = (session: AiChatSession): void => {
    if (!props.onPin || pinning !== null) return;
    setPinning(session.id);
    void props.onPin(session.id, !session.pinnedAt).catch((error: unknown) => Message.error(errorText(error))).finally(() => setPinning(null));
  };
  const current = sessions.find((s) => s.id === currentId);
  const runtimeFor = (id: number): SessionRuntime | undefined => props.runtimes?.find((item) => item.sessionId === id);
  const busy = (id: number): boolean => id === props.busyId || isSessionRunning(runtimeFor(id));
  const runningCount = props.runtimes?.filter((item) => item.status === 'RUNNING').length ?? 0;
  const waitingCount = props.runtimes?.filter((item) => item.status === 'QUEUED').length ?? 0;
  const statusIcon = (id: number): JSX.Element | null => {
    const runtime = runtimeFor(id);
    if (busy(id)) {
      const waiting = runtime?.status === 'QUEUED';
      const label = props.reconnecting ? '正在重新连接' : waiting ? '等待执行'
        : ({ tools: '正在调用工具', waiting: '等待会话回复', thinking: '正在思考', stopping: '正在停止…', answer: '正在生成回复' }[runtime?.phase ?? ''] ?? '正在运行');
      return waiting || props.reconnecting ? <Clock size={14} aria-label={label}><title>{label}</title></Clock>
        : <LoaderCircle size={14} className="session-nav-spin" aria-label={label}><title>{label}</title></LoaderCircle>;
    }
    if (runtime && ['FAILED', 'ABORTED', 'INTERRUPTED', 'TRUNCATED'].includes(runtime.status)) {
      const label = runtime.status === 'FAILED' ? '上次运行失败' : '上次回复未完成';
      return <TriangleAlert size={14} aria-label={label}><title>{label}</title></TriangleAlert>;
    }
    if (runtime?.status === 'PAUSED') return <Clock size={14} aria-label="待发已暂停"><title>待发已暂停 · {runtime.queueCount}</title></Clock>;
    return null;
  };
  const bar = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const mainMenu = useRef<HTMLDivElement>(null);
  const childMenu = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [openState, setOpen] = useState(false);
  const open = docked || openState;
  const [position, setPosition] = useState({ left: 8, top: 8, width: 340, height: 360 });
  const [viewport, setViewport] = useState({ width: window.innerWidth, height: window.innerHeight });
  const [menu, setMenu] = useState<MenuTarget | null>(null);
  const [sub, setSub] = useState<'copy' | 'export' | null>(null);
  const [edit, setEditState] = useState<Edit | null>(null);
  const editRef = useRef<Edit | null>(null);
  const [saving, setSaving] = useState(false);
  const savePromise = useRef<Promise<boolean> | null>(null);
  const [confirm, setConfirm] = useState<{ kind: 'delete' | 'generate'; session: AiChatSession } | null>(null);
  const lastConfirm = useRef(confirm);
  if (confirm) lastConfirm.current = confirm;
  const shownConfirm = confirm ?? lastConfirm.current;
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [confirmError, setConfirmError] = useState('');
  const [copyFallback, setCopyFallback] = useState<string | null>(null);
  const [generatingId, setGeneratingId] = useState<number | null>(null);
  const trackedGenerations = useRef(new Set<number>());
  const target = sessions.find((s) => s.id === menu?.id);
  const menuWidth = Math.min(196, viewport.width - 16);
  const menuX = clamp(menu?.x ?? 8, viewport.width - menuWidth - 8);
  // 提供置顶时菜单多一项，高度与子菜单的起点随之下移。
  const pinRow = props.onPin ? 34 : 0;
  const menuY = clamp(menu?.y ?? 8, viewport.height - 258 - pinRow - 8);
  const stacked = viewport.width < menuWidth * 2 + 24
    || (menuX - 8 < menuWidth && viewport.width - menuX - menuWidth - 8 < menuWidth);
  const childX = menuX + menuWidth * 2 + 8 <= viewport.width ? menuX + menuWidth : menuX - menuWidth;
  const childY = clamp(menuY + pinRow + (sub === 'copy' ? 82 : 116), viewport.height - (sub === 'copy' ? 84 : 118) - 8);

  const setEdit = (value: Edit | null): void => { editRef.current = value; setEditState(value); };
  const closeMenu = (restore = false): void => {
    if (restore) menu?.origin.focus();
    setMenu(null); setSub(null);
  };
  const saveRename = (): Promise<boolean> => {
    if (savePromise.current) return savePromise.current;
    const draft = editRef.current;
    if (!draft) return Promise.resolve(true);
    const title = draft.draft.trim();
    if (!title) { setEdit({ ...draft, error: '标题不能为空' }); input.current?.focus(); return Promise.resolve(false); }
    if (title === draft.original) { setEdit(null); return Promise.resolve(true); }
    setSaving(true);
    const promise = props.onRename(draft.id, title).then(() => {
      setEdit(null); Message.success('会话已重命名');
      requestAnimationFrame(() => list.current?.querySelector<HTMLButtonElement>(`[data-session="${draft.id}"]`)?.focus());
      return true;
    }).catch((error: unknown) => {
      setEdit({ ...draft, error: errorText(error) }); return false;
    }).finally(() => { setSaving(false); savePromise.current = null; });
    savePromise.current = promise;
    return promise;
  };
  const closeList = async (): Promise<void> => {
    closeMenu();
    if (await saveRename()) setOpen(false);
  };
  const beginRename = async (session: AiChatSession): Promise<void> => {
    closeMenu();
    if (!(await saveRename())) return;
    setOpen(true);
    setEdit({ id: session.id, original: session.title, draft: session.title });
  };
  const showMenu = async (session: AiChatSession, origin: HTMLElement, x: number, y: number): Promise<void> => {
    if (props.creating) return;
    if (!(await saveRename())) return;
    setSub(null); setMenu({ id: session.id, origin, x, y });
  };
  const rowMenu = (session: AiChatSession, origin: HTMLElement): void => {
    const rect = origin.getBoundingClientRect();
    void showMenu(session, origin, rect.right - 196, rect.bottom + 4);
  };
  useImperativeHandle(handle, () => ({
    openMenu: (sessionId, origin) => { const session = sessions.find((s) => s.id === sessionId); if (session) rowMenu(session, origin); },
    focusSearch: () => { search.current?.focus(); search.current?.select(); }
  }));

  useLayoutEffect(() => {
    if (docked || !open || !visible) return;
    const reposition = (): void => {
      const rect = bar.current?.getBoundingClientRect();
      if (!rect) return;
      const width = Math.min(rect.width || 340, window.innerWidth - 16);
      const below = window.innerHeight - rect.bottom - 16;
      const height = Math.min(396, Math.max(below, rect.top - 16));
      setPosition({ left: clamp(rect.left, window.innerWidth - width - 8),
        top: below >= 180 ? rect.bottom + 6 : Math.max(8, rect.top - height - 6),
        width, height: below >= 180 ? Math.min(396, below) : height });
    };
    reposition();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(reposition);
    if (bar.current) observer?.observe(bar.current);
    window.addEventListener('resize', reposition);
    return () => { observer?.disconnect(); window.removeEventListener('resize', reposition); };
  }, [open, visible]);
  useEffect(() => {
    const resize = (): void => { setViewport({ width: window.innerWidth, height: window.innerHeight }); closeMenu(); };
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  useLayoutEffect(() => { if (edit) { input.current?.focus(); input.current?.select(); } }, [edit?.id]);
  useLayoutEffect(() => {
    if (!docked && open && visible && !editRef.current) list.current?.querySelector<HTMLButtonElement>(`[data-session="${currentId}"]`)?.focus();
  }, [open, visible]);
  useLayoutEffect(() => {
    if (menu) mainMenu.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
  }, [menu]);
  useEffect(() => { if (!visible) closeMenu(); }, [visible]);
  useEffect(() => { closeMenu(); }, [currentId]);
  useEffect(() => {
    for (const id of trackedGenerations.current) {
      const session = sessions.find((s) => s.id === id);
      if (!session || ['failed', 'ready', 'idle'].includes(session.titleStatus ?? '')) {
        if (session?.titleStatus === 'failed') Message.error('标题生成失败，原名已保留，可从菜单重试');
        trackedGenerations.current.delete(id);
      }
    }
  }, [sessions]);
  useEffect(() => {
    if (!visible || (!open && !menu) || (docked && !menu && !edit)) return;
    const outside = (event: PointerEvent): void => {
      const node = event.target as Node;
      if (bar.current?.contains(node) || list.current?.contains(node) || mainMenu.current?.contains(node) || childMenu.current?.contains(node)) return;
      void closeList();
    };
    const escape = (event: globalThis.KeyboardEvent): void => {
      if (event.key !== 'Escape' || confirm || copyFallback !== null || editRef.current || (docked && !menu)) return;
      event.preventDefault(); event.stopImmediatePropagation();
      if (sub) { setSub(null); requestAnimationFrame(() => mainMenu.current?.querySelector<HTMLButtonElement>(`[data-child="${sub}"]`)?.focus()); }
      else if (menu) closeMenu(true);
      else { setOpen(false); trigger.current?.focus(); }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape, true);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape, true); };
  });

  const generate = async (session: AiChatSession, overwriteManual: boolean): Promise<boolean> => {
    setGeneratingId(session.id);
    try {
      await props.onGenerate(session, overwriteManual);
      trackedGenerations.current.add(session.id);
      return true;
    } catch (error) { Message.error(errorText(error)); return false; }
    finally { setGeneratingId(null); }
  };
  const requestGenerate = (): void => {
    if (!target) return;
    closeMenu();
    if (target.titleSource === 'manual') { setConfirm({ kind: 'generate', session: target }); setConfirmError(''); }
    else void generate(target, false);
  };
  const copy = async (kind: 'link' | 'title'): Promise<void> => {
    if (!target) return;
    const value = kind === 'link' ? target.sessionLink : target.title;
    closeMenu();
    if (!value) { Message.error('会话链接尚不可用，请刷新后重试'); return; }
    try {
      await navigator.clipboard.writeText(value);
      Message.success(kind === 'link' ? '会话链接已复制' : '会话标题已复制');
    } catch { setCopyFallback(value); }
  };
  const menuKeys = (event: KeyboardEvent<HTMLDivElement>): void => {
    const container = event.currentTarget;
    const buttons = [...container.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')];
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault(); event.stopPropagation();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
        : (index + (event.key === 'ArrowUp' ? -1 : 1) + buttons.length) % buttons.length;
      buttons[next]?.focus();
    } else if (event.key === 'ArrowRight') {
      const child = (document.activeElement as HTMLElement)?.dataset.child as 'copy' | 'export' | undefined;
      if (child) { event.preventDefault(); setSub(child); requestAnimationFrame(() => (childMenu.current ?? mainMenu.current)?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()); }
    } else if (event.key === 'ArrowLeft' && sub) {
      event.preventDefault(); setSub(null); requestAnimationFrame(() => mainMenu.current?.querySelector<HTMLButtonElement>(`[data-child="${sub}"]`)?.focus());
    } else if (event.key === 'Tab') closeMenu();
  };
  const openChild = (child: 'copy' | 'export'): void => {
    setSub(child);
    requestAnimationFrame(() => (childMenu.current ?? mainMenu.current)?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus());
  };
  const subItems = <>
    {stacked && <button role="menuitem" onClick={() => { setSub(null); requestAnimationFrame(() => mainMenu.current?.querySelector<HTMLButtonElement>('[data-child]')?.focus()); }}><ArrowLeft size={14} />返回</button>}
    {sub === 'copy' ? <>
      <button role="menuitem" onClick={() => void copy('link')}><Link size={14} />复制会话链接</button>
      <button role="menuitem" onClick={() => void copy('title')}><Copy size={14} />复制会话标题</button>
    </> : (['md', 'html', 'json'] as const).map((format) =>
      <button role="menuitem" key={format} disabled={props.exporting} onClick={() => {
        if (target) { closeMenu(); void props.onExport(target.id, format); }
      }}><Download size={14} />{format === 'md' ? 'Markdown' : format.toUpperCase()}</button>)}
  </>;

  const rows = (
    <div className="session-nav-rows" onScroll={() => closeMenu()}>
      {props.loading ? <p className="session-nav-empty">正在加载会话…</p> : props.error ?
        <div className="session-nav-empty">会话加载失败 <button onClick={props.onRetry}>重试</button></div> :
        sessions.length === 0 ? <div className="session-nav-empty">暂无会话 <button onClick={props.onCreate}>新建会话</button></div> :
        shown.length === 0 ? <p className="session-nav-empty">没有匹配的会话</p> :
        shown.map((session, index) => <Fragment key={session.id}>
          {docked && (index === 0 || groupOf(shown[index - 1]) !== groupOf(session))
            ? <div className="session-nav-group" role="presentation">{groupOf(session)}</div> : null}
          <div className={`session-nav-row${session.id === currentId ? ' is-current' : ''}${session.id === menu?.id ? ' has-menu' : ''}`}
          onContextMenu={(event) => { event.preventDefault(); void showMenu(session, event.currentTarget.querySelector('button')!, event.clientX, event.clientY); }}>
          {edit?.id === session.id ? <div className="session-nav-edit">
            <input ref={input} aria-label="重命名会话" value={edit.draft} disabled={saving} aria-invalid={!!edit.error}
              onChange={(e) => setEdit({ ...edit, draft: e.target.value, error: undefined })}
              onBlur={() => void saveRename()} onKeyDown={(e) => {
                e.stopPropagation();
                if (e.nativeEvent.isComposing || e.keyCode === 229) return;
                if (e.key === 'Enter') { e.preventDefault(); void saveRename(); }
                if (e.key === 'Escape') { e.preventDefault(); setEdit(null); requestAnimationFrame(() => list.current?.querySelector<HTMLButtonElement>(`[data-session="${session.id}"]`)?.focus()); }
              }} />
            {edit.error && <small role="alert">{edit.error}</small>}
            {saving && <small>正在保存…</small>}
          </div> : <>
            <button data-session={session.id} className="session-nav-select" title={session.title} disabled={props.creating} aria-current={session.id === currentId ? 'true' : undefined}
              onClick={async () => { if (await saveRename()) { closeMenu(); setOpen(false); if (session.id !== currentId) props.onSelect(session.id); } }}
              onKeyDown={(e) => {
                if (e.key === 'F2') { e.preventDefault(); void beginRename(session); }
                if (e.key === 'ContextMenu' || (e.key === 'F10' && e.shiftKey)) { e.preventDefault(); rowMenu(session, e.currentTarget); }
                if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                  e.preventDefault();
                  const rows = [...(list.current?.querySelectorAll<HTMLButtonElement>('[data-session]') ?? [])];
                  rows[(rows.indexOf(e.currentTarget) + (e.key === 'ArrowDown' ? 1 : -1) + rows.length) % rows.length]?.focus();
                }
              }}>
              <span className="session-nav-mark">{session.id === currentId && <Check size={14} />}</span>
              <span className="session-nav-copy"><span className="session-nav-name">{session.title}</span>
                <span className="session-nav-meta">{session.messageCount ?? 0} 条消息 · {recentTime(session.updatedAt)}
                  {!!runtimeFor(session.id)?.queueCount && ` · 待发送 ${runtimeFor(session.id)?.queueCount}`}
                  {session.titleStatus === 'generating' && ' · 正在生成标题'}</span></span>
              {statusIcon(session.id)}
            </button>
            {props.onPin ? <button className={`session-nav-row-pin${session.pinnedAt ? ' is-pinned' : ''}`} aria-pressed={!!session.pinnedAt}
              aria-label={`${session.pinnedAt ? '取消置顶' : '置顶'}：${session.title}`} title={session.pinnedAt ? '取消置顶' : '置顶'}
              disabled={pinning === session.id} onClick={() => togglePin(session)}><Pin size={14} fill={session.pinnedAt ? 'currentColor' : 'none'} /></button> : null}
            <button className="session-nav-row-more" aria-label={`更多操作：${session.title}`} onClick={(e) => rowMenu(session, e.currentTarget)}><MoreHorizontal size={16} /></button>
          </>}
        </div></Fragment>)}
    </div>
  );

  return <>
    {docked ? <div className="session-nav-docked" ref={list}>
      <div className="session-nav-docked-top" ref={bar}>
        <button className="session-nav-new" aria-label="新建会话" disabled={props.creating}
          onClick={async () => { if (await saveRename()) { closeMenu(); props.onCreate(); } }}><Plus size={16} />新建会话</button>
        <label className="session-nav-search"><Search size={14} aria-hidden="true" />
          <input ref={search} value={query} placeholder="查找会话" aria-label="查找会话" onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape' && query) { e.stopPropagation(); setQuery(''); } }} /></label>
      </div>
      {rows}
      {props.footer}
    </div> : <>
    <div className="session-nav" ref={bar}>
      <button className="session-nav-trigger" ref={trigger} disabled={props.creating} aria-label="切换会话" aria-haspopup="dialog" aria-expanded={open}
        title={current?.title} onClick={() => { if (open) void closeList(); else setOpen(true); }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); }
          if (e.key === 'F2' && current) { e.preventDefault(); void beginRename(current); }
          if (current && (e.key === 'ContextMenu' || (e.key === 'F10' && e.shiftKey))) { e.preventDefault(); rowMenu(current, e.currentTarget); }
        }}
        onDoubleClick={() => { if (current) void beginRename(current); }}>
        <span>{current?.title ?? (props.loading ? '正在加载会话…' : '新会话')}</span>
        {current && statusIcon(current.id)}
        <ChevronDown size={14} />
      </button>
      <button className="session-nav-icon" aria-label="新建会话" title="新建会话" disabled={props.creating}
        onClick={async () => { if (await saveRename()) { setOpen(false); closeMenu(); props.onCreate(); } }}><Plus size={16} /></button>
      <button className="session-nav-icon" aria-label="会话更多操作" title="会话更多操作" disabled={!current || props.creating}
        onClick={(e) => { if (current) rowMenu(current, e.currentTarget); }}><MoreHorizontal size={17} /></button>
    </div>
    {open && visible && createPortal(<div ref={list} className="session-nav-list" role="dialog" aria-label="会话列表"
      style={{ left: position.left, top: position.top, width: position.width, maxHeight: position.height }}>
      <div className="session-nav-heading">会话 <span>{sessions.length}</span>
        {runningCount > 0 && <span>{runningCount} 个进行中</span>}{waitingCount > 0 && <span>{waitingCount} 个等待执行</span>}</div>
      {rows}
    </div>, document.body)}
    </>}
    {menu && target && visible && createPortal(<>
      <div className="session-nav-menu" role="menu" aria-label={`会话操作：${target.title}`} ref={mainMenu}
        style={{ left: menuX, top: menuY, width: menuWidth }} onKeyDown={menuKeys}>
        {stacked && sub ? subItems : <>
          <button role="menuitem" onMouseEnter={() => setSub(null)} onClick={requestGenerate}
            disabled={!target.canGenerateTitle || target.titleStatus === 'generating' || generatingId === target.id}
            title={!target.canGenerateTitle ? '首轮完整回复保存后可用' : undefined}>
            <Sparkles size={14} />{target.titleStatus === 'generating' ? '正在生成标题…' : ['ai', 'manual'].includes(target.titleSource ?? '') ? '重新生成标题' : '生成会话标题'}</button>
          <button role="menuitem" onMouseEnter={() => setSub(null)} onClick={() => void beginRename(target)}><Pencil size={14} />重命名 <kbd>F2</kbd></button>
          {props.onPin ? <button role="menuitem" onMouseEnter={() => setSub(null)} onClick={() => { closeMenu(); togglePin(target); }}>
            <Pin size={14} />{target.pinnedAt ? '取消置顶' : '置顶'}</button> : null}
          <div role="separator" />
          <button role="menuitem" data-child="copy" aria-haspopup="menu" aria-expanded={sub === 'copy'} onMouseEnter={() => { if (!stacked) setSub('copy'); }} onClick={() => openChild('copy')}><Copy size={14} />复制 <ChevronRight size={13} className="session-nav-chevron" /></button>
          <button role="menuitem" data-child="export" aria-haspopup="menu" aria-expanded={sub === 'export'} onMouseEnter={() => { if (!stacked) setSub('export'); }} onClick={() => openChild('export')}><Download size={14} />导出 <ChevronRight size={13} className="session-nav-chevron" /></button>
          <div role="separator" />
          {busy(target.id) && props.onStop && <button role="menuitem" disabled={runtimeFor(target.id)?.phase === 'stopping'} onClick={() => {
            void props.onStop!(target.id).then(() => closeMenu()).catch((error) => Message.error(errorText(error)));
          }}><Square size={14} />{runtimeFor(target.id)?.phase === 'stopping' ? '正在停止…'
            : '停止回复'}</button>}
          <button role="menuitem" className="session-nav-danger" onMouseEnter={() => setSub(null)}
            disabled={busy(target.id) || !!runtimeFor(target.id)?.queueCount} title={busy(target.id) || runtimeFor(target.id)?.queueCount ? '请先停止回复，并发送或移除待发消息' : undefined}
            onClick={() => { closeMenu(); setConfirm({ kind: 'delete', session: target }); setConfirmError(''); }}><Trash2 size={14} />删除会话</button>
        </>}
      </div>
      {sub && !stacked && <div ref={childMenu} className="session-nav-menu" role="menu" aria-label={sub === 'copy' ? '复制' : '导出'} onKeyDown={menuKeys}
        style={{ left: childX, top: childY, width: menuWidth }}>{subItems}</div>}
    </>, document.body)}
    <Modal visible={confirm !== null} title={shownConfirm?.kind === 'delete' ? '删除会话？' : '替换手动设置的标题？'}
      okText={shownConfirm?.kind === 'delete' ? '删除会话' : '生成标题'} cancelText="取消" wrapStyle={{ zIndex: 1600 }}
      okButtonProps={{ status: shownConfirm?.kind === 'delete' ? 'danger' : undefined }} confirmLoading={confirmBusy}
      cancelButtonProps={{ autoFocus: true, disabled: confirmBusy }} autoFocus={false} focusLock unmountOnExit
      onCancel={() => { if (!confirmBusy) setConfirm(null); }} onOk={async () => {
        if (!confirm || confirmBusy) return;
        setConfirmBusy(true); setConfirmError('');
        try {
          if (confirm.kind === 'delete') {
            const deletingCurrent = confirm.session.id === currentId;
            await props.onDelete(confirm.session.id);
            if (deletingCurrent) setOpen(false);
            Message.success('会话已删除'); setConfirm(null);
          }
          else if (await generate(confirm.session, true)) setConfirm(null);
        } catch (error) { setConfirmError(errorText(error)); }
        finally { setConfirmBusy(false); }
      }}>
      <p className="session-nav-confirm-title">「{shownConfirm?.session.title}」</p>
      <p>{shownConfirm?.kind === 'delete' ? '会话和消息将被删除，且不可恢复。已保存的长期记忆会保留。' : '将根据首次完整问答生成新标题；生成失败会保留原名。'}</p>
      {confirmError && <p className="session-nav-danger" role="alert">{confirmError}</p>}
    </Modal>
    <Modal visible={copyFallback !== null} title="无法自动复制，请手动复制" wrapStyle={{ zIndex: 1600 }} footer={null} onCancel={() => setCopyFallback(null)} unmountOnExit>
      <textarea className="session-nav-copy-fallback" aria-label="待复制内容" value={copyFallback ?? ''} readOnly onFocus={(e) => e.target.select()} autoFocus />
    </Modal>
  </>;
});
