import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Button, Input } from '@arco-design/web-react';
import { ChevronDown } from 'lucide-react';
import { EFFORTS, effortLabel, supportedEfforts, type ModelDraft } from './modelPresets';
import type { ModelPreset, ReasoningControl } from './types';

interface Props { draft: ModelDraft; patch: (value: Partial<ModelDraft>) => void; format: string; busy: boolean; preset?: ModelPreset; restore: () => void }
export function ReasoningSettings({ draft, patch, format, busy, preset, restore }: Props) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState('');
  const [position, setPosition] = useState({ top: 0, left: 0, width: 300, maxHeight: 400 });
  const trigger = useRef<HTMLButtonElement>(null);
  const popup = useRef<HTMLDivElement>(null);
  const searchInput = useRef<HTMLInputElement>(null);
  const supported = supportedEfforts(format);
  const close = (focus = false) => { setOpen(false); if (focus) trigger.current?.focus(); };
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const rect = trigger.current?.getBoundingClientRect(); if (!rect) return;
      const below = window.innerHeight - rect.bottom - 12, above = rect.top - 12;
      const flip = below < 400 && above > below;
      const maxHeight = Math.max(140, Math.min(400, flip ? above : below));
      const height = Math.min(popup.current?.scrollHeight || 400, maxHeight);
      const width = Math.min(Math.max(rect.width, 270), window.innerWidth - 24);
      setPosition({ top: Math.max(12, flip ? rect.top - height - 6 : rect.bottom + 6), left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)), width, maxHeight });
    };
    place();
    window.addEventListener('resize', place); window.addEventListener('scroll', place, true);
    return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
  // 高度限制放宽后按新布局再定位，避免旧尺寸让向上展开的弹层盖住触发器。
  }, [open, search, position.maxHeight, position.width]);
  useLayoutEffect(() => { if (open) searchInput.current?.focus({ preventScroll: true }); }, [open]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (!popup.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) close(); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); } };
    document.addEventListener('pointerdown', outside); window.addEventListener('keydown', escape, true);
    return () => { document.removeEventListener('pointerdown', outside); window.removeEventListener('keydown', escape, true); };
  }, [open]);
  useEffect(() => { if (busy) setOpen(false); }, [busy]);
  const toggle = (level: string, checked: boolean) => {
    const reasoningEfforts = EFFORTS.filter(value => value === level ? checked : draft.reasoningEfforts.includes(value));
    const removed = !!draft.defaultReasoningEffort && !reasoningEfforts.includes(draft.defaultReasoningEffort);
    patch({ reasoningEfforts, ...(removed ? { defaultReasoningEffort: '' } : {}) });
    setNotice(removed ? '已移除默认等级，改用服务默认。' : '');
  };
  const summary = draft.reasoningControl === 'effort' ? effortLabel(draft.reasoningEfforts) : ({ unknown: '未设置', none: '无思考能力', fixed: '由模型决定', budget: '使用预算控制' }[draft.reasoningControl]);
  return <section className="connection-reasoning">
    <h3>思考设置</h3>
    <div className="connection-field"><label id="reasoning-label">思考等级</label>
      <button type="button" className="connection-effort-trigger" ref={trigger} aria-labelledby="reasoning-label reasoning-summary" aria-expanded={open} aria-haspopup="dialog" aria-controls={open ? 'reasoning-popup' : undefined} disabled={busy || draft.reasoningControl !== 'effort'} title={draft.reasoningEfforts.join(', ')} onClick={() => setOpen(value => !value)}><span id="reasoning-summary">{summary}</span><ChevronDown size={15} /></button>
      {draft.reasoningControl === 'effort' && <p className="connection-hint">默认：{draft.defaultReasoningEffort || '自动（服务默认）'}</p>}
      {notice && <p className="connection-hint" role="status">{notice}</p>}
    </div>
    <details className="connection-limits"><summary>高级兼容设置</summary>
      <div className="connection-field"><label htmlFor="reasoning-control">控制方式</label><select id="reasoning-control" className="connection-native-select" value={draft.reasoningControl} disabled={busy} onChange={event => { patch({ reasoningControl: event.target.value as ReasoningControl, defaultReasoningEffort: '', thinkingBudgetTokens: '' }); setNotice(draft.defaultReasoningEffort ? '控制方式已改变，默认等级恢复为服务默认。' : ''); }}>
        <option value="effort">强度档位（effort）</option><option value="budget" disabled={format !== 'anthropic'}>预算控制{format !== 'anthropic' ? '（当前协议未适配）' : ''}</option><option value="fixed">支持思考但不可调</option><option value="none">无思考能力</option><option value="unknown">未知</option>
      </select></div>
      {draft.reasoningControl === 'budget' && <div className="connection-field"><label htmlFor="thinking-budget">思考 Token 预算</label><Input id="thinking-budget" inputMode="numeric" value={draft.thinkingBudgetTokens} disabled={busy} onChange={thinkingBudgetTokens => patch({ thinkingBudgetTokens })} placeholder="留空沿用服务默认" /><p className="connection-hint">至少 1024，且小于本次请求的输出上限。</p></div>}
      {preset && <Button disabled={busy} onClick={() => { const removed = draft.defaultReasoningEffort && !preset.reasoningEfforts.includes(draft.defaultReasoningEffort); restore(); setNotice(removed ? '已恢复预设资料；原默认等级不再可用，改用服务默认。保存后生效。' : '已恢复预设资料，保存后生效。'); }}>恢复预设资料</Button>}
    </details>
    {open && createPortal(<div ref={popup} id="reasoning-popup" role="dialog" aria-label="配置思考等级" className="connection-effort-popup" style={position}>
      <div className="connection-effort-search"><input ref={searchInput} aria-label="搜索思考等级" placeholder="搜索思考等级…" value={search} onChange={event => setSearch(event.target.value)} /></div>
      <div className="connection-effort-options" role="group" aria-label="可用思考等级">
        {EFFORTS.filter(level => level.includes(search.trim().toLowerCase())).map(level => <label className="connection-effort-option" key={level} title={supported.includes(level) ? undefined : '当前协议不支持此等级'}><input type="checkbox" checked={draft.reasoningEfforts.includes(level)} disabled={!supported.includes(level) && !draft.reasoningEfforts.includes(level)} onChange={event => toggle(level, event.target.checked)} /><span>{level}</span>{!supported.includes(level) && <small>当前协议不支持</small>}</label>)}
        {!EFFORTS.some(level => level.includes(search.trim().toLowerCase())) && <p className="connection-hint">没有匹配的等级</p>}
      </div>
      <div className="connection-effort-footer"><label htmlFor="default-reasoning">默认等级</label><select id="default-reasoning" className="connection-native-select" disabled={!draft.reasoningEfforts.length} value={draft.defaultReasoningEffort} onChange={event => { patch({ defaultReasoningEffort: event.target.value }); setNotice(''); }}><option value="">自动（服务默认）</option>{draft.reasoningEfforts.map(level => <option key={level} value={level} disabled={!supported.includes(level)}>{level}</option>)}</select></div>
    </div>, document.body)}
  </section>;
}
