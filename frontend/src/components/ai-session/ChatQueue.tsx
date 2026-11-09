import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ArrowUp, ChevronDown, ChevronUp, MoreHorizontal, Pause, Play, X } from 'lucide-react';
import type { ChatQueueView, QueueItem } from '../../lib/chatQueue';
import { friendlyMessage } from '../../lib/errors';
import './chat-queue.css';

interface Props {
  sessionId?: number;
  view?: ChatQueueView;
  runningId: string | null;
  loadingError?: boolean;
  retry: () => void;
  action: (item: QueueItem, action: string, extra?: Record<string, unknown>) => Promise<ChatQueueView>;
  pause: (value: boolean) => Promise<void>;
}
type Edit = { text: string; keep: string[] };
export function ChatQueue({ sessionId, view, runningId, loadingError, retry, action, pause }: Props): JSX.Element | null {
  const [expanded, setExpanded] = useState(false);
  const [menu, setMenu] = useState<string | null>(null);
  const [detail, setDetail] = useState<string | null>(null);
  const menuAnchor = useRef<HTMLButtonElement | null>(null);
  const menuElement = useRef<HTMLDivElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [undo, setUndo] = useState<QueueItem | null>(null);
  const edits = useRef(new Map<string, Edit>());
  const [, render] = useState(0);
  const currentSession = useRef(sessionId); currentSession.current = sessionId;
  useEffect(() => { setMenu(null); setDetail(null); setError(''); setUndo(null); setExpanded(false); }, [sessionId]);
  useEffect(() => { if (!undo) return; const timer = window.setTimeout(() => setUndo(null), 8000); return () => clearTimeout(timer); }, [undo]);
  useEffect(() => {
    if (!menu) return;
    menuElement.current?.querySelector('button')?.focus();
    const outside = (event: PointerEvent) => {
      if (!menuElement.current?.contains(event.target as Node) && !menuAnchor.current?.contains(event.target as Node)) setMenu(null);
    };
    const close = () => setMenu(null);
    document.addEventListener('pointerdown', outside);
    window.addEventListener('resize', close);
    return () => { document.removeEventListener('pointerdown', outside); window.removeEventListener('resize', close); };
  }, [menu]);
  const perform = async (work: () => Promise<unknown>): Promise<void> => {
    const target = sessionId;
    if (busy) return;
    setBusy(true); setError(''); setMenu(null);
    try { await work(); } catch (reason) { if (currentSession.current === target) setError(friendlyMessage(reason, '操作失败，请重试')); }
    finally { setBusy(false); }
  };
  const items = view?.items ?? [];
  const steering = (view?.steering ?? []).filter((item) => item.state !== 'RECEIVED');
  if (!items.length && !steering.length && !undo && !error && !loadingError) return null;
  const shown = expanded || view?.editingId ? items : items.slice(0, 1);
  const selected = items.find((item) => item.requestId === menu);
  const anchor = menuAnchor.current?.getBoundingClientRect();
  const closeMenu = () => { setMenu(null); menuAnchor.current?.focus(); };
  return <section className="chat-queue" aria-label="待发送消息">
    <header>
      <span>{items.length ? `${view?.paused ? '已暂停' : '待发送'} · ${items.length}` : '插话'}</span>
      <span className="chat-queue-header-actions">
        {items.length > 1 && <button type="button" onClick={() => setExpanded(!expanded)} aria-expanded={expanded} aria-label={expanded ? '收起待发列表' : '展开待发列表'}>{expanded ? <ChevronUp size={13} /> : <ChevronDown size={13} />}</button>}
        {!!items.length && <button type="button" disabled={busy || !!view?.editingId} onClick={() => void perform(() => pause(!view?.paused))}>
          {view?.paused ? <Play size={12} /> : <Pause size={12} />}{view?.paused ? '继续发送' : '暂停接续'}</button>}
      </span>
    </header>
    <div className="chat-queue-items" onScroll={() => setMenu(null)}>
      {shown.map((item) => {
        const editing = view?.editingId === item.requestId;
        const editKey = `${sessionId}:${item.requestId}`;
        const draft = edits.current.get(editKey) ?? { text: item.userText, keep: (item.attachments ?? []).map((a) => a.clientId) };
        const edit = (patch: Partial<Edit>) => { edits.current.set(editKey, { ...draft, ...patch }); render((x) => x + 1); };
        return <div className={`chat-queue-item${editing ? ' is-editing' : ''}`} key={item.requestId}>
          {editing ? <div className="chat-queue-editor">
            <textarea aria-label="编辑待发消息" autoFocus value={draft.text} onChange={(event) => edit({ text: event.target.value })} rows={3}
              onKeyDown={(event) => { if (event.key === 'Escape') { event.stopPropagation(); void perform(async () => { await action(item, 'cancelEdit'); edits.current.delete(editKey); }); } }} />
            <div className="chat-queue-attachments">{item.attachments?.filter((a) => draft.keep.includes(a.clientId)).map((a) => <span key={a.clientId}>{a.name}<button type="button" aria-label={`移除待发附件 ${a.name}`} onClick={() => edit({ keep: draft.keep.filter((id) => id !== a.clientId) })}><X size={12} /></button></span>)}</div>
            <div className="chat-queue-edit-actions"><small>编辑期间暂停接续</small>
              <button type="button" disabled={busy} onClick={() => void perform(async () => { await action(item, 'cancelEdit'); edits.current.delete(editKey); })}>取消</button>
              <button type="button" disabled={busy || !draft.text.trim()} onClick={() => void perform(async () => { await action(item, 'save', { userText: draft.text, keepAttachments: draft.keep }); edits.current.delete(editKey); })}>保存修改</button>
            </div>
          </div> : <>
            <div className={`chat-queue-preview${detail === item.requestId ? ' is-open' : ''}`}>
              <button type="button" className="chat-queue-body" aria-expanded={detail === item.requestId} onClick={() => setDetail(detail === item.requestId ? null : item.requestId)}
                onKeyDown={(event) => { if (event.key === 'Escape') { event.stopPropagation(); setDetail(null); } }}>{item.userText}</button>
              {!!item.attachments?.length && <small>{item.attachments.map((a) => a.name).join(' · ')}</small>}
              {item.context && <small>上下文：{item.context}</small>}
              {item.source === 'tool' && <small>来自会话工具</small>}
              {item.error && <small role="alert">{item.error}</small>}
            </div>
            {item.source === 'human' && <div className="chat-queue-item-actions">
              {runningId && <button type="button" title="提交，但不中断模型运行" aria-label="插话" disabled={busy || !!view?.editingId} onClick={() => void perform(() => action(item, 'steer', { targetRequestId: runningId }))}><ArrowUp size={14} /></button>}
              <button type="button" aria-label="待发消息操作" aria-haspopup="menu" aria-expanded={menu === item.requestId} disabled={busy || !!view?.editingId}
                onClick={(event) => { menuAnchor.current = event.currentTarget; setMenu(menu === item.requestId ? null : item.requestId); }}><MoreHorizontal size={16} /></button>
            </div>}
          </>}
        </div>;
      })}
      {!expanded && !view?.editingId && items.length > 1 && <button className="chat-queue-more" type="button" onClick={() => setExpanded(true)}>展开其余 {items.length - 1} 条</button>}
      {steering.map((item) => <div key={item.requestId} className="chat-queue-steering"><span title={item.userText}>{item.userText}</span><small>{item.state === 'UNKNOWN' ? '接收未知' : '等待模型接收'}</small>
        {item.state === 'UNKNOWN' && <button type="button" disabled={busy} onClick={() => void perform(() => action(item, 'remove'))}>移除</button>}</div>)}
    </div>
    {undo && <div className="chat-queue-undo" role="status">已移除 <button type="button" disabled={busy} onClick={() => void perform(async () => { await action(undo, 'undo'); if (currentSession.current === sessionId) setUndo(null); })}>撤销</button></div>}
    {error && <p role="alert" className="chat-queue-error">{error}</p>}
    {loadingError && <p className="chat-queue-error">待发列表暂时无法读取 <button type="button" onClick={retry}>重试</button></p>}
    {selected && anchor && createPortal(<div className="chat-queue-menu" ref={menuElement} role="menu" aria-label="待发消息操作"
      style={{ position: 'fixed', left: Math.max(8, Math.min(anchor.right - 132, window.innerWidth - 140)), top: Math.max(8, anchor.top - 100) }}
      onKeyDown={(event) => {
        if (event.key === 'Escape') { event.stopPropagation(); closeMenu(); }
        if (event.key === 'Tab') setMenu(null);
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault(); const buttons = Array.from(event.currentTarget.querySelectorAll('button'));
          buttons[(buttons.indexOf(document.activeElement as HTMLButtonElement) + (event.key === 'ArrowDown' ? 1 : buttons.length - 1)) % buttons.length]?.focus();
        }
      }}>
      <button type="button" role="menuitem" onClick={() => void perform(() => action(selected, 'beginEdit'))}>编辑消息</button>
      <button type="button" role="menuitem" onClick={() => void perform(async () => {
        const value = await action(selected, 'remove');
        if (currentSession.current === sessionId) setUndo({ ...selected, revision: value.itemRevision! });
      })}>移除消息</button>
    </div>, document.body)}
  </section>;
}
