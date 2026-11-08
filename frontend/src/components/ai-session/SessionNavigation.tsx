import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { Message, Modal } from '@arco-design/web-react';
import { ArrowLeft, Check, ChevronDown, ChevronRight, Copy, Download, Link, LoaderCircle, MoreHorizontal, Pencil, Plus, Sparkles, Trash2 } from 'lucide-react';
import type { AiChatSession } from '../../lib/types';
import './session-navigation.css';

interface Props {
  visible: boolean;
  sessions: AiChatSession[];
  currentId?: number;
  busyId?: number;
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
}
type MenuTarget = { id: number; x: number; y: number; origin: HTMLElement };
type Edit = { id: number; original: string; draft: string; error?: string };
const errorText = (error: unknown): string => error instanceof Error ? error.message : '操作失败，请重试';
const clamp = (value: number, max: number): number => Math.max(8, Math.min(value, max));

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
export function SessionNavigation(props: Props): JSX.Element {
  const { sessions, currentId, visible } = props;
  const current = sessions.find((s) => s.id === currentId);
  const bar = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const mainMenu = useRef<HTMLDivElement>(null);
  const childMenu = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
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
  const menuY = clamp(menu?.y ?? 8, viewport.height - 224 - 8);
  const stacked = viewport.width < menuWidth * 2 + 24
    || (menuX - 8 < menuWidth && viewport.width - menuX - menuWidth - 8 < menuWidth);
  const childX = menuX + menuWidth * 2 + 8 <= viewport.width ? menuX + menuWidth : menuX - menuWidth;
  const childY = clamp(menuY + (sub === 'copy' ? 82 : 116), viewport.height - (sub === 'copy' ? 84 : 118) - 8);

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

  useLayoutEffect(() => {
    if (!open || !visible) return;
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
    if (open && visible && !editRef.current) list.current?.querySelector<HTMLButtonElement>(`[data-session="${currentId}"]`)?.focus();
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
    if (!visible || (!open && !menu)) return;
    const outside = (event: PointerEvent): void => {
      const node = event.target as Node;
      if (bar.current?.contains(node) || list.current?.contains(node) || mainMenu.current?.contains(node) || childMenu.current?.contains(node)) return;
      void closeList();
    };
    const escape = (event: globalThis.KeyboardEvent): void => {
      if (event.key !== 'Escape' || confirm || copyFallback !== null || editRef.current) return;
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

  return <>
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
        {current?.titleStatus === 'generating' && <LoaderCircle className="session-nav-spin" size={13} aria-label="正在生成标题" />}
        <ChevronDown size={14} />
      </button>
      <button className="session-nav-icon" aria-label="新建会话" title="新建会话" disabled={props.creating}
        onClick={async () => { if (await saveRename()) { setOpen(false); closeMenu(); props.onCreate(); } }}><Plus size={16} /></button>
      <button className="session-nav-icon" aria-label="会话更多操作" title="会话更多操作" disabled={!current || props.creating}
        onClick={(e) => { if (current) rowMenu(current, e.currentTarget); }}><MoreHorizontal size={17} /></button>
    </div>
    {open && visible && createPortal(<div ref={list} className="session-nav-list" role="dialog" aria-label="会话列表"
      style={{ left: position.left, top: position.top, width: position.width, maxHeight: position.height }}>
      <div className="session-nav-heading">会话 <span>{sessions.length}</span></div>
      <div className="session-nav-rows" onScroll={() => closeMenu()}>
        {props.loading ? <p className="session-nav-empty">正在加载会话…</p> : props.error ?
          <div className="session-nav-empty">会话加载失败 <button onClick={props.onRetry}>重试</button></div> :
          sessions.length === 0 ? <div className="session-nav-empty">暂无会话 <button onClick={props.onCreate}>新建会话</button></div> :
          sessions.map((session) => <div key={session.id} className={`session-nav-row${session.id === currentId ? ' is-current' : ''}${session.id === menu?.id ? ' has-menu' : ''}`}
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
                  <span className="session-nav-meta">{session.messageCount ?? 0} 条消息 · {recentTime(session.updatedAt)}</span></span>
                {session.titleStatus === 'generating' && <LoaderCircle size={13} className="session-nav-spin" aria-label="正在生成标题" />}
              </button>
              <button className="session-nav-row-more" aria-label={`更多操作：${session.title}`} onClick={(e) => rowMenu(session, e.currentTarget)}><MoreHorizontal size={16} /></button>
            </>}
          </div>)}
      </div>
    </div>, document.body)}
    {menu && target && visible && createPortal(<>
      <div className="session-nav-menu" role="menu" aria-label={`会话操作：${target.title}`} ref={mainMenu}
        style={{ left: menuX, top: menuY, width: menuWidth }} onKeyDown={menuKeys}>
        {stacked && sub ? subItems : <>
          <button role="menuitem" onMouseEnter={() => setSub(null)} onClick={requestGenerate}
            disabled={!target.canGenerateTitle || target.titleStatus === 'generating' || generatingId === target.id}
            title={!target.canGenerateTitle ? '首轮完整回复保存后可用' : undefined}>
            <Sparkles size={14} />{target.titleStatus === 'generating' ? '正在生成标题…' : ['ai', 'manual'].includes(target.titleSource ?? '') ? '重新生成标题' : '生成会话标题'}</button>
          <button role="menuitem" onMouseEnter={() => setSub(null)} onClick={() => void beginRename(target)}><Pencil size={14} />重命名 <kbd>F2</kbd></button>
          <div role="separator" />
          <button role="menuitem" data-child="copy" aria-haspopup="menu" aria-expanded={sub === 'copy'} onMouseEnter={() => { if (!stacked) setSub('copy'); }} onClick={() => openChild('copy')}><Copy size={14} />复制 <ChevronRight size={13} className="session-nav-chevron" /></button>
          <button role="menuitem" data-child="export" aria-haspopup="menu" aria-expanded={sub === 'export'} onMouseEnter={() => { if (!stacked) setSub('export'); }} onClick={() => openChild('export')}><Download size={14} />导出 <ChevronRight size={13} className="session-nav-chevron" /></button>
          <div role="separator" />
          <button role="menuitem" className="session-nav-danger" onMouseEnter={() => setSub(null)}
            disabled={target.id === props.busyId} title={target.id === props.busyId ? '请先停止正在生成的回复' : undefined}
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
}
